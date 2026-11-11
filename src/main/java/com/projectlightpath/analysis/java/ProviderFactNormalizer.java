package com.projectlightpath.analysis.java;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Converts provider graph observations into provider-only SemanticCallFacts. */
final class ProviderFactNormalizer {
  private ProviderFactNormalizer() {}

  static List<SemanticCallFacts.Fact> normalize(AnalysisContext context, AnalysisGraph graph) {
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    Map<String, Integer> baseIdCounts = new HashMap<>();
    for (CallEdge edge : graph.calls()) {
      baseIdCounts.merge(legacyCallId(edge), 1, Integer::sum);
    }
    for (CallEdge edge : graph.calls()) {
      String callIdentity = edge.caller().displayName() + "|" + edge.caller().descriptor()
          + "->" + edge.callee().displayName() + "|" + edge.callee().descriptor()
          + "@" + edge.line() + "|" + edge.sourcePath() + "|" + edge.relationshipType();
      String baseId = legacyCallId(edge);
      String id = baseIdCounts.get(baseId) == 1
          ? uniqueId(ids, baseId, callIdentity)
          : uniqueId(ids, baseId + "-" + sha256(callIdentity), callIdentity);
      boolean openWorldDynamic = DynamicCertaintyPolicy.isOpenWorldProviderRelationship(
          edge.relationshipType());
      SemanticCallFacts.Certainty certainty = openWorldDynamic
          ? SemanticCallFacts.Certainty.UNRESOLVED
          : edge.certaintyHint();
      String limitation = openWorldDynamic
          ? DynamicCertaintyPolicy.LIMITATION
          : certainty == SemanticCallFacts.Certainty.POSSIBLE
          ? "CHA exposes a bounded implementation/callback candidate; runtime selection is not unique."
          : null;
      facts.add(SemanticCallFacts.Fact.provider(
          id,
          context,
          edge.caller().displayName(),
          edge.callee().displayName(),
          edge.relationshipType(),
          certainty,
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
      String baseId = dynamicFeatureId(feature);
      String id = baseId;
      int duplicate = 1;
      while (!ids.add(id)) {
        id = baseId + "-" + duplicate++;
      }
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
              feature.sourceLine() > 0 ? feature.sourceLine() : null,
               dynamicEvidenceAttributes(feature))),
          feature.limitation()));
    }
    return List.copyOf(facts);
  }

  /** Uses the complete source identity so separate dynamic sites cannot silently collide. */
  private static String dynamicFeatureId(DynamicFeature feature) {
    String identity = feature.kind().name() + "|"
        + feature.sourcePath() + "|"
        + feature.caller().descriptor() + "|"
        + feature.target() + "|"
        + feature.targetDescriptor() + "|"
        + feature.sourceLine() + "|"
        + feature.sourceOffset() + "|"
        + feature.symbols();
    return "sootup-" + feature.kind().name().toLowerCase(Locale.ROOT) + "-" + sha256(identity);
  }

  /** Returns the legacy provider ID seed used for ordinary call edges. */
  private static String legacyCallId(CallEdge edge) {
    return "sootup-call-" + Integer.toHexString(
        (edge.caller().descriptor() + "->" + edge.callee().descriptor() + "@" + edge.line()).hashCode());
  }

  /** Keeps legacy IDs stable for ordinary edges while making collisions explicit and safe. */
  private static String uniqueId(Set<String> ids, String baseId, String identity) {
    if (ids.add(baseId)) {
      return baseId;
    }
    String digest = sha256(identity);
    String candidate = baseId + "-" + digest;
    int duplicate = 1;
    while (!ids.add(candidate)) {
      candidate = baseId + "-" + digest + "-" + duplicate++;
    }
    return candidate;
  }

  private static Map<String, String> dynamicEvidenceAttributes(DynamicFeature feature) {
    if (feature.targetDescriptor().isBlank()) {
      return Map.of("basis", "SootUp graph does not contain a unique dynamic target");
    }
    return Map.of(
        "basis", "SootUp graph does not contain a unique dynamic target",
        "targetDescriptor", feature.targetDescriptor());
  }

  private static String sha256(String value) {
    try {
      byte[] bytes = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(bytes.length * 2);
      for (byte item : bytes) {
        hex.append(String.format("%02x", item));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

}
