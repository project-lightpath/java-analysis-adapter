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
 * Per-parameter annotation API on the javac source-symbol index (#37):
 * one annotation list per parameter, aligned with {@code parameterTypes()}
 * by construction (both derive from {@code ExecutableElement.getParameters()}).
 *
 * <p>The class runs same-thread and {@link Isolated isolated} because it
 * writes the {@code flpath.java.symbolindex} flag.
 */
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class JavacParameterAnnotationTest {
  /** Opt-out flag: defaults ON since #66; "false" pins the textual path. */
  private static final String FLAG = "flpath.java.symbolindex";

  @TempDir Path temp;

  @AfterEach
  void clearFlag() {
    System.clearProperty(FLAG);
  }

  @Test
  void declarationAnnotationsAlignWithParameterTypes() throws IOException {
    JavacSourceSymbolIndex index = index("aligned", Map.of(
        "demo/Dec.java", """
            package demo;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) public @interface Dec {
              int value() default 0;
            }
            """,
        "demo/Probe.java", """
            package demo;
            public class Probe {
              public void call(@Dec(1) String a, @Dec(2) String b) {}
            }
            """));
    JavacSourceSymbolIndex.MemberInfo call = member(index, "demo.Probe", "call");
    assertEquals(List.of("java.lang.String", "java.lang.String"), call.parameterTypes());
    assertEquals(2, call.parameterAnnotations().size());
    assertEquals("demo.Dec", call.parameterAnnotations().get(0).get(0).canonicalName());
    assertEquals("1", call.parameterAnnotations().get(0).get(0).values().get("value"));
    assertEquals("2", call.parameterAnnotations().get(1).get(0).values().get("value"));
  }

  @Test
  void zeroParameterMethodsYieldAnEmptyList() throws IOException {
    JavacSourceSymbolIndex index = index("empty", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              public void none() {}
            }
            """));
    assertTrue(member(index, "demo.Probe", "none").parameterAnnotations().isEmpty());
  }

  @Test
  void varargsPositionIsPresentButTypeUseIsNotADeclarationMirror() throws IOException {
    JavacSourceSymbolIndex index = index("varargs", Map.of(
        "demo/Use.java", """
            package demo;
            import java.lang.annotation.*;
            @Target(ElementType.TYPE_USE) public @interface Use {}
            """,
        "demo/Probe.java", """
            package demo;
            public class Probe {
              public void call(String @Use ... b) {}
            }
            """));
    JavacSourceSymbolIndex.MemberInfo call = member(index, "demo.Probe", "call");
    // Position: one parameter, one annotation entry.
    assertEquals(1, call.parameterAnnotations().size());
    // Position split: the type-use annotation is not a declaration mirror.
    assertTrue(call.parameterAnnotations().get(0).isEmpty());
    // The type spelling is unaffected by the type-use annotation.
    assertEquals(List.of("java.lang.String[]"), call.parameterTypes());
  }

  @Test
  void mixedDeclarationAndTypeUseSplitCleanly() throws IOException {
    JavacSourceSymbolIndex index = index("mixed", Map.of(
        "demo/Dec.java", """
            package demo;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) public @interface Dec {}
            """,
        "demo/Use.java", """
            package demo;
            import java.lang.annotation.*;
            @Target(ElementType.TYPE_USE) public @interface Use {}
            """,
        "demo/Probe.java", """
            package demo;
            public class Probe {
              public void call(@Dec String c) {}
              public void mixed(@Dec @Use String c) {}
            }
            """));
    JavacSourceSymbolIndex.MemberInfo mixed = member(index, "demo.Probe", "mixed");
    // Declaration mirror only; the type-use position is #23's concern.
    assertEquals(1, mixed.parameterAnnotations().get(0).size());
    assertEquals("demo.Dec", mixed.parameterAnnotations().get(0).get(0).canonicalName());
  }

  @Test
  void constructorParameterAnnotationsAlign() throws IOException {
    JavacSourceSymbolIndex index = index("ctor", Map.of(
        "demo/Dec.java", """
            package demo;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) public @interface Dec {
              int value() default 0;
            }
            """,
        "demo/Probe.java", """
            package demo;
            public class Probe {
              public Probe(@Dec(3) String a, int b) {}
            }
            """));
    JavacSourceSymbolIndex.MemberInfo constructor =
        index.type("demo.Probe").get(0).members().stream()
            .filter(member -> member.kind().equals("constructor"))
            .findFirst().orElseThrow();
    assertEquals(2, constructor.parameterAnnotations().size());
    assertEquals("demo.Dec", constructor.parameterAnnotations().get(0).get(0).canonicalName());
    assertTrue(constructor.parameterAnnotations().get(1).isEmpty());
  }

  @Test
  void overloadsKeepTheirOwnParameterAnnotations() throws IOException {
    JavacSourceSymbolIndex index = index("overloads", Map.of(
        "demo/Dec.java", """
            package demo;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) public @interface Dec {
              int value() default 0;
            }
            """,
        "demo/Probe.java", """
            package demo;
            public class Probe {
              public void run(@Dec(1) String a) {}
              public void run(@Dec(2) int a) {}
            }
            """));
    List<JavacSourceSymbolIndex.MemberInfo> overloads =
        index.type("demo.Probe").get(0).members().stream()
            .filter(member -> member.name().equals("run"))
            .toList();
    assertEquals(2, overloads.size());
    for (JavacSourceSymbolIndex.MemberInfo overload : overloads) {
      assertEquals(1, overload.parameterAnnotations().size());
      assertEquals(1, overload.parameterAnnotations().get(0).size());
    }
    assertTrue(overloads.stream().anyMatch(overload ->
        overload.parameterAnnotations().get(0).get(0).values().get("value").equals("1")));
    assertTrue(overloads.stream().anyMatch(overload ->
        overload.parameterAnnotations().get(0).get(0).values().get("value").equals("2")));
  }

  @Test
  void dollarAndUnicodeParameterAnnotationsRenderCanonically() throws IOException {
    JavacSourceSymbolIndex index = index("names", Map.of(
        "demo/Marks.java", """
            package demo;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) public @interface Mar$k {}
            @Retention(RetentionPolicy.RUNTIME) public @interface Ωmega {}
            """,
        "demo/Probe.java", """
            package demo;
            public class Probe {
              public void call(@Mar$k String a, @Ωmega String b) {}
            }
            """));
    JavacSourceSymbolIndex.MemberInfo call = member(index, "demo.Probe", "call");
    assertEquals("demo.Mar$k", call.parameterAnnotations().get(0).get(0).canonicalName());
    assertEquals("Mar$k", JavacAnnotationAccess.simpleName(
        call.parameterAnnotations().get(0).get(0)));
    assertEquals("demo.Ωmega", call.parameterAnnotations().get(1).get(0).canonicalName());
  }

  @Test
  void apiIsInertWithoutTheIndexAndLiveWithIt() throws IOException {
    AnalysisContext context = context("inert", Map.of(
        "demo/Dec.java", """
            package demo;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) public @interface Dec {}
            """,
        "demo/Probe.java", """
            package demo;
            public class Probe {
              public void call(@Dec String a) {}
            }
            """));
    System.setProperty(FLAG, "false"); // opt-out golden (#66)
    assertEquals(Optional.empty(), JavacSourceSymbolIndex.of(context));
    System.setProperty(FLAG, "true");
    JavacSourceSymbolIndex index = JavacSourceSymbolIndex.of(context).orElseThrow();
    assertEquals("demo.Dec",
        member(index, "demo.Probe", "call").parameterAnnotations().get(0).get(0)
            .canonicalName());
  }

  @Test
  void unresolvedParameterTypesKeepFailVisibleSemantics() throws IOException {
    JavacSourceSymbolIndex index = index("error", Map.of(
        "demo/Dec.java", """
            package demo;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) public @interface Dec {}
            """,
        "demo/Probe.java", """
            package demo;
            public class Probe {
              public void call(@Dec Missing a) {}
            }
            """));
    JavacSourceSymbolIndex.MemberInfo call = member(index, "demo.Probe", "call");
    // The member is error-typed (fail-visible) AND the declaration mirrors
    // are still rendered: the additive component does not lose them.
    assertTrue(call.errorType());
    assertEquals("demo.Dec", call.parameterAnnotations().get(0).get(0).canonicalName());
  }

  private JavacSourceSymbolIndex index(String id, Map<String, String> files)
      throws IOException {
    System.setProperty(FLAG, "true");
    return JavacSourceSymbolIndex.of(context(id, files)).orElseThrow();
  }

  private static JavacSourceSymbolIndex.MemberInfo member(
      JavacSourceSymbolIndex index, String typeName, String methodName) {
    return index.type(typeName).get(0).members().stream()
        .filter(candidate -> candidate.name().equals(methodName))
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
        .fixtureId("javac-parameter-annotation-test")
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
