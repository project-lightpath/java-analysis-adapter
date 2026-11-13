package com.projectlightpath.analysis.java;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.module.InvalidModuleDescriptorException;
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads descriptor files, archives, and module provider declarations. */
final class ServiceLoaderDescriptorSource {
  static final String SERVICES_DIRECTORY = "META-INF/services";
  static final int MAX_DESCRIPTOR_BYTES = 1_048_576;
  static final int MAX_MODULE_INFO_BYTES = 4 * 1_048_576;
  static final long MAX_ARCHIVE_BYTES = 128L * 1_024 * 1_024;
  static final int MAX_ARCHIVE_ENTRIES = 4_096;
  static final int MAX_SERVICE_DIRECTORY_ENTRIES = 4_096;
  static final long MAX_ARCHIVE_ENTRY_BYTES = 4L * 1_024 * 1_024;
  static final long MAX_ARCHIVE_DECOMPRESSED_BYTES = 16L * 1_024 * 1_024;
  static final int MAX_ARCHIVES = 256;
  static final String MODULE_INFO = "module-info.class";
  static final Pattern BINARY_CLASS_NAME = Pattern.compile(
      "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

  private ServiceLoaderDescriptorSource() {}

  static void scanClasspathEntry(
      Path entry,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited) {
    scanClasspathEntry(entry, descriptors, visited, new ScanState());
  }

  static void scanClasspathEntry(
      Path entry,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited,
      ScanState state) {
    if (state.acceptPath(entry)) {
      if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
        scanClassDirectory(entry, entry.toString(), descriptors, visited, state);
      } else if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
        ServiceLoaderDescriptorArchiveSource.scan(entry, descriptors, state);
      }
    }
  }

  static void scanClassDirectory(
      Path root,
      String label,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited) {
    scanClassDirectory(root, label, descriptors, visited, new ScanState());
  }

  static void scanClassDirectory(
      Path root,
      String label,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited,
      ScanState state) {
    Path normalized = root.toAbsolutePath().normalize();
    if (!state.acceptPath(normalized)
        || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    scanServiceDirectory(normalized.resolve(SERVICES_DIRECTORY), label, descriptors, visited, state);
    scanModuleFile(normalized.resolve(MODULE_INFO), label, descriptors, visited, state);
  }

  static void scanServiceDirectory(
      Path directory,
      String label,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited) {
    scanServiceDirectory(directory, label, descriptors, visited, new ScanState());
  }

  static void scanServiceDirectory(
      Path directory,
      String label,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited,
      ScanState state) {
    Path normalized = directory.toAbsolutePath().normalize();
    if (!state.acceptPath(normalized)
        || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
        || !visited.add("services:" + normalized)) {
      return;
    }
    List<Path> paths;
    try (var directoryPaths = Files.list(normalized)) {
      List<Path> bounded = directoryPaths.filter(path ->
              Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                  || Files.isSymbolicLink(path))
          .limit(MAX_SERVICE_DIRECTORY_ENTRIES + 1L)
          .toList();
      if (bounded.size() > MAX_SERVICE_DIRECTORY_ENTRIES) {
        state.diagnostic(
            "service_directory_entry_limit",
            normalized.toString(),
            "service descriptor directory exceeds " + MAX_SERVICE_DIRECTORY_ENTRIES + " entries");
      }
      paths = bounded.stream().sorted().limit(MAX_SERVICE_DIRECTORY_ENTRIES).toList();
    } catch (IOException error) {
      state.diagnostic(
          "service_directory_read_error", normalized.toString(),
          ServiceLoaderDescriptorArchiveSource.message(error));
      return;
    }
    paths.forEach(path -> addServiceFile(path, label, descriptors, state, visited));
  }

  static void addServiceFile(
      Path path,
      String label,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors) {
    addServiceFile(path, label, descriptors, new ScanState(), new HashSet<>());
  }

  static void addServiceFile(
      Path path,
      String label,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      ScanState state,
      Set<String> visited) {
    Path normalized = path.toAbsolutePath().normalize();
    if (!state.acceptPath(normalized) || !visited.add("descriptor:" + normalized)) {
      return;
    }
    byte[] bytes = ServiceLoaderDescriptorArchiveSource.readFile(
        normalized, state, "descriptor_too_large", "descriptor_read_error", MAX_DESCRIPTOR_BYTES);
    if (bytes == null) {
      return;
    }
    ProviderEntries parsed = providers(bytes);
    if (BINARY_CLASS_NAME.matcher(normalized.getFileName().toString()).matches()) {
      String descriptorPath = label + "/" + SERVICES_DIRECTORY + "/" + path.getFileName();
      descriptors.add(new ServiceLoaderDescriptorScanner.Descriptor(
          normalized.getFileName().toString(), parsed.providers(), descriptorPath,
          ServiceLoaderDescriptorArchiveSource.sha256(bytes),
          "meta_inf_services", parsed.malformedEntries()));
    }
  }

  private static void scanModuleFile(
      Path path,
      String label,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited,
      ScanState state) {
    Path normalized = path.toAbsolutePath().normalize();
    if (!state.acceptPath(normalized)
        || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
        || !visited.add("module:" + normalized)) {
      return;
    }
    byte[] bytes = ServiceLoaderDescriptorArchiveSource.readFile(
        normalized, state, "module_info_too_large", "module_info_read_error", MAX_MODULE_INFO_BYTES);
    if (bytes != null) {
      addModuleBytes(bytes, label + "/" + MODULE_INFO, descriptors, state);
    }
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
                ServiceLoaderDescriptorArchiveSource.sha256(bytes),
               "module_info",
               List.of()));
        }
      }
    } catch (IOException | InvalidModuleDescriptorException error) {
      state.diagnostic(
          "malformed_module_info", path, ServiceLoaderDescriptorArchiveSource.message(error));
    }
  }

  static ProviderEntries providers(byte[] bytes) {
    List<String> providers = new ArrayList<>();
    List<String> malformed = new ArrayList<>();
    new String(bytes, StandardCharsets.UTF_8).lines()
        .map(ServiceLoaderDescriptorSource::withoutComment)
        .map(String::trim)
        .map(value -> value.startsWith("\uFEFF") ? value.substring(1) : value)
        .filter(value -> !value.isBlank())
        .forEach(value -> {
          if (BINARY_CLASS_NAME.matcher(value).matches()) {
            if (!providers.contains(value)) {
              providers.add(value);
            }
          } else {
            malformed.add(value);
          }
        });
    return new ProviderEntries(List.copyOf(providers), List.copyOf(malformed));
  }

  private static String withoutComment(String line) {
    int comment = line.indexOf('#');
    return comment < 0 ? line : line.substring(0, comment);
  }

  record ProviderEntries(List<String> providers, List<String> malformedEntries) {}

  static final class ScanState {
    private final List<Path> authorizedRoots;
    private final long maxDecompressedBytes;
    private final List<ServiceLoaderDescriptorScanner.Diagnostic> diagnostics = new ArrayList<>();
    private int archives;
    private long decompressedBytes;

    ScanState() {
      this(List.of());
    }

    ScanState(List<Path> authorizedRoots) {
      this(authorizedRoots, MAX_ARCHIVE_DECOMPRESSED_BYTES);
    }

    ScanState(List<Path> authorizedRoots, long maxDecompressedBytes) {
      if (maxDecompressedBytes <= 0) {
        throw new IllegalArgumentException("maximum decompressed bytes must be positive");
      }
      this.authorizedRoots = authorizedRoots.stream()
          .map(path -> path.toAbsolutePath().normalize())
          .distinct()
          .toList();
      this.maxDecompressedBytes = maxDecompressedBytes;
    }

    boolean acceptPath(Path path) {
      Path normalized = path.toAbsolutePath().normalize();
      if (!authorizedRoots.isEmpty()
          && authorizedRoots.stream().noneMatch(normalized::startsWith)) {
        diagnostic(
            "descriptor_path_outside_inputs", normalized.toString(),
            "descriptor path is outside selected analysis inputs");
        return false;
      }
      if (containsSymbolicLink(normalized)) {
        diagnostic(
            "descriptor_symlink_rejected", normalized.toString(),
            "descriptor path contains a symbolic link");
        return false;
      }
      return true;
    }

    private boolean containsSymbolicLink(Path path) {
      if (authorizedRoots.isEmpty()) {
        return Files.isSymbolicLink(path);
      }
      return authorizedRoots.stream()
          .filter(path::startsWith)
          .anyMatch(root -> containsSymbolicLinkBelow(root, path));
    }

    private static boolean containsSymbolicLinkBelow(Path root, Path path) {
      Path current = root;
      if (Files.isSymbolicLink(current)) {
        return true;
      }
      for (Path component : root.relativize(path)) {
        current = current.resolve(component);
        if (Files.isSymbolicLink(current)) {
          return true;
        }
      }
      return false;
    }

    boolean reserveArchive(Path archive) {
      if (archives >= MAX_ARCHIVES) {
        diagnostic(
            "archive_limit", archive.toString(), "scan exceeds " + MAX_ARCHIVES + " archives");
        return false;
      }
      archives++;
      return true;
    }

    boolean reserveDecompressed(long bytes, String path) {
      return consumeDecompressed(bytes, path);
    }

    boolean consumeDecompressed(long bytes, String path) {
      if (bytes < 0 || bytes > maxDecompressedBytes - decompressedBytes) {
        diagnostic(
            "archive_decompression_limit", path,
            "archive decompression exceeds " + maxDecompressedBytes + " bytes");
        return false;
      }
      decompressedBytes += bytes;
      return true;
    }

    long remainingDecompressed() {
      return maxDecompressedBytes - decompressedBytes;
    }

    boolean hasDecompressedBudget() {
      return remainingDecompressed() > 0;
    }

    void diagnostic(String code, String path, String detail) {
      diagnostics.add(new ServiceLoaderDescriptorScanner.Diagnostic(code, path, detail));
    }

    List<ServiceLoaderDescriptorScanner.Diagnostic> diagnostics() {
      return List.copyOf(diagnostics);
    }
  }

}
