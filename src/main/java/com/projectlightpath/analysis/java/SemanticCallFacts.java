package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Provenance-preserving provider and framework relationships for one analysis context. */
public final class SemanticCallFacts {
  public enum Certainty {
    DEFINITE("definite"),
    POSSIBLE("possible"),
    UNRESOLVED("unresolved");

    private final String id;

    Certainty(String id) {
      this.id = id;
    }

    public String id() {
      return id;
    }
  }

  public enum FactSet {
    PROVIDER("provider"),
    FRAMEWORK("framework");

    private final String id;

    FactSet(String id) {
      this.id = id;
    }

    public String id() {
      return id;
    }
  }

  public enum EvidenceOrigin {
    SOOTUP_BASE_GRAPH("SootUpBaseGraph"),
    FRAMEWORK_MODEL("FrameworkModel");

    private final String id;

    EvidenceOrigin(String id) {
      this.id = id;
    }

    public String id() {
      return id;
    }
  }

  public enum Status {
    EMITTED("emitted"),
    ABSTAINED("abstained");

    private final String id;

    Status(String id) {
      this.id = id;
    }

    public String id() {
      return id;
    }
  }

  public record Evidence(
      String kind,
      String path,
      List<String> symbols,
      Integer line,
      Map<String, String> attributes) {
    public Evidence {
      if (kind == null || kind.isBlank()) {
        throw new IllegalArgumentException("evidence kind must not be blank");
      }
      if (path == null || path.isBlank()) {
        throw new IllegalArgumentException("evidence path must not be blank");
      }
      symbols = List.copyOf(symbols == null ? List.of() : symbols);
      attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
    }

    public static Evidence of(String kind, String path, List<String> symbols) {
      return new Evidence(kind, path, symbols, null, Map.of());
    }

    public static Evidence sootup(String path, int line, String caller, String callee, String basis) {
      Map<String, String> attributes = new LinkedHashMap<>();
      attributes.put("basis", basis);
      attributes.put("caller", caller);
      attributes.put("callee", callee);
      return new Evidence("sootup_call", path, List.of(), line, attributes);
    }

    public static Evidence sootup(
        String path,
        int line,
        MethodSymbol caller,
        MethodSymbol callee,
        String basis) {
      Map<String, String> attributes = new LinkedHashMap<>();
      attributes.put("basis", basis);
      attributes.put("caller", caller.displayName());
      attributes.put("callee", callee.displayName());
      attributes.put("callerSignature", caller.descriptor());
      attributes.put("calleeSignature", callee.descriptor());
      return new Evidence("sootup_call", path, List.of(), line, attributes);
    }
  }

  public record RouteSelectionCriteria(
      String httpMethod,
      String pathTemplate,
      List<String> consumes,
      List<String> produces,
      List<String> headers,
      List<String> parameters,
      String activeAnalysisContext) {
    public RouteSelectionCriteria {
      if (httpMethod == null || httpMethod.isBlank()) {
        throw new IllegalArgumentException("route HTTP method must not be blank");
      }
      if (pathTemplate == null || pathTemplate.isBlank()) {
        throw new IllegalArgumentException("route path template must not be blank");
      }
      if (activeAnalysisContext == null || activeAnalysisContext.isBlank()) {
        throw new IllegalArgumentException("route analysis context must not be blank");
      }
      consumes = List.copyOf(consumes == null ? List.of() : consumes);
      produces = List.copyOf(produces == null ? List.of() : produces);
      headers = List.copyOf(headers == null ? List.of() : headers);
      parameters = List.copyOf(parameters == null ? List.of() : parameters);
    }
  }

  public record Fact(
      String id,
      String framework,
      String contextId,
      String caller,
      String callee,
      String relationshipType,
      Certainty certainty,
      Status status,
      FactSet factSet,
      EvidenceOrigin evidenceOrigin,
      String modelRule,
      String claim,
      String runtimeSelection,
      String providerCallerAbsence,
      RouteSelectionCriteria selectionCriteria,
      String derivedFrom,
      List<Evidence> evidence,
      String limitation) {
    public Fact {
      require(id, "fact id");
      require(framework, "fact framework");
      require(contextId, "fact context id");
      require(caller, "fact caller");
      require(callee, "fact callee");
      require(relationshipType, "fact relationship type");
      Objects.requireNonNull(certainty, "fact certainty");
      Objects.requireNonNull(status, "fact status");
      Objects.requireNonNull(factSet, "fact set");
      Objects.requireNonNull(evidenceOrigin, "fact evidence origin");
      evidence = List.copyOf(evidence == null ? List.of() : evidence);
      if (certainty == Certainty.UNRESOLVED && status != Status.ABSTAINED) {
        throw new IllegalArgumentException("unresolved facts must be abstained");
      }
    }

    public static Fact provider(
        String id,
        AnalysisContext context,
        String caller,
        String callee,
        String relationshipType,
        Certainty certainty,
        List<Evidence> evidence,
        String limitation) {
      return new Fact(
          id,
          context.framework().id(),
          context.id(),
          caller,
          callee,
          relationshipType,
          certainty,
          certainty == Certainty.UNRESOLVED ? Status.ABSTAINED : Status.EMITTED,
          FactSet.PROVIDER,
          EvidenceOrigin.SOOTUP_BASE_GRAPH,
          null,
          null,
          null,
          null,
          null,
          null,
          evidence,
          limitation);
    }

    public static Fact framework(
        String id,
        AnalysisContext context,
        String caller,
        String callee,
        String relationshipType,
        Certainty certainty,
        String modelRule,
        List<Evidence> evidence,
        String limitation) {
      return new Fact(
          id,
          context.framework().id(),
          context.id(),
          caller,
          callee,
          relationshipType,
          certainty,
          certainty == Certainty.UNRESOLVED ? Status.ABSTAINED : Status.EMITTED,
          FactSet.FRAMEWORK,
          EvidenceOrigin.FRAMEWORK_MODEL,
          modelRule,
          null,
          null,
          null,
          null,
          null,
          evidence,
          limitation);
    }

    public Fact withFrameworkDetails(
        String claim,
        String runtimeSelection,
        String providerCallerAbsence,
        RouteSelectionCriteria selectionCriteria,
        String derivedFrom) {
      return new Fact(
          id,
          framework,
          contextId,
          caller,
          callee,
          relationshipType,
          certainty,
          status,
          factSet,
          evidenceOrigin,
          modelRule,
          claim,
          runtimeSelection,
          providerCallerAbsence,
          selectionCriteria,
          derivedFrom,
          evidence,
          limitation);
    }

    /** Attach an exact provider fact identity without changing the framework claim. */
    public Fact withDerivedFrom(String providerFactId) {
      return new Fact(
          id,
          framework,
          contextId,
          caller,
          callee,
          relationshipType,
          certainty,
          status,
          factSet,
          evidenceOrigin,
          modelRule,
          claim,
          runtimeSelection,
          providerCallerAbsence,
          selectionCriteria,
          providerFactId,
          evidence,
          limitation);
    }

    private static void require(String value, String name) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(name + " must not be blank");
      }
    }
  }

  public record Link(
      String frameworkFactId,
      String providerFactId,
      String relation,
      boolean certaintyPreserved) {
    public Link {
      if (frameworkFactId == null || frameworkFactId.isBlank()) {
        throw new IllegalArgumentException("framework fact id must not be blank");
      }
      if (providerFactId == null || providerFactId.isBlank()) {
        throw new IllegalArgumentException("provider fact id must not be blank");
      }
      if (relation == null || relation.isBlank()) {
        throw new IllegalArgumentException("fact link relation must not be blank");
      }
    }
  }

  public record MergePolicy(
      boolean providerAndFrameworkFactsRemainDistinguishable,
      boolean frameworkFactsNeverReplaceProviderFacts,
      boolean providerCallerAbsenceDoesNotDisproveRoute,
      boolean unresolvedDynamicBehaviorRemainsUnresolved) {
    public static MergePolicy contract() {
      return new MergePolicy(true, true, true, true);
    }
  }

  public record RunMetadata(
      String contextId,
      String framework,
      String frameworkVersion,
      String fixtureId,
      String selectedProfile,
      String analysisScope,
      int javaRelease,
      String providerName,
      String providerVersion,
      int graphMethodCount,
      int graphCallCount,
      AnalysisContext.Provenance provenance,
      AnalysisContext.Handoff handoff) {
    /** Compatibility constructor for callers that predate the JSON handoff metadata. */
    public RunMetadata(
        String contextId,
        String framework,
        String frameworkVersion,
        String fixtureId,
        String selectedProfile,
        String analysisScope,
        int javaRelease,
        String providerName,
        String providerVersion,
        int graphMethodCount,
        int graphCallCount,
        AnalysisContext.Provenance provenance) {
      this(
          contextId,
          framework,
          frameworkVersion,
          fixtureId,
          selectedProfile,
          analysisScope,
          javaRelease,
          providerName,
          providerVersion,
          graphMethodCount,
          graphCallCount,
          provenance,
          AnalysisContext.Handoff.empty());
    }
  }

  private final String contextId;
  private final AnalysisContext.Framework framework;
  private final List<Fact> providerFacts;
  private final List<Fact> frameworkFacts;
  private final List<Link> links;
  private final MergePolicy mergePolicy;
  private final RunMetadata runMetadata;

  public SemanticCallFacts(
      AnalysisContext context,
      List<Fact> providerFacts,
      List<Fact> frameworkFacts,
      List<Link> links) {
    this(context, AnalysisGraph.empty(), providerFacts, frameworkFacts, links);
  }

  public SemanticCallFacts(
      AnalysisContext context,
      AnalysisGraph graph,
      List<Fact> providerFacts,
      List<Fact> frameworkFacts,
      List<Link> links) {
    contextId = context.id();
    framework = context.framework();
    this.providerFacts = immutableFacts(providerFacts, FactSet.PROVIDER);
    this.frameworkFacts = immutableFacts(frameworkFacts, FactSet.FRAMEWORK);
    this.links = List.copyOf(links == null ? List.of() : links);
    mergePolicy = MergePolicy.contract();
    runMetadata = new RunMetadata(
        context.id(),
        context.framework().id(),
        context.frameworkVersion(),
        context.fixtureId(),
        context.selectedProfile(),
        context.analysisScope().id(),
        context.javaRelease(),
        graph.providerName(),
        graph.providerVersion(),
        graph.methodCount(),
        graph.callCount(),
        context.provenance(),
        context.handoff());
    validateLinks();
  }

  public String contextId() {
    return contextId;
  }

  public AnalysisContext.Framework framework() {
    return framework;
  }

  public List<Fact> providerFacts() {
    return providerFacts;
  }

  public List<Fact> frameworkFacts() {
    return frameworkFacts;
  }

  public List<Link> links() {
    return links;
  }

  public MergePolicy mergePolicy() {
    return mergePolicy;
  }

  public RunMetadata runMetadata() {
    return runMetadata;
  }

  public List<Fact> allFacts() {
    List<Fact> result = new ArrayList<>(providerFacts);
    result.addAll(frameworkFacts);
    return Collections.unmodifiableList(result);
  }

  public EnumSet<Certainty> certaintyStates() {
    EnumSet<Certainty> result = EnumSet.noneOf(Certainty.class);
    allFacts().forEach(fact -> result.add(fact.certainty()));
    return result;
  }

  private static List<Fact> immutableFacts(List<Fact> facts, FactSet expectedSet) {
    List<Fact> result = List.copyOf(facts == null ? List.of() : facts);
    if (result.stream().anyMatch(fact -> fact.factSet() != expectedSet)) {
      throw new IllegalArgumentException("facts must remain in their declared relationship set");
    }
    return result;
  }

  private void validateLinks() {
    Map<String, Fact> providers = new LinkedHashMap<>();
    providerFacts.forEach(fact -> providers.put(fact.id(), fact));
    Map<String, Fact> frameworks = new LinkedHashMap<>();
    frameworkFacts.forEach(fact -> frameworks.put(fact.id(), fact));
    for (Link link : links) {
      if (!frameworks.containsKey(link.frameworkFactId()) || !providers.containsKey(link.providerFactId())) {
        throw new IllegalArgumentException("fact link must reference one provider and one framework fact");
      }
    }
  }
}
