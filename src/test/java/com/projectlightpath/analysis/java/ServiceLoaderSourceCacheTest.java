package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
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

    try {
      Class<?> cache = Class.forName(
          "com.projectlightpath.analysis.java.ServiceLoaderSourceCache");
      Method sourceModel = cache.getDeclaredMethod(
          "source", SourceMetadataScanner.class, MethodSymbol.class);
      Object first = ((Optional<?>) sourceModel.invoke(null, scanner, owner)).orElseThrow();
      Object second = ((Optional<?>) sourceModel.invoke(null, scanner, owner)).orElseThrow();
      assertSame(first, second, "repeated edges must reuse the source model");
    } catch (ReflectiveOperationException error) {
      fail("per-scanner ServiceLoader source cache is missing", error);
    }
  }
}
