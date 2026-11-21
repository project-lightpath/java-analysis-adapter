package com.projectlightpath.analysis.java;

import java.util.Optional;

/**
 * RED_22 stub for the javac annotation read layer.
 *
 * <p>This stub exists only so the RED commit compiles: every lookup
 * returns empty and the repair trigger never fires, so flag-on behavior
 * is exactly today's textual output. The GREEN commit implements the
 * correlation and the repair; this surface is the contract the tests
 * compile against.
 */
final class JavacAnnotationAccess {
  private JavacAnnotationAccess() {}

  /** Returns the accessor bound to one scanner's context. */
  static JavacAnnotationAccess of(SourceMetadataScanner scanner) {
    return new JavacAnnotationAccess();
  }

  /** Reports whether the index seam flag is enabled. */
  boolean enabled() {
    return JavacSourceSymbolIndex.enabled();
  }

  /** Returns the unique index member matching owner, name, and arity. */
  Optional<JavacSourceSymbolIndex.MemberInfo> member(
      String typeName, String memberName, int parameterCount) {
    return Optional.empty();
  }

  /** Reports whether a raw parameter text needs javac repair. */
  boolean repairTriggered(String rawParameter) {
    return false;
  }

  /** Returns the repaired canonical spelling of one parameter, if unique. */
  Optional<String> repairedParameterType(
      String rawParameter, String typeName, String memberName,
      int parameterCount, int parameterIndex) {
    return Optional.empty();
  }

  /** Returns the annotation's simple name (last canonical segment). */
  static String simpleName(JavacSourceSymbolIndex.AnnotationInfo annotation) {
    String canonical = annotation.canonicalName();
    int separator = canonical.lastIndexOf('.');
    return separator < 0 ? canonical : canonical.substring(separator + 1);
  }

  /** Strips the renderer's surrounding quotes from a string value. */
  static String unquoted(String renderedValue) {
    if (renderedValue != null && renderedValue.length() >= 2
        && renderedValue.startsWith("\"") && renderedValue.endsWith("\"")) {
      return renderedValue.substring(1, renderedValue.length() - 1);
    }
    return renderedValue;
  }
}
