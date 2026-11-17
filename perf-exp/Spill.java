package com.projectlightpath.analysis.java;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** LAB ONLY. Two-tape external merge sort, opaque paths, shared live-byte quota.
 * No file-per-entry/run and no in-memory run manifest. Max two records during
 * merging; initial runs hold at most 256KiB estimated record/object storage.
 */
final class Spill implements AutoCloseable {
  final Path root;
  final long max=Long.getLong("spike.spillMax",128L<<20);
  long live,peak,written,read,files,peakFiles,open,peakOpen,created;
  final long failWrite=Long.getLong("spike.failWriteAfter",Long.MAX_VALUE);
  final long failRead=Long.getLong("spike.failSpillReadAfter",Long.MAX_VALUE);
  Spill() throws IOException {
    root=Files.createTempDirectory(Path.of(Objects.requireNonNull(System.getProperty("spike.tempRoot"),"explicit external spike.tempRoot required")),"seek-spill-");
  }
  final class Tape implements AutoCloseable {
    final Path path; final RandomAccessFile f; boolean closed;
    Tape() throws IOException {
      path=Files.createTempFile(root,"opaque-",".bin"); files++; created++; peakFiles=Math.max(files,peakFiles);
      try {f=new RandomAccessFile(path.toFile(),"rw");}
      catch(IOException e){Files.delete(path);files--;throw e;}
      open++; peakOpen=Math.max(open,peakOpen);
    }
    void put(byte[] b) throws IOException {
      long grow=Math.max(0,f.getFilePointer()+b.length-f.length());
      SeekProbe.require(grow<=max-live,"experimental_spill_quota");
      SeekProbe.require(b.length<=failWrite-written,"injected_spill_write_failure");
      // Reserve before write; length is reconciled even after a partial OS write.
      long before=f.length();
      try { f.write(b); } finally { long delta=f.length()-before; live+=delta; peak=Math.max(live,peak); }
      written+=b.length;
    }
    byte[] get(int n) throws IOException {
      SeekProbe.require(n>=0 && n<=1<<20,"invalid_internal_record_length");
      SeekProbe.require(n<=failRead-read,"injected_spill_read_failure");
      byte[] b=new byte[n]; f.readFully(b); read+=n; return b;
    }
    void number(long n) throws IOException { put(java.nio.ByteBuffer.allocate(8).putLong(n).array()); }
    long number() throws IOException { return java.nio.ByteBuffer.wrap(get(8)).getLong(); }
    void clear() throws IOException { long n=f.length(); f.setLength(0); f.seek(0); live-=n; }
    void truncate(long n) throws IOException { long before=f.length(); f.setLength(n); f.seek(n); live-=before-n; }
    public void close() throws IOException {
      if(closed)return; closed=true;
      long n=f.length(); try { f.close(); } finally { open--; Files.delete(path); files--; live-=n; }
    }
  }
  static void string(DataOutputStream d,String s) throws IOException {
    byte[] b=s.getBytes(StandardCharsets.UTF_8); d.writeInt(b.length); d.write(b);
  }
  static String string(DataInputStream d) throws IOException {
    int n=d.readInt(); SeekProbe.require(n>=0 && n<=262144,"internal_string_bounds");
    byte[] b=d.readNBytes(n); SeekProbe.require(b.length==n,"internal_short_string");
    return new String(b,StandardCharsets.UTF_8);
  }
  static byte[] encode(SeekProbe.Entry e) throws IOException {
    var b=new ByteArrayOutputStream(); var d=new DataOutputStream(b); string(d,e.name());
    d.writeInt(e.flags());d.writeInt(e.method());
    for(long n:new long[]{e.crc(),e.compressed(),e.length(),e.local(),e.data(),e.end()}) d.writeLong(n);
    return b.toByteArray();
  }
  static SeekProbe.Entry decode(byte[] b) throws IOException {
    var d=new DataInputStream(new ByteArrayInputStream(b));
    return new SeekProbe.Entry(string(d),d.readInt(),d.readInt(),d.readLong(),d.readLong(),d.readLong(),d.readLong(),d.readLong(),d.readLong());
  }
  final class Sort implements AutoCloseable {
    Tape a,b;
    final Comparator<SeekProbe.Entry> order;
    final List<SeekProbe.Entry> buffer=new ArrayList<>(); long memory,runs,remaining;
    // PERF EXPERIMENT (defaults preserve current behavior exactly):
    // spike.runBytes sizes initial merge runs (256KiB today); spike.sortMemory
    // (0=off) keeps the whole sort in heap when the estimated record cost fits.
    final long sortMemory=Long.getLong("spike.sortMemory",0);
    final long runBytes=Long.getLong("spike.runBytes",262144);
    boolean spilled, inMemory; int cursor;
    Sort(Comparator<SeekProbe.Entry> c) throws IOException {
      order=c;a=new Tape();try{b=new Tape();}catch(IOException e){a.close();throw e;}
    }
    void add(SeekProbe.Entry e) throws IOException {
      long cost=256L+e.name().length()*2L;
      if(sortMemory>0 && !spilled) {
        if(memory+cost>sortMemory) { flush(); spilled=true; }
      } else if(memory+cost>runBytes && !buffer.isEmpty()) flush();
      buffer.add(e); memory+=cost;
    }
    void write(Tape t,SeekProbe.Entry e) throws IOException {
      byte[] x=encode(e); t.number(x.length); t.put(x);
    }
    SeekProbe.Entry read(Tape t) throws IOException { return decode(t.get(Math.toIntExact(t.number()))); }
    void flush() throws IOException {
      if(buffer.isEmpty())return; buffer.sort(order); long start=a.f.getFilePointer();
      a.number(0); a.number(buffer.size()); for(var e:buffer)write(a,e);
      long end=a.f.getFilePointer(); a.f.seek(start); a.number(end-start-16); a.f.seek(end);
      buffer.clear();memory=0;runs++;
    }
    void finish() throws IOException {
      inMemory=sortMemory>0 && !spilled;
      if(inMemory) { buffer.sort(order); runs=0; remaining=buffer.size(); cursor=0; return; }
      flush();
      while(runs>1) {
        a.f.seek(0);b.clear();long produced=0;
        while(a.f.getFilePointer()<a.f.length()) {
          long leftBytes=a.number(), ln=a.number(), left=a.f.getFilePointer(), rightHeader=left+leftBytes;
          long rn=0,right=0,end=rightHeader;
          if(rightHeader<a.f.length()) {a.f.seek(rightHeader);long rb=a.number();rn=a.number();right=a.f.getFilePointer();end=right+rb;}
          long start=b.f.getFilePointer();b.number(0);b.number(ln+rn);
          SeekProbe.Entry le=null,re=null;
          while(ln>0 || rn>0 || le!=null || re!=null) {
            if(le==null && ln>0){a.f.seek(left);le=read(a);left=a.f.getFilePointer();ln--;}
            if(re==null && rn>0){a.f.seek(right);re=read(a);right=a.f.getFilePointer();rn--;}
            if(re==null || le!=null && order.compare(le,re)<=0){write(b,le);le=null;}
            else {write(b,re);re=null;}
          }
          long stop=b.f.getFilePointer();b.f.seek(start);b.number(stop-start-16);b.f.seek(stop);
          a.f.seek(end);produced++;
        }
        a.clear();Tape t=a;a=b;b=t;runs=produced;
      }
      a.f.seek(0); if(runs>0){a.number();remaining=a.number();}
    }
    SeekProbe.Entry next() throws IOException {
      if(remaining==0)return null;remaining--;
      if(inMemory)return buffer.get(cursor++);
      return read(a);
    }
    void rewind() throws IOException {
      if(inMemory){cursor=0;remaining=buffer.size();return;}
      a.f.seek(0);remaining=0;if(runs>0){a.number();remaining=a.number();}
    }
    public void close() throws IOException {try{a.close();}finally{b.close();}}
  }
  /** Disk-backed descriptor List: fixed-width offset index, no per-entry heap index.
   * Unchanged production Scan(List) still copies: only call certainty on small lab cases.
   */
  final class Descriptors extends AbstractList<ServiceLoaderDescriptorScanner.Descriptor> implements AutoCloseable {
    final Tape data,index; int count;
    Descriptors() throws IOException {data=new Tape();try{index=new Tape();}catch(IOException e){data.close();throw e;}}
    public int size(){return count;}
    long mark() throws IOException {return data.f.length();}
    void rollback(int n,long bytes) throws IOException {count=n;index.truncate(n*8L);data.truncate(bytes);}
    public boolean add(ServiceLoaderDescriptorScanner.Descriptor v) {
      try {
        SeekProbe.require(count<Integer.MAX_VALUE,"experimental_descriptor_list_capacity");
        var b=new ByteArrayOutputStream();var d=new DataOutputStream(b);
        string(d,v.serviceType());string(d,v.path());string(d,v.fingerprint());string(d,v.kind());
        for(var list:List.of(v.providers(),v.malformedEntries())){d.writeInt(list.size());for(String s:list)string(d,s);}
        byte[] x=b.toByteArray();long offset=data.f.length();data.f.seek(offset);data.number(x.length);data.put(x);
        index.f.seek(index.f.length());index.number(offset);count++;return true;
      }catch(IOException e){throw new UncheckedIOException(e);}
    }
    public ServiceLoaderDescriptorScanner.Descriptor get(int i) {
      Objects.checkIndex(i,count);
      try {
        index.f.seek(i*8L);data.f.seek(index.number());var d=new DataInputStream(new ByteArrayInputStream(data.get(Math.toIntExact(data.number()))));
        String type=string(d),path=string(d),sha=string(d),kind=string(d);
        List<List<String>> lists=new ArrayList<>();
        for(int k=0;k<2;k++){int n=d.readInt();List<String> l=new ArrayList<>();for(int j=0;j<n;j++)l.add(string(d));lists.add(l);}
        return new ServiceLoaderDescriptorScanner.Descriptor(type,lists.get(0),path,sha,kind,lists.get(1));
      }catch(IOException e){throw new UncheckedIOException(e);}
    }
    public void close() throws IOException {try{data.close();}finally{index.close();}}
  }
  public void close() throws IOException {
    // Constant file count; also recovers partially constructed sorts/tapes.
    try(var paths=Files.newDirectoryStream(root)){for(Path p:paths)Files.delete(p);}
    Files.delete(root);
  }
}
