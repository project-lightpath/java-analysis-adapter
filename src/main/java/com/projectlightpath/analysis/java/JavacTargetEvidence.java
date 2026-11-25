package com.projectlightpath.analysis.java;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The javac symbol evidence for one source-bound reflection target
 * (issue #25). At most one of {@code sourceBound} and {@code
 * diagnosticCode} is meaningful: a source-bound target carries the
 * javac-proven binary keying and shapes; a diagnostic target is one the
 * index sees but cannot prove (error, duplicate, ambiguity, visibility,
 * shape mismatch); {@link #NONE} means the site stays on the pre-existing
 * runtime-string path with no javac evidence at all.
 *
 * <p>The evidence also renders its own fact consumption (certainty,
 * callee, limitation, evidence attributes) so the normalizer stays free
 * of branching — a PMD-forced placement, behavior identical.
 */
record JavacTargetEvidence(
    boolean sourceBound,
    String keyedTarget,
    String targetKind,
    String jvmDescriptor,
    List<String> parameterTypes,
    List<String> annotationNames,
    String diagnosticCode) {

  private static final String BOUND_BASIS =
      "javac source-symbol index proves the source-defined target";
  private static final String UNPROVEN_BASIS =
      "javac source-symbol index could not prove the reflection target";
  private static final String FALLBACK_BASIS =
      "SootUp graph does not contain a unique dynamic target";

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

  /** The fact certainty: proven DEFINITE, diagnostic POSSIBLE, else unchanged. */
  SemanticCallFacts.Certainty certainty() {
    if (sourceBound) {
      return SemanticCallFacts.Certainty.DEFINITE;
    }
    return diagnosticCode.isBlank()
        ? SemanticCallFacts.Certainty.UNRESOLVED
        : SemanticCallFacts.Certainty.POSSIBLE;
  }

  /** The keyed callee, or the site's textual target when nothing is keyed. */
  String callee(String fallback) {
    return keyedTarget.isEmpty() ? fallback : keyedTarget;
  }

  /** The limitation text, or the site's pre-existing one when absent. */
  String limitation(String fallback) {
    if (sourceBound) {
      return "Source-defined reflection target; shape proven by the javac source-symbol index.";
    }
    if (!diagnosticCode.isBlank()) {
      return "Reflection target seen by the javac source-symbol index but not proven: "
          + diagnosticCode + ".";
    }
    return fallback;
  }

  /**
   * The evidence attributes: the pre-existing shape when no evidence is
   * present, the recorded javac symbol evidence (javacSymbol,
   * reflectionTargetKind, parameter and annotation shapes, diagnostic)
   * when it is. The textual target descriptor of proxy features is
   * preserved in the absent arm and extended with the JVM descriptor of
   * proven member targets.
   */
  Map<String, String> attributes(String textualTargetDescriptor) {
    if (!present()) {
      if (textualTargetDescriptor.isBlank()) {
        return Map.of("basis", FALLBACK_BASIS);
      }
      return Map.of("basis", FALLBACK_BASIS,
          "targetDescriptor", textualTargetDescriptor);
    }
    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put("basis", sourceBound ? BOUND_BASIS : UNPROVEN_BASIS);
    if (sourceBound) {
      attributes.put("javacSymbol", keyedTarget);
      attributes.put("sourceBound", "true");
      attributes.put("reflectionTargetKind", targetKind);
    } else {
      attributes.put("diagnostic", diagnosticCode);
    }
    if (!jvmDescriptor.isBlank()) {
      attributes.put("targetDescriptor", jvmDescriptor);
    }
    if (!parameterTypes.isEmpty()) {
      attributes.put("parameterTypes", String.join(",", parameterTypes));
    }
    if (!annotationNames.isEmpty()) {
      attributes.put("annotationNames", String.join(",", annotationNames));
    }
    return attributes;
  }
}
