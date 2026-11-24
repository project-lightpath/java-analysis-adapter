package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * javac TypeMirror parameter/return/type-shape matching behind the
 * {@code flpath.java.symbolindex} flag, for issue #23.
 *
 * <p>Each flag-on cell asserts the index-shape answer; each surface also
 * pins its flag-off textual golden. The class runs same-thread and
 * {@link Isolated isolated} because it writes the flag.
 */
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class JavacTypeShapeMigrationTest {
  private static final String FLAG = "flpath.java.symbolindex";

  @TempDir Path temp;

  @AfterEach
  void clearFlag() {
    System.clearProperty(FLAG);
  }

  @Test
  void multiOverloadProxyDescriptorsCorrelateByType() throws IOException {
    AnalysisContext context = context("multi-overload", Map.of(
        "demo/Api.java", """
            package demo;
            public interface Api {
              void run(String p);
              void run(Integer p);
            }
            """,
        "demo/Main.java", """
            package demo;
            public class Main {
              void audit() {
                Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {Api.class},
                    (proxyInstance, method, args) -> null);
              }
            }
            """));
    System.clearProperty(FLAG);
    List<DynamicFeature> off = new SourceMetadataScanner(context).dynamicFeatures();
    System.setProperty(FLAG, "true");
    List<DynamicFeature> on = new SourceMetadataScanner(context).dynamicFeatures();
    // Flag-off golden: the textual arity-collapsed spellings.
    assertTrue(off.stream().anyMatch(feature ->
            feature.target().equals("demo.Api#run")
                && feature.targetDescriptor().equals("(String)")),
        off.toString());
    assertTrue(off.stream().anyMatch(feature ->
            feature.target().equals("demo.Api#run")
                && feature.targetDescriptor().equals("(Integer)")),
        off.toString());
    // Flag-on: the type bijection correlates both same-arity overloads and
    // the descriptors carry canonical index shapes.
    assertTrue(on.stream().anyMatch(feature ->
            feature.target().equals("demo.Api#run")
                && feature.targetDescriptor().equals("(java.lang.String)")),
        on.toString());
    assertTrue(on.stream().anyMatch(feature ->
            feature.target().equals("demo.Api#run")
                && feature.targetDescriptor().equals("(java.lang.Integer)")),
        on.toString());
  }

  @Test
  void bijectionFailsClosedOnUnresolvableSpelling() throws IOException {
    AnalysisContext context = context("fail-closed", Map.of(
        "demo/Outer.java", """
            package demo;
            class Outer { static class Inner { } }
            """,
        "demo/Api.java", """
            package demo;
            public interface Api {
              void run(String p);
              void run(Inner p);
            }
            """,
        "demo/Main.java", """
            package demo;
            public class Main {
              void audit() {
                Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {Api.class},
                    (proxyInstance, method, args) -> null);
              }
            }
            """));
    System.setProperty(FLAG, "true");
    List<DynamicFeature> on = new SourceMetadataScanner(context).dynamicFeatures();
    // The annotated overload's textual vector is unresolvable, so the whole
    // two-member group fails closed to textual — no partial correlation.
    assertTrue(on.stream().anyMatch(feature ->
            feature.target().equals("demo.Api#run")
                && feature.targetDescriptor().equals("(String)")),
        on.toString());
    assertTrue(on.stream().anyMatch(feature ->
            feature.target().equals("demo.Api#run")
                && feature.targetDescriptor().equals("(Inner)")),
        on.toString());
    assertFalse(on.stream().anyMatch(feature ->
            feature.target().equals("demo.Api#run")
                && feature.targetDescriptor().equals("(java.lang.String)")),
        on.toString());
  }

  @Test
  void commentCorruptedOverloadStaysTextualWhileCleanSiblingUpgrades() throws IOException {
    AnalysisContext context = context("comment-guard", Map.of(
        "demo/Api.java", """
            package demo;
            public interface Api {
              void call(String p);
              void call(@SuppressWarnings(\"unused\") int a /* ( */, int b);
            }
            """,
        "demo/Main.java", """
            package demo;
            public class Main {
              void audit() {
                Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {Api.class},
                    (proxyInstance, method, args) -> null);
              }
            }
            """));
    System.setProperty(FLAG, "true");
    List<DynamicFeature> on = new SourceMetadataScanner(context).dynamicFeatures();
    // COUNT assertion: proxy features carry only owner#name identity, so the
    // fail-closed control is a count. Exactly one demo.Api#call feature may
    // carry the canonical descriptor (the clean sibling); the corrupted
    // overload keeps a textual descriptor containing int.
    assertEquals(1, on.stream()
        .filter(feature -> feature.target().equals("demo.Api#call")
            && feature.targetDescriptor().equals("(java.lang.String)"))
        .count(),
        on.toString());
    assertTrue(on.stream()
        .filter(feature -> feature.target().equals("demo.Api#call"))
        .anyMatch(feature -> !feature.targetDescriptor().equals("(java.lang.String)")
            && !feature.targetDescriptor().equals("(java.lang.String[])")
            && feature.targetDescriptor().contains("int")),
        on.toString());
  }

  @Test
  void constructorCorrelationUsesInitToSimpleNameMapping() throws IOException {
    AnalysisContext context = context("constructors", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              public Probe(String label) { }
              public Probe(int count) { }
            }
            """));
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile file =
        file(scanner, sourceRoot("constructors"), "demo/Probe.java");
    SourceMetadataScannerState.MethodInfo ctor = SourceMetadataScanner
        .constructors(file).stream()
        .filter(method -> method.parameters().contains("String"))
        .findFirst().orElseThrow();
    JavacMemberCorrelation access = JavacMemberCorrelation.of(scanner);
    // The <init> textual declaration correlates to the index constructor
    // named by the owner's simple name, through the same type bijection.
    assertEquals(List.of("java.lang.String"),
        access.correlatedConstructor(file, "demo.Probe", ctor)
            .orElseThrow().parameterTypes());
    SourceMetadataScannerState.MethodInfo intCtor = SourceMetadataScanner
        .constructors(file).stream()
        .filter(method -> method.parameters().contains("int"))
        .findFirst().orElseThrow();
    assertEquals(List.of("int"),
        access.correlatedConstructor(file, "demo.Probe", intCtor)
            .orElseThrow().parameterTypes());
  }

  @Test
  void reflectionParameterMatchingUsesCanonicalIndexTypes() throws IOException {
    AnalysisContext context = context("reflection", Map.of(
        "demo/Ann.java", """
            package demo;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @interface Ann { int value() default 0; }
            """,
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit(@Ann(value = 1) String target) {
                try { Class.forName(target); } catch (ClassNotFoundException e) { }
              }
            }
            """));
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile file =
        file(scanner, sourceRoot("reflection"), "demo/Probe.java");
    SourceMetadataScannerState.MethodInfo audit = SourceMetadataScanner
        .methods(file).get(0);
    int offset = audit.start() + 10;
    System.clearProperty(FLAG);
    // Flag-off golden: the corrupted textual spelling cannot match.
    assertFalse(scanner.parameterMatchesType(
        file, audit, "java.lang.String", "target", offset));
    System.setProperty(FLAG, "true");
    // Flag-on: the correlated member's canonical parameter type matches.
    assertTrue(scanner.parameterMatchesType(
        file, audit, "java.lang.String", "target", offset));
  }

  @Test
  void producerReturnTypeAbstainsOnUnprovableIndexShapes() throws IOException {
    AnalysisContext context = context("producer", Map.of(
        "demo/Outer.java", """
            package demo;
            class Outer { static class Inner { } }
            """,
        "demo/Producer.java", """
            package demo;
            import jakarta.enterprise.inject.Produces;
            public class Producer {
              @Produces public Inner make() { return null; }
              @Produces public String clean() { return null; }
            }
            """));
    System.clearProperty(FLAG);
    List<SourceMetadataScannerState.BeanCandidate> off =
        new SourceMetadataScanner(context).beanCandidates(
            AnalysisContext.Framework.QUARKUS);
    System.setProperty(FLAG, "true");
    List<SourceMetadataScannerState.BeanCandidate> on =
        new SourceMetadataScanner(context).beanCandidates(
            AnalysisContext.Framework.QUARKUS);
    // Flag-off golden: the cross-file nested return type resolves to
    // nothing textually; the clean producer resolves canonically.
    assertTrue(off.stream()
        .anyMatch(candidate -> candidate.producerMethod().equals("make")
            && candidate.candidateType().isEmpty()),
        off.toString());
    assertTrue(off.stream()
        .anyMatch(candidate -> candidate.producerMethod().equals("clean")
            && candidate.candidateType().equals("java.lang.String")),
        off.toString());
    // Flag-on: the index is per-file, so the cross-file nested return is an
    // ERROR type — the correlated member must abstain (never surface the
    // unqualified error spelling "Inner" as a canonical answer) and the
    // textual answer survives; the clean producer keeps its canonical type.
    assertTrue(on.stream()
        .anyMatch(candidate -> candidate.producerMethod().equals("make")
            && candidate.candidateType().isEmpty()),
        on.toString());
    assertTrue(on.stream()
        .anyMatch(candidate -> candidate.producerMethod().equals("make")
            && !candidate.candidateType().equals("Inner")),
        on.toString());
    assertTrue(on.stream()
        .anyMatch(candidate -> candidate.producerMethod().equals("clean")
            && candidate.candidateType().equals("java.lang.String")),
        on.toString());
  }

  @Test
  void producerOverloadDescriptorsDifferentiateByType() throws IOException {
    AnalysisContext context = context("producer-overloads", Map.of(
        "demo/Producer.java", """
            package demo;
            import jakarta.enterprise.inject.Produces;
            public class Producer {
              @Produces public String make(String seed) { return seed; }
              @Produces public Integer make(Integer seed) { return seed; }
            }
            """));
    System.clearProperty(FLAG);
    List<SourceMetadataScannerState.BeanCandidate> off =
        new SourceMetadataScanner(context).beanCandidates(
            AnalysisContext.Framework.QUARKUS);
    System.setProperty(FLAG, "true");
    SourceMetadataScanner onScanner = new SourceMetadataScanner(context);
    List<SourceMetadataScannerState.BeanCandidate> on =
        onScanner.beanCandidates(AnalysisContext.Framework.QUARKUS);
    // Flag-off golden: two same-arity producer candidates exist.
    assertEquals(2, off.stream()
        .filter(candidate -> candidate.producerMethod().equals("make")).count());
    // Flag-on: the bijection correlates each overload and each candidate
    // renders ITS OWN JVM descriptor — never the other overload's. The
    // candidates are told apart by their canonical bean types.
    assertEquals(2, on.stream()
        .filter(candidate -> candidate.producerMethod().equals("make")).count());
    for (SourceMetadataScannerState.BeanCandidate candidate : on) {
      if (!candidate.producerMethod().equals("make")) {
        continue;
      }
      Optional<String> descriptor =
          BoundedFrameworkModelCandidateMetadata.producerDescriptor(onScanner, candidate);
      if (candidate.candidateType().equals("java.lang.String")) {
        assertEquals(Optional.of("(Ljava/lang/String;)Ljava/lang/String;"), descriptor,
            "the String overload must carry its own descriptor");
      } else if (candidate.candidateType().equals("java.lang.Integer")) {
        assertEquals(Optional.of("(Ljava/lang/Integer;)Ljava/lang/Integer;"), descriptor,
            "the Integer overload must carry its own descriptor");
      } else {
        throw new AssertionError("unexpected producer type: " + candidate.candidateType());
      }
    }
  }

  @Test
  void repositorySignatureUsesCanonicalIndexTypes() throws IOException {
    Path sourceRoot = temp.resolve("repo").resolve("src/main/java");
    write(sourceRoot, "demo/Ann.java", """
        package demo;
        import java.lang.annotation.*;
        @Retention(RetentionPolicy.RUNTIME) @interface Ann { int value() default 0; }
        """);
    write(sourceRoot, "demo/Account.java", "package demo; public class Account {}\n");
    write(sourceRoot, "demo/AccountRepository.java", """
        package demo;
        import java.util.List;
        public interface AccountRepository
            extends org.springframework.data.repository.CrudRepository<Account, Long> {
          List<Account> findByOwner(@Ann(value = 1) String owner);
        }
        """);
    AnalysisContext context = context(sourceRoot, "2.7.18",
        AnalysisContext.Framework.SPRING_BOOT);
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile repository =
        file(scanner, sourceRoot("repo"), "demo/AccountRepository.java");
    System.clearProperty(FLAG);
    SourceMetadataScannerState.RepositoryMethod textual = scanner
        .springRepositoryMethods(repository).stream()
        .filter(method -> method.name().equals("findByOwner"))
        .findFirst().orElseThrow();
    System.setProperty(FLAG, "true");
    SourceMetadataScannerState.RepositoryMethod canonical = scanner
        .springRepositoryMethods(repository).stream()
        .filter(method -> method.name().equals("findByOwner"))
        .findFirst().orElseThrow();
    // Flag-off golden: the signature keeps the unqualified textual spelling.
    assertEquals("findByOwner(String)", textual.identity());
    // Flag-on: the correlated member's canonical parameter type.
    assertEquals("findByOwner(java.lang.String)", canonical.identity());
  }

  @Test
  void jvmDescriptorRendersFromIndexShapes() throws IOException {
    AnalysisContext context = context("jvm-descriptor", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              public Probe(java.util.List<String> items, String... rest) { }
            }
            """));
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile file =
        file(scanner, sourceRoot("jvm-descriptor"), "demo/Probe.java");
    SourceMetadataScannerState.MethodInfo ctor = SourceMetadataScanner
        .constructors(file).get(0);
    JavacMemberCorrelation access = JavacMemberCorrelation.of(scanner);
    JavacSourceSymbolIndex.MemberInfo member =
        access.correlatedConstructor(file, "demo.Probe", ctor).orElseThrow();
    // The bounded-model JVM descriptor dialect renders from index shapes:
    // erased generic -> java.util.List, varargs -> String[].
    assertEquals("(Ljava/util/List;[Ljava/lang/String;)V",
        JavacShapeDescriptors.jvmDescriptorFromMember(member).orElseThrow());
  }

  @Test
  void sameEntityIdRepositoryContractCheckDoesNotCrash() throws IOException {
    Path sourceRoot = temp.resolve("same-entity-id").resolve("src/main/java");
    write(sourceRoot, "demo/User.java", "package demo; public class User {}\n");
    write(sourceRoot, "demo/UserRepository.java", """
        package demo;
        public interface UserRepository
            extends org.springframework.data.repository.CrudRepository<User, User> {
          void deleteByName(String name);
        }
        """);
    AnalysisContext context = context(sourceRoot, "2.7.18",
        AnalysisContext.Framework.SPRING_BOOT);
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile repository =
        file(scanner, sourceRoot, "demo/UserRepository.java");
    // CrudRepository<User, User> makes the entity and id types equal; the
    // contract check must not crash on the duplicate (the expected-types
    // collection is a List, not a Set).
    assertDoesNotThrow(() -> scanner.springRepositoryMethods(repository));
    assertTrue(scanner.springRepositoryMethods(repository).stream()
        .anyMatch(method -> method.name().equals("deleteByName")));
  }

  @Test
  void reflectionParameterMatchingDoesNotMatchShadowedSimpleNames() throws IOException {
    AnalysisContext context = context("shadow", Map.of(
        "demo/Shadow.java", """
            package demo;
            class Class { }
            """,
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit(Class target) {
                try { Class.forName(target.toString()); }
                catch (ClassNotFoundException e) { }
              }
            }
            """));
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile file =
        file(scanner, sourceRoot("shadow"), "demo/Probe.java");
    SourceMetadataScannerState.MethodInfo audit = SourceMetadataScanner
        .methods(file).get(0);
    int offset = audit.start() + 10;
    System.setProperty(FLAG, "true");
    // The sibling demo.Class shadows java.lang.Class for this file (JLS
    // 6.4.1): the parameter's canonical type is demo.Class, and the
    // simple-name query "Class" must NOT match it — the canonical
    // comparison only serves exact canonical spellings.
    assertFalse(scanner.parameterMatchesType(
        file, audit, "Class", "target", offset));
    assertTrue(scanner.parameterMatchesType(
        file, audit, "demo.Class", "target", offset));
  }

  private SourceMetadataScannerState.SourceFile file(
      SourceMetadataScanner scanner, Path sourceRoot, String relative) {
    Path root = sourceRoot.resolve(relative);
    return scanner.files().stream()
        .filter(item -> item.path().equals(root))
        .findFirst().orElseThrow();
  }

  private Path sourceRoot(String id) {
    return temp.resolve(id).resolve("src/main/java");
  }

  private AnalysisContext context(String id, Map<String, String> files) throws IOException {
    Path sourceRoot = sourceRoot(id);
    for (Map.Entry<String, String> entry : files.entrySet()) {
      write(sourceRoot, entry.getKey(), entry.getValue());
    }
    return context(sourceRoot, "3.40.1", AnalysisContext.Framework.QUARKUS);
  }

  private AnalysisContext context(
      Path sourceRoot, String frameworkVersion,
      AnalysisContext.Framework framework) throws IOException {
    Path root = sourceRoot.getParent().getParent();
    return AnalysisContext.builder()
        .id(root.getFileName().toString())
        .framework(framework)
        .frameworkVersion(frameworkVersion)
        .fixtureId("javac-type-shape-test")
        .javaRelease(17)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
  }

  private static void write(Path sourceRoot, String relative, String content)
      throws IOException {
    Path file = sourceRoot.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }
}
