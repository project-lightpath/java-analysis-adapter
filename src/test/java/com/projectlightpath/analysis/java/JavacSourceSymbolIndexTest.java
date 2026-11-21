package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import javax.tools.Diagnostic;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * javac source-symbol index: one parse per AnalysisContext, read API, and
 * fail-visible diagnostics, behind the {@code flpath.java.symbolindex} flag.
 *
 * <p>The class runs same-thread because it owns the flag and the
 * {@link JavacSourceSymbolIndex#COMPILATIONS} counter; every counter
 * assertion is a delta, never an absolute value.
 */
@Execution(ExecutionMode.SAME_THREAD)
class JavacSourceSymbolIndexTest {
  private static final String FLAG = "flpath.java.symbolindex";
  private static final Supplier<JavaCompiler> REAL_COMPILER =
      JavacSourceSymbolIndex.COMPILER.get();

  @TempDir Path temp;

  @AfterEach
  void restoreSeams() {
    System.clearProperty(FLAG);
    JavacSourceSymbolIndex.COMPILER.set(REAL_COMPILER);
  }

  @Test
  void unmappableNameIgnoresMappableErrorsThatComeFirst() {
    JavaFileObject unit = new javax.tools.SimpleJavaFileObject(
        java.net.URI.create("file:/tmp/A.java"), JavaFileObject.Kind.SOURCE) {};

    assertEquals("<external>/Foo.class", JavacSourceSymbolIndex.IndexRenderer.firstUnmappableName(
        List.of(error(unit), error(jarEntry()))));
  }

  @Test
  void flagOffYieldsNoIndexAndNoCompilation() throws IOException {
    System.clearProperty(FLAG);
    long before = JavacSourceSymbolIndex.COMPILATIONS.get();
    AnalysisContext context = context("flag-off", Map.of("demo/App.java",
        "package demo; public class App {}"));
    assertEquals(Optional.empty(), JavacSourceSymbolIndex.of(context));
    assertEquals(0, JavacSourceSymbolIndex.COMPILATIONS.get() - before);
    assertFalse(JavacSourceSymbolIndex.enabled());
  }

  @Test
  void secondLookupDoesNotStartAnotherCompiler() throws IOException {
    long before = JavacSourceSymbolIndex.COMPILATIONS.get();
    AnalysisContext context = context("one-parse", Map.of("demo/App.java",
        "package demo; public class App {}"));
    Optional<JavacSourceSymbolIndex> first = withFlag(() -> JavacSourceSymbolIndex.of(context));
    Optional<JavacSourceSymbolIndex> second = withFlag(() -> JavacSourceSymbolIndex.of(context));
    assertTrue(first.isPresent() && second.isPresent());
    assertSameEntries(first.get(), second.get());
    assertEquals(1, JavacSourceSymbolIndex.COMPILATIONS.get() - before);
    AnalysisContext fresh = context("one-parse-fresh", Map.of("demo/App.java",
        "package demo; public class App {}"));
    assertTrue(withFlag(() -> JavacSourceSymbolIndex.of(fresh)).isPresent());
    assertEquals(2, JavacSourceSymbolIndex.COMPILATIONS.get() - before);
  }

  @Test
  void dollarNamesResolveWithoutDecoyLeaks() throws IOException {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("demo/Cash.java", "package demo; class Cash$Flow {}");
    files.put("demo/Outer.java", "package demo; public class Outer { class Inner {} }");
    files.put("demo/Top.java", "package demo; class Outer$Inner {}");
    files.put("demo/Flow.java", "package demo; class $Flow {}");
    files.put("demo/Cash2.java", "package demo; class Cash$$Flow {}");
    files.put("demo/Dollar.java", "package demo; class $ {}");
    files.put("a$b/Outer.java", "package a$b; public class Outer {}");
    AnalysisContext context = context("dollar-names", files);
    JavacSourceSymbolIndex index = withFlag(() -> JavacSourceSymbolIndex.of(context)).orElseThrow();

    List<JavacSourceSymbolIndex.TypeInfo> cash = index.type("demo.Cash$Flow");
    assertEquals(1, cash.size());
    assertEquals("demo.Cash$Flow", cash.get(0).binaryName());
    assertEquals("demo.Cash$Flow", cash.get(0).canonicalName());

    // D3: nested demo.Outer.Inner and top-level demo.Outer$Inner share a binary name.
    List<JavacSourceSymbolIndex.TypeInfo> nested = index.type("demo.Outer.Inner");
    assertEquals(1, nested.size());
    assertTrue(nested.get(0).file().toString().endsWith("Outer.java"));
    List<JavacSourceSymbolIndex.TypeInfo> collision = index.type("demo.Outer$Inner");
    assertEquals(2, collision.size());
    assertTrue(collision.stream().allMatch(
        type -> type.shape().duplicateBinaryName()), collision.toString());
    assertTrue(collision.stream().anyMatch(
        type -> type.file().toString().endsWith("Top.java")
            && type.shape().errorType()), collision.toString());

    assertEquals(1, index.type("demo.$Flow").size());
    assertEquals(1, index.type("demo.Cash$$Flow").size());
    assertEquals(1, index.type("demo.$").size());
    assertEquals(1, index.type("a$b.Outer").size());
    // An ordinary lookup whose canonical and binary names coincide returns one entry.
    assertEquals(1, index.type("demo.Cash$Flow").size());
  }

  @Test
  void overlappingRootsSubmitEachFileOnce() throws IOException {
    Path root = temp.resolve("overlap");
    Path sourceRoot = root.resolve("src/main/java");
    writeFiles(sourceRoot, Map.of("demo/A.java", "package demo; public class A {}"));
    AnalysisContext context = AnalysisContext.builder()
        .id("overlap")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("javac-index-test")
        .javaRelease(17)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .generatedSourceRoot("src/main/java", sourceRoot,
            AnalysisContext.Scope.PRODUCTION, true, false)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
    JavacSourceSymbolIndex index = withFlag(() -> JavacSourceSymbolIndex.of(context))
        .orElseThrow();
    List<JavacSourceSymbolIndex.TypeInfo> found = index.type("demo.A");
    assertEquals(1, found.size());
    assertFalse(found.get(0).shape().duplicateBinaryName());
    assertTrue(index.diagnostics().stream().noneMatch(
        row -> row.code().equals("duplicate_binary_name")), index.diagnostics().toString());

    Path file = sourceRoot.resolve("demo/A.java");
    assertEquals(index.typesIn(file), index.typesIn(
        root.resolve("src/x/../main/java/demo/A.java")));
  }

  @Test
  void releaseClampIsDiagnosedOnlyAboveTheRuntimeFeature() throws IOException {
    AnalysisContext clamped = context("release-clamped", Map.of("demo/App.java",
        "package demo; public class App {}"),
        builder -> builder.javaRelease(Runtime.version().feature() + 1));
    JavacSourceSymbolIndex index =
        withFlag(() -> JavacSourceSymbolIndex.of(clamped)).orElseThrow();
    assertTrue(index.diagnostics().stream().anyMatch(
        row -> row.code().equals("release_clamped")), index.diagnostics().toString());

    AnalysisContext exact = context("release-exact", Map.of("demo/App.java",
        "package demo; public class App {}"),
        builder -> builder.javaRelease(Runtime.version().feature()));
    JavacSourceSymbolIndex exactIndex =
        withFlag(() -> JavacSourceSymbolIndex.of(exact)).orElseThrow();
    assertTrue(exactIndex.diagnostics().stream().noneMatch(
        row -> row.code().equals("release_clamped")), exactIndex.diagnostics().toString());
  }

  @Test
  void localAndAnonymousClassesAreIndexedByBinaryNameOnly() throws IOException {
    AnalysisContext context = context("local-anon", Map.of("demo/Bridge.java", """
        package demo;
        public class Bridge {
          void audit() {
            class LocalService {}
            LocalService service = null;
          }
          Object anon = new Object() {};
        }
        """));
    JavacSourceSymbolIndex index = withFlag(() -> JavacSourceSymbolIndex.of(context))
        .orElseThrow();
    assertEquals(1, index.type("demo.Bridge$1LocalService").size());
    assertEquals(1, index.type("demo.Bridge$1").size());
    // The local class's qualified name is its bare simple name (probe result);
    // it is deliberately absent from the canonical map.
    assertEquals(0, index.type("LocalService").size());
    assertEquals(0, index.type("").size());
    assertEquals(0, index.type("demo.").size());
  }

  @Test
  void declarationAnnotationsExposeCanonicalNamesAndValues() throws IOException {
    AnalysisContext context = context("annotations", Map.of(
        "demo/Marker.java", """
            package demo;
            import java.lang.annotation.*;
            @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD,
                ElementType.CONSTRUCTOR, ElementType.TYPE_USE})
            public @interface Marker {
              String name();
              int count() default 0;
              Class<?>[] types() default {};
              Status status() default Status.NEW;
              Inner inner() default @Inner;
              @interface Inner {}
              enum Status { NEW, OLD }
            }
            """,
        "demo/App.java", """
            package demo;
            @Marker(name = "app", count = 2, types = {Object.class, String.class},
                status = Marker.Status.OLD, inner = @Marker.Inner)
            public class App {
              @Marker(name = "field") private String field;
              @Marker(name = "method") public void method() {}
              @Marker(name = "ctor") public App() {}
            }
            """,
        "demo/package-info.java", "@Marker(name = \"pkg\") package demo;"));
    JavacSourceSymbolIndex index = withFlag(() -> JavacSourceSymbolIndex.of(context))
        .orElseThrow();

    JavacSourceSymbolIndex.TypeInfo app = index.type("demo.App").get(0);
    assertEquals("class", app.shape().declarationKind());
    assertEquals(1, app.annotations().size());
    JavacSourceSymbolIndex.AnnotationInfo marker = app.annotations().get(0);
    assertEquals("demo.Marker", marker.canonicalName());
    assertEquals("\"app\"", marker.values().get("name"));
    assertEquals("2", marker.values().get("count"));
    assertEquals("{java.lang.Object, java.lang.String}", marker.values().get("types"));
    assertEquals("demo.Marker.Status.OLD", marker.values().get("status"));
    assertEquals("@demo.Marker.Inner", marker.values().get("inner"));

    JavacSourceSymbolIndex.MemberInfo field =
        index.members("demo.App", "field").get(0);
    assertEquals("field", field.kind());
    assertEquals("", field.returnType());
    assertTrue(field.parameterTypes().isEmpty());
    assertEquals("\"field\"", field.annotations().get(0).values().get("name"));

    JavacSourceSymbolIndex.MemberInfo method =
        index.members("demo.App", "method").get(0);
    assertEquals("method", method.kind());
    assertEquals("void", method.returnType());

    JavacSourceSymbolIndex.MemberInfo constructor =
        index.members("demo.App", "App").get(0);
    assertEquals("constructor", constructor.kind());
    assertEquals("void", constructor.returnType());

    Path packageInfo = temp.resolve("annotations/src/main/java/demo/package-info.java");
    List<JavacSourceSymbolIndex.AnnotationInfo> packageRows =
        index.packageAnnotations(packageInfo);
    assertEquals(1, packageRows.size());
    assertEquals("demo.Marker", packageRows.get(0).canonicalName());
    assertEquals("\"pkg\"", packageRows.get(0).values().get("name"));
    // Non-normalized spelling of the same file resolves identically.
    assertEquals(packageRows, index.packageAnnotations(
        temp.resolve("annotations/src/main/x/../java/demo/package-info.java")));
    // The miss shape: a non-package-info path returns empty.
    assertEquals(0, index.packageAnnotations(
        temp.resolve("annotations/src/main/java/demo/App.java")).size());
  }

  @Test
  void memberShapesUseCanonicalErasedNames() throws IOException {
    AnalysisContext context = context("member-shapes", Map.of("demo/Shapes.java", """
        package demo;
        import java.lang.annotation.*;
        import java.util.List;
        @Target(ElementType.TYPE_USE) @interface Ann {}
        public class Shapes {
          public static class Nested {}
          public void audit(String @Ann ... args, @Ann String p, Nested nested) {}
          public List<String> names() { return null; }
          public record Point(int x) {}
          public enum Color { RED, GREEN }
          public void foo(String a) {}
          public void foo(String a, int b) {}
        }
        """));
    JavacSourceSymbolIndex index = withFlag(() -> JavacSourceSymbolIndex.of(context))
        .orElseThrow();

    JavacSourceSymbolIndex.MemberInfo audit =
        index.members("demo.Shapes", "audit").get(0);
    assertEquals(List.of("java.lang.String[]", "java.lang.String", "demo.Shapes.Nested"),
        audit.parameterTypes());
    assertEquals("void", audit.returnType());
    assertEquals("java.util.List", index.members("demo.Shapes", "names").get(0).returnType());

    assertEquals("record", index.type("demo.Shapes.Point").get(0)
        .shape().declarationKind());
    List<JavacSourceSymbolIndex.MemberInfo> components =
        index.members("demo.Shapes.Point", "x");
    assertTrue(components.stream().anyMatch(member -> member.kind().equals("field")
        && member.returnType().isEmpty()), components.toString());

    assertEquals("enum", index.type("demo.Shapes.Color").get(0)
        .shape().declarationKind());
    JavacSourceSymbolIndex.MemberInfo constant =
        index.members("demo.Shapes.Color", "RED").get(0);
    assertEquals("enum_constant", constant.kind());
    assertEquals("", constant.returnType());

    List<JavacSourceSymbolIndex.MemberInfo> overloads =
        index.members("demo.Shapes", "foo");
    assertEquals(2, overloads.size());
    assertTrue(overloads.stream().anyMatch(
        member -> member.parameterTypes().equals(List.of("java.lang.String"))));
    assertTrue(overloads.stream().anyMatch(
        member -> member.parameterTypes().equals(List.of("java.lang.String", "int"))));
  }

  @Test
  void errorTypeMemberIsFlaggedWithADiagnostic() throws IOException {
    AnalysisContext context = context("error-type", Map.of("demo/Broken.java",
        "package demo; public class Broken { Missing payload; }"));
    JavacSourceSymbolIndex index = withFlag(() -> JavacSourceSymbolIndex.of(context))
        .orElseThrow();
    JavacSourceSymbolIndex.MemberInfo payload =
        index.members("demo.Broken", "payload").get(0);
    assertTrue(payload.errorType());
    assertTrue(index.diagnostics().stream().anyMatch(
        row -> row.path().endsWith("Broken.java") && row.line() > 0),
        index.diagnostics().toString());
  }

  @Test
  void missingSourceRootIsDiagnosedAndShortCircuits() throws IOException {
    long before = JavacSourceSymbolIndex.COMPILATIONS.get();
    AnalysisContext absent = context("absent-root", Map.of(), builder -> {});
    JavacSourceSymbolIndex index = withFlag(() -> JavacSourceSymbolIndex.of(absent))
        .orElseThrow();
    assertTrue(index.diagnostics().stream().anyMatch(
        row -> row.code().equals("missing_source_root")), index.diagnostics().toString());
    assertEquals(0, JavacSourceSymbolIndex.COMPILATIONS.get() - before);

    Path root = temp.resolve("empty-root");
    Files.createDirectories(root.resolve("src/main/java"));
    AnalysisContext empty = context("empty-root", Map.of());
    JavacSourceSymbolIndex emptyIndex = withFlag(() -> JavacSourceSymbolIndex.of(empty))
        .orElseThrow();
    assertTrue(emptyIndex.diagnostics().stream().anyMatch(
        row -> row.code().equals("missing_source")), emptyIndex.diagnostics().toString());
  }

  @Test
  void outOfScopeRootsEmitMissingSource() throws IOException {
    long before = JavacSourceSymbolIndex.COMPILATIONS.get();
    Path root = temp.resolve("scope-mismatch");
    Path sourceRoot = root.resolve("src/main/java");
    writeFiles(sourceRoot, Map.of("demo/App.java", "package demo; public class App {}"));
    AnalysisContext context = AnalysisContext.builder()
        .id("scope-mismatch")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("javac-index-test")
        .javaRelease(17)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.TEST, true)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
    JavacSourceSymbolIndex index = withFlag(() -> JavacSourceSymbolIndex.of(context))
        .orElseThrow();
    assertTrue(index.diagnostics().stream().anyMatch(
        row -> row.code().equals("missing_source")), index.diagnostics().toString());
    assertEquals(0, JavacSourceSymbolIndex.COMPILATIONS.get() - before);
  }

  @Test
  void missingRequiredClasspathIsDiagnosed() throws IOException {
    AnalysisContext absent = context("classpath-absent", Map.of("demo/App.java",
        "package demo; public class App {}"),
        builder -> builder.dependencyClasspath(
            List.of(temp.resolve("classpath-absent/missing.jar")))
            .dependencyClasspathRequired(true));
    JavacSourceSymbolIndex index =
        withFlag(() -> JavacSourceSymbolIndex.of(absent)).orElseThrow();
    assertTrue(index.diagnostics().stream().anyMatch(
        row -> row.code().equals("missing_classpath")), index.diagnostics().toString());

    AnalysisContext empty = context("classpath-empty", Map.of("demo/App.java",
        "package demo; public class App {}"),
        builder -> builder.dependencyClasspath(List.of())
            .dependencyClasspathRequired(true));
    JavacSourceSymbolIndex emptyIndex = withFlag(() -> JavacSourceSymbolIndex.of(empty))
        .orElseThrow();
    assertTrue(emptyIndex.diagnostics().stream().anyMatch(
        row -> row.code().equals("missing_classpath")), emptyIndex.diagnostics().toString());
  }

  @Test
  void duplicateBinaryNamesAreFlaggedInBothFiles() throws IOException {
    Path root = temp.resolve("duplicate");
    Path first = root.resolve("r1");
    Path second = root.resolve("r2");
    writeFiles(first, Map.of("demo/Dup.java", "package demo; class Dup {}"));
    writeFiles(second, Map.of("demo/Dup.java", "package demo; class Dup {}"));
    AnalysisContext context = AnalysisContext.builder()
        .id("duplicate")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("javac-index-test")
        .javaRelease(17)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("r1", first, AnalysisContext.Scope.PRODUCTION, true)
        .sourceRoot("r2", second, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
    JavacSourceSymbolIndex index = withFlag(() -> JavacSourceSymbolIndex.of(context))
        .orElseThrow();
    List<JavacSourceSymbolIndex.TypeInfo> duplicates = index.type("demo.Dup");
    assertEquals(2, duplicates.size());
    assertTrue(duplicates.stream().allMatch(
        type -> type.shape().duplicateBinaryName()), duplicates.toString());
    assertEquals(1, index.typesIn(first.resolve("demo/Dup.java")).size());
    assertEquals(1, index.typesIn(second.resolve("demo/Dup.java")).size());
    assertTrue(index.diagnostics().stream().anyMatch(
        row -> row.code().equals("duplicate_binary_name")), index.diagnostics().toString());
  }

  @Test
  void repeatedBuildsAreDeterministicAndRecordCost() throws IOException {
    Map<String, String> files = Map.of("demo/App.java",
        "package demo; public class App { public void run() {} }");
    AnalysisContext first = context("determinism-a", files);
    AnalysisContext second = context("determinism-a", files);
    JavacSourceSymbolIndex indexA = withFlag(() -> JavacSourceSymbolIndex.of(first))
        .orElseThrow();
    JavacSourceSymbolIndex indexB = withFlag(() -> JavacSourceSymbolIndex.of(second))
        .orElseThrow();
    assertSameEntries(indexA, indexB);
    assertTrue(indexA.wallMillis() >= 0);
    assertTrue(indexA.peakHeapBytes() > 0);
  }

  @Test
  void flagDoesNotChangeScanOutput() throws IOException {
    Map<String, String> files = Map.of(
        "demo/Bridge.java", """
            package demo;
            import java.util.ServiceLoader;
            class Bridge {
              void audit() {
                ServiceLoader.load(Provider.class).findFirst()
                    .ifPresent(value -> value.record());
              }
            }
            """,
        "demo/Provider.java", "package demo; interface Provider { void record(); }",
        "demo/ProviderImpl.java",
        "package demo; final class ProviderImpl implements Provider { public void record() {} }");
    AnalysisContext context = context("wiring", files);
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    CallEdge edge = new CallEdge(
        new MethodSymbol("demo.Bridge", "audit", "()V"),
        new MethodSymbol("demo.ProviderImpl", "record", "()V"),
        3,
        "src/main/java/demo/Bridge.java",
        SemanticCallFacts.Certainty.DEFINITE,
        "service_loader");
    System.clearProperty(FLAG);
    ServiceLoaderDescriptorScanner.Scan scanOff = ServiceLoaderDescriptorScanner.scan(context);
    ServiceLoaderDescriptorScanner.Resolution resolutionOff =
        ServiceLoaderDescriptorScanner.resolve(context, edge, scanOff, scanner);

    System.setProperty(FLAG, "true");
    assertTrue(JavacSourceSymbolIndex.of(context).isPresent());
    ServiceLoaderDescriptorScanner.Scan scanOn = ServiceLoaderDescriptorScanner.scan(context);
    ServiceLoaderDescriptorScanner.Resolution resolutionOn = ServiceLoaderDescriptorScanner.resolve(
        context, edge, scanOn, new SourceMetadataScanner(context));
    assertEquals(scanOff, scanOn);
    assertEquals(resolutionOff, resolutionOn);
  }

  @Test
  void diagnosticsNeverContainAbsolutePaths() throws IOException {
    Path root = temp.resolve("paths");
    Path sourceRoot = root.resolve("src/main/java");
    writeFiles(sourceRoot, Map.of("demo/Broken.java",
        "package demo; public class Broken { Missing payload; }"));
    AnalysisContext context = AnalysisContext.builder()
        .id("paths")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("javac-index-test")
        .javaRelease(17)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot(sourceRoot.toString(), sourceRoot,
            AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspath(List.of(temp.resolve("elsewhere/missing.jar")))
        .dependencyClasspathRequired(true)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
    JavacSourceSymbolIndex index = withFlag(() -> JavacSourceSymbolIndex.of(context))
        .orElseThrow();
    List<JavacSourceSymbolIndex.IndexDiagnostic> rows = index.diagnostics();
    assertFalse(rows.isEmpty());
    for (JavacSourceSymbolIndex.IndexDiagnostic row : rows) {
      assertFalse(row.path().isBlank() && !row.code().equals("javac_global_error"),
          row.toString());
      assertFalse(Path.of(row.path()).isAbsolute(), row.toString());
      assertFalse(row.message().contains(temp.toString()), row.toString());
    }
    assertTrue(rows.stream().anyMatch(
        row -> row.code().equals("missing_classpath")
            && row.path().equals("<external>/missing.jar")), rows.toString());
    assertTrue(rows.stream().anyMatch(
        row -> row.path().endsWith("Broken.java")
            && row.path().equals("src/main/java/demo/Broken.java")), rows.toString());
  }

  @Test
  void unavailableCompilerYieldsADiagnosticAndIsCached() throws IOException {
    long before = JavacSourceSymbolIndex.COMPILATIONS.get();
    JavacSourceSymbolIndex.COMPILER.set(() -> null);
    AnalysisContext context = context("unavailable", Map.of("demo/App.java",
        "package demo; public class App {}"));
    System.setProperty(FLAG, "true");
    Optional<JavacSourceSymbolIndex> first = JavacSourceSymbolIndex.of(context);
    assertTrue(first.isPresent());
    assertTrue(first.get().diagnostics().stream().anyMatch(
        row -> row.code().equals("javac_unavailable")),
        first.get().diagnostics().toString());
    assertEquals(0, JavacSourceSymbolIndex.COMPILATIONS.get() - before);
    Optional<JavacSourceSymbolIndex> second = JavacSourceSymbolIndex.of(context);
    assertTrue(second.isPresent());
    assertSameEntries(first.get(), second.get());
    assertEquals(0, JavacSourceSymbolIndex.COMPILATIONS.get() - before);
  }

  private static JavaFileObject jarEntry() {
    return new javax.tools.SimpleJavaFileObject(
        java.net.URI.create("file:/tmp/Foo.class"), JavaFileObject.Kind.CLASS) {
      @Override
      public java.net.URI toUri() {
        return java.net.URI.create("jar:file:/libs/foo.jar!/demo/Foo.class");
      }

      @Override
      public String getName() {
        return "demo/Foo.class";
      }
    };
  }

  private static Diagnostic<JavaFileObject> error(JavaFileObject source) {
    return new Diagnostic<>() {
      @Override
      public Kind getKind() {
        return Kind.ERROR;
      }

      @Override
      public JavaFileObject getSource() {
        return source;
      }

      @Override
      public long getPosition() {
        return 0;
      }

      @Override
      public long getStartPosition() {
        return 0;
      }

      @Override
      public long getEndPosition() {
        return 0;
      }

      @Override
      public long getLineNumber() {
        return 0;
      }

      @Override
      public long getColumnNumber() {
        return 0;
      }

      @Override
      public String getCode() {
        return "compiler.err.test";
      }

      @Override
      public String getMessage(java.util.Locale locale) {
        return "test error";
      }
    };
  }

  private interface ContextCustomizer {
    void customize(AnalysisContext.Builder builder);
  }

  private AnalysisContext context(String id, Map<String, String> files) throws IOException {
    return context(id, files, builder -> {});
  }

  private AnalysisContext context(
      String id, Map<String, String> files, ContextCustomizer customizer) throws IOException {
    Path root = temp.resolve(id);
    Path sourceRoot = root.resolve("src/main/java");
    writeFiles(sourceRoot, files);
    AnalysisContext.Builder builder = AnalysisContext.builder()
        .id(id)
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("javac-index-test")
        .javaRelease(17)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting());
    customizer.customize(builder);
    return builder.build();
  }

  private <T> T withFlag(java.util.function.Supplier<T> action) {
    System.setProperty(FLAG, "true");
    try {
      return action.get();
    } finally {
      System.clearProperty(FLAG);
    }
  }

  private void assertSameEntries(JavacSourceSymbolIndex first, JavacSourceSymbolIndex second) {
    assertEquals(first.type("demo.App"), second.type("demo.App"));
    assertEquals(first.members("demo.App", "run"), second.members("demo.App", "run"));
    assertEquals(first.diagnostics(), second.diagnostics());
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
