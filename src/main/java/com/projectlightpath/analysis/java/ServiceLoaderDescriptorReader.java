package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NavigableSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.TreeSet;

/** Reads selected META-INF/services files for the first bounded ServiceLoader path. */
final class ServiceLoaderDescriptorReader {
  private static final String SERVICES = "META-INF/services";
  private static final Pattern PROVIDER = Pattern.compile("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*");

  private ServiceLoaderDescriptorReader() {}

  static ServiceLoaderDescriptorScanner.Scan scan(AnalysisContext context) {
    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    Set<String> visited = new HashSet<>();
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
    SootUpInputLocationFactory.DependencyResolution dependencyResolution =
        SootUpInputLocationFactory.dependencyResolution(context);
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
      if (parent != null) {
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
      if (!authorizedPath(context, resolved, dependencyEntries)) {
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
      ServiceLoaderDescriptorSource.scanClasspathEntry(dependency, descriptors, visited, state);
    }
    descriptors.sort(Comparator.comparing(ServiceLoaderDescriptorScanner.Descriptor::path));
    return new ServiceLoaderDescriptorScanner.Scan(descriptors, state.diagnostics());
  }

  private static void scanDirectory(
      Path directory,
      AnalysisContext context,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited,
      ServiceLoaderDescriptorSource.ScanState state) {
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    NavigableSet<Path> candidates = new TreeSet<>();
    try (var paths = Files.newDirectoryStream(directory)) {
      for (Path path : paths) {
        if (!state.tryEnumerate(path.toString())) {
          break;
        }
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
          ServiceLoaderDescriptorSource.retainSmallest(
              candidates, path, ServiceLoaderDescriptorSource.MAX_ENUMERATED_ENTRIES);
        }
      }
    } catch (IOException error) {
      throw new IllegalStateException("cannot scan ServiceLoader descriptor directory: " + directory, error);
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
        || !ServiceLoaderDescriptorSource.BINARY_CLASS_NAME.matcher(
            normalized.getFileName().toString()).matches()) {
      return;
    }
    try (InputStream input = Files.newInputStream(normalized)) {
      byte[] bytes = ServiceLoaderDescriptorArchiveSource.readEntry(
          input, state, normalized.toString());
      if (bytes == null) {
        return;
      }
      Set<String> providers = new LinkedHashSet<>();
      List<String> malformed = new ArrayList<>();
      for (String line : new String(bytes, StandardCharsets.UTF_8).lines().toList()) {
        String candidate = line.split("#", 2)[0].trim();
        if (candidate.isEmpty()) {
          continue;
        }
        if (PROVIDER.matcher(candidate).matches()) {
          providers.add(candidate);
        } else {
          malformed.add(candidate);
        }
      }
      String serviceType = normalized.getFileName().toString();
      descriptors.add(new ServiceLoaderDescriptorScanner.Descriptor(
          serviceType,
          List.copyOf(providers),
          displayPath(context, normalized),
          ServiceLoaderDescriptorSource.sha256(bytes),
          "meta_inf_services",
          malformed));
    } catch (IOException error) {
      throw new IllegalStateException("cannot read ServiceLoader descriptor: " + normalized, error);
    }
  }

  private static String displayPath(AnalysisContext context, Path path) {
    Path root = context.repositoryRoot().toAbsolutePath().normalize();
    return path.startsWith(root)
        ? root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/")
        : path.toString();
  }

  private static boolean authorizedPath(
      AnalysisContext context, Path path, List<Path> dependencyEntries) {
    Path normalized = path.toAbsolutePath().normalize();
    if (under(normalized, context.repositoryRoot())
        || under(normalized, context.applicationClasses())) {
      return true;
    }
    for (AnalysisContext.SourceRoot root : context.includedSourceRoots()) {
      if (under(normalized, root.path())) {
        return true;
      }
    }
    for (Path dependency : dependencyEntries) {
      if (under(normalized, dependency)) {
        return true;
      }
    }
    return false;
  }

  private static boolean under(Path path, Path root) {
    try {
      Path realPath = path.toRealPath();
      Path realRoot = root.toRealPath();
      return Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)
          ? realPath.startsWith(realRoot)
          : realPath.equals(realRoot);
    } catch (IOException | SecurityException error) {
      return false;
    }
  }

}
