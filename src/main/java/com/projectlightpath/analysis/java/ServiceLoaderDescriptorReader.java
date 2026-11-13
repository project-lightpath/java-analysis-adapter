package com.projectlightpath.analysis.java;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Collects ServiceLoader descriptors from selected application and dependency inputs. */
final class ServiceLoaderDescriptorReader {
  private ServiceLoaderDescriptorReader() {}

  /** Reads application output, source resources, metadata paths, and selected dependencies. */
  static ServiceLoaderDescriptorScanner.Scan scan(AnalysisContext context) {
    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    Set<String> visited = new HashSet<>();
    List<Path> dependencyEntries = SootUpInputLocationFactory.resolvedDependencyEntries(context);
    ServiceLoaderDescriptorSource.ScanState state =
        new ServiceLoaderDescriptorSource.ScanState(authorizedRoots(context, dependencyEntries));
    scanApplicationOutput(context, descriptors, visited, state);
    scanSourceResources(context, descriptors, visited, state);
    scanFrameworkMetadata(context, descriptors, visited, state);
    scanDependencies(dependencyEntries, descriptors, visited, state);
    descriptors.sort(Comparator.comparing(ServiceLoaderDescriptorScanner.Descriptor::path)
        .thenComparing(ServiceLoaderDescriptorScanner.Descriptor::kind)
        .thenComparing(ServiceLoaderDescriptorScanner.Descriptor::serviceType));
    return new ServiceLoaderDescriptorScanner.Scan(
        List.copyOf(descriptors), state.diagnostics());
  }

  private static void scanApplicationOutput(
      AnalysisContext context,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited,
      ServiceLoaderDescriptorSource.ScanState state) {
    ServiceLoaderDescriptorSource.scanClassDirectory(
        context.applicationClasses(), context.applicationClasses().toString(), descriptors, visited, state);
  }

  private static void scanSourceResources(
      AnalysisContext context,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited,
      ServiceLoaderDescriptorSource.ScanState state) {
    for (AnalysisContext.SourceRoot root : context.includedSourceRoots()) {
      Path parent = root.path().getParent();
      if (parent != null) {
        Path resources = parent.resolve("resources");
        ServiceLoaderDescriptorSource.scanServiceDirectory(
            resources.resolve(ServiceLoaderDescriptorSource.SERVICES_DIRECTORY),
            displayPath(context, resources), descriptors, visited, state);
      }
    }
  }

  private static void scanFrameworkMetadata(
      AnalysisContext context,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited,
      ServiceLoaderDescriptorSource.ScanState state) {
    for (AnalysisContext.FrameworkMetadata metadata : context.frameworkMetadata()) {
      if (isObservedServiceDescriptor(metadata)) {
        scanMetadataPath(context, Path.of(metadata.declaredPath()), descriptors, visited, state);
      }
    }
  }

  private static boolean isObservedServiceDescriptor(AnalysisContext.FrameworkMetadata metadata) {
    return metadata.kind().equals("service_loader_descriptor")
        && metadata.status() == AnalysisContext.MetadataStatus.OBSERVED
        && metadata.declaredPath() != null;
  }

  private static void scanMetadataPath(
      AnalysisContext context,
      Path declared,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited,
      ServiceLoaderDescriptorSource.ScanState state) {
    Path resolved = declared.isAbsolute()
        ? declared.normalize()
        : context.repositoryRoot().resolve(declared).normalize();
    if (Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
      scanMetadataFile(context, resolved, descriptors, visited, state);
      return;
    }
    Path servicesDirectory = servicesDirectory(resolved);
    Path classpathRoot = classpathRoot(servicesDirectory, resolved);
    ServiceLoaderDescriptorSource.scanServiceDirectory(
        servicesDirectory, displayPath(context, classpathRoot), descriptors, visited, state);
  }

  private static void scanMetadataFile(
      AnalysisContext context,
      Path descriptor,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited,
      ServiceLoaderDescriptorSource.ScanState state) {
    Path servicesDirectory = descriptor.getParent();
    Path classpathRoot = classpathRoot(servicesDirectory, descriptor);
    ServiceLoaderDescriptorSource.addServiceFile(
        descriptor, displayPath(context, classpathRoot), descriptors, state, visited);
  }

  private static Path servicesDirectory(Path resolved) {
    Path filename = resolved.getFileName();
    return filename != null && filename.toString().equals("services")
        ? resolved
        : resolved.resolve(ServiceLoaderDescriptorSource.SERVICES_DIRECTORY);
  }

  private static Path classpathRoot(Path servicesDirectory, Path fallback) {
    if (servicesDirectory == null) {
      return fallback;
    }
    Path metaInf = servicesDirectory.getParent();
    if (metaInf == null || metaInf.getParent() == null) {
      return fallback;
    }
    return metaInf.getParent();
  }

  private static void scanDependencies(
      List<Path> dependencyEntries,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited,
      ServiceLoaderDescriptorSource.ScanState state) {
    for (Path entry : dependencyEntries) {
      ServiceLoaderDescriptorSource.scanClasspathEntry(entry, descriptors, visited, state);
    }
  }

  private static List<Path> authorizedRoots(
      AnalysisContext context, List<Path> dependencyEntries) {
    List<Path> roots = new ArrayList<>();
    roots.add(context.repositoryRoot());
    roots.add(context.applicationClasses());
    roots.addAll(dependencyEntries);
    for (AnalysisContext.SourceRoot root : context.includedSourceRoots()) {
      roots.add(root.path());
      Path parent = root.path().getParent();
      if (parent != null) {
        roots.add(parent.resolve("resources"));
      }
    }
    return roots.stream().map(path -> path.toAbsolutePath().normalize()).distinct().toList();
  }

  static String displayPath(AnalysisContext context, Path path) {
    Path normalized = path.toAbsolutePath().normalize();
    if (normalized.startsWith(context.repositoryRoot())) {
      return context.repositoryRoot().relativize(normalized).toString()
          .replace(normalized.getFileSystem().getSeparator(), "/");
    }
    return normalized.toString();
  }

}
