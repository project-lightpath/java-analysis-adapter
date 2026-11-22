package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * javac annotation read layer: names, values, and the parameter-shape
 * repair, behind the {@code flpath.java.symbolindex} flag.
 *
 * <p>The class runs same-thread and {@link Isolated isolated} from all
 * other classes because it writes the flag, which production scan code
 * now reads.
 */
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class JavacAnnotationMigrationTest {
  private static final String FLAG = "flpath.java.symbolindex";

  @TempDir Path temp;

  @AfterEach
  void clearFlag() {
    System.clearProperty(FLAG);
  }

  @Test
  void flagOffOutputIsByteForByteTextual() throws IOException {
    AnalysisContext context = context("flag-off", Map.of(
        "demo/Probe.java", """
            package demo;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @interface Ann { int value() default 0; }
            public class Probe {
              public void plain(@Ann(value = 1) String p, String q) {}
            }
            """));
    SourceMetadataScanner off = new SourceMetadataScanner(context);
    System.clearProperty(FLAG);
    assertFalse(JavacAnnotationAccess.of(off).enabled());
    SourceMetadataScanner on = new SourceMetadataScanner(context);
    System.setProperty(FLAG, "true");
    try {
      // Unrelated scan surfaces are unaffected by the flag (the accessor
      // is call-site-local); the migrated seam's own flag-off parity is
      // asserted exactly by the end-to-end proxy cell.
      assertEquals(off.routes(context.framework()).toString(),
          on.routes(context.framework()).toString());
    } finally {
      System.clearProperty(FLAG);
    }
  }

  @Test
  void dollarAndUnicodeAnnotationNamesResolve() throws IOException {
    AnalysisContext context = context("names", Map.of(
        "demo/Names.java", """
            package demo;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @interface Mar$k {}
            @Retention(RetentionPolicy.RUNTIME) @interface Ωmega {}
            public class Names {
              @Mar$k private String field;
              @Ωmega public void method() {}
            }
            """));
    JavacSourceSymbolIndex index = withFlag(() -> JavacSourceSymbolIndex.of(context))
        .orElseThrow();
    List<JavacSourceSymbolIndex.TypeInfo> type = index.type("demo.Names");
    assertEquals(1, type.size());
    List<JavacSourceSymbolIndex.AnnotationInfo> fieldAnnotations =
        type.get(0).members().stream()
            .filter(member -> member.name().equals("field"))
            .findFirst().orElseThrow().annotations();
    assertEquals(1, fieldAnnotations.size());
    assertEquals("demo.Mar$k", fieldAnnotations.get(0).canonicalName());
    assertEquals("Mar$k", JavacAnnotationAccess.simpleName(fieldAnnotations.get(0)));

    List<JavacSourceSymbolIndex.AnnotationInfo> methodAnnotations =
        type.get(0).members().stream()
            .filter(member -> member.name().equals("method"))
            .findFirst().orElseThrow().annotations();
    assertEquals("demo.Ωmega", methodAnnotations.get(0).canonicalName());
    assertEquals("Ωmega", JavacAnnotationAccess.simpleName(methodAnnotations.get(0)));
  }

  @Test
  void qualifiedAnnotationNameResolvesCanonically() throws IOException {
    AnalysisContext context = context("qualified", Map.of(
        "pkg/Ann.java", "package pkg; public @interface Ann {}",
        "demo/Qualified.java", """
            package demo;
            public class Qualified {
              @pkg.Ann private String field;
            }
            """));
    JavacSourceSymbolIndex index = withFlag(() -> JavacSourceSymbolIndex.of(context))
        .orElseThrow();
    JavacSourceSymbolIndex.AnnotationInfo annotation = index
        .type("demo.Qualified").get(0).members().stream()
        .filter(member -> member.name().equals("field"))
        .findFirst().orElseThrow().annotations().get(0);
    assertEquals("pkg.Ann", annotation.canonicalName());
    assertEquals("Ann", JavacAnnotationAccess.simpleName(annotation));
  }

  @Test
  void annotationValuesRenderMinusQuoting() throws IOException {
    AnalysisContext context = context("values", Map.of(
        "demo/Val.java", """
            package demo;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) public @interface Val {
              String name();
              int count() default 0;
              Class<?>[] types() default {};
            }
            """,
        "demo/ValUser.java", """
            package demo;
            public class ValUser {
              @Val(name = "app", count = 2, types = {Object.class}) private String field;
            }
            """));
    JavacSourceSymbolIndex index = withFlag(() -> JavacSourceSymbolIndex.of(context))
        .orElseThrow();
    JavacSourceSymbolIndex.AnnotationInfo annotation = index
        .type("demo.ValUser").get(0).members().stream()
        .filter(member -> member.name().equals("field"))
        .findFirst().orElseThrow().annotations().get(0);
    assertEquals("app", JavacAnnotationAccess.unquoted(annotation.values().get("name")));
    assertEquals("2", annotation.values().get("count"));
    assertEquals("{java.lang.Object}", annotation.values().get("types"));
  }

  @Test
  void typeUseAndVarargsAnnotationsRepairParameterShapes() throws IOException {
    AnalysisContext context = context("repair", Map.of(
        "demo/Ann.java", """
            package demo;
            import java.lang.annotation.*;
            @Target(ElementType.TYPE_USE) @interface Ann {}
            """,
        "demo/Probe.java", """
            package demo;
            public class Probe {
              public void annotated(String @$Ann ... args, @Ann(value = 1) String p) {}
              public void plain(String p, int q) {}
            }
            """));
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    JavacAnnotationAccess access = JavacAnnotationAccess.of(scanner);
    Optional<String> varargs = access.repairedParameterType(
        "String @$Ann ... args", "demo.Probe", "annotated", 2, 0);
    assertTrue(varargs.isPresent(), "the varargs parameter must be repaired");
    assertEquals("java.lang.String[]", varargs.orElseThrow());
    Optional<String> argumented = access.repairedParameterType(
        "@Ann(value = 1) String p", "demo.Probe", "annotated", 2, 1);
    assertEquals("java.lang.String", argumented.orElseThrow());
    // Clean parameters keep the textual spelling.
    assertFalse(access.repairTriggered("String p"));
    assertFalse(access.repairTriggered("@Deprecated String p"));
    assertFalse(access.repairTriggered("List<@Ann String> p"));
    assertTrue(access.repairTriggered("@Mar$k String p"));
    assertTrue(access.repairTriggered("@Ann(value = 1) String p"));
    assertTrue(access.repairTriggered("@MaΩga String p"));
  }

  @Test
  void annotationLikeTextInStringsAndCommentsIsNotAnAnnotation() throws IOException {
    AnalysisContext context = context("decoy", Map.of(
        "demo/Decoy.java", """
            package demo;
            public class Decoy {
              // @NotAnAnnotation String comment
              private String text = "@AlsoNot(value = 1) String literal";
              public void method(String p) {}
            }
            """));
    JavacSourceSymbolIndex index = withFlag(() -> JavacSourceSymbolIndex.of(context))
        .orElseThrow();
    JavacSourceSymbolIndex.MemberInfo field = index.type("demo.Decoy").get(0)
        .members().stream()
        .filter(member -> member.name().equals("text"))
        .findFirst().orElseThrow();
    assertTrue(field.annotations().isEmpty(), field.toString());
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    assertFalse(JavacAnnotationAccess.of(scanner)
        .repairTriggered("String p"));
  }

  @Test
  void sameArityOverloadsFallBackToTextualValues() throws IOException {
    AnalysisContext context = context("overload", Map.of(
        "demo/Overload.java", """
            package demo;
            public class Overload {
              public void run(String a) {}
              public void run(int a) {}
            }
            """));
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    JavacAnnotationAccess access = JavacAnnotationAccess.of(scanner);
    // Two same-arity overloads: the correlation must not guess which
    // one a raw parameter belongs to — flag-on falls back to textual.
    assertTrue(access.member("demo.Overload", "run", 1).isEmpty());
    assertTrue(access.repairedParameterType(
        "@Ann(value = 1) String a", "demo.Overload", "run", 1, 0).isEmpty());
  }

  @Test
  void unindexedSourcesFallBackSilently() throws IOException {
    AnalysisContext context = context("unindexed", Map.of(
        "demo/App.java", "package demo; public class App { public void run(String p) {} }"));
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    JavacAnnotationAccess access = JavacAnnotationAccess.of(scanner);
    // A type the index never saw (the file is indexed; the type is not):
    // the correlation falls back silently.
    assertTrue(access.member("demo.Missing", "run", 1).isEmpty());
  }

  @Test
  void repeatedLookupsAreDeterministic() throws IOException {
    Map<String, String> files = Map.of(
        "demo/App.java", "package demo; public class App { public void run(String p) {} }");
    AnalysisContext first = context("determinism", files);
    AnalysisContext second = context("determinism", files);
    JavacSourceSymbolIndex indexA = withFlag(() -> JavacSourceSymbolIndex.of(first))
        .orElseThrow();
    JavacSourceSymbolIndex indexB = withFlag(() -> JavacSourceSymbolIndex.of(second))
        .orElseThrow();
    assertEquals(indexA.type("demo.App"), indexB.type("demo.App"));
  }

  @Test
  void accessorCarriesNoNameCaptureRegex() throws IOException {
    // Cell 12: the name-capturing regex family is absent from the read
    // layer, and the trigger is a character-class scan.
    java.nio.file.Path source = Path.of(
        "src/main/java/com/projectlightpath/analysis/java/JavacAnnotationAccess.java");
    assertTrue(Files.exists(source), source.toString());
    String content = Files.readString(source);
    assertFalse(content.contains("@[A-Za-z_]"), "no name-capture regex literal");
    assertFalse(content.contains("@("), "no annotation-argument matcher");
  }

  @Test
  void flagOnScanDoesNotPoisonTheTextualCache() throws IOException {
    Map<String, String> files = Map.of(
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
            """);
    AnalysisContext context = context("cache", files);
    // An independent textual baseline: identical sources at a different
    // root use a different static-cache key, so its extracts are filled
    // purely textually and cannot share the warmed entry.
    AnalysisContext baseline = context("cache-baseline", files);
    System.clearProperty(FLAG);
    List<SourceMetadataScannerState.MethodInfo> textualApi =
        new SourceMetadataScanner(baseline)
            .methods(file(baseline, "cache-baseline", "demo/Api.java"));
    SourceMetadataScanner warm = new SourceMetadataScanner(context);
    System.setProperty(FLAG, "true");
    // The flag-on scan fills the static cache for this root through the
    // migrated seam; the repaired descriptor proves the accessor ran.
    List<DynamicFeature> warmed = warm.dynamicFeatures();
    assertTrue(warmed.stream().anyMatch(feature ->
        feature.target().startsWith("demo.Api#")
            && feature.targetDescriptor().equals("(java.lang.String)")),
        warmed.toString());
    System.clearProperty(FLAG);
    // A flag-off re-read of the warmed root must equal the independent
    // textual baseline — the cache was not poisoned by index values.
    assertEquals(textualApi.toString(),
        warm.methods(file(context, "cache", "demo/Api.java")).toString());
  }

  @Test
  void supplementaryLetterTailsTriggerRepair() throws IOException {
    AnalysisContext context = context("surrogate", Map.of(
        "demo/App.java", "package demo; public class App {}"));
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    JavacAnnotationAccess access = JavacAnnotationAccess.of(scanner);
    // U+10400 reads as a surrogate pair via charAt; the code-point-aware
    // tail check still detects the unconsumed annotation name.
    assertTrue(access.repairTriggered("@A𐐀 String p"));
    assertTrue(access.repairTriggered("@𐐀mega String p"));
    assertFalse(access.repairTriggered("@Ann String p"));
  }

  @Test
  void unresolvedArrayComponentsFallBackToTextual() throws IOException {
    AnalysisContext context = context("array-error", Map.of(
        "demo/Ann.java", """
            package demo;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @interface Ann { int value() default 0; }
            """,
        "demo/Probe.java", """
            package demo;
            public class Probe {
              public void call(@Ann(value = 1) Missing[] p) {}
            }
            """));
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    JavacAnnotationAccess access = JavacAnnotationAccess.of(scanner);
    // The array is ARRAY-kinded but its component is unresolved: the member
    // is error-typed, so the repair falls back to the textual spelling.
    assertTrue(access.repairedParameterType(
        "@Ann(value = 1) Missing[] p", "demo.Probe", "call", 1, 0).isEmpty());
  }

  @Test
  void nestedTypeMethodsInInterfaceFilesFallBackToTextual() throws IOException {
    AnalysisContext context = context("nested-owner", Map.of(
        "demo/Ann.java", """
            package demo;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @interface Ann { int value() default 0; }
            """,
        "demo/Api.java", """
            package demo;
            public interface Api {
              void call(@Ann(value = 1) String p);
              class Nested {
                public void call(@Ann(value = 1) String p) {}
              }
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
    System.setProperty(FLAG, "true");
    List<DynamicFeature> features = new SourceMetadataScanner(context).dynamicFeatures();
    // The interface file declares a nested type, so the owner of each
    // textual method cannot be proven: no descriptor is repaired.
    assertTrue(features.stream().noneMatch(feature ->
        feature.target().startsWith("demo.Api#")
            && feature.targetDescriptor().equals("(java.lang.String)")),
        features.toString());
  }

  @Test
  void mixedNameTriggerShapesRepair() throws IOException {
    AnalysisContext context = context("mixed", Map.of(
        "demo/Mixed.java", """
            package demo;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @interface MaΩga {}
            @Retention(RetentionPolicy.RUNTIME) @interface Mar$k {}
            """,
        "demo/Probe.java", """
            package demo;
            public class Probe {
              public void mixed(@MaΩga String a, @Mar$k String b) {}
            }
            """));
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    JavacAnnotationAccess access = JavacAnnotationAccess.of(scanner);
    assertTrue(access.repairTriggered("@MaΩga String a"));
    assertEquals("java.lang.String", access.repairedParameterType(
        "@MaΩga String a", "demo.Probe", "mixed", 2, 0).orElseThrow());
    assertEquals("java.lang.String", access.repairedParameterType(
        "@Mar$k String b", "demo.Probe", "mixed", 2, 1).orElseThrow());
  }

  @Test
  void cleanGenericParametersKeepTheirTextualSpelling() throws IOException {
    AnalysisContext context = context("generic", Map.of(
        "demo/Generic.java", """
            package demo;
            import java.lang.annotation.*;
            @Target(ElementType.TYPE_USE) @interface Ann {}
            """,
        "demo/Probe.java", """
            package demo;
            import java.util.List;
            public class Probe {
              public void run(List<@Ann String> a, java.util.List<String> b) {}
            }
            """));
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    JavacAnnotationAccess access = JavacAnnotationAccess.of(scanner);
    // Clean parameters are not repaired: the textual spelling survives.
    assertTrue(access.repairedParameterType(
        "List<@Ann String> a", "demo.Probe", "run", 2, 0).isEmpty());
    // A triggered parameter in the same descriptor records the delta: the
    // repair returns the real parameter's canonical type, not a fabricated
    // String spelling.
    assertEquals("java.util.List", access.repairedParameterType(
        "@Ann(value = 1) String a", "demo.Probe", "run", 2, 0).orElse(""));
  }

  @Test
  void proxyDescriptorRepairsTriggeredParametersEndToEnd() throws IOException {
    AnalysisContext context = context("proxy", Map.of(
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
    System.clearProperty(FLAG);
    List<DynamicFeature> off = new SourceMetadataScanner(context).dynamicFeatures();
    System.setProperty(FLAG, "true");
    List<DynamicFeature> on = new SourceMetadataScanner(context).dynamicFeatures();
    // Migration point 1, end to end: the textual descriptor keeps the broken
    // shape flag-off, and the javac repair records the canonical delta flag-on.
    assertTrue(off.stream().anyMatch(feature ->
            feature.target().startsWith("demo.Api#")
            && feature.targetDescriptor().equals("((value=1)String)")),
        off.toString());
    assertTrue(on.stream().anyMatch(feature ->
            feature.target().startsWith("demo.Api#")
            && feature.targetDescriptor().equals("(java.lang.String)")),
        on.toString());
  }

  @Test
  void commentTextInParameterListsSkipsIndexedRepair() throws IOException {
    AnalysisContext context = context("comment-arity", Map.of(
        "demo/Ann.java", """
            package demo;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @interface Mar$k {}
            """,
        "demo/Api.java", """
            package demo;
            public interface Api {
              void call(String p);
              void call(@Mar$k int a /* ( */, int b);
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
    System.setProperty(FLAG, "true");
    List<DynamicFeature> features = new SourceMetadataScanner(context).dynamicFeatures();
    // The comment's '(' hides the separator from the split, so the derived
    // arity (1) would select the wrong overload: the repair is skipped and
    // the descriptor stays textual.
    assertTrue(features.stream().noneMatch(feature ->
        feature.target().startsWith("demo.Api#")
            && feature.targetDescriptor().equals("(java.lang.String)")),
        features.toString());
    assertTrue(features.stream().anyMatch(feature ->
        feature.target().startsWith("demo.Api#")
            && feature.targetDescriptor().contains("int")),
        features.toString());
  }

  private <T> T withFlag(java.util.function.Supplier<T> action) {
    System.setProperty(FLAG, "true");
    try {
      return action.get();
    } finally {
      System.clearProperty(FLAG);
    }
  }

  private SourceMetadataScannerState.SourceFile file(
      AnalysisContext context, String id, String relative) {
    Path root = temp.resolve(id).resolve("src/main/java").resolve(relative);
    return new SourceMetadataScanner(context).files().stream()
        .filter(item -> item.path().equals(root.toAbsolutePath().normalize()))
        .findFirst().orElseThrow();
  }

  private AnalysisContext context(String id, Map<String, String> files) throws IOException {
    Path root = temp.resolve(id);
    Path sourceRoot = root.resolve("src/main/java");
    for (Map.Entry<String, String> entry : files.entrySet()) {
      Path file = sourceRoot.resolve(entry.getKey());
      Files.createDirectories(file.getParent());
      Files.writeString(file, entry.getValue());
    }
    return AnalysisContext.builder()
        .id(id)
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("javac-annotation-test")
        .javaRelease(17)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
  }
}
