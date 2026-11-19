package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
            public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
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
              public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
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
            public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
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
            public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
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
            public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
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
            public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
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
            public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
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
            public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
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
    String id = "matrix-cell-on-demand-super";
    assertTrue(matrixSites(id, """
        package demo;
        import java.util.*;
        import support.*;
        class Bridge extends Base {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of("support/Base.java", """
        package support;
        public class Base {
          public static class ServiceLoader<T> {
            public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
          }
        }
        """)).isEmpty());
    assertCompiles(temp.resolve(id));
  }

  /**
   * Malformed-form robustness (review probe): a dotless import
   * ({@code import ServiceLoader;}) must not abort the scan. It is not a
   * valid single-type import, so it cannot make the superclass resolvable.
   */
  @Test
  void dotlessImportDoesNotAbortScan() throws IOException {
    assertFalse(matrixSites("matrix-cell-dotless-import", """
        package demo;
        import ServiceLoader;
        import java.util.*;
        class Bridge extends ServiceLoader {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """).isEmpty());
  }

  /**
   * JLS 3.6/6.4.1 (review probe): whitespace around dots in an import is
   * legal, so the on-demand superclass resolution must still see it.
   */
  @Test
  void spacedOnDemandImportSuperclassNameShadows() throws IOException {
    assertTrue(matrixSites("matrix-cell-spaced-on-demand", """
        package demo;
        import java.util.*;
        import support . * ;
        class Bridge extends Base {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of("support/Base.java", """
        package support;
        public class Base {
          public static class ServiceLoader<T> {
            public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
          }
        }
        """)).isEmpty());
  }

  /**
   * Unicode package names are legal identifiers; a wildcard import of one must
   * be captured like an ASCII import.
   */
  @Test
  void unicodePackageOnDemandImportSuperclassNameShadows() throws IOException {
    assertTrue(matrixSites("matrix-cell-unicode-package", """
        package demo;
        import java.util.*;
        import suppōrt.*;
        class Bridge extends Base {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of("suppōrt/Base.java", """
        package suppōrt;
        public class Base {
          public static class ServiceLoader<T> {
            public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
          }
        }
        """)).isEmpty());
  }

  /**
   * JLS 6.4.1 precedence with spaced imports: the single-type import outranks
   * the same-package type even when it is written with spaces around the dot.
   */
  @Test
  void spacedSingleTypeImportOutranksSamePackage() throws IOException {
    assertTrue(matrixSites("matrix-cell-spaced-single-import", """
        package demo;
        import java.util.*;
        import other . Base ;
        class Bridge extends Base {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of(
            "demo/Base.java", """
        package demo;
        class Base { }
        """,
            "other/Base.java", """
        package other;
        public class Base {
          public static class ServiceLoader<T> {
            public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
          }
        }
        """)).isEmpty());
  }

  /**
   * Guard (green at head and after the wildcard fix): a same-package
   * superclass without the member type outranks an on-demand import whose
   * Base does declare one, so the site stays attributed.
   */
  @Test
  void samePackageSuperclassWithoutMemberTypeOutranksOnDemandImport() throws IOException {
    assertFalse(matrixSites("matrix-cell-same-package-wins", """
        package demo;
        import java.util.*;
        import support.*;
        class Bridge extends Base {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of(
            "demo/Base.java", """
        package demo;
        class Base { }
        """,
            "support/Base.java", """
        package support;
        public class Base {
          public static class ServiceLoader<T> {
            public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
          }
        }
        """)).isEmpty());
  }

  /**
   * A spaced foreign single-type import must defeat wildcard ownership
   * (peer-review probe; head attributes it to java.util).
   */
  @Test
  void spacedForeignSingleTypeImportAbstains() throws IOException {
    assertTrue(matrixSites("matrix-cell-spaced-foreign", """
        package demo;
        import java.util.*;
        import other . ServiceLoader;
        class Bridge {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of("other/ServiceLoader.java", """
        package other;
        public class ServiceLoader<T> {
          public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
        }
        """)).isEmpty());
  }

  /** A private member type is not inherited, so it must not shadow. */
  @Test
  void privateNestedServiceLoaderDoesNotShadow() throws IOException {
    assertFalse(matrixSites("matrix-cell-private-nested", """
        package demo;
        import java.util.ServiceLoader;
        class Bridge extends Base {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of("demo/Base.java", """
        package demo;
        class Base {
          private static class ServiceLoader<T> { }
        }
        """)).isEmpty());
  }

  /** A legal `$` in a package name must parse so wildcard resolution works. */
  @Test
  void packageDollarNameIsParsed() throws IOException {
    assertTrue(matrixSites("matrix-cell-dollar-package", """
        package demo;
        import java.util.*;
        import a$b.*;
        class Bridge extends Base {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of("a$b/Base.java", """
        package a$b;
        public class Base {
          public static class ServiceLoader<T> {
            public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
          }
        }
        """)).isEmpty());
  }

  /** Unicode type names are legal identifiers and must resolve across files. */
  @Test
  void unicodeTypeNameIsResolved() throws IOException {
    assertTrue(matrixSites("matrix-cell-unicode-type", """
        package demo;
        import java.util.*;
        class Bridge extends Bāse {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of("demo/Bāse.java", """
        package demo;
        public class Bāse {
          public static class ServiceLoader<T> {
            public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
          }
        }
        """)).isEmpty());
  }

  /** A letter-number package start (Ⅻ) is a legal Java identifier start. */
  @Test
  void letterNumberPackageIsParsed() throws IOException {
    assertTrue(matrixSites("matrix-cell-letter-number-package", """
        package demo;
        import java.util.*;
        import Ⅻp.*;
        class Bridge extends Base {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of("Ⅻp/Base.java", """
        package Ⅻp;
        public class Base {
          public static class ServiceLoader<T> {
            public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
          }
        }
        """)).isEmpty());
  }

  /** A Unicode-typed value named ServiceLoader shadows, as for ASCII types. */
  @Test
  void unicodeTypedValueDeclarationShadows() throws IOException {
    assertTrue(matrixSites("matrix-cell-unicode-typed-value", """
        package demo;
        import java.util.*;
        class Bridge {
          void audit() { Данные ServiceLoader = null; ServiceLoader.load(Object.class); }
        }
        """).isEmpty());
  }

  /**
   * A private member type is not inherited even when an annotation with an
   * array initializer precedes the modifiers.
   */
  @Test
  void privateTypeAfterArrayAnnotationDoesNotShadow() throws IOException {
    assertFalse(matrixSites("matrix-cell-private-annotation", """
        package demo;
        import java.util.ServiceLoader;
        class Bridge extends Base {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of("demo/Base.java", """
        package demo;
        class Base {
          @Marker({Base.class}) private static class ServiceLoader<T> { }
        }
        """)).isEmpty());
  }

  /**
   * A static on-demand import of a type that declares a member ServiceLoader
   * collides with `java.util.*` (javac: ambiguous), so ownership is unprovable.
   */
  @Test
  void staticOnDemandLookalikeMakesWildcardAmbiguous() throws IOException {
    assertTrue(matrixSites("matrix-cell-static-ambiguous", """
        package demo;
        import java.util.*;
        import static other.Outer.*;
        class Bridge {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of("other/Outer.java", """
        package other;
        public class Outer {
          public static class ServiceLoader<T> {
            public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
          }
        }
        """)).isEmpty());
  }

  /** Unicode inside generic type arguments must not defeat a value shadow. */
  @Test
  void unicodeGenericArgumentValueDeclarationShadows() throws IOException {
    assertTrue(matrixSites("matrix-cell-unicode-generic-value", """
        package demo;
        import java.util.*;
        class Bridge {
          void audit() { Registry<Данные> ServiceLoader = null; ServiceLoader.load(Object.class); }
        }
        """).isEmpty());
  }

  /** A package-qualified Unicode superclass reference resolves. */
  @Test
  void qualifiedUnicodeSuperclassNameIsResolved() throws IOException {
    assertTrue(matrixSites("matrix-cell-qualified-unicode", """
        package demo;
        import java.util.*;
        class Bridge extends Ⅻp.Base {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of("Ⅻp/Base.java", """
        package Ⅻp;
        public class Base {
          public static class ServiceLoader<T> {
            public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
          }
        }
        """)).isEmpty());
  }

  /** Item S: a value named java turns the qualified spelling into a value chain. */
  @Test
  void javaParameterDropsQualifiedSite() throws IOException {
    assertTrue(matrixSites("matrix-cell-java-parameter", """
        package demo;
        import java.util.*;
        class Bridge {
          void audit(Util java) { java.util.ServiceLoader.load(Object.class); }
        }
        """).isEmpty());
  }

  @Test
  void javaLocalDeclaredAfterTheSiteKeepsIt() throws IOException {
    assertFalse(matrixSites("matrix-cell-java-local-after", """
        package demo;
        import java.util.*;
        class Bridge {
          void audit() { java.util.ServiceLoader.load(Object.class); Util java = null; }
        }
        """).isEmpty());
  }

  @Test
  void javaFieldDropsQualifiedSite() throws IOException {
    assertTrue(matrixSites("matrix-cell-java-field", """
        package demo;
        import java.util.*;
        class Bridge {
          void audit() { java.util.ServiceLoader.load(Object.class); }
          Util java;
        }
        """).isEmpty());
  }

  @Test
  void javaLambdaParameterDropsOnlyInsideTheLambda() throws IOException {
    assertEquals(1, matrixSites("matrix-cell-java-lambda", """
        package demo;
        import java.util.*;
        class Bridge {
          void audit() {
            java.util.function.Consumer<Util> consumer = java ->
                java.util.ServiceLoader.load(Object.class);
            java.util.ServiceLoader.load(Object.class);
          }
        }
        """).size());
  }

  @Test
  void switchArmJavaPatternDropsOnlyThatArm() throws IOException {
    assertEquals(1, matrixSites("matrix-cell-java-switch", """
        package demo;
        import java.util.*;
        class Bridge {
          void audit(Object value) {
            switch (value) {
              case Util java -> java.util.ServiceLoader.load(Object.class);
              default -> java.util.ServiceLoader.load(Object.class);
            }
          }
        }
        """).size());
  }

  @Test
  void samePackageTypeJavaDropsQualifiedSite() throws IOException {
    assertTrue(matrixSites("matrix-cell-java-type-same-package", """
        package demo;
        import java.util.*;
        class Bridge {
          void audit() { java.util.ServiceLoader.load(Object.class); }
        }
        """, Map.of("demo/java.java", """
        package demo;
        public class java {
          public static class util { }
        }
        """)).isEmpty());
  }

  @Test
  void importedTypeJavaDropsQualifiedSite() throws IOException {
    assertTrue(matrixSites("matrix-cell-java-type-import", """
        package demo;
        import java.util.*;
        import other.java;
        class Bridge {
          void audit() { java.util.ServiceLoader.load(Object.class); }
        }
        """, Map.of("other/java.java", """
        package other;
        public class java {
          public static class util { }
        }
        """)).isEmpty());
  }

  @Test
  void bareServiceLoaderIsUnaffectedByAJavaValue() throws IOException {
    assertEquals(1, matrixSites("matrix-cell-java-bare", """
        package demo;
        import java.util.ServiceLoader;
        class Bridge {
          void audit(Util java) { ServiceLoader.load(Object.class); }
        }
        """).size());
  }

  @Test
  void controlNameIsNotJava() throws IOException {
    assertEquals(1, matrixSites("matrix-cell-java-control", """
        package demo;
        import java.util.*;
        class Bridge {
          void audit(Util myjava) { java.util.ServiceLoader.load(Object.class); }
        }
        """).size());
  }

  /** A single-type import of java proves a type named java exists. */
  @Test
  void importedTypeJavaWithoutSelectedSourceDropsQualifiedSite() throws IOException {
    assertTrue(matrixSites("matrix-cell-java-type-import-unselected", """
        package demo;
        import java.util.*;
        import other.java;
        class Bridge {
          void audit() { java.util.ServiceLoader.load(Object.class); }
        }
        """).isEmpty());
  }

  /** A lambda parameter list element named java is in scope in the body. */
  @Test
  void multiParameterLambdaJavaDropsQualifiedSite() throws IOException {
    assertTrue(matrixSites("matrix-cell-java-lambda-list", """
        package demo;
        import java.util.*;
        class Bridge {
          void audit() {
            java.util.function.BiConsumer<Util, Util> consumer = (java, other) ->
                java.util.ServiceLoader.load(Object.class);
          }
        }
        """).isEmpty());
  }

  /** The same parameter-list gap for a lambda parameter named ServiceLoader. */
  @Test
  void multiParameterLambdaServiceLoaderDropsBareSite() throws IOException {
    assertTrue(matrixSites("matrix-cell-service-loader-lambda-list", """
        package demo;
        import java.util.ServiceLoader;
        class Bridge {
          void audit() {
            java.util.function.BiConsumer<Util, Util> consumer = (ServiceLoader, other) ->
                ServiceLoader.load(Object.class);
          }
        }
        """).isEmpty());
  }

  /** Identifier-ignorable characters inside an import name are not semantic. */
  @Test
  void ignorableCharacterInImportNameAbstains() throws IOException {
    assertTrue(matrixSites("matrix-cell-ignorable-import", """
        package demo;
        import java.util.*;
        import other.Service\u200CLoader;
        class Bridge {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of("other/ServiceLoader.java", """
        package other;
        public class ServiceLoader<T> {
          public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
        }
        """)).isEmpty());
  }

  /** A later declarator (`Type unused, ServiceLoader = ...`) declares the name. */
  @Test
  void laterDeclaratorNamedServiceLoaderShadows() throws IOException {
    assertTrue(matrixSites("matrix-cell-later-declarator", """
        package demo;
        import java.util.*;
        class Bridge {
          void audit() {
            Helper unused, ServiceLoader = new Helper();
            ServiceLoader.load(Object.class);
          }
        }
        """).isEmpty());
  }

  /** A top-level ServiceLoader in a differently named same-package file shadows. */
  @Test
  void samePackageTopLevelInDifferentlyNamedFileShadows() throws IOException {
    assertTrue(matrixSites("matrix-cell-multi-top-level", """
        package demo;
        import java.util.*;
        class Bridge {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of("demo/Helpers.java", """
        package demo;
        class Helpers { }
        class ServiceLoader {
          static <S> ServiceLoader<S> load(Class<S> type) { return null; }
        }
        """)).isEmpty());
  }

  /** Whitespace around package dots does not change the package name. */
  @Test
  void spacedPackageDeclarationStillResolves() throws IOException {
    assertTrue(matrixSites("matrix-cell-spaced-package", """
        package demo;
        import java.util.*;
        import support.api.*;
        class Bridge extends Base {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of("support/api/Base.java", """
        package support . api;
        public class Base {
          public static class ServiceLoader<T> {
            public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
          }
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

  // ---- Issue #9: static on-demand owner matching (a) ----

  /** A1: an unrelated same-simple-name type must not create wildcard ambiguity. */
  @Test
  void staticOnDemandUnrelatedOwnerDoesNotAbstain() throws IOException {
    assertFalse(matrixSitesIn("matrix-cell-static-unrelated-owner", "demo", """
        package demo;
        import java.util.*;
        import static a.Tools.*;
        class Bridge {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of(
            "a/Tools.java", """
        package a;
        public class Tools {
          public static void helper() { }
        }
        """,
            "b/Tools.java", """
        package b;
        public class Tools {
          public static class ServiceLoader<T> {
            public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
          }
        }
        """)).isEmpty());
  }

  /** A2: the named owner still makes the wildcard ambiguous. */
  @Test
  void staticOnDemandOwnerMatchWithLookalikeStaysAmbiguous() throws IOException {
    assertTrue(matrixSitesIn("matrix-cell-static-owner-lookalike", "demo", """
        package demo;
        import java.util.*;
        import static a.Tools.*;
        class Bridge {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of(
            "a/Tools.java", """
        package a;
        public class Tools {
          public static class ServiceLoader<T> {
            public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
          }
        }
        """,
            "b/Tools.java", """
        package b;
        public class Tools {
          public static class ServiceLoader<T> {
            public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
          }
        }
        """)).isEmpty());
  }

  /** A4: a package-private member is not accessible through a cross-package static import. */
  @Test
  void staticOnDemandPackagePrivateMemberCrossPackageDoesNotAbstain() throws IOException {
    assertFalse(matrixSitesIn("matrix-cell-static-package-private", "demo", """
        package demo;
        import java.util.*;
        import static a.Tools.*;
        class Bridge {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of("a/Tools.java", """
        package a;
        public class Tools {
          public static void helper() { }
          static class ServiceLoader<T> { }
        }
        """)).isEmpty());
  }

  /** A5: the same package-private member is accessible from its own package. */
  @Test
  void staticOnDemandSamePackagePackagePrivateMemberStaysAmbiguous() throws IOException {
    assertTrue(matrixSitesIn("matrix-cell-static-package-private-same", "a", """
        package a;
        import java.util.*;
        import static a.Tools.*;
        class Bridge {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of("a/Tools.java", """
        package a;
        public class Tools {
          public static void helper() { }
          static class ServiceLoader<T> { }
        }
        """)).isEmpty());
  }

  /** A6: a private member is never importable, so it cannot create ambiguity. */
  @Test
  void staticOnDemandPrivateMemberDoesNotAbstain() throws IOException {
    assertFalse(matrixSitesIn("matrix-cell-static-private-member", "demo", """
        package demo;
        import java.util.*;
        import static a.Tools.*;
        class Bridge {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of("a/Tools.java", """
        package a;
        public class Tools {
          public static void helper() { }
          private static class ServiceLoader<T> { }
        }
        """)).isEmpty());
  }

  /** A7: protected cross-package static import over-abstains (documented residual). */
  @Test
  void staticOnDemandProtectedMemberCrossPackageStaysAmbiguous() throws IOException {
    assertTrue(matrixSitesIn("matrix-cell-static-protected-member", "demo", """
        package demo;
        import java.util.*;
        import static a.Tools.*;
        class Bridge {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of("a/Tools.java", """
        package a;
        public class Tools {
          public static void helper() { }
          protected static class ServiceLoader<T> { }
        }
        """)).isEmpty());
  }

  // ---- Issue #9: inherited member-type accessibility (b) ----

  private static String crossPackageBase(String memberModifiers) {
    return """
        package other;
        public class Base {
          %s static class ServiceLoader<T> { }
        }
        """.formatted(memberModifiers);
  }

  private static final String SINGLE_TYPE_IMPORT_BRIDGE = """
      package demo;
      import java.util.*;
      import other.Base;
      class Bridge extends Base {
        void audit() { ServiceLoader.load(Object.class); }
      }
      """;

  private static final String ON_DEMAND_IMPORT_BRIDGE = """
      package demo;
      import java.util.*;
      import other.*;
      class Bridge extends Base {
        void audit() { ServiceLoader.load(Object.class); }
      }
      """;

  private static final String QUALIFIED_SUPERCLASS_BRIDGE = """
      package demo;
      import java.util.*;
      class Bridge extends other.Base {
        void audit() { ServiceLoader.load(Object.class); }
      }
      """;

  /** B5 via a single-type import: package-private cross-package member is not inherited. */
  @Test
  void crossPackagePackagePrivateMemberViaSingleTypeImportIsNotInherited() throws IOException {
    assertFalse(matrixSitesIn("matrix-cell-cross-package-private-import", "demo",
        SINGLE_TYPE_IMPORT_BRIDGE,
        Map.of("other/Base.java", crossPackageBase(""))).isEmpty());
  }

  /** B5 via an on-demand import. */
  @Test
  void crossPackagePackagePrivateMemberViaOnDemandImportIsNotInherited() throws IOException {
    assertFalse(matrixSitesIn("matrix-cell-cross-package-private-demand", "demo",
        ON_DEMAND_IMPORT_BRIDGE,
        Map.of("other/Base.java", crossPackageBase(""))).isEmpty());
  }

  /** B5 via a package-qualified superclass. */
  @Test
  void crossPackagePackagePrivateMemberViaQualifiedSuperclassIsNotInherited() throws IOException {
    assertFalse(matrixSitesIn("matrix-cell-cross-package-private-qualified", "demo",
        QUALIFIED_SUPERCLASS_BRIDGE,
        Map.of("other/Base.java", crossPackageBase(""))).isEmpty());
  }

  /** B4 via a single-type import: protected cross-package member is inherited. */  @Test
  void crossPackageProtectedMemberViaSingleTypeImportIsInherited() throws IOException {
    assertTrue(matrixSitesIn("matrix-cell-cross-package-protected-import", "demo",
        SINGLE_TYPE_IMPORT_BRIDGE,
        Map.of("other/Base.java", crossPackageBase("protected"))).isEmpty());
  }

  /** B4 via an on-demand import. */
  @Test
  void crossPackageProtectedMemberViaOnDemandImportIsInherited() throws IOException {
    assertTrue(matrixSitesIn("matrix-cell-cross-package-protected-demand", "demo",
        ON_DEMAND_IMPORT_BRIDGE,
        Map.of("other/Base.java", crossPackageBase("protected"))).isEmpty());
  }

  /** B4 via a package-qualified superclass. */
  @Test
  void crossPackageProtectedMemberViaQualifiedSuperclassIsInherited() throws IOException {
    assertTrue(matrixSitesIn("matrix-cell-cross-package-protected-qualified", "demo",
        QUALIFIED_SUPERCLASS_BRIDGE,
        Map.of("other/Base.java", crossPackageBase("protected"))).isEmpty());
  }

  // ---- Issue #9 follow-up: interface members and unselected owners ----

  private static String crossPackageInterfaceBase() {
    return """
        package other;
        public interface Base {
          class ServiceLoader<T> { }
        }
        """;
  }

  private static final String INTERFACE_SINGLE_TYPE_IMPORT_BRIDGE = """
      package demo;
      import java.util.*;
      import other.Base;
      interface Bridge extends Base {
        default void audit() { ServiceLoader.load(Object.class); }
      }
      """;

  private static final String INTERFACE_ON_DEMAND_IMPORT_BRIDGE = """
      package demo;
      import java.util.*;
      import other.*;
      interface Bridge extends Base {
        default void audit() { ServiceLoader.load(Object.class); }
      }
      """;

  private static final String INTERFACE_QUALIFIED_SUPERCLASS_BRIDGE = """
      package demo;
      import java.util.*;
      interface Bridge extends other.Base {
        default void audit() { ServiceLoader.load(Object.class); }
      }
      """;

  /** JLS 9.3: an unmodified member type of an interface is implicitly public. */
  @Test
  void interfaceMemberViaSingleTypeImportIsInherited() throws IOException {
    assertTrue(matrixSitesIn("matrix-cell-interface-member-import", "demo",
        INTERFACE_SINGLE_TYPE_IMPORT_BRIDGE,
        Map.of("other/Base.java", crossPackageInterfaceBase())).isEmpty());
  }

  @Test
  void interfaceMemberViaOnDemandImportIsInherited() throws IOException {
    assertTrue(matrixSitesIn("matrix-cell-interface-member-demand", "demo",
        INTERFACE_ON_DEMAND_IMPORT_BRIDGE,
        Map.of("other/Base.java", crossPackageInterfaceBase())).isEmpty());
  }

  @Test
  void interfaceMemberViaQualifiedSuperclassIsInherited() throws IOException {
    assertTrue(matrixSitesIn("matrix-cell-interface-member-qualified", "demo",
        INTERFACE_QUALIFIED_SUPERCLASS_BRIDGE,
        Map.of("other/Base.java", crossPackageInterfaceBase())).isEmpty());
  }

  /** An unselected static-import owner with a selected lookalike stays unproven. */
  @Test
  void staticOnDemandUnselectedOwnerWithLookalikeAbstains() throws IOException {
    assertTrue(matrixSitesIn("matrix-cell-static-unselected-owner", "demo", """
        package demo;
        import java.util.*;
        import static a.Tools.*;
        class Bridge {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of("b/Tools.java", """
        package b;
        public class Tools {
          public static void helper() { }
        }
        """)).isEmpty());
  }

  /** No selected same-simple-name type, so the unselected owner adds no doubt. */
  @Test
  void staticOnDemandUnselectedOwnerWithoutLookalikeStaysAttributed() throws IOException {
    assertFalse(matrixSitesIn("matrix-cell-static-unselected-owner-plain", "demo", """
        package demo;
        import java.util.*;
        import static a.Tools.*;
        class Bridge {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of()).isEmpty());
  }

  // ---- Issue #9: sourceFile owner '$' matching (c) ----

  private static final String DOLLAR_FLOW_SOURCE = """
      package demo;
      import java.util.*;

      class Cash$Flow {
        void audit() {
          ServiceLoader.load(Object.class);
        }
      }
      """;

  private static final String BRIDGE_SOURCE = """
      package demo;
      import java.util.*;
      class Bridge {
        void audit() { ServiceLoader.load(Object.class); }
      }
      """;

  @Test
  void topLevelDollarNameIsResolvedExactly() throws IOException {
    String id = "matrix-cell-owner-dollar-top";
    Map<String, String> files = Map.of(
        "demo/Cash$Flow.java", DOLLAR_FLOW_SOURCE,
        "demo/Bridge.java", BRIDGE_SOURCE);
    assertFalse(matrixSitesForOwner(id, "demo.Cash$Flow", files).isEmpty());
    assertTrue(sourceFileOf(id, files, "demo.Bridge").isPresent());
  }

  @Test
  void leadingDollarTopLevelNameIsResolved() throws IOException {
    String id = "matrix-cell-owner-leading-dollar";
    Optional<SourceMetadataScanner.SourceFile> file = sourceFileOf(id, Map.of(
        "demo/$Flow.java", "package demo;\nclass $Flow { }\n"), "demo.$Flow");
    assertTrue(file.isPresent());
    assertTrue(file.orElseThrow().path().toString().endsWith("$Flow.java"));
  }

  @Test
  void ordinaryNestedOwnerFallsBackToTopLevel() throws IOException {
    String id = "matrix-cell-owner-ordinary-nested";
    Optional<SourceMetadataScanner.SourceFile> file = sourceFileOf(id, Map.of(
        "demo/Outer.java", "package demo;\nclass Outer { class Inner { } }\n"),
        "demo.Outer$Inner");
    assertTrue(file.isPresent());
    assertTrue(file.orElseThrow().path().toString().endsWith("Outer.java"));
  }

  @Test
  void nestedInsideDollarNamedTopLevelIsResolved() throws IOException {
    String id = "matrix-cell-owner-dollar-nested";
    Optional<SourceMetadataScanner.SourceFile> file = sourceFileOf(id, Map.of(
        "demo/Cash$Flow.java", "package demo;\nclass Cash$Flow { class Inner { } }\n"),
        "demo.Cash$Flow$Inner");
    assertTrue(file.isPresent());
    assertTrue(file.orElseThrow().path().toString().endsWith("Cash$Flow.java"));
  }

  @Test
  void unknownNestedOwnerIsUnresolved() throws IOException {
    assertTrue(sourceFileOf("matrix-cell-owner-missing", Map.of(
        "demo/Outer.java", "package demo;\nclass Outer { }\n"),
        "demo.Missing$Inner").isEmpty());
  }

  @Test
  void plainOwnerIsResolved() throws IOException {
    assertTrue(sourceFileOf("matrix-cell-owner-plain", Map.of(
        "demo/Plain.java", "package demo;\nclass Plain { }\n"), "demo.Plain").isPresent());
  }

  @Test
  void dollarInPackageNameIsNotTruncated() throws IOException {
    String id = "matrix-cell-owner-dollar-package";
    Map<String, String> files = Map.of(
        "a$b/Service.java", "package a$b;\nclass Service { }\n",
        "a$b/Outer.java", "package a$b;\nclass Outer { class Inner { } }\n");
    assertTrue(sourceFileOf(id, files, "a$b.Service").isPresent());
    assertTrue(sourceFileOf(id, files, "a$b.Outer$Inner").isPresent());
    assertTrue(sourceFileOf(id, files, "a$b.Outer").isPresent());
  }

  @Test
  void missingDollarOwnerNeverResolvesDecoyDefaultPackageType() throws IOException {
    String id = "matrix-cell-owner-decoy";
    Map<String, String> files = Map.of("a.java", "class a { }\n");
    assertTrue(sourceFileOf(id, files, "a").isPresent(), "decoy must be selected");
    assertTrue(sourceFileOf(id, files, "a$b.Missing$Inner").isEmpty());
  }

  @Test
  void dollarBoundaryEdgesAreResolved() throws IOException {
    String id = "matrix-cell-owner-boundary";
    Map<String, String> files = Map.of(
        "demo/X.java", "package demo;\nclass X { }\n",
        "demo/Outer.java", "package demo;\nclass Outer { class I { } class $Inner { } }\n");
    assertTrue(sourceFileOf(id, files, "demo.X").isPresent(), "single-character segment");
    assertTrue(sourceFileOf(id, files, "demo.Outer$I").isPresent(), "single-character nested");
    assertTrue(sourceFileOf(id, files, "demo.Outer$$Inner").isPresent(), "adjacent $ components");
  }

  @Test
  void declaredTypeResolvesDollarNamedTopLevel() throws IOException {
    SourceMetadataScanner scanner = scannerFor("matrix-cell-owner-declared",
        Map.of("demo/Cash$Flow.java", DOLLAR_FLOW_SOURCE));
    assertTrue(scanner.declaredType("demo.Cash$Flow").isPresent());
  }

  @Test
  void methodSourceResolvesDollarNamedTopLevel() throws IOException {
    SourceMetadataScanner scanner = scannerFor("matrix-cell-owner-method",
        Map.of("demo/Cash$Flow.java", DOLLAR_FLOW_SOURCE));
    Optional<String> body = scanner.methodSource(
        new MethodSymbol("demo.Cash$Flow", "audit", "()V"), 5);
    assertTrue(body.isPresent());
    assertTrue(body.orElseThrow().contains("ServiceLoader.load"));
  }

  @Test
  void sourceLineContainsResolvesDollarNamedTopLevel() throws IOException {
    SourceMetadataScanner scanner = scannerFor("matrix-cell-owner-source-line",
        Map.of("demo/Cash$Flow.java", DOLLAR_FLOW_SOURCE));
    assertTrue(scanner.sourceLineContains(
        new MethodSymbol("demo.Cash$Flow", "audit", "()V"), 6, "ServiceLoader"));
  }

  // ---- PR #12 CodeRabbit findings ----

  /** Annotation names may contain `$`; masking must still expose `public`. */
  @Test
  void dollarNamedAnnotationArgumentDoesNotHidePublicMemberType() throws IOException {
    assertTrue(matrixSitesIn("matrix-cell-dollar-annotation", "demo", """
        package demo;
        import java.util.*;
        import other.Base;
        class Bridge extends Base {
          void audit() { ServiceLoader.load(Object.class); }
        }
        """, Map.of("other/Base.java", """
        package other;
        public class Base {
          public @$Mark({Object.class}) static class ServiceLoader<T> { }
          @interface $Mark { Class<?>[] value(); }
        }
        """)).isEmpty());
  }

  /**
   * A nested owner must not bind to an unrelated top-level type whose name is
   * only a prefix of the owner.
   */
  @Test
  void nestedOwnerWithDollarTopLevelPrefixPrefersDeclaringFile() throws IOException {
    String id = "matrix-cell-owner-prefix-decoy";
    Map<String, String> files = Map.of(
        "demo/Outer.java", "package demo;\nclass Outer { class Inner { class Leaf { } } }\n",
        "demo/Outer$Inner.java", "package demo;\nclass Outer$Inner { }\n");
    Optional<SourceMetadataScanner.SourceFile> file =
        sourceFileOf(id, files, "demo.Outer$Inner$Leaf");
    assertTrue(file.isPresent());
    assertTrue(file.orElseThrow().path().toString().endsWith("Outer.java"),
        "must not bind to the unrelated Outer$Inner.java");
  }

  @Test
  void anonymousOwnerResolvesEnclosingTopLevel() throws IOException {
    Optional<SourceMetadataScanner.SourceFile> file = sourceFileOf(
        "matrix-cell-owner-anonymous",
        Map.of("demo/Outer.java", "package demo;\nclass Outer { }\n"),
        "demo.Outer$1");
    assertTrue(file.isPresent());
    assertTrue(file.orElseThrow().path().toString().endsWith("Outer.java"));
  }

  @Test
  void nestedAnonymousOwnerResolvesEnclosingTopLevel() throws IOException {
    Optional<SourceMetadataScanner.SourceFile> file = sourceFileOf(
        "matrix-cell-owner-nested-anonymous",
        Map.of("demo/Outer.java", "package demo;\nclass Outer { class Inner { } }\n"),
        "demo.Outer$Inner$1");
    assertTrue(file.isPresent());
    assertTrue(file.orElseThrow().path().toString().endsWith("Outer.java"));
  }

  @Test
  void exactTopLevelDollarOwnerResolvesItself() throws IOException {
    Map<String, String> files = Map.of(
        "demo/Outer.java", "package demo;\nclass Outer { class Inner { } }\n",
        "demo/Outer$Inner.java", "package demo;\nclass Outer$Inner { }\n");
    Optional<SourceMetadataScanner.SourceFile> file =
        sourceFileOf("matrix-cell-owner-exact-dollar", files, "demo.Outer$Inner");
    assertTrue(file.isPresent());
    assertTrue(file.orElseThrow().path().toString().endsWith("Outer$Inner.java"));
  }

  private List<ServiceLoaderCallSite> matrixSites(
      String id, String bridgeSource) throws IOException {
    return matrixSites(id, bridgeSource, Map.of());
  }

  private List<ServiceLoaderCallSite> matrixSites(
      String id, String bridgeSource, Map<String, String> extraFiles) throws IOException {
    return matrixSitesIn(id, "demo", bridgeSource, extraFiles);
  }

  private List<ServiceLoaderCallSite> matrixSitesIn(
      String id, String bridgePackage, String bridgeSource,
      Map<String, String> extraFiles) throws IOException {
    Map<String, String> files = new LinkedHashMap<>(extraFiles);
    files.put(bridgePackage.replace('.', '/') + "/Bridge.java", bridgeSource);
    return matrixSitesForOwner(id, bridgePackage + ".Bridge", files);
  }

  private List<ServiceLoaderCallSite> matrixSitesForOwner(
      String id, String owner, Map<String, String> files) throws IOException {
    SourceMetadataScanner scanner = scannerFor(id, files);
    return ServiceLoaderSourceCache.source(
        scanner, new MethodSymbol(owner, "audit", "()V")).orElseThrow().sites();
  }

  private Optional<SourceMetadataScanner.SourceFile> sourceFileOf(
      String id, Map<String, String> files, String owner) throws IOException {
    return scannerFor(id, files).sourceFile(owner);
  }

  private SourceMetadataScanner scannerFor(String id, Map<String, String> files)
      throws IOException {
    Path root = temp.resolve(id);
    Path sourceRoot = root.resolve("src/main/java");
    for (Map.Entry<String, String> entry : files.entrySet()) {
      Path path = sourceRoot.resolve(entry.getKey());
      Files.createDirectories(path.getParent());
      Files.writeString(path, entry.getValue());
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
    return new SourceMetadataScanner(context);
  }
}


