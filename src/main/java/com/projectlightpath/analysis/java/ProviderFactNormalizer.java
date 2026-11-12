package com.projectlightpath.analysis.java;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    List<EdgeRef> edgeRefs = new ArrayList<>();
    Map<String, Integer> applicationBaseIdCounts = new HashMap<>();
    for (int index = 0; index < graph.calls().size(); index++) {
      CallEdge edge = graph.calls().get(index);
      EdgeRef ref = new EdgeRef(index, edge, edgeIdentity(edge));
      edgeRefs.add(ref);
      if (edge.targetContext().origin() == CallEdge.TargetOrigin.APPLICATION) {
        applicationBaseIdCounts.merge(legacyCallId(edge), 1, Integer::sum);
      }
    }
    Map<Integer, String> idsByIndex = new HashMap<>();
    edgeRefs.stream()
        .sorted(Comparator.comparing(EdgeRef::identity).thenComparingInt(EdgeRef::index))
        .forEach(ref -> {
          CallEdge edge = ref.edge();
          String id;
          if (edge.targetContext().origin() == CallEdge.TargetOrigin.APPLICATION) {
            String baseId = legacyCallId(edge);
            id = applicationBaseIdCounts.get(baseId) == 1
                ? uniqueId(ids, baseId, ref.identity())
                : uniqueId(ids, baseId + "-" + sha256(ref.identity()), ref.identity());
          } else {
            id = uniqueId(ids, dependencyCallId(edge), ref.identity());
          }
          idsByIndex.put(ref.index(), id);
        });
    for (EdgeRef ref : edgeRefs) {
      CallEdge edge = ref.edge();
      String id = idsByIndex.get(ref.index());
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
          List.of(sootupEvidence(edge)),
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

  /** Gives dependency targets an origin-aware ID namespace without changing application IDs. */
  private static String dependencyCallId(CallEdge edge) {
    return "sootup-dependency-call-" + sha256(dependencyCallIdentity(edge));
  }

  /** Retains the pre-existing identity string for application call edges. */
  private static String applicationCallIdentity(CallEdge edge) {
    return edge.caller().displayName() + "|" + edge.caller().descriptor()
        + "->" + edge.callee().displayName() + "|" + edge.callee().descriptor()
        + "@" + edge.line() + "|" + edge.sourcePath() + "|" + edge.relationshipType();
  }

  /** Separates dependency target origin from the optional evidence path. */
  private static String dependencyCallIdentity(CallEdge edge) {
    return applicationCallIdentity(edge)
        + "|" + edge.targetContext().origin().id()
        + "|" + edge.targetContext().dependencyClasspathEntry();
  }

  private static String edgeIdentity(CallEdge edge) {
    return edge.targetContext().origin() == CallEdge.TargetOrigin.APPLICATION
        ? applicationCallIdentity(edge)
        : dependencyCallIdentity(edge);
  }

  private static SemanticCallFacts.Evidence sootupEvidence(CallEdge edge) {
    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put("basis", "SootUp CHA application call");
    attributes.put("caller", edge.caller().displayName());
    attributes.put("callee", edge.callee().displayName());
    attributes.put("callerSignature", edge.caller().descriptor());
    attributes.put("calleeSignature", edge.callee().descriptor());
    if (edge.targetContext().origin() == CallEdge.TargetOrigin.DEPENDENCY) {
      attributes.put("calleeOwner", edge.callee().owner());
      attributes.put("calleeMethod", edge.callee().name());
      attributes.put("targetOrigin", edge.targetContext().origin().id());
      attributes.put("dependencyClasspathEntry", edge.targetContext().dependencyClasspathEntry());
      if (!edge.targetContext().targetBytecodePath().isBlank()) {
        attributes.put("targetBytecodePath", edge.targetContext().targetBytecodePath());
      }
      if (!edge.targetContext().targetSourcePath().isBlank()) {
        attributes.put("targetSourcePath", edge.targetContext().targetSourcePath());
      }
    }
    return new SemanticCallFacts.Evidence(
        "sootup_call", edge.sourcePath(), List.of(), edge.line(), attributes);
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

  private record EdgeRef(int index, CallEdge edge, String identity) {}
}
