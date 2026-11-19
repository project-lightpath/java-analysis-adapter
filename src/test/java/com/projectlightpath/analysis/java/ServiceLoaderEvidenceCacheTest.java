package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Item Q: edge-independent descriptor evidence is built once per run. */
class ServiceLoaderEvidenceCacheTest {
  @TempDir Path temp;

  @Test
  void descriptorEvidenceIsSharedAcrossEdgesInOneRun() throws IOException {
    Path sourceRoot = temp.resolve("src/main/java");
    Path classes = temp.resolve("target/classes");
    Path services = classes.resolve("META-INF/services");
    Files.createDirectories(sourceRoot.resolve("demo"));
    Files.createDirectories(services);
    Files.writeString(sourceRoot.resolve("demo/App.java"), "package demo;\n"
        + "import java.util.ServiceLoader;\n"
        + "class App {\n"
        + "  void audit1() { ServiceLoader.load(Provider.class).findFirst()"
        + ".ifPresent(value -> value.record()); }\n"
        + "  void audit2() { ServiceLoader.load(Provider.class).findFirst()"
        + ".ifPresent(value -> value.record()); }\n"
        + "}\n");
    Files.writeString(sourceRoot.resolve("demo/Provider.java"),
        "package demo; interface Provider { void record(); }\n");
    Files.writeString(services.resolve("demo.Provider"), "demo.DefaultProvider\n");

    AnalysisContext context = AnalysisContext.builder()
        .id("evidence-cache")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("evidence-cache")
        .javaRelease(17)
        .repositoryRoot(temp)
        .applicationClasses(classes)
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();

    CallEdge first = new CallEdge(
        new MethodSymbol("demo.App", "lambda$audit1$0", "()V"),
        new MethodSymbol("demo.DefaultProvider", "record", "()V"),
        4,
        "src/main/java/demo/App.java",
        SemanticCallFacts.Certainty.POSSIBLE,
        "service_loader");
    CallEdge second = new CallEdge(
        new MethodSymbol("demo.App", "lambda$audit2$0", "()V"),
        new MethodSymbol("demo.DefaultProvider", "record", "()V"),
        5,
        "src/main/java/demo/App.java",
        SemanticCallFacts.Certainty.POSSIBLE,
        "service_loader");
    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(context,
        new AnalysisGraph(List.of(first, second), List.of(),
            Set.of("demo.App", "demo.DefaultProvider"), "SootUp", "3.0.1", 2, 1));

    List<SemanticCallFacts.Evidence> descriptors = facts.providerFacts().stream()
        .filter(fact -> fact.relationshipType().equals("service_loader"))
        .map(fact -> fact.evidence().stream()
            .filter(evidence -> evidence.kind().equals("service_loader_descriptor"))
            .findFirst()
            .orElseThrow())
        .toList();
    assertEquals(2, descriptors.size(), descriptors.toString());
    assertSame(descriptors.get(0), descriptors.get(1));

    List<SemanticCallFacts.Evidence> resolutions = facts.providerFacts().stream()
        .filter(fact -> fact.relationshipType().equals("service_loader"))
        .map(fact -> fact.evidence().stream()
            .filter(evidence -> evidence.kind().equals("service_loader_resolution"))
            .findFirst()
            .orElseThrow())
        .toList();
    assertNotSame(resolutions.get(0), resolutions.get(1));

    SemanticCallFacts secondRun = new JavaAnalysisAdapter().analyze(context,
        new AnalysisGraph(List.of(first, second), List.of(),
            Set.of("demo.App", "demo.DefaultProvider"), "SootUp", "3.0.1", 2, 1));
    SemanticCallFacts.Evidence secondRunEvidence = secondRun.providerFacts().stream()
        .filter(fact -> fact.relationshipType().equals("service_loader"))
        .map(fact -> fact.evidence().stream()
            .filter(evidence -> evidence.kind().equals("service_loader_descriptor"))
            .findFirst()
            .orElseThrow())
        .findFirst()
        .orElseThrow();
    assertNotSame(descriptors.get(0), secondRunEvidence);
  }
}
