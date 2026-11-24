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
          void delete(String name);
        }
        """);
    AnalysisContext context = context(sourceRoot, "2.7.18",
        AnalysisContext.Framework.SPRING_BOOT);
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile repository =
        file(scanner, sourceRoot, "demo/UserRepository.java");
    // CrudRepository<User, User> makes the entity and id types equal; the
    // guaranteed-contract redeclaration check (delete is a contract name,
    // its single parameter differs from the entity/id types) must reach
    // the expected-types collection and not crash on the duplicate (a
    // List, not a Set).
    assertDoesNotThrow(() -> scanner.springRepositoryMethods(repository));
    assertTrue(scanner.springRepositoryMethods(repository).stream()
        .anyMatch(method -> method.name().equals("delete")
            && !method.guaranteed()));
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

  @Test
  void typeVariableParametersFailClosedInTheBijection() throws IOException {
    AnalysisContext context = context("type-variables", Map.of(
        "demo/Api.java", """
            package demo;
            import java.util.List;
            import java.util.Set;
            public interface Api {
              <List extends java.util.Set> void run(List p);
              <Set extends java.util.List> void run(Set p);
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
    // The mirrored type parameters erase to their bounds in the index but
    // resolve through imports textually — the vectors would derange and
    // the bijection would correlate a declaration to the OTHER overload.
    // A method type parameter therefore abstains: both overloads keep
    // their textual descriptors.
    assertTrue(on.stream().anyMatch(feature ->
            feature.target().equals("demo.Api#run")
                && feature.targetDescriptor().equals("(List)")),
        on.toString());
    assertTrue(on.stream().anyMatch(feature ->
            feature.target().equals("demo.Api#run")
                && feature.targetDescriptor().equals("(Set)")),
        on.toString());
    assertFalse(on.stream().anyMatch(feature ->
            feature.target().equals("demo.Api#run")
                && (feature.targetDescriptor().equals("(java.util.List)")
                    || feature.targetDescriptor().equals("(java.util.Set)"))),
        on.toString());
  }

  @Test
  void unresolvedTypeVariableBoundReturnAbstains() throws IOException {
    AnalysisContext context = context("unresolved-bound", Map.of(
        "demo/Producer.java", """
            package demo;
            import jakarta.enterprise.inject.Produces;
            public class Producer {
              @Produces public <T extends missing.Thing> T make() { return null; }
            }
            """));
    System.setProperty(FLAG, "true");
    List<SourceMetadataScannerState.BeanCandidate> on =
        new SourceMetadataScanner(context).beanCandidates(
            AnalysisContext.Framework.QUARKUS);
    // The declared return mirror is a type variable (not an error), but its
    // erasure IS an error type (unresolved bound): the member must abstain
    // rather than publish the erased error spelling as the bean type.
    assertTrue(on.stream()
        .anyMatch(candidate -> candidate.producerMethod().equals("make")
            && candidate.candidateType().isEmpty()),
        on.toString());
  }

  @Test
  void fragmentAndRepositorySignaturesShareOneRule() throws IOException {
    Path sourceRoot = temp.resolve("shared-identity").resolve("src/main/java");
    write(sourceRoot, "demo/Account.java", "package demo; public class Account {}\n");
    write(sourceRoot, "demo/Other.java", "package demo; public class Other {}\n");
    write(sourceRoot, "demo/AccountFragment.java", """
        package demo;
        public interface AccountFragment {
          Other find(String key);
        }
        """);
    write(sourceRoot, "demo/AccountRepository.java", """
        package demo;
        public interface AccountRepository
            extends org.springframework.data.repository.CrudRepository<Account, Long>,
                AccountFragment {
          Other find(String key);
        }
        """);
    AnalysisContext context = context(sourceRoot, "2.7.18",
        AnalysisContext.Framework.SPRING_BOOT);
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile repository =
        file(scanner, sourceRoot, "demo/AccountRepository.java");
    SourceMetadataScannerState.SourceFile fragment =
        file(scanner, sourceRoot, "demo/AccountFragment.java");
    // The same method declared in two files must get ONE signature identity
    // even though each file's index member differs (the cross-file Other
    // return is an error type per-file): the rule is per-parameter and
    // deterministic, so both sides canonicalize identically.
    String repositorySignature = scanner.springRepositoryMethods(repository).stream()
        .filter(method -> method.name().equals("find"))
        .map(SourceMetadataScannerState.RepositoryMethod::identity)
        .findFirst().orElseThrow();
    SourceMetadataScannerState.MethodInfo fragmentFind = SourceMetadataScanner
        .methods(fragment).stream()
        .filter(method -> method.name().equals("find"))
        .findFirst().orElseThrow();
    String fragmentSignature = scanner.repositoryMethodSignature(fragment, fragmentFind);
    assertEquals("find(java.lang.String)", repositorySignature);
    assertEquals(repositorySignature, fragmentSignature);
  }

  @Test
  void typeVariableAbstentionSurvivesMethodBodies() throws IOException {
    AnalysisContext context = context("type-variables-bodies", Map.of(
        "demo/Example.java", """
            package demo;
            import java.util.List;
            import java.util.Set;
            public class Example {
              public <List extends java.util.Set> String pick(List p) { return null; }
              public <Set extends java.util.List> Integer pick(Set p) { return null; }
            }
            """));
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile file =
        file(scanner, sourceRoot("type-variables-bodies"), "demo/Example.java");
    JavacMemberCorrelation correlation = JavacMemberCorrelation.of(scanner);
    for (SourceMetadataScannerState.MethodInfo method
        : SourceMetadataScanner.methods(file)) {
      // Methods with bodies follow the previous declaration's closing
      // brace, which the declaration start includes: the type-parameter
      // clause must still be found, so both mirrored overloads abstain.
      assertTrue(correlation
              .correlatedMethod(file, "demo.Example", method).isEmpty(),
          "the mirrored type-parameter overload must abstain: " + method.parameters());
    }
  }

  @Test
  void fragmentAndRepositorySignaturesShareOneRuleAcrossImportStyles() throws IOException {
    Path sourceRoot = temp.resolve("import-styles").resolve("src/main/java");
    write(sourceRoot, "demo/Account.java", "package demo; public class Account {}\n");
    write(sourceRoot, "demo/AccountFragment.java", """
        package demo;
        import java.util.*;
        public interface AccountFragment {
          Date find(Date key);
        }
        """);
    write(sourceRoot, "demo/AccountRepository.java", """
        package demo;
        import java.util.Date;
        public interface AccountRepository
            extends org.springframework.data.repository.CrudRepository<Account, Long>,
                AccountFragment {
          Date find(Date key);
        }
        """);
    AnalysisContext context = context(sourceRoot, "2.7.18",
        AnalysisContext.Framework.SPRING_BOOT);
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile repository =
        file(scanner, sourceRoot, "demo/AccountRepository.java");
    SourceMetadataScannerState.SourceFile fragment =
        file(scanner, sourceRoot, "demo/AccountFragment.java");
    // Both files declare the SAME spelling (Date) but import it
    // differently: the repository explicitly (resolves to java.util.Date),
    // the fragment on-demand (resolves to nothing — probe-verified). An
    // import-dependent canonical form would split the identity
    // (find(java.util.Date) vs find(Date)); the stable rule canonicalizes
    // only file-INDEPENDENT resolutions (primitives, qualified spellings,
    // java.lang), so both sides keep the token and the identity holds.
    String repositorySignature = scanner.springRepositoryMethods(repository).stream()
        .filter(method -> method.name().equals("find"))
        .map(SourceMetadataScannerState.RepositoryMethod::identity)
        .findFirst().orElseThrow();
    SourceMetadataScannerState.MethodInfo fragmentFind = SourceMetadataScanner
        .methods(fragment).stream()
        .filter(method -> method.name().equals("find"))
        .findFirst().orElseThrow();
    String fragmentSignature = scanner.repositoryMethodSignature(fragment, fragmentFind);
    assertEquals(repositorySignature, fragmentSignature,
        "import-style differences must not split the identity: "
            + repositorySignature + " vs " + fragmentSignature);
  }

  @Test
  void nestedTypeParameterBoundsYieldEveryName() throws IOException {
    AnalysisContext context = context("nested-clause", Map.of(
        "demo/Nested.java", """
            package demo;
            public class Nested {
              public <X extends java.util.Set<String>, Y extends java.util.List>
                  String pick(X p) { return null; }
              public <Y extends java.util.List, X extends java.util.Set<String>>
                  Integer pick(Y p) { return null; }
            }
            """));
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile file =
        file(scanner, sourceRoot("nested-clause"), "demo/Nested.java");
    JavacMemberCorrelation correlation = JavacMemberCorrelation.of(scanner);
    for (SourceMetadataScannerState.MethodInfo method
        : SourceMetadataScanner.methods(file)) {
      // The nested bound (Set<String>) contains an inner '>'; a parser that
      // stops at the first '>' would miss the second type parameter and
      // let the mirrored overloads correlate to each other's members. The
      // balanced parse yields every name, so both abstain.
      assertTrue(correlation
              .correlatedMethod(file, "demo.Nested", method).isEmpty(),
          "the nested-bound mirrored overload must abstain: " + method.parameters());
    }
  }

  @Test
  void unresolvedTypeVariableBoundParameterAbstains() throws IOException {
    AnalysisContext context = context("unresolved-bound-param", Map.of(
        "demo/Bound.java", """
            package demo;
            public class Bound<T extends missing.Thing> {
              public void run(T value) { }
            }
            """,
        "demo/Valid.java", """
            package demo;
            public class Valid<T extends Number> {
              public void run(T value) { }
            }
            """));
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile boundFile =
        file(scanner, sourceRoot("unresolved-bound-param"), "demo/Bound.java");
    SourceMetadataScannerState.MethodInfo run = SourceMetadataScanner
        .methods(boundFile).stream()
        .filter(method -> method.name().equals("run"))
        .findFirst().orElseThrow();
    // The parameter's declared mirror is a TYPEVAR (not an error kind), but
    // its ERASURE is an error type (the class-scoped bound is unresolved):
    // the member must abstain rather than publish the erased error spelling
    // in parameterTypes. This pins the erased-parameter check specifically —
    // the declared check alone does not fire for a TYPEVAR mirror.
    assertTrue(JavacMemberCorrelation.of(scanner)
            .correlatedMethod(boundFile, "demo.Bound", run).isEmpty(),
        "an unresolved type-variable bound parameter must abstain");
    // Control: a VALID bound (T extends Number, erasure java.lang.Number is
    // not an error type) stays eligible — the member correlates and its
    // canonical parameter type is the erased bound.
    SourceMetadataScannerState.SourceFile validFile =
        file(scanner, sourceRoot("unresolved-bound-param"), "demo/Valid.java");
    SourceMetadataScannerState.MethodInfo validRun = SourceMetadataScanner
        .methods(validFile).stream()
        .filter(method -> method.name().equals("run"))
        .findFirst().orElseThrow();
    assertEquals(List.of("java.lang.Number"),
        JavacMemberCorrelation.of(scanner)
            .correlatedMethod(validFile, "demo.Valid", validRun)
            .orElseThrow().parameterTypes(),
        "a valid bounded type variable must stay eligible");
  }

  @Test
  void commentsInsideBoundsAndLaterNamesAreRecognized() throws IOException {
    AnalysisContext context = context("comment-in-clause", Map.of(
        "demo/Example.java", """
            package demo;
            import java.util.List;
            import java.util.Set;
            public class Example {
              public <X extends /* > */ Number, List extends java.util.Set>
                  String pick(List p) { return null; }
              public <Y extends /* > */ Number, Set extends java.util.List>
                  Integer pick(Set p) { return null; }
            }
            """));
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile file =
        file(scanner, sourceRoot("comment-in-clause"), "demo/Example.java");
    JavacMemberCorrelation correlation = JavacMemberCorrelation.of(scanner);
    List<SourceMetadataScannerState.MethodInfo> picks = SourceMetadataScanner
        .methods(file).stream()
        .filter(method -> method.name().equals("pick"))
        .toList();
    assertEquals(2, picks.size(), "both mirrored overloads must be found");
    // The comment's '>' must not end the balanced clause (the header is
    // comment-masked before parsing), and the parameters use the SECOND
    // declared name in each clause (after the nested bound) — proving the
    // later names are parsed. Both mirrored overloads abstain.
    for (SourceMetadataScannerState.MethodInfo method : picks) {
      assertTrue(correlation
              .correlatedMethod(file, "demo.Example", method).isEmpty(),
          "the comment-corrupted mirrored overload must abstain: "
              + method.parameters());
    }
  }

  @Test
  void commentBeforeClauseDoesNotHideTypeParameters() throws IOException {
    AnalysisContext context = context("comment-before-clause", Map.of(
        "demo/Example.java", """
            package demo;
            import java.util.List;
            import java.util.Set;
            public class Example {
              public /* List p */ <X extends java.util.Set<String>, List extends java.util.Set>
                  String pick(List p) { return null; }
              public /* Set p */ <Y extends java.util.Set<String>, Set extends java.util.List>
                  Integer pick(Set p) { return null; }
            }
            """));
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile file =
        file(scanner, sourceRoot("comment-before-clause"), "demo/Example.java");
    JavacMemberCorrelation correlation = JavacMemberCorrelation.of(scanner);
    List<SourceMetadataScannerState.MethodInfo> picks = SourceMetadataScanner
        .methods(file).stream()
        .filter(method -> method.name().equals("pick"))
        .toList();
    assertEquals(2, picks.size(), "both mirrored overloads must be found");
    // The comment BEFORE the clause repeats the parameter text verbatim
    // (public /* List p */ <...>): a raw indexOf for the parameter list
    // would truncate the header at the comment, hide the clause entirely,
    // and let the mirrored overloads correlate to each other. The boundary
    // is located on the comment-masked source, so the clause is found. The
    // parameters are the SECOND declared names in each clause, after an
    // actual nested generic bound (Set<String>). Both abstain.
    for (SourceMetadataScannerState.MethodInfo method : picks) {
      assertTrue(correlation
              .correlatedMethod(file, "demo.Example", method).isEmpty(),
          "the comment-before-clause mirrored overload must abstain: "
              + method.parameters());
    }
  }

  @Test
  void nestedParenthesisAnnotationArgumentBeforeClauseAbstains() throws IOException {
    AnalysisContext context = context("nested-paren-annotation", Map.of(
        "demo/Ann.java", """
            package demo;
            public @interface B { int value(); }
            public @interface A { B value(); }
            """,
        "demo/Example.java", """
            package demo;
            import java.util.List;
            import java.util.Set;
            public class Example {
              @A(@B(1))
              public <X extends java.util.Set<String>, List extends java.util.Set>
                  String pick(List p) { return null; }
              @A(@B(1))
              public <Y extends java.util.Set<String>, Set extends java.util.List>
                  Integer pick(Set p) { return null; }
            }
            """));
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile file =
        file(scanner, sourceRoot("nested-paren-annotation"), "demo/Example.java");
    JavacMemberCorrelation correlation = JavacMemberCorrelation.of(scanner);
    List<SourceMetadataScannerState.MethodInfo> picks = SourceMetadataScanner
        .methods(file).stream()
        .filter(method -> method.name().equals("pick"))
        .toList();
    assertEquals(2, picks.size(), "both mirrored overloads must be found");
    // The annotation argument has NESTED parentheses (@A(@B(1))): a flat
    // [^)]* annotation pattern cannot consume it, the clause after it goes
    // unrecognized, and the mirrored overloads correlate to each other's
    // members. The prefix parser consumes balanced annotation arguments,
    // so the clause is found and both abstain.
    for (SourceMetadataScannerState.MethodInfo method : picks) {
      assertTrue(correlation
              .correlatedMethod(file, "demo.Example", method).isEmpty(),
          "the nested-paren annotation mirrored overload must abstain: "
              + method.parameters());
    }
  }

  @Test
  void boundAnnotationArgumentBracketsDoNotCloseClause() throws IOException {
    AnalysisContext context = context("bound-annotation-brackets", Map.of(
        "demo/A.java", """
            package demo;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            @Target(ElementType.TYPE_USE)
            public @interface A { boolean value(); }
            """,
        "demo/Example.java", """
            package demo;
            import java.util.List;
            import java.util.Set;
            public class Example {
              public <X extends @A(1 > 0) Number, List extends java.util.Set>
                  String pick(List p) { return null; }
              public <Y extends @A(1 > 0) Number, Set extends java.util.List>
                  Integer pick(Set p) { return null; }
            }
            """));
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile file =
        file(scanner, sourceRoot("bound-annotation-brackets"), "demo/Example.java");
    JavacMemberCorrelation correlation = JavacMemberCorrelation.of(scanner);
    List<SourceMetadataScannerState.MethodInfo> picks = SourceMetadataScanner
        .methods(file).stream()
        .filter(method -> method.name().equals("pick"))
        .toList();
    assertEquals(2, picks.size(), "both mirrored overloads must be found");
    // The bound annotation argument contains '>' (@A(1 > 0)): counting it
    // as an angle bracket closes the clause before the second name, so
    // List/Set resolve through imports and the mirrored overloads
    // correlate to each other's members. Parenthesis contents are
    // shielded from the bracket count, so both abstain.
    for (SourceMetadataScannerState.MethodInfo method : picks) {
      assertTrue(correlation
              .correlatedMethod(file, "demo.Example", method).isEmpty(),
          "the bound-annotation mirrored overload must abstain: "
              + method.parameters());
    }
  }

  @Test
  void whitespaceBeforeAnnotationArgumentsKeepsClauseVisible() throws IOException {
    AnalysisContext context = context("ws-annotation-args", Map.of(
        "demo/Ann.java", """
            package demo;
            public @interface B { int value(); }
            public @interface A { B value(); }
            """,
        "demo/Example.java", """
            package demo;
            import java.util.List;
            import java.util.Set;
            public class Example {
              @A (@B(1))
              public <X extends java.util.Set<String>, List extends java.util.Set>
                  String pick(List p) { return null; }
              @A (@B(1))
              public <Y extends java.util.Set<String>, Set extends java.util.List>
                  Integer pick(Set p) { return null; }
            }
            """));
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile file =
        file(scanner, sourceRoot("ws-annotation-args"), "demo/Example.java");
    JavacMemberCorrelation correlation = JavacMemberCorrelation.of(scanner);
    List<SourceMetadataScannerState.MethodInfo> picks = SourceMetadataScanner
        .methods(file).stream()
        .filter(method -> method.name().equals("pick"))
        .toList();
    assertEquals(2, picks.size(), "both mirrored overloads must be found");
    // Whitespace between the annotation name and its argument list is
    // legal Java: the argument consumption must skip it, otherwise the
    // annotation looks argument-less, the prefix stops at '(', the clause
    // goes unrecognized, and the mirrored overloads correlate to each
    // other's members.
    for (SourceMetadataScannerState.MethodInfo method : picks) {
      assertTrue(correlation
              .correlatedMethod(file, "demo.Example", method).isEmpty(),
          "the whitespace-annotation mirrored overload must abstain: "
              + method.parameters());
    }
  }

  @Test
  void annotatedTypeParameterNamesAreRecognized() throws IOException {
    AnalysisContext context = context("annotated-type-params", Map.of(
        "demo/Ann.java", """
            package demo;
            public @interface Ann { }
            """,
        "demo/Example.java", """
            package demo;
            import java.util.List;
            import java.util.Set;
            public class Example {
              public <@Ann List extends java.util.Set> String pick(List p) { return null; }
              public <@Ann Set extends java.util.List> Integer pick(Set p) { return null; }
            }
            """));
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile file =
        file(scanner, sourceRoot("annotated-type-params"), "demo/Example.java");
    JavacMemberCorrelation correlation = JavacMemberCorrelation.of(scanner);
    List<SourceMetadataScannerState.MethodInfo> picks = SourceMetadataScanner
        .methods(file).stream()
        .filter(method -> method.name().equals("pick"))
        .toList();
    assertEquals(2, picks.size(), "both mirrored overloads must be found");
    // An annotation on the type parameter itself (<@Ann List ...>) must be
    // stripped before the name is taken: recording "@Ann" as the name
    // would leave List/Set unresolved through imports and let the
    // mirrored overloads correlate to each other's members.
    for (SourceMetadataScannerState.MethodInfo method : picks) {
      assertTrue(correlation
              .correlatedMethod(file, "demo.Example", method).isEmpty(),
          "the annotated type-parameter mirrored overload must abstain: "
              + method.parameters());
    }
  }

  @Test
  void typeVariableTokenKeepsDistinctOverloadIdentities() throws IOException {
    Path sourceRoot = temp.resolve("token-overloads").resolve("src/main/java");
    write(sourceRoot, "demo/Account.java", "package demo; public class Account {}\n");
    write(sourceRoot, "demo/AccountRepository.java", """
        package demo;
        public interface AccountRepository
            extends org.springframework.data.repository.CrudRepository<Account, Long> {
          <String extends Number> Object find(String key);
          Object find(java.lang.String key);
        }
        """);
    AnalysisContext context = context(sourceRoot, "2.7.18",
        AnalysisContext.Framework.SPRING_BOOT);
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile repository =
        file(scanner, sourceRoot, "demo/AccountRepository.java");
    // Two DISTINCT overloads: <String extends Number> find(String key) and
    // find(java.lang.String key). The type variable must keep its TOKEN
    // (resolving String through java.lang would give both overloads the
    // identity find(java.lang.String) and deduplicate them). The token
    // rule keeps the identities distinct: find(String) vs
    // find(java.lang.String).
    List<String> identities = scanner.springRepositoryMethods(repository).stream()
        .map(SourceMetadataScannerState.RepositoryMethod::identity)
        .filter(identity -> identity.startsWith("find("))
        .sorted()
        .toList();
    assertEquals(List.of("find(String)", "find(java.lang.String)"), identities,
        "a type-variable parameter must keep its token so the two "
            + "overloads keep distinct identities: " + identities);
  }

  @Test
  void whitespaceAroundQualifiedNameDotsKeepsClauseVisible() throws IOException {
    AnalysisContext context = context("ws-qualified-annotation", Map.of(
        "demo/Example.java", """
            package demo;
            import java.util.List;
            import java.util.Set;
            public class Example {
              @java . lang . SuppressWarnings ("unchecked")
              public <X extends java.util.Set<String>, List extends java.util.Set>
                  String pick(List p) { return null; }
              @java . lang . SuppressWarnings ("unchecked")
              public <Y extends java.util.Set<String>, Set extends java.util.List>
                  Integer pick(Set p) { return null; }
            }
            """));
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile file =
        file(scanner, sourceRoot("ws-qualified-annotation"), "demo/Example.java");
    JavacMemberCorrelation correlation = JavacMemberCorrelation.of(scanner);
    List<SourceMetadataScannerState.MethodInfo> picks = SourceMetadataScanner
        .methods(file).stream()
        .filter(method -> method.name().equals("pick"))
        .toList();
    assertEquals(2, picks.size(), "both mirrored overloads must be found");
    // Whitespace around the dots of a qualified annotation name
    // (@java . lang . SuppressWarnings (...)) is legal Java: the qualified
    // name consumption must skip it, otherwise the name stops at the first
    // segment, the prefix stops at the '.', the clause goes unrecognized,
    // and the mirrored overloads correlate to each other's members.
    for (SourceMetadataScannerState.MethodInfo method : picks) {
      assertTrue(correlation
              .correlatedMethod(file, "demo.Example", method).isEmpty(),
          "the whitespace-qualified-annotation mirrored overload must abstain: "
              + method.parameters());
    }
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
