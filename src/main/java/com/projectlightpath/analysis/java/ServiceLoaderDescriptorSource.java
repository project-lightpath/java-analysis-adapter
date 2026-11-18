package com.projectlightpath.analysis.java;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.module.InvalidModuleDescriptorException;
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Reads ServiceLoader metadata from selected classpath directories and archives. */
final class ServiceLoaderDescriptorSource {
  static final String SERVICES_DIRECTORY = "META-INF/services";
  static final String WAR_CLASSES_DIRECTORY = "WEB-INF/classes/";
  static final String WAR_LIB_DIRECTORY = "WEB-INF/lib/";
  static final String MODULE_INFO = "module-info.class";
  static final long MAX_ARCHIVE_DECOMPRESSED_BYTES = 16L * 1024 * 1024;
  static final int MAX_ENUMERATED_ENTRIES = 1024;

  private ServiceLoaderDescriptorSource() {}

  static void scanClasspathEntry(
      Path entry,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited,
      ScanState state) {
    scanClasspathEntry(entry, descriptors, visited, state, false);
  }

  static void scanClasspathEntry(
      Path entry,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited,
      ScanState state,
      boolean diagnoseUnavailable) {
    // PMD GodClass forces the file-kind probing to live in
    // ServiceLoaderDescriptorReader; behavior is identical.
    ServiceLoaderDescriptorReader.scanSelectedEntry(
        entry, descriptors, visited, state, diagnoseUnavailable);
  }

  static void scanServiceDirectory(
      Path directory,
      String label,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited,
      ScanState state) {
    Path normalized = directory.toAbsolutePath().normalize();
    if (!ServiceLoaderDescriptorAuthorization.readableDirectory(normalized, state)) {
      return;
    }
    serviceFileCandidates(normalized, visited, "service_directory_read_error", state)
        .ifPresent(candidates -> {
          for (Path path : candidates) {
            addServiceFile(path,
                label + "/" + SERVICES_DIRECTORY + "/" + path.getFileName(),
                descriptors, visited, state);
          }
        });
  }

  /**
   * Enumerates the service-file candidates of one already-normalized services
   * directory in deterministic smallest-first order. Empty when the directory
   * was already visited, the enumeration budget was exhausted, or the stream
   * failed (the failure diagnostic is keyed by {@code readErrorKey}, which the
   * two callers keep distinct for behavior compatibility).
   */
  static Optional<List<Path>> serviceFileCandidates(
      Path normalized, Set<String> visited, String readErrorKey, ScanState state) {
    if (!visited.add("services:" + normalized)) {
      return Optional.empty();
    }
    NavigableSet<Path> candidates = new TreeSet<>();
    try (DirectoryStream<Path> paths = Files.newDirectoryStream(normalized)) {
      for (Path path : paths) {
        if (!state.tryEnumerate(path.toString())) {
          return Optional.empty();
        }
        if (ServiceLoaderDescriptorAuthorization.serviceFileCandidate(path, state)) {
          retainSmallest(candidates, path, MAX_ENUMERATED_ENTRIES);
        }
      }
    } catch (IOException | DirectoryIteratorException | UnsupportedOperationException | SecurityException error) {
      state.diagnostic(readErrorKey, normalized.toString(), message(error));
      return Optional.empty();
    }
    return Optional.of(List.copyOf(candidates));
  }

  static <T extends Comparable<? super T>> void retainSmallest(
      NavigableSet<T> values, T candidate, int limit) {
    if (values.size() < limit) {
      values.add(candidate);
    } else if (candidate.compareTo(values.last()) < 0) {
      values.pollLast();
      values.add(candidate);
    }
  }

  /** Reads one service descriptor file; {@code displayPath} is the reported location. */
  static void addServiceFile(
      Path path,
      String displayPath,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited,
      ScanState state) {
    Path normalized = path.toAbsolutePath().normalize();
    if (!visited.add("descriptor:" + normalized)
        || normalized.getFileName() == null
        || !ServiceLoaderDescriptorAuthorization.isBinaryClassName(
            normalized.getFileName().toString())) {
      return;
    }
    try (var input = Files.newInputStream(normalized, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = ServiceLoaderDescriptorArchiveSource.readEntry(
          input, state, normalized.toString());
      if (bytes == null) {
        return;
      }
      ProviderEntries parsed = providers(bytes);
      descriptors.add(new ServiceLoaderDescriptorScanner.Descriptor(
          normalized.getFileName().toString(),
          parsed.providers(),
          displayPath,
          sha256(bytes),
          "meta_inf_services",
          parsed.malformedEntries()));
    } catch (IOException | UnsupportedOperationException | SecurityException error) {
      state.diagnostic("descriptor_read_error", normalized.toString(), message(error));
    }
  }

  static void scanModuleInfo(
      Path classpathDirectory,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      ScanState state) {
    Path module = classpathDirectory.resolve(MODULE_INFO);
    if (!Files.isRegularFile(module, LinkOption.NOFOLLOW_LINKS)) {
      if (!Files.notExists(module, LinkOption.NOFOLLOW_LINKS)) {
        state.diagnostic("descriptor_read_error", module.toString(),
            "module-info.class is not a non-symbolic regular file");
      }
      return;
    }
    String path = module.toAbsolutePath().normalize().toString();
    if (!state.hasDecompressedBudget()) {
      state.diagnostic(
          "archive_decompression_limit", path,
          "module-info.class could not be read after the descriptor decompression budget was exhausted");
      return;
    }
    try (var input = Files.newInputStream(module, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = ServiceLoaderDescriptorArchiveSource.readEntry(input, state, path);
      if (bytes != null) {
        addModuleBytes(bytes, path, descriptors, state);
      }
    } catch (IOException | UnsupportedOperationException | SecurityException error) {
      state.diagnostic("descriptor_read_error", path, message(error));
    }
  }

  static ProviderEntries providers(byte[] bytes) {
    Set<String> providers = new java.util.LinkedHashSet<>();
    List<String> malformed = new ArrayList<>();
    new String(bytes, StandardCharsets.UTF_8).lines()
        .map(ServiceLoaderDescriptorSource::withoutComment)
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .forEach(value -> {
          if (ServiceLoaderDescriptorAuthorization.isBinaryClassName(value)) {
            providers.add(value);
          } else {
            malformed.add(value);
          }
        });
    return new ProviderEntries(List.copyOf(providers), List.copyOf(malformed));
  }

  static void addModuleBytes(
      byte[] bytes,
      String path,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      ScanState state) {
    try {
      ModuleDescriptor module = ModuleDescriptor.read(new ByteArrayInputStream(bytes));
      String digest = sha256(bytes);
      for (ModuleDescriptor.Provides provides : module.provides()) {
        if (!provides.providers().isEmpty()) {
          descriptors.add(new ServiceLoaderDescriptorScanner.Descriptor(
              provides.service(),
              List.copyOf(provides.providers()),
              path,
              digest,
              "module_info",
              List.of()));
        }
      }
    } catch (IOException | InvalidModuleDescriptorException error) {
      state.diagnostic("malformed_module_info", path, message(error));
    }
  }

  private static String withoutComment(String line) {
    int comment = line.indexOf('#');
    return comment < 0 ? line : line.substring(0, comment);
  }

  static String sha256(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder result = new StringBuilder(digest.length * 2);
      for (byte value : digest) {
        result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
        result.append(Character.forDigit(value & 0x0f, 16));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  static String message(Exception error) {
    return error.getMessage() == null || error.getMessage().isBlank()
        ? error.getClass().getSimpleName() : error.getMessage();
  }

  record ProviderEntries(List<String> providers, List<String> malformedEntries) {}

  static final class ScanState {
    private final List<ServiceLoaderDescriptorScanner.Diagnostic> diagnostics = new ArrayList<>();
    private final long maxDecompressedBytes;
    private long decompressedBytes;
    private boolean warMetadataLimitReported;
    private int warMetadataDiagnostics;

    ScanState() {
      this(MAX_ARCHIVE_DECOMPRESSED_BYTES);
    }

    ScanState(long maxDecompressedBytes) {
      if (maxDecompressedBytes <= 0) {
        throw new IllegalArgumentException("maximum decompressed bytes must be positive");
      }
      this.maxDecompressedBytes = maxDecompressedBytes;
    }

    void diagnostic(String code, String path, String detail) {
      diagnostics.add(new ServiceLoaderDescriptorScanner.Diagnostic(code, path, detail));
    }

    void warNestedMetadataSkipped(String path) {
      if (warMetadataDiagnostics == 0) {
        diagnostic(
            "war_nested_service_metadata_skipped", path,
            "WAR-contained ServiceLoader metadata was not scanned");
        warMetadataDiagnostics++;
      } else if (!warMetadataLimitReported) {
        diagnostic(
            "war_nested_service_metadata_skipped_limit", path,
            "additional WAR-contained ServiceLoader metadata diagnostics were suppressed");
        warMetadataLimitReported = true;
      }
    }

    List<ServiceLoaderDescriptorScanner.Diagnostic> diagnostics() {
      return List.copyOf(diagnostics);
    }

    long remainingDecompressed() {
      return maxDecompressedBytes - decompressedBytes;
    }

    private int enumeratedEntries;

    boolean hasDecompressedBudget() {
      return remainingDecompressed() > 0;
    }

    boolean tryEnumerate(String path) {
      if (enumeratedEntries >= MAX_ENUMERATED_ENTRIES) {
        diagnostic(
            "descriptor_enumeration_limit", path,
            "descriptor entry enumeration exceeds " + MAX_ENUMERATED_ENTRIES + " entries");
        return false;
      }
      enumeratedEntries++;
      return true;
    }

    boolean consumeDecompressed(long bytes, String path) {
      if (bytes < 0 || bytes > remainingDecompressed()) {
        diagnostic(
            "archive_decompression_limit", path,
            "archive decompression exceeds " + maxDecompressedBytes + " bytes");
        return false;
      }
      decompressedBytes += bytes;
      return true;
    }
  }
}
