package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DynamicCertaintyGuardTest {
  @TempDir Path temp;

  /** Verifies framework dynamic claims are abstained before provider linking. */
  @Test
  void frameworkDynamicEvidenceCannotBePromotedOrLinked() throws IOException {
    Path sourceRoot = temp.resolve("src/main/java");
    Files.createDirectories(sourceRoot);
    AnalysisContext context = context(sourceRoot);
    MethodSymbol caller = new MethodSymbol("demo.App", "run", "()V");
    MethodSymbol callee = new MethodSymbol("demo.Target", "run", "()V");
    AnalysisGraph graph = new AnalysisGraph(
        List.of(new CallEdge(
            caller,
            callee,
            4,
            "src/main/java/demo/App.java",
            SemanticCallFacts.Certainty.DEFINITE,
            "invokes")),
        List.of(),
        Set.of("demo.App", "demo.Target"),
        "SootUp",
        "3.0.1",
        2,
        1);
    FrameworkModel overclaimingModel = new FrameworkModel() {
      @Override
      public AnalysisContext.Framework framework() {
        return AnalysisContext.Framework.QUARKUS;
      }

      @Override
      public List<SemanticCallFacts.Fact> enrich(AnalysisContext selectedContext, AnalysisGraph ignored) {
        return List.of(
            dynamicFact(
                selectedContext,
                "overstated-reflection",
                "reflection",
                "reflection_api"),
            dynamicFact(
                selectedContext,
                "overstated-proxy",
                "proxy_dispatch",
                "dynamic_proxy_call"),
            SemanticCallFacts.Fact.framework(
                "bounded-proxy",
                selectedContext,
                new SemanticCallFacts.Fact.FrameworkInput(
                    "quarkus:arc-client-proxy:demo.Target",
                    "demo.Target#run",
                    "proxy_dispatch",
                    SemanticCallFacts.Certainty.DEFINITE,
                    "quarkus-metadata-proxy-target",
                    List.of(new SemanticCallFacts.Evidence(
                        "bean_scope",
                        "src/main/java/demo/Target.java",
                        List.of("ApplicationScoped"),
                        2,
                        Map.of())),
                    null)));
      }
    };

    SemanticCallFacts facts = new JavaAnalysisAdapter(
        new SootUpBaseGraphProvider(), overclaimingModel).analyze(context, graph);

    List<SemanticCallFacts.Fact> dynamicFacts = facts.frameworkFacts().stream()
        .filter(DynamicCertaintyGuardTest::isDynamicFact)
        .toList();
    assertEquals(2, dynamicFacts.size());
    assertTrue(dynamicFacts.stream().allMatch(fact ->
        fact.certainty() == SemanticCallFacts.Certainty.UNRESOLVED
            && fact.status() == SemanticCallFacts.Status.ABSTAINED
            && fact.derivedFrom() == null));
    assertTrue(facts.links().isEmpty());

    SemanticCallFacts.Fact boundedProxy = facts.frameworkFacts().stream()
        .filter(fact -> fact.id().equals("bounded-proxy"))
        .findFirst()
        .orElseThrow();
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, boundedProxy.certainty());
    assertEquals(SemanticCallFacts.Status.EMITTED, boundedProxy.status());
  }

  /** Ensures dynamic proxy evidence overrides a bounded proxy model rule. */
  @Test
  void dynamicProxyEvidenceOverridesBoundedProxyModelRule() throws IOException {
    Path sourceRoot = temp.resolve("src/main/java");
    Files.createDirectories(sourceRoot);
    AnalysisContext context = context(sourceRoot);
    SemanticCallFacts.Fact boundedProxy = SemanticCallFacts.Fact.framework(
        "bounded-dynamic-proxy",
        context,
        new SemanticCallFacts.Fact.FrameworkInput(
            "quarkus:arc-client-proxy:demo.Target",
            "demo.Target#run",
            "proxy_dispatch",
            SemanticCallFacts.Certainty.DEFINITE,
            "quarkus-metadata-proxy-target",
            List.of(new SemanticCallFacts.Evidence(
                "dynamic_proxy_call",
                "src/main/java/demo/App.java",
                List.of("Proxy.newProxyInstance"),
                4,
                Map.of("targetDescriptor", "()V"))),
            null));

    assertTrue(DynamicCertaintyPolicy.isOpenWorldFrameworkFact(boundedProxy));
    SemanticCallFacts.Fact abstained = boundedProxy.abstainDynamic();

    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED, abstained.certainty());
    assertEquals(SemanticCallFacts.Status.ABSTAINED, abstained.status());
    assertNull(abstained.derivedFrom());
  }

  /** Verifies all supported dynamic relationship aliases override definite hints. */
  @Test
  void providerDynamicAliasesRemainAbstained() throws IOException {
    Path sourceRoot = temp.resolve("src/main/java");
    Files.createDirectories(sourceRoot);
    AnalysisContext context = context(sourceRoot);
    MethodSymbol caller = new MethodSymbol("demo.App", "run", "()V");
    MethodSymbol callee = new MethodSymbol("demo.Target", "run", "()V");
    List<String> dynamicRelationships = List.of(
        "reflection", "reflection_api", "dynamic", "dynamic_proxy", "proxy_dispatch");
    List<CallEdge> edges = new java.util.ArrayList<>();
    for (int index = 0; index < dynamicRelationships.size(); index++) {
      edges.add(new CallEdge(
          caller,
          callee,
          index + 1,
          "src/main/java/demo/App.java",
          SemanticCallFacts.Certainty.DEFINITE,
          dynamicRelationships.get(index)));
    }
    edges.add(new CallEdge(
        caller,
        callee,
        10,
        "src/main/java/demo/App.java",
        SemanticCallFacts.Certainty.DEFINITE,
        "invokes"));

    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(context, new AnalysisGraph(
        edges,
        List.of(),
        Set.of(),
        "SootUp",
        "3.0.1",
        0,
        edges.size()));

    assertTrue(facts.providerFacts().stream()
        .filter(fact -> dynamicRelationships.contains(fact.relationshipType()))
        .allMatch(fact -> fact.certainty() == SemanticCallFacts.Certainty.UNRESOLVED
            && fact.status() == SemanticCallFacts.Status.ABSTAINED));
    SemanticCallFacts.Fact ordinary = facts.providerFacts().stream()
        .filter(fact -> fact.relationshipType().equals("invokes"))
        .findFirst()
        .orElseThrow();
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, ordinary.certainty());
    assertFalse(ordinary.status() == SemanticCallFacts.Status.ABSTAINED);
  }

  /** Ensures repeated dynamic abstention supplies the contract limitation when it is missing. */
  @Test
  void alreadyUnresolvedDynamicFactReceivesDefaultLimitation() throws IOException {
    Path sourceRoot = temp.resolve("src/main/java");
    Files.createDirectories(sourceRoot);
    AnalysisContext context = context(sourceRoot);
    SemanticCallFacts.Fact unresolved = SemanticCallFacts.Fact.framework(
         "unresolved-without-limitation",
         context,
         new SemanticCallFacts.Fact.FrameworkInput(
             "dynamic",
             "demo.Target#run",
             "reflection",
             SemanticCallFacts.Certainty.UNRESOLVED,
             "source-dynamic-reflection",
             List.of(),
             null));

    SemanticCallFacts.Fact abstained = unresolved.abstainDynamic();

    assertEquals(SemanticCallFacts.Status.ABSTAINED, abstained.status());
    assertEquals(DynamicCertaintyPolicy.LIMITATION, abstained.limitation());
  }

  /** Ensures abstention removes a provider identity assigned before dynamic certainty was known. */
  @Test
  void abstainingDynamicFactClearsPreassignedProviderIdentity() throws IOException {
    Path sourceRoot = temp.resolve("src/main/java");
    Files.createDirectories(sourceRoot);
    AnalysisContext context = context(sourceRoot);
    SemanticCallFacts.Fact linked = SemanticCallFacts.Fact.framework(
            "linked-dynamic",
            context,
            new SemanticCallFacts.Fact.FrameworkInput(
                "dynamic",
                "demo.Target#run",
                "proxy_dispatch",
                SemanticCallFacts.Certainty.POSSIBLE,
                "provider-hint",
                List.of(),
                null))
        .withDerivedFrom("provider-fact-id");

    SemanticCallFacts.Fact abstained = linked.abstainDynamic();

    assertEquals("provider-fact-id", linked.derivedFrom());
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED, abstained.certainty());
    assertEquals(SemanticCallFacts.Status.ABSTAINED, abstained.status());
    assertNull(abstained.derivedFrom());
  }

  /** Identifies the dynamic framework facts used by the regression assertions. */
  private static boolean isDynamicFact(SemanticCallFacts.Fact fact) {
    return fact.relationshipType().equals("reflection")
        || fact.evidence().stream().anyMatch(evidence ->
            evidence.kind().equals("dynamic_proxy_call"));
  }

  /** Builds a deliberately overstated dynamic framework fact. */
  private static SemanticCallFacts.Fact dynamicFact(
      AnalysisContext context,
      String id,
      String relationshipType,
      String evidenceKind) {
    return SemanticCallFacts.Fact.framework(
         id,
         context,
         new SemanticCallFacts.Fact.FrameworkInput(
             "dynamic",
             "demo.Target#run",
             relationshipType,
             SemanticCallFacts.Certainty.DEFINITE,
             "overstated-default",
             List.of(new SemanticCallFacts.Evidence(
                 evidenceKind,
                 "src/main/java/demo/App.java",
                 List.of(evidenceKind),
                 4,
                 Map.of("targetDescriptor", "()V"))),
             null));
  }

  /** Builds the minimal production context for certainty-guard tests. */
  private AnalysisContext context(Path sourceRoot) {
    return AnalysisContext.builder()
        .id("quarkus-production")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("dynamic-guard-test")
        .javaRelease(17)
        .repositoryRoot(temp)
        .applicationClasses(temp.resolve("target/classes"))
        .selectedProfile("production")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true,
            "source-root-fingerprint")
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
  }
}
