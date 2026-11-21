package com.projectlightpath.analysis.java;

/** Central certainty policy for open-world dynamic behavior. */
final class DynamicCertaintyPolicy {
  static final String LIMITATION =
      "Open-world dynamic behavior does not identify a unique runtime target.";

  private DynamicCertaintyPolicy() {}

  /** Returns whether a provider relationship type must remain unresolved. */
  static boolean isOpenWorldProviderRelationship(String relationshipType) {
    return "reflection".equals(relationshipType)
        || "reflection_api".equals(relationshipType)
        || "dynamic".equals(relationshipType)
        || "dynamic_proxy".equals(relationshipType)
        || "proxy_dispatch".equals(relationshipType);
  }

  /**
   * Returns whether framework evidence identifies an open-world dynamic site.
   * Dynamic evidence overrides bounded model rules.
   */
  static boolean isOpenWorldFrameworkFact(SemanticCallFacts.Fact fact) {
    boolean proxyDispatch = "proxy_dispatch".equals(fact.relationshipType());
    if (!proxyDispatch && isOpenWorldProviderRelationship(fact.relationshipType())) {
      return true;
    }
    if (!proxyDispatch) {
      return fact.evidence().stream().anyMatch(DynamicCertaintyPolicy::isDynamicEvidence);
    }
    return "quarkus-unbounded-proxy".equals(fact.modelRule())
        || "spring-open-reflection".equals(fact.modelRule())
        || fact.evidence().stream().anyMatch(DynamicCertaintyPolicy::isDynamicEvidence);
  }

  /** Returns whether evidence identifies reflection or an unbounded proxy call. */
  private static boolean isDynamicEvidence(SemanticCallFacts.Evidence evidence) {
    return "reflection_api".equals(evidence.kind())
        || "dynamic_proxy_call".equals(evidence.kind());
  }
}
