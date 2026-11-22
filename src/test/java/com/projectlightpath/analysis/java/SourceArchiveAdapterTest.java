package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end adapter routing for SOURCE_ARCHIVE inputs (issue #42 scope 2):
 * extraction feeds the existing source/javac analysis path, the descriptor
 * diagnostic keeps ServiceLoader facts POSSIBLE without binaries, binaries
 * stay authoritative in mixed mode, failures surface, and the random
 * extraction root never leaks into the output or the filesystem.
 */
class SourceArchiveAdapterTest {
  @TempDir Path temp;

  private static final String PROVIDER = """
      package demo;
      public interface Provider {
        String record();
      }
      """;
  private static final String DEFAULT_PROVIDER = """
      package demo;
      public final class DefaultProvider implements Provider {
        public String record() { return "default"; }
      }
      """;
  private static final String APP = """
      package demo;
      import java.util.*;
      public final class App {
        public static void audit() {
          ServiceLoader.load(Provider.class).findFirst()
              .ifPresent(provider -> provider.record());
        }
      }
      """;

  private record Fixture(Path sourcesJar, Path classes) {}

  private Fixture fixture(String jarName) throws IOException {
    Path sourceRoot = temp.resolve("src/main/java");
    Files.createDirectories(sourceRoot.resolve("demo"));
    Files.writeString(sourceRoot.resolve("demo/Provider.java"), PROVIDER);
    Files.writeString(sourceRoot.resolve("demo/DefaultProvider.java"), DEFAULT_PROVIDER);
    Files.writeString(sourceRoot.resolve("demo/App.java"), APP);
    Path jar = zipSources(sourceRoot, temp.resolve(jarName));
    Path classes = temp.resolve("classes");
    compile(sourceRoot, classes);
    return new Fixture(jar, classes);
  }

  private Path zipSources(Path sourceRoot, Path jar) throws IOException {
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(jar));
        Stream<Path> paths = Files.walk(sourceRoot)) {
      for (Path source : paths.filter(path -> path.toString().endsWith(".java")).sorted()
          .toList()) {
        output.putNextEntry(new ZipEntry(
            sourceRoot.relativize(source).toString().replace(
                source.getFileSystem().getSeparator(), "/")));
        Files.copy(source, output);
        output.closeEntry();
      }
    }
    return jar;
  }

  private void compile(Path sourceRoot, Path classes) throws IOException {
    Files.createDirectories(classes);
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "a JDK compiler is required");
    try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, null);
        Stream<Path> paths = Files.walk(sourceRoot)) {
      List<Path> sources = paths.filter(path -> path.toString().endsWith(".java"))
          .sorted().toList();
      assertTrue(compiler.getTask(
          null, manager, null,
          List.of("--release", "17", "-d", classes.toString()),
          null, manager.getJavaFileObjectsFromPaths(sources)).call(),
          "fixture compilation failed");
    }
  }

  private Path binaryDescriptorJar(String name) throws IOException {
    Path jar = temp.resolve(name);
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(jar))) {
      output.putNextEntry(new ZipEntry("META-INF/services/demo.Provider"));
      output.write("demo.DefaultProvider\n".getBytes(StandardCharsets.UTF_8));
      output.closeEntry();
    }
    return jar;
  }

  private AnalysisContext context(Fixture fixture, Path binary) {
    AnalysisContext.Builder builder = AnalysisContext.builder()
        .id("source-archive-adapter")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("source-archive-adapter")
        .javaRelease(17)
        .repositoryRoot(temp)
        .applicationClasses(fixture.classes())
        .selectedProfile("production")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .inputMode(AnalysisContext.InputMode.SOURCE_ARCHIVE)
        .dependencyClasspathRequired(false)
        .sourceArchive(fixture.sourcesJar())
        .provenance(AnalysisContext.Provenance.forTesting());
    if (binary != null) {
      builder.addDependencyClasspath(binary);
    }
    return builder.build();
  }

  private List<SemanticCallFacts.Fact> serviceLoaderFacts(SemanticCallFacts facts) {
    return facts.providerFacts().stream()
        .filter(fact -> fact.relationshipType().equals("service_loader"))
        .toList();
  }

  private Set<String> lingeringExtractionRoots() throws IOException {
    Path temporary = Path.of(System.getProperty("java.io.tmpdir"));
    try (Stream<Path> paths = Files.list(temporary)) {
      return paths.filter(path -> path.getFileName().toString()
              .startsWith("flpath-42-sources-"))
          .map(path -> path.getFileName().toString())
          .collect(java.util.stream.Collectors.toSet());
    }
  }

  /**
   * Serializes the residue assertions in this class: the adapter extracts
   * into the shared java.io.tmpdir, and JUnit runs these methods in
   * parallel, so a sibling method's live root would break a snapshot diff.
   */
  private static final Object EXTRACTION_LOCK = new Object();

  @Test
  void sourceOnlyArchiveRunStaysPossibleWithTheScanDiagnostic() throws IOException {
    synchronized (EXTRACTION_LOCK) {
    Fixture fixture = fixture("app-sources.jar");
    Set<String> before = lingeringExtractionRoots();

    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(context(fixture, null));

    List<SemanticCallFacts.Fact> serviceFacts = serviceLoaderFacts(facts);
    assertEquals(1, serviceFacts.size(), facts.providerFacts().toString());
    SemanticCallFacts.Fact fact = serviceFacts.get(0);
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, fact.certainty());
    assertTrue(fact.evidence().stream().noneMatch(
        item -> item.kind().equals("service_loader_descriptor")),
        fact.evidence().toString());
    assertTrue(fact.evidence().stream().anyMatch(item ->
            item.kind().equals("service_loader_scan_diagnostic")
                && "descriptor_metadata_unavailable_for_source_archive"
                    .equals(item.attributes().get("diagnosticCode"))
                && "incomplete".equals(item.attributes().get("scanStatus"))),
        fact.evidence().toString());
    assertNotNull(fact.limitation());
    assertTrue(fact.limitation().contains("descriptor_metadata_unavailable_for_source_archive"),
        fact.limitation());
    assertEquals(before, lingeringExtractionRoots());
    }
  }

  @Test
  void mixedArchiveRunWithBinariesPromotesDefiniteFromBinaryTruth() throws IOException {
    synchronized (EXTRACTION_LOCK) {
    Fixture fixture = fixture("mixed-sources.jar");
    Path binary = binaryDescriptorJar("mixed-application-1.0.0.jar");

    SemanticCallFacts facts =
        new JavaAnalysisAdapter().analyze(context(fixture, binary));

    List<SemanticCallFacts.Fact> serviceFacts = serviceLoaderFacts(facts);
    assertEquals(1, serviceFacts.size(), facts.providerFacts().toString());
    SemanticCallFacts.Fact fact = serviceFacts.get(0);
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, fact.certainty());
    assertTrue(fact.evidence().stream().anyMatch(item ->
            item.kind().equals("service_loader_descriptor")
                && "meta_inf_services".equals(item.attributes().get("descriptorKind"))),
        fact.evidence().toString());
    assertTrue(fact.evidence().stream().noneMatch(item ->
            item.kind().equals("service_loader_scan_diagnostic")),
        fact.evidence().toString());
    }
  }

  @Test
  void extractionFailureFailsClosedWithoutResidue() throws IOException {
    synchronized (EXTRACTION_LOCK) {
    Path broken = temp.resolve("broken-sources.jar");
    Files.writeString(broken, "not a zip");
    AnalysisContext brokenContext = AnalysisContext.builder()
        .id("source-archive-adapter-broken")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("source-archive-adapter")
        .javaRelease(17)
        .repositoryRoot(temp)
        .applicationClasses(temp.resolve("classes"))
        .selectedProfile("production")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .inputMode(AnalysisContext.InputMode.SOURCE_ARCHIVE)
        .dependencyClasspathRequired(false)
        .sourceArchive(broken)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
    Files.createDirectories(temp.resolve("classes"));
    Set<String> before = lingeringExtractionRoots();

    assertThrows(IllegalArgumentException.class,
        () -> new JavaAnalysisAdapter().analyze(brokenContext));
    assertEquals(before, lingeringExtractionRoots());
    }
  }

  @Test
  void prebuiltGraphOverloadFailsClosedForSourceArchive() throws IOException {
    Fixture fixture = fixture("prebuilt-sources.jar");

    assertThrows(IllegalArgumentException.class,
        () -> new JavaAnalysisAdapter().analyze(context(fixture, null), AnalysisGraph.empty()));
  }

  @Test
  void repeatedRunsProduceIdenticalJsonWithoutLeakingTheExtractionRoot()
      throws IOException {
    synchronized (EXTRACTION_LOCK) {
    Fixture fixture = fixture("stable-sources.jar");
    Path binary = binaryDescriptorJar("stable-application-1.0.0.jar");

    Path firstOutput = temp.resolve("first.json");
    Path secondOutput = temp.resolve("second.json");
    SemanticCallFactsJsonWriter.write(
        new JavaAnalysisAdapter().analyze(context(fixture, binary)), firstOutput);
    SemanticCallFactsJsonWriter.write(
        new JavaAnalysisAdapter().analyze(context(fixture, binary)), secondOutput);

    assertEquals(Files.readString(firstOutput), Files.readString(secondOutput));
    assertTrue(!Files.readString(firstOutput).contains("flpath-42-sources-"),
        "the extraction root leaked into the output");
    }
  }
}
