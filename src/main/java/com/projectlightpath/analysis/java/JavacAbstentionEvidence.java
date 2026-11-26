package com.projectlightpath.analysis.java;

import java.util.List;
import java.util.Map;

/**
 * The per-member javac abstention evidence for one declined lookup
 * (issue #62). The blast-radius-safe visibility channel: the evidence
 * attaches to the affected fact's evidence attributes and NEVER enters
 * {@code scan.diagnostics()} — routing it there would block DEFINITE
 * promotion for the WHOLE scan
 * ({@code ServiceLoaderDescriptorScanner.isUnique} requires empty
 * scan diagnostics), downgrading certainty for every archive and entry
 * from unrelated abstentions.
 *
 * <p>The evidence is visibility-only: it never changes a fact's
 * certainty, status, limitation, or ID — the abstaining consumer keeps
 * its textual fallback answer and this record says WHY.
 */
record JavacAbstentionEvidence(
    String code,
    String member,
    List<String> indexDiagnostics) {

  /** The no-evidence sentinel: the lookup succeeded or the flag is off. */
  static final JavacAbstentionEvidence NONE =
      new JavacAbstentionEvidence("", "", List.of());

  JavacAbstentionEvidence {
    code = code == null ? "" : code;
    member = member == null ? "" : member;
    indexDiagnostics = List.copyOf(
        indexDiagnostics == null ? List.of() : indexDiagnostics);
    // present() cannot be called here: the record fields are assigned
    // from the normalized parameters only AFTER the compact constructor
    // body completes, so an instance method would read null fields.
    if (!code.isBlank() && member.isBlank()) {
      throw new IllegalArgumentException("abstention member must not be blank");
    }
  }

  /** Reports whether this record carries evidence at all. */
  boolean present() {
    return !code.isBlank();
  }

  /** The member identity: {@code owner#name(arity)}. */
  static String memberIdentity(String owner, String name, int arity) {
    return owner + "#" + name + "(" + arity + ")";
  }

  /**
   * The evidence attributes contributed to the affected fact: the code
   * and member identity, plus the underlying javac/index error codes
   * for the member's file when the index recorded any. Absent evidence
   * contributes nothing.
   */
  Map<String, String> attributes() {
    if (!present()) {
      return Map.of();
    }
    Map<String, String> attributes = new java.util.LinkedHashMap<>();
    attributes.put("javacAbstention", code);
    attributes.put("javacAbstentionMember", member);
    if (!indexDiagnostics.isEmpty()) {
      attributes.put("javacAbstentionDiagnostics", String.join(",", indexDiagnostics));
    }
    return attributes;
  }

  /**
   * The evidence attributes contributed to the affected fact, under the
   * CALLER-member keys ({@code javacCallerAbstention} family) — the
   * reflection/proxy classification seam's decline, distinct from the
   * descriptor-member {@code javacAbstention} family.
   */
  Map<String, String> callerAttributes() {
    if (!present()) {
      return Map.of();
    }
    Map<String, String> attributes = new java.util.LinkedHashMap<>();
    attributes.put("javacCallerAbstention", code);
    attributes.put("javacCallerAbstentionMember", member);
    if (!indexDiagnostics.isEmpty()) {
      attributes.put("javacCallerAbstentionDiagnostics", String.join(",", indexDiagnostics));
    }
    return attributes;
  }

  /**
   * The evidence attributes contributed under the PRODUCER-TYPE keys
   * ({@code javacProducerTypeAbstention} family): the producer-type
   * seam's own correlation decline, distinct from the descriptor
   * member's {@code javacAbstention} family because the two seams
   * derive the owner differently (the type seam keeps its historical
   * raw-extraction anchor) and both can decline for one candidate.
   */
  Map<String, String> producerTypeAttributes() {
    if (!present()) {
      return Map.of();
    }
    Map<String, String> attributes = new java.util.LinkedHashMap<>();
    attributes.put("javacProducerTypeAbstention", code);
    attributes.put("javacProducerTypeAbstentionMember", member);
    if (!indexDiagnostics.isEmpty()) {
      attributes.put("javacProducerTypeAbstentionDiagnostics",
          String.join(",", indexDiagnostics));
    }
    return attributes;
  }

  /** Merges this record's attributes into an existing map, in place. */
  void mergeInto(Map<String, String> attributes) {
    attributes.putAll(attributes());
  }
}
