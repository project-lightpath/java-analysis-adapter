package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/** Authorizes descriptor paths against the selected analysis inputs. */
final class ServiceLoaderDescriptorAuthorization {
  private ServiceLoaderDescriptorAuthorization() {}

  static boolean authorizedPath(
      AnalysisContext context, Path path, List<Path> dependencyEntries) {
    Path normalized = path.toAbsolutePath().normalize();
    if (under(normalized, context.applicationClasses())) {
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
    return explicitlySelectedMetadataLocation(context, normalized);
  }

  private static boolean explicitlySelectedMetadataLocation(
      AnalysisContext context, Path normalized) {
    Path repositoryRoot = context.repositoryRoot().toAbsolutePath().normalize();
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
        continue;
      }
      Path selected = (declared.isAbsolute() ? declared : repositoryRoot.resolve(declared))
          .toAbsolutePath().normalize();
      if (sameRealPath(selected, repositoryRoot)
          || !under(selected, repositoryRoot)
          || !sameRealPath(selected, normalized)) {
        continue;
      }
      return true;
    }
    return false;
  }

  private static boolean sameRealPath(Path left, Path right) {
    try {
      return left.toRealPath().equals(right.toRealPath());
    } catch (IOException | SecurityException error) {
      return false;
    }
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
