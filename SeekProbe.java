package com.projectlightpath.analysis.java;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.lang.management.*;
import java.util.*;
import java.util.zip.*;

/** EXPERIMENT ONLY. Custom security-sensitive parser; NOT production reviewed.
 * All experiment budgets below are lab controls, not proposed product defaults.
 * No whole-directory allocation. Names/order/ranges/descriptors use external spill.
 * All finite default controls below are LAB controls, NOT production policy.
 */
public final class SeekProbe implements AutoCloseable {
  static final long U32=0xffffffffL;
  final FileChannel file;
  final long size, rawMax=Long.getLong("spike.rawMax",64L<<20);
  final long directoryMax=Long.getLong("spike.directoryMax",64L<<20);
  boolean readingDirectory=true;
  long raw, seeks, count, expanded, nameBytes, cenStart, cenEnd, prefix, expected, validationLines;
  final Spill spill;
  Spill.Sort selected, ranges;
  long selectedCount,commentBytes,directoryRead;
  record Entry(String name,int flags,int method,long crc,long compressed,long length,
      long local,long data,long end) {}
  static IOException bad(String code) { return new IOException(code); }
  static void require(boolean yes,String code) throws IOException { if(!yes) throw bad(code); }
  static long add(long a,long b) throws IOException {
    require(a>=0 && b>=0 && a<=Long.MAX_VALUE-b,"offset_overflow"); return a+b;
  }
  static int u16(byte[] a,int p) { return (a[p]&255)|((a[p+1]&255)<<8); }
  static long u32(byte[] a,int p) { return Integer.toUnsignedLong(ByteBuffer.wrap(a,p,4).order(ByteOrder.LITTLE_ENDIAN).getInt()); }
  static long u64(byte[] a,int p) throws IOException {
    require(p>=0 && p<=a.length-8,"short_zip64");
    long n=ByteBuffer.wrap(a,p,8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    require(n>=0,"unsigned_zip64_outside_seekable_range"); return n;
  }
  byte[] read(long pos,int n) throws IOException {
    require(pos>=0 && n>=0 && pos<=size && n<=size-pos,"outside_file");
    require(n<=rawMax-raw,"experimental_raw_io_budget");
    require(!readingDirectory || n<=directoryMax-raw,"experimental_directory_io_budget");
    byte[] a=new byte[n]; ByteBuffer b=ByteBuffer.wrap(a); seeks++;
    while(b.hasRemaining()) { int r=file.read(b,pos+b.position()); require(r>0,"short_read"); raw+=r; }
    return a;
  }
  static String utf8(byte[] a) throws IOException {
    return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(a)).toString();
  }
  SeekProbe(Path path,Spill spill) throws IOException {
    this.spill=spill; file=FileChannel.open(path,StandardOpenOption.READ); size=file.size();
  }
  // Same strict UTF-8 charset as default ZipFile, regardless of EFS bit. Entry
  // comments are decoded when entries() materializes each ZipEntry, not EOCD.
  void comment(long pos,int n) throws IOException {
    if(n==0)return;
    CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
    ByteBuffer input=ByteBuffer.allocate(8196);CharBuffer output=CharBuffer.allocate(8192);
    while(n>0) {
      int take=Math.min(8192,n);input.put(read(pos,take));pos+=take;n-=take;commentBytes+=take;
      input.flip();
      for(;;) {output.clear();CoderResult r=decoder.decode(input,output,n==0);
        if(r.isError())r.throwException();if(r.isUnderflow())break;}
      input.compact();
    }
    require(input.position()==0,"truncated_comment_utf8");
  }
  void directory() throws IOException {
    int len=(int)Math.min(size,65557); byte[] tail=read(size-len,len); int e=-1;
    for(int i=len-22;i>=0;i--) if(u32(tail,i)==0x06054b50L && i+22+u16(tail,i+20)==len) { e=i; break; }
    require(e>=0,"missing_or_truncated_eocd");
    require(u16(tail,e+4)==0 && u16(tail,e+6)==0,"unsupported_multidisk");
    long end=size-len+e, endCen=end;
    expected=u16(tail,e+10); long bytes=u32(tail,e+12), offset=u32(tail,e+16);
    require(u16(tail,e+8)==expected,"eocd_count_mismatch");
    // 65535 is a valid classic entry count, not a ZIP64 sentinel by itself: only
    // size/offset sentinels, or an actual locator, introduce ZIP64 here. The walk
    // still re-validates the real count against the directory bounds.
    boolean sentinel=bytes==U32 || offset==U32;
    byte[] loc=end>=20?read(end-20,20):new byte[20];
    if(u32(loc,0)==0x07064b50L) {
      require(u32(loc,4)==0 && u32(loc,16)==1,"unsupported_multidisk");
      long zpos=u64(loc,8); byte[] z=read(zpos,56);
      require(u32(z,0)==0x06064b50L,"invalid_zip64_or_unsupported_prefixed_zip64");
      long record=u64(z,4); require(record>=44 && add(zpos,add(12,record))==end-20,"zip64_record_bounds");
      require(u32(z,16)==0 && u32(z,20)==0,"unsupported_multidisk");
      long zcount=u64(z,32), zbytes=u64(z,40), zoffset=u64(z,48);
      require(u64(z,24)==zcount,"zip64_count_mismatch");
      require((expected==65535 || expected==zcount) && (bytes==U32 || bytes==zbytes)
          && (offset==U32 || offset==zoffset),"zip64_eocd_mismatch");
      expected=zcount; bytes=zbytes; offset=zoffset; endCen=zpos;
    } else require(!sentinel,"missing_zip64_locator");
    require(bytes<=endCen && offset<=endCen-bytes,"cen_bounds");
    cenStart=endCen-bytes; cenEnd=endCen; prefix=cenStart-offset;
    require(expected<=bytes/46,"impossible_entry_count");
  }
  // ZIP64 fields are present only for sentinel fields, in specification order.
  static long[] extra(byte[] x,long length,long compressed,long offset,long disk) throws IOException {
    boolean need=length==U32 || compressed==U32 || offset==U32 || disk==65535, found=false;
    int p=0;
    while(p<x.length) {
      require(x.length-p>=4,"truncated_extra"); int tag=u16(x,p), n=u16(x,p+2); p+=4;
      require(n<=x.length-p,"extra_bounds"); int q=p, end=p+n;
      if(tag==1) {
        require(!found,"duplicate_zip64_extra"); found=true;
        require(n==0 || n==8 || n==16 || n==24 || n==28,"invalid_zip64_extra_block_length");
        if(length==U32) { require(q+8<=end,"short_zip64"); length=u64(x,q); q+=8; }
        if(compressed==U32) { require(q+8<=end,"short_zip64"); compressed=u64(x,q); q+=8; }
        if(offset==U32) { require(q+8<=end,"short_zip64"); offset=u64(x,q); q+=8; }
        if(disk==65535) { require(q+4<=end,"short_zip64"); disk=u32(x,q); }
      }
      p=end;
    }
    require(!need || found,"missing_zip64_extra"); return new long[]{length,compressed,offset,disk};
  }
  Entry entry(long pos) throws IOException {
    require(pos<=cenEnd-46,"short_cen_header"); byte[] h=read(pos,46);
    require(u32(h,0)==0x02014b50L,"invalid_cen_signature");
    int nl=u16(h,28), xl=u16(h,30), cl=u16(h,32), flags=u16(h,8), method=u16(h,10);
    require(add(pos,46L+nl+xl+cl)<=cenEnd,"cen_record_bounds");
    require((flags & ~(0x800|8|6))==0,"unsupported_flags_or_encryption");
    require(method==0 || method==8,"unsupported_method");
    byte[] name=read(pos+46,nl); String n=utf8(name);
    require(nl>0 && n.indexOf('\0')<0,"invalid_name");
    byte[] x=read(pos+46+nl,xl);
    comment(pos+46+nl+xl,cl);
    long[] fields=extra(x,u32(h,24),u32(h,20),u32(h,42),u16(h,34));
    require(fields[3]==0,"unsupported_multidisk");
    long local=add(prefix,fields[2]); require(local<=cenStart-30,"local_header_bounds");
    byte[] l=read(local,30);
    require(u32(l,0)==0x04034b50L,"invalid_local_signature");
    require(u16(l,6)==flags && u16(l,8)==method,"local_cen_flags_method_mismatch");
    int lnl=u16(l,26), lxl=u16(l,28);
    long data=add(local,30L+lnl+lxl), end=add(data,fields[1]);
    require(end<=cenStart,"payload_overlaps_cen");
    require(Arrays.equals(name,read(local+30,lnl)),"local_cen_name_mismatch");
    byte[] lx=read(local+30+lnl,lxl);
    long[] lf=extra(lx,u32(l,22),u32(l,18),0,0);
    long crc=u32(h,16);
    if((flags&8)==0) {
      require(u32(l,14)==crc && lf[0]==fields[0] && lf[1]==fields[1],"local_cen_sizes_crc_mismatch");
    } else {
      boolean wide=u32(l,18)==U32 || u32(l,22)==U32 || fields[0]>=U32 || fields[1]>=U32;
      int width=wide?8:4;
      require(end<=cenStart-4,"descriptor_bounds"); byte[] first=read(end,4);
      int skip=u32(first,0)==0x08074b50L?4:0;
      require(add(end,skip+4L+2*width)<=cenStart,"descriptor_bounds");
      byte[] dd=read(end+skip,4+2*width);
      require(u32(dd,0)==crc && (wide?u64(dd,4):u32(dd,4))==fields[1]
          && (wide?u64(dd,12):u32(dd,8))==fields[0],"data_descriptor_mismatch");
      end=add(end,skip+4L+2*width);
    }
    require(method!=0 || fields[0]==fields[1],"stored_sizes_mismatch");
    return new Entry(n,flags,method,crc,fields[1],fields[0],local,data,end);
  }
  long next(long p) throws IOException {
    byte[] h=read(p+28,6); return add(p,46L+u16(h,0)+u16(h,2)+u16(h,4));
  }
  void walk(Path path,ServiceLoaderDescriptorSource.ScanState state,boolean policy) throws IOException {
    selected=spill.new Sort(Comparator.comparing(Entry::name).thenComparingLong(Entry::local));
    ranges=spill.new Sort(Comparator.comparingLong(Entry::local).thenComparingLong(Entry::end));
    directory(); long p=cenStart;
    while(p<cenEnd) {
      Entry e=entry(p); count++;
      if(policy && !state.tryEnumerate(path+"!/"+e.name)) throw bad("policy_enumeration_incomplete");
      boolean service=e.name.startsWith("META-INF/services/") && !e.name.endsWith("/");
      boolean mod=e.name.equals("module-info.class");
      if(!e.name.endsWith("/") && ServiceLoaderDescriptorNestedArchiveSource.isWar(path))
        ServiceLoaderDescriptorNestedArchiveSource.diagnoseSkippedEntry(e.name,path,state);
      if(service || mod) {
        // Bound all selected names, even invalid service names, before regex parsing.
        require(e.name.length()<=1024,"experimental_selected_name_validation_budget");
        nameBytes+=e.name.length()*2L;
        selected.add(e);selectedCount++;
      }
      // All referenced LOC/header/payload/descriptor ranges, including ignored.
      ranges.add(new Entry("",0,0,0,0,0,e.local,e.data,e.end));
      p=next(p);
    }
    require(p==cenEnd && count==expected,"cen_count_mismatch");
    ranges.finish();Entry prior=null,e;
    while((e=ranges.next())!=null){require(prior==null || prior.end<=e.local,"referenced_entry_overlap");prior=e;}
    selected.finish();
    String last=null;
    while((e=selected.next())!=null){require(!e.name.equals(last),"duplicate_selected_name");last=e.name;}
    selected.rewind();
    directoryRead=raw;
    readingDirectory=false;
  }
  void charge(long bytes,ServiceLoaderDescriptorSource.ScanState state,String label) throws IOException {
    expanded+=bytes;
    long remaining=state.remainingDecompressed();
    state.consumeDecompressed(Math.min(bytes,remaining),label);
    // Like production readEntry, at most one sentinel byte beyond the final
    // expansion allowance can be observed; the allowance remains exhausted.
    require(bytes<=remaining,"archive_decompression_limit");
  }
  byte[] body(Entry e,ServiceLoaderDescriptorSource.ScanState state,String label) throws IOException {
    long maximum=state.remainingDecompressed();
    require(maximum>0,"archive_decompression_limit");
    require(e.length<=maximum,"archive_decompression_limit");
    ByteArrayOutputStream out=new ByteArrayOutputStream(8192); CRC32 crc=new CRC32();
    byte[] output=new byte[8192]; long pos=e.data, remaining=e.compressed, got=0;
    Inflater inf=e.method==8?new Inflater(true):null;
    try {
      if(inf==null) {
        while(remaining>0) {
          int n=(int)Math.min(Math.min(8192,remaining),maximum-got+1);
          byte[] a=read(pos,n); pos+=n; remaining-=n; got+=n;charge(n,state,label);
          require(got<=maximum,"archive_decompression_limit"); out.write(a); crc.update(a);
        }
      } else {
        while(!inf.finished()) {
          if(inf.needsInput()) {
            require(remaining>0,"truncated_deflate"); int n=(int)Math.min(8192,remaining);
            byte[] a=read(pos,n); pos+=n; remaining-=n; inf.setInput(a);
          }
          long written=inf.getBytesWritten();
          int n;
          try { n=inf.inflate(output,0,(int)Math.min(output.length,maximum-got+1)); }
          catch(DataFormatException error) {
            // inflate() can emit output before throwing; the getBytesWritten()
            // delta charges those produced bytes exactly once, preserving the
            // exhausted-budget, sentinel-byte and rollback semantics.
            long produced=inf.getBytesWritten()-written;
            got+=produced;charge(produced,state,label);require(got<=maximum,"archive_decompression_limit");
            throw bad("invalid_deflate");
          }
          got+=n;charge(n,state,label);require(got<=maximum,"archive_decompression_limit");
          out.write(output,0,n); crc.update(output,0,n);
          require(n>0 || inf.finished() || inf.needsInput(),"invalid_deflate_progress");
        }
        require(inf.getBytesRead()==e.compressed,"compressed_size_mismatch");
      }
      require(got==e.length && crc.getValue()==e.crc,"selected_size_or_crc_mismatch");
      return out.toByteArray();
    } finally { if(inf!=null) inf.end(); }
  }
  void scan(Path path,List<ServiceLoaderDescriptorScanner.Descriptor> out,
      ServiceLoaderDescriptorSource.ScanState state,boolean policy) throws IOException {
    walk(path,state,policy);
    // Main takes a disk checkpoint and rolls it back on any incomplete archive.
    Entry e;
    String previous=null;
    while((e=selected.next())!=null) {
      require(!e.name.equals(previous),"duplicate_selected_name");previous=e.name;
      if(e.name.equals("module-info.class")) {
        require(e.length<=Long.getLong("spike.entryMax",1L<<20),"experimental_per_entry_materialization_limit");
        byte[] b=body(e,state,path+"!/module-info.class");
        ServiceLoaderDescriptorSource.addModuleBytes(b,path+"!/module-info.class",out,state);continue;
      }
      String type=e.name.substring(18);
      if(!ServiceLoaderDescriptorSource.BINARY_CLASS_NAME.matcher(type).matches()) continue;
      require(e.length<=Long.getLong("spike.entryMax",1L<<20),"experimental_per_entry_materialization_limit");
      byte[] b=body(e,state,path+"!/"+e.name);
      // Avoid the known unrelated recursive regex failure in current production helpers.
      String text=new String(b,StandardCharsets.UTF_8);
      long lines=text.lines().count(); validationLines+=lines;
      require(text.lines().allMatch(line->line.length()<=1024)
           && lines<=1024,"experimental_provider_validation_budget");
      var parsed=ServiceLoaderDescriptorSource.providers(b);
      out.add(new ServiceLoaderDescriptorScanner.Descriptor(type,parsed.providers(),
          path+"!/"+e.name,ServiceLoaderDescriptorSource.sha256(b),"meta_inf_services",parsed.malformedEntries()));
    }
  }
  public void close() throws IOException {
    try{file.close();}finally{try{if(selected!=null)selected.close();}finally{if(ranges!=null)ranges.close();}}
  }
  // Independent ZipFile metadata reference with ONLY the enumeration policy removed.
  // This is deliberately not used as the implementation: ZipFile eagerly indexes CEN.
  static void zipReference(Path path,List<ServiceLoaderDescriptorScanner.Descriptor> out,
      ServiceLoaderDescriptorSource.ScanState state) throws IOException {
    try(ZipFile z=new ZipFile(path.toFile())) {
      TreeMap<String,ZipEntry> services=new TreeMap<>(); ZipEntry module=null;
      var entries=z.entries();
      while(entries.hasMoreElements()) {
        ZipEntry e=entries.nextElement();
        if(e.isDirectory()) continue;
        if(e.getName().startsWith("META-INF/services/")) services.putIfAbsent(e.getName(),e);
        if(e.getName().equals("module-info.class") && module==null) module=e;
      }
      for(var e:services.values()) {
        String type=e.getName().substring(18);
        if(!ServiceLoaderDescriptorSource.BINARY_CLASS_NAME.matcher(type).matches()) continue;
        try(InputStream in=z.getInputStream(e)) {
          byte[] b=ServiceLoaderDescriptorArchiveSource.readEntry(in,state,path+"!/"+e.getName());
          if(b==null) continue;
          var parsed=ServiceLoaderDescriptorSource.providers(b);
          out.add(new ServiceLoaderDescriptorScanner.Descriptor(type,parsed.providers(),path+"!/"+e.getName(),
              ServiceLoaderDescriptorSource.sha256(b),"meta_inf_services",parsed.malformedEntries()));
        }
      }
      if(module!=null) try(InputStream in=z.getInputStream(module)) {
        byte[] b=ServiceLoaderDescriptorArchiveSource.readEntry(in,state,path+"!/module-info.class");
        if(b!=null) ServiceLoaderDescriptorSource.addModuleBytes(b,path+"!/module-info.class",out,state);
      }
    }
  }
  static String certainty(List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      ServiceLoaderDescriptorSource.ScanState state) throws Exception {
    Path root=Files.createTempDirectory(Path.of(System.getProperty("spike.root")),"certainty-");
    try {
      Path source=Files.createDirectories(root.resolve("src/main/java/demo"));
      Path classes=Files.createDirectories(root.resolve("classes"));
      Files.writeString(source.resolve("App.java"),"package demo;\nimport java.util.ServiceLoader;\n"
          +"class App { void call() { ServiceLoader.load(Provider.class).findFirst()"
          +".ifPresent(p -> p.record()); } }\n");
      Files.writeString(source.resolve("Provider.java"),"package demo; interface Provider { void record(); }");
      var context=AnalysisContext.builder().id("spike").framework(AnalysisContext.Framework.QUARKUS)
          .frameworkVersion("3.40.1").fixtureId("spike").javaRelease(17).repositoryRoot(root)
          .applicationClasses(classes).selectedProfile("test").analysisScope(AnalysisContext.Scope.PRODUCTION)
          .sourceRoot("src/main/java",root.resolve("src/main/java"),AnalysisContext.Scope.PRODUCTION,true)
          .dependencyClasspathRequired(false).provenance(AnalysisContext.Provenance.forTesting()).build();
      var edge=new CallEdge(new MethodSymbol("demo.App","call","()V"),
          new MethodSymbol("demo.DefaultProvider","record","()V"),3,"src/main/java/demo/App.java",
          SemanticCallFacts.Certainty.DEFINITE,"service_loader");
      var scan=new ServiceLoaderDescriptorScanner.Scan(descriptors,state.diagnostics());
      return "R certainty="+ServiceLoaderDescriptorScanner.resolve(context,edge,scan,
          new SourceMetadataScanner(context)).certainty();
    } finally {
      try(var paths=Files.walk(root)) {
        for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
      }
    }
  }
  public static void main(String[] args) throws Exception {
    String mode=args[0]; long metadata=Long.parseLong(args[1]);
    var state=new ServiceLoaderDescriptorSource.ScanState(metadata);
    try(Spill spill=new Spill();Spill.Descriptors result=spill.new Descriptors()) {
    long start=System.nanoTime(), raw=0,seeks=0,count=0,expanded=0,selected=0,comments=0,cenBytes=0,directoryRead=0; int opened=0,closed=0;
    for(int i=2;i<args.length;i++) {
      Path path=Path.of(args[i]).toAbsolutePath().normalize();
      if(mode.equals("zip")) { ServiceLoaderDescriptorArchiveSource.scan(path,result,state); continue; }
      if(mode.equals("zip-reference")) { zipReference(path,result,state); continue; }
      SeekProbe probe=null;
      int checkpoint=result.size();long checkpointBytes=result.mark();
      try {
        probe=new SeekProbe(path,spill); opened++;
        probe.scan(path,result,state,mode.equals("seek-policy"));
      } catch(IOException | UncheckedIOException error) {
        result.rollback(checkpoint,checkpointBytes);
        if(!"policy_enumeration_incomplete".equals(error.getMessage()))
          state.diagnostic("seek_incomplete",path.toString(),error.getMessage());
      } finally {
         if(probe!=null) { raw+=probe.raw; seeks+=probe.seeks; count+=probe.count; expanded+=probe.expanded;
           selected+=probe.selectedCount;comments+=probe.commentBytes;
           cenBytes+=probe.cenEnd-probe.cenStart;directoryRead+=probe.directoryRead;
          probe.close(); closed++; }
      }
    }
    double scanMs=(System.nanoTime()-start)/1e6;
    String resolution=null;
    // Do not expose any evidence until every stored descriptor has replayed
    // successfully into a quota-accounted transport transcript. A result-store
    // read or transcript write failure aborts the whole result transaction.
    try(Spill.Tape transcript=spill.new Tape()) {
      try {
        for(var d:result)transcript.put(("D\t"+d+"\n").getBytes(StandardCharsets.UTF_8));
        if(Boolean.getBoolean("spike.certainty"))resolution=certainty(result,state);
      }catch(IOException | UncheckedIOException error) {
        transcript.clear();result.rollback(0,0);
        state.diagnostic("seek_result_incomplete","result transaction",error.getMessage());
        if(Boolean.getBoolean("spike.certainty"))resolution=certainty(List.of(),state);
      }
      // This is transport, not metadata parsing. A real transport I/O failure
      // has no success trailer and the consumer must discard the transaction.
      transcript.f.seek(0);byte[] chunk=new byte[8192];int n;
      while((n=transcript.f.read(chunk))>0)System.out.write(chunk,0,n);
      require(!System.out.checkError(),"evidence_transport_failure");
    }
    for(var d:state.diagnostics()) System.out.println("E\t"+d);
    if(resolution!=null)System.out.println(resolution);
    long peak=0;
    for(var pool:ManagementFactory.getMemoryPoolMXBeans())
      if(pool.getType()==MemoryType.HEAP) peak+=pool.getPeakUsage().getUsed();
    System.out.printf(Locale.ROOT,"S ms=%.3f raw=%d seeks=%d cenEntries=%d expanded=%d descriptors=%d diagnostics=%d heapPoolPeakSum=%d heapMax=%d opened=%d closed=%d%n",
        scanMs,raw,seeks,count,expanded,result.size(),state.diagnostics().size(),peak,Runtime.getRuntime().maxMemory(),opened,closed);
    System.out.printf(Locale.ROOT,"T selected=%d commentsRead=%d cenBytes=%d directoryRead=%d spillLive=%d spillPeak=%d spillWritten=%d spillRead=%d tempCreated=%d tempFiles=%d peakTempFiles=%d openSpill=%d peakOpenSpill=%d root=%s%n",
        selected,comments,cenBytes,directoryRead,spill.live,spill.peak,spill.written,spill.read,spill.created,spill.files,spill.peakFiles,spill.open,spill.peakOpen,spill.root);
    }
  }
}
