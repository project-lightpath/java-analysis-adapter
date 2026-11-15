package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

  /** Creates provider evidence emitted from a SootUp call edge. */
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

    /** Named framework-fact inputs so callers cannot swap positional evidence fields. */
    public record FrameworkInput(
        String caller,
        String callee,
        String relationshipType,
        Certainty certainty,
        String modelRule,
        List<Evidence> evidence,
        String limitation) {}

    public static Fact framework(String id, AnalysisContext context, FrameworkInput input) {
      Objects.requireNonNull(input, "framework fact input");
      return new Fact(
          id,
          context.framework().id(),
          context.id(),
          input.caller(),
          input.callee(),
          input.relationshipType(),
          input.certainty(),
          input.certainty() == Certainty.UNRESOLVED ? Status.ABSTAINED : Status.EMITTED,
          FactSet.FRAMEWORK,
          EvidenceOrigin.FRAMEWORK_MODEL,
          input.modelRule(),
          null,
          null,
          null,
          null,
          null,
          input.evidence(),
          input.limitation());
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

    /** Downgrades open-world dynamic evidence without preserving a misleading provider link. */
    public Fact abstainDynamic() {
      if (certainty == Certainty.UNRESOLVED
          && derivedFrom == null
          && limitation != null
          && !limitation.isBlank()) {
        return this;
      }
      return new Fact(
          id,
          framework,
          contextId,
          caller,
          callee,
          relationshipType,
          Certainty.UNRESOLVED,
          Status.ABSTAINED,
          factSet,
          evidenceOrigin,
          modelRule,
          claim,
          runtimeSelection,
          providerCallerAbsence,
          selectionCriteria,
          null,
          evidence,
          limitation == null || limitation.isBlank()
              ? DynamicCertaintyPolicy.LIMITATION
              : limitation);
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

  /** Immutable metadata emitted for one analysis context and provider graph. */
  public static final class RunMetadata {
    private final String contextId;
    private final String framework;
    private final String frameworkVersion;
    private final String fixtureId;
    private final String selectedProfile;
    private final String analysisScope;
    private final int javaRelease;
    private final String providerName;
    private final String providerVersion;
    private final int graphMethodCount;
    private final int graphCallCount;
    private final java.nio.file.Path repositoryRoot;
    private final AnalysisContext.Provenance provenance;
    private final AnalysisContext.Handoff handoff;
    private final AnalysisContext.DependencyCoordinateManifest dependencyCoordinateManifest;
    private final List<AnalysisContext.SourceRoot> generatedSourceRoots;
    private final List<AnalysisContext.SourceRoot> sourceRoots;

    private RunMetadata(AnalysisContext context, AnalysisGraph graph) {
      contextId = context.id();
      framework = context.framework().id();
      frameworkVersion = context.frameworkVersion();
      fixtureId = context.fixtureId();
      selectedProfile = context.selectedProfile();
      analysisScope = context.analysisScope().id();
      javaRelease = context.javaRelease();
      providerName = graph.providerName();
      providerVersion = graph.providerVersion();
      graphMethodCount = graph.methodCount();
      graphCallCount = graph.callCount();
      repositoryRoot = context.repositoryRoot();
      provenance = context.provenance();
      handoff = context.handoff();
      dependencyCoordinateManifest = context.dependencyCoordinateManifest();
      generatedSourceRoots = List.copyOf(context.generatedSourceRoots());
      sourceRoots = List.copyOf(context.sourceRoots());
    }

    /** Creates run metadata from the immutable context and provider graph inputs. */
    public static RunMetadata from(AnalysisContext context, AnalysisGraph graph) {
      return new RunMetadata(
          Objects.requireNonNull(context, "analysis context"),
          Objects.requireNonNull(graph, "analysis graph"));
    }

    public String contextId() {
      return contextId;
    }

    public String framework() {
      return framework;
    }

    public String frameworkVersion() {
      return frameworkVersion;
    }

    public String fixtureId() {
      return fixtureId;
    }

    public String selectedProfile() {
      return selectedProfile;
    }

    public String analysisScope() {
      return analysisScope;
    }

    public int javaRelease() {
      return javaRelease;
    }

    public String providerName() {
      return providerName;
    }

    public String providerVersion() {
      return providerVersion;
    }

    public int graphMethodCount() {
      return graphMethodCount;
    }

    public int graphCallCount() {
      return graphCallCount;
    }

    public AnalysisContext.Provenance provenance() {
      return provenance;
    }

    public java.nio.file.Path repositoryRoot() {
      return repositoryRoot;
    }

    public AnalysisContext.Handoff handoff() {
      return handoff;
    }

    public AnalysisContext.DependencyCoordinateManifest dependencyCoordinateManifest() {
      return dependencyCoordinateManifest;
    }

    public List<AnalysisContext.SourceRoot> generatedSourceRoots() {
      return generatedSourceRoots;
    }

    public List<AnalysisContext.SourceRoot> sourceRoots() {
      return sourceRoots;
    }

    @Override
    public boolean equals(Object value) {
      if (this == value) {
        return true;
      }
      if (!(value instanceof RunMetadata other)) {
        return false;
      }
      return javaRelease == other.javaRelease
          && graphMethodCount == other.graphMethodCount
          && graphCallCount == other.graphCallCount
          && Objects.equals(contextId, other.contextId)
          && Objects.equals(framework, other.framework)
          && Objects.equals(frameworkVersion, other.frameworkVersion)
          && Objects.equals(fixtureId, other.fixtureId)
          && Objects.equals(selectedProfile, other.selectedProfile)
           && Objects.equals(analysisScope, other.analysisScope)
           && Objects.equals(providerName, other.providerName)
           && Objects.equals(providerVersion, other.providerVersion)
           && Objects.equals(repositoryRoot, other.repositoryRoot)
           && Objects.equals(provenance, other.provenance)
           && Objects.equals(handoff, other.handoff)
           && Objects.equals(dependencyCoordinateManifest, other.dependencyCoordinateManifest)
           && Objects.equals(generatedSourceRoots, other.generatedSourceRoots)
          && Objects.equals(sourceRoots, other.sourceRoots);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
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
           repositoryRoot,
           provenance,
           handoff,
           dependencyCoordinateManifest,
           generatedSourceRoots,
          sourceRoots);
    }

    @Override
    public String toString() {
      return "RunMetadata[contextId=" + contextId
          + ", framework=" + framework
          + ", frameworkVersion=" + frameworkVersion
          + ", fixtureId=" + fixtureId
          + ", selectedProfile=" + selectedProfile
          + ", analysisScope=" + analysisScope
          + ", javaRelease=" + javaRelease
          + ", providerName=" + providerName
           + ", providerVersion=" + providerVersion
           + ", graphMethodCount=" + graphMethodCount
           + ", graphCallCount=" + graphCallCount
           + ", repositoryRoot=" + repositoryRoot
           + ", provenance=" + provenance
           + ", handoff=" + handoff
           + ", dependencyCoordinateManifest=" + dependencyCoordinateManifest
          + ", generatedSourceRoots=" + generatedSourceRoots
          + ", sourceRoots=" + sourceRoots
          + "]";
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
    runMetadata = RunMetadata.from(context, graph);
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

  /** Validates fact-set ownership and freezes facts for the result. */
  private static List<Fact> immutableFacts(List<Fact> facts, FactSet expectedSet) {
    List<Fact> result = List.copyOf(facts == null ? List.of() : facts);
    Set<String> ids = new HashSet<>();
    for (Fact fact : result) {
      if (fact.factSet() != expectedSet) {
        throw new IllegalArgumentException("facts must remain in their declared relationship set");
      }
      if (!ids.add(fact.id())) {
        throw new IllegalArgumentException("fact IDs must be unique within a relationship set: " + fact.id());
      }
    }
    return result;
  }

  /** Validates that every link references facts in the corresponding sets. */
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
