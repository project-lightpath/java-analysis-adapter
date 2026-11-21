package com.projectlightpath.analysis.java;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Classifies descriptor paths against the selected analysis inputs. */
final class ServiceLoaderDescriptorProvenance {
  private ServiceLoaderDescriptorProvenance() {}

  /**
   * Returns the explicitly selected service descriptor locations: observed
   * metadata paths that stay inside the repository root and are not the root
   * itself.
   */
  static List<Path> selectedLocations(AnalysisContext context) {
    Path repositoryRoot = context.repositoryRoot().toAbsolutePath().normalize();
    List<Path> result = new ArrayList<>();
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
      if (ServiceLoaderDescriptorAuthorization.sameRealPath(selected, repositoryRoot)
          || !ServiceLoaderDescriptorAuthorization.under(selected, repositoryRoot)) {
        continue;
      }
      result.add(selected);
    }
    return List.copyOf(result);
  }

  /** True when {@code normalized} is one of the explicitly selected locations. */
  static boolean explicitlySelected(AnalysisContext context, Path normalized) {
    return selectedLocations(context).stream()
        .anyMatch(selected -> ServiceLoaderDescriptorAuthorization.sameRealPath(selected, normalized));
  }

  /**
   * True when a descriptor path is backed by the selected output, a dependency
   * entry, or an explicitly selected metadata location (file or directory).
   * Source-root-derived resources are not classpath-backed unless selected
   * explicitly, so they cannot by themselves earn DEFINITE certainty.
   */
  static boolean classpathBacked(
      AnalysisContext context, Path path, List<Path> dependencyEntries) {
    Path normalized = path.toAbsolutePath().normalize();
    if (ServiceLoaderDescriptorAuthorization.under(normalized, context.applicationClasses())) {
      return true;
    }
    for (Path dependency : dependencyEntries) {
      if (ServiceLoaderDescriptorAuthorization.under(normalized, dependency)) {
        return true;
      }
    }
    return selectedLocations(context).stream()
        .anyMatch(selected -> ServiceLoaderDescriptorAuthorization.sameRealPath(selected, normalized)
            || ServiceLoaderDescriptorAuthorization.under(normalized, selected));
  }
}
