package com.projectlightpath.analysis.java;

import java.nio.file.Path;

/**
 * Behavior-neutral compile stub for the RED commit of #45 step 4. The real port replaces
 * this file; every method here exists only so {@code ContractFixtureValidatorTest}
 * compiles and fails against unimplemented behavior.
 */
public final class ContractFixtureValidator {

  /** Stub. */
  public static final class ValidationError extends RuntimeException {
    ValidationError(String message) {
      super(message);
    }
  }

  /** Stub. */
  public record Result(String vpeSha, int trackedEntries, String fixtureSha256,
      boolean fixtureBytesEqual) {}

  private ContractFixtureValidator() {}

  /** Stub. */
  public static int run(String[] args) {
    return 1;
  }

  /** Stub. */
  public static Result validate(Path javaRoot, Path vpeRoot, String vpeSha,
      Path manifest) {
    throw new ValidationError("stub");
  }

  /** Stub. */
  public static String gitBlob(Path path) {
    return "stub";
  }
}
