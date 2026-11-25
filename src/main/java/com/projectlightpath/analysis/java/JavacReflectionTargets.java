package com.projectlightpath.analysis.java;

import java.util.Objects;

/**
 * Resolves source-bound reflection targets against the javac source-symbol
 * index (issue #25): a {@code Class.forName} / {@code ClassLoader
 * .loadClass} literal or a {@code getMethod}-family site whose receiver
 * provably names a source-defined type is keyed by its binary name and
 * proven with javac shapes; runtime strings return {@link
 * JavacTargetEvidence#NONE} and keep the pre-existing abstention.
 */
final class JavacReflectionTargets {

  private JavacReflectionTargets() {
  }

  /** Returns the resolver bound to one scanner's context. */
  static JavacReflectionTargets of(SourceMetadataScanner scanner) {
    Objects.requireNonNull(scanner);
    return new JavacReflectionTargets();
  }

  /**
   * Behavior-neutral stub (RED_25): no site carries javac evidence yet.
   * The GREEN implementation resolves class and member targets through
   * {@link JavacSourceSymbolIndex} behind the symbolindex flag.
   */
  JavacTargetEvidence evidence(
      SourceMetadataScannerState.SourceFile file,
      SourceMetadataScannerState.DynamicSite site) {
    Objects.requireNonNull(file);
    Objects.requireNonNull(site);
    return JavacTargetEvidence.NONE;
  }
}
