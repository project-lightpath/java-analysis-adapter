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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
  private String previousResolver;

  @BeforeEach
  void useTextualResolver() {
    previousResolver = System.getProperty(JavacServiceLoaderSiteResolver.PROPERTY);
    System.setProperty(JavacServiceLoaderSiteResolver.PROPERTY, "textual");
  }

  @AfterEach
  void restoreResolver() {
    if (previousResolver == null) {
      System.clearProperty(JavacServiceLoaderSiteResolver.PROPERTY);
    } else {
      System.setProperty(JavacServiceLoaderSiteResolver.PROPERTY, previousResolver);
    }
  }

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
