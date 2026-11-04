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
    addFrameworkEntrypointObservations(context, frameworkFacts, providerFacts);
    return new SemanticCallFacts(
        context,
        graph,
        providerFacts,
        frameworkFacts,
        links(providerFacts, frameworkFacts));
  }

  private static void addFrameworkEntrypointObservations(
      AnalysisContext context,
      List<SemanticCallFacts.Fact> frameworkFacts,
      List<SemanticCallFacts.Fact> providerFacts) {
    for (SemanticCallFacts.Fact frameworkFact : frameworkFacts) {
      if (!frameworkFact.relationshipType().equals("framework_entrypoint")) {
        continue;
      }
      boolean alreadyObserved = providerFacts.stream()
          .anyMatch(provider -> provider.relationshipType().equals("framework_entrypoint")
              && provider.callee().equals(frameworkFact.callee()));
      if (alreadyObserved) {
        continue;
      }
      String id = "sootup-framework-entrypoint-" + slug(frameworkFact.callee());
      providerFacts.add(SemanticCallFacts.Fact.provider(
          id,
          context,
          "SootUp CHA no incoming application caller",
          frameworkFact.callee(),
          "framework_entrypoint",
          SemanticCallFacts.Certainty.UNRESOLVED,
          List.of(new SemanticCallFacts.Evidence(
              "sootup_missing_incoming",
              frameworkFact.evidence().isEmpty() ? "unknown" : frameworkFact.evidence().get(0).path(),
              List.of(frameworkFact.callee()),
              null,
              Map.of("basis", "no incoming application call in SootUp CHA"))),
          "No incoming Java caller is not evidence that a framework route is absent or dead code."));
    }
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
      if (provider == null && frameworkFact.relationshipType().equals("framework_entrypoint")) {
        provider = providerFacts.stream()
            .filter(candidate -> candidate.relationshipType().equals("framework_entrypoint"))
            .filter(candidate -> candidate.callee().equals(frameworkFact.callee()))
            .findFirst()
            .orElse(null);
      }
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

  private static String slug(String value) {
    return value.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
  }
}
