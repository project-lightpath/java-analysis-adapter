package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JLS 6.5 name-resolution family-closure matrix (AGENTS.md practice 1). One
 * fixture per cell of the precedence chain, weakest to strongest, asserting
 * attribution ONLY where the JDK class provably wins. Cells that re-pin
 * existing tests do so with self-contained fixtures so the matrix is readable
 * end-to-end; dispositions (accepted over-approximations) are labeled in the
 * fixture comments and recorded in the item-J evidence commit.
 */
class ServiceLoaderNameResolutionMatrixTest {
  @TempDir Path temp;

  /** Cell 1: on-demand import vs same-package type in another file (JLS 6.4.1). */
  @Test
  void onDemandImportVersusSamePackageTypeInAnotherFileAbstains() throws IOException {
    // JLS 6.4.1: a type-import-on-demand declaration never shadows any
    // declaration, so the same-package ServiceLoader declared in ANOTHER
    // compilation unit wins and the simple name is NOT the JDK class. The
    // adapter's abstention is therefore exactly correct behavior — the
    // earlier "accepted over-approximation" label rested on the inverted
    // rule and is withdrawn.
    assertTrue(matrixSites("matrix-cell-1", """
        package demo;
        import java.util.*;
        class Bridge {
          void audit() { ServiceLoader.load(Provider.class); }
        }
        """, Map.of("demo/ServiceLoader.java", """
        package demo;
        class ServiceLoader<T> { }
        """)).isEmpty());
  }

  /** Cell 2: on-demand import alone, no competing form. */
  @Test
  void onDemandImportAloneIsAttributed() throws IOException {
    assertFalse(matrixSites("matrix-cell-2", """
        package demo;
        import java.util.*;
        class Bridge {
          void audit() { ServiceLoader.load(Provider.class); }
        }
        """).isEmpty());
  }

  /** Cell 3: single-type import of java.util.ServiceLoader, no wildcard. */
  @Test
  void javaUtilSingleTypeImportIsAttributed() throws IOException {
    assertFalse(matrixSites("matrix-cell-3", """
        package demo;
        import java.util.ServiceLoader;
        class Bridge {
          void audit() { ServiceLoader.load(Provider.class); }
        }
        """).isEmpty());
  }

  /** Cell 4: single-type import of other.ServiceLoader, no wildcard. */
  @Test
  void foreignSingleTypeImportWithoutWildcardAbstains() throws IOException {
    assertTrue(matrixSites("matrix-cell-4", """
        package demo;
        import other.ServiceLoader;
        class Bridge {
          void audit() { ServiceLoader.load(Provider.class); }
        }
        """, Map.of("other/ServiceLoader.java", """
        package other;
        public class ServiceLoader<T> {
          public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
        }
        """)).isEmpty());
  }

  /** Cell 5: wildcard plus single-type import of other.ServiceLoader (item G). */
  @Test
  void wildcardPlusForeignSingleTypeImportAbstains() throws IOException {
    assertTrue(matrixSites("matrix-cell-5", """
        package demo;
        import java.util.*;
        import other.ServiceLoader;
        class Bridge {
          void audit() { ServiceLoader.load(Provider.class); }
        }
        """, Map.of("other/ServiceLoader.java", """
        package other;
        public class ServiceLoader<T> {
          public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
        }
        """)).isEmpty());
  }

  /** Cell 6a: same-compilation-unit top-level type shadows the imports. */
  @Test
  void sameCompilationUnitTopLevelTypeShadowsWildcardImport() throws IOException {
    assertTrue(matrixSites("matrix-cell-6a", """
        package demo;
        import java.util.*;
        class Bridge {
          void audit() { ServiceLoader.load(Provider.class); }
        }
        class ServiceLoader<T> {
          static <S> ServiceLoader<S> load(Class<S> type) { return null; }
        }
        """).isEmpty());
  }

  /** Cell 6b: nested type shadows the imports (re-pin). */
  @Test
  void sameCompilationUnitNestedTypeShadowsWildcardImport() throws IOException {
    assertTrue(matrixSites("matrix-cell-6b", """
        package demo;
        import java.util.*;
        class Bridge {
          static class ServiceLoader<T> {
            static <S> ServiceLoader<S> load(Class<S> type) { return null; }
          }
          void audit() { ServiceLoader.load(Provider.class); }
        }
        """).isEmpty());
  }

  /** Cell 6c: local class shadows the imports inside its block (re-pin). */
  @Test
  void sameCompilationUnitLocalTypeShadowsWildcardImport() throws IOException {
    assertTrue(matrixSites("matrix-cell-6c", """
        package demo;
        import java.util.*;
        class Bridge {
          void audit() {
            class ServiceLoader<T> {
              static <S> ServiceLoader<S> load(Class<S> type) { return null; }
            }
            ServiceLoader.load(Provider.class);
          }
        }
        """).isEmpty());
  }

  /** Cell 7a: value declarations (field) shadow the simple name. */
  @Test
  void fieldNamedServiceLoaderShadowsTheSimpleName() throws IOException {
    assertTrue(matrixSites("matrix-cell-7a", """
        package demo;
        import java.util.ServiceLoader;
        class Bridge {
          private final Helper ServiceLoader;
          Bridge() { this.ServiceLoader = new Helper(); }
          void audit() { ServiceLoader.load(Provider.class); }
          static class Helper {
            static <T> T load(Class<T> type) { return null; }
          }
        }
        """).isEmpty());
  }

  /** Cell 7b: value declarations (parameter, local, lambda parameter). */
  @Test
  void parameterLocalAndLambdaParameterNamedServiceLoaderShadow() throws IOException {
    assertTrue(matrixSites("matrix-cell-7b", """
        package demo;
        import java.util.ServiceLoader;
        class Bridge {
          void audit(Helper ServiceLoader) { ServiceLoader.load(Provider.class); }
          void withLocal() {
            Helper ServiceLoader = helper();
            ServiceLoader.load(Provider.class);
          }
          void withLambda() {
            accept((Helper ServiceLoader) -> ServiceLoader.load(Provider.class));
          }
          void accept(Object action) { }
          static class Helper {
            static <T> T load(Class<T> type) { return null; }
          }
          static Helper helper() { return null; }
        }
        """).isEmpty());
  }

  /**
   * Cell 8a: lambda parameter x local class declared inside the lambda. The
   * lambda parameter owns the name inside the body; both scopes end at the
   * lambda body, so a real call after the lambda stays attributed.
   */
  @Test
  void lambdaParameterAndLocalClassScopeEndAtTheLambdaBody() throws IOException {
    assertEquals(1, matrixSites("matrix-cell-8a", """
        package demo;
        import java.util.ServiceLoader;
        class Bridge {
          void audit() {
            accept((Helper ServiceLoader) -> {
              class ServiceLoader<T> { }
              ServiceLoader.load(Provider.class);
            });
            ServiceLoader.load(Provider.class);
          }
          void accept(Object action) { }
          static class Helper {
            static <T> T load(Class<T> type) { return null; }
          }
        }
        """).size());
  }

  /** Cell 8b: lambda-shaped switch pattern x later switch arm (item I). */
  @Test
  void arrowArmPatternVariableScopesToItsArm() throws IOException {
    assertEquals(1, matrixSites("matrix-cell-8b", """
        package demo;
        import java.util.ServiceLoader;
        class Bridge {
          void audit(Object value) {
            switch (value) {
              case Helper ServiceLoader -> ServiceLoader.touch();
              default -> ServiceLoader.load(Provider.class);
            }
          }
          static class Helper {
            static <T> T load(Class<T> type) { return null; }
          }
        }
        """).size());
  }

  /**
   * Cell 8c: import x nested type — wildcard plus foreign single-type import
   * plus a same-CU nested type. JLS 6.5: the same-CU nested type beats both
   * imports, so the simple name is NOT the JDK class: abstain.
   */
  @Test
  void nestedTypeBeatsWildcardAndForeignSingleTypeImport() throws IOException {
    assertTrue(matrixSites("matrix-cell-8c", """
        package demo;
        import java.util.*;
        import other.ServiceLoader;
        class Bridge {
          static class ServiceLoader<T> {
            static <S> ServiceLoader<S> load(Class<S> type) { return null; }
          }
          void audit() { ServiceLoader.load(Provider.class); }
        }
        """, Map.of("other/ServiceLoader.java", """
        package other;
        public class ServiceLoader<T> {
          public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
        }
        """)).isEmpty());
  }

  /**
   * Cell 9: fully qualified java.util.ServiceLoader.load under every competing
   * form (wildcard, foreign single-type import, nested type) is always
   * attributed: the FQN escape hatch.
   */
  @Test
  void fullyQualifiedNameIsAttributedUnderEveryCompetingForm() throws IOException {
    assertEquals(1, matrixSites("matrix-cell-9", """
        package demo;
        import java.util.*;
        import other.ServiceLoader;
        class Bridge {
          static class ServiceLoader<T> {
            static <S> ServiceLoader<S> load(Class<S> type) { return null; }
          }
          void audit() { java.util.ServiceLoader.load(Provider.class); }
        }
        """, Map.of("other/ServiceLoader.java", """
        package other;
        public class ServiceLoader<T> {
          public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
        }
        """)).size());
  }

  /**
   * Inherited member type from a superclass in another file (item L): with
   * Base.java declaring a nested ServiceLoader and Child extends Base, the
   * inherited member type is in scope in Child (JLS 6.4.1/6.5) and shadows
   * the on-demand import.
   */
  @Test
  void inheritedMemberTypeFromSuperclassInAnotherFileShadows() throws IOException {
    assertTrue(matrixSites("matrix-cell-inherited-member", """
        package demo;
        import java.util.*;
        class Bridge extends Base {
          void audit() { ServiceLoader.load(Provider.class); }
        }
        """, Map.of("demo/Base.java", """
        package demo;
        class Base {
          static class ServiceLoader<T> {
            static <S> ServiceLoader<S> load(Class<S> type) { return null; }
          }
        }
        """)).isEmpty());
  }

  /**
   * Guard (green at head): a top-level ServiceLoader as the superclass
   * already abstains through the same-package shadow path — item L's
   * extends-resolution must not break that shape.
   */
  @Test
  void superclassNameFromAnotherFileShadows() throws IOException {
    assertTrue(matrixSites("matrix-cell-superclass-name", """
        package demo;
        import java.util.*;
        class Bridge extends ServiceLoader {
          void audit() { ServiceLoader.load(Provider.class); }
        }
        """, Map.of("demo/ServiceLoader.java", """
        package demo;
        class ServiceLoader<T> {
          static <S> ServiceLoader<S> load(Class<S> type) { return null; }
        }
        """)).isEmpty());
  }

  /**
   * Precision guard: a third file's nested ServiceLoader that the site's
   * class does NOT extend must not shadow — the site stays attributed.
   */
  @Test
  void unrelatedServiceLoaderTypeInAnotherFileDoesNotShadow() throws IOException {
    assertFalse(matrixSites("matrix-cell-unrelated-nested", """
        package demo;
        import java.util.*;
        class Bridge extends Base {
          void audit() { ServiceLoader.load(Provider.class); }
        }
        """, Map.of("demo/Base.java", """
        package demo;
        class Base { }
        """, "demo/Unrelated.java", """
        package demo;
        class Unrelated {
          static class ServiceLoader<T> {
            static <S> ServiceLoader<S> load(Class<S> type) { return null; }
          }
        }
        """)).isEmpty());
  }

  /** FQN escape under the inherited member type: still attributed. */
  @Test
  void fullyQualifiedCallSurvivesInheritedMemberTypeShadow() throws IOException {
    assertEquals(1, matrixSites("matrix-cell-inherited-fqn", """
        package demo;
        import java.util.*;
        class Bridge extends Base {
          void audit() { java.util.ServiceLoader.load(Provider.class); }
        }
        """, Map.of("demo/Base.java", """
        package demo;
        class Base {
          static class ServiceLoader<T> {
            static <S> ServiceLoader<S> load(Class<S> type) { return null; }
          }
        }
        """)).size());
  }

  /**
   * Precision (item M): a type-parameter bound is not an extends clause.
   * {@code Bridge<T extends Base>} does not inherit Base's member types, so
   * the wildcard import still supplies the JDK ServiceLoader.
   */
  @Test
  void typeParameterBoundIsNotAnExtendsClause() throws IOException {
    assertFalse(matrixSites("matrix-cell-binding", """
        package demo;
        import java.util.*;
        class Bridge<T extends Base> {
          void audit() { ServiceLoader.load(Provider.class); }
        }
        """, Map.of("demo/Base.java", """
        package demo;
        class Base {
          static class ServiceLoader<T> { }
        }
        """)).isEmpty());
  }

  /**
   * Precision (item M): a type nested inside a member type is not inherited —
   * {@code Base.Inner.ServiceLoader} is not in scope in Bridge, so the
   * wildcard import supplies the JDK class.
   */
  @Test
  void nestedTypeOfMemberTypeIsNotInherited() throws IOException {
    assertFalse(matrixSites("matrix-cell-deep-nested", """
        package demo;
        import java.util.*;
        class Bridge extends Base {
          void audit() { ServiceLoader.load(Provider.class); }
        }
        """, Map.of("demo/Base.java", """
        package demo;
        class Base {
          static class Inner {
            static class ServiceLoader<T> { }
          }
        }
        """)).isEmpty());
  }

  /**
   * Precedence (item M, JLS 6.4.1): a single-type import outranks a
   * same-package type, so an imported superclass without a ServiceLoader
   * member leaves the wildcard-supplied JDK class attributed.
   */
  @Test
  void singleTypeImportedSuperclassNameOutranksSamePackage() throws IOException {
    assertFalse(matrixSites("matrix-cell-import-precedence", """
        package demo;
        import java.util.*;
        import other.Base;
        class Bridge extends Base {
          void audit() { ServiceLoader.load(Provider.class); }
        }
        """, Map.of(
            "demo/Base.java", """
        package demo;
        class Base {
          static class ServiceLoader<T> { }
        }
        """,
            "other/Base.java", """
        package other;
        public class Base { }
        """)).isEmpty());
  }

  /**
   * Guard (green at head and after item M): a single-type import that does
   * resolve the superclass keeps the inherited-type abstention.
   */
  @Test
  void singleTypeImportedSuperclassWithMemberTypeShadows() throws IOException {
    assertTrue(matrixSites("matrix-cell-import-shadow", """
        package demo;
        import java.util.*;
        import other.Base;
        class Bridge extends Base {
          void audit() { ServiceLoader.load(Provider.class); }
        }
        """, Map.of("other/Base.java", """
        package other;
        public class Base {
          public static class ServiceLoader<T> { }
        }
        """)).isEmpty());
  }

  /**
   * Qualified superclass name resolving to a selected source file (item N):
   * {@code Bridge extends demo.Base} inherits demo.Base's member type, so the
   * custom loader shadows the wildcard import and the site abstains.
   */
  @Test
  void qualifiedSuperclassNameIsResolved() throws IOException {
    String id = "matrix-cell-qualified-super";
    assertTrue(matrixSites(id, """
        package demo;
        import java.util.*;
        class Bridge extends demo.Base {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of("demo/Base.java", """
        package demo;
        class Base {
          static class ServiceLoader<T> {
            static <S> ServiceLoader<S> load(Class<S> type) { return null; }
          }
        }
        """)).isEmpty());
    assertCompiles(temp.resolve(id));
  }

  /**
   * Deferred residual (item N): a qualified superclass whose package has no
   * selected source file keeps the pre-existing attribution. Removing it is
   * policy work (FLPATH-5124), not this bounded textual resolution.
   */
  @Test
  void unselectedQualifiedSuperclassNameStaysAttributed() throws IOException {
    assertFalse(matrixSites("matrix-cell-qualified-external", """
        package demo;
        import java.util.*;
        class Bridge extends external.Base {
          void audit() { ServiceLoader.load(Provider.class); }
        }
        """).isEmpty());
  }

  /**
   * JLS 6.4.1 (CodeRabbit 4614619867): a superclass resolved through an
   * on-demand import ({@code import support.*;}) can inherit a ServiceLoader
   * member type. The on-demand branch must actually see the wildcard import.
   */
  @Test
  void onDemandImportSuperclassNameShadows() throws IOException {
    assertTrue(matrixSites("matrix-cell-on-demand-super", """
        package demo;
        import java.util.*;
        import support.*;
        class Bridge extends Base {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of("support/Base.java", """
        package support;
        public class Base {
          public static class ServiceLoader<T> { }
        }
        """)).isEmpty());
  }

  /**
   * Compiles every Java source written under the fixture root, proving the
   * fixture is complete Java rather than a text shape only.
   */
  private static void assertCompiles(Path fixtureRoot) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "a JDK compiler is required for this fixture");
    List<Path> sources;
    try (Stream<Path> paths = Files.walk(fixtureRoot.resolve("src/main/java"))) {
      sources = paths.filter(path -> path.toString().endsWith(".java")).toList();
    }
    Path classes = fixtureRoot.resolve("classes");
    Files.createDirectories(classes);
    try (StandardJavaFileManager manager =
        compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
      Iterable<? extends JavaFileObject> units = manager.getJavaFileObjectsFromPaths(sources);
      boolean compiled = compiler.getTask(
          null, manager, null,
          List.of("-proc:none", "-d", classes.toString()), null, units).call();
      assertTrue(compiled, "fixture compiles: " + sources);
    }
  }

  private List<ServiceLoaderCallSite> matrixSites(
      String id, String bridgeSource) throws IOException {
    return matrixSites(id, bridgeSource, Map.of());
  }

  private List<ServiceLoaderCallSite> matrixSites(
      String id, String bridgeSource, Map<String, String> extraFiles) throws IOException {
    Path root = temp.resolve(id);
    Path sourceRoot = root.resolve("src/main/java");
    Path bridge = sourceRoot.resolve("demo/Bridge.java");
    Files.createDirectories(bridge.getParent());
    Files.writeString(bridge, bridgeSource);
    for (Map.Entry<String, String> entry : extraFiles.entrySet()) {
      Path extra = sourceRoot.resolve(entry.getKey());
      Files.createDirectories(extra.getParent());
      Files.writeString(extra, entry.getValue());
    }
    AnalysisContext context = AnalysisContext.builder()
        .id(id)
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("matrix-test")
        .javaRelease(17)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
    return ServiceLoaderSourceCache.source(
        new SourceMetadataScanner(context), new MethodSymbol("demo.Bridge", "audit", "()V"))
        .orElseThrow().sites();
  }
}
