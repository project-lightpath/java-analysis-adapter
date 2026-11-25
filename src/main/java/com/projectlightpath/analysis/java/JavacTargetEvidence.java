package com.projectlightpath.analysis.java;

import java.util.List;

/**
 * The javac symbol evidence for one source-bound reflection target
 * (issue #25). At most one of {@code sourceBound} and {@code
 * diagnosticCode} is meaningful: a source-bound target carries the
 * javac-proven binary keying and shapes; a diagnostic target is one the
 * index sees but cannot prove (error, duplicate, ambiguity, visibility,
 * shape mismatch); {@link #NONE} means the site stays on the pre-existing
 * runtime-string path with no javac evidence at all.
 */
record JavacTargetEvidence(
    boolean sourceBound,
    String keyedTarget,
    String targetKind,
    String jvmDescriptor,
    List<String> parameterTypes,
    List<String> annotationNames,
    String diagnosticCode) {

  /** The no-evidence sentinel: the runtime-string path, unchanged. */
  static final JavacTargetEvidence NONE =
      new JavacTargetEvidence(false, "", "", "", List.of(), List.of(), "");

  JavacTargetEvidence {
    keyedTarget = keyedTarget == null ? "" : keyedTarget;
    targetKind = targetKind == null ? "" : targetKind;
    jvmDescriptor = jvmDescriptor == null ? "" : jvmDescriptor;
    parameterTypes = List.copyOf(parameterTypes == null ? List.of() : parameterTypes);
    annotationNames = List.copyOf(annotationNames == null ? List.of() : annotationNames);
    diagnosticCode = diagnosticCode == null ? "" : diagnosticCode;
  }

  /** Reports whether this evidence changes the fact's certainty at all. */
  boolean present() {
    return sourceBound || !diagnosticCode.isBlank();
  }
}
