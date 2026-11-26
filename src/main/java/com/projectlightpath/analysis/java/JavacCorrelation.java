package com.projectlightpath.analysis.java;

import java.util.Optional;

/**
 * The outcome of one javac member correlation (issue #62): either the
 * correlated member, or the per-member abstention evidence saying why
 * the correlation declined. Exactly one side is set — enforced by the
 * compact constructor — so a caller can never see both or neither.
 *
 * <p>The abstention half is the blast-radius-safe visibility channel:
 * it attaches to the affected fact's evidence attributes and never
 * enters {@code scan.diagnostics()}.
 */
record JavacCorrelation(
    JavacSourceSymbolIndex.MemberInfo member,
    JavacAbstentionEvidence abstention) {

  JavacCorrelation {
    if (member != null && abstention != null && abstention.present()) {
      throw new IllegalArgumentException(
          "a correlated member carries no abstention");
    }
    if (abstention == null) {
      abstention = JavacAbstentionEvidence.NONE;
    }
  }

  /** The correlated-member outcome. */
  static JavacCorrelation correlated(JavacSourceSymbolIndex.MemberInfo member) {
    return new JavacCorrelation(member, null);
  }

  /** The abstention outcome. */
  static JavacCorrelation abstained(JavacAbstentionEvidence evidence) {
    return new JavacCorrelation(null, evidence);
  }

  /** The empty outcome: the seam is off (flag opt-out, no index). */
  static JavacCorrelation empty() {
    return new JavacCorrelation(null, JavacAbstentionEvidence.NONE);
  }

  /** The correlated member, when this outcome correlates. */
  Optional<JavacSourceSymbolIndex.MemberInfo> memberOptional() {
    return Optional.ofNullable(member);
  }

  /** The abstention evidence, when this outcome abstains. */
  Optional<JavacAbstentionEvidence> abstentionOptional() {
    return abstention.present() ? Optional.of(abstention) : Optional.empty();
  }
}
