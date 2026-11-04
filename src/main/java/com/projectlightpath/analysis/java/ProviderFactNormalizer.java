package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.List;

/** Converts provider graph observations into provider-only SemanticCallFacts. */
final class ProviderFactNormalizer {
  private ProviderFactNormalizer() {}

  static List<SemanticCallFacts.Fact> normalize(AnalysisContext context, AnalysisGraph graph) {
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    for (CallEdge edge : graph.calls()) {
      String id = "sootup-call-" + Integer.toHexString(
          (edge.caller().descriptor() + "->" + edge.callee().descriptor() + "@" + edge.line()).hashCode());
      String limitation = edge.certaintyHint() == SemanticCallFacts.Certainty.POSSIBLE
          ? "CHA exposes a bounded implementation/callback candidate; runtime selection is not unique."
          : null;
      facts.add(SemanticCallFacts.Fact.provider(
          id,
          context,
          edge.caller().displayName(),
          edge.callee().displayName(),
          edge.relationshipType(),
          edge.certaintyHint(),
          List.of(SemanticCallFacts.Evidence.sootup(
              edge.sourcePath(),
              edge.line(),
              edge.caller(),
              edge.callee(),
              "SootUp CHA application call")),
          limitation));
    }
    for (DynamicFeature feature : graph.dynamicFeatures()) {
      String relationship = feature.kind() == DynamicFeature.Kind.REFLECTION ? "reflection" : "proxy_dispatch";
      String id = "sootup-" + feature.kind().name().toLowerCase() + "-" + Integer.toHexString(
          (feature.caller().descriptor() + "->" + feature.target()).hashCode());
      facts.add(SemanticCallFacts.Fact.provider(
          id,
          context,
          feature.caller().displayName(),
          feature.target(),
          relationship,
          SemanticCallFacts.Certainty.UNRESOLVED,
          List.of(new SemanticCallFacts.Evidence(
              feature.kind() == DynamicFeature.Kind.REFLECTION ? "reflection_api" : "dynamic_proxy_call",
              feature.sourcePath(),
              feature.symbols(),
              null,
              java.util.Map.of("basis", "SootUp graph does not contain a unique dynamic target"))),
          feature.limitation()));
    }
    return List.copyOf(facts);
  }
}
