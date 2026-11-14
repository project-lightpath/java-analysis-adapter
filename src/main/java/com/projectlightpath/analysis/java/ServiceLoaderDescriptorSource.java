package com.projectlightpath.analysis.java;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.module.InvalidModuleDescriptorException;
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Reads ServiceLoader metadata from selected classpath directories and archives. */
final class ServiceLoaderDescriptorSource {
  static final String SERVICES_DIRECTORY = "META-INF/services";
  static final String WAR_CLASSES_DIRECTORY = "WEB-INF/classes/";
  static final String WAR_LIB_DIRECTORY = "WEB-INF/lib/";
  static final String MODULE_INFO = "module-info.class";
  static final long MAX_ARCHIVE_DECOMPRESSED_BYTES = 16L * 1024 * 1024;
  static final int MAX_ENUMERATED_ENTRIES = 1024;
  static final Pattern BINARY_CLASS_NAME = Pattern.compile(
      "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

  private ServiceLoaderDescriptorSource() {}

  static void scanClasspathEntry(
      Path entry,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited,
      ScanState state) {
    Path normalized = entry.toAbsolutePath().normalize();
    if (Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
      scanServiceDirectory(normalized.resolve(SERVICES_DIRECTORY), normalized.toString(),
          descriptors, visited, state);
    } else if (Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
      ServiceLoaderDescriptorArchiveSource.scan(normalized, descriptors, state);
    }
  }

  static void scanServiceDirectory(
      Path directory,
      String label,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited,
      ScanState state) {
    Path normalized = directory.toAbsolutePath().normalize();
    if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
        || !visited.add("services:" + normalized)) {
      return;
    }
    NavigableSet<Path> candidates = new TreeSet<>();
    try (DirectoryStream<Path> paths = Files.newDirectoryStream(normalized)) {
      for (Path path : paths) {
        state.recordEnumeration(path.toString());
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
          retainSmallest(candidates, path, MAX_ENUMERATED_ENTRIES);
        }
      }
    } catch (IOException error) {
      state.diagnostic(
          "service_directory_read_error", normalized.toString(), message(error));
    }
    for (Path path : candidates) {
      addServiceFile(path, label, descriptors, visited, state);
    }
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

  private static void addServiceFile(
      Path path,
      String label,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited,
      ScanState state) {
    Path normalized = path.toAbsolutePath().normalize();
    if (!visited.add("descriptor:" + normalized)
        || normalized.getFileName() == null
        || !BINARY_CLASS_NAME.matcher(normalized.getFileName().toString()).matches()) {
      return;
    }
    try (var input = Files.newInputStream(normalized)) {
      byte[] bytes = ServiceLoaderDescriptorArchiveSource.readEntry(
          input, state, normalized.toString());
      if (bytes == null) {
        return;
      }
      ProviderEntries parsed = providers(bytes);
      descriptors.add(new ServiceLoaderDescriptorScanner.Descriptor(
          normalized.getFileName().toString(),
          parsed.providers(),
          label + "/" + SERVICES_DIRECTORY + "/" + normalized.getFileName(),
          sha256(bytes),
          "meta_inf_services",
          parsed.malformedEntries()));
    } catch (IOException error) {
      state.diagnostic("descriptor_read_error", normalized.toString(), message(error));
    }
  }

  static ProviderEntries providers(byte[] bytes) {
    Set<String> providers = new java.util.LinkedHashSet<>();
    List<String> malformed = new ArrayList<>();
    new String(bytes, StandardCharsets.UTF_8).lines()
        .map(ServiceLoaderDescriptorSource::withoutComment)
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .forEach(value -> {
          if (BINARY_CLASS_NAME.matcher(value).matches()) {
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
      for (ModuleDescriptor.Provides provides : module.provides()) {
        if (!provides.providers().isEmpty()) {
          descriptors.add(new ServiceLoaderDescriptorScanner.Descriptor(
              provides.service(),
              List.copyOf(provides.providers()),
              path,
              sha256(bytes),
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

    void recordEnumeration(String path) {
      if (enumeratedEntries == MAX_ENUMERATED_ENTRIES) {
        diagnostic(
            "descriptor_enumeration_limit", path,
            "descriptor entry enumeration exceeds " + MAX_ENUMERATED_ENTRIES + " entries");
        enumeratedEntries++;
        return;
      }
      if (enumeratedEntries < MAX_ENUMERATED_ENTRIES) {
        enumeratedEntries++;
      }
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
