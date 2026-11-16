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
