package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Provenance labels for the ServiceLoader descriptor lane (issue #69,
 * owner decision: explicit selection overrides root-scope exclusion,
 * and everything seen carries its provenance/scope label — the label
 * is the safety valve that makes the override never-silent).
 *
 * <p>Each cell pins BOTH halves: the admission (the override is the
 * current, now-deliberate behavior — scanned today) and the label
 * (RED until GREEN_69 emits {@code descriptorProvenance} and
 * {@code descriptorRootScope} on the descriptor evidence).
 */
class ServiceLoaderDescriptorProvenanceTest {
  private static final String BRIDGE = """
      package demo;
      import java.util.ServiceLoader;
      class Bridge { void audit() { ServiceLoader.load(Provider.class).findFirst()
          .ifPresent(value -> value.record()); } }
      """;

  @TempDir Path temp;

  @Test
  void selectedMetadataFileInsideAnExcludedRootIsScannedAndLabeled()
      throws IOException {
    // The excluded root is BESIDE (not under) any included root, so
    // only the explicitlySelected fallback can admit the file.
    Path root = temp.resolve("p1");
    Path sourceRoot = root.resolve("src/main/java");
    Path excludedRoot = root.resolve("excluded-gen");
    writeBridge(sourceRoot);
    writeServices(excludedRoot.resolve("META-INF/services"));
    AnalysisContext context = baseBuilder(root, sourceRoot)
        .generatedSourceRoot("excluded-gen", excludedRoot,
            AnalysisContext.Scope.PRODUCTION, false, false, null)
        .frameworkMetadata(metadata("excluded-gen/META-INF/services/demo.Provider"))
        .build();
    ServiceLoaderDescriptorScanner.Scan scan =
        ServiceLoaderDescriptorScanner.scan(context);
    // The override, pinned: the descriptor IS scanned.
    assertEquals(1, scan.descriptors().size(), scan.descriptors().toString());
    SemanticCallFacts.Evidence evidence = descriptorEvidence(context, scan);
    assertEquals("selected_metadata", evidence.attributes().get("descriptorProvenance"),
        evidence.attributes().toString());
    assertEquals("production/excluded", evidence.attributes().get("descriptorRootScope"),
        evidence.attributes().toString());
  }

  @Test
  void selectedMetadataDirectorySweepingANestedTestRootIsLabeled()
      throws IOException {
    // The declared services DIRECTORY sits under the included source
    // root, inside a nested TEST-scope declared root: the metadata arm
    // scanned it (selected_metadata) even though the included-roots
    // containment also admits the directory — the arm that scanned the
    // file is the mechanism, and the most-specific declared root is the
    // scope label.
    Path root = temp.resolve("p2");
    Path sourceRoot = root.resolve("src/main/java");
    Path testRoot = sourceRoot.resolve("test-scope");
    writeBridge(sourceRoot);
    writeServices(testRoot.resolve("META-INF/services"));
    AnalysisContext context = baseBuilder(root, sourceRoot)
        .generatedSourceRoot("src/main/java/test-scope", testRoot,
            AnalysisContext.Scope.TEST, true, false, null)
        .frameworkMetadata(metadata("src/main/java/test-scope/META-INF/services"))
        .build();
    ServiceLoaderDescriptorScanner.Scan scan =
        ServiceLoaderDescriptorScanner.scan(context);
    assertEquals(1, scan.descriptors().size(), scan.descriptors().toString());
    SemanticCallFacts.Evidence evidence = descriptorEvidence(context, scan);
    assertEquals("selected_metadata", evidence.attributes().get("descriptorProvenance"),
        evidence.attributes().toString());
    assertEquals("test/included", evidence.attributes().get("descriptorRootScope"),
        evidence.attributes().toString());
  }

  @Test
  void selectedMetadataFileInsideAnExcludedRootUnderAnIncludedRootIsLabeled()
      throws IOException {
    // The round-1 geometry cell: the excluded root is NESTED UNDER the
    // included source root, so the included-roots containment admits
    // the declared file without the fallback — but the mechanism is
    // still the arm that scanned it (selected_metadata), and the label
    // distinguishes the excluded scope that the admission geometry
    // cannot.
    Path root = temp.resolve("p2b");
    Path sourceRoot = root.resolve("src/main/java");
    Path excludedNested = sourceRoot.resolve("excluded-gen");
    writeBridge(sourceRoot);
    writeServices(excludedNested.resolve("META-INF/services"));
    AnalysisContext context = baseBuilder(root, sourceRoot)
        .generatedSourceRoot("src/main/java/excluded-gen", excludedNested,
            AnalysisContext.Scope.PRODUCTION, false, false, null)
        .frameworkMetadata(metadata(
            "src/main/java/excluded-gen/META-INF/services/demo.Provider"))
        .build();
    ServiceLoaderDescriptorScanner.Scan scan =
        ServiceLoaderDescriptorScanner.scan(context);
    assertEquals(1, scan.descriptors().size(), scan.descriptors().toString());
    SemanticCallFacts.Evidence evidence = descriptorEvidence(context, scan);
    assertEquals("selected_metadata", evidence.attributes().get("descriptorProvenance"),
        evidence.attributes().toString());
    assertEquals("production/excluded", evidence.attributes().get("descriptorRootScope"),
        evidence.attributes().toString());
  }

  @Test
  void perRootResourcesSweepIsLabeledIncludedRoot() throws IOException {
    Path root = temp.resolve("p3");
    Path sourceRoot = root.resolve("src/main/java");
    writeBridge(sourceRoot);
    writeServices(root.resolve("src/main/resources/META-INF/services"));
    AnalysisContext context = baseBuilder(root, sourceRoot).build();
    ServiceLoaderDescriptorScanner.Scan scan =
        ServiceLoaderDescriptorScanner.scan(context);
    assertEquals(1, scan.descriptors().size(), scan.descriptors().toString());
    SemanticCallFacts.Evidence evidence = descriptorEvidence(context, scan);
    assertEquals("included_root", evidence.attributes().get("descriptorProvenance"),
        evidence.attributes().toString());
    // The sweep subtree (src/main/resources) is a SIBLING of the declared
    // source root, not under it - no root-scope label (plan-review round 1,
    // finding 1; the cell's original production/included expectation was
    // wrong).
    assertFalse(evidence.attributes().containsKey("descriptorRootScope"),
        evidence.attributes().toString());
  }

  @Test
  void applicationClassesDescriptorIsLabeledWithoutRootScope()
      throws IOException {
    Path root = temp.resolve("p4");
    Path sourceRoot = root.resolve("src/main/java");
    Path classes = root.resolve("target/classes");
    writeBridge(sourceRoot);
    writeServices(classes.resolve("META-INF/services"));
    AnalysisContext context = baseBuilder(root, sourceRoot)
        .applicationClasses(classes)
        .build();
    ServiceLoaderDescriptorScanner.Scan scan =
        ServiceLoaderDescriptorScanner.scan(context);
    assertEquals(1, scan.descriptors().size(), scan.descriptors().toString());
    SemanticCallFacts.Evidence evidence = descriptorEvidence(context, scan);
    assertEquals("application_classes", evidence.attributes().get("descriptorProvenance"),
        evidence.attributes().toString());
    assertFalse(evidence.attributes().containsKey("descriptorRootScope"),
        evidence.attributes().toString());
  }

  @Test
  void dependencyEntryDescriptorIsLabeledWithoutRootScope()
      throws IOException {
    // The dependency arm's mechanism, and the synthetic-path null-label
    // contract: a dependency directory's services file carries
    // dependency_entry and no root-scope label (P4 covered only the
    // applicationClasses half of the pairwise matrix).
    Path root = temp.resolve("p4b");
    Path sourceRoot = root.resolve("src/main/java");
    Path dependency = root.resolve("lib");
    writeBridge(sourceRoot);
    writeServices(dependency.resolve("META-INF/services"));
    AnalysisContext context = baseBuilder(root, sourceRoot)
        .dependencyClasspath(List.of(dependency))
        .build();
    ServiceLoaderDescriptorScanner.Scan scan =
        ServiceLoaderDescriptorScanner.scan(context);
    assertEquals(1, scan.descriptors().size(), scan.descriptors().toString());
    SemanticCallFacts.Evidence evidence = descriptorEvidence(context, scan);
    assertEquals("dependency_entry", evidence.attributes().get("descriptorProvenance"),
        evidence.attributes().toString());
    assertFalse(evidence.attributes().containsKey("descriptorRootScope"),
        evidence.attributes().toString());
  }

  @Test
  void symlinkedSelectionPathIsLabeledByTheRealLocation() throws IOException {
    // CodeRabbit CR-1 on PR #73: a metadata path selected through a
    // symbolic-link parent inside the included root aliases the excluded
    // root's services file. Admission compares real paths (under/
    // sameRealPath); the label must too, or the descriptor would be
    // labeled production/included while really lying under the excluded
    // root. The direct-path control is P1.
    Path root = temp.resolve("p7");
    Path sourceRoot = root.resolve("src/main/java");
    Path excludedRoot = root.resolve("excluded-gen");
    writeBridge(sourceRoot);
    writeServices(excludedRoot.resolve("META-INF/services"));
    Files.createSymbolicLink(
        sourceRoot.resolve("alias"), Path.of("../../../excluded-gen"));
    AnalysisContext context = baseBuilder(root, sourceRoot)
        .generatedSourceRoot("excluded-gen", excludedRoot,
            AnalysisContext.Scope.PRODUCTION, false, false, null)
        .frameworkMetadata(metadata(
            "src/main/java/alias/META-INF/services/demo.Provider"))
        .build();
    ServiceLoaderDescriptorScanner.Scan scan =
        ServiceLoaderDescriptorScanner.scan(context);
    // Admission unchanged: the aliased descriptor IS scanned (the
    // explicit-selection arm matches real paths).
    assertEquals(1, scan.descriptors().size(), scan.descriptors().toString());
    SemanticCallFacts.Evidence evidence = descriptorEvidence(context, scan);
    assertEquals("selected_metadata", evidence.attributes().get("descriptorProvenance"),
        evidence.attributes().toString());
    assertEquals("production/excluded", evidence.attributes().get("descriptorRootScope"),
        evidence.attributes().toString());
  }

  @Test
  void labelsReachTheProviderFactEndToEnd() throws IOException {
    Path root = temp.resolve("p5");
    Path sourceRoot = root.resolve("src/main/java");
    Path excludedRoot = root.resolve("excluded-gen");
    writeBridge(sourceRoot);
    writeServices(excludedRoot.resolve("META-INF/services"));
    AnalysisContext context = baseBuilder(root, sourceRoot)
        .generatedSourceRoot("excluded-gen", excludedRoot,
            AnalysisContext.Scope.PRODUCTION, false, false, null)
        .frameworkMetadata(metadata("excluded-gen/META-INF/services/demo.Provider"))
        .build();
    CallEdge factEdge = new CallEdge(
        new MethodSymbol("demo.Bridge", "audit", "()V"),
        new MethodSymbol("demo.ProviderImpl", "record", "()V"),
        4,
        "src/main/java/demo/Bridge.java",
        SemanticCallFacts.Certainty.DEFINITE,
        "service_loader");
    List<SemanticCallFacts.Fact> facts = new JavaAnalysisAdapter()
        .analyze(context, new AnalysisGraph(
            List.of(factEdge), List.of(), java.util.Set.of(), "SootUp", "3.0.1", 0, 0))
        .providerFacts();
    assertTrue(facts.stream().anyMatch(fact ->
            fact.evidence().stream()
                .filter(evidence -> evidence.kind().equals("service_loader_descriptor"))
                .anyMatch(evidence ->
                    "selected_metadata".equals(
                        evidence.attributes().get("descriptorProvenance"))
                        && "production/excluded".equals(
                            evidence.attributes().get("descriptorRootScope")))),
        facts.toString());
  }

  @Test
  void sameContentMergeKeepsTheClasspathTwinLabel() throws IOException {
    // The DescriptorIdentity merge (classpath-preferred, pre-existing
    // pinned behavior) discards the excluded twin's label: the
    // survivor carries the application_classes twin's provenance.
    // Pinned as documented behavior, not changed here.
    Path root = temp.resolve("p6");
    Path sourceRoot = root.resolve("src/main/java");
    Path excludedRoot = root.resolve("excluded-gen");
    Path classes = root.resolve("target/classes");
    writeBridge(sourceRoot);
    writeServices(excludedRoot.resolve("META-INF/services"));
    writeServices(classes.resolve("META-INF/services"));
    AnalysisContext context = baseBuilder(root, sourceRoot)
        .applicationClasses(classes)
        .generatedSourceRoot("excluded-gen", excludedRoot,
            AnalysisContext.Scope.PRODUCTION, false, false, null)
        .frameworkMetadata(metadata("excluded-gen/META-INF/services/demo.Provider"))
        .build();
    ServiceLoaderDescriptorScanner.Scan scan =
        ServiceLoaderDescriptorScanner.scan(context);
    // The scan keeps BOTH same-content twins; the DescriptorIdentity merge
    // happens at resolve time. The classpath twin wins here (its display
    // path is absolute and sorts first; both twins are classpath-backed),
    // and the survivor carries the classpath twin's label - the excluded
    // twin's label is DISCARDED by the merge. That is the documented
    // residual from plan-review round 2 finding 4: the merge can drop a
    // scope label; deterministic but not label-cautious. Pinned as
    // observed behavior, not changed here.
    assertEquals(2, scan.descriptors().size(), scan.descriptors().toString());
    SemanticCallFacts.Evidence evidence = descriptorEvidence(context, scan);
    assertEquals("application_classes", evidence.attributes().get("descriptorProvenance"),
        evidence.attributes().toString());
  }

  private SemanticCallFacts.Evidence descriptorEvidence(
      AnalysisContext context, ServiceLoaderDescriptorScanner.Scan scan) {
    CallEdge edge = new CallEdge(
        new MethodSymbol("demo.Bridge", "audit", "()V"),
        new MethodSymbol("demo.ProviderImpl", "record", "()V"),
        4,
        "src/main/java/demo/Bridge.java",
        SemanticCallFacts.Certainty.DEFINITE,
        "service_loader");
    ServiceLoaderDescriptorScanner.Resolution resolution =
        ServiceLoaderDescriptorScanner.resolve(
            context, edge, scan, new SourceMetadataScanner(context));
    return resolution.evidence().stream()
        .filter(evidence -> evidence.kind().equals("service_loader_descriptor"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(resolution.evidence().toString()));
  }

  private AnalysisContext.FrameworkMetadata metadata(String declaredPath) {
    return new AnalysisContext.FrameworkMetadata(
        "service-loader-resources", "service_loader_descriptor",
        AnalysisContext.MetadataStatus.OBSERVED, declaredPath);
  }

  private AnalysisContext.Builder baseBuilder(Path root, Path sourceRoot) {
    return AnalysisContext.builder()
        .id(root.getFileName().toString())
        .framework(AnalysisContext.Framework.SPRING_BOOT)
        .frameworkVersion("3.4.0")
        .fixtureId("descriptor-provenance-test")
        .javaRelease(17)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("nowhere-classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot,
            AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting());
  }

  private void writeBridge(Path sourceRoot) throws IOException {
    Path bridge = sourceRoot.resolve("demo/Bridge.java");
    Files.createDirectories(bridge.getParent());
    Files.writeString(bridge, BRIDGE);
    Files.writeString(sourceRoot.resolve("demo/Provider.java"),
        "package demo; interface Provider { void record(); }");
    Files.writeString(sourceRoot.resolve("demo/ProviderImpl.java"),
        "package demo; final class ProviderImpl implements Provider"
        + " { public void record() { } }");
  }

  private void writeServices(Path services) throws IOException {
    Files.createDirectories(services);
    Files.writeString(services.resolve("demo.Provider"), "demo.ProviderImpl\n");
  }
}
