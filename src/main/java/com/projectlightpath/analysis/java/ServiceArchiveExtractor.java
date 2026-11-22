package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
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

  /** One rejection cause; translated once, at the run boundary. */
  static final class ExtractionFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ExtractionFailure(String message) {
      super(message);
    }

    ExtractionFailure(String message, Throwable cause) {
      super(message, cause);
    }
  }

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
    Objects.requireNonNull(archives, "archives");
    if (maxExtractedBytes <= 0) {
      throw new IllegalArgumentException("maximum extracted bytes must be positive");
    }
    Path root = null;
    boolean success = false;
    try {
      root = createRoot(tempRootParent);
      Extraction extraction = extractArchives(archives, root, maxExtractedBytes);
      success = true;
      return extraction;
    } catch (IOException | ExtractionFailure error) {
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
      List<Path> archives, Path root, long maxExtractedBytes) throws IOException {
    List<Path> roots = new ArrayList<>();
    List<String> declared = new ArrayList<>();
    long[] extractedBytes = {0};
    for (Path archive : archives) {
      Path normalized = archive.toAbsolutePath().normalize();
      String fileName = archiveFileName(normalized);
      Path archiveRoot = root.resolve(fileName);
      if (Files.exists(archiveRoot, LinkOption.NOFOLLOW_LINKS)) {
        throw new ExtractionFailure("duplicate source archive file name: " + fileName);
      }
      extractedBytes[0] = new ArchiveRun(normalized, archiveRoot, maxExtractedBytes)
          .extract(extractedBytes[0]);
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
    private final Set<String> written = new HashSet<>();

    ArchiveRun(Path archive, Path archiveRoot, long maxExtractedBytes) {
      this.archive = archive;
      this.archiveRoot = archiveRoot;
      this.maxExtractedBytes = maxExtractedBytes;
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
      long[] total = {extractedBytes};
      try (SeekableByteChannel channel = config.channelOpener().open(archive)) {
        ServiceLoaderDescriptorZipDirectory directory =
            new ServiceLoaderDescriptorZipDirectory(channel, channel.size(), config, state);
        directory.walk(entry -> {
          total[0] += write(entry, directory, state, total[0]);
        });
        return total[0];
      } catch (ServiceLoaderDescriptorZipDirectory.ZipFormatException error) {
        throw new ExtractionFailure("invalid source archive: " + archive, error);
      } catch (ServiceLoaderDescriptorZipDirectory.RawIoLimitException error) {
        throw new ExtractionFailure(
            "source archive raw I/O budget exceeded: " + archive, error);
      }
    }

    private void probeArchive() throws IOException {
      BasicFileAttributes attributes;
      try {
        attributes = Files.readAttributes(
            archive, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (IOException error) {
        throw new ExtractionFailure("source archive is unavailable: " + archive, error);
      }
      if (!attributes.isRegularFile()) {
        throw new ExtractionFailure("source archive is not a regular file: " + archive);
      }
      if (attributes.size() > ServiceLoaderDescriptorArchiveSource.MAX_ARCHIVE_BYTES) {
        throw new ExtractionFailure(
            "source archive exceeds the archive size limit: " + archive);
      }
    }

    private long write(
        ServiceLoaderDescriptorZipEntry entry,
        ServiceLoaderDescriptorZipDirectory directory,
        ServiceLoaderDescriptorSource.ScanState state,
        long extractedBytes) throws IOException {
      Path target = validatedTarget(entry);
      if (entry.directory()) {
        createEntryDirectories(target, entry.name());
        return 0;
      }
      if (!written.add(entry.name())) {
        throw new ExtractionFailure("duplicate source archive entry: " + entry.name());
      }
      if (entry.length() > maxExtractedBytes - extractedBytes) {
        throw new ExtractionFailure(
            "source archive extraction exceeds " + maxExtractedBytes + " bytes: " + archive);
      }
      createEntryDirectories(target.getParent(), entry.name());
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        throw new ExtractionFailure(
            "source archive entry name collides with another entry: " + entry.name());
      }
      return copy(entry, target, directory, state);
    }

    /** Validates one entry name and returns its resolved target path. */
    private Path validatedTarget(ServiceLoaderDescriptorZipEntry entry) {
      String name = entry.name();
      String path = entry.directory() && name.endsWith("/")
          ? name.substring(0, name.length() - 1)
          : name;
      if (path.isEmpty()
          || path.startsWith("/")
          || path.contains("\\")
          || path.indexOf('\0') >= 0
          || path.indexOf('*') >= 0
          || hasUnsafeSegment(path)) {
        throw new ExtractionFailure("unsafe source archive entry name: " + name);
      }
      Path target = archiveRoot.resolve(path).normalize();
      if (target.equals(archiveRoot) || !target.startsWith(archiveRoot)) {
        throw new ExtractionFailure("source archive entry escapes its root: " + name);
      }
      return target;
    }

    private static boolean hasUnsafeSegment(String path) {
      for (String segment : path.split("/", -1)) {
        if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
          return true;
        }
      }
      return false;
    }

    private static void createEntryDirectories(Path directory, String name)
        throws IOException {
      try {
        Files.createDirectories(directory);
      } catch (FileAlreadyExistsException error) {
        throw new ExtractionFailure(
            "source archive entry conflicts with an extracted file: " + name, error);
      }
    }

    private long copy(
        ServiceLoaderDescriptorZipEntry entry,
        Path target,
        ServiceLoaderDescriptorZipDirectory directory,
        ServiceLoaderDescriptorSource.ScanState state) throws IOException {
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
        if (copied != entry.length() || input.read() >= 0) {
          throw new ExtractionFailure(
              "source archive entry length mismatch: " + entry.name());
        }
      } catch (FileAlreadyExistsException error) {
        throw new ExtractionFailure(
            "source archive entry name collides with another entry: " + entry.name(), error);
      } catch (IOException error) {
        throw new ExtractionFailure(
            "source archive entry could not be read: " + entry.name(), error);
      }
      return copied;
    }
  }

  private static String archiveFileName(Path archive) {
    Path fileName = archive.getFileName();
    if (fileName == null) {
      throw new ExtractionFailure("source archive has no file name: " + archive);
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
      throw new ExtractionFailure("source archive extraction root could not be created", error);
    }
  }

  private static IllegalArgumentException rejection(Exception error, Path root) {
    String message = error.getMessage() == null || error.getMessage().isBlank()
        ? error.getClass().getSimpleName() : error.getMessage();
    if (root != null) {
      message = message.replace(root.toString(), "<temp>");
    }
    return new IllegalArgumentException(
        "source archive extraction failed: " + message, error);
  }

  private static void deleteRecursively(Path root) {
    try {
      try (var paths = Files.walk(root)) {
        paths.sorted(Comparator.reverseOrder()).forEach(path -> {
          try {
            Files.deleteIfExists(path);
          } catch (IOException | DirectoryIteratorException ignored) {
            // Best-effort cleanup; residue is an ops residual (R2).
          }
        });
      }
    } catch (IOException | DirectoryIteratorException ignored) {
      // Best-effort cleanup; residue is an ops residual (R2).
    }
  }
}
