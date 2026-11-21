package com.projectlightpath.analysis.java;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Owner-only temporary storage and a two-tape external merge sort.
 *
 * <p>At most one record per side is live during a merge. The in-memory sort
 * buffer is bounded per {@link Sort} by half of the configured sort memory, and
 * every tape write is charged against the per-archive spill quota before it
 * happens.
 */
final class ServiceLoaderDescriptorSpill implements AutoCloseable {
  static final class SpillLimitException extends IOException {
    SpillLimitException(String message) {
      super(message);
    }
  }

  static final class SpillException extends IOException {
    SpillException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  static final class SpillCleanupException extends IOException {
    SpillCleanupException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private final ServiceLoaderDescriptorArchiveConfig config;
  private final Path root;
  private long live;
  private long peak;
  private long written;
  private long read;
  private int files;
  private int peakFiles;
  private int open;
  private int peakOpen;
  private int created;
  private boolean closed;

  ServiceLoaderDescriptorSpill(ServiceLoaderDescriptorArchiveConfig config) throws IOException {
    this.config = Objects.requireNonNull(config, "config");
    Path parent = config.tempRoot() == null
        ? Path.of(System.getProperty("java.io.tmpdir"))
        : config.tempRoot();
    Path createdRoot;
    try {
      createdRoot = Files.createTempDirectory(
          parent,
          "flpath-5124-spill-",
          PosixFilePermissions.asFileAttribute(
              PosixFilePermissions.fromString("rwx------")));
    } catch (UnsupportedOperationException error) {
      createdRoot = Files.createTempDirectory(parent, "flpath-5124-spill-");
    }
    root = createdRoot;
  }

  Path root() {
    return root;
  }

  long peakLiveBytes() {
    return peak;
  }

  long writtenBytes() {
    return written;
  }

  long readBytes() {
    return read;
  }

  int peakTapeFiles() {
    return peakFiles;
  }

  int createdTapeFiles() {
    return created;
  }

  int openTapeFiles() {
    return open;
  }

  final class Tape implements AutoCloseable {
    private final Path path;
    private final RandomAccessFile file;
    private boolean tapeClosed;

    Tape() throws IOException {
      path = Files.createTempFile(root, "tape-", ".bin");
      files++;
      created++;
      peakFiles = Math.max(peakFiles, files);
      try {
        file = new RandomAccessFile(path.toFile(), "rw");
      } catch (IOException error) {
        Files.deleteIfExists(path);
        files--;
        throw new SpillException("spill tape create failed", error);
      }
      open++;
      peakOpen = Math.max(peakOpen, open);
    }

    void put(byte[] bytes) throws IOException {
      interrupt();
      long grow = Math.max(0, file.getFilePointer() + bytes.length - file.length());
      if (grow > config.maxSpillBytes() - live) {
        throw new SpillLimitException("inline spill quota exceeded");
      }
      long before = file.length();
      try {
        file.write(bytes);
      } catch (IOException error) {
        throw new SpillException("spill tape write failed", error);
      } finally {
        long delta = file.length() - before;
        live += delta;
        peak = Math.max(live, peak);
      }
      written += bytes.length;
    }

    byte[] get(int length) throws IOException {
      interrupt();
      if (length < 0 || length > 1 << 20) {
        throw new SpillException("invalid internal spill record length", null);
      }
      byte[] bytes = new byte[length];
      try {
        file.readFully(bytes);
      } catch (IOException error) {
        throw new SpillException("spill tape read failed", error);
      }
      read += length;
      return bytes;
    }

    void number(long value) throws IOException {
      put(java.nio.ByteBuffer.allocate(8).putLong(value).array());
    }

    long number() throws IOException {
      return java.nio.ByteBuffer.wrap(get(8)).getLong();
    }

    void clear() throws IOException {
      long length = file.length();
      try {
        file.setLength(0);
        file.seek(0);
      } catch (IOException error) {
        throw new SpillException("spill tape clear failed", error);
      }
      live -= length;
    }

    @Override
    public void close() throws IOException {
      if (tapeClosed) {
        return;
      }
      tapeClosed = true;
      long length = 0;
      try {
        length = file.length();
      } catch (IOException ignored) {
        // length is only used for quota accounting; deletion is still attempted
      }
      try {
        file.close();
      } catch (IOException error) {
        throw new SpillException("spill tape close failed", error);
      } finally {
        open--;
        live -= length;
        try {
          config.cleanup().delete(path);
          files--;
        } catch (IOException error) {
          throw new SpillCleanupException("spill tape cleanup failed", error);
        }
      }
    }
  }

  private static void interrupt() throws InterruptedIOException {
    if (Thread.currentThread().isInterrupted()) {
      throw new InterruptedIOException("descriptor scan interrupted");
    }
  }

  static void string(DataOutputStream output, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  static String string(DataInputStream input) throws IOException {
    int length = input.readInt();
    if (length < 0 || length > 262_144) {
      throw new SpillException("invalid internal string length", null);
    }
    byte[] bytes = input.readNBytes(length);
    if (bytes.length != length) {
      throw new SpillException("short internal string", null);
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  static byte[] encode(ServiceLoaderDescriptorZipEntry entry) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      string(output, entry.name());
      output.writeInt(entry.flags());
      output.writeInt(entry.method());
      for (long value : new long[] {
          entry.crc(), entry.compressed(), entry.length(),
          entry.local(), entry.data(), entry.end()}) {
        output.writeLong(value);
      }
      output.writeInt(entry.extraLength());
      string(output, entry.comment());
    }
    return bytes.toByteArray();
  }

  static ServiceLoaderDescriptorZipEntry decode(byte[] bytes) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
      String name = string(input);
      int flags = input.readInt();
      int method = input.readInt();
      long crc = input.readLong();
      long compressed = input.readLong();
      long length = input.readLong();
      long local = input.readLong();
      long data = input.readLong();
      long end = input.readLong();
      int extraLength = input.readInt();
      String comment = string(input);
      return new ServiceLoaderDescriptorZipEntry(
          name, flags, method, crc, compressed, length, local, data, end,
          extraLength, comment);
    }
  }

  final class Sort implements AutoCloseable {
    private final Comparator<ServiceLoaderDescriptorZipEntry> order;
    private final long quota;
    private final long runBytes;
    private final List<ServiceLoaderDescriptorZipEntry> buffer = new ArrayList<>();
    private Tape left;
    private Tape right;
    private long memory;
    private long runs;
    private long remaining;

    Sort(Comparator<ServiceLoaderDescriptorZipEntry> order) throws IOException {
      this.order = order;
      long half = Math.max(1L, config.sortMemoryBytes() / 2L);
      quota = half;
      runBytes = Math.max(1L, Math.min(half, config.mergeRunBytes()));
      left = new Tape();
      try {
        right = new Tape();
      } catch (IOException error) {
        left.close();
        throw error;
      }
    }

    void add(ServiceLoaderDescriptorZipEntry entry) throws IOException {
      interrupt();
      long cost = 256L + entry.name().length() * 2L + entry.comment().length() * 2L;
      if (memory + cost > runBytes && !buffer.isEmpty()) {
        flush();
      }
      if (cost > quota && buffer.isEmpty()) {
        // A single bounded record still has to be sorted; write it as its own run.
        buffer.add(entry);
        flush();
        return;
      }
      buffer.add(entry);
      memory += cost;
    }

    private void write(Tape tape, ServiceLoaderDescriptorZipEntry entry) throws IOException {
      byte[] bytes = encode(entry);
      tape.number(bytes.length);
      tape.put(bytes);
    }

    private ServiceLoaderDescriptorZipEntry read(Tape tape) throws IOException {
      return decode(tape.get(Math.toIntExact(tape.number())));
    }

    private void flush() throws IOException {
      if (buffer.isEmpty()) {
        return;
      }
      interrupt();
      buffer.sort(order);
      long start = left.file.getFilePointer();
      left.number(0);
      left.number(buffer.size());
      for (ServiceLoaderDescriptorZipEntry entry : buffer) {
        write(left, entry);
      }
      long end = left.file.getFilePointer();
      left.file.seek(start);
      left.number(end - start - 16);
      left.file.seek(end);
      buffer.clear();
      memory = 0;
      runs++;
    }

    void finish() throws IOException {
      flush();
      while (runs > 1) {
        interrupt();
        left.file.seek(0);
        right.clear();
        long produced = 0;
        while (left.file.getFilePointer() < left.file.length()) {
          long leftBytes = left.number();
          long leftCount = left.number();
          long leftStart = left.file.getFilePointer();
          long rightHeader = leftStart + leftBytes;
          long rightCount = 0;
          long rightStart = 0;
          long end = rightHeader;
          if (rightHeader < left.file.length()) {
            left.file.seek(rightHeader);
            long rightBytes = left.number();
            rightCount = left.number();
            rightStart = left.file.getFilePointer();
            end = rightStart + rightBytes;
          }
          long start = right.file.getFilePointer();
          right.number(0);
          right.number(leftCount + rightCount);
          ServiceLoaderDescriptorZipEntry leftEntry = null;
          ServiceLoaderDescriptorZipEntry rightEntry = null;
          while (leftCount > 0 || rightCount > 0 || leftEntry != null || rightEntry != null) {
            if (leftEntry == null && leftCount > 0) {
              left.file.seek(leftStart);
              leftEntry = read(left);
              leftStart = left.file.getFilePointer();
              leftCount--;
            }
            if (rightEntry == null && rightCount > 0) {
              left.file.seek(rightStart);
              rightEntry = read(left);
              rightStart = left.file.getFilePointer();
              rightCount--;
            }
            if (rightEntry == null
                || leftEntry != null && order.compare(leftEntry, rightEntry) <= 0) {
              write(right, leftEntry);
              leftEntry = null;
            } else {
              write(right, rightEntry);
              rightEntry = null;
            }
          }
          long stop = right.file.getFilePointer();
          right.file.seek(start);
          right.number(stop - start - 16);
          right.file.seek(stop);
          left.file.seek(end);
          produced++;
        }
        left.clear();
        Tape swap = left;
        left = right;
        right = swap;
        runs = produced;
      }
      left.file.seek(0);
      if (runs > 0) {
        left.number();
        remaining = left.number();
      }
    }

    ServiceLoaderDescriptorZipEntry next() throws IOException {
      interrupt();
      if (remaining == 0) {
        return null;
      }
      remaining--;
      return read(left);
    }

    @Override
    public void close() throws IOException {
      IOException failure = null;
      try {
        left.close();
      } catch (IOException error) {
        failure = error;
      }
      try {
        right.close();
      } catch (IOException error) {
        if (failure == null) {
          failure = error;
        } else {
          failure.addSuppressed(error);
        }
      }
      if (failure != null) {
        throw failure;
      }
    }
  }

  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    IOException failure = null;
    try (var paths = Files.newDirectoryStream(root)) {
      for (Path path : paths) {
        try {
          config.cleanup().delete(path);
        } catch (IOException error) {
          if (failure == null) {
            failure = new SpillCleanupException("spill root cleanup failed", error);
          } else {
            failure.addSuppressed(error);
          }
        }
      }
    } catch (IOException | java.nio.file.DirectoryIteratorException error) {
      Throwable cause = error instanceof java.nio.file.DirectoryIteratorException iterator
          ? iterator.getCause() : error;
      if (failure == null) {
        failure = new SpillCleanupException("spill root read failed", cause);
      } else {
        failure.addSuppressed(cause);
      }
    }
    try {
      config.cleanup().delete(root);
    } catch (IOException error) {
      if (failure == null) {
        failure = new SpillCleanupException("spill root cleanup failed", error);
      } else {
        failure.addSuppressed(error);
      }
    }
    if (failure != null) {
      throw failure;
    }
  }
}
