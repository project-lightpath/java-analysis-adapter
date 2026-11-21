package com.projectlightpath.analysis.java;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Bounded, seekable central-directory reader for one archive. */
final class ServiceLoaderDescriptorZipDirectory implements AutoCloseable {
  static final long U32 = 0xffffffffL;

  static final class ZipFormatException extends IOException {
    ZipFormatException(String message) {
      super(message);
    }

    ZipFormatException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  static final class RawIoLimitException extends IOException {
    RawIoLimitException(String message) {
      super(message);
    }
  }

  @FunctionalInterface
  interface EntryVisitor {
    void visit(ServiceLoaderDescriptorZipEntry entry) throws IOException;
  }

  private final SeekableByteChannel channel;
  private final long size;
  private final ServiceLoaderDescriptorArchiveConfig config;
  private long raw;
  private long cenStart;
  private long cenEnd;
  private long prefix;
  private long expected;

  ServiceLoaderDescriptorZipDirectory(
      SeekableByteChannel channel,
      long size,
      ServiceLoaderDescriptorArchiveConfig config,
      ServiceLoaderDescriptorSource.ScanState state) {
    this.channel = Objects.requireNonNull(channel, "channel");
    this.size = size;
    this.config = Objects.requireNonNull(config, "config");
    Objects.requireNonNull(state, "state");
  }

  private void interrupt() throws InterruptedIOException {
    if (Thread.currentThread().isInterrupted()) {
      throw new InterruptedIOException("descriptor scan interrupted");
    }
  }

  private byte[] read(long position, int length) throws IOException {
    interrupt();
    if (position < 0 || length < 0 || position > size || length > size - position) {
      throw new ZipFormatException("outside_file");
    }
    if (length > config.maxRawDirectoryBytes() - raw) {
      throw new RawIoLimitException("raw directory I/O budget exceeded");
    }
    byte[] bytes = new byte[length];
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    long offset = position;
    while (buffer.hasRemaining()) {
      channel.position(offset);
      int count = channel.read(buffer);
      if (count <= 0) {
        throw new ZipFormatException("short_read");
      }
      offset += count;
      raw += count;
    }
    return bytes;
  }

  private static int u16(byte[] bytes, int position) {
    return (bytes[position] & 255) | ((bytes[position + 1] & 255) << 8);
  }

  private static long u32(byte[] bytes, int position) {
    return Integer.toUnsignedLong(
        ByteBuffer.wrap(bytes, position, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
  }

  private static long u64(byte[] bytes, int position) throws IOException {
    if (position < 0 || position > bytes.length - 8) {
      throw new ZipFormatException("short_zip64");
    }
    long value = ByteBuffer.wrap(bytes, position, 8)
        .order(ByteOrder.LITTLE_ENDIAN).getLong();
    if (value < 0) {
      throw new ZipFormatException("unsigned_zip64_outside_seekable_range");
    }
    return value;
  }

  private static long add(long left, long right) throws IOException {
    if (left < 0 || right < 0 || left > Long.MAX_VALUE - right) {
      throw new ZipFormatException("offset_overflow");
    }
    return left + right;
  }

  private static void require(boolean condition, String code) throws IOException {
    if (!condition) {
      throw new ZipFormatException(code);
    }
  }

  private String decode(byte[] bytes, boolean utf8) throws IOException {
    if (!utf8) {
      return new String(bytes, StandardCharsets.UTF_8);
    }
    CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException error) {
      throw new ZipFormatException("invalid_utf8", error);
    }
  }

  private void directory() throws IOException {
    int length = (int) Math.min(size, 65_557);
    byte[] tail = read(size - length, length);
    int eocd = -1;
    for (int index = length - 22; index >= 0; index--) {
      if (u32(tail, index) == 0x06054b50L
          && index + 22 + u16(tail, index + 20) == length) {
        eocd = index;
        break;
      }
    }
    require(eocd >= 0, "missing_or_truncated_eocd");
    require(u16(tail, eocd + 4) == 0 && u16(tail, eocd + 6) == 0, "unsupported_multidisk");
    long end = size - length + eocd;
    long endCen = end;
    expected = u16(tail, eocd + 10);
    long bytes = u32(tail, eocd + 12);
    long offset = u32(tail, eocd + 16);
    require(u16(tail, eocd + 8) == expected, "eocd_count_mismatch");
    boolean sentinel = bytes == U32 || offset == U32;
    byte[] locator = end >= 20 ? read(end - 20, 20) : new byte[20];
    if (u32(locator, 0) == 0x07064b50L) {
      require(u32(locator, 4) == 0 && u32(locator, 16) == 1, "unsupported_multidisk");
      long zip64Position = u64(locator, 8);
      byte[] zip64 = read(zip64Position, 56);
      require(u32(zip64, 0) == 0x06064b50L,
          "invalid_zip64_or_unsupported_prefixed_zip64");
      long record = u64(zip64, 4);
      require(record >= 44 && add(zip64Position, add(12, record)) == end - 20,
          "zip64_record_bounds");
      require(u32(zip64, 16) == 0 && u32(zip64, 20) == 0, "unsupported_multidisk");
      long count = u64(zip64, 32);
      long zip64Bytes = u64(zip64, 40);
      long zip64Offset = u64(zip64, 48);
      require(u64(zip64, 24) == count, "zip64_count_mismatch");
      require((expected == 65_535 || expected == count)
              && (bytes == U32 || bytes == zip64Bytes)
              && (offset == U32 || offset == zip64Offset),
          "zip64_eocd_mismatch");
      expected = count;
      bytes = zip64Bytes;
      offset = zip64Offset;
      endCen = zip64Position;
    } else {
      require(!sentinel, "missing_zip64_locator");
    }
    require(bytes <= endCen && offset <= endCen - bytes, "cen_bounds");
    cenStart = endCen - bytes;
    cenEnd = endCen;
    prefix = cenStart - offset;
    require(expected <= bytes / 46, "impossible_entry_count");
  }

  static long[] extra(
      byte[] extra, long length, long compressed, long offset, long disk)
      throws IOException {
    boolean needLength = length == U32;
    boolean needCompressed = compressed == U32;
    boolean needOffset = offset == U32;
    boolean needDisk = disk == 65_535;
    boolean found = false;
    long expectedLength = (needLength ? 8 : 0)
        + (needCompressed ? 8 : 0)
        + (needOffset ? 8 : 0)
        + (needDisk ? 4 : 0);
    int position = 0;
    while (position + 4 <= extra.length) {
      int tag = u16(extra, position);
      int blockLength = u16(extra, position + 2);
      position += 4;
      if (blockLength > extra.length - position) {
        // JDK ZipFile tolerates opaque/trailing extra bytes; only the ZIP64
        // field is semantically interpreted by this reader.
        break;
      }
      int cursor = position;
      int end = position + blockLength;
      if (tag == 1) {
        require(!found, "duplicate_zip64_extra");
        found = true;
        require(blockLength == expectedLength
                || blockLength == 0 && expectedLength == 0,
            "invalid_zip64_extra_block_length");
        if (needLength) {
          require(cursor + 8 <= end, "short_zip64");
          length = u64(extra, cursor);
          cursor += 8;
        }
        if (needCompressed) {
          require(cursor + 8 <= end, "short_zip64");
          compressed = u64(extra, cursor);
          cursor += 8;
        }
        if (needOffset) {
          require(cursor + 8 <= end, "short_zip64");
          offset = u64(extra, cursor);
          cursor += 8;
        }
        if (needDisk) {
          require(cursor + 4 <= end, "short_zip64");
          disk = u32(extra, cursor);
        }
      }
      position = end;
    }
    require(!(needLength || needCompressed || needOffset || needDisk) || found,
        "missing_zip64_extra");
    return new long[] {length, compressed, offset, disk};
  }

  private boolean descriptorMatches(
      long position, int skip, int width, long crc, long compressed, long length)
      throws IOException {
    int need = 4 + 2 * width;
    if (position < 0 || position + skip + need > cenStart) {
      return false;
    }
    byte[] data = read(position + skip, need);
    long actualCrc = u32(data, 0);
    long actualCompressed = width == 8 ? u64(data, 4) : u32(data, 4);
    long actualLength = width == 8 ? u64(data, 12) : u32(data, 8);
    return actualCrc == crc && actualCompressed == compressed && actualLength == length;
  }

  private ServiceLoaderDescriptorZipEntry entry(long position) throws IOException {
    interrupt();
    require(position <= cenEnd - 46, "short_cen_header");
    byte[] header = read(position, 46);
    require(u32(header, 0) == 0x02014b50L, "invalid_cen_signature");
    int nameLength = u16(header, 28);
    int extraLength = u16(header, 30);
    int commentLength = u16(header, 32);
    int flags = u16(header, 8);
    int method = u16(header, 10);
    require(add(position, 46L + nameLength + extraLength + commentLength) <= cenEnd,
        "cen_record_bounds");
    require((flags & ~(0x800 | 8 | 6)) == 0, "unsupported_flags_or_encryption");
    require(method == 0 || method == 8, "unsupported_method");
    boolean utf8 = (flags & 0x800) != 0;
    byte[] nameBytes = read(position + 46, nameLength);
    String name = decode(nameBytes, utf8);
    require(nameLength > 0 && name.indexOf('\0') < 0, "invalid_name");
    byte[] extra = read(position + 46 + nameLength, extraLength);
    byte[] commentBytes = read(position + 46 + nameLength + extraLength, commentLength);
    String comment = decode(commentBytes, utf8);
    long[] fields = extra(
        extra, u32(header, 24), u32(header, 20), u32(header, 42), u16(header, 34));
    require(fields[3] == 0, "unsupported_multidisk");
    long local = add(prefix, fields[2]);
    require(local <= cenStart - 30, "local_header_bounds");
    byte[] localHeader = read(local, 30);
    require(u32(localHeader, 0) == 0x04034b50L, "invalid_local_signature");
    require(u16(localHeader, 6) == flags && u16(localHeader, 8) == method,
        "local_cen_flags_method_mismatch");
    int localNameLength = u16(localHeader, 26);
    int localExtraLength = u16(localHeader, 28);
    long data = add(local, 30L + localNameLength + localExtraLength);
    long end = add(data, fields[1]);
    require(end <= cenStart, "payload_overlaps_cen");
    require(Arrays.equals(nameBytes, read(local + 30, localNameLength)),
        "local_cen_name_mismatch");
    byte[] localExtra = read(local + 30 + localNameLength, localExtraLength);
    long[] localFields = extra(
        localExtra, u32(localHeader, 22), u32(localHeader, 18), 0, 0);
    long crc = u32(header, 16);
    if ((flags & 8) == 0) {
      require(u32(localHeader, 14) == crc
              && localFields[0] == fields[0]
              && localFields[1] == fields[1],
          "local_cen_sizes_crc_mismatch");
    } else {
      boolean wide = u32(localHeader, 18) == U32
          || u32(localHeader, 22) == U32
          || fields[0] >= U32
          || fields[1] >= U32;
      int width = wide ? 8 : 4;
      int need = 4 + 2 * width;
      boolean signature = end + 4 <= cenStart && u32(read(end, 4), 0) == 0x08074b50L;
      boolean accepted = false;
      if (signature) {
        accepted = descriptorMatches(end, 4, width, crc, fields[1], fields[0]);
        if (accepted) {
          end = add(end, 4L + need);
        }
      }
      if (!accepted) {
        accepted = descriptorMatches(end, 0, width, crc, fields[1], fields[0]);
        if (accepted) {
          end = add(end, need);
        }
      }
      require(accepted, "data_descriptor_mismatch");
    }
    require(method != 0 || fields[0] == fields[1], "stored_sizes_mismatch");
    return new ServiceLoaderDescriptorZipEntry(
        name, flags, method, crc, fields[1], fields[0], local, data, end,
        extraLength, comment);
  }

  private long next(long position) throws IOException {
    byte[] header = read(position + 28, 6);
    return add(position, 46L + u16(header, 0) + u16(header, 2) + u16(header, 4));
  }

  void walk(EntryVisitor visitor) throws IOException {
    directory();
    long position = cenStart;
    long count = 0;
    while (position < cenEnd) {
      ServiceLoaderDescriptorZipEntry entry = entry(position);
      visitor.visit(entry);
      count++;
      position = next(position);
    }
    require(position == cenEnd && count == expected, "cen_count_mismatch");
  }

  InputStream stream(
      ServiceLoaderDescriptorZipEntry entry,
      ServiceLoaderDescriptorSource.ScanState scanState,
      String path) {
    return new EntryStream(entry, scanState, path);
  }

  private final class EntryStream extends InputStream {
    private final ServiceLoaderDescriptorZipEntry entry;
    private final ServiceLoaderDescriptorSource.ScanState scanState;
    private final String path;
    private final byte[] output = new byte[8192];
    private final CRC32 crc = new CRC32();
    private Inflater inflater;
    private int available;
    private long position;
    private long remaining;
    private long produced;
    private boolean finished;

    EntryStream(
        ServiceLoaderDescriptorZipEntry entry,
        ServiceLoaderDescriptorSource.ScanState scanState,
        String path) {
      this.entry = entry;
      this.scanState = scanState;
      this.path = path;
      position = entry.data();
      remaining = entry.compressed();
      if (entry.deflated()) {
        inflater = new Inflater(true);
      }
    }

    private void chargeUnreturned(long bytes) {
      if (bytes <= 0) {
        return;
      }
      long allowed = Math.min(bytes, scanState.remainingDecompressed());
      if (allowed > 0) {
        scanState.consumeDecompressed(allowed, path);
      }
    }

    private boolean fill() throws IOException {
      if (available > 0) {
        return true;
      }
      if (finished) {
        return false;
      }
      interrupt();
      if (inflater == null) {
        if (remaining == 0) {
          finishEntry();
          return false;
        }
        int take = (int) Math.min(output.length, remaining);
        byte[] bytes = ServiceLoaderDescriptorZipDirectory.this.read(position, take);
        position += take;
        remaining -= take;
        produced += take;
        crc.update(bytes);
        System.arraycopy(bytes, 0, output, 0, take);
        available = take;
        return true;
      }
      while (available == 0) {
        if (inflater.finished()) {
          finishEntry();
          return false;
        }
        if (inflater.needsInput()) {
          if (remaining == 0) {
            throw new ZipFormatException("truncated_deflate");
          }
          int take = (int) Math.min(8192, remaining);
          byte[] bytes = ServiceLoaderDescriptorZipDirectory.this.read(position, take);
          position += take;
          remaining -= take;
          inflater.setInput(bytes);
        }
        long written = inflater.getBytesWritten();
        int count;
        try {
          count = inflater.inflate(output, 0, output.length);
        } catch (DataFormatException error) {
          chargeUnreturned(inflater.getBytesWritten() - written);
          throw new ZipFormatException("invalid_deflate", error);
        }
        if (count > 0) {
          produced += count;
          crc.update(output, 0, count);
          available = count;
          continue;
        }
        if (!inflater.finished() && !inflater.needsInput()) {
          throw new ZipFormatException("invalid_deflate_progress");
        }
      }
      return true;
    }

    private void finishEntry() throws IOException {
      if (finished) {
        return;
      }
      finished = true;
      if (inflater != null) {
        if (inflater.getBytesRead() != entry.compressed()) {
          throw new ZipFormatException("compressed_size_mismatch");
        }
      }
      if (produced != entry.length() || crc.getValue() != entry.crc()) {
        throw new ZipFormatException("selected_size_or_crc_mismatch");
      }
    }

    @Override
    public int read() throws IOException {
      if (!fill()) {
        return -1;
      }
      int value = output[0] & 255;
      System.arraycopy(output, 1, output, 0, --available);
      return value;
    }

    @Override
    public int read(byte[] target, int offset, int length) throws IOException {
      if (length == 0) {
        return 0;
      }
      if (!fill()) {
        return -1;
      }
      int count = Math.min(length, available);
      System.arraycopy(output, 0, target, offset, count);
      available -= count;
      return count;
    }

    @Override
    public void close() {
      if (inflater != null) {
        inflater.end();
        inflater = null;
      }
    }
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }
}
