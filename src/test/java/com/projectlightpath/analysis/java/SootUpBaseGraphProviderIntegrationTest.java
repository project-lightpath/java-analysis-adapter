package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
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

  /** Covers compiled call-site binding, explicit loaders, wildcard inputs, and bad metadata. */
  @Test
  void analyzesCompiledServiceLoaderEdgesWithSelectedWildcardMetadata() throws IOException {
    Path dependencySource = temp.resolve("service-dependency/src/main/java");
    Path dependencyClasses = temp.resolve("service-dependency/target/classes");
    write(dependencySource, "demo/AuditProvider.java", """
        package demo;
        public interface AuditProvider { String record(); }
        """);
    write(dependencySource, "demo/OtherProvider.java", """
        package demo;
        public interface OtherProvider { String record(); }
        """);
    write(dependencySource, "demo/DefaultAuditProvider.java", """
        package demo;
        public final class DefaultAuditProvider implements AuditProvider {
          public String record() { return "audit"; }
        }
        """);
    write(dependencySource, "demo/DefaultOtherProvider.java", """
        package demo;
        public final class DefaultOtherProvider implements OtherProvider {
          public String record() { return "other"; }
        }
        """);
    compile(dependencySource, dependencyClasses, List.of());
    Path dependencyJar = jar(
        dependencyClasses,
        temp.resolve("service-dependency/provider.jar"),
        Map.of(
            "META-INF/services/demo.AuditProvider",
            "demo.DefaultAuditProvider\nnot a binary provider name\n",
            "META-INF/services/demo.OtherProvider",
            "demo.DefaultOtherProvider\n",
            "META-INF/services/demo.Malformed",
            "not a binary provider name\n",
            "module-info.class",
            "not a valid module descriptor"));

    Path applicationSource = temp.resolve("service-application/src/main/java");
    Path applicationClasses = temp.resolve("service-application/target/classes");
    write(applicationSource, "demo/App.java", """
        package demo;
        import java.util.ServiceLoader;
        public final class App {
          public String audit() {
            return ServiceLoader.load(AuditProvider.class).findFirst()
                .map(provider -> provider.record()).orElse("");
          }
          public String other() {
            return ServiceLoader.load(OtherProvider.class).findFirst()
                .map(provider -> provider.record()).orElse("");
          }
          public String auditWithLoader(ClassLoader loader) {
            return ServiceLoader.load(AuditProvider.class , loader).findFirst()
                .map(provider -> provider.record()).orElse("");
          }
          public String unrelated(AuditProvider provider) {
            ServiceLoader.load(AuditProvider.class); return ((java.util.function.Supplier<String>) () -> new DefaultAuditProvider().record()).get();
          }
          public String unrelatedSameServiceType(AuditProvider provider) {
            return consume(ServiceLoader.load(AuditProvider.class), provider.record());
          }
          public String unrelatedSameStatement() {
            return ServiceLoader.load(OtherProvider.class).findFirst()
                .map(provider -> new DefaultOtherProvider().record()).orElse("");
          }
          private static String consume(Object ignored, String value) { return value; }
        }
        """);
    write(applicationSource, "demo/ServiceLoader.java", """
        package demo;
        public final class ServiceLoader {
          public static <S> void load(Class<S> service) {}
        }
        """);
    write(applicationSource, "demo/CustomApp.java", """
        package demo;
        import java.util.*;
        public final class CustomApp {
          public void load() { ServiceLoader.load(AuditProvider.class); }
        }
        """);
    write(applicationSource, "demo/CustomLoader.java", """
        package demo;
        public final class CustomLoader {
          public <S> void load(Class<S> service) {}
        }
        """);
    write(applicationSource, "demo/ShadowApp.java", """
        package demo;
        import java.util.ServiceLoader;
        public final class ShadowApp {
          public void load(CustomLoader ServiceLoader) {
            ServiceLoader.load(AuditProvider.class);
          }
        }
        """);
    compile(applicationSource, applicationClasses, List.of(dependencyJar));

    Path wildcardDirectory = temp.resolve("service-application/dependencies");
    Files.createDirectories(wildcardDirectory);
    Path selectedJar = wildcardDirectory.resolve("01-provider.jar");
    Files.copy(dependencyJar, selectedJar);
    AnalysisContext context = AnalysisContext.builder()
        .id("service-loader-production")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("sootup-service-loader-integration")
        .javaRelease(17)
        .repositoryRoot(temp)
        .applicationClasses(applicationClasses)
        .selectedProfile("production")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", applicationSource, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspath(List.of(wildcardDirectory.resolve("*")))
        .dependencyClasspathRequired(true)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();

    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(context);
    SemanticCallFacts.Fact audit = concreteServiceFact(facts, "lambda$audit$", "demo.DefaultAuditProvider");
    SemanticCallFacts.Fact other = concreteServiceFact(facts, "lambda$other$", "demo.DefaultOtherProvider");
    SemanticCallFacts.Fact explicit = concreteServiceFact(
        facts, "lambda$auditWithLoader$", "demo.DefaultAuditProvider");
    SemanticCallFacts.Fact unrelated = facts.providerFacts().stream()
        .filter(fact -> fact.caller().contains("lambda$unrelated$"))
        .filter(fact -> fact.callee().equals("demo.DefaultAuditProvider#record"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.providerFacts().toString()));
     SemanticCallFacts.Fact unrelatedSameStatement = facts.providerFacts().stream()
         .filter(fact -> fact.caller().contains("lambda$unrelatedSameStatement$"))
         .filter(fact -> fact.callee().equals("demo.DefaultOtherProvider#record"))
         .findFirst()
         .orElseThrow(() -> new AssertionError(facts.providerFacts().toString()));
     SemanticCallFacts.Fact unrelatedSameServiceType = facts.providerFacts().stream()
         .filter(fact -> fact.caller().equals("demo.App#unrelatedSameServiceType"))
         .filter(fact -> fact.callee().equals("demo.DefaultAuditProvider#record"))
         .findFirst()
         .orElseThrow(() -> new AssertionError(facts.providerFacts().toString()));
    SemanticCallFacts.Fact customLoader = facts.providerFacts().stream()
        .filter(fact -> fact.caller().equals("demo.CustomApp#load"))
        .filter(fact -> fact.callee().equals("demo.ServiceLoader#load"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.providerFacts().toString()));
    SemanticCallFacts.Fact shadowLoader = facts.providerFacts().stream()
        .filter(fact -> fact.caller().equals("demo.ShadowApp#load"))
        .filter(fact -> fact.callee().equals("demo.CustomLoader#load"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.providerFacts().toString()));

    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, audit.certainty());
    assertEquals("demo.AuditProvider", descriptorServiceType(audit));
    assertEquals("demo.DefaultAuditProvider", descriptorProviders(audit));
    assertEquals(selectedJar.toAbsolutePath().normalize().toString(), descriptorPath(audit).split("!/", 2)[0]);
     assertEquals("malformed", resolution(audit).attributes().get("descriptorStatus"));
     assertTrue(audit.evidence().stream()
         .anyMatch(evidence -> evidence.kind().equals("service_loader_scan_diagnostic")
             && evidence.attributes().get("diagnosticCode").equals("malformed_module_info")));
    assertEquals("not a binary provider name",
        audit.evidence().stream()
            .filter(evidence -> evidence.kind().equals("service_loader_descriptor"))
            .findFirst()
            .orElseThrow()
            .attributes()
            .get("malformedProviderEntries"));
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, other.certainty());
    assertEquals("demo.OtherProvider", descriptorServiceType(other));
    assertEquals("demo.DefaultOtherProvider", descriptorProviders(other));
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, explicit.certainty());
    assertEquals("explicit", resolution(explicit).attributes().get("classLoaderSelection"));
    assertTrue(explicit.limitation().contains("malformed provider metadata"), explicit.toString());
    assertTrue(!unrelated.relationshipType().equals("service_loader"), unrelated.toString());
     assertEquals("callback", unrelatedSameStatement.relationshipType(), unrelatedSameStatement.toString());
     assertEquals(SemanticCallFacts.Certainty.POSSIBLE, unrelatedSameStatement.certainty());
     assertEquals("interface_dispatch", unrelatedSameServiceType.relationshipType(),
         unrelatedSameServiceType.toString());
     assertNotEquals(SemanticCallFacts.Certainty.DEFINITE, unrelatedSameServiceType.certainty());
    assertEquals("invokes", customLoader.relationshipType());
    assertEquals("invokes", shadowLoader.relationshipType());
  }

  private static SemanticCallFacts.Fact concreteServiceFact(
      SemanticCallFacts facts, String callerFragment, String callee) {
    return facts.providerFacts().stream()
        .filter(fact -> fact.relationshipType().equals("service_loader"))
        .filter(fact -> fact.caller().contains(callerFragment))
        .filter(fact -> fact.callee().equals(callee + "#record"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.providerFacts().toString()));
  }

  private static String descriptorServiceType(SemanticCallFacts.Fact fact) {
    return fact.evidence().stream()
        .filter(evidence -> evidence.kind().equals("service_loader_descriptor"))
        .findFirst()
        .orElseThrow()
        .attributes()
        .get("serviceType");
  }

  private static String descriptorProviders(SemanticCallFacts.Fact fact) {
    return fact.evidence().stream()
        .filter(evidence -> evidence.kind().equals("service_loader_descriptor"))
        .findFirst()
        .orElseThrow()
        .attributes()
        .get("providers");
  }

  private static String descriptorPath(SemanticCallFacts.Fact fact) {
    return fact.evidence().stream()
        .filter(evidence -> evidence.kind().equals("service_loader_descriptor"))
        .findFirst()
        .orElseThrow()
        .path();
  }

  private static SemanticCallFacts.Evidence resolution(SemanticCallFacts.Fact fact) {
    return fact.evidence().stream()
        .filter(evidence -> evidence.kind().equals("service_loader_resolution"))
        .findFirst()
        .orElseThrow();
  }

  private static Path jar(Path classes, Path jar, Map<String, String> resources) throws IOException {
    Files.createDirectories(jar.getParent());
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
        var paths = Files.walk(classes)) {
      paths.filter(path -> path.toString().endsWith(".class"))
          .sorted()
          .forEach(path -> writeJarEntry(output, classes.relativize(path).toString(),
              readBytes(path)));
      resources.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(entry -> writeJarEntry(
              output, entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8)));
    }
    return jar;
  }

  private static void writeJarEntry(JarOutputStream output, String name, byte[] bytes) {
    try {
      output.putNextEntry(new JarEntry(name.replace('\\', '/')));
      output.write(bytes);
      output.closeEntry();
    } catch (IOException error) {
      throw new IllegalStateException("cannot write integration JAR entry " + name, error);
    }
  }

  private static byte[] readBytes(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException error) {
      throw new IllegalStateException("cannot read integration fixture " + path, error);
    }
  }

  private static void compile(Path sourceRoot, Path classes) throws IOException {
    compile(sourceRoot, classes, List.of());
  }

  private static void compile(Path sourceRoot, Path classes, List<Path> classpath) throws IOException {
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
      List<String> options = new ArrayList<>(List.of(
          "--release", "17", "-d", classes.toString()));
      if (!classpath.isEmpty()) {
        options.add("--class-path");
        options.add(classpath.stream()
            .map(Path::toString)
            .collect(Collectors.joining(File.pathSeparator)));
      }
      boolean compiled = compiler.getTask(
          null,
          fileManager,
          null,
          options,
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
