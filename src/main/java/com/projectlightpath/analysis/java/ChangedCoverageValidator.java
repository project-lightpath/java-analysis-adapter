package com.projectlightpath.analysis.java;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Behavior-neutral compile stub for the RED commit of #45 step 4. The real port replaces
 * this file; every method here exists only so {@code ChangedCoverageValidatorTest}
 * compiles and fails against unimplemented behavior.
 */
public final class ChangedCoverageValidator {

  /** Stub. */
  public static final class ValidationError extends RuntimeException {
    ValidationError(String message) {
      super(message);
    }
  }

  /** Stub. */
  public record JacocoCoverage(Map<String, Set<Long>> executable,
      Map<String, Set<Long>> covered) {}

  private ChangedCoverageValidator() {}

  /** Stub. */
  public static int run(String[] args, Path cwd) {
    return 1;
  }

  /** Stub. */
  public static Map<String, Set<Long>> parseUnifiedDiff(String diffText) {
    return Map.of();
  }

  /** Stub. */
  public static Map<String, Set<Long>> changedLines(Path root, String base, String head,
      String suffix, Path evidence) {
    throw new ValidationError("stub");
  }

  /** Stub. */
  public static Map<String, Set<Long>> gitChangedLines(Path root, String base,
      String head, String suffix) {
    throw new ValidationError("stub");
  }

  /** Stub. */
  public static String sourceKey(String pkg, String name, String sourceRoot) {
    return "";
  }

  /** Stub. */
  public static boolean isProductionPath(String path, String sourceRoot) {
    return false;
  }

  /** Stub. */
  public static JacocoCoverage jacocoLines(Path report, String sourceRoot) {
    throw new ValidationError("stub");
  }

  /** Stub. */
  public static double round2(double value) {
    return value;
  }

  /** Stub. */
  public static String format2(double value) {
    return "stub";
  }

  /** Stub. */
  public static String sha256(Path file) {
    return "stub";
  }
}
