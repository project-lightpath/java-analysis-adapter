package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * Default-on semantics of the {@code flpath.java.symbolindex} seam (issue
 * #66): no property (and any value other than an explicit {@code false})
 * builds the index; {@code "false"} is the deliberate opt-out that keeps
 * the textual path for field bisection. Also pins the qualifier-lookalike
 * exclusion under the javac path and the scan-diagnostics isolation of
 * index diagnostics under the new default.
 *
 * <p>Writes the flag, so the class is {@code @Isolated} +
 * {@code SAME_THREAD}; {@code @AfterEach} restores the default (absent).
 */
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class JavacSymbolIndexDefaultOnTest {
  private static final String FLAG = "flpath.java.symbolindex";

  @TempDir Path temp;

  @AfterEach
  void restoreFlag() {
    System.clearProperty(FLAG);
  }

  @Test
  void defaultBuildsTheIndex() throws IOException {
    // The cell ensures the property is absent at entry; no reliance on a
    // previous cell's cleanup or JUnit method ordering.
    System.clearProperty(FLAG);
    assertNull(System.getProperty(FLAG));
    long before = JavacSourceSymbolIndex.COMPILATIONS.get();
    AnalysisContext context = context("default-on", Map.of("demo/App.java",
        "package demo; public class App {}"));
    assertTrue(JavacSourceSymbolIndex.enabled());
    Optional<JavacSourceSymbolIndex> index = JavacSourceSymbolIndex.of(context);
    assertTrue(index.isPresent());
    assertEquals(1, JavacSourceSymbolIndex.COMPILATIONS.get() - before);
  }

  @Test
  void explicitOptOutDisablesTheIndex() throws IOException {
    System.setProperty(FLAG, "false");
    long before = JavacSourceSymbolIndex.COMPILATIONS.get();
    AnalysisContext context = context("opt-out", Map.of("demo/App.java",
        "package demo; public class App {}"));
    assertFalse(JavacSourceSymbolIndex.enabled());
    assertEquals(Optional.empty(), JavacSourceSymbolIndex.of(context));
    assertEquals(0, JavacSourceSymbolIndex.COMPILATIONS.get() - before);
  }

  @Test
  void unrecognizedValuesKeepTheDefaultOn() throws IOException {
    Map<String, String> files = Map.of("demo/App.java",
        "package demo; public class App {}");
    // "true" and any unrecognized value keep the default (on): the opt-out
    // must be deliberate. Boundary cells for the equalsIgnoreCase
    // arithmetic: "FALSE" opts out, " False " (whitespace is NOT trimmed)
    // and "" (empty) keep the default.
    for (String value : List.of("true", "xyz", " False ", "")) {
      System.setProperty(FLAG, value);
      assertTrue(JavacSourceSymbolIndex.enabled(), value);
      assertTrue(JavacSourceSymbolIndex.of(context("value-" + value, files))
          .isPresent(), value);
    }
    System.setProperty(FLAG, "FALSE");
    assertFalse(JavacSourceSymbolIndex.enabled());
    assertEquals(Optional.empty(),
        JavacSourceSymbolIndex.of(context("value-FALSE", files)));
  }

  @Test
  void defaultScanMatchesFlagOnOutput() throws IOException {
    AnalysisContext context = proxyRepairFixture("default-parity");
    // Default (no property): the javac repair is the answer.
    System.clearProperty(FLAG);
    List<DynamicFeature> byDefault = new SourceMetadataScanner(context)
        .dynamicFeatures();
    System.setProperty(FLAG, "true");
    List<DynamicFeature> flagOn = new SourceMetadataScanner(context)
        .dynamicFeatures();
    System.setProperty(FLAG, "false");
    List<DynamicFeature> optOut = new SourceMetadataScanner(context)
        .dynamicFeatures();
    // Non-vacuity: the three arms are distinguishable on this fixture.
    assertTrue(byDefault.stream().anyMatch(feature ->
        feature.target().startsWith("demo.Api#")
            && feature.targetDescriptor().equals("(java.lang.String)")),
        byDefault.toString());
    assertTrue(flagOn.stream().anyMatch(feature ->
        feature.target().startsWith("demo.Api#")
            && feature.targetDescriptor().equals("(java.lang.String)")),
        flagOn.toString());
    assertTrue(optOut.stream().anyMatch(feature ->
        feature.target().startsWith("demo.Api#")
            && feature.targetDescriptor().equals("((value=1)String)")),
        optOut.toString());
    assertEquals(projection(flagOn), projection(byDefault));
  }

  @Test
  void optOutReproducesTheTextualDefault() throws IOException {
    AnalysisContext context = proxyRepairFixture("opt-out-golden");
    System.setProperty(FLAG, "false");
    // Exact pinned textual value (the pre-#66 default output), not a
    // comparison against a moving arm.
    assertTrue(new SourceMetadataScanner(context).dynamicFeatures().stream()
        .anyMatch(feature -> feature.target().startsWith("demo.Api#")
            && feature.targetDescriptor().equals("((value=1)String)")));
  }

  @Test
  void qualifierLookalikesStayExcludedUnderTheJavacPath() throws IOException {
    AnalysisContext context = springQualifierFixture("lookalike", true);
    System.setProperty(FLAG, "true");
    List<SourceMetadataScannerState.InjectionPoint> injections =
        new SourceMetadataScanner(context).injectionPoints(
            AnalysisContext.Framework.SPRING_BOOT);
    SourceMetadataScannerState.Parameter parameter = injections.stream()
        .filter(point -> point.kind().equals("constructor"))
        .findFirst().orElseThrow().parameters().get(0);
    assertEquals(List.of("Qualifier:fast"), parameter.qualifiers());
  }

  @Test
  void lookalikeDeclaredBeforeTheCanonicalAnnotationStaysExcluded()
      throws IOException {
    AnalysisContext context = springQualifierFixture("lookalike-order", false);
    System.setProperty(FLAG, "true");
    List<SourceMetadataScannerState.InjectionPoint> injections =
        new SourceMetadataScanner(context).injectionPoints(
            AnalysisContext.Framework.SPRING_BOOT);
    SourceMetadataScannerState.Parameter parameter = injections.stream()
        .filter(point -> point.kind().equals("constructor"))
        .findFirst().orElseThrow().parameters().get(0);
    assertEquals(List.of("Qualifier:fast"), parameter.qualifiers());
  }

  @Test
  void lookalikeWithTheCanonicalMirrorUnresolvedStaysExcluded()
      throws IOException {
    // No spring Qualifier stub in the source set: the canonical import
    // still resolves textually, but the canonical mirror is error-typed.
    // The lookalike must never supply the emitted value.
    AnalysisContext context = springQualifierFixture("lookalike-dropped",
        true, true);
    System.setProperty(FLAG, "true");
    List<SourceMetadataScannerState.InjectionPoint> injections =
        new SourceMetadataScanner(context).injectionPoints(
            AnalysisContext.Framework.SPRING_BOOT);
    SourceMetadataScannerState.Parameter parameter = injections.stream()
        .filter(point -> point.kind().equals("constructor"))
        .findFirst().orElseThrow().parameters().get(0);
    assertEquals(List.of("Qualifier:fast"), parameter.qualifiers());
  }

  @Test
  void beanSiteNamedLookalikesStayExcludedUnderTheJavacPath()
      throws IOException {
    Map<String, String> files = Map.of(
        "jakarta/inject/Named.java",
        "package jakarta.inject; public @interface Named { String value() default \"\"; }",
        "demo/Named.java",
        "package demo; public @interface Named { String value() default \"\"; }",
        "demo/Bean.java", """
            package demo;
            import jakarta.inject.Named;
            @Named("svc") @demo.Named("custom") public class Bean {}
            """);
    AnalysisContext context = springContext("bean-lookalike", files);
    System.setProperty(FLAG, "true");
    List<SourceMetadataScannerState.BeanCandidate> beans =
        new SourceMetadataScanner(context).beanCandidates(
            AnalysisContext.Framework.SPRING_BOOT);
    List<String> qualifiers = beans.stream()
        .filter(bean -> bean.candidateId().equals("demo.Bean"))
        .findFirst().orElseThrow().qualifiers();
    assertTrue(qualifiers.contains("Named:svc"), qualifiers.toString());
    assertFalse(qualifiers.contains("Named:custom"), qualifiers.toString());
  }

  @Test
  void unguardedInjectionSitesKeepUserDefinedQualifiers() throws IOException {
    // The field/method/constructor annotation-block sites are unguarded
    // for BOTH frameworks (parity with the textual fallback): a
    // user-defined same-simple-name annotation still emits there.
    Map<String, String> spring = Map.of(
        "org/springframework/beans/factory/annotation/Autowired.java",
        "package org.springframework.beans.factory.annotation;"
            + " public @interface Autowired {}",
        "org/springframework/beans/factory/annotation/Qualifier.java",
        "package org.springframework.beans.factory.annotation;"
            + " public @interface Qualifier { String value() default \"\"; }",
        "org/springframework/stereotype/Component.java",
        "package org.springframework.stereotype; public @interface Component {}",
        "demo/Qualifier.java",
        "package demo; public @interface Qualifier { String value() default \"\"; }",
        "demo/Field.java", """
            package demo;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Component;
            @Component public class Field {
              @Autowired @demo.Qualifier("custom") private Object dependency;
            }
            """);
    System.setProperty(FLAG, "true");
    List<SourceMetadataScannerState.InjectionPoint> springField =
        new SourceMetadataScanner(springContext("unguarded-spring", spring))
            .injectionPoints(AnalysisContext.Framework.SPRING_BOOT);
    assertEquals(List.of("Qualifier:custom"), springField.stream()
        .filter(point -> point.kind().equals("field")
            && point.member().equals("dependency"))
        .findFirst().orElseThrow().qualifiers());

    Map<String, String> quarkus = Map.of(
        "jakarta/inject/Inject.java", "package jakarta.inject; public @interface Inject {}",
        "jakarta/enterprise/context/ApplicationScoped.java",
        "package jakarta.enterprise.context; public @interface ApplicationScoped {}",
        "demo/Named.java",
        "package demo; public @interface Named { String value() default \"\"; }",
        "demo/Greeter.java", """
            package demo;
            import jakarta.enterprise.context.ApplicationScoped;
            import jakarta.inject.Inject;
            @ApplicationScoped public class Greeter {
              @Inject @demo.Named("custom") private Object dependency;
            }
            """);
    List<SourceMetadataScannerState.InjectionPoint> quarkusField =
        new SourceMetadataScanner(quarkusContext("unguarded-quarkus", quarkus))
            .injectionPoints(AnalysisContext.Framework.QUARKUS);
    assertEquals(List.of("Named:custom"), quarkusField.stream()
        .filter(point -> point.kind().equals("field")
            && point.member().equals("dependency"))
        .findFirst().orElseThrow().qualifiers());
  }

  @Test
  void unicodeNamedQualifiersAreStillCorrectedByTheIndex() throws IOException {
    // Emit-path pin for the remaining-mirrors append (the pure index
    // correction): the $/Unicode names the textual pattern cannot capture
    // still come back corrected. Green at RED under the explicit "true".
    Map<String, String> files = Map.of("demo/Weird.java", """
        package demo;
        import org.springframework.stereotype.Service;
        @Service @Mar$k @Ωmega public class Weird {}
        @interface Mar$k {}
        @interface Ωmega {}
        """);
    AnalysisContext context = springContext("unicode-emit", files);
    System.setProperty(FLAG, "true");
    List<String> qualifiers = new SourceMetadataScanner(context)
        .beanCandidates(AnalysisContext.Framework.SPRING_BOOT).stream()
        .filter(bean -> bean.candidateId().equals("demo.Weird"))
        .findFirst().orElseThrow().qualifiers();
    assertEquals(List.of("Mar$k", "Ωmega"), qualifiers);
  }

  @Test
  void indexDiagnosticsNeverReachScanDiagnostics() throws IOException {
    // The index emits its own missing_classpath row (non-vacuity) while
    // the scan-global diagnostics stay identical to the opt-out arm and
    // free of index rows — the #62 invariant under the new default.
    Path root = temp.resolve("diagnostics");
    Path sourceRoot = root.resolve("src/main/java");
    writeFiles(sourceRoot, Map.of("demo/App.java",
        "package demo; public class App {}"));
    AnalysisContext context = AnalysisContext.builder()
        .id("diagnostics")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("javac-index-default-on-test")
        .javaRelease(17)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot,
            AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspath(List.of(temp.resolve("elsewhere/missing.jar")))
        .dependencyClasspathRequired(true)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
    System.clearProperty(FLAG);
    JavacSourceSymbolIndex index = JavacSourceSymbolIndex.of(context)
        .orElseThrow();
    assertTrue(index.diagnostics().stream().anyMatch(
        row -> row.code().equals("missing_classpath")),
        index.diagnostics().toString());
    ServiceLoaderDescriptorScanner.Scan defaultScan =
        ServiceLoaderDescriptorScanner.scan(context);
    System.setProperty(FLAG, "false");
    ServiceLoaderDescriptorScanner.Scan optOutScan =
        ServiceLoaderDescriptorScanner.scan(context);
    assertEquals(optOutScan.diagnostics(), defaultScan.diagnostics());
    for (String indexCode : List.of("missing_classpath", "missing_source",
        "missing_source_root", "index_build_error", "javac_unavailable",
        "release_clamped", "duplicate_binary_name", "javac_global_error")) {
      assertTrue(defaultScan.diagnostics().stream()
          .noneMatch(row -> row.code().equals(indexCode)),
          defaultScan.diagnostics().toString());
    }
  }

  private AnalysisContext proxyRepairFixture(String id) throws IOException {
    return context(id, Map.of(
        "demo/Ann.java", """
            package demo;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @interface Ann { int value() default 0; }
            """,
        "demo/Api.java", """
            package demo;
            public interface Api {
              void call(@Ann(value = 1) String p);
            }
            """,
        "demo/Main.java", """
            package demo;
            public class Main {
              void run() {
                Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {Api.class},
                    (proxyInstance, method, args) -> null);
              }
            }
            """));
  }

  /**
   * Spring constructor-injection fixture with a canonical
   * {@code @Qualifier("fast")}, a user-defined {@code @demo.Qualifier}
   * lookalike, and a {@code @Value} string-decoy on the same parameter.
   * {@code canonicalFirst} controls declaration order; {@code
   * dropSpringStub} removes the spring Qualifier source stub so the
   * canonical mirror is error-typed.
   */
  private AnalysisContext springQualifierFixture(
      String id, boolean canonicalFirst) throws IOException {
    return springQualifierFixture(id, canonicalFirst, false);
  }

  private AnalysisContext springQualifierFixture(
      String id, boolean canonicalFirst, boolean dropSpringStub)
      throws IOException {
    String canonical = "@Qualifier(\"fast\")";
    String lookalike = "@demo.Qualifier(\"custom\")";
    String annotations = canonicalFirst
        ? canonical + "\n              " + lookalike
        : lookalike + "\n              " + canonical;
    Map<String, String> files = new java.util.LinkedHashMap<>();
    if (!dropSpringStub) {
      files.put("org/springframework/beans/factory/annotation/Qualifier.java",
          "package org.springframework.beans.factory.annotation;"
              + " public @interface Qualifier { String value() default \"\"; }");
    }
    files.put("org/springframework/beans/factory/annotation/Autowired.java",
        "package org.springframework.beans.factory.annotation;"
            + " public @interface Autowired {}");
    files.put("org/springframework/beans/factory/annotation/Value.java",
        "package org.springframework.beans.factory.annotation;"
            + " public @interface Value { String value() default \"\"; }");
    files.put("demo/Qualifier.java",
        "package demo; public @interface Qualifier { String value() default \"\"; }");
    files.put("demo/Service.java", "package demo; public interface Service {}");
    files.put("demo/Consumer.java", """
        package demo;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.beans.factory.annotation.Qualifier;
        import org.springframework.beans.factory.annotation.Value;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(
              %s
              @Value("@Qualifier(literal)")
              Service service) { }
        }
        """.formatted(annotations));
    return springContext(id, files);
  }

  private AnalysisContext springContext(String id, Map<String, String> files)
      throws IOException {
    return context(id, AnalysisContext.Framework.SPRING_BOOT, files);
  }

  private AnalysisContext quarkusContext(String id, Map<String, String> files)
      throws IOException {
    return context(id, AnalysisContext.Framework.QUARKUS, files);
  }

  private AnalysisContext context(
      String id, AnalysisContext.Framework framework, Map<String, String> files)
      throws IOException {
    Path root = temp.resolve(id);
    Path sourceRoot = root.resolve("src/main/java");
    writeFiles(sourceRoot, files);
    return AnalysisContext.builder()
        .id(id)
        .framework(framework)
        .frameworkVersion(
            framework == AnalysisContext.Framework.SPRING_BOOT ? "3.4.0" : "3.40.1")
        .fixtureId("javac-index-default-on-test")
        .javaRelease(17)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot,
            AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
  }

  private AnalysisContext context(String id, Map<String, String> files)
      throws IOException {
    return context(id, AnalysisContext.Framework.QUARKUS, files);
  }

  private List<String> projection(List<DynamicFeature> features) {
    return features.stream()
        .map(feature -> feature.target() + " " + feature.targetDescriptor())
        .sorted()
        .toList();
  }

  private static void writeFiles(Path sourceRoot, Map<String, String> files)
      throws IOException {
    for (Map.Entry<String, String> entry : files.entrySet()) {
      Path file = sourceRoot.resolve(entry.getKey());
      Files.createDirectories(file.getParent());
      Files.writeString(file, entry.getValue());
    }
  }
}
