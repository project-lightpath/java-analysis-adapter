package com.projectlightpath.analysis.java;

import java.nio.file.Path;
import java.util.List;

/**
 * Build-time validation for the explicit input-mode contract (issue #42):
 * mode selection is caller state, never guessed, and ambiguous
 * provisioning fails closed. Mirrors the placement pattern of
 * {@code DependencyCoordinateManifestValidator}.
 */
final class AnalysisContextInputModeValidator {
  private AnalysisContextInputModeValidator() {}

  static void validate(AnalysisContext context, AnalysisContext.Builder builder) {
    AnalysisContext.InputMode inputMode = context.explicitInputMode();
    if (inputMode == null || inputMode == AnalysisContext.InputMode.SOURCE_TREE) {
      requireRequiredSourceRoot(context);
      if (!context.sourceArchives().isEmpty()) {
        throw new IllegalArgumentException(
            "source archives require inputMode SOURCE_ARCHIVE");
      }
      return;
    }
    if (inputMode == AnalysisContext.InputMode.SOURCE_ARCHIVE) {
      validateSourceArchiveMode(context, builder);
      return;
    }
    validateBinaryArchiveMode(context);
  }

  private static void requireRequiredSourceRoot(AnalysisContext context) {
    if (context.sourceRoots().stream().noneMatch(root -> !root.generated() && root.required())) {
      throw new IllegalArgumentException("at least one required production/source root is required");
    }
  }

  private static void validateSourceArchiveMode(
      AnalysisContext context, AnalysisContext.Builder builder) {
    if (context.sourceArchives().isEmpty()) {
      throw new IllegalArgumentException(
          "inputMode SOURCE_ARCHIVE requires at least one source archive");
    }
    if (context.analysisScope() == AnalysisContext.Scope.TEST) {
      // Extracted roots are PRODUCTION roots; a TEST-scope run would
      // include zero of them and silently skip the source analysis
      // (issue #42 review F2).
      throw new IllegalArgumentException(
          "inputMode SOURCE_ARCHIVE requires the production analysis scope");
    }
    if (!builder.extractedSourceRoots
        && (!context.sourceRoots().isEmpty() || !context.generatedSourceRoots().isEmpty())) {
      throw new IllegalArgumentException(
          "inputMode SOURCE_ARCHIVE must not declare source roots");
    }
    for (Path archive : context.sourceArchives()) {
      if (context.dependencyClasspath().contains(archive)) {
        throw new IllegalArgumentException(
            "source archive must not also appear on the dependency classpath: " + archive);
      }
    }
  }

  private static void validateBinaryArchiveMode(AnalysisContext context) {
    // BINARY_ARCHIVE: descriptor/module metadata comes from the dependency
    // classpath alone; the required-root check is satisfied by its entries.
    if (!context.sourceArchives().isEmpty()) {
      throw new IllegalArgumentException(
          "source archives require inputMode SOURCE_ARCHIVE");
    }
    if (!context.sourceRoots().isEmpty() || !context.generatedSourceRoots().isEmpty()) {
      throw new IllegalArgumentException(
          "inputMode BINARY_ARCHIVE must not declare source roots");
    }
    if (context.dependencyClasspath().isEmpty()) {
      throw new IllegalArgumentException(
          "inputMode BINARY_ARCHIVE requires at least one dependency classpath entry");
    }
  }

}

