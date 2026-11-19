package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads selected META-INF/services files for the first bounded ServiceLoader path. */
final class ServiceLoaderDescriptorReader {
  private static final String SERVICES = "META-INF/services";

  private ServiceLoaderDescriptorReader() {}

  static ServiceLoaderDescriptorScanner.Scan scan(AnalysisContext context) {
    return scan(context, SootUpInputLocationFactory.dependencyResolution(context));
  }

  static ServiceLoaderDescriptorScanner.Scan scan(
      AnalysisContext context, SootUpInputLocationFactory.DependencyResolution dependencyResolution) {
    return scan(
        context, dependencyResolution, new ServiceLoaderDescriptorSource.ScanState());
  }

  static ServiceLoaderDescriptorScanner.Scan scan(
      AnalysisContext context,
      SootUpInputLocationFactory.DependencyResolution dependencyResolution,
      ServiceLoaderDescriptorSource.ScanState state) {
    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    Set<String> visited = new HashSet<>();
    for (Path wildcard : dependencyResolution.truncatedWildcards()) {
      state.diagnostic(
          "dependency_enumeration_limit", wildcard.toString(),
          "dependency wildcard expansion exceeds "
              + SootUpInputLocationFactory.MAX_DEPENDENCY_ENTRIES + " entries");
    }
    for (SootUpInputLocationFactory.Skipped skip
        : dependencyResolution.skipped()) {
      state.diagnostic(skip.code(), skip.path().toString(), skip.detail());
    }
    List<Path> dependencyEntries = dependencyResolution.entries();
    ServiceLoaderDescriptorSource.scanClasspathEntry(
        context.applicationClasses(), descriptors, visited, state);
    for (AnalysisContext.SourceRoot root : context.includedSourceRoots()) {
      Path parent = root.path().getParent();
      if (parent != null && ServiceLoaderDescriptorAuthorization.derivedDirectory(
          parent, "resources/" + SERVICES, state)) {
        scanDirectory(parent.resolve("resources").resolve(SERVICES), context,
            dependencyEntries, descriptors, visited, state);
      }
    }
    for (AnalysisContext.FrameworkMetadata metadata : context.frameworkMetadata()) {
      if (metadata.status() == AnalysisContext.MetadataStatus.OBSERVED
          && "service_loader_descriptor".equals(metadata.kind())
          && metadata.declaredPath() != null) {
        scanObservedMetadata(metadata, context, dependencyEntries, descriptors, visited, state);
      }
    }
    for (Path dependency : dependencyEntries) {
      ServiceLoaderDescriptorSource.scanClasspathEntry(
          dependency, descriptors, visited, state, true);
    }
    descriptors.sort(Comparator.comparing(ServiceLoaderDescriptorScanner.Descriptor::path)
        .thenComparing(ServiceLoaderDescriptorScanner.Descriptor::serviceType));
    return new ServiceLoaderDescriptorScanner.Scan(descriptors, state.diagnostics());
  }

  /** Handles one observed metadata selection: one file, or one directory tree. */
  private static void scanObservedMetadata(
      AnalysisContext.FrameworkMetadata metadata,
      AnalysisContext context,
      List<Path> dependencyEntries,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited,
      ServiceLoaderDescriptorSource.ScanState state) {
    Path declared;
    try {
      declared = Path.of(metadata.declaredPath());
    } catch (InvalidPathException error) {
      state.diagnostic(
          "descriptor_path_invalid", metadata.declaredPath(),
          ServiceLoaderDescriptorSource.message(error));
      return;
    }
    Path normalizedDeclared = (declared.isAbsolute()
        ? declared
        : context.repositoryRoot().resolve(declared)).toAbsolutePath().normalize();
    if (!ServiceLoaderDescriptorAuthorization.authorizedPath(
        context, normalizedDeclared, dependencyEntries)) {
      if (Files.notExists(normalizedDeclared, LinkOption.NOFOLLOW_LINKS)
          && withinRepository(context, normalizedDeclared)) {
        state.diagnostic(
            "descriptor_entry_unavailable", normalizedDeclared.toString(),
            "observed descriptor metadata path does not exist in the selected analysis inputs");
      } else {
        state.diagnostic(
            "descriptor_path_outside_context", normalizedDeclared.toString(),
            "observed descriptor metadata path is outside the selected analysis inputs");
      }
      return;
    }
    if (!Files.isRegularFile(normalizedDeclared, LinkOption.NOFOLLOW_LINKS)) {
      scanDirectory(normalizedDeclared, context, dependencyEntries, descriptors, visited, state);
      return;
    }
    if (validDescriptorFilename(normalizedDeclared)) {
      addFile(normalizedDeclared, context, dependencyEntries, descriptors, visited, state);
      return;
    }
    // An explicitly selected input must not vanish silently: the diagnostic
    // is scan-global (it blocks DEFINITE promotion for the whole scan), while
    // incidental files found during directory discovery stay ignored.
    state.diagnostic(
        "descriptor_filename_invalid", displayPath(context, normalizedDeclared),
        "explicitly selected service descriptor filename is not a binary class name");
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
      int descriptorStart = descriptors.size();
      long retainedBefore = state.retainedBytes();
      if (ServiceLoaderDescriptorAuthorization.derivedDirectory(
          normalized, ServiceLoaderDescriptorSource.SERVICES_DIRECTORY, state)) {
        ServiceLoaderDescriptorSource.scanServiceDirectory(
            normalized.resolve(ServiceLoaderDescriptorSource.SERVICES_DIRECTORY),
            normalized.toString(), "meta_inf_services", descriptors, visited, state);
      }
      if (!ServiceLoaderDescriptorSource.scanModuleInfo(normalized, descriptors, state)) {
        descriptors.subList(descriptorStart, descriptors.size()).clear();
        state.restoreRetained(retainedBefore);
      }
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
      List<Path> dependencyEntries,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited,
      ServiceLoaderDescriptorSource.ScanState state) {
    if (!ServiceLoaderDescriptorAuthorization.readableDirectory(directory, state)) {
      return;
    }
    ServiceLoaderDescriptorSource.serviceFileCandidates(
        directory.toAbsolutePath().normalize(), visited,
        "descriptor_directory_read_error", state)
        .ifPresent(candidates -> {
          for (Path path : candidates) {
            addFile(path, context, dependencyEntries, descriptors, visited, state);
          }
        });
  }

  private static void addFile(
      Path file,
      AnalysisContext context,
      List<Path> dependencyEntries,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited,
      ServiceLoaderDescriptorSource.ScanState state) {
    String kind = ServiceLoaderDescriptorProvenance.classpathBacked(
        context, file, dependencyEntries) ? "meta_inf_services" : "source_meta_inf_services";
    ServiceLoaderDescriptorSource.addServiceFile(
        file,
        displayPath(context, file.toAbsolutePath().normalize()),
        kind,
        descriptors,
        visited,
        state);
  }

  private static boolean validDescriptorFilename(Path file) {
    return file.getFileName() != null
        && ServiceLoaderDescriptorAuthorization.isBinaryClassName(file.getFileName().toString());
  }

  private static boolean withinRepository(AnalysisContext context, Path path) {
    Path root = context.repositoryRoot().toAbsolutePath().normalize();
    if (path.startsWith(root)) {
      return true;
    }
    try {
      Path realRoot = root.toRealPath();
      Path existing = path;
      while (existing != null && Files.notExists(existing, LinkOption.NOFOLLOW_LINKS)) {
        existing = existing.getParent();
      }
      return existing != null && existing.toRealPath().startsWith(realRoot);
    } catch (IOException error) {
      return false;
    }
  }

  private static String displayPath(AnalysisContext context, Path path) {
    Path root = context.repositoryRoot().toAbsolutePath().normalize();
    return path.startsWith(root)
        ? root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/")
        : path.toString();
  }

}
