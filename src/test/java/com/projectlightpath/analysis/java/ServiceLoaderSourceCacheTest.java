package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceLoaderSourceCacheTest {
  @TempDir Path temp;

  @Test
  void reusesOneSourceModelForRepeatedEdges() throws IOException {
    Path sourceRoot = temp.resolve("src/main/java");
    Path source = sourceRoot.resolve("demo/Bridge.java");
    Files.createDirectories(source.getParent());
    Files.writeString(source, "package demo; class Bridge { void audit() { } }\n");
    AnalysisContext context = AnalysisContext.builder()
        .id("service-loader-source-cache")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("cache-test")
        .javaRelease(17)
        .repositoryRoot(temp)
        .applicationClasses(temp.resolve("classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    MethodSymbol owner = new MethodSymbol("demo.Bridge", "audit", "()V");

    var first = ServiceLoaderSourceCache.source(scanner, owner).orElseThrow();
    var second = ServiceLoaderSourceCache.source(scanner, owner).orElseThrow();
    assertSame(first, second, "repeated edges must reuse the source model");
  }

  @Test
  void abstainsForNestedServiceLoaderShadowingWildcardImport() throws IOException {
    assertTrue(cachedSites("nested-service-loader-shadow", """
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

  @Test
  void abstainsForLocalServiceLoaderShadowingWildcardImport() throws IOException {
    assertTrue(cachedSites("local-service-loader-shadow", """
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

  @Test
  void allowsWildcardServiceLoaderWhenShadowTextIsOnlyCommentOrString() throws IOException {
    assertFalse(cachedSites("masked-service-loader-shadow", """
        package demo;
        import java.util.*;
        class Bridge {
          void audit() {
            String text = "class ServiceLoader {}";
            // class ServiceLoader { }
            ServiceLoader.load(Provider.class);
          }
        }
        """).isEmpty());
  }

  @Test
  void abstainsForNestedServiceLoaderShadowingExplicitImport() throws IOException {
    assertTrue(cachedSites("nested-explicit-service-loader-shadow", """
        package demo;
        import java.util.ServiceLoader;
        class Bridge {
          static class ServiceLoader<T> {
            static <S> ServiceLoader<S> load(Class<S> type) { return null; }
          }
          void audit() { ServiceLoader.load(Provider.class); }
        }
        """).isEmpty());
  }

  @Test
  void abstainsForLocalServiceLoaderShadowingExplicitImport() throws IOException {
    assertTrue(cachedSites("local-explicit-service-loader-shadow", """
        package demo;
        import java.util.ServiceLoader;
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

  @Test
  void scopesLocalServiceLoaderShadowingToItsEnclosingMethod() throws IOException {
    assertEquals(1, cachedSites("local-service-loader-shadow-in-helper", """
        package demo;
        import java.util.ServiceLoader;
        class Bridge {
          void audit() { ServiceLoader.load(Provider.class); }
          void helper() {
            class ServiceLoader<T> { }
            ServiceLoader.load(Provider.class);
          }
        }
        """).size());
  }

  @Test
  void preservesFullyQualifiedServiceLoaderWithLocalShadow() throws IOException {
    assertFalse(cachedSites("qualified-service-loader-shadow", """
        package demo;
        class Bridge {
          static class ServiceLoader<T> { }
          void audit() { java.util.ServiceLoader.load(Provider.class); }
        }
        """).isEmpty());
  }

  @Test
  void explicitImportWinsOverSamePackageTypeInAnotherFile() throws IOException {
    String id = "explicit-import-with-package-shadow";
    Path root = temp.resolve(id);
    Path sourceRoot = root.resolve("src/main/java");
    Path bridge = sourceRoot.resolve("demo/Bridge.java");
    Files.createDirectories(bridge.getParent());
    Files.writeString(bridge, """
        package demo;
        import java.util.ServiceLoader;
        class Bridge {
          void audit() { ServiceLoader.load(Provider.class); }
        }
        """);
    Files.writeString(sourceRoot.resolve("demo/ServiceLoader.java"), """
        package demo;
        class ServiceLoader<T> { }
        """);
    AnalysisContext context = AnalysisContext.builder()
        .id(id)
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("cache-shadow-test")
        .javaRelease(17)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();

    List<ServiceLoaderCallSite> sites = ServiceLoaderSourceCache.source(
        new SourceMetadataScanner(context), new MethodSymbol("demo.Bridge", "audit", "()V"))
        .orElseThrow().sites();
    assertFalse(sites.isEmpty());
  }

  @Test
  void abstainsForParameterNamedServiceLoader() throws IOException {
    assertTrue(cachedSites("parameter-service-loader-shadow", """
        package demo;
        import java.util.ServiceLoader;
        class Bridge {
          void audit(Helper ServiceLoader) { ServiceLoader.load(Provider.class); }
          static class Helper {
            static <T> T load(Class<T> type) { return null; }
          }
        }
        """).isEmpty());
  }

  @Test
  void abstainsForLocalVariableNamedServiceLoader() throws IOException {
    assertTrue(cachedSites("local-variable-service-loader-shadow", """
        package demo;
        import java.util.ServiceLoader;
        class Bridge {
          void audit() {
            Helper ServiceLoader = helper();
            ServiceLoader.load(Provider.class);
          }
          static class Helper {
            static <T> T load(Class<T> type) { return null; }
          }
          static Helper helper() { return null; }
        }
        """).isEmpty());
  }

  @Test
  void abstainsForFieldNamedServiceLoader() throws IOException {
    assertTrue(cachedSites("field-service-loader-shadow", """
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

  @Test
  void abstainsForLambdaParameterNamedServiceLoader() throws IOException {
    assertTrue(cachedSites("lambda-parameter-service-loader-shadow", """
        package demo;
        import java.util.ServiceLoader;
        class Bridge {
          void audit() {
            accept((Helper ServiceLoader) -> ServiceLoader.load(Provider.class));
          }
          void accept(Object action) { }
          static class Helper {
            static <T> T load(Class<T> type) { return null; }
          }
        }
        """).isEmpty());
  }

  @Test
  void abstainsForGenericAndArrayTypedServiceLoaderVariables() throws IOException {
    assertTrue(cachedSites("generic-array-service-loader-shadow", """
        package demo;
        import java.util.ServiceLoader;
        class Bridge {
          void audit(Helper[] ServiceLoader) {
            ServiceLoader.load(Provider.class);
          }
          void other() {
            java.util.Map<String, String> ServiceLoader = new java.util.HashMap<>();
            ServiceLoader.load(Provider.class);
          }
          static class Helper {
            static <T> T load(Class<T> type) { return null; }
          }
        }
        """).isEmpty());
  }

  @Test
  void allowsRealServiceLoaderInOtherMethod() throws IOException {
    assertEquals(1, cachedSites("value-shadow-other-method", """
        package demo;
        import java.util.ServiceLoader;
        class Bridge {
          void audit() { Helper ServiceLoader = helper(); }
          void other() { ServiceLoader.load(Provider.class); }
          static Helper helper() { return null; }
        }
        """).size());
  }

  @Test
  void allowsRealServiceLoaderBeforeLocalDeclaration() throws IOException {
    assertEquals(1, cachedSites("value-shadow-before-declaration", """
        package demo;
        import java.util.ServiceLoader;
        class Bridge {
          void audit() {
            ServiceLoader.load(Provider.class);
            Helper ServiceLoader = helper();
          }
          static Helper helper() { return null; }
        }
        """).size());
  }

  @Test
  void allowsRealServiceLoaderOutsideCatchBlock() throws IOException {
    assertEquals(1, cachedSites("value-shadow-outside-catch", """
        package demo;
        import java.util.ServiceLoader;
        class Bridge {
          void audit() {
            try { int count = 1; }
            catch (java.io.IOException ServiceLoader) { ServiceLoader.toString(); }
            ServiceLoader.load(Provider.class);
          }
        }
        """).size());
  }

  @Test
  void allowsRealServiceLoaderWithCaseLabel() throws IOException {
    assertEquals(1, cachedSites("value-shadow-case-label", """
        package demo;
        import java.util.ServiceLoader;
        class Bridge {
          enum Mode { ServiceLoader, Other }
          void audit(Mode mode) {
            switch (mode) {
              case ServiceLoader: break;
            }
            ServiceLoader.load(Provider.class);
          }
        }
        """).size());
  }

  @Test
  void allowsRealServiceLoaderWithGenericTypeArgument() throws IOException {
    assertEquals(1, cachedSites("value-shadow-generic-argument", """
        package demo;
        import java.util.ServiceLoader;
        import java.util.List;
        class Bridge {
          private final List<ServiceLoader> loaders;
          void audit() { ServiceLoader.load(Provider.class); }
        }
        """).size());
  }

  @Test
  void allowsRealServiceLoaderInLaterSwitchArm() throws IOException {
    assertEquals(1, cachedSites("value-shadow-arrow-case-label", """
        package demo;
        import java.util.ServiceLoader;
        class Bridge {
          void audit(Mode mode) {
            switch (mode) {
              case ServiceLoader -> doX();
              case Other -> ServiceLoader.load(Provider.class);
            }
          }
          void doX() { }
          enum Mode { ServiceLoader, Other }
        }
        """).size());
  }

  private List<ServiceLoaderCallSite> cachedSites(String id, String sourceText) throws IOException {
    Path sourceRoot = temp.resolve(id).resolve("src/main/java");
    Path source = sourceRoot.resolve("demo/Bridge.java");
    Files.createDirectories(source.getParent());
    Files.writeString(source, sourceText);
    AnalysisContext context = AnalysisContext.builder()
        .id(id)
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("cache-shadow-test")
        .javaRelease(17)
        .repositoryRoot(temp.resolve(id))
        .applicationClasses(temp.resolve(id).resolve("classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    MethodSymbol owner = new MethodSymbol("demo.Bridge", "audit", "()V");
    return ServiceLoaderSourceCache.source(scanner, owner).orElseThrow().sites();
  }
}
