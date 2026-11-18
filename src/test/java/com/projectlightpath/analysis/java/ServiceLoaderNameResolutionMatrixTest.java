package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

  /** Cell 1: package member vs on-demand import (JLS 6.5.5, corrected). */
  @Test
  void onDemandImportVersusSamePackageTypeInAnotherFileAbstains() throws IOException {
    // JLS 6.5.5: a type-import-on-demand shadows a same-package type declared
    // in ANOTHER compilation unit, so the simple name resolves to the JDK
    // class here. The adapter abstains instead: an explicitly pinned accepted
    // over-approximation in the safe direction. Attribution tightening is a
    // possible future change and must not ride a matrix pin.
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
