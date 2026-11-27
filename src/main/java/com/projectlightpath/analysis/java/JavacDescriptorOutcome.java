package com.projectlightpath.analysis.java;

import java.util.Optional;

/**
 * One descriptor-building outcome with its per-member javac abstention
 * evidence (issue #62): the descriptor (textual or index-rendered,
 * unchanged in value from the pre-#62 behavior) plus the decline codes
 * of the lookups that fell back — the correlation abstention (seam A)
 * and, when the per-parameter repair path also declined, the repair
 * lookup's own code (seam B, under its own {@code
 * javacRepairAbstention} key; the repair only runs after a correlation
 * abstention, so it is always secondary).
 */
record JavacDescriptorOutcome(
    Optional<String> descriptor,
    JavacAbstentionEvidence abstention,
    JavacAbstentionEvidence repairAbstention) {

  JavacDescriptorOutcome {
    descriptor = descriptor == null ? Optional.empty() : descriptor;
    abstention = abstention == null ? JavacAbstentionEvidence.NONE : abstention;
    repairAbstention =
        repairAbstention == null ? JavacAbstentionEvidence.NONE : repairAbstention;
  }

  /** The plain outcome: a descriptor with no abstention evidence. */
  static JavacDescriptorOutcome of(String descriptor) {
    return new JavacDescriptorOutcome(
        Optional.ofNullable(descriptor), null, null);
  }

  /** The outcome's abstention attributes, merged into the target map. */
  void mergeAbstentionsInto(java.util.Map<String, String> attributes) {
    abstention.mergeInto(attributes);
    if (repairAbstention.present()) {
      attributes.put("javacRepairAbstention", repairAbstention.code());
    }
  }
}
