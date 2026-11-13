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
    Set<String> ids = new HashSet<>();
    CallReferences references = collectCallReferences(graph);
    Map<Integer, String> callIds = callIds(references, ids);
    boolean hasServiceLoaderEdges = references.edges().stream()
        .anyMatch(ref -> ref.edge().relationshipType().equals("service_loader"));
    SourceMetadataScanner sourceScanner = hasServiceLoaderEdges
        ? new SourceMetadataScanner(context) : null;
    ServiceLoaderDescriptorScanner.Scan serviceLoaderDescriptors = hasServiceLoaderEdges
        ? ServiceLoaderDescriptorScanner.scan(context)
        : new ServiceLoaderDescriptorScanner.Scan(List.of());
    List<SemanticCallFacts.Fact> facts = callFacts(
        context, references.edges(), callIds, serviceLoaderDescriptors, sourceScanner);
    facts.addAll(dynamicFacts(context, graph.dynamicFeatures(), ids));
    return List.copyOf(facts);
  }

  /** Collects graph edges and the application collision counts used for stable IDs. */
  private static CallReferences collectCallReferences(AnalysisGraph graph) {
    List<EdgeRef> edgeRefs = new ArrayList<>();
    Map<String, Integer> applicationBaseIdCounts = new HashMap<>();
    for (int index = 0; index < graph.calls().size(); index++) {
      CallEdge edge = graph.calls().get(index);
      edgeRefs.add(new EdgeRef(index, edge, edgeIdentity(edge)));
      if (isApplicationEdge(edge)) {
        applicationBaseIdCounts.merge(legacyCallId(edge), 1, Integer::sum);
      }
    }
    return new CallReferences(List.copyOf(edgeRefs), Map.copyOf(applicationBaseIdCounts));
  }

  /** Assigns deterministic IDs without changing the graph's output order. */
  private static Map<Integer, String> callIds(CallReferences references, Set<String> ids) {
    Map<Integer, String> idsByIndex = new HashMap<>();
    references.edges().stream()
        .sorted(Comparator.comparing(EdgeRef::identity).thenComparingInt(EdgeRef::index))
        .forEach(ref -> idsByIndex.put(
            ref.index(), callId(ref, references.applicationBaseIdCounts(), ids)));
    return idsByIndex;
  }

  /** Selects the origin-aware ID namespace for one call edge. */
  private static String callId(
      EdgeRef ref, Map<String, Integer> applicationBaseIdCounts, Set<String> ids) {
    if (!isApplicationEdge(ref.edge())) {
      return uniqueId(ids, dependencyCallId(ref.edge()), ref.identity());
    }
    String baseId = legacyCallId(ref.edge());
    if (applicationBaseIdCounts.get(baseId) == 1) {
      return uniqueId(ids, baseId, ref.identity());
    }
    return uniqueId(ids, baseId + "-" + sha256(ref.identity()), ref.identity());
  }

  /** Normalizes ordinary call edges in their original provider order. */
  private static List<SemanticCallFacts.Fact> callFacts(
      AnalysisContext context,
      List<EdgeRef> edgeRefs,
      Map<Integer, String> callIds,
      ServiceLoaderDescriptorScanner.Scan serviceLoaderDescriptors,
      SourceMetadataScanner sourceScanner) {
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    for (EdgeRef ref : edgeRefs) {
      facts.add(callFact(
          context, ref.edge(), callIds.get(ref.index()), serviceLoaderDescriptors, sourceScanner));
    }
    return facts;
  }

  /** Normalizes one ordinary provider edge with the centralized certainty policy. */
  private static SemanticCallFacts.Fact callFact(
      AnalysisContext context,
      CallEdge edge,
      String id,
      ServiceLoaderDescriptorScanner.Scan serviceLoaderDescriptors,
      SourceMetadataScanner sourceScanner) {
    boolean openWorldDynamic = DynamicCertaintyPolicy.isOpenWorldProviderRelationship(
        edge.relationshipType());
    ServiceLoaderDescriptorScanner.Resolution serviceLoaderResolution = edge.relationshipType().equals("service_loader")
        ? ServiceLoaderDescriptorScanner.resolve(
            context, edge, serviceLoaderDescriptors, sourceScanner)
        : null;
    SemanticCallFacts.Certainty certainty = openWorldDynamic
        ? SemanticCallFacts.Certainty.UNRESOLVED
        : serviceLoaderResolution == null ? edge.certaintyHint() : serviceLoaderResolution.certainty();
    List<SemanticCallFacts.Evidence> evidence = new ArrayList<>();
    evidence.add(sootupEvidence(edge));
    if (serviceLoaderResolution != null) {
      evidence.addAll(serviceLoaderResolution.evidence());
    }
    String limitation = serviceLoaderResolution == null
        ? limitation(openWorldDynamic, certainty, edge.relationshipType())
        : serviceLoaderResolution.limitation();
    return SemanticCallFacts.Fact.provider(
        id,
        context,
        edge.caller().displayName(),
        edge.callee().displayName(),
        edge.relationshipType(),
        certainty,
        List.copyOf(evidence),
        limitation);
  }

  /** Normalizes source-backed dynamic observations after ordinary call IDs exist. */
  private static List<SemanticCallFacts.Fact> dynamicFacts(
      AnalysisContext context, List<DynamicFeature> features, Set<String> ids) {
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    for (DynamicFeature feature : features) {
      facts.add(dynamicFact(context, feature, nextDynamicId(feature, ids)));
    }
    return facts;
  }

  /** Creates one unresolved fact for a reflection or proxy observation. */
  private static SemanticCallFacts.Fact dynamicFact(
      AnalysisContext context, DynamicFeature feature, String id) {
    String relationship = feature.kind() == DynamicFeature.Kind.REFLECTION
        ? "reflection" : "proxy_dispatch";
    String evidenceKind = feature.kind() == DynamicFeature.Kind.REFLECTION
        ? "reflection_api" : "dynamic_proxy_call";
    return SemanticCallFacts.Fact.provider(
        id,
        context,
        feature.caller().displayName(),
        feature.target(),
        relationship,
        SemanticCallFacts.Certainty.UNRESOLVED,
        List.of(new SemanticCallFacts.Evidence(
            evidenceKind,
            feature.sourcePath(),
            feature.symbols(),
            feature.sourceLine() > 0 ? feature.sourceLine() : null,
            dynamicEvidenceAttributes(feature))),
        feature.limitation());
  }

  /** Allocates a deterministic dynamic ID without colliding with call IDs. */
  private static String nextDynamicId(DynamicFeature feature, Set<String> ids) {
    String baseId = dynamicFeatureId(feature);
    String id = baseId;
    int duplicate = 1;
    while (!ids.add(id)) {
      id = baseId + "-" + duplicate++;
    }
    return id;
  }

  /** Applies open-world abstention and bounded-candidate limitations. */
  private static String limitation(
      boolean openWorldDynamic,
      SemanticCallFacts.Certainty certainty,
      String relationshipType) {
    if (openWorldDynamic) {
      return DynamicCertaintyPolicy.LIMITATION;
    }
    return certainty == SemanticCallFacts.Certainty.POSSIBLE
        ? relationshipType.equals("callback")
            ? "CHA exposes a concrete callback candidate; runtime registration or selection is not unique."
            : "CHA exposes a bounded implementation candidate; runtime selection is not unique."
        : null;
  }

  private static boolean isApplicationEdge(CallEdge edge) {
    return edge.targetContext().origin() == CallEdge.TargetOrigin.APPLICATION;
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
    attributes.put("basis", switch (edge.relationshipType()) {
      case "callback" -> "SootUp CHA concrete callback implementation edge";
      case "service_loader" -> "SootUp CHA ServiceLoader candidate edge";
      default -> "SootUp CHA application call";
    });
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

  private record EdgeRef(int index, CallEdge edge, String identity) {}

  private record CallReferences(
      List<EdgeRef> edges, Map<String, Integer> applicationBaseIdCounts) {}
}
