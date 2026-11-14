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
          static String staticRun(String value) { return value; }
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
          public String interfaceStaticCall() { return Service.staticRun("x"); }
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
    SemanticCallFacts.Fact interfaceStaticCall = facts.providerFacts().stream()
        .filter(fact -> fact.caller().equals("demo.App#interfaceStaticCall"))
        .filter(fact -> fact.callee().equals("demo.Service#staticRun"))
        .filter(fact -> fact.relationshipType().equals("invokes"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.providerFacts().toString()));
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, interfaceStaticCall.certainty());
    assertEquals("(Ljava/lang/String;)Ljava/lang/String;",
        interfaceStaticCall.evidence().get(0).attributes().get("calleeSignature"));
    assertTrue(facts.providerFacts().stream().allMatch(fact ->
        fact.evidence().get(0).attributes().get("calleeSignature").startsWith("(")));
  }

  /** Keeps every provider edge visible when one source owns many ServiceLoader sites. */
  @Test
  void classifiesMultipleProviderEdges() throws IOException {
    Path sourceRoot = temp.resolve("service-loader-many/src/main/java");
    Path classes = temp.resolve("service-loader-many/target/classes");
    Path resources = temp.resolve("service-loader-many/resources");
    StringBuilder methods = new StringBuilder();
    for (int index = 0; index < 128; index++) {
      methods.append("  public String call").append(index).append("() {\n")
          .append("    return ServiceLoader.load(Provider.class).findFirst()\n")
          .append("        .map(value -> value.record()).orElse(\"\");\n")
          .append("  }\n");
    }
    write(sourceRoot, "demo/App.java", "package demo;\n"
        + "import java.util.ServiceLoader;\n"
        + "public final class App {\n"
        + methods
        + "}\n");
    write(sourceRoot, "demo/Provider.java", "package demo;\n"
        + "interface Provider { String record(); }\n");
    write(sourceRoot, "demo/DefaultProvider.java", "package demo;\n"
        + "final class DefaultProvider implements Provider {\n"
        + "  public String record() { return \"provider\"; }\n"
        + "}\n");
    write(resources, "META-INF/services/demo.Provider", "demo.DefaultProvider\n");
    compile(sourceRoot, classes);

    AnalysisContext context = AnalysisContext.builder()
        .id("service-loader-many")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("java21-quarkus")
        .javaRelease(17)
        .repositoryRoot(temp)
        .applicationClasses(classes)
        .selectedProfile("production")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
            "service-loader-resources",
            "service_loader_descriptor",
            AnalysisContext.MetadataStatus.OBSERVED,
            "service-loader-many/resources/META-INF/services"))
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();

    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(context);
    List<SemanticCallFacts.Fact> serviceFacts = facts.providerFacts().stream()
        .filter(fact -> fact.relationshipType().equals("service_loader"))
        .toList();

    assertEquals(128, serviceFacts.size(), facts.providerFacts().toString());
    assertEquals(128, serviceFacts.stream().map(SemanticCallFacts.Fact::caller).distinct().count());
    assertTrue(serviceFacts.stream().allMatch(fact ->
        fact.callee().equals("demo.DefaultProvider#record")
            && fact.evidence().stream().anyMatch(evidence ->
                evidence.kind().equals("service_loader_descriptor")
                    && evidence.attributes().get("serviceType").equals("demo.Provider"))));
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
