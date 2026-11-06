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
    List<SemanticCallFacts.Fact> providerFacts = new ArrayList<>(ProviderFactNormalizer.normalize(context, graph));
    List<SemanticCallFacts.Fact> frameworkFacts = attachExactProviderIdentities(
        selectedModel.enrich(context, graph), providerFacts);
    return new SemanticCallFacts(
        context,
        graph,
        providerFacts,
        frameworkFacts,
        links(providerFacts, frameworkFacts));
  }

  private static List<SemanticCallFacts.Fact> attachExactProviderIdentities(
      List<SemanticCallFacts.Fact> frameworkFacts,
      List<SemanticCallFacts.Fact> providerFacts) {
    List<SemanticCallFacts.Fact> result = new ArrayList<>();
    for (SemanticCallFacts.Fact frameworkFact : frameworkFacts) {
      if (frameworkFact.derivedFrom() != null) {
        boolean providerExists = providerFacts.stream()
            .anyMatch(provider -> provider.id().equals(frameworkFact.derivedFrom()));
        result.add(providerExists
            ? frameworkFact
            : frameworkFact.withDerivedFrom(null));
        continue;
      }
      if (frameworkFact.relationshipType().equals("framework_entrypoint")) {
        result.add(frameworkFact);
        continue;
      }
      List<SemanticCallFacts.Fact> exactTargets = providerFacts.stream()
          .filter(provider -> provider.certainty() != SemanticCallFacts.Certainty.UNRESOLVED)
          .filter(provider -> provider.callee().equals(frameworkFact.callee()))
          .filter(provider -> !provider.relationshipType().equals("reflection"))
          .filter(provider -> !provider.relationshipType().equals("proxy_dispatch")
              || frameworkFact.relationshipType().equals("proxy_dispatch"))
          .toList();
      result.add(exactTargets.size() == 1
          ? frameworkFact.withDerivedFrom(exactTargets.get(0).id())
          : frameworkFact);
    }
    return List.copyOf(result);
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
