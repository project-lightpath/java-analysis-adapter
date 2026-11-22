package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Writes one archive's entries into its extraction sub-root (issue #42):
 * name validation, object accounting, and bounded byte-verified copies.
 * Split from {@link ServiceArchiveExtractor.ArchiveRun} for PMD.
 */
final class SourceArchiveEntryWriter {
  private final Path archive;
  private final Path archiveRoot;
  private final long maxExtractedBytes;
  private final long maxObjects;
  private final long[] objects;
  private final Set<String> written = new HashSet<>();
  private final Set<Path> seenPaths = new HashSet<>();

  SourceArchiveEntryWriter(
      Path archive, Path archiveRoot, long maxExtractedBytes, long maxObjects,
      long[] objects) {
    this.archive = archive;
    this.archiveRoot = archiveRoot;
    this.maxExtractedBytes = maxExtractedBytes;
    this.maxObjects = maxObjects;
    this.objects = objects;
  }

  long write(
      ServiceLoaderDescriptorZipEntry entry,
      ServiceLoaderDescriptorZipDirectory directory,
      ServiceLoaderDescriptorSource.ScanState state,
      long extractedBytes) throws IOException {
    Path target = validatedTarget(entry);
    chargeObjects(target, entry.directory(), entry.name());
    if (entry.directory()) {
      return writeDirectoryEntry(entry, target);
    }
    return writeFileEntry(entry, target, directory, state, extractedBytes);
  }

  private long writeDirectoryEntry(
      ServiceLoaderDescriptorZipEntry entry, Path target) throws IOException {
    if (entry.length() != 0) {
      // Directory entries carry no payload; declared data would never be
      // stream-verified, so it is rejected instead (PR #47 review). (An
      // empty DEFLATE stream still reports compressed > 0, so the declared
      // uncompressed length is the authoritative signal.)
      throw new SourceArchiveExtractionFailure(
          "source archive directory entry declares data: " + entry.name());
    }
    createEntryDirectories(target, entry.name());
    return 0;
  }

  private long writeFileEntry(
      ServiceLoaderDescriptorZipEntry entry,
      Path target,
      ServiceLoaderDescriptorZipDirectory directory,
      ServiceLoaderDescriptorSource.ScanState state,
      long extractedBytes) throws IOException {
    if (!written.add(entry.name())) {
      throw new SourceArchiveExtractionFailure("duplicate source archive entry: " + entry.name());
    }
    if (entry.length() > maxExtractedBytes - extractedBytes) {
      throw new SourceArchiveExtractionFailure(
          "source archive extraction exceeds " + maxExtractedBytes + " bytes: " + archive);
    }
    createEntryDirectories(target.getParent(), entry.name());
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new SourceArchiveExtractionFailure(
          "source archive entry name collides with another entry: " + entry.name());
    }
    return copy(entry, target, directory, state);
  }

  /**
   * Charges the run-wide filesystem-object budget for one entry and its
   * not-yet-created parent directories before anything is created.
   */
  private void chargeObjects(Path target, boolean directory, String name) {
    Path relative = archiveRoot.relativize(target);
    List<Path> created = new ArrayList<>();
    Path accumulated = archiveRoot;
    int directorySegments = relative.getNameCount() - (directory ? 0 : 1);
    for (int index = 0; index < directorySegments; index++) {
      accumulated = accumulated.resolve(relative.getName(index));
      if (seenPaths.add(accumulated)) {
        created.add(accumulated);
      }
    }
    long newObjects = created.size() + (directory ? 0 : 1);
    if (objects[0] + newObjects > maxObjects) {
      throw new SourceArchiveExtractionFailure(
          "source archive extraction exceeds " + maxObjects + " filesystem objects: "
              + archive);
    }
    objects[0] += newObjects;
  }

  /** Validates one entry name and returns its resolved target path. */
  private Path validatedTarget(ServiceLoaderDescriptorZipEntry entry) {
    String name = entry.name();
    String path = entry.directory() && name.endsWith("/")
        ? name.substring(0, name.length() - 1)
        : name;
    if (hasUnsafeCharacters(path) || hasUnsafeSegment(path) || exceedsNameBounds(path)) {
      throw new SourceArchiveExtractionFailure("unsafe source archive entry name: " + name);
    }
    Path target = archiveRoot.resolve(path).normalize();
    if (target.equals(archiveRoot) || !target.startsWith(archiveRoot)) {
      throw new SourceArchiveExtractionFailure("source archive entry escapes its root: " + name);
    }
    return target;
  }

  private static boolean hasUnsafeCharacters(String path) {
    return path.isEmpty()
        || path.startsWith("/")
        || path.contains("\\")
        || path.indexOf('\0') >= 0
        || path.indexOf('*') >= 0;
  }

  /**
   * Depth and length bounds, enforced before any parent-prefix Path is
   * built: a 65,535-byte ZIP name could otherwise force quadratic prefix
   * allocation inside the object accounting (PR #47 review).
   */
  private static boolean exceedsNameBounds(String path) {
    return path.length() > ServiceArchiveExtractor.MAX_ENTRY_NAME_CHARS
        || countSegments(path) > ServiceArchiveExtractor.MAX_ENTRY_NAME_SEGMENTS;
  }

  private static boolean hasUnsafeSegment(String path) {
    for (String segment : path.split("/", -1)) {
      if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
        return true;
      }
    }
    return false;
  }

  private static int countSegments(String path) {
    int count = 1;
    for (int index = 0; index < path.length(); index++) {
      if (path.charAt(index) == '/') {
        count++;
      }
    }
    return count;
  }

  private static void createEntryDirectories(Path directory, String name)
      throws IOException {
    try {
      Files.createDirectories(directory);
    } catch (FileAlreadyExistsException error) {
      throw new SourceArchiveExtractionFailure(
          "source archive entry conflicts with an extracted file: " + name, error);
    }
  }

  private long copy(
      ServiceLoaderDescriptorZipEntry entry,
      Path target,
      ServiceLoaderDescriptorZipDirectory directory,
      ServiceLoaderDescriptorSource.ScanState state) throws IOException {
    boolean[] mismatch = {false};
    try {
      copyEntryBytes(entry, target, directory, state, mismatch);
    } catch (FileAlreadyExistsException error) {
      throw new SourceArchiveExtractionFailure(
          "source archive entry name collides with another entry: " + entry.name(), error);
    } catch (IOException error) {
      throw new SourceArchiveExtractionFailure(
          "source archive entry could not be read: " + entry.name(), error);
    }
    if (mismatch[0]) {
      throw new SourceArchiveExtractionFailure(
          "source archive entry length mismatch: " + entry.name());
    }
    return entry.length();
  }

  private void copyEntryBytes(
      ServiceLoaderDescriptorZipEntry entry,
      Path target,
      ServiceLoaderDescriptorZipDirectory directory,
      ServiceLoaderDescriptorSource.ScanState state,
      boolean[] mismatch) throws IOException {
    String displayPath = archive + "!/" + entry.name();
    long copied = 0;
    try (InputStream input = directory.stream(entry, state, displayPath);
        var output = Files.newOutputStream(target)) {
      byte[] buffer = new byte[8192];
      while (copied < entry.length()) {
        int count = input.read(
            buffer, 0, (int) Math.min(buffer.length, entry.length() - copied));
        if (count < 0) {
          break;
        }
        if (count > 0) {
          output.write(buffer, 0, count);
          copied += count;
        }
      }
      // Reading to EOF lets the seekable stream verify the entry CRC.
      mismatch[0] = copied != entry.length() || input.read() >= 0;
    }
  }
}
