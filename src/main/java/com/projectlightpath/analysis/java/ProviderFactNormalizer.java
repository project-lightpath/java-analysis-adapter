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
import java.util.function.Supplier;

/** Converts provider graph observations into provider-only SemanticCallFacts. */
final class ProviderFactNormalizer {
  private ProviderFactNormalizer() {}

  static List<SemanticCallFacts.Fact> normalize(AnalysisContext context, AnalysisGraph graph) {
    return normalize(context, graph, () -> SootUpInputLocationFactory.dependencyResolution(context));
  }

  static List<SemanticCallFacts.Fact> normalize(
      AnalysisContext context,
      AnalysisGraph graph,
      Supplier<SootUpInputLocationFactory.DependencyResolution> dependencies) {
    return normalize(context, graph, dependencies, List.of());
  }

  static List<SemanticCallFacts.Fact> normalize(
      AnalysisContext context,
      AnalysisGraph graph,
      Supplier<SootUpInputLocationFactory.DependencyResolution> dependencies,
      List<SemanticCallFacts.Diagnostic> diagnostics) {
    Set<String> ids = new HashSet<>();
    CallReferences references = collectCallReferences(graph);
    Map<Integer, String> callIds = callIds(references, ids);
    boolean hasServiceLoaderEdges = references.edges().stream()
        .anyMatch(ref -> ref.edge().relationshipType().equals("service_loader"));
    SourceMetadataScanner sourceScanner = hasServiceLoaderEdges
        ? new SourceMetadataScanner(context) : null;
    ServiceLoaderDescriptorScanner.Scan descriptors = hasServiceLoaderEdges
        ? ServiceLoaderDescriptorScanner.scan(context, dependencies.get())
        : new ServiceLoaderDescriptorScanner.Scan(List.of());
    descriptors = descriptors.withAdditionalDiagnostics(
        diagnostics.stream()
            .map(item -> new ServiceLoaderDescriptorScanner.Diagnostic(
                item.code(), item.path(), item.detail()))
            .toList());
    ServiceLoaderDescriptorEvidenceCache evidenceCache =
        new ServiceLoaderDescriptorEvidenceCache();
    List<SemanticCallFacts.Fact> facts = callFacts(
        context, references.edges(), callIds, descriptors, sourceScanner, evidenceCache);
    facts.addAll(dynamicFacts(context, graph.dynamicFeatures(), ids));
    return List.copyOf(facts);
  }

  /** Collects graph edges and the application collision counts used for stable IDs. */
  private static CallReferences collectCallReferences(AnalysisGraph graph) {
    List<EdgeRef> edgeRefs = new ArrayList<>();
    Map<String, Integer> applicationBaseIdCounts = new HashMap<>();
    Map<String, Integer> declaredBaseIdCounts = new HashMap<>();
    for (int index = 0; index < graph.calls().size(); index++) {
      CallEdge edge = graph.calls().get(index);
      edgeRefs.add(new EdgeRef(index, edge, edgeIdentity(edge)));
      if (isDeclaredInvoke(edge)) {
        declaredBaseIdCounts.merge(declaredCallId(edge), 1, Integer::sum);
      } else if (isApplicationEdge(edge)) {
        applicationBaseIdCounts.merge(legacyCallId(edge), 1, Integer::sum);
      }
    }
    return new CallReferences(
        List.copyOf(edgeRefs), Map.copyOf(applicationBaseIdCounts), Map.copyOf(declaredBaseIdCounts));
  }

  /** Assigns deterministic IDs without changing the graph's output order. */
  private static Map<Integer, String> callIds(CallReferences references, Set<String> ids) {
    Map<Integer, String> idsByIndex = new HashMap<>();
    references.edges().stream()
        .sorted(Comparator.comparing(EdgeRef::identity).thenComparingInt(EdgeRef::index))
        .forEach(ref -> idsByIndex.put(
            ref.index(), callId(ref, references.applicationBaseIdCounts(),
                references.declaredBaseIdCounts(), ids)));
    return idsByIndex;
  }

  /** Selects the origin-aware ID namespace for one call edge. */
  private static String callId(
      EdgeRef ref,
      Map<String, Integer> applicationBaseIdCounts,
      Map<String, Integer> declaredBaseIdCounts,
      Set<String> ids) {
    if (isDeclaredInvoke(ref.edge())) {
      String baseId = declaredCallId(ref.edge());
      if (declaredBaseIdCounts.get(baseId) == 1) {
        return uniqueId(ids, baseId, ref.identity());
      }
      return uniqueId(ids, baseId + "-" + sha256(ref.identity()), ref.identity());
    }
    if (!isApplicationEdge(ref.edge())) {
      return uniqueId(ids, dependencyCallId(ref.edge()), ref.identity());
    }
    String baseId = legacyCallId(ref.edge());
    if (applicationBaseIdCounts.get(baseId) == 1) {
      return uniqueId(ids, baseId, ref.identity());
    }
    return uniqueId(ids, baseId + "-" + sha256(ref.identity()), ref.identity());
  }

  private static boolean isDeclaredInvoke(CallEdge edge) {
    return edge.relationshipType().equals("declared_invokes");
  }

  /** Normalizes ordinary call edges in their original provider order. */
  private static List<SemanticCallFacts.Fact> callFacts(
      AnalysisContext context,
      List<EdgeRef> edgeRefs,
      Map<Integer, String> callIds,
      ServiceLoaderDescriptorScanner.Scan descriptors,
      SourceMetadataScanner sourceScanner,
      ServiceLoaderDescriptorEvidenceCache evidenceCache) {
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    for (EdgeRef ref : edgeRefs) {
      facts.add(callFact(context, ref.edge(), callIds.get(ref.index()), descriptors, sourceScanner,
          evidenceCache));
    }
    return facts;
  }

  /** Normalizes one ordinary provider edge with the centralized certainty policy. */
  private static SemanticCallFacts.Fact callFact(
      AnalysisContext context,
      CallEdge edge,
      String id,
      ServiceLoaderDescriptorScanner.Scan descriptors,
      SourceMetadataScanner sourceScanner,
      ServiceLoaderDescriptorEvidenceCache evidenceCache) {
    boolean openWorldDynamic = DynamicCertaintyPolicy.isOpenWorldProviderRelationship(
        edge.relationshipType());
    ServiceLoaderDescriptorScanner.Resolution serviceResolution =
        edge.relationshipType().equals("service_loader")
            ? ServiceLoaderDescriptorScanner.resolve(
                context, edge, descriptors, sourceScanner, evidenceCache)
            : null;
    SemanticCallFacts.Certainty certainty = openWorldDynamic
        ? SemanticCallFacts.Certainty.UNRESOLVED
        : serviceResolution == null ? edge.certaintyHint() : serviceResolution.certainty();
    List<SemanticCallFacts.Evidence> evidence = new ArrayList<>();
    evidence.add(sootupEvidence(context, edge));
    if (serviceResolution != null) {
      evidence.addAll(serviceResolution.evidence());
    }
    return SemanticCallFacts.Fact.provider(
        id,
        context,
        edge.caller().displayName(),
        edge.callee().displayName(),
        edge.relationshipType(),
        certainty,
        List.copyOf(evidence),
        serviceResolution == null
            ? limitation(openWorldDynamic, certainty, edge.relationshipType())
            : serviceResolution.limitation());
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

  /** Creates one fact for a reflection or proxy observation, consuming javac evidence. */
  private static SemanticCallFacts.Fact dynamicFact(
      AnalysisContext context, DynamicFeature feature, String id) {
    String relationship = feature.kind() == DynamicFeature.Kind.REFLECTION
        ? "reflection" : "proxy_dispatch";
    String evidenceKind = feature.kind() == DynamicFeature.Kind.REFLECTION
        ? "reflection_api" : "dynamic_proxy_call";
    // Issue #25: the evidence renders its own consumption — a source-defined
    // target proven by the javac index is DEFINITE (its shape, not its
    // dispatch); a target the index sees but cannot prove is POSSIBLE with
    // the diagnostic recorded per fact; everything else — runtime strings,
    // non-literals, unresolvable receivers — keeps the pre-existing
    // UNRESOLVED abstention, byte-identical.
    JavacTargetEvidence evidence =
        feature.targetEvidence() == null ? JavacTargetEvidence.NONE : feature.targetEvidence();
    // Issue #62: the per-member abstention attributes ride the same
    // evidence map. Visibility-only: certainty, callee, and limitation
    // are the #25 channel's, unchanged.
    Map<String, String> attributes =
        new java.util.LinkedHashMap<>(evidence.attributes(feature.targetDescriptor()));
    feature.abstentions().mergeInto(attributes);
    return SemanticCallFacts.Fact.provider(
        id,
        context,
        feature.caller().displayName(),
        evidence.callee(feature.target()),
        relationship,
        evidence.certainty(),
        List.of(new SemanticCallFacts.Evidence(
            evidenceKind,
            feature.sourcePath(),
            feature.symbols(),
            feature.sourceLine() > 0 ? feature.sourceLine() : null,
            attributes)),
        evidence.limitation(feature.limitation()));
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
      boolean openWorldDynamic, SemanticCallFacts.Certainty certainty, String relationshipType) {
    if (openWorldDynamic) {
      return DynamicCertaintyPolicy.LIMITATION;
    }
    if (relationshipType.equals("declared_invokes")) {
      return "Declared interface-method target at the call site; runtime dispatch"
          + " resolution is carried by the spring-data framework facts.";
    }
    return certainty == SemanticCallFacts.Certainty.POSSIBLE
        ? "CHA exposes a bounded implementation/callback candidate; runtime selection is not unique."
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

  /** Gives declared repository invokes their own ID namespace, outside the legacy counts. */
  private static String declaredCallId(CallEdge edge) {
    return "sootup-declared-" + Integer.toHexString(
        (edge.caller().descriptor() + "->" + edge.callee().descriptor() + "@" + edge.line()).hashCode());
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

  private static SemanticCallFacts.Evidence sootupEvidence(
      AnalysisContext context, CallEdge edge) {
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
      if (!DynamicCertaintyPolicy.isOpenWorldProviderRelationship(edge.relationshipType())) {
        addDependencyCoordinateAttributes(attributes, context, edge);
      }
    }
    return new SemanticCallFacts.Evidence(
        "sootup_call", edge.sourcePath(), List.of(), edge.line(), attributes);
  }

  /** Adds only upstream-supplied coordinate values to dependency evidence. */
  private static void addDependencyCoordinateAttributes(
      Map<String, String> attributes,
      AnalysisContext context,
      CallEdge edge) {
    AnalysisContext.DependencyCoordinateEntry coordinate = context
        .dependencyCoordinateManifest()
        .entryFor(java.nio.file.Path.of(edge.targetContext().dependencyClasspathEntry()))
        .orElse(null);
    if (coordinate == null) {
      return;
    }
    attributes.put("dependencyGroupId", coordinate.groupId());
    attributes.put("dependencyArtifactId", coordinate.artifactId());
    attributes.put("dependencyVersion", coordinate.version());
    if (coordinate.classifier() != null) {
      attributes.put("dependencyClassifier", coordinate.classifier());
    }
    if (coordinate.fingerprint() != null) {
      attributes.put("dependencyClasspathEntryFingerprint", coordinate.fingerprint());
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
      List<EdgeRef> edges,
      Map<String, Integer> applicationBaseIdCounts,
      Map<String, Integer> declaredBaseIdCounts) {}
}
