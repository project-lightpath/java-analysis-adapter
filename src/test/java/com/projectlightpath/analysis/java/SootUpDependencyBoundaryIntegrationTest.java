package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SootUpDependencyBoundaryIntegrationTest {
  @TempDir Path temp;

  @Test
  void retainsExactApplicationToDependencyFactsAndPreservesControls() throws IOException {
    Fixture fixture = fixture();
    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(fixture.context());

    SemanticCallFacts.Fact vulnerable = fact(facts, "app.Application#dependencyCall");
    assertEquals("vulnerable.api.VulnerableApi#check", vulnerable.callee());
    assertEquals("invokes", vulnerable.relationshipType());
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, vulnerable.certainty());
    assertEquals(SemanticCallFacts.Status.EMITTED, vulnerable.status());
    assertEquals(SemanticCallFacts.FactSet.PROVIDER, vulnerable.factSet());
    assertEquals(SemanticCallFacts.EvidenceOrigin.SOOTUP_BASE_GRAPH, vulnerable.evidenceOrigin());
    assertEquals(fixture.context().id(), vulnerable.contextId());
    assertEquals(fixture.context().provenance(), facts.runMetadata().provenance());
    assertEquals(fixture.context().handoff(), facts.runMetadata().handoff());

    SemanticCallFacts.Evidence vulnerableEvidence = vulnerable.evidence().get(0);
    Map<String, String> vulnerableAttributes = vulnerableEvidence.attributes();
    assertEquals("vulnerable.api.VulnerableApi", vulnerableAttributes.get("calleeOwner"));
    assertEquals("check", vulnerableAttributes.get("calleeMethod"));
    assertEquals("(Ljava/lang/String;)Z", vulnerableAttributes.get("calleeSignature"));
    assertEquals("dependency", vulnerableAttributes.get("targetOrigin"));
    assertEquals(fixture.vulnerableJar().toAbsolutePath().normalize().toString(),
        vulnerableAttributes.get("dependencyClasspathEntry"));
    assertTrue(vulnerableAttributes.containsKey("targetBytecodePath"));
    assertFalse(vulnerableAttributes.get("targetBytecodePath").endsWith(".java"));
    assertTrue(vulnerableEvidence.path().endsWith("src/main/java/app/Application.java"));
    assertNotNull(vulnerableEvidence.line());
    assertTrue(vulnerableEvidence.line() > 0);

    SemanticCallFacts.Fact body = fact(facts, "app.Application#dependencyBodyCall");
    assertEquals("vulnerable.api.VulnerableApi#normalize", body.callee());
    assertEquals("(Ljava/lang/Object;)Ljava/lang/String;",
        body.evidence().get(0).attributes().get("calleeSignature"));

    assertFalse(facts.providerFacts().stream()
        .anyMatch(item -> item.caller().equals("app.Application#dependencyCall")
            && item.callee().equals("vulnerable.api.VulnerableApi#check")
            && item.evidence().stream().anyMatch(evidence ->
                evidence.attributes().get("calleeSignature").equals("(I)Z"))));

    SemanticCallFacts.Fact duplicate = fact(facts, "app.Application#duplicateCall");
    assertEquals("vulnerable.api.Duplicate#origin", duplicate.callee());
    assertEquals(fixture.vulnerableJar().toAbsolutePath().normalize().toString(),
        duplicate.evidence().get(0).attributes().get("dependencyClasspathEntry"));

    SemanticCallFacts.Fact peer = fact(facts, "app.Application#peerCall");
    assertEquals("peer.api.PeerApi#value", peer.callee());
    assertEquals(fixture.peerJar().toAbsolutePath().normalize().toString(),
        peer.evidence().get(0).attributes().get("dependencyClasspathEntry"));

    SemanticCallFacts.Fact application = facts.providerFacts().stream()
        .filter(item -> item.caller().equals("app.Application#applicationCall"))
        .filter(item -> item.callee().equals("app.Local#value"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.providerFacts().toString()));
    assertEquals("()Ljava/lang/String;",
        application.evidence().get(0).attributes().get("callerSignature"));
    assertFalse(application.evidence().get(0).attributes().containsKey("targetOrigin"));
    assertFalse(application.evidence().get(0).attributes().containsKey("dependencyClasspathEntry"));
    SemanticCallFacts.Evidence applicationEvidence = application.evidence().get(0);
    assertEquals("sootup_call", applicationEvidence.kind());
    assertEquals("src/main/java/app/Application.java", applicationEvidence.path());
    assertTrue(applicationEvidence.symbols().isEmpty());
    assertNotNull(applicationEvidence.line());
    assertTrue(applicationEvidence.line() > 0);
    assertEquals(Map.of(
        "basis", "SootUp CHA application call",
        "caller", "app.Application#applicationCall",
        "callee", "app.Local#value",
        "callerSignature", "()Ljava/lang/String;",
        "calleeSignature", "()Ljava/lang/String;"), applicationEvidence.attributes());

    assertFalse(facts.providerFacts().stream().anyMatch(item -> item.callee().startsWith("java.")));
    assertTrue(facts.providerFacts().stream()
        .allMatch(item -> item.caller().startsWith("app.")));
  }

  @Test
  void retainsPossibleDependencyDispatchWithoutPromotingCertainty() throws IOException {
    Fixture fixture = fixture();
    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(fixture.context());

    SemanticCallFacts.Fact dispatch = facts.providerFacts().stream()
        .filter(item -> item.caller().equals("app.Application#dependencyInterfaceCall"))
        .filter(item -> item.callee().startsWith("vulnerable.api."))
        .filter(item -> item.relationshipType().equals("interface_dispatch"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.providerFacts().toString()));
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, dispatch.certainty());
    assertEquals(SemanticCallFacts.Status.EMITTED, dispatch.status());
    assertEquals("dependency", dispatch.evidence().get(0).attributes().get("targetOrigin"));
    assertEquals(fixture.vulnerableJar().toAbsolutePath().normalize().toString(),
        dispatch.evidence().get(0).attributes().get("dependencyClasspathEntry"));
    assertFalse(facts.providerFacts().stream().anyMatch(item ->
        item.certainty() == SemanticCallFacts.Certainty.UNRESOLVED
            && item.status() == SemanticCallFacts.Status.EMITTED));
  }

  @Test
  void retainsPossibleVirtualDependencyDispatchWithoutPromotingCertainty() throws IOException {
    Fixture fixture = fixture();
    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(fixture.context());

    List<SemanticCallFacts.Fact> dispatches = facts.providerFacts().stream()
        .filter(item -> item.caller().equals("app.Application#dependencyVirtualCall"))
        .filter(item -> item.callee().endsWith("#value"))
        .filter(item -> item.relationshipType().equals("invokes"))
        .toList();
    assertFalse(dispatches.isEmpty(), facts.providerFacts().toString());
    assertTrue(dispatches.stream().allMatch(item ->
        item.certainty() == SemanticCallFacts.Certainty.POSSIBLE));
    assertTrue(dispatches.stream().allMatch(item ->
        fixture.vulnerableJar().toAbsolutePath().normalize().toString().equals(
            item.evidence().get(0).attributes().get("dependencyClasspathEntry"))));
  }

  @Test
  void keepsFinalVirtualDependencyTargetsDefinite() throws IOException {
    Fixture fixture = fixture();
    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(fixture.context());

    assertFinalVirtualCallIsDefinite(facts, "dependencyFinalSubclassCall");
    assertFinalVirtualCallIsDefinite(facts, "dependencyFinalOverrideCall");
    assertFinalVirtualCallIsDefinite(facts, "dependencyInheritedFinalCall");
  }

  @Test
  void preservesExistingInvalidDependencyClasspathBehavior() throws IOException {
    Fixture fixture = fixture();
    Path missing = temp.resolve("missing-dependency.jar");
    AnalysisContext mixedContext = contextWithDependencies(fixture, List.of(fixture.vulnerableJar(), missing));
    SemanticCallFacts mixedFacts = new JavaAnalysisAdapter().analyze(mixedContext);
    assertEquals("dependency", fact(mixedFacts, "app.Application#dependencyCall")
        .evidence().get(0).attributes().get("targetOrigin"));

    AnalysisContext allInvalidContext = contextWithDependencies(fixture, List.of(missing));
    assertThrows(IllegalArgumentException.class,
        () -> new SootUpBaseGraphProvider().analyze(allInvalidContext));
  }

  @Test
  void deduplicatesRepeatedDependencyClasspathEntries() throws IOException {
    Fixture fixture = fixture();
    AnalysisContext context = contextWithDependencies(
        fixture, List.of(fixture.vulnerableJar(), fixture.vulnerableJar(), fixture.peerJar()));

    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(context);

    List<SemanticCallFacts.Fact> duplicateFacts = facts.providerFacts().stream()
        .filter(item -> item.caller().equals("app.Application#duplicateCall"))
        .filter(item -> item.callee().equals("vulnerable.api.Duplicate#origin"))
        .toList();
    assertEquals(1, duplicateFacts.size());
    assertEquals(fixture.vulnerableJar().toAbsolutePath().normalize().toString(),
        duplicateFacts.get(0).evidence().get(0).attributes().get("dependencyClasspathEntry"));
  }

  @Test
  void expandsTrailingDependencyClasspathWildcards() throws IOException {
    Fixture fixture = fixture();
    Path wildcardDirectory = temp.resolve("wildcard-dependencies");
    Files.createDirectories(wildcardDirectory);
    Path vulnerableJar = wildcardDirectory.resolve("01-vulnerable.jar");
    Path peerJar = wildcardDirectory.resolve("02-peer.JAR");
    Files.copy(fixture.vulnerableJar(), vulnerableJar);
    Files.copy(fixture.peerJar(), peerJar);

    AnalysisContext context = contextWithDependencies(
        fixture, List.of(wildcardDirectory.resolve("*")));
    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(context);

    SemanticCallFacts.Fact vulnerable = fact(facts, "app.Application#dependencyCall");
    assertEquals(vulnerableJar.toAbsolutePath().normalize().toString(),
        vulnerable.evidence().get(0).attributes().get("dependencyClasspathEntry"));
    SemanticCallFacts.Fact peer = fact(facts, "app.Application#peerCall");
    assertEquals(peerJar.toAbsolutePath().normalize().toString(),
        peer.evidence().get(0).attributes().get("dependencyClasspathEntry"));
  }

  @Test
  void attributesWarContainedClassesToTheOuterDependencyEntry() throws IOException {
    Fixture fixture = fixture();
    Path warSource = temp.resolve("war/src/main/java");
    Path warClasses = temp.resolve("war/target/classes");
    write(warSource, "war/api/WarApi.java", """
        package war.api;
        public class WarApi {
          public static String value() { return "war"; }
        }
        """);
    compile(warSource, warClasses, List.of());
    Path war = war(warClasses, fixture.vulnerableJar(), temp.resolve("application.war"));

    Path applicationSource = temp.resolve("war-application/src/main/java");
    Path applicationClasses = temp.resolve("war-application/target/classes");
    write(applicationSource, "app/WarApplication.java", """
        package app;
        import vulnerable.api.VulnerableApi;
        import war.api.WarApi;
        public class WarApplication {
          public String webClassesCall() { return WarApi.value(); }
          public boolean nestedJarCall(String value) { return VulnerableApi.check(value); }
        }
        """);
    compile(applicationSource, applicationClasses, List.of(warClasses, fixture.vulnerableJar()));
    AnalysisContext context = contextFor(
        applicationSource, applicationClasses, List.of(war));

    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(context);
    String expectedWar = war.toAbsolutePath().normalize().toString();
    SemanticCallFacts.Fact webClasses = fact(facts, "app.WarApplication#webClassesCall");
    assertEquals("war.api.WarApi#value", webClasses.callee());
    assertEquals(expectedWar,
        webClasses.evidence().get(0).attributes().get("dependencyClasspathEntry"));
    SemanticCallFacts.Fact nestedJar = fact(facts, "app.WarApplication#nestedJarCall");
    assertEquals("vulnerable.api.VulnerableApi#check", nestedJar.callee());
    assertEquals(expectedWar,
        nestedJar.evidence().get(0).attributes().get("dependencyClasspathEntry"));
  }

  @Test
  void skipsDependencyMethodsMissingFromTheResolvedArtifact() throws IOException {
    Path originalDependencySource = temp.resolve("phantom/original/src/main/java");
    Path originalDependencyClasses = temp.resolve("phantom/original/target/classes");
    write(originalDependencySource, "phantom/api/PhantomApi.java", """
        package phantom.api;
        public final class PhantomApi {
          public static String removed() { return "original"; }
        }
        """);
    compile(originalDependencySource, originalDependencyClasses, List.of());
    Path dependencyJar = jar(
        originalDependencyClasses, temp.resolve("phantom-dependency-1.0.jar"));

    Path applicationSource = temp.resolve("phantom/application/src/main/java");
    Path applicationClasses = temp.resolve("phantom/application/target/classes");
    write(applicationSource, "phantom/App.java", """
        package phantom;
        import phantom.api.PhantomApi;
        public class App {
          public String call() { return PhantomApi.removed(); }
        }
        """);
    compile(applicationSource, applicationClasses, List.of(dependencyJar));

    Path replacementDependencySource = temp.resolve("phantom/replacement/src/main/java");
    Path replacementDependencyClasses = temp.resolve("phantom/replacement/target/classes");
    write(replacementDependencySource, "phantom/api/PhantomApi.java", """
        package phantom.api;
        public final class PhantomApi {
          public static String present() { return "replacement"; }
        }
        """);
    compile(replacementDependencySource, replacementDependencyClasses, List.of());
    jar(replacementDependencyClasses, dependencyJar);

    AnalysisContext context = AnalysisContext.builder()
        .id("phantom-dependency")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("sootup-phantom-dependency")
        .javaRelease(17)
        .repositoryRoot(temp)
        .applicationClasses(applicationClasses)
        .selectedProfile("production")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", applicationSource, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspath(List.of(dependencyJar))
        .dependencyClasspathRequired(true)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();

    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(context);
    assertFalse(facts.providerFacts().stream()
        .anyMatch(fact -> fact.caller().equals("phantom.App#call")));
  }

  private Fixture fixture() throws IOException {
    Path dependencySource = temp.resolve("dependency/src/main/java");
    Path dependencyClasses = temp.resolve("dependency/target/classes");
    write(dependencySource, "vulnerable/api/VulnerableApi.java", """
        package vulnerable.api;
        public final class VulnerableApi {
          public static boolean check(String value) { return value != null; }
          public static boolean check(int value) { return value > 0; }
          public static String normalize(Object value) {
            if (!(value instanceof String)) { return ""; }
            String text = (String) value;
            try { return text.trim(); } catch (RuntimeException ignored) { return ""; }
          }
        }
        """);
    write(dependencySource, "vulnerable/api/BoundaryService.java", """
        package vulnerable.api;
        public interface BoundaryService { String execute(String value); }
        """);
    write(dependencySource, "vulnerable/api/BoundaryServiceImpl.java", """
        package vulnerable.api;
        public final class BoundaryServiceImpl implements BoundaryService {
          public String execute(String value) { return value; }
        }
        """);
    write(dependencySource, "vulnerable/api/VirtualApi.java", """
        package vulnerable.api;
        public class VirtualApi {
          public String value() { return "base"; }
        }
        """);
    write(dependencySource, "vulnerable/api/VirtualApiOverride.java", """
        package vulnerable.api;
        public class VirtualApiOverride extends VirtualApi {
          @Override public String value() { return "override"; }
        }
        """);
    write(dependencySource, "vulnerable/api/FinalVirtualApi.java", """
        package vulnerable.api;
        public final class FinalVirtualApi extends VirtualApi { }
        """);
    write(dependencySource, "vulnerable/api/FinalOverrideVirtualApi.java", """
        package vulnerable.api;
        public class FinalOverrideVirtualApi extends VirtualApi {
          @Override public final String value() { return "final-override"; }
        }
        """);
    write(dependencySource, "vulnerable/api/FinalMethodVirtualApi.java", """
        package vulnerable.api;
        public class FinalMethodVirtualApi extends VirtualApi {
          @Override public final String value() { return "final-method"; }
        }
        """);
    write(dependencySource, "vulnerable/api/InheritedFinalVirtualApi.java", """
        package vulnerable.api;
        public class InheritedFinalVirtualApi extends FinalMethodVirtualApi { }
        """);
    write(dependencySource, "vulnerable/api/Duplicate.java", """
        package vulnerable.api;
        public final class Duplicate {
          public static String origin() { return "vulnerable"; }
        }
        """);
    compile(dependencySource, dependencyClasses, List.of());
    Path vulnerableJar = jar(dependencyClasses, temp.resolve("vulnerable-dependency-1.0.jar"));

    Path peerSource = temp.resolve("peer/src/main/java");
    Path peerClasses = temp.resolve("peer/target/classes");
    write(peerSource, "peer/api/PeerApi.java", """
        package peer.api;
        public final class PeerApi {
          public static String value() { return "peer"; }
        }
        """);
    write(peerSource, "vulnerable/api/Duplicate.java", """
        package vulnerable.api;
        public final class Duplicate {
          public static String origin() { return "peer"; }
        }
        """);
    compile(peerSource, peerClasses, List.of());
    Path peerJar = jar(peerClasses, temp.resolve("peer-dependency-1.0.jar"));

    Path applicationSource = temp.resolve("application/src/main/java");
    Path applicationClasses = temp.resolve("application/target/classes");
    write(applicationSource, "app/Application.java", """
        package app;
        import peer.api.PeerApi;
        import vulnerable.api.BoundaryService;
        import vulnerable.api.Duplicate;
        import vulnerable.api.FinalOverrideVirtualApi;
        import vulnerable.api.FinalVirtualApi;
        import vulnerable.api.InheritedFinalVirtualApi;
        import vulnerable.api.VulnerableApi;
        import vulnerable.api.VirtualApi;
        public class Application {
          public boolean dependencyCall(String value) {
            return VulnerableApi.check(value);
          }
          public String dependencyBodyCall(Object value) {
            return VulnerableApi.normalize(value);
          }
          public String dependencyVirtualCall(VirtualApi value) {
            return value.value();
          }
          public String dependencyFinalSubclassCall(FinalVirtualApi value) {
            return value.value();
          }
          public String dependencyFinalOverrideCall(FinalOverrideVirtualApi value) {
            return value.value();
          }
          public String dependencyInheritedFinalCall(InheritedFinalVirtualApi value) {
            return value.value();
          }
          public String dependencyInterfaceCall(BoundaryService service) {
            return service.execute("value");
          }
          public String duplicateCall() {
            return Duplicate.origin();
          }
          public String peerCall() {
            return PeerApi.value();
          }
          public String applicationCall() {
            return new Local().value();
          }
          public int jdkCall(String value) {
            return value.length();
          }
        }
        """);
    write(applicationSource, "app/Local.java", """
        package app;
        public class Local {
          public String value() { return "application"; }
        }
        """);
    compile(applicationSource, applicationClasses, List.of(vulnerableJar, peerJar));

    AnalysisContext context = AnalysisContext.builder()
        .id("dependency-boundary")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("sootup-dependency-boundary")
        .javaRelease(17)
        .repositoryRoot(temp)
        .applicationClasses(applicationClasses)
        .selectedProfile("production")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", applicationSource, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspath(List.of(vulnerableJar, peerJar))
        .dependencyClasspathRequired(true)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
    return new Fixture(context, vulnerableJar, peerJar);
  }

  private static AnalysisContext contextFor(
      Path applicationSource, Path applicationClasses, List<Path> dependencies) {
    return AnalysisContext.builder()
        .id("war-dependency-boundary")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("sootup-war-dependency-boundary")
        .javaRelease(17)
        .repositoryRoot(applicationSource.getParent().getParent())
        .applicationClasses(applicationClasses)
        .selectedProfile("production")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", applicationSource, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspath(dependencies)
        .dependencyClasspathRequired(true)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
  }

  private static AnalysisContext contextWithDependencies(Fixture fixture, List<Path> dependencies) {
    AnalysisContext original = fixture.context();
    AnalysisContext.SourceRoot sourceRoot = original.sourceRoots().get(0);
    AnalysisContext.Builder builder = AnalysisContext.builder()
        .id(original.id())
        .framework(original.framework())
        .frameworkVersion(original.frameworkVersion())
        .fixtureId(original.fixtureId())
        .javaRelease(original.javaRelease())
        .repositoryRoot(original.repositoryRoot())
        .applicationClasses(original.applicationClasses())
        .selectedProfile(original.selectedProfile())
        .analysisScope(original.analysisScope())
        .sourceRoot(sourceRoot.declaredPath(), sourceRoot.path(), sourceRoot.scope(), sourceRoot.required())
        .dependencyClasspath(dependencies)
        .dependencyClasspathRequired(original.dependencyClasspathRequired())
        .frameworkBindingMetadata(original.frameworkBindingMetadata())
        .provenance(original.provenance())
        .handoff(original.handoff());
    original.frameworkMetadata().forEach(builder::frameworkMetadata);
    return builder.build();
  }

  private static SemanticCallFacts.Fact fact(SemanticCallFacts facts, String caller) {
    return facts.providerFacts().stream()
        .filter(item -> item.caller().equals(caller))
        .filter(item -> item.evidence().stream().anyMatch(evidence ->
            evidence.attributes().containsKey("calleeSignature")))
        .filter(item -> !item.relationshipType().equals("interface_dispatch"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.providerFacts().toString()));
  }

  private static void assertFinalVirtualCallIsDefinite(SemanticCallFacts facts, String caller) {
    List<SemanticCallFacts.Fact> dispatches = facts.providerFacts().stream()
        .filter(item -> item.caller().equals("app.Application#" + caller))
        .filter(item -> item.relationshipType().equals("invokes"))
        .toList();
    assertFalse(dispatches.isEmpty(), facts.providerFacts().toString());
    assertTrue(dispatches.stream().allMatch(item ->
        item.certainty() == SemanticCallFacts.Certainty.DEFINITE));
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
      List<String> options = new ArrayList<>(List.of(
          "--release", "17", "-g", "-d", classes.toString()));
      if (!classpath.isEmpty()) {
        options.add("-classpath");
        options.add(classpath.stream().map(Path::toString).reduce((left, right) ->
            left + java.io.File.pathSeparator + right).orElseThrow());
      }
      var units = fileManager.getJavaFileObjectsFromFiles(
          sources.stream().map(Path::toFile).toList());
      boolean compiled = compiler.getTask(null, fileManager, null, options, null, units).call();
      assertTrue(compiled, "integration fixture compilation failed");
    }
  }

  private static Path jar(Path classes, Path jar) throws IOException {
    try (OutputStream stream = Files.newOutputStream(jar);
         JarOutputStream output = new JarOutputStream(stream)) {
      List<Path> files = Files.walk(classes)
          .filter(Files::isRegularFile)
          .sorted(Comparator.comparing(Path::toString))
          .toList();
      for (Path file : files) {
        String entryName = classes.relativize(file).toString()
            .replace(file.getFileSystem().getSeparator(), "/");
        output.putNextEntry(new JarEntry(entryName));
        Files.copy(file, output);
        output.closeEntry();
      }
    }
    return jar;
  }

  private static Path war(Path classes, Path nestedJar, Path war) throws IOException {
    try (OutputStream stream = Files.newOutputStream(war);
         JarOutputStream output = new JarOutputStream(stream)) {
      for (String directory : List.of(
          "WEB-INF/", "WEB-INF/classes/", "WEB-INF/classes/war/",
          "WEB-INF/classes/war/api/", "WEB-INF/lib/")) {
        output.putNextEntry(new JarEntry(directory));
        output.closeEntry();
      }
      List<Path> files = Files.walk(classes)
          .filter(Files::isRegularFile)
          .sorted(Comparator.comparing(Path::toString))
          .toList();
      for (Path file : files) {
        String entryName = "WEB-INF/classes/" + classes.relativize(file).toString()
            .replace(file.getFileSystem().getSeparator(), "/");
        output.putNextEntry(new JarEntry(entryName));
        Files.copy(file, output);
        output.closeEntry();
      }
      output.putNextEntry(new JarEntry("WEB-INF/lib/nested.jar"));
      Files.copy(nestedJar, output);
      output.closeEntry();
    }
    return war;
  }

  private static void write(Path root, String relative, String source) throws IOException {
    Path path = root.resolve(relative);
    Files.createDirectories(path.getParent());
    Files.writeString(path, source);
  }

  private record Fixture(AnalysisContext context, Path vulnerableJar, Path peerJar) {}
}
