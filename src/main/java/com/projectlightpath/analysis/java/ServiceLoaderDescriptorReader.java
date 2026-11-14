package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Reads selected META-INF/services files for the first bounded ServiceLoader path. */
final class ServiceLoaderDescriptorReader {
  private static final String SERVICES = "META-INF/services";
  private static final Pattern PROVIDER = Pattern.compile("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*");

  private ServiceLoaderDescriptorReader() {}

  static ServiceLoaderDescriptorScanner.Scan scan(AnalysisContext context) {
    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    Set<String> visited = new HashSet<>();
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
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
      Path declared = Path.of(metadata.declaredPath());
      Path resolved = declared.isAbsolute() ? declared : context.repositoryRoot().resolve(declared);
      if (Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
        addFile(resolved, context, descriptors, visited, state);
      } else {
        scanDirectory(resolved, context, descriptors, visited, state);
      }
    }
    for (Path dependency : SootUpInputLocationFactory.resolvedDependencyEntries(context)) {
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
    try (Stream<Path> paths = Files.list(directory)) {
      paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          .sorted()
          .forEach(path -> addFile(path, context, descriptors, visited, state));
    } catch (IOException error) {
      throw new IllegalStateException("cannot scan ServiceLoader descriptor directory: " + directory, error);
    }
  }

  private static void addFile(
      Path file,
      AnalysisContext context,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      Set<String> visited,
      ServiceLoaderDescriptorSource.ScanState state) {
    Path normalized = file.toAbsolutePath().normalize();
    if (!visited.add("descriptor:" + normalized) || normalized.getFileName() == null) {
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

}
