package com.projectlightpath.analysis.java;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Converts provider graph observations into provider-only SemanticCallFacts. */
final class ProviderFactNormalizer {
  private ProviderFactNormalizer() {}

  static List<SemanticCallFacts.Fact> normalize(AnalysisContext context, AnalysisGraph graph) {
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (CallEdge edge : graph.calls()) {
      String callIdentity = edge.caller().displayName() + "|" + edge.caller().descriptor()
          + "->" + edge.callee().displayName() + "|" + edge.callee().descriptor()
          + "@" + edge.line() + "|" + edge.sourcePath() + "|" + edge.relationshipType();
      String baseId = "sootup-call-" + Integer.toHexString(
          (edge.caller().descriptor() + "->" + edge.callee().descriptor() + "@" + edge.line()).hashCode());
      String id = uniqueId(ids, baseId, callIdentity);
      boolean openWorldDynamic = isOpenWorldDynamic(edge.relationshipType());
      SemanticCallFacts.Certainty certainty = openWorldDynamic
          ? SemanticCallFacts.Certainty.UNRESOLVED
          : edge.certaintyHint();
      String limitation = openWorldDynamic
          ? "Open-world dynamic behavior does not identify a unique runtime target."
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
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(identity.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(bytes.length * 2);
      for (byte value : bytes) {
        hex.append(String.format("%02x", value));
      }
      return "sootup-" + feature.kind().name().toLowerCase() + "-" + hex;
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
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

  /** Keeps open-world reflection and proxy edges abstained even if a provider overstates certainty. */
  private static boolean isOpenWorldDynamic(String relationshipType) {
    return "reflection".equals(relationshipType)
        || "dynamic_proxy".equals(relationshipType)
        || "proxy_dispatch".equals(relationshipType);
  }
}
