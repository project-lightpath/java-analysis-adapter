package com.projectlightpath.analysis.java;

import java.util.List;
import java.util.Optional;

/**
 * javac index shapes for the injection-contract type matching (issue #61).
 *
 * <p>RED-stage compile stub (behavior-neutral, per AGENTS): every method
 * returns empty, so the matching surface keeps its exact textual behavior
 * while the migration cells compile. The GREEN commit replaces the stub
 * with the seam implementation: the canonical erased shape of one
 * injection parameter (the correlated declaring member), of one bean
 * candidate's own declaration, and of one candidate's direct supertypes —
 * each javac-resolved, each participating only as a repair of a textual
 * vacuum or a generics-gated additional disjunct (PLAN-61 §3).
 */
final class JavacInjectionTypeShapes {
  private final SourceMetadataScanner scanner;

  private JavacInjectionTypeShapes(SourceMetadataScanner scanner) {
    this.scanner = scanner;
  }

  /** Returns the shapes bound to one scanner's context. */
  static JavacInjectionTypeShapes of(SourceMetadataScanner scanner) {
    return new JavacInjectionTypeShapes(scanner);
  }

  /**
   * The canonical erased index shape of one injection parameter, or empty
   * on every decline (flag off, absent index, ambiguous overload,
   * error-typed member, multi-type owner, phantom constructor guard).
   */
  Optional<String> requestedShape(
      SourceMetadataScannerState.InjectionPoint injection,
      int parameterIndex) {
    return Optional.empty();
  }

  /**
   * The canonical erased identity of the candidate's own declaration, or
   * empty on every decline.
   */
  Optional<String> candidateShape(
      SourceMetadataScannerState.BeanCandidate candidate) {
    return Optional.empty();
  }

  /**
   * The canonical erased DIRECT supertypes of the candidate's declared
   * type, or empty on every decline.
   */
  Optional<List<String>> contractShapes(
      SourceMetadataScannerState.BeanCandidate candidate) {
    return Optional.empty();
  }
}
