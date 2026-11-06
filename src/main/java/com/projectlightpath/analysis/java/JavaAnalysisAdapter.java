package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Production integration boundary: SootUp base graph plus project-owned
 * FrameworkModel and AnalysisContext produces separate SemanticCallFacts sets.
 */
public final class JavaAnalysisAdapter {
  private final SootUpBaseGraphProvider baseGraphProvider;
  private final FrameworkModel frameworkModel;

  public JavaAnalysisAdapter() {
    this(new SootUpBaseGraphProvider(), null);
  }

  public JavaAnalysisAdapter(SootUpBaseGraphProvider baseGraphProvider) {
    this(baseGraphProvider, null);
  }

  public JavaAnalysisAdapter(SootUpBaseGraphProvider baseGraphProvider, FrameworkModel frameworkModel) {
    this.baseGraphProvider = Objects.requireNonNull(baseGraphProvider, "base graph provider");
    this.frameworkModel = frameworkModel;
  }

  public SemanticCallFacts analyze(AnalysisContext context) {
    Objects.requireNonNull(context, "analysis context");
    AnalysisGraph graph = baseGraphProvider.analyze(context);
    return analyze(context, graph);
  }

  /** Testable handoff used by callers that already own a provider graph. */
  public SemanticCallFacts analyze(AnalysisContext context, AnalysisGraph graph) {
    Objects.requireNonNull(context, "analysis context");
    Objects.requireNonNull(graph, "analysis graph");
    FrameworkModel selectedModel = frameworkModel == null
        ? FrameworkModels.forContext(context.framework())
        : frameworkModel;
    List<SemanticCallFacts.Fact> frameworkFacts = selectedModel.enrich(context, graph);
    List<SemanticCallFacts.Fact> providerFacts = new ArrayList<>(ProviderFactNormalizer.normalize(context, graph));
    return new SemanticCallFacts(
        context,
        graph,
        providerFacts,
        frameworkFacts,
        links(providerFacts, frameworkFacts));
  }

  private static List<SemanticCallFacts.Link> links(
      List<SemanticCallFacts.Fact> providerFacts,
      List<SemanticCallFacts.Fact> frameworkFacts) {
    Map<String, SemanticCallFacts.Fact> providers = new HashMap<>();
    providerFacts.forEach(fact -> providers.put(fact.id(), fact));
    List<SemanticCallFacts.Link> result = new ArrayList<>();
    for (SemanticCallFacts.Fact frameworkFact : frameworkFacts) {
      if (frameworkFact.derivedFrom() == null
          && !frameworkFact.relationshipType().equals("framework_entrypoint")) {
        continue;
      }
      SemanticCallFacts.Fact provider = providers.get(frameworkFact.derivedFrom());
      if (provider != null) {
        result.add(new SemanticCallFacts.Link(
            frameworkFact.id(),
            provider.id(),
            "enriches_without_replacing",
            true));
      }
    }
    return List.copyOf(result);
  }

}
