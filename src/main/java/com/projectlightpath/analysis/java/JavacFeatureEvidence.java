package com.projectlightpath.analysis.java;

/**
 * The javac evidence of one dynamic feature, grouped so the
 * {@link DynamicFeature} record keeps its historical parameter shape:
 * the #25 target evidence and the #62 per-member abstentions.
 */
record JavacFeatureEvidence(
    JavacTargetEvidence target,
    JavacFeatureAbstentions abstentions) {

  /** The no-evidence sentinel. */
  static final JavacFeatureEvidence NONE =
      new JavacFeatureEvidence(null, null);

  JavacFeatureEvidence {
    target = target == null ? JavacTargetEvidence.NONE : target;
    abstentions = abstentions == null ? JavacFeatureAbstentions.NONE : abstentions;
  }
}
