package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

/** Reads selected META-INF/services files for the first bounded ServiceLoader path. */
final class ServiceLoaderDescriptorReader {
  private static final String SERVICES = "META-INF/services";

  private ServiceLoaderDescriptorReader() {}

  static ServiceLoaderDescriptorScanner.Scan scan(AnalysisContext context) {
    return scan(context, SootUpInputLocationFactory.dependencyResolution(context));
  }

  static ServiceLoaderDescriptorScanner.Scan scan(
      AnalysisContext context, SootUpInputLocationFactory.DependencyResolution dependencyResolution) {
    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    Set<String> visited = new HashSet<>();
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
    for (Path wildcard : dependencyResolution.truncatedWildcards()) {
      state.diagnostic(
          "dependency_enumeration_limit", wildcard.toString(),
          "dependency wildcard expansion exceeds "
              + SootUpInputLocationFactory.MAX_DEPENDENCY_ENTRIES + " entries");
    }
    List<Path> dependencyEntries = dependencyResolution.entries();
    ServiceLoaderDescriptorSource.scanClasspathEntry(
        context.applicationClasses(), descriptors, visited, state);
    for (AnalysisContext.SourceRoot root : context.includedSourceRoots()) {
      Path parent = root.path().getParent();
      if (parent != null && ServiceLoaderDescriptorAuthorization.derivedDirectory(
          parent, "resources/" + SERVICES, state)) {
        scanDirectory(parent.resolve("resources").resolve(SERVICES), context, descriptors, visited, state);
      }
    }
    for (AnalysisContext.FrameworkMetadata metadata : context.frameworkMetadata()) {
      if (metadata.status() != AnalysisContext.MetadataStatus.OBSERVED
          || !"service_loader_descriptor".equals(metadata.kind())
          || metadata.declaredPath() == null) {
        continue;
      }
      Path declared;
      try {
        declared = Path.of(metadata.declaredPath());
      } catch (InvalidPathException error) {
        state.diagnostic(
            "descriptor_path_invalid", metadata.declaredPath(),
            ServiceLoaderDescriptorSource.message(error));
        continue;
      }
      Path resolved = declared.isAbsolute() ? declared : context.repositoryRoot().resolve(declared);
      if (!ServiceLoaderDescriptorAuthorization.authorizedPath(
          context, resolved, dependencyEntries)) {
        state.diagnostic(
            "descriptor_path_outside_context", resolved.toAbsolutePath().normalize().toString(),
            "observed descriptor metadata path is outside the selected analysis inputs");
        continue;
      }
      if (Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
        addFile(resolved, context, descriptors, visited, state);
      } else {
        scanDirectory(resolved, context, descriptors, visited, state);
      }
    }
    for (Path dependency : dependencyEntries) {
      ServiceLoaderDescriptorSource.scanClasspathEntry(
          dependency, descriptors, visited, state, true);
    }
    descriptors.sort(Comparator.comparing(ServiceLoaderDescriptorScanner.Descriptor::path));
    return new ServiceLoaderDescriptorScanner.Scan(descriptors, state.diagnostics());
  }

  /** Scans one selected classpath entry, diagnosing unavailable entries on request. */
  static void scanSelectedEntry(
      Path entry,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited,
      ServiceLoaderDescriptorSource.ScanState state,
      boolean diagnoseUnavailable) {
    Path normalized = entry.toAbsolutePath().normalize();
    BasicFileAttributes attributes;
    try {
      attributes = Files.readAttributes(
          normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (IOException error) {
      if (diagnoseUnavailable) {
        state.diagnostic("descriptor_entry_unavailable", normalized.toString(),
            ServiceLoaderDescriptorSource.message(error));
      }
      return;
    }
    if (attributes.isDirectory()) {
      if (ServiceLoaderDescriptorAuthorization.derivedDirectory(
          normalized, ServiceLoaderDescriptorSource.SERVICES_DIRECTORY, state)) {
        ServiceLoaderDescriptorSource.scanServiceDirectory(
            normalized.resolve(ServiceLoaderDescriptorSource.SERVICES_DIRECTORY),
            normalized.toString(), descriptors, visited, state);
      }
      ServiceLoaderDescriptorSource.scanModuleInfo(normalized, descriptors, state);
    } else if (attributes.isRegularFile()) {
      ServiceLoaderDescriptorArchiveSource.scan(normalized, descriptors, state);
    } else if (diagnoseUnavailable) {
      state.diagnostic("descriptor_entry_unavailable", normalized.toString(),
          "entry is neither a non-symbolic directory nor a regular file");
    }
  }

  private static void scanDirectory(
      Path directory,
      AnalysisContext context,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited,
      ServiceLoaderDescriptorSource.ScanState state) {
    if (!ServiceLoaderDescriptorAuthorization.readableDirectory(directory, state)) {
      return;
    }
    if (!visited.add("services:" + directory.toAbsolutePath().normalize())) {
      return;
    }
    NavigableSet<Path> candidates = new TreeSet<>();
    boolean complete = true;
    try (var paths = Files.newDirectoryStream(directory)) {
      for (Path path : paths) {
        if (!state.tryEnumerate(path.toString())) {
          complete = false;
          break;
        }
        if (ServiceLoaderDescriptorAuthorization.serviceFileCandidate(path, state)) {
          ServiceLoaderDescriptorSource.retainSmallest(
              candidates, path, ServiceLoaderDescriptorSource.MAX_ENUMERATED_ENTRIES);
        }
      }
    } catch (IOException | DirectoryIteratorException | UnsupportedOperationException | SecurityException error) {
      state.diagnostic("descriptor_directory_read_error",
          directory.toAbsolutePath().normalize().toString(),
          ServiceLoaderDescriptorSource.message(error));
      return;
    }
    if (!complete) {
      return;
    }
    for (Path path : candidates) {
      addFile(path, context, descriptors, visited, state);
    }
  }

  private static void addFile(
      Path file,
      AnalysisContext context,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited,
      ServiceLoaderDescriptorSource.ScanState state) {
    Path normalized = file.toAbsolutePath().normalize();
    if (!visited.add("descriptor:" + normalized)
        || normalized.getFileName() == null
        || !ServiceLoaderDescriptorAuthorization.isBinaryClassName(
            normalized.getFileName().toString())) {
      return;
    }
    try (InputStream input = Files.newInputStream(normalized, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = ServiceLoaderDescriptorArchiveSource.readEntry(
          input, state, normalized.toString());
      if (bytes == null) {
        return;
      }
      ServiceLoaderDescriptorSource.ProviderEntries parsed =
          ServiceLoaderDescriptorSource.providers(bytes);
      String serviceType = normalized.getFileName().toString();
      descriptors.add(new ServiceLoaderDescriptorScanner.Descriptor(
          serviceType,
          parsed.providers(),
          displayPath(context, normalized),
          ServiceLoaderDescriptorSource.sha256(bytes),
          "meta_inf_services",
          parsed.malformedEntries()));
    } catch (IOException | UnsupportedOperationException | SecurityException error) {
      state.diagnostic("descriptor_read_error", normalized.toString(),
          ServiceLoaderDescriptorSource.message(error));
    }
  }

  private static String displayPath(AnalysisContext context, Path path) {
    Path root = context.repositoryRoot().toAbsolutePath().normalize();
    return path.startsWith(root)
        ? root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/")
        : path.toString();
  }

}
