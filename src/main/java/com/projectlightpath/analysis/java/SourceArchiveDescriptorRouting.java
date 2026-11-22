package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

/**
 * Input-mode routing for the descriptor scan (issue #42): a source archive
 * is never descriptor-scanned as a binary, and a SOURCE_ARCHIVE run without
 * resolved binaries records that descriptor metadata is unavailable.
 *
 * <p>The {@code descriptor_metadata_unavailable_for_source_archive} and
 * {@code source_archive_on_dependency_classpath} diagnostics are
 * scan-global: they block DEFINITE promotion for the whole scan.
 */
final class SourceArchiveDescriptorRouting {
  private SourceArchiveDescriptorRouting() {}

  /** Filters the resolved dependency entries for descriptor scanning. */
  static List<Path> routedDependencyEntries(
      AnalysisContext context,
      SootUpInputLocationFactory.DependencyResolution dependencyResolution,
      ServiceLoaderDescriptorSource.ScanState state) {
    if (context.inputMode() != AnalysisContext.InputMode.SOURCE_ARCHIVE) {
      return dependencyResolution.entries();
    }
    List<Path> routed = new ArrayList<>();
    for (Path entry : dependencyResolution.entries()) {
      if (context.sourceArchives().contains(entry)) {
        state.diagnostic(
            "source_archive_on_dependency_classpath", entry.toString(),
            "a source archive was resolved as a dependency classpath entry and was not scanned");
        continue;
      }
      routed.add(entry);
    }
    if (routed.isEmpty()) {
      Path archive = context.sourceArchives().get(0);
      state.diagnostic(
          "descriptor_metadata_unavailable_for_source_archive",
          archive.toAbsolutePath().normalize().toString(),
          "source archive input has no SBOM-resolved binary descriptor metadata");
    }
    return List.copyOf(routed);
  }

  /**
   * Records the SOURCE_TREE limitation when the caller explicitly selected
   * that mode and no dependency binaries were resolved. Limitations never
   * block DEFINITE promotion; the default mode stays silent for parity.
   */
  static List<String> modeLimitations(
      AnalysisContext context,
      SootUpInputLocationFactory.DependencyResolution dependencyResolution) {
    if (context.explicitInputMode() == AnalysisContext.InputMode.SOURCE_TREE
        && dependencyResolution.entries().isEmpty()) {
      return List.of(
          "dependency binaries are unavailable for this source tree; checked-in "
              + "META-INF/services descriptors are the only descriptor source");
    }
    return List.of();
  }

  /**
   * Display path for one descriptor file: repository-relative by default;
   * for SOURCE_ARCHIVE runs, extracted roots are reported with their stable
   * declared paths, and inputs outside every source root (application
   * classes, dependency jars) fall back to the repository-relative form
   * instead of machine-absolute paths.
   */
  static String displayPath(AnalysisContext context, Path path) {
    if (context.inputMode() == AnalysisContext.InputMode.SOURCE_ARCHIVE) {
      java.util.Optional<AnalysisContext.SourceRoot> root =
          AnalysisContext.mostSpecificRoot(
              path.toAbsolutePath().normalize(), context.includedSourceRoots());
      if (root.isPresent()) {
        return context.displayPath(path);
      }
    }
    Path root = context.repositoryRoot().toAbsolutePath().normalize();
    return path.startsWith(root)
        ? root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/")
        : path.toString();
  }
}
