package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end ServiceLoader facts for the javac-backed production seam.
 *
 * <p>The lane runs with {@code flpath.java.resolver=javac} and asserts the
 * externally visible service_loader facts, so a regression in site discovery,
 * source correlation, or descriptor promotion fails here before it reaches
 * callers.
 */
class ServiceLoaderEndToEndTest {
  private static final String RESOLVER_PROPERTY = "flpath.java.resolver";
  private static final String EXPECTED_PROVIDER = "demo.DefaultProvider#record";

  @TempDir Path temp;
  private String previousResolver;

  @BeforeEach
  void enableJavacResolver() {
    previousResolver = System.getProperty(RESOLVER_PROPERTY);
    System.setProperty(RESOLVER_PROPERTY, "javac");
  }

  @AfterEach
  void restoreResolver() {
    if (previousResolver == null) {
      System.clearProperty(RESOLVER_PROPERTY);
    } else {
      System.setProperty(RESOLVER_PROPERTY, previousResolver);
    }
  }

  @Test
  void emitsTheProviderCallbackForAValidWildcardImport() throws IOException {
    assertServiceFacts("valid-wildcard", Set.of(EXPECTED_PROVIDER));
  }

  @Test
  void resolvesTheOwnerOfAStaticOnDemandImport() throws IOException {
    assertServiceFacts("static-import-owner", Set.of(EXPECTED_PROVIDER));
  }

  @Test
  void doesNotAttributeASamePackageLookalike() throws IOException {
    assertServiceFacts("shadowed-same-package", Set.of());
  }

  private void assertServiceFacts(String fixture, Set<String> expectedCallees) throws IOException {
    Path fixtureRoot = temp.resolve(fixture);
    copyFixture(fixture, fixtureRoot);
    Path sourceRoot = fixtureRoot.resolve("src/main/java");
    Path classes = fixtureRoot.resolve("target/classes");
    compileFixture(sourceRoot, classes);

    AnalysisContext context = AnalysisContext.builder()
        .id("service-loader-e2e-" + fixture)
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("service-loader-e2e")
        .javaRelease(17)
        .repositoryRoot(fixtureRoot)
        .applicationClasses(classes)
        .selectedProfile("production")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
            "service-loader-resources",
            "service_loader_descriptor",
            AnalysisContext.MetadataStatus.OBSERVED,
            fixture + "/src/main/resources/META-INF/services"))
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();

    List<SemanticCallFacts.Fact> serviceFacts =
        new JavaAnalysisAdapter().analyze(context).providerFacts().stream()
            .filter(fact -> fact.relationshipType().equals("service_loader"))
            .toList();
    Set<String> callees = serviceFacts.stream()
        .map(SemanticCallFacts.Fact::callee)
        .collect(Collectors.toSet());
    assertEquals(expectedCallees, callees, serviceFacts.toString());
    assertTrue(serviceFacts.stream()
        .allMatch(fact -> fact.certainty() == SemanticCallFacts.Certainty.DEFINITE),
        serviceFacts.toString());
    assertTrue(serviceFacts.stream()
        .allMatch(fact -> fact.status() == SemanticCallFacts.Status.EMITTED),
        serviceFacts.toString());
    assertFalse(serviceFacts.stream().anyMatch(fact -> fact.evidence().isEmpty()),
        serviceFacts.toString());
  }

  private void copyFixture(String fixture, Path destination) throws IOException {
    URL resource = getClass().getResource("/e2e/service-loader/" + fixture);
    assertFalse(resource == null, "missing e2e fixture: " + fixture);
    try (Stream<Path> paths = Files.walk(Path.of(resource.toURI()))) {
      for (Path source : paths.toList()) {
        Path target = destination.resolve(Path.of(resource.toURI()).relativize(source).toString());
        if (Files.isDirectory(source)) {
          Files.createDirectories(target);
        } else {
          Files.createDirectories(target.getParent());
          Files.copy(source, target);
        }
      }
    } catch (URISyntaxException error) {
      throw new IOException("cannot locate e2e fixture " + fixture, error);
    }
  }

  private static void compileFixture(Path sourceRoot, Path classes) throws IOException {
    Files.createDirectories(classes);
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("a JDK compiler is required for the e2e fixture");
    }
    try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, null)) {
      List<Path> sources;
      try (Stream<Path> paths = Files.walk(sourceRoot)) {
        sources = paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
      }
      boolean compiled = compiler.getTask(
          null,
          manager,
          null,
          List.of("--release", "17", "-d", classes.toString()),
          null,
          manager.getJavaFileObjectsFromPaths(sources)).call();
      assertTrue(compiled, "e2e fixture compilation failed: " + sourceRoot);
    }
  }
}
