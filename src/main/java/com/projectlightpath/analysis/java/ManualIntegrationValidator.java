package com.projectlightpath.analysis.java;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;

/**
 * Behavior-neutral compile stub for the RED commit of #45 step 3. The real port replaces
 * this file; every method here exists only so {@code ManualIntegrationValidatorTest}
 * compiles and fails against unimplemented behavior.
 */
public final class ManualIntegrationValidator {

  /** Stub. */
  public static final String DEFAULT_SOURCE_REVISION = null;

  /** Stub. */
  public static final class ValidationError extends RuntimeException {
    ValidationError(String message) {
      super(message);
    }
  }

  private ManualIntegrationValidator() {}

  /** Stub. */
  public static int run(String[] args, Path cwd) {
    return 1;
  }

  /** Stub. */
  public static ObjectNode validate(ObjectNode context, ObjectNode facts,
      String expectedRevision) {
    throw new ValidationError("stub");
  }

  /** Stub. */
  public static String pythonJson(JsonNode node) {
    return "stub";
  }
}
