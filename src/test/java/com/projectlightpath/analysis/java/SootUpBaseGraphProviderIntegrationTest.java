package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SootUpBaseGraphProviderIntegrationTest {
  @TempDir Path temp;

  @Test
  void analyzesCompiledApplicationWithNormalizedDescriptorsAndPossibleInterfaceDispatch()
      throws IOException {
    Path sourceRoot = temp.resolve("src/main/java");
    Path classes = temp.resolve("target/classes");
    write(sourceRoot, "demo/Service.java", """
        package demo;
        public interface Service {
          String run(String value);
        }
        """);
    write(sourceRoot, "demo/Impl.java", """
        package demo;
        public class Impl implements Service {
          public String run(String value) { return value; }
        }
        """);
    write(sourceRoot, "demo/App.java", """
        package demo;
        public class App {
          private final Impl field = new Impl();
          public String interfaceCall(Service service) { return service.run("x"); }
          public String directCall(Impl implementation) { return implementation.run("x"); }
          public String directFieldCall() { return field.run("x"); }
        }
        """);
    compile(sourceRoot, classes);

    AnalysisContext context = AnalysisContext.builder()
        .id("quarkus-production")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("sootup-integration")
        .javaRelease(17)
        .repositoryRoot(temp)
        .applicationClasses(classes)
        .selectedProfile("production")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();

    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(context);

    SemanticCallFacts.Fact interfaceDispatch = facts.providerFacts().stream()
        .filter(fact -> fact.caller().equals("demo.App#interfaceCall"))
        .filter(fact -> fact.relationshipType().equals("interface_dispatch"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.providerFacts().toString()));
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, interfaceDispatch.certainty());
    assertEquals("(Ljava/lang/String;)Ljava/lang/String;",
        interfaceDispatch.evidence().get(0).attributes().get("calleeSignature"));

    SemanticCallFacts.Fact directCall = facts.providerFacts().stream()
        .filter(fact -> fact.caller().equals("demo.App#directCall"))
        .filter(fact -> fact.relationshipType().equals("invokes"))
        .filter(fact -> fact.callee().endsWith("#run"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.providerFacts().toString()));
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, directCall.certainty());
    assertEquals("(Ljava/lang/String;)Ljava/lang/String;",
        directCall.evidence().get(0).attributes().get("calleeSignature"));
    SemanticCallFacts.Fact directFieldCall = facts.providerFacts().stream()
        .filter(fact -> fact.caller().equals("demo.App#directFieldCall"))
        .filter(fact -> fact.relationshipType().equals("invokes"))
        .filter(fact -> fact.callee().endsWith("#run"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.providerFacts().toString()));
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, directFieldCall.certainty());
    assertTrue(facts.providerFacts().stream().allMatch(fact ->
        fact.evidence().get(0).attributes().get("calleeSignature").startsWith("(")));
  }

  private static void compile(Path sourceRoot, Path classes) throws IOException {
    Files.createDirectories(classes);
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("a JDK compiler is required for the SootUp integration test");
    }
    try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
      List<Path> sources = Files.walk(sourceRoot)
          .filter(path -> path.toString().endsWith(".java"))
          .sorted()
          .toList();
      var units = fileManager.getJavaFileObjectsFromFiles(
          sources.stream().map(Path::toFile).toList());
      boolean compiled = compiler.getTask(
          null,
          fileManager,
          null,
          List.of("--release", "17", "-d", classes.toString()),
          null,
          units).call();
      assertTrue(compiled, "integration fixture compilation failed");
    }
  }

  private static void write(Path root, String relative, String source) throws IOException {
    Path path = root.resolve(relative);
    Files.createDirectories(path.getParent());
    Files.writeString(path, source);
  }
}
