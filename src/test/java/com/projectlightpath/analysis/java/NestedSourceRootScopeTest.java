package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Nested source-root scope semantics (issue #68): a declared root that is
 * excluded ({@code included=false}) or out-of-scope (e.g. TEST under
 * PRODUCTION) never contributes {@code .java} files to the parent
 * included root's walk — in BOTH walkers (the scanner and the javac
 * index), symmetrically, and regardless of the
 * {@code flpath.java.symbolindex} flag state. The most-specific DECLARED
 * root governs: an included nested root under an excluded parent still
 * contributes.
 *
 * <p>N6 writes the flag, so the class is {@code @Isolated} +
 * {@code SAME_THREAD}; {@code @AfterEach} restores the default.
 */
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class NestedSourceRootScopeTest {
  private static final String FLAG = "flpath.java.symbolindex";

  @TempDir Path temp;

  @AfterEach
  void restoreFlag() {
    System.clearProperty(FLAG);
  }

  @Test
  void scannerExcludesNestedOutOfScopeRootFiles() throws IOException {
    AnalysisContext context = testNestedUnderProduction("scanner-test-scope");
    List<String> files = new SourceMetadataScanner(context).files().stream()
        .map(file -> file.path().getFileName().toString())
        .sorted()
        .toList();
    assertEquals(List.of("App.java"), files);
  }

  @Test
  void indexExcludesNestedOutOfScopeRootFiles() throws IOException {
    AnalysisContext context = testNestedUnderProduction("index-test-scope");
    JavacSourceSymbolIndex index = JavacSourceSymbolIndex.of(context)
        .orElseThrow();
    assertEquals(1, index.type("demo.App").size(), "non-vacuity: App indexed");
    assertTrue(index.type("demo.Extra").isEmpty(),
        index.type("demo.Extra").toString());
  }

  @Test
  void scannerExcludesNestedExcludedGeneratedRootFiles() throws IOException {
    Path root = temp.resolve("excluded-generated");
    Path sourceRoot = root.resolve("src/main/java");
    Path generatedRoot = root.resolve("generated-src/main/java");
    Path excludedNested = generatedRoot.resolve("excluded");
    write(sourceRoot, "demo/App.java",
        "package demo; public class App {}");
    write(generatedRoot, "demo/generated/Kept.java",
        "package demo.generated; public class Kept {}");
    write(excludedNested, "demo/generated/Dropped.java",
        "package demo.generated; public class Dropped {}");
    AnalysisContext context = baseBuilder("excluded-generated", root, sourceRoot)
        .generatedSourceRoot("generated-src/main/java", generatedRoot,
            AnalysisContext.Scope.PRODUCTION, true, true, "kept-fingerprint")
        .generatedSourceRoot("generated-src/excluded", excludedNested,
            AnalysisContext.Scope.PRODUCTION, false, false, null)
        .build();
    List<String> files = new SourceMetadataScanner(context).files().stream()
        .map(file -> file.path().getFileName().toString())
        .sorted()
        .toList();
    assertEquals(List.of("App.java", "Kept.java"), files);
  }

  @Test
  void indexExcludesNestedExcludedGeneratedRootFiles() throws IOException {
    Path root = temp.resolve("excluded-generated-index");
    Path sourceRoot = root.resolve("src/main/java");
    Path generatedRoot = root.resolve("generated-src/main/java");
    Path excludedNested = generatedRoot.resolve("excluded");
    write(generatedRoot, "demo/generated/Kept.java",
        "package demo.generated; public class Kept {}");
    write(excludedNested, "demo/generated/Dropped.java",
        "package demo.generated; public class Dropped {}");
    AnalysisContext context = baseBuilder(
        "excluded-generated-index", root, sourceRoot)
        .generatedSourceRoot("generated-src/main/java", generatedRoot,
            AnalysisContext.Scope.PRODUCTION, true, true, "kept-fingerprint")
        .generatedSourceRoot("generated-src/excluded", excludedNested,
            AnalysisContext.Scope.PRODUCTION, false, false, null)
        .build();
    JavacSourceSymbolIndex index = JavacSourceSymbolIndex.of(context)
        .orElseThrow();
    assertEquals(1, index.type("demo.generated.Kept").size(),
        "non-vacuity: Kept indexed");
    assertTrue(index.type("demo.generated.Dropped").isEmpty());
  }

  @Test
  void includedNestedRootUnderExcludedParentStillContributes()
      throws IOException {
    // The most-specific DECLARED root governs: an included nested root
    // under an excluded parent is walked today and must stay walked.
    Path root = temp.resolve("specificity-edge");
    Path sourceRoot = root.resolve("src/main/java");
    Path excludedParent = root.resolve("generated-src/excluded");
    Path includedNested = excludedParent.resolve("keep");
    write(sourceRoot, "demo/App.java", "package demo; public class App {}");
    write(includedNested, "demo/generated/Kept.java",
        "package demo.generated; public class Kept {}");
    AnalysisContext context = baseBuilder("specificity-edge", root, sourceRoot)
        .generatedSourceRoot("generated-src/excluded", excludedParent,
            AnalysisContext.Scope.PRODUCTION, false, false, null)
        .generatedSourceRoot("generated-src/excluded/keep", includedNested,
            AnalysisContext.Scope.PRODUCTION, true, true, "kept-fingerprint")
        .build();
    List<String> files = new SourceMetadataScanner(context).files().stream()
        .map(file -> file.path().getFileName().toString())
        .sorted()
        .toList();
    assertEquals(List.of("App.java", "Kept.java"), files);
    assertEquals(1, JavacSourceSymbolIndex.of(context).orElseThrow()
        .type("demo.generated.Kept").size());
  }

  @Test
  void flagStatesAgreeOnTheExcludedNestedFileSet() throws IOException {
    // Parity over the generated fixture (a non-empty fact oracle — the
    // included mapper produces a generated_implementation fact), so the
    // comparison cannot pass vacuously.
    System.setProperty(FLAG, "false");
    List<String> optOut = generatedProjections();
    System.clearProperty(FLAG);
    List<String> byDefault = generatedProjections();
    assertEquals(optOut, byDefault);
    assertTrue(byDefault.stream().anyMatch(line -> line.contains("KeptMapper")),
        byDefault.toString());
    assertFalse(byDefault.stream().anyMatch(line -> line.contains("Dropped")),
        byDefault.toString());
  }

  @Test
  void allFilesExcludedIsNotMissingSource() throws IOException {
    // An included root whose only .java files sit under a nested
    // excluded root: scope-excluded is not missing — no missing_source
    // row from the index (the scanner file set is the cross-flag
    // oracle; flag-off has no index diagnostics to compare).
    Path root = temp.resolve("all-excluded");
    Path sourceRoot = root.resolve("src/main/java");
    Path excludedNested = sourceRoot.resolve("excluded");
    write(excludedNested, "demo/Hidden.java",
        "package demo; public class Hidden {}");
    AnalysisContext context = baseBuilder("all-excluded", root, sourceRoot)
        .generatedSourceRoot("src/main/java/excluded", excludedNested,
            AnalysisContext.Scope.PRODUCTION, false, false, null)
        .build();
    assertTrue(new SourceMetadataScanner(context).files().isEmpty());
    JavacSourceSymbolIndex index = JavacSourceSymbolIndex.of(context)
        .orElseThrow();
    assertTrue(index.diagnostics().stream()
        .noneMatch(row -> row.code().equals("missing_source")),
        index.diagnostics().toString());
  }

  @Test
  void generatedProvenanceExcludesNestedRootFiles() throws IOException {
    // End to end: the shape of the pre-existing
    // generatedProvenanceUsesOnlyIncludedActiveScopeNestedRoots fixture
    // plus an IncludedMapper under the included root — exactly one
    // generated fact, none for the excluded/test mappers.
    Path root = temp.resolve("provenance");
    Path sourceRoot = root.resolve("src/main/java");
    Path generatedRoot = root.resolve("generated-src/main/java");
    Path excludedNested = generatedRoot.resolve("excluded");
    Path testNested = generatedRoot.resolve("test");
    write(generatedRoot, "demo/generated/IncludedMapper.java", """
        package demo.generated;
        public class IncludedMapper { public String map(String value) { return value; } }
        """);
    write(excludedNested, "demo/generated/ExcludedMapper.java", """
        package demo.generated;
        public class ExcludedMapper { public String map(String value) { return value; } }
        """);
    write(testNested, "demo/generated/TestMapper.java", """
        package demo.generated;
        public class TestMapper { public String map(String value) { return value; } }
        """);
    AnalysisContext context = baseBuilder("provenance", root, sourceRoot)
        .generatedSourceRoot("generated-src/main/java", generatedRoot,
            AnalysisContext.Scope.PRODUCTION, true, true, "included-fingerprint")
        .generatedSourceRoot("generated-src/excluded", excludedNested,
            AnalysisContext.Scope.PRODUCTION, false, false, "excluded-fingerprint")
        .generatedSourceRoot("generated-src/test", testNested,
            AnalysisContext.Scope.TEST, true, false, "test-fingerprint")
        .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
            "quarkus-generated-source-root", "generated_source_root",
            AnalysisContext.MetadataStatus.OBSERVED, "generated-src/main/java"))
        .build();
    List<SemanticCallFacts.Fact> facts = new JavaAnalysisAdapter()
        .analyze(context, AnalysisGraph.empty()).frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("generated_implementation"))
        .toList();
    assertEquals(1, facts.size(), facts.toString());
    assertEquals("demo.generated.IncludedMapper#map", facts.get(0).callee());
    assertEquals("generated-src/main/java",
        facts.get(0).evidence().get(0).attributes().get("sourceRoot"));
    assertEquals("true",
        facts.get(0).evidence().get(0).attributes().get("sourceRootIncluded"));
    assertEquals("production",
        facts.get(0).evidence().get(0).attributes().get("sourceRootScope"));
    assertEquals("included-fingerprint",
        facts.get(0).evidence().get(0).attributes().get("sourceRootFingerprint"));
    assertTrue(facts.stream().noneMatch(item ->
        item.callee().contains("ExcludedMapper")
            || item.callee().contains("TestMapper")), facts.toString());
  }

  /** The N1/N2 fixture: a nested included TEST-scope root under a PRODUCTION root. */
  private AnalysisContext testNestedUnderProduction(String id)
      throws IOException {
    Path root = temp.resolve(id);
    Path sourceRoot = root.resolve("src/main/java");
    Path testNested = sourceRoot.resolve("test-java");
    write(sourceRoot, "demo/App.java", "package demo; public class App {}");
    write(testNested, "demo/Extra.java",
        "package demo; public class Extra {}");
    return baseBuilder(id, root, sourceRoot)
        .sourceRoot("src/main/java/test-java", testNested,
            AnalysisContext.Scope.TEST, true)
        .build();
  }

  private List<String> generatedProjections() throws IOException {
    Path root = temp.resolve("parity");
    Path sourceRoot = root.resolve("src/main/java");
    Path generatedRoot = root.resolve("generated-src/main/java");
    Path excludedNested = generatedRoot.resolve("excluded");
    write(generatedRoot, "demo/generated/KeptMapper.java", """
        package demo.generated;
        public class KeptMapper { public String map(String value) { return value; } }
        """);
    write(excludedNested, "demo/generated/DroppedMapper.java", """
        package demo.generated;
        public class DroppedMapper { public String map(String value) { return value; } }
        """);
    AnalysisContext context = baseBuilder("parity", root, sourceRoot)
        .generatedSourceRoot("generated-src/main/java", generatedRoot,
            AnalysisContext.Scope.PRODUCTION, true, true, "kept-fingerprint")
        .generatedSourceRoot("generated-src/excluded", excludedNested,
            AnalysisContext.Scope.PRODUCTION, false, false, null)
        .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
            "quarkus-generated-source-root", "generated_source_root",
            AnalysisContext.MetadataStatus.OBSERVED, "generated-src/main/java"))
        .build();
    List<String> files = new SourceMetadataScanner(context).files().stream()
        .map(file -> file.path().getFileName().toString())
        .sorted()
        .toList();
    List<String> callees = new JavaAnalysisAdapter()
        .analyze(context, AnalysisGraph.empty()).frameworkFacts().stream()
        .map(SemanticCallFacts.Fact::callee)
        .sorted()
        .toList();
    List<String> projection = new java.util.ArrayList<>(files);
    projection.addAll(callees);
    return projection;
  }

  private AnalysisContext.Builder baseBuilder(
      String id, Path root, Path sourceRoot) {
    return AnalysisContext.builder()
        .id(id)
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("nested-root-scope-test")
        .javaRelease(17)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot,
            AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting());
  }

  private static void write(Path root, String relative, String content)
      throws IOException {
    Path file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }
}
