package com.projectlightpath.analysis.java;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Behavior-neutral compile stub for the RED commit of #45 step 2. The real port replaces
 * this file; every method here exists only so {@code SpikeContractValidatorTest} compiles
 * and fails against unimplemented behavior.
 */
public final class SpikeContractValidator {
  private SpikeContractValidator() {}

  /** Stub. */
  public static final class ValidationError extends RuntimeException {
    ValidationError(String message) {
      super(message);
    }
  }

  /** Stub. */
  public static int run(String[] args, Path root) {
    return 1;
  }

  /** Stub. */
  public static String validate(Path root) {
    throw new ValidationError("stub");
  }

  /** Stub. */
  public static Path resolveRepositoryRoot(Path start) {
    throw new ValidationError("stub: repository root");
  }

  /** Stub. */
  static boolean truthy(Object node) {
    return false;
  }

  /** Stub. */
  static boolean containsHostPath(Object node) {
    return false;
  }

  /** Stub. */
  static boolean javacContainsHostPath(Object node) {
    return false;
  }

  /** Stub. */
  static Set<String> requiredJavacCells() {
    return Set.of();
  }

  /** Stub. */
  static Map<String, Long> recomputeTotals(Object observation) {
    return Map.of();
  }

  /** Stub. */
  static Map<String, Long> totalsAsLongMap(Object totals) {
    return Map.of();
  }
}
