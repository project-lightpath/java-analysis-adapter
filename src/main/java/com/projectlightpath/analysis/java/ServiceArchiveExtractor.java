package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Safe extraction of source archives into one owner-only temporary root
 * (issue #42).
 *
 * <p>Enumeration and entry streams come from the #34 seekable reader
 * (read-only reuse); the binary descriptor scan path is untouched. Every
 * failure rejects the whole run with {@link IllegalArgumentException} after
 * removing the temporary root, so a bad archive can never degrade into an
 * empty-but-complete analysis.
 */
final class ServiceArchiveExtractor {
  /** Total decompressed bytes an extraction may write (new policy, issue #42). */
  static final long MAX_EXTRACTED_BYTES = 512L * 1024 * 1024;

  /**
   * Run-wide cap on created files and directories (including implicit
   * parents). Empty files and directory entries consume no byte budget, so
   * without this cap a bounded archive could exhaust the temporary
   * filesystem's object slots (PR #47 review CR-5).
   */
  static final long MAX_EXTRACTED_OBJECTS = 262_144L;

  /**
   * Bounds on one entry name, enforced before any parent-prefix Path is
   * built: a 65,535-byte ZIP name could otherwise force quadratic prefix
   * allocation inside the object accounting (PR #47 review). Both bounds
   * sit far above real Maven -sources.jar layouts and inside common
   * filesystem limits (PATH_MAX 1024, NAME_MAX 255), so accepted names
   * remain creatable.
   */
  static final int MAX_ENTRY_NAME_CHARS = 512;
  static final int MAX_ENTRY_NAME_SEGMENTS = 128;

  private ServiceArchiveExtractor() {}

  /** One extracted run: the shared temp root and one root per archive. */
  static final class Extraction implements AutoCloseable {
    private final Path root;
    private final List<Path> archiveRoots;
    private final List<String> declaredPaths;

    Extraction(Path root, List<Path> archiveRoots, List<String> declaredPaths) {
      this.root = Objects.requireNonNull(root, "root");
      this.archiveRoots = List.copyOf(archiveRoots);
      this.declaredPaths = List.copyOf(declaredPaths);
    }

    Path root() {
      return root;
    }

    List<Path> archiveRoots() {
      return archiveRoots;
    }

    List<String> declaredPaths() {
      return declaredPaths;
    }

    /** Best-effort recursive delete; crash residue is an ops residual (R2). */
    @Override
    public void close() {
      deleteRecursively(root);
    }
  }

  static Extraction extract(List<Path> archives) {
    return extract(archives, MAX_EXTRACTED_BYTES, null);
  }

  /** Test seam: an injectable total-byte budget and temp-root parent. */
  static Extraction extract(List<Path> archives, long maxExtractedBytes, Path tempRootParent) {
    return extract(archives, maxExtractedBytes, MAX_EXTRACTED_OBJECTS, tempRootParent);
  }

  /** Test seam: injectable byte and filesystem-object budgets and temp-root parent. */
  static Extraction extract(
      List<Path> archives, long maxExtractedBytes, long maxObjects, Path tempRootParent) {
    Objects.requireNonNull(archives, "archives");
    if (maxExtractedBytes <= 0) {
      throw new IllegalArgumentException("maximum extracted bytes must be positive");
    }
    if (maxObjects <= 0) {
      throw new IllegalArgumentException("maximum extracted objects must be positive");
    }
    Path root = null;
    boolean success = false;
    try {
      root = createRoot(tempRootParent);
      Extraction extraction = extractArchives(archives, root, maxExtractedBytes, maxObjects);
      success = true;
      return extraction;
    } catch (IOException | SourceArchiveExtractionFailure error) {
      throw rejection(error, root);
    } finally {
      if (!success) {
        // Broadened beyond InterruptedIOException on purpose (review F8):
        // any failed run aborts the operation, so a pending interrupt must
        // not leak into later phases; the rejection still propagates.
        if (Thread.currentThread().isInterrupted()) {
          Thread.interrupted();
        }
        if (root != null) {
          deleteRecursively(root);
        }
      }
    }
  }

  private static Extraction extractArchives(
      List<Path> archives, Path root, long maxExtractedBytes, long maxObjects)
      throws IOException {
    List<Path> roots = new ArrayList<>();
    List<String> declared = new ArrayList<>();
    long[] extractedBytes = {0};
    long[] objects = {0};
    for (Path archive : archives) {
      Path normalized = archive.toAbsolutePath().normalize();
      String fileName = archiveFileName(normalized);
      Path archiveRoot = root.resolve(fileName);
      if (Files.exists(archiveRoot, LinkOption.NOFOLLOW_LINKS)) {
        throw new SourceArchiveExtractionFailure("duplicate source archive file name: " + fileName);
      }
      extractedBytes[0] = new ArchiveRun(normalized, archiveRoot, maxExtractedBytes, maxObjects,
          objects).extract(extractedBytes[0]);
      roots.add(archiveRoot);
      declared.add(fileName);
    }
    return new Extraction(root, roots, declared);
  }

  /** One archive's bounded extraction into its own sub-root. */
  private static final class ArchiveRun {
    private final Path archive;
    private final Path archiveRoot;
    private final long maxExtractedBytes;
    private final long maxObjects;
    private final long[] objects;

    ArchiveRun(
        Path archive, Path archiveRoot, long maxExtractedBytes, long maxObjects,
        long[] objects) {
      this.archive = archive;
      this.archiveRoot = archiveRoot;
      this.maxExtractedBytes = maxExtractedBytes;
      this.maxObjects = maxObjects;
      this.objects = objects;
    }

    long extract(long extractedBytes) throws IOException {
      probeArchive();
      Files.createDirectories(archiveRoot);
      ServiceLoaderDescriptorArchiveConfig config =
          ServiceLoaderDescriptorArchiveConfig.defaults();
      // Dedicated accounting: extraction never shares the descriptor scan's
      // 16 MiB decompressed budget; the seekable reader charges this state.
      ServiceLoaderDescriptorSource.ScanState state =
          new ServiceLoaderDescriptorSource.ScanState(maxExtractedBytes);
      SourceArchiveEntryWriter writer = new SourceArchiveEntryWriter(
          archive, archiveRoot, maxExtractedBytes, maxObjects, objects);
      long[] total = {extractedBytes};
      try (SeekableByteChannel channel = openBoundedChannel(config)) {
        ServiceLoaderDescriptorZipDirectory directory =
            new ServiceLoaderDescriptorZipDirectory(channel, channel.size(), config, state);
        directory.walk(entry -> {
          total[0] += writer.write(entry, directory, state, total[0]);
        });
        return total[0];
      } catch (ServiceLoaderDescriptorZipDirectory.ZipFormatException error) {
        throw new SourceArchiveExtractionFailure("invalid source archive: " + archive, error);
      } catch (ServiceLoaderDescriptorZipDirectory.RawIoLimitException error) {
        throw new SourceArchiveExtractionFailure(
            "source archive raw I/O budget exceeded: " + archive, error);
      }
    }

    /**
     * Opens the archive channel and re-checks the size limit on it: the
     * file may have been replaced between the probe and the open (PR #47
     * review CR-4). The channel is closed before any rejection so the
     * descriptor is not held until GC (final review N1), matching the
     * sibling reader's convention.
     */
    private SeekableByteChannel openBoundedChannel(
        ServiceLoaderDescriptorArchiveConfig config) throws IOException {
      SeekableByteChannel channel = config.channelOpener().open(archive);
      long size;
      try {
        size = channel.size();
      } catch (IOException sizeError) {
        closeQuietly(channel, sizeError);
        throw sizeError;
      }
      if (size > ServiceLoaderDescriptorArchiveSource.MAX_ARCHIVE_BYTES) {
        closeQuietly(channel, null);
        throw new SourceArchiveExtractionFailure(
            "source archive exceeds the archive size limit: " + archive);
      }
      return channel;
    }

    private static void closeQuietly(SeekableByteChannel channel, Exception suppressed) {
      try {
        channel.close();
      } catch (IOException closeError) {
        if (suppressed != null) {
          suppressed.addSuppressed(closeError);
        }
      }
    }

    private void probeArchive() throws IOException {
      BasicFileAttributes attributes;
      try {
        attributes = Files.readAttributes(
            archive, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (IOException error) {
        throw new SourceArchiveExtractionFailure("source archive is unavailable: " + archive, error);
      }
      if (!attributes.isRegularFile()) {
        throw new SourceArchiveExtractionFailure("source archive is not a regular file: " + archive);
      }
      if (attributes.size() > ServiceLoaderDescriptorArchiveSource.MAX_ARCHIVE_BYTES) {
        throw new SourceArchiveExtractionFailure(
            "source archive exceeds the archive size limit: " + archive);
      }
    }
  }

  private static String archiveFileName(Path archive) {
    Path fileName = archive.getFileName();
    if (fileName == null) {
      throw new SourceArchiveExtractionFailure("source archive has no file name: " + archive);
    }
    return fileName.toString();
  }

  private static Path createRoot(Path tempRootParent) {
    Path parent = tempRootParent == null
        ? Path.of(System.getProperty("java.io.tmpdir"))
        : tempRootParent;
    try {
      try {
        return Files.createTempDirectory(
            parent,
            "flpath-42-sources-",
            PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
      } catch (UnsupportedOperationException expectedOnNonPosix) {
        return Files.createTempDirectory(parent, "flpath-42-sources-");
      }
    } catch (IOException error) {
      throw new SourceArchiveExtractionFailure("source archive extraction root could not be created", error);
    }
  }

  /**
   * Translates one failure into the caller-visible rejection. The original
   * cause is deliberately not attached: filesystem exceptions carry the
   * temporary root in their messages and stack traces, and the rejection
   * contract is a sanitized message (PR #47 review CR-8).
   */
  private static IllegalArgumentException rejection(Exception error, Path root) {
    String message = error.getMessage() == null || error.getMessage().isBlank()
        ? error.getClass().getSimpleName() : error.getMessage();
    if (root != null) {
      message = message.replace(root.toString(), "<temp>");
    }
    return new IllegalArgumentException(
        "source archive extraction failed: " + message
            + " (" + error.getClass().getSimpleName() + ")");
  }

  /**
   * Best-effort post-order delete. {@code walkFileTree} reports traversal
   * failures as {@code IOException} instead of the stream-laziness
   * {@code UncheckedIOException} of {@code Files.walk}, so cleanup can
   * never override the run outcome (PR #47 review CR-9); residue is an ops
   * residual (R2).
   */
  private static void deleteRecursively(Path root) {
    try {
      Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
          try {
            Files.deleteIfExists(file);
          } catch (IOException | DirectoryIteratorException ignored) {
            // Best-effort cleanup; residue is an ops residual (R2).
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path directory, IOException error) {
          try {
            Files.deleteIfExists(directory);
          } catch (IOException | DirectoryIteratorException ignored) {
            // Best-effort cleanup; residue is an ops residual (R2).
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException error) {
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException | DirectoryIteratorException ignored) {
      // Best-effort cleanup; residue is an ops residual (R2).
    }
  }
}
