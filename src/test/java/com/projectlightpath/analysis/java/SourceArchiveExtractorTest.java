package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Equivalence-class tests for safe source-archive extraction (issue #42
 * scope 2): zip-slip, entry, and byte bounds; owner-only temp root; cleanup
 * without residue on every failure path.
 */
class SourceArchiveExtractorTest {
  @TempDir Path temp;

  private Path zip(String name, EntryWriter writer) throws IOException {
    Path archive = temp.resolve(name);
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      writer.write(output);
    }
    return archive;
  }

  private interface EntryWriter {
    void write(ZipOutputStream output) throws IOException;
  }

  private static void put(ZipOutputStream output, String entry, String content)
      throws IOException {
    output.putNextEntry(new ZipEntry(entry));
    output.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    output.closeEntry();
  }

  // --- success path -------------------------------------------------------

  @Test
  void extractsNestedSourcesWithContent() throws IOException {
    Path archive = zip("app-sources.jar", output -> {
      output.putNextEntry(new ZipEntry("demo/"));
      output.closeEntry();
      put(output, "demo/App.java", "package demo; class App {}\n");
      put(output, "demo/util/Helper.java", "package demo.util; class Helper {}\n");
    });

    try (ServiceArchiveExtractor.Extraction extraction =
        ServiceArchiveExtractor.extract(
            List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp)) {
      Path root = extraction.root();
      assertEquals(1, extraction.archiveRoots().size());
      Path sources = extraction.archiveRoots().get(0);
      assertTrue(sources.startsWith(root));
      assertEquals("package demo; class App {}\n",
          Files.readString(sources.resolve("demo/App.java")));
      assertEquals("package demo.util; class Helper {}\n",
          Files.readString(sources.resolve("demo/util/Helper.java")));
      assertTrue(Files.isRegularFile(sources.resolve("demo/App.java"), LinkOption.NOFOLLOW_LINKS));
    }
  }

  @Test
  void closeRemovesTheExtractionRoot() throws IOException {
    Path archive = zip("close-sources.jar", output ->
        put(output, "demo/App.java", "package demo; class App {}\n"));

    Path root;
    try (ServiceArchiveExtractor.Extraction extraction =
        ServiceArchiveExtractor.extract(
            List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp)) {
      root = extraction.root();
      assertTrue(Files.isDirectory(root));
    }
    assertFalse(Files.exists(root, LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  void extractionRootIsOwnerOnlyWherePosixApplies() throws IOException {
    Path archive = zip("perm-sources.jar", output ->
        put(output, "demo/App.java", "package demo; class App {}\n"));

    try (ServiceArchiveExtractor.Extraction extraction =
        ServiceArchiveExtractor.extract(
            List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp)) {
      try {
        String permissions = PosixFilePermissions.toString(
            Files.getPosixFilePermissions(extraction.root()));
        assertEquals("rwx------", permissions);
      } catch (UnsupportedOperationException expectedOnNonPosix) {
        // Windows/other: owner-only enforcement is POSIX-only, documented.
      }
    }
  }

  @Test
  void declaredPathsAreStableAcrossRuns() throws IOException {
    Path archive = zip("stable-sources.jar", output ->
        put(output, "demo/App.java", "package demo; class App {}\n"));

    try (ServiceArchiveExtractor.Extraction one =
        ServiceArchiveExtractor.extract(
            List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp);
        ServiceArchiveExtractor.Extraction two =
        ServiceArchiveExtractor.extract(
            List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp)) {
      assertEquals(List.of("stable-sources.jar"), one.declaredPaths());
      assertEquals(List.of("stable-sources.jar"), two.declaredPaths());
      assertEquals(
          one.archiveRoots().get(0).getFileName(),
          two.archiveRoots().get(0).getFileName());
    }
  }

  // --- zip-slip and entry-name rejection ----------------------------------

  @Test
  void rejectsParentDirectoryEscapeWithoutResidue() throws IOException {
    Path archive = zip("escape-sources.jar", output -> {
      put(output, "demo/App.java", "package demo; class App {}\n");
      put(output, "../evil.txt", "escaped");
    });

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> ServiceArchiveExtractor.extract(
            List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp));
    assertNotNull(error.getMessage());
    assertFalse(Files.exists(temp.resolve("evil.txt")));
    try (var paths = Files.list(temp)) {
      assertTrue(paths.filter(path -> !path.toString().endsWith(".jar")).toList().isEmpty());
    }
  }

  @Test
  void rejectsInternalDotDotTraversal() throws IOException {
    Path archive = zip("traversal-sources.jar", output ->
        put(output, "demo/../../evil.txt", "escaped"));

    assertThrows(IllegalArgumentException.class,
        () -> ServiceArchiveExtractor.extract(
            List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp));
    assertFalse(Files.exists(temp.getParent().resolve("evil.txt")));
  }

  @Test
  void rejectsLeadingSlashName() throws IOException {
    Path archive = zip("absolute-sources.jar", output ->
        put(output, "/etc/evil.txt", "absolute"));

    assertThrows(IllegalArgumentException.class,
        () -> ServiceArchiveExtractor.extract(
            List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp));
  }

  @Test
  void rejectsBackslashName() throws IOException {
    Path archive = zip("backslash-sources.jar", output ->
        put(output, "demo\\App.java", "package demo; class App {}\n"));

    assertThrows(IllegalArgumentException.class,
        () -> ServiceArchiveExtractor.extract(
            List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp));
  }

  @Test
  void rejectsSingleDotComponent() throws IOException {
    Path archive = zip("dot-sources.jar", output ->
        put(output, "demo/./App.java", "package demo; class App {}\n"));

    assertThrows(IllegalArgumentException.class,
        () -> ServiceArchiveExtractor.extract(
            List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp));
  }

  @Test
  void rejectsEntryNameResolvingToTheRootItself() throws IOException {
    Path archive = zip("self-sources.jar", output -> {
      output.putNextEntry(new ZipEntry("/"));
      output.closeEntry();
    });

    assertThrows(IllegalArgumentException.class,
        () -> ServiceArchiveExtractor.extract(
            List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp));
  }

  @Test
  void rejectsDuplicateFileEntries() throws IOException {
    Path archive = rawDuplicateEntryZip();

    assertThrows(IllegalArgumentException.class,
        () -> ServiceArchiveExtractor.extract(
            List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp));
  }

  /**
   * Builds a stored-entry ZIP with two central-directory records for one
   * name (ZipOutputStream refuses duplicates, so the bytes are assembled
   * directly; local and CEN fields agree, so the #34 parser accepts it).
   */
  private Path rawDuplicateEntryZip() throws IOException {
    byte[] data = "package demo; class First {}\n"
        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
    crc.update(data);
    int crcValue = (int) crc.getValue();
    byte[] name = "demo/Dup.java".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    long[] locals = new long[2];
    for (int index = 0; index < 2; index++) {
      locals[index] = out.size();
      out.writeBytes(new byte[] {0x50, 0x4b, 0x03, 0x04});
      short_(out, 20);
      short_(out, 0);
      short_(out, 0);
      short_(out, 0);
      short_(out, 0);
      int_(out, crcValue);
      int_(out, data.length);
      int_(out, data.length);
      short_(out, name.length);
      short_(out, 0);
      out.writeBytes(name);
      out.writeBytes(data);
    }
    long cenStart = out.size();
    for (int index = 0; index < 2; index++) {
      out.writeBytes(new byte[] {0x50, 0x4b, 0x01, 0x02});
      short_(out, 20);
      short_(out, 20);
      short_(out, 0);
      short_(out, 0);
      short_(out, 0);
      short_(out, 0);
      int_(out, crcValue);
      int_(out, data.length);
      int_(out, data.length);
      short_(out, name.length);
      short_(out, 0);
      short_(out, 0);
      short_(out, 0);
      short_(out, 0);
      int_(out, 0);
      int_(out, (int) locals[index]);
      out.writeBytes(name);
    }
    int cenSize = out.size() - (int) cenStart;
    out.writeBytes(new byte[] {0x50, 0x4b, 0x05, 0x06});
    short_(out, 0);
    short_(out, 0);
    short_(out, 2);
    short_(out, 2);
    int_(out, cenSize);
    int_(out, (int) cenStart);
    short_(out, 0);
    Path archive = temp.resolve("duplicate-sources.jar");
    Files.write(archive, out.toByteArray());
    return archive;
  }

  private static void short_(java.io.ByteArrayOutputStream out, int value) {
    out.write(value & 0xff);
    out.write((value >>> 8) & 0xff);
  }

  private static void int_(java.io.ByteArrayOutputStream out, int value) {
    for (int shift = 0; shift < 32; shift += 8) {
      out.write((value >>> shift) & 0xff);
    }
  }

  @Test
  void rejectsFileThenChildConflict() throws IOException {
    Path archive = zip("conflict-sources.jar", output -> {
      put(output, "demo", "file where a directory is needed");
      put(output, "demo/App.java", "package demo; class App {}\n");
    });

    assertThrows(IllegalArgumentException.class,
        () -> ServiceArchiveExtractor.extract(
            List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp));
  }

  // --- archive-level rejection --------------------------------------------

  @Test
  void rejectsMissingArchiveWithoutResidue() {
    assertThrows(IllegalArgumentException.class,
        () -> ServiceArchiveExtractor.extract(List.of(temp.resolve("missing.jar"))));
  }

  @Test
  void rejectsNonZipArchiveWithoutResidue() throws IOException {
    Path archive = temp.resolve("not-a-zip.jar");
    Files.writeString(archive, "not a zip");

    assertThrows(IllegalArgumentException.class,
        () -> ServiceArchiveExtractor.extract(
            List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp));
  }

  @Test
  void rejectsOversizedExtractionThroughTheBudgetSeam() throws IOException {
    Path archive = zip("budget-sources.jar", output ->
        put(output, "demo/App.java", "package demo; class App {}\n"));

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> ServiceArchiveExtractor.extract(List.of(archive), 4L, temp));
    assertNotNull(error.getMessage());
    try (var paths = Files.list(temp)) {
      assertTrue(paths.filter(path -> !path.toString().endsWith(".jar")).toList().isEmpty());
    }
  }

  @Test
  void extractionBudgetBoundaryAcceptsExactFitAndRejectsOneByteUnder() throws IOException {
    String content = "package demo; class App {}\n";
    Path archive = zip("exact-sources.jar", output ->
        put(output, "demo/App.java", content));
    long contentBytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

    try (ServiceArchiveExtractor.Extraction extraction =
        ServiceArchiveExtractor.extract(List.of(archive), contentBytes, temp)) {
      assertTrue(Files.isRegularFile(
          extraction.archiveRoots().get(0).resolve("demo/App.java")));
    }
    assertThrows(IllegalArgumentException.class,
        () -> ServiceArchiveExtractor.extract(List.of(archive), contentBytes - 1, temp));
    try (var paths = Files.list(temp)) {
      assertTrue(paths.filter(path -> !path.toString().endsWith(".jar")).toList().isEmpty());
    }
  }

  @Test
  void interruptionCleansUpAndClearsTheFlag() throws IOException {
    Path archive = zip("interrupt-sources.jar", output ->
        put(output, "demo/App.java", "package demo; class App {}\n"));

    Thread.currentThread().interrupt();
    try {
      assertThrows(IllegalArgumentException.class,
          () -> ServiceArchiveExtractor.extract(
            List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp));
      assertFalse(Thread.interrupted());
    } finally {
      Thread.interrupted();
    }
    try (var paths = Files.list(temp)) {
      assertTrue(paths.filter(path -> !path.toString().endsWith(".jar")).toList().isEmpty());
    }
  }

  @Test
  void failureAfterPartialExtractionLeavesNoResidue() throws IOException {
    Path archive = zip("partial-sources.jar", output -> {
      put(output, "demo/Good.java", "package demo; class Good {}\n");
      put(output, "demo/../../evil.txt", "escape after partial extraction");
    });

    assertThrows(IllegalArgumentException.class,
        () -> ServiceArchiveExtractor.extract(
            List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp));
    try (var paths = Files.list(temp)) {
      assertTrue(paths.filter(path -> !path.toString().endsWith(".jar")).toList().isEmpty());
    }
  }

  @Test
  void rejectsEmptyMiddleSegment() throws IOException {
    Path archive = zip("empty-segment-sources.jar", output ->
        put(output, "demo//App.java", "package demo; class App {}\n"));

    assertThrows(IllegalArgumentException.class,
        () -> ServiceArchiveExtractor.extract(
            List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp));
  }

  @Test
  void rejectsNulByteInEntryName() throws IOException {
    // Empirical probe: the #34 parser decodes the name bytes strictly as
    // UTF-8 (a 0x00 byte is valid UTF-8), so a NUL passes through to the
    // extractor's entry-name validation and must be rejected there.
    Path archive = zip("nul-sources.jar", output ->
        put(output, "demo/A\0pp.java", "package demo; class App {}\n"));

    assertThrows(IllegalArgumentException.class,
        () -> ServiceArchiveExtractor.extract(
            List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp));
    try (var paths = Files.list(temp)) {
      assertTrue(paths.filter(path -> !path.toString().endsWith(".jar")).toList().isEmpty());
    }
  }

  @Test
  void rejectsWildcardInEntryName() throws IOException {
    Path archive = zip("wildcard-sources.jar", output ->
        put(output, "demo/*.java", "wildcard entry"));

    assertThrows(IllegalArgumentException.class,
        () -> ServiceArchiveExtractor.extract(
            List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp));
  }

  @Test
  void caseVariantNamesAreHandledSafely() throws IOException {
    Path archive = zip("case-sources.jar", output -> {
      put(output, "demo/Foo.java", "package demo; class Foo {}\n");
      put(output, "demo/foo.java", "package demo; class foo {}\n");
    });

    // Case-insensitive filesystems collide (sanitized rejection); sensitive
    // ones extract both. Either outcome must be safe and leave no residue.
    try {
      ServiceArchiveExtractor.Extraction extraction = ServiceArchiveExtractor.extract(
          List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp);
      assertTrue(Files.isRegularFile(
          extraction.archiveRoots().get(0).resolve("demo/Foo.java")));
      assertTrue(Files.isRegularFile(
          extraction.archiveRoots().get(0).resolve("demo/foo.java")));
      extraction.close();
    } catch (IllegalArgumentException acceptableOnCaseInsensitiveFilesystems) {
      // rejected: the collision must surface as a sanitized failure
    }
    try (var paths = Files.list(temp)) {
      assertTrue(paths.filter(path -> !path.toString().endsWith(".jar")).toList().isEmpty());
    }
  }

  @Test
  void rejectsDirectoryThenFileConflict() throws IOException {
    Path archive = zip("dir-file-sources.jar", output -> {
      output.putNextEntry(new ZipEntry("demo/"));
      output.closeEntry();
      put(output, "demo", "file where the directory was");
    });

    assertThrows(IllegalArgumentException.class,
        () -> ServiceArchiveExtractor.extract(
            List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp));
  }

  @Test
  void rejectsDuplicateArchiveFileNamesAcrossArchives() throws IOException {
    Path first = zip("same-name.jar", output ->
        put(output, "demo/A.java", "package demo; class A {}\n"));
    Path directory = Files.createDirectories(temp.resolve("duplicates"));
    Path second = zip("other-name.jar", output ->
        put(output, "demo/B.java", "package demo; class B {}\n"));
    Files.move(second, directory.resolve("same-name.jar"));

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> ServiceArchiveExtractor.extract(
            List.of(first, directory.resolve("same-name.jar")),
            ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp));
    assertTrue(error.getMessage().contains("duplicate source archive file name"),
        error.getMessage());
    assertTrue(Files.exists(first), "the first archive must still exist");
  }

  @Test
  void objectBudgetBoundsCreatedFilesAndDirectories() throws IOException {
    // Two directory entries plus three files = five filesystem objects.
    Path archive = zip("objects-sources.jar", output -> {
      output.putNextEntry(new ZipEntry("a/"));
      output.closeEntry();
      output.putNextEntry(new ZipEntry("b/"));
      output.closeEntry();
      put(output, "a/F1.java", "package a; class F1 {}\n");
      put(output, "a/F2.java", "package a; class F2 {}\n");
      put(output, "b/F3.java", "package b; class F3 {}\n");
    });

    try (ServiceArchiveExtractor.Extraction extraction = ServiceArchiveExtractor.extract(
        List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, 5L, temp)) {
      assertTrue(Files.isRegularFile(
          extraction.archiveRoots().get(0).resolve("b/F3.java")));
    }
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> ServiceArchiveExtractor.extract(
            List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, 4L, temp));
    assertTrue(error.getMessage().contains("filesystem objects"), error.getMessage());
    try (var paths = Files.list(temp)) {
      assertTrue(paths.filter(path -> !path.toString().endsWith(".jar")).toList().isEmpty());
    }
  }

  @Test
  void objectBudgetCountsImplicitParentDirectories() throws IOException {
    // One deeply nested empty file charges every implicit directory plus
    // the file itself: x/y/z/F.java = 4 objects.
    Path archive = zip("nested-sources.jar", output ->
        put(output, "x/y/z/F.java", "package x.y.z; class F {}\n"));

    try (ServiceArchiveExtractor.Extraction extraction = ServiceArchiveExtractor.extract(
        List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, 4L, temp)) {
      assertTrue(Files.isRegularFile(
          extraction.archiveRoots().get(0).resolve("x/y/z/F.java")));
    }
    assertThrows(IllegalArgumentException.class, () -> ServiceArchiveExtractor.extract(
        List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, 3L, temp));
  }

  @Test
  void objectBudgetAccumulatesAcrossArchives() throws IOException {
    String content = "package demo; class App {}\n";
    Path first = zip("objects-first.jar", output ->
        put(output, "demo/App.java", content));
    Path second = zip("objects-second.jar", output ->
        put(output, "demo/App.java", content));

    // Each archive charges one directory (demo) plus one file.
    try (ServiceArchiveExtractor.Extraction extraction = ServiceArchiveExtractor.extract(
        List.of(first, second), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, 4L, temp)) {
      assertEquals(2, extraction.archiveRoots().size());
    }
    assertThrows(IllegalArgumentException.class, () -> ServiceArchiveExtractor.extract(
        List.of(first, second), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, 3L, temp));
    try (var paths = Files.list(temp)) {
      assertTrue(paths.filter(path -> !path.toString().endsWith(".jar")).toList().isEmpty());
    }
  }

  @Test
  void duplicateDirectoryEntriesAreIdempotentAndChargeNothing() throws IOException {
    // ZipOutputStream refuses duplicate names, so the two identical
    // directory records are assembled directly (stored, no data).
    Path archive = assembleDuplicateDirWithFile(temp.resolve("duplicate-dir-file.jar"));

    // demo/ twice + one file = exactly two charged objects.
    try (ServiceArchiveExtractor.Extraction extraction = ServiceArchiveExtractor.extract(
        List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, 2L, temp)) {
      assertTrue(Files.isRegularFile(
          extraction.archiveRoots().get(0).resolve("demo/App.java")));
    }
  }

  /** Raw ZIP: the demo/ directory record twice, then one stored file. */
  private Path assembleDuplicateDirWithFile(Path archive) throws IOException {
    byte[] dirName = "demo/".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] fileName = "demo/App.java".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] data = "package demo; class App {}\n"
        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
    crc.update(data);
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

    long dirLocal = out.size();
    writeStoredLocalHeader(out, dirName, 0, 0);
    long fileLocal = out.size();
    writeStoredLocalHeader(out, fileName, (int) crc.getValue(), data.length);
    out.writeBytes(data);

    long cenStart = out.size();
    writeCenRecord(out, dirName, 0, 0, dirLocal);
    writeCenRecord(out, dirName, 0, 0, dirLocal);
    writeCenRecord(out, fileName, (int) crc.getValue(), data.length, fileLocal);

    int cenSize = out.size() - (int) cenStart;
    out.writeBytes(new byte[] {0x50, 0x4b, 0x05, 0x06});
    short_(out, 0);
    short_(out, 0);
    short_(out, 3);
    short_(out, 3);
    int_(out, cenSize);
    int_(out, (int) cenStart);
    short_(out, 0);
    Files.write(archive, out.toByteArray());
    return archive;
  }

  private static void writeStoredLocalHeader(
      java.io.ByteArrayOutputStream out, byte[] name, int crcValue, int length) {
    out.writeBytes(new byte[] {0x50, 0x4b, 0x03, 0x04});
    short_(out, 20);
    short_(out, 0);
    short_(out, 0);
    short_(out, 0);
    short_(out, 0);
    int_(out, crcValue);
    int_(out, length);
    int_(out, length);
    short_(out, name.length);
    short_(out, 0);
    out.writeBytes(name);
  }

  private static void writeCenRecord(
      java.io.ByteArrayOutputStream out, byte[] name, int crcValue, int length, long local) {
    out.writeBytes(new byte[] {0x50, 0x4b, 0x01, 0x02});
    short_(out, 20);
    short_(out, 20);
    short_(out, 0);
    short_(out, 0);
    short_(out, 0);
    short_(out, 0);
    int_(out, crcValue);
    int_(out, length);
    int_(out, length);
    short_(out, name.length);
    short_(out, 0);
    short_(out, 0);
    short_(out, 0);
    short_(out, 0);
    int_(out, 0);
    int_(out, (int) local);
    out.writeBytes(name);
  }

  @Test
  void rejectsEntryNamesBeyondTheDepthAndLengthBounds() throws IOException {
    // One segment over the depth bound (129 segments): rejected before any
    // Path prefix is built.
    StringBuilder deep = new StringBuilder("a");
    for (int index = 0; index < ServiceArchiveExtractor.MAX_ENTRY_NAME_SEGMENTS; index++) {
      deep.append("/a");
    }
    Path deepArchive = zip("deep-sources.jar", output ->
        put(output, deep.toString(), "package a; class A {}\n"));

    IllegalArgumentException deepError = assertThrows(IllegalArgumentException.class,
        () -> ServiceArchiveExtractor.extract(
            List.of(deepArchive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp));
    assertTrue(deepError.getMessage().contains("unsafe source archive entry name"),
        deepError.getMessage());

    // One character over the length bound (513 characters).
    StringBuilder longName = new StringBuilder("L");
    for (int index = 0; index < ServiceArchiveExtractor.MAX_ENTRY_NAME_CHARS; index++) {
      longName.append('l');
    }
    Path longArchive = zip("long-name-sources.jar", output ->
        put(output, longName.toString(), "package a; class A {}\n"));

    IllegalArgumentException longError = assertThrows(IllegalArgumentException.class,
        () -> ServiceArchiveExtractor.extract(
            List.of(longArchive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp));
    assertTrue(longError.getMessage().contains("unsafe source archive entry name"),
        longError.getMessage());
  }

  @Test
  void acceptsEntryNamesAtTheDepthAndLengthBounds() throws IOException {
    // Exactly 128 segments: at the depth bound, inside the length bound
    // and the filesystem's own limits.
    StringBuilder deep = new StringBuilder();
    for (int index = 0; index < ServiceArchiveExtractor.MAX_ENTRY_NAME_SEGMENTS; index++) {
      if (index > 0) {
        deep.append('/');
      }
      deep.append('s').append(index % 10);
    }
    Path deepArchive = zip("deep-boundary-sources.jar", output ->
        put(output, deep.toString(), "package a; class A {}\n"));
    try (ServiceArchiveExtractor.Extraction extraction = ServiceArchiveExtractor.extract(
        List.of(deepArchive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp)) {
      assertTrue(Files.exists(extraction.archiveRoots().get(0).resolve(deep.toString())));
    }

    // Exactly 512 characters across three components of at most 170
    // characters each (plus two separators): the exact length bound, each
    // component within NAME_MAX.
    StringBuilder longName = new StringBuilder("L".repeat(170));
    longName.append('/').append("M".repeat(170));
    longName.append('/').append("B".repeat(170));
    assertEquals(ServiceArchiveExtractor.MAX_ENTRY_NAME_CHARS, longName.length());
    Path longArchive = zip("long-boundary-sources.jar", output ->
        put(output, longName.toString(), "package a; class A {}\n"));
    try (ServiceArchiveExtractor.Extraction extraction = ServiceArchiveExtractor.extract(
        List.of(longArchive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp)) {
      assertTrue(Files.exists(extraction.archiveRoots().get(0).resolve(longName.toString())));
    }
  }

  @Test
  void rejectsEntriesWhoseDeclaredLengthDisagreesWithTheData() throws IOException {
    // Raw ZIP: the local/CEN records declare one byte less than the stored
    // data. The #34 reader's CRC-at-EOF validation rejects the entry first
    // ("could not be read"); the extractor's mismatch flag is
    // defense-in-depth behind it. Either way the inconsistent entry is
    // rejected fail-closed with a sanitized message (final review N4).
    byte[] name = "demo/Short.java".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] data = "package demo; class Short {}\n"
        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
    crc.update(data);
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    long local = out.size();
    writeStoredLocalHeader(out, name, (int) crc.getValue(), data.length - 1);
    out.writeBytes(data);
    long cenStart = out.size();
    writeCenRecord(out, name, (int) crc.getValue(), data.length - 1, local);
    int cenSize = out.size() - (int) cenStart;
    out.writeBytes(new byte[] {0x50, 0x4b, 0x05, 0x06});
    short_(out, 0);
    short_(out, 0);
    short_(out, 1);
    short_(out, 1);
    int_(out, cenSize);
    int_(out, (int) cenStart);
    short_(out, 0);
    Path archive = temp.resolve("short-length-sources.jar");
    Files.write(archive, out.toByteArray());

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> ServiceArchiveExtractor.extract(
            List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp));
    assertTrue(error.getMessage().contains("could not be read")
            || error.getMessage().contains("length mismatch"),
        error.getMessage());
  }

  @Test
  void rejectsDirectoryEntriesDeclaringData() throws IOException {
    Path archive = zip("dir-data-sources.jar", output -> {
      output.putNextEntry(new ZipEntry("demo/"));
      output.write("payload where no payload may be".getBytes(
          java.nio.charset.StandardCharsets.UTF_8));
      output.closeEntry();
    });

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> ServiceArchiveExtractor.extract(
            List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp));
    assertTrue(error.getMessage().contains("directory entry declares data"),
        error.getMessage());
  }

  @Test
  void budgetAccumulatesAcrossArchives() throws IOException {
    String content = "package demo; class App {}\n";
    long contentBytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    Path first = zip("accumulate-first.jar", output ->
        put(output, "demo/App.java", content));
    Path second = zip("accumulate-second.jar", output ->
        put(output, "demo/App.java", content));

    try (ServiceArchiveExtractor.Extraction extraction = ServiceArchiveExtractor.extract(
        List.of(first, second), contentBytes * 2, temp)) {
      assertEquals(2, extraction.archiveRoots().size());
    }
    assertThrows(IllegalArgumentException.class, () -> ServiceArchiveExtractor.extract(
        List.of(first, second), contentBytes * 2 - 1, temp));
    try (var paths = Files.list(temp)) {
      assertTrue(paths.filter(path -> !path.toString().endsWith(".jar")).toList().isEmpty());
    }
  }

  @Test
  void repoLayoutArchiveExtractsSourcesAndResourcesAsFiles() throws IOException {
    Path archive = zip("repo-layout-sources.jar", output -> {
      put(output, "src/main/java/demo/App.java", "package demo; class App {}\n");
      put(output, "src/main/resources/META-INF/services/demo.Provider",
          "demo.ProviderImpl\n");
    });

    try (ServiceArchiveExtractor.Extraction extraction = ServiceArchiveExtractor.extract(
        List.of(archive), ServiceArchiveExtractor.MAX_EXTRACTED_BYTES, temp)) {
      Path sources = extraction.archiveRoots().get(0);
      assertTrue(Files.isRegularFile(sources.resolve("src/main/java/demo/App.java")));
      assertTrue(Files.isRegularFile(
          sources.resolve("src/main/resources/META-INF/services/demo.Provider")));
    }
  }
}
