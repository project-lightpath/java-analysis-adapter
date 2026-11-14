package com.projectlightpath.analysis.java;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Validates coordinate ownership and Maven replay invariants at the model boundary. */
final class DependencyCoordinateManifestValidator {
  private DependencyCoordinateManifestValidator() {}

  static void validate(AnalysisContext context) {
    AnalysisContext.DependencyCoordinateManifest manifest = context.dependencyCoordinateManifest();
    if (manifest.present() != context.handoff().hasReplayMetadata()) {
      throw new IllegalArgumentException(
          "dependency coordinate manifest and Maven replay metadata must be present together");
    }
    switch (manifest.presence()) {
      case ABSENT -> validateAbsent(context.provenance());
      case PRESENT_EMPTY -> validatePresentEmpty(context);
      case PRESENT_NONEMPTY -> validatePresentNonEmpty(manifest, context);
    }
  }

  private static void validateAbsent(AnalysisContext.Provenance provenance) {
    if (provenance.dependencyCoordinateFingerprint() != null) {
      throw new IllegalArgumentException(
          "dependencyCoordinateFingerprint requires a dependency coordinate manifest");
    }
  }

  private static void validatePresentEmpty(AnalysisContext context) {
    if (!context.dependencyClasspath().isEmpty() || context.dependencyClasspathRequired()) {
      throw new IllegalArgumentException(
          "present-empty dependency coordinate manifest requires an empty optional classpath");
    }
    if (context.provenance().dependencyCoordinateFingerprint() != null) {
      throw new IllegalArgumentException(
          "present-empty dependency coordinate manifest must not have a coordinate fingerprint");
    }
  }

  private static void validatePresentNonEmpty(
      AnalysisContext.DependencyCoordinateManifest manifest, AnalysisContext context) {
    List<Path> dependencyClasspath = context.dependencyClasspath();
    if (dependencyClasspath.isEmpty() || dependencyClasspath.stream().anyMatch(
        DependencyCoordinateManifestValidator::isWildcard)) {
      throw new IllegalArgumentException(
          "non-empty dependency coordinate manifest requires concrete dependency classpath entries");
    }
    if (!manifest.fingerprint().equals(context.provenance().dependencyCoordinateFingerprint())) {
      throw new IllegalArgumentException(
          "dependency coordinate manifest fingerprint does not match provenance dependencyCoordinateFingerprint");
    }
    Set<Path> classpathEntries = Set.copyOf(dependencyClasspath);
    Set<Path> coordinateEntries = manifest.entries().stream()
        .map(AnalysisContext.DependencyCoordinateEntry::classpathEntry)
        .collect(Collectors.toSet());
    if (!classpathEntries.equals(coordinateEntries)) {
      throw new IllegalArgumentException(
          "dependency coordinate manifest paths must exactly match normalized dependency classpath entries");
    }
  }

  private static boolean isWildcard(Path path) {
    return path.toString().contains("*");
  }
}
