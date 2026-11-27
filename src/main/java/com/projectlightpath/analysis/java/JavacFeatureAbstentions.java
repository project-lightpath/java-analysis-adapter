package com.projectlightpath.analysis.java;

/**
 * The #62 per-member abstentions of one dynamic feature, grouped so the
 * {@link DynamicFeature} record keeps its historical parameter shape:
 * the descriptor member's correlation decline, the parameter-repair
 * decline, and the caller member's correlation decline — three
 * different members that can all abstain on the same feature.
 */
record JavacFeatureAbstentions(
    JavacAbstentionEvidence descriptor,
    JavacAbstentionEvidence repair,
    JavacAbstentionEvidence caller) {

  /** The no-evidence sentinel. */
  static final JavacFeatureAbstentions NONE =
      new JavacFeatureAbstentions(null, null, null);

  JavacFeatureAbstentions {
    descriptor = descriptor == null ? JavacAbstentionEvidence.NONE : descriptor;
    repair = repair == null ? JavacAbstentionEvidence.NONE : repair;
    caller = caller == null ? JavacAbstentionEvidence.NONE : caller;
  }

  /** Merges every present abstention's attributes into the target map. */
  void mergeInto(java.util.Map<String, String> attributes) {
    descriptor.mergeInto(attributes);
    if (repair.present()) {
      attributes.put("javacRepairAbstention", repair.code());
    }
    attributes.putAll(caller.callerAttributes());
  }
}
