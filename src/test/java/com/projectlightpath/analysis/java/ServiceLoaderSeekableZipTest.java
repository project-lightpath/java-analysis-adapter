package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** FLPATH-5124: seekable archive reader parity, bounds and lifetime. */
class ServiceLoaderSeekableZipTest {
  private static final String SERVICE = "META-INF/services/demo.Provider";

  @TempDir Path temp;

  @Test
  void eocdCountMismatchIsRejectedWithoutDescriptors() throws IOException {
    Path archive = temp.resolve("count-mismatch.jar");
    writeZip(archive, List.of(entry(SERVICE, "demo.Impl\n"), entry("noise/a", "x")));
    patchEocd(archive, 3, null);

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorArchiveSource.scan(archive, descriptors, state);

    assertTrue(descriptors.isEmpty(), descriptors.toString());
    assertTrue(state.diagnostics().stream()
        .anyMatch(item -> item.code().equals("invalid_archive")), state.diagnostics().toString());
  }

  @Test
  void prefixedClassicArchiveIsAccepted() throws IOException {
    Path archive = temp.resolve("prefixed.jar");
    writeZip(archive, List.of(entry(SERVICE, "demo.Impl\n")));
    byte[] original = Files.readAllBytes(archive);
    byte[] prefixed = new byte[original.length + 7];
    System.arraycopy(original, 0, prefixed, 7, original.length);
    Files.write(archive, prefixed);

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorArchiveSource.scan(archive, descriptors, state);

    assertEquals(1, descriptors.size(), state.diagnostics().toString());
    assertEquals(List.of("demo.Impl"), descriptors.get(0).providers());
  }

  @Test
  void efsClearNonAsciiNameIsDecodedLenientlyLikeZipFile() throws IOException {
    Path archive = temp.resolve("legacy-name.jar");
    writeZip(archive, List.of(entry("noise/\u00e9", "x"), entry(SERVICE, "demo.Impl\n")));
    clearEfsFlags(archive);

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorArchiveSource.scan(archive, descriptors, state);

    assertEquals(1, descriptors.size(), state.diagnostics().toString());
    assertEquals("demo.Provider", descriptors.get(0).serviceType());
  }

  @Test
  void validCorpusMatchesJdkZipFileReference() throws IOException {
    Path archive = temp.resolve("parity.jar");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      put(output, SERVICE, "demo.A\ndemo.B\n");
      ZipEntry stored = new ZipEntry("META-INF/services/demo.Stored");
      stored.setComment("comment-\u00e9");
      output.putNextEntry(stored);
      output.write("demo.C\n".getBytes(StandardCharsets.UTF_8));
      output.closeEntry();
      put(output, "noise/dir/", "");
    }

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorArchiveSource.scan(archive, descriptors, state);
    assertTrue(state.diagnostics().isEmpty(), state.diagnostics().toString());

    List<String> expected = new ArrayList<>();
    try (ZipFile reference = new ZipFile(archive.toFile())) {
      reference.stream()
          .filter(item -> !item.isDirectory()
              && item.getName().startsWith("META-INF/services/"))
          .forEach(item -> expected.add(item.getName()));
    }
    assertEquals(expected, descriptors.stream().map(item -> item.path()
        .substring(item.path().indexOf("!/") + 2)).toList());
  }

  @Test
  void dataDescriptorWithAndWithoutSignatureIsAccepted() throws IOException {
    for (boolean signature : new boolean[] {true, false}) {
      Path archive = temp.resolve("descriptor-" + signature + ".jar");
      writeDataDescriptor(archive, signature);
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
      ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
      ServiceLoaderDescriptorArchiveSource.scan(archive, descriptors, state);
      assertEquals(1, descriptors.size(), state.diagnostics().toString());
      assertEquals(List.of("demo.Impl"), descriptors.get(0).providers());
    }
  }

  @Test
  void encryptedEntryIsRejected() throws IOException {
    Path archive = temp.resolve("encrypted.jar");
    writeZip(archive, List.of(entry(SERVICE, "demo.Impl\n")));
    patchFlags(archive, 1);
    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorArchiveSource.scan(archive, descriptors, state);
    assertTrue(descriptors.isEmpty());
    assertTrue(state.diagnostics().stream()
        .anyMatch(item -> item.code().equals("invalid_archive")), state.diagnostics().toString());
  }

  @Test
  void malformedEfsNameIsRejected() throws IOException {
    Path archive = temp.resolve("bad-utf8.jar");
    writeZip(archive, List.of(entry(SERVICE, "demo.Impl\n")));
    setEfsAndCorruptName(archive);
    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorArchiveSource.scan(archive, descriptors, state);
    assertTrue(descriptors.isEmpty());
    assertTrue(state.diagnostics().stream()
        .anyMatch(item -> item.code().equals("invalid_archive")), state.diagnostics().toString());
  }

  @Test
  void exceptionalDeflateOutputIsChargedExactlyOnce() throws IOException {
    Path archive = temp.resolve("bad-deflate.jar");
    writeMalformedDeflate(archive, 4096);

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state =
        new ServiceLoaderDescriptorSource.ScanState(4096);
    ServiceLoaderDescriptorArchiveSource.scan(archive, descriptors, state);

    assertTrue(descriptors.isEmpty(), descriptors.toString());
    assertEquals(0L, state.remainingDecompressed(), state.diagnostics().toString());
    assertTrue(state.diagnostics().stream()
        .anyMatch(item -> item.code().equals("archive_entry_read_error")),
        state.diagnostics().toString());
  }

  @Test
  void interruptRollsBackAndLeavesNoSpillResidue() throws IOException {
    Path archive = temp.resolve("interrupt.jar");
    writeZip(archive, List.of(entry(SERVICE, "demo.Impl\n")));
    Path spillRoot = Files.createDirectory(temp.resolve("spill"));

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
    Thread.currentThread().interrupt();
    try {
      ServiceLoaderDescriptorArchiveSource.scan(
          archive, descriptors, state,
          ServiceLoaderDescriptorArchiveConfig.defaults().withTempRoot(spillRoot));
    } finally {
      Thread.interrupted();
    }

    assertTrue(descriptors.isEmpty(), descriptors.toString());
    assertTrue(state.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_scan_interrupted")),
        state.diagnostics().toString());
    assertTrue(empty(spillRoot), "spill root not empty");
  }

  @Test
  void interruptDuringEntryReadRollsBackAndClearsTheFlag() throws IOException {
    Path archive = temp.resolve("interrupt-payload.jar");
    StringBuilder payload = new StringBuilder();
    for (int index = 0; index < 20_000; index++) {
      payload.append("demo.Impl\n");
    }
    writeZip(archive, List.of(storedEntry(SERVICE, payload.toString())));
    Path spillRoot = Files.createDirectory(temp.resolve("interrupt-payload-spill"));

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorArchiveSource.scan(
        archive, descriptors, state,
        ServiceLoaderDescriptorArchiveConfig.defaults()
            .withTempRoot(spillRoot)
            .withChannelOpener(path -> new InterruptingChannel(path, 12)));

    assertTrue(descriptors.isEmpty(), descriptors.toString());
    assertTrue(state.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_scan_interrupted")),
        state.diagnostics().toString());
    assertFalse(Thread.currentThread().isInterrupted());
    assertTrue(empty(spillRoot), "spill root not empty");
  }

  private static final class InterruptingChannel implements SeekableByteChannel {
    private final SeekableByteChannel delegate;
    private final int interruptAt;
    private int reads;

    InterruptingChannel(Path path, int interruptAt) throws IOException {
      delegate = Files.newByteChannel(path, StandardOpenOption.READ);
      this.interruptAt = interruptAt;
    }

    @Override
    public int read(ByteBuffer destination) throws IOException {
      reads++;
      if (reads == interruptAt) {
        Thread.currentThread().interrupt();
      }
      return delegate.read(destination);
    }

    @Override
    public int write(ByteBuffer source) throws IOException {
      return delegate.write(source);
    }

    @Override
    public long position() throws IOException {
      return delegate.position();
    }

    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
      delegate.position(newPosition);
      return this;
    }

    @Override
    public long size() throws IOException {
      return delegate.size();
    }

    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
      delegate.truncate(size);
      return this;
    }

    @Override
    public boolean isOpen() {
      return delegate.isOpen();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }

  @Test
  void spillQuotaFailureLeavesNoPartialResultAndNoResidue() throws IOException {
    Path archive = temp.resolve("spill-limit.jar");
    writeZip(archive, List.of(entry(SERVICE, "demo.Impl\n"), entry("noise/a", "x")));
    Path spillRoot = Files.createDirectory(temp.resolve("spill-limit"));

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorArchiveSource.scan(
        archive, descriptors, state,
        ServiceLoaderDescriptorArchiveConfig.defaults()
            .withTempRoot(spillRoot)
            .withSortMemoryBytes(1)
            .withMaxSpillBytes(1));

    assertTrue(descriptors.isEmpty(), descriptors.toString());
    assertTrue(state.diagnostics().stream()
        .anyMatch(item -> item.code().equals("archive_spill_limit")),
        state.diagnostics().toString());
    assertTrue(empty(spillRoot), "spill root not empty");
  }

  @Test
  void successfulScanLeavesNoSpillResidue() throws IOException {
    Path archive = temp.resolve("cleanup.jar");
    writeZip(archive, List.of(entry(SERVICE, "demo.Impl\n")));
    Path spillRoot = Files.createDirectory(temp.resolve("cleanup"));

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorArchiveSource.scan(
        archive, descriptors, state,
        ServiceLoaderDescriptorArchiveConfig.defaults().withTempRoot(spillRoot));

    assertEquals(1, descriptors.size(), state.diagnostics().toString());
    assertTrue(empty(spillRoot), "spill root not empty");
  }

  @Test
  void retainedNameBudgetIsBoundedAndReleased() throws IOException {
    Path archive = temp.resolve("long-name.jar");
    String longName = "META-INF/services/demo." + "A".repeat(200);
    writeZip(archive, List.of(entry(longName, "demo.Impl\n")));

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state = ServiceLoaderDescriptorSource.ScanState
        .withBudgets(16L * 1024 * 1024, 2L * 1024 * 1024 * 1024,
            ServiceLoaderDescriptorBudget.MAX_RETAINED_DESCRIPTOR_BYTES, 10);
    ServiceLoaderDescriptorArchiveSource.scan(archive, descriptors, state);

    assertTrue(descriptors.isEmpty(), descriptors.toString());
    assertTrue(state.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_retention_limit")),
        state.diagnostics().toString());
    assertEquals(0L, state.retainedBytes());
    assertEquals(0L, state.retainedNameBytes());
  }

  @Test
  void mutatingChannelIsRejectedAndCleanedUp() throws IOException {
    Path archive = temp.resolve("mutation.jar");
    writeZip(archive, List.of(entry(SERVICE, "demo.Impl\n")));
    Path spillRoot = Files.createDirectory(temp.resolve("mutation-spill"));

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorArchiveSource.scan(
        archive, descriptors, state,
        ServiceLoaderDescriptorArchiveConfig.defaults()
            .withTempRoot(spillRoot)
            .withChannelOpener(path -> new FailingChannel(path)));

    assertTrue(descriptors.isEmpty(), descriptors.toString());
    assertTrue(state.diagnostics().stream().anyMatch(item ->
        item.code().equals("invalid_archive")
            || item.code().equals("archive_read_error")), state.diagnostics().toString());
    assertTrue(empty(spillRoot), "spill root not empty");
  }

  private static final class FailingChannel implements SeekableByteChannel {
    private final SeekableByteChannel delegate;
    private boolean failed;

    FailingChannel(Path path) throws IOException {
      delegate = Files.newByteChannel(path, StandardOpenOption.READ);
    }

    @Override
    public int read(ByteBuffer destination) throws IOException {
      if (failed) {
        return -1;
      }
      failed = true;
      return delegate.read(destination);
    }

    @Override
    public int write(ByteBuffer source) throws IOException {
      return delegate.write(source);
    }

    @Override
    public long position() throws IOException {
      return delegate.position();
    }

    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
      delegate.position(newPosition);
      return this;
    }

    @Override
    public long size() throws IOException {
      return delegate.size();
    }

    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
      delegate.truncate(size);
      return this;
    }

    @Override
    public boolean isOpen() {
      return delegate.isOpen();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }

  private record Pair(String name, byte[] bytes, boolean stored) {}

  private static Pair entry(String name, String value) {
    return new Pair(name, value.getBytes(StandardCharsets.UTF_8), false);
  }

  private static Pair storedEntry(String name, String value) {
    return new Pair(name, value.getBytes(StandardCharsets.UTF_8), true);
  }

  private static void writeZip(Path archive, List<Pair> entries) throws IOException {
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      for (Pair entry : entries) {
        if (entry.stored()) {
          CRC32 crc = new CRC32();
          crc.update(entry.bytes());
          ZipEntry stored = new ZipEntry(entry.name());
          stored.setMethod(ZipEntry.STORED);
          stored.setSize(entry.bytes().length);
          stored.setCompressedSize(entry.bytes().length);
          stored.setCrc(crc.getValue());
          output.putNextEntry(stored);
          output.write(entry.bytes());
          output.closeEntry();
        } else {
          put(output, entry.name(), new String(entry.bytes(), StandardCharsets.UTF_8));
        }
      }
    }
  }

  private static void put(ZipOutputStream output, String name, String value) throws IOException {
    output.putNextEntry(new ZipEntry(name));
    output.write(value.getBytes(StandardCharsets.UTF_8));
    output.closeEntry();
  }

  private static boolean empty(Path directory) throws IOException {
    try (var paths = Files.list(directory)) {
      return paths.findAny().isEmpty();
    }
  }

  private static void patchEocd(Path archive, int count, Integer bytes) throws IOException {
    byte[] data = Files.readAllBytes(archive);
    int eocd = lastIndexOf(data, new byte[] {0x50, 0x4b, 0x05, 0x06});
    putLeShort(data, eocd + 8, count);
    putLeShort(data, eocd + 10, count);
    if (bytes != null) {
      putLeInt(data, eocd + 12, bytes);
    }
    Files.write(archive, data);
  }

  private static void clearEfsFlags(Path archive) throws IOException {
    byte[] data = Files.readAllBytes(archive);
    int position = 0;
    while ((position = indexOf(data, new byte[] {0x50, 0x4b, 0x03, 0x04}, position)) >= 0) {
      putLeShort(data, position + 6, readLeShort(data, position + 6) & ~0x800);
      position += 4;
    }
    position = 0;
    while ((position = indexOf(data, new byte[] {0x50, 0x4b, 0x01, 0x02}, position)) >= 0) {
      putLeShort(data, position + 8, readLeShort(data, position + 8) & ~0x800);
      position += 4;
    }
    Files.write(archive, data);
  }

  private static void writeDataDescriptor(Path archive, boolean signature) throws IOException {
    byte[] data = "demo.Impl\n".getBytes(StandardCharsets.UTF_8);
    byte[] name = SERVICE.getBytes(StandardCharsets.UTF_8);
    CRC32 crc = new CRC32();
    crc.update(data);
    ByteArrayOutputStream file = new ByteArrayOutputStream();
    ByteBuffer local = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
    local.putInt(0x04034b50).putShort((short) 20).putShort((short) 8)
        .putShort((short) 0).putShort((short) 0).putShort((short) 0)
        .putInt(0).putInt(0).putInt(0)
        .putShort((short) name.length).putShort((short) 0);
    file.write(local.array());
    file.write(name);
    file.write(data);
    if (signature) {
      file.write(new byte[] {0x50, 0x4b, 0x07, 0x08});
    }
    file.write(intLe((int) crc.getValue()));
    file.write(intLe(data.length));
    file.write(intLe(data.length));
    int cenOffset = file.size();
    ByteBuffer cen = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
    cen.putInt(0x02014b50).putShort((short) 20).putShort((short) 20)
        .putShort((short) 8).putShort((short) 0).putShort((short) 0)
        .putShort((short) 0).putInt((int) crc.getValue())
        .putInt(data.length).putInt(data.length)
        .putShort((short) name.length).putShort((short) 0).putShort((short) 0)
        .putShort((short) 0).putShort((short) 0).putInt(0).putInt(0);
    file.write(cen.array());
    file.write(name);
    writeEocd(file, file.size() - cenOffset, cenOffset);
    Files.write(archive, file.toByteArray());
  }

  private static byte[] intLe(int value) {
    return new byte[] {
        (byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)};
  }

  private static void patchFlags(Path archive, int extraBits) throws IOException {
    byte[] data = Files.readAllBytes(archive);
    int position = 0;
    while ((position = indexOf(data, new byte[] {0x50, 0x4b, 0x03, 0x04}, position)) >= 0) {
      putLeShort(data, position + 6, readLeShort(data, position + 6) | extraBits);
      position += 4;
    }
    position = 0;
    while ((position = indexOf(data, new byte[] {0x50, 0x4b, 0x01, 0x02}, position)) >= 0) {
      putLeShort(data, position + 8, readLeShort(data, position + 8) | extraBits);
      position += 4;
    }
    Files.write(archive, data);
  }

  private static void setEfsAndCorruptName(Path archive) throws IOException {
    byte[] data = Files.readAllBytes(archive);
    int local = indexOf(data, new byte[] {0x50, 0x4b, 0x03, 0x04}, 0);
    putLeShort(data, local + 6, readLeShort(data, local + 6) | 0x800);
    data[local + 30] = (byte) 0xff;
    int cen = indexOf(data, new byte[] {0x50, 0x4b, 0x01, 0x02}, 0);
    putLeShort(data, cen + 8, readLeShort(data, cen + 8) | 0x800);
    data[cen + 46] = (byte) 0xff;
    Files.write(archive, data);
  }

  private static void writeMalformedDeflate(Path archive, int produced) throws IOException {
    byte[] data = new byte[produced];
    java.util.Arrays.fill(data, (byte) 'x');
    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    compressed.write(0);
    compressed.write(produced & 255);
    compressed.write((produced >>> 8) & 255);
    compressed.write((65_535 - produced) & 255);
    compressed.write(((65_535 - produced) >>> 8) & 255);
    compressed.write(data);
    compressed.write(7);
    byte[] body = compressed.toByteArray();
    CRC32 crc = new CRC32();
    crc.update(data);
    ByteArrayOutputStream file = new ByteArrayOutputStream();
    int nameLength = SERVICE.length();
    writeLocal(file, crc.getValue(), body.length, produced, nameLength);
    file.write(SERVICE.getBytes(StandardCharsets.UTF_8));
    file.write(body);
    int cenOffset = file.size();
    writeCen(file, crc.getValue(), body.length, produced, nameLength);
    file.write(SERVICE.getBytes(StandardCharsets.UTF_8));
    writeEocd(file, file.size() - cenOffset, cenOffset);
    Files.write(archive, file.toByteArray());
  }

  private static void writeLocal(
      ByteArrayOutputStream output, long crc, int compressed, int length, int nameLength)
      throws IOException {
    ByteBuffer header = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
    header.putInt(0x04034b50).putShort((short) 20).putShort((short) 0)
        .putShort((short) 8).putInt(0).putInt((int) crc)
        .putInt(compressed).putInt(length)
        .putShort((short) nameLength).putShort((short) 0);
    output.write(header.array());
  }

  private static void writeCen(
      ByteArrayOutputStream output, long crc, int compressed, int length, int nameLength)
      throws IOException {
    ByteBuffer header = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
    header.putInt(0x02014b50).putShort((short) 20).putShort((short) 20)
        .putShort((short) 0).putShort((short) 8).putInt(0).putInt((int) crc)
        .putInt(compressed).putInt(length)
        .putShort((short) nameLength).putShort((short) 0).putShort((short) 0)
        .putShort((short) 0).putShort((short) 0).putInt(0).putInt(0);
    output.write(header.array());
  }

  private static void writeEocd(ByteArrayOutputStream output, int cenBytes, int cenOffset)
      throws IOException {
    ByteBuffer end = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
    end.putInt(0x06054b50).putShort((short) 0).putShort((short) 0)
        .putShort((short) 1).putShort((short) 1)
        .putInt(cenBytes).putInt(cenOffset).putShort((short) 0);
    output.write(end.array());
  }

  private static int readLeShort(byte[] data, int position) {
    return (data[position] & 255) | ((data[position + 1] & 255) << 8);
  }

  private static void putLeShort(byte[] data, int position, int value) {
    data[position] = (byte) value;
    data[position + 1] = (byte) (value >>> 8);
  }

  private static void putLeInt(byte[] data, int position, int value) {
    data[position] = (byte) value;
    data[position + 1] = (byte) (value >>> 8);
    data[position + 2] = (byte) (value >>> 16);
    data[position + 3] = (byte) (value >>> 24);
  }

  private static int indexOf(byte[] data, byte[] needle, int from) {
    for (int index = Math.max(0, from); index <= data.length - needle.length; index++) {
      boolean match = true;
      for (int offset = 0; offset < needle.length; offset++) {
        if (data[index + offset] != needle[offset]) {
          match = false;
          break;
        }
      }
      if (match) {
        return index;
      }
    }
    return -1;
  }

  private static int lastIndexOf(byte[] data, byte[] needle) {
    for (int index = data.length - needle.length; index >= 0; index--) {
      int found = indexOf(data, needle, index);
      if (found == index) {
        return index;
      }
    }
    return -1;
  }
}
