package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Compiler-bound ServiceLoader sites from the production seam. */
class JavacServiceLoaderSiteResolverTest {
  @TempDir Path temp;

  @Test
  void textualRollbackFlagNoLongerDisablesJavac() throws IOException {
    SourceMetadataScanner scanner = scanner("flag-off", """
        package demo;
        import java.util.*;
        class Bridge { void audit() { ServiceLoader.load(Object.class); } }
        """);
    String previous = System.getProperty("flpath.java.resolver");
    System.setProperty("flpath.java.resolver", "textual");
    try {
      Optional<Map<Path, List<ServiceLoaderCallSite>>> sites =
          JavacServiceLoaderSiteResolver.sites(scanner);
      assertTrue(sites.isPresent());
      Path bridge = temp.resolve("flag-off/src/main/java/demo/Bridge.java")
          .toAbsolutePath().normalize();
      assertFalse(sites.get().getOrDefault(bridge, List.of()).isEmpty());
    } finally {
      if (previous == null) {
        System.clearProperty("flpath.java.resolver");
      } else {
        System.setProperty("flpath.java.resolver", previous);
      }
    }
  }

  @Test
  void usesCompilerBoundSites() throws IOException {
    SourceMetadataScanner scanner = scanner("flag-on", """
        package demo;
        import java.util.*;
        class Bridge { void audit() { ServiceLoader.load(Object.class); } }
        """);
    Optional<Map<Path, List<ServiceLoaderCallSite>>> sites =
        JavacServiceLoaderSiteResolver.sites(scanner);
    assertTrue(sites.isPresent());
    Path bridge = temp.resolve("flag-on/src/main/java/demo/Bridge.java")
        .toAbsolutePath().normalize();
    List<ServiceLoaderCallSite> found = sites.get().get(bridge);
    assertFalse(found == null || found.isEmpty());
    assertEquals("java.lang.Object", found.get(0).serviceType());
  }

  @Test
  void doesNotAcceptALookalikeServiceLoader() throws IOException {
    SourceMetadataScanner scanner = scanner("lookalike", """
        package demo;
        import java.util.*;
        import static a.Tools.ServiceLoader;
        class Bridge { void audit() { ServiceLoader.load(Object.class); } }
        """, Map.of("a/Tools.java", """
        package a;
        public class Tools {
          public static class ServiceLoader<T> {
            public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
          }
        }
        """));
    Optional<Map<Path, List<ServiceLoaderCallSite>>> sites =
        JavacServiceLoaderSiteResolver.sites(scanner);
    assertTrue(sites.isPresent());
    Path bridge = temp.resolve("lookalike/src/main/java/demo/Bridge.java")
        .toAbsolutePath().normalize();
    assertTrue(sites.get().getOrDefault(bridge, List.of()).isEmpty());
  }

  @Test
  void moduleLayerLoadIsNotAClasspathFact() throws IOException {
    SourceMetadataScanner scanner = scanner("module-layer", """
        package demo;
        import java.lang.ModuleLayer;
        import java.util.*;
        class Bridge {
          void audit() { ServiceLoader.load(ModuleLayer.boot(), Object.class); }
        }
        """);
    Optional<Map<Path, List<ServiceLoaderCallSite>>> sites =
        JavacServiceLoaderSiteResolver.sites(scanner);
    assertTrue(sites.isPresent());
    Path bridge = temp.resolve("module-layer/src/main/java/demo/Bridge.java")
        .toAbsolutePath().normalize();
    assertTrue(sites.get().getOrDefault(bridge, List.of()).isEmpty());
  }

  @Test
  void unrelatedErrorDoesNotDropValidFileSites() throws IOException {
    SourceMetadataScanner scanner = scanner("unrelated-error", """
        package demo;
        import java.util.*;
        class Bridge { void audit() { ServiceLoader.load(Object.class); } }
        """, Map.of("demo/Broken.java", """
        package demo;
        class Broken { Missing value; }
        """));
    Optional<Map<Path, List<ServiceLoaderCallSite>>> sites =
        JavacServiceLoaderSiteResolver.sites(scanner);
    assertTrue(sites.isPresent());
    Path bridge = temp.resolve("unrelated-error/src/main/java/demo/Bridge.java")
        .toAbsolutePath().normalize();
    assertFalse(sites.get().getOrDefault(bridge, List.of()).isEmpty());
  }

  @Test
  void localClassServiceTypeIsPreserved() throws IOException {
    SourceMetadataScanner scanner = scanner("local-service", """
        package demo;
        import java.util.*;
        class Bridge {
          void audit() {
            class LocalService { }
            ServiceLoader.load(LocalService.class);
          }
        }
        """);
    Optional<Map<Path, List<ServiceLoaderCallSite>>> sites =
        JavacServiceLoaderSiteResolver.sites(scanner);
    assertTrue(sites.isPresent());
    Path bridge = temp.resolve("local-service/src/main/java/demo/Bridge.java")
        .toAbsolutePath().normalize();
    List<ServiceLoaderCallSite> found = sites.get().getOrDefault(bridge, List.of());
    assertFalse(found.isEmpty());
    assertTrue(found.get(0).serviceType().endsWith("$1LocalService"), found.toString());
  }

  @Test
  void erroringFileProducesASourceDiagnosticAndAbstains() throws IOException {
    SourceMetadataScanner scanner = scanner("source-error", """
        package demo;
        import java.util.*;
        class Bridge {
          void audit() {
            Missing value;
            ServiceLoader.load(Object.class);
          }
        }
        """);
    CallEdge edge = new CallEdge(
        new MethodSymbol("demo.Bridge", "audit", "()V"),
        new MethodSymbol("demo.Provider", "record", "()V"),
        5,
        "src/main/java/demo/Bridge.java",
        SemanticCallFacts.Certainty.DEFINITE,
        "service_loader");
    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(scanner.context);

    ServiceLoaderDescriptorScanner.Resolution resolution =
        ServiceLoaderDescriptorScanner.resolve(scanner.context, edge, scan, scanner);

    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, resolution.certainty());
    assertTrue(resolution.limitation().contains("javac_source_error"),
        resolution.limitation());
    assertTrue(resolution.evidence().stream().anyMatch(item ->
        item.kind().equals("service_loader_scan_diagnostic")
            && item.attributes().get("diagnosticCode").equals("javac_source_error")),
        resolution.evidence().toString());
  }

  @Test
  void sourceErrorInOneFileDowngradesACleanSiblingEdge() throws IOException {
    String bridge = """
        package demo;
        import java.util.ServiceLoader;
        class Bridge { void audit() { ServiceLoader.load(Provider.class).findFirst()
            .ifPresent(value -> value.record()); } }
        """;
    Map<String, String> clean = Map.of(
        "demo/Provider.java", "package demo; interface Provider { void record(); }",
        "demo/ProviderImpl.java",
        "package demo; final class ProviderImpl implements Provider { public void record() { } }");
    ServiceLoaderDescriptorScanner.Scan scan = new ServiceLoaderDescriptorScanner.Scan(List.of(
        new ServiceLoaderDescriptorScanner.Descriptor(
            "demo.Provider", List.of("demo.ProviderImpl"),
            "META-INF/services/demo.Provider", "fingerprint", "meta_inf_services", List.of())));
    CallEdge edge = providerEdge();

    assertEquals(SemanticCallFacts.Certainty.DEFINITE,
        resolve(scanner("clean-sibling", bridge, clean), scan, edge).certainty());

    Map<String, String> withError = new java.util.HashMap<>(clean);
    withError.put("demo/Broken.java", "package demo; class Broken { Missing value; }");
    ServiceLoaderDescriptorScanner.Resolution broken =
        resolve(scanner("error-sibling", bridge, withError), scan, edge);
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, broken.certainty());
    assertTrue(broken.limitation().contains("javac_source_error"), broken.limitation());
  }

  @Test
  void jarSchemeDiagnosticsDoNotAbortTheScan() {
    JavaFileObject jarEntry = new JavaFileObject() {
      @Override
      public URI toUri() {
        return URI.create("jar:file:/libs/foo.jar!/demo/Foo.class");
      }

      @Override
      public String getName() {
        return "demo/Foo.class";
      }

      @Override
      public JavaFileObject.Kind getKind() {
        return JavaFileObject.Kind.CLASS;
      }

      @Override
      public boolean isNameCompatible(String simpleName, JavaFileObject.Kind kind) {
        return true;
      }

      @Override
      public javax.lang.model.element.NestingKind getNestingKind() {
        return null;
      }

      @Override
      public javax.lang.model.element.Modifier getAccessLevel() {
        return null;
      }

      @Override
      public java.io.InputStream openInputStream() {
        return java.io.InputStream.nullInputStream();
      }

      @Override
      public java.io.OutputStream openOutputStream() {
        throw new UnsupportedOperationException();
      }

      @Override
      public java.io.Reader openReader(boolean ignoreEncodingErrors) {
        throw new UnsupportedOperationException();
      }

      @Override
      public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        throw new UnsupportedOperationException();
      }

      @Override
      public java.io.Writer openWriter() {
        throw new UnsupportedOperationException();
      }

      @Override
      public long getLastModified() {
        return 0;
      }

      @Override
      public boolean delete() {
        return false;
      }
    };
    assertDoesNotThrow(() -> JavacServiceLoaderSiteResolver.sourcePath(jarEntry));
    assertNull(JavacServiceLoaderSiteResolver.sourcePath(jarEntry));
  }

  @Test
  void sourceDiagnosticPathsNeverLeakAbsolutePaths() throws IOException {
    SourceMetadataScanner scanner = scanner("paths", "package demo; class Bridge { }");
    Path root = temp.resolve("paths").toAbsolutePath().normalize();
    assertEquals("a/B.java",
        ServiceLoaderDescriptorScanner.diagnosticPath(scanner, root.resolve("a/B.java")));
    assertEquals("<external>/B.java", ServiceLoaderDescriptorScanner.diagnosticPath(
        scanner, temp.resolve("elsewhere/B.java").toAbsolutePath().normalize()));
    assertEquals("<external>/B", ServiceLoaderDescriptorScanner.diagnosticPath(
        scanner, temp.resolve("elsewhere/B").toAbsolutePath().normalize()));
    assertEquals("<external>/B.java", ServiceLoaderDescriptorScanner.diagnosticPath(
        scanner, temp.resolve("paths-other/B.java").toAbsolutePath().normalize()));
    assertEquals("", ServiceLoaderDescriptorScanner.diagnosticPath(scanner, root));
  }

  @Test
  void unresolvableImportProducesASourceDiagnostic() throws IOException {
    StringBuilder name = new StringBuilder("a");
    for (int index = 0; index < 20000; index++) {
      name.append(".b");
    }
    SourceMetadataScanner scanner = scanner("global-failure",
        "package demo;\nimport " + name + ".*;\nclass Bridge { }\n");
    ServiceLoaderDescriptorScanner.Resolution resolution = resolve(
        scanner, new ServiceLoaderDescriptorScanner.Scan(List.of()), providerEdge());
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, resolution.certainty());
    assertTrue(resolution.limitation().contains("javac_source_error"), resolution.limitation());
  }

  @Test
  void erroringFileSitesAreDroppedWhileValidSiblingSurvives() throws IOException {
    SourceMetadataScanner scanner = scanner("error-file-site", """
        package demo;
        import java.util.*;
        class Bridge { void audit() { ServiceLoader.load(Object.class); } }
        """, Map.of("demo/Broken.java", """
        package demo;
        import java.util.*;
        class Broken {
          void audit() {
            Missing value;
            ServiceLoader.load(Object.class);
          }
        }
        """));
    Optional<Map<Path, List<ServiceLoaderCallSite>>> sites =
        JavacServiceLoaderSiteResolver.sites(scanner);
    assertTrue(sites.isPresent());
    Path bridge = temp.resolve("error-file-site/src/main/java/demo/Bridge.java")
        .toAbsolutePath().normalize();
    Path broken = temp.resolve("error-file-site/src/main/java/demo/Broken.java")
        .toAbsolutePath().normalize();
    assertFalse(sites.get().getOrDefault(bridge, List.of()).isEmpty());
    assertTrue(sites.get().getOrDefault(broken, List.of()).isEmpty());
  }

  private static CallEdge providerEdge() {
    return new CallEdge(
        new MethodSymbol("demo.Bridge", "audit", "()V"),
        new MethodSymbol("demo.ProviderImpl", "record", "()V"),
        4,
        "src/main/java/demo/Bridge.java",
        SemanticCallFacts.Certainty.DEFINITE,
        "service_loader");
  }

  private static ServiceLoaderDescriptorScanner.Resolution resolve(
      SourceMetadataScanner scanner,
      ServiceLoaderDescriptorScanner.Scan scan,
      CallEdge edge) {
    return ServiceLoaderDescriptorScanner.resolve(scanner.context, edge, scan, scanner);
  }

  private SourceMetadataScanner scanner(String id, String bridgeSource) throws IOException {
    return scanner(id, bridgeSource, Map.of());
  }

  private SourceMetadataScanner scanner(
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
        .fixtureId("javac-resolver-test")
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
