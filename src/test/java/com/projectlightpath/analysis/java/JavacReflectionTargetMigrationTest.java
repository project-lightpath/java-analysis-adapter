package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Source-bound reflection targets use javac shapes for issue #25: a
 * reflection literal that names a type or member declared by the selected
 * source files is keyed by its binary name, proven DEFINITE with the
 * javac parameter/annotation shapes recorded; runtime strings (names the
 * index does not carry, non-literal arguments, unresolvable receivers)
 * keep the pre-existing UNRESOLVED abstention; error, duplicate, and
 * ambiguity shapes downgrade to POSSIBLE with a per-fact diagnostic.
 *
 * <p>Every cell asserts through the full analyze path (scanner features
 * to facts), never vacuously. Flag-off goldens pin the default output.
 * The class runs same-thread and {@link Isolated isolated} because it
 * writes the flag.
 */
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class JavacReflectionTargetMigrationTest {
  /** Opt-out flag: defaults ON since #66; "false" pins the textual path. */
  private static final String FLAG = "flpath.java.symbolindex";

  @TempDir Path temp;

  @AfterEach
  void clearFlag() {
    System.clearProperty(FLAG);
  }

  // F1: source-defined class target -> DEFINITE, binary keying, evidence.
  @Test
  void sourceDefinedForNameTargetIsDefinite() throws IOException {
    AnalysisContext context = context("f1", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws ClassNotFoundException {
                Class.forName("demo.Present");
              }
            }
            class Present { }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "Class.forName", "#audit");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, fact.certainty(), fact.toString());
    assertEquals(SemanticCallFacts.Status.EMITTED, fact.status());
    assertEquals("demo.Present", fact.callee());
    assertEquals("demo.Present", fact.evidence().get(0).attributes().get("javacSymbol"));
    assertEquals("true", fact.evidence().get(0).attributes().get("sourceBound"));
    assertEquals("class", fact.evidence().get(0).attributes().get("reflectionTargetKind"));
  }

  // F2: nested target named by its BINARY name -> keyed $-joined.
  @Test
  void nestedBinaryNameForNameTargetIsDefinite() throws IOException {
    AnalysisContext context = context("f2", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws ClassNotFoundException {
                Class.forName("demo.Outer$Inner");
              }
            }
            class Outer { static class Inner { } }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "Class.forName", "#audit");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, fact.certainty(), fact.toString());
    assertEquals("demo.Outer$Inner", fact.callee());
  }

  // F3: canonical dotted spelling of a nested type -> binary-name mismatch.
  @Test
  void canonicalDottedNestedSpellingIsNotPromoted() throws IOException {
    AnalysisContext context = context("f3", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws ClassNotFoundException {
                Class.forName("demo.Outer.Inner");
              }
            }
            class Outer { static class Inner { } }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "Class.forName", "#audit");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, fact.certainty(), fact.toString());
    assertEquals("binary_name_mismatch",
        fact.evidence().get(0).attributes().get("diagnostic"));
    assertFalse(fact.evidence().get(0).attributes().containsKey("sourceBound"));
  }

  // F4: runtime-only literal -> never promoted (byte-identical abstention).
  @Test
  void runtimeOnlyLiteralStaysUnresolved() throws IOException {
    AnalysisContext context = context("f4", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws ClassNotFoundException {
                Class.forName("java.lang.String");
              }
            }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "Class.forName", "#audit");
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED, fact.certainty());
    assertEquals(SemanticCallFacts.Status.ABSTAINED, fact.status());
    assertEquals("java.lang.String#unknown", fact.callee());
    assertFalse(fact.evidence().get(0).attributes().containsKey("javacSymbol"));
  }

  // F5: non-literal forName -> unchanged.
  @Test
  void nonLiteralForNameStaysUnresolved() throws IOException {
    AnalysisContext context = context("f5", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws ClassNotFoundException {
                Class.forName(System.getProperty("probe.target"));
              }
            }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "Class.forName", "#audit");
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED, fact.certainty());
    assertEquals("unknown#unknown", fact.callee());
  }

  // F6: loadClass class target -> DEFINITE.
  @Test
  void sourceDefinedLoadClassTargetIsDefinite() throws IOException {
    AnalysisContext context = context("f6", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void load(ClassLoader loader) throws ClassNotFoundException {
                loader.loadClass("demo.Present");
              }
            }
            class Present { }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "ClassLoader.loadClass", "#load");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, fact.certainty(), fact.toString());
    assertEquals("demo.Present", fact.callee());
  }

  // F7: '$' and Unicode name shapes -> exact binary keying.
  @Test
  void dollarAndUnicodeSimpleNamesAreDefinite() throws IOException {
    AnalysisContext context = context("f7", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void one() throws ClassNotFoundException { Class.forName("demo.Cash$Flow"); }
              void two() throws ClassNotFoundException { Class.forName("demo.Café"); }
            }
            """,
        "demo/Cash$Flow.java", "package demo; public class Cash$Flow {}\n",
        "demo/Café.java", "package demo; public class Café {}\n"));
    System.setProperty(FLAG, "true");
    List<SemanticCallFacts.Fact> facts = facts(context);
    assertEquals("demo.Cash$Flow",
        fact(facts, "Class.forName", "#one").callee());
    assertEquals(SemanticCallFacts.Certainty.DEFINITE,
        fact(facts, "Class.forName", "#one").certainty());
    assertEquals("demo.Café",
        fact(facts, "Class.forName", "#two").callee());
    assertEquals(SemanticCallFacts.Certainty.DEFINITE,
        fact(facts, "Class.forName", "#two").certainty());
  }

  // M1: chained member target -> DEFINITE with javac shapes.
  @Test
  void chainedMemberTargetIsDefiniteWithShapes() throws IOException {
    AnalysisContext context = context("m1", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws Exception {
                Class.forName("demo.Present").getDeclaredMethod("handle", String.class);
              }
            }
            class Present {
              void handle(String value) { }
            }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "getDeclaredMethod", "#audit");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, fact.certainty(), fact.toString());
    assertEquals("demo.Present#handle", fact.callee());
    assertEquals("(Ljava/lang/String;)V", fact.evidence().get(0).attributes().get("targetDescriptor"));
    assertEquals("java.lang.String", fact.evidence().get(0).attributes().get("parameterTypes"));
  }

  // M2: overload differentiation by parameter vector.
  @Test
  void overloadSitesCorrelateTheirOwnMember() throws IOException {
    AnalysisContext context = context("m2", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void ints() throws Exception {
                Class.forName("demo.Present").getDeclaredMethod("run", int.class);
              }
              void strings() throws Exception {
                Class.forName("demo.Present").getDeclaredMethod("run", String.class);
              }
            }
            class Present {
              void run(int count) { }
              void run(String label) { }
            }
            """));
    System.setProperty(FLAG, "true");
    List<SemanticCallFacts.Fact> facts = facts(context);
    SemanticCallFacts.Fact ints = fact(facts, "getDeclaredMethod", "#ints");
    SemanticCallFacts.Fact strings = fact(facts, "getDeclaredMethod", "#strings");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, ints.certainty(), ints.toString());
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, strings.certainty(), strings.toString());
    assertEquals("(I)V", ints.evidence().get(0).attributes().get("targetDescriptor"));
    assertEquals("(Ljava/lang/String;)V",
        strings.evidence().get(0).attributes().get("targetDescriptor"));
  }

  // M3: varargs selected by its erased array shape.
  @Test
  void varargsMemberMatchesArrayClassLiteral() throws IOException {
    AnalysisContext context = context("m3", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws Exception {
                Class.forName("demo.Present").getDeclaredMethod("x", String[].class);
              }
            }
            class Present {
              void x(String... rest) { }
            }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "getDeclaredMethod", "#audit");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, fact.certainty(), fact.toString());
    assertEquals("([Ljava/lang/String;)V",
        fact.evidence().get(0).attributes().get("targetDescriptor"));
  }

  // M4: generic erasure — List.class selects List<String>.
  @Test
  void genericErasureMatchesRawClassLiteral() throws IOException {
    AnalysisContext context = context("m4", Map.of(
        "demo/Probe.java", """
            package demo;
            import java.util.List;
            public class Probe {
              void audit() throws Exception {
                Class.forName("demo.Present").getDeclaredMethod("x", List.class);
              }
            }
            class Present {
              void x(List<String> values) { }
            }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "getDeclaredMethod", "#audit");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, fact.certainty(), fact.toString());
    assertEquals("(Ljava/util/List;)V",
        fact.evidence().get(0).attributes().get("targetDescriptor"));
  }

  // M5: primitive array shape.
  @Test
  void primitiveArrayShapeMatches() throws IOException {
    AnalysisContext context = context("m5", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws Exception {
                Class.forName("demo.Present").getDeclaredMethod("x", int[].class);
              }
            }
            class Present {
              void x(int[] counts) { }
            }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "getDeclaredMethod", "#audit");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, fact.certainty(), fact.toString());
    assertEquals("([I)V", fact.evidence().get(0).attributes().get("targetDescriptor"));
  }

  // M6: class-literal receiver (simple and qualified spellings).
  @Test
  void classLiteralReceiverResolvesTheOwner() throws IOException {
    AnalysisContext context = context("m6", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void simple() throws Exception { Present.class.getMethod("run"); }
              void qualified() throws Exception { demo.Present.class.getMethod("run"); }
            }
            class Present {
              public void run() { }
            }
            """));
    System.setProperty(FLAG, "true");
    List<SemanticCallFacts.Fact> facts = facts(context);
    assertEquals("demo.Present#run", fact(facts, "getMethod", "#simple").callee());
    assertEquals(SemanticCallFacts.Certainty.DEFINITE,
        fact(facts, "getMethod", "#simple").certainty());
    assertEquals("demo.Present#run", fact(facts, "getMethod", "#qualified").callee());
    assertEquals(SemanticCallFacts.Certainty.DEFINITE,
        fact(facts, "getMethod", "#qualified").certainty());
  }

  // M7: variable receiver — dataflow out of scope, stays unresolved.
  @Test
  void variableReceiverStaysUnresolved() throws IOException {
    AnalysisContext context = context("m7", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws Exception {
                Class<?> type = Class.forName("demo.Present");
                type.getMethod("run");
              }
            }
            class Present { public void run() { } }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "getMethod", "#audit");
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED, fact.certainty());
    assertEquals("unknown#run", fact.callee());
  }

  // M8: getClass() receiver — stays unresolved.
  @Test
  void getClassReceiverStaysUnresolved() throws IOException {
    AnalysisContext context = context("m8", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws Exception {
                getClass().getMethod("run");
              }
            }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "getMethod", "#audit");
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED, fact.certainty());
    assertEquals("unknown#run", fact.callee());
  }

  // M9: member name not declared by the resolved owner -> POSSIBLE.
  @Test
  void undeclaredMemberNameIsPossible() throws IOException {
    AnalysisContext context = context("m9", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws Exception {
                Class.forName("demo.Present").getMethod("missing");
              }
            }
            class Present { }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "getMethod", "#audit");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, fact.certainty(), fact.toString());
    assertEquals("member_not_declared",
        fact.evidence().get(0).attributes().get("diagnostic"));
  }

  // M10: constructor target keyed #<init>.
  @Test
  void constructorTargetIsDefiniteWithInitKeying() throws IOException {
    AnalysisContext context = context("m10", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws Exception {
                Class.forName("demo.Present").getDeclaredConstructor(String.class);
              }
            }
            class Present {
              Present(String label) { }
            }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "getDeclaredConstructor", "#audit");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, fact.certainty(), fact.toString());
    assertEquals("demo.Present#<init>", fact.callee());
    assertEquals("(Ljava/lang/String;)V",
        fact.evidence().get(0).attributes().get("targetDescriptor"));
  }

  // M12: inherited member is never attributed to the superclass.
  @Test
  void inheritedMemberIsNeverMisAttributed() throws IOException {
    AnalysisContext context = context("m12", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws Exception {
                Class.forName("demo.Child").getMethod("run");
              }
            }
            class Base { public void run() { } }
            class Child extends Base { }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "getMethod", "#audit");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, fact.certainty(), fact.toString());
    assertEquals("member_not_declared",
        fact.evidence().get(0).attributes().get("diagnostic"));
    assertFalse(fact.callee().startsWith("demo.Base"), fact.callee());
  }

  // M13: nested-type parameter literals — qualified resolves, simple fails closed.
  @Test
  void nestedParameterLiteralsResolveQualifiedOnly() throws IOException {
    AnalysisContext context = context("m13", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void qualified() throws Exception {
                Class.forName("demo.Present").getDeclaredMethod("x", demo.Outer.Inner.class);
              }
              void simple() throws Exception {
                Class.forName("demo.Present").getDeclaredMethod("x", Inner.class);
              }
            }
            class Outer { static class Inner { } }
            class Present {
              void x(Outer.Inner value) { }
            }
            """));
    System.setProperty(FLAG, "true");
    List<SemanticCallFacts.Fact> facts = facts(context);
    SemanticCallFacts.Fact qualified = fact(facts, "getDeclaredMethod", "#qualified");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, qualified.certainty(), qualified.toString());
    assertEquals("(Ldemo/Outer$Inner;)V",
        qualified.evidence().get(0).attributes().get("targetDescriptor"));
    SemanticCallFacts.Fact simple = fact(facts, "getDeclaredMethod", "#simple");
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED, simple.certainty());
  }

  // M14: non-literal member name -> unchanged.
  @Test
  void nonLiteralMemberNameStaysUnresolved() throws IOException {
    AnalysisContext context = context("m14", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit(String name) throws Exception {
                Class.forName("demo.Present").getMethod(name);
              }
            }
            class Present { public void run() { } }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "getMethod", "#audit");
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED, fact.certainty());
    assertEquals("unknown#unknown", fact.callee());
  }

  // M15: implicit default constructor — javac synthesizes it into the index
  // (probe-verified: kind=constructor, parameterTypes=[]), so the zero-arg
  // lookup resolves DEFINITE; the original "not indexed, fails closed"
  // premise was factually wrong and the cell pins the true behavior.
  @Test
  void implicitDefaultConstructorResolvesDefinite() throws IOException {
    AnalysisContext context = context("m15", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws Exception {
                Class.forName("demo.NoCtor").getDeclaredConstructor();
              }
            }
            class NoCtor { }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "getDeclaredConstructor", "#audit");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, fact.certainty(), fact.toString());
    assertEquals("demo.NoCtor#<init>", fact.callee());
    assertEquals("()V", fact.evidence().get(0).attributes().get("targetDescriptor"));
  }

  // M16: '$'-simple-name owner constructor — kind-filtered selection.
  @Test
  void dollarSimpleNameOwnerConstructorIsDefinite() throws IOException {
    AnalysisContext context = context("m16", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws Exception {
                Class.forName("demo.Cash$Flow").getDeclaredConstructor(String.class);
              }
            }
            """,
        "demo/Cash$Flow.java", """
            package demo;
            public class Cash$Flow {
              public Cash$Flow(String label) { }
            }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "getDeclaredConstructor", "#audit");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, fact.certainty(), fact.toString());
    assertEquals("demo.Cash$Flow#<init>", fact.callee());
  }

  // M17: assignment pre-chain boundary.
  @Test
  void assignmentReceiverIsDefinite() throws IOException {
    AnalysisContext context = context("m17", Map.of(
        "demo/Probe.java", """
            package demo;
            import java.lang.reflect.Method;
            public class Probe {
              void audit() throws Exception {
                Method method = Present.class.getMethod("run");
              }
            }
            class Present {
              public void run() { }
            }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "getMethod", "#audit");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, fact.certainty(), fact.toString());
    assertEquals("demo.Present#run", fact.callee());
  }

  // M19: control-flow and cast pre-chain boundaries.
  @Test
  void controlFlowAndCastReceiversAreDefinite() throws IOException {
    AnalysisContext context = context("m19", Map.of(
        "demo/Probe.java", """
            package demo;
            import java.lang.reflect.Method;
            public class Probe {
              void audit(boolean condition) throws Exception {
                if (condition) {
                  Class.forName("demo.Present").getDeclaredMethod("handle");
                }
                Method method = (Method) Present.class.getMethod("run");
              }
            }
            class Present {
              void handle() { }
              public void run() { }
            }
            """));
    System.setProperty(FLAG, "true");
    List<SemanticCallFacts.Fact> facts = facts(context);
    SemanticCallFacts.Fact controlFlow = fact(facts, "getDeclaredMethod", "#audit");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, controlFlow.certainty(),
        controlFlow.toString());
    assertEquals("demo.Present#handle", controlFlow.callee());
    SemanticCallFacts.Fact cast = fact(facts, "getMethod", "#audit");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, cast.certainty(), cast.toString());
    assertEquals("demo.Present#run", cast.callee());
  }

  // V1: getMethod cannot promote non-public members.
  @Test
  void getMethodCannotPromoteNonPublicMembers() throws IOException {
    AnalysisContext context = context("v1", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws Exception {
                Present.class.getMethod("run");
              }
            }
            class Present {
              private void run() { }
            }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "getMethod", "#audit");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, fact.certainty(), fact.toString());
    assertEquals("visibility_mismatch",
        fact.evidence().get(0).attributes().get("diagnostic"));
  }

  // V3: a FIELD sharing the lookup name is never a method target.
  @Test
  void getMethodCannotPromoteFieldNamesakes() throws IOException {
    AnalysisContext context = context("v3", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws Exception {
                Present.class.getMethod("run");
              }
            }
            class Present {
              public int run;
            }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "getMethod", "#audit");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, fact.certainty(), fact.toString());
    assertEquals("member_not_declared",
        fact.evidence().get(0).attributes().get("diagnostic"));
  }

  // V4: an enum constant sharing the lookup name is never a method target.
  @Test
  void getMethodCannotPromoteEnumConstantNamesakes() throws IOException {
    AnalysisContext context = context("v4", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws Exception {
                Color.class.getMethod("RED");
              }
            }
            enum Color { RED }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "getMethod", "#audit");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, fact.certainty(), fact.toString());
    assertEquals("member_not_declared",
        fact.evidence().get(0).attributes().get("diagnostic"));
  }

  // M20: the forName literal anchor is offset-faithful under indentation —
  // a quoted-first-argument call in the receiver prefix must not supply
  // the owner literal.
  @Test
  void deeplyIndentedForNameAnchorReadsTheRightLiteral() throws IOException {
    AnalysisContext context = context("m20", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws Exception {
                                verify("demo.Evil", Class.forName("demo.Good").getDeclaredMethod("m"));
              }
              void verify(String name, Object value) { }
            }
            class Evil { public void m() {} }
            class Good { public void m() {} }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "getDeclaredMethod", "#audit");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, fact.certainty(), fact.toString());
    assertEquals("demo.Good#m", fact.callee());
  }

  // M21: interface members are implicitly public — the visibility gate
  // must not over-abstain for them.
  @Test
  void interfaceMembersPassTheVisibilityGate() throws IOException {
    AnalysisContext context = context("m21", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws Exception {
                Work.class.getMethod("run");
              }
            }
            interface Work {
              void run();
            }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "getMethod", "#audit");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, fact.certainty(), fact.toString());
    assertEquals("demo.Work#run", fact.callee());
  }

  // M22 (RED, CodeRabbit CR-1): partially qualified class-literal owner.
  @Test
  void partiallyQualifiedClassLiteralOwnerResolves() throws IOException {
    AnalysisContext context = context("m22", Map.of(
        "demo/Probe.java", """
            package demo;
            import demo.Outer;
            public class Probe {
              void audit() throws Exception {
                Outer.Inner.class.getMethod("run");
              }
            }
            """,
        "demo/Outer.java", """
            package demo;
            public class Outer {
              public static class Inner {
                public void run() { }
              }
            }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "getMethod", "#audit");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, fact.certainty(), fact.toString());
    assertEquals("demo.Outer$Inner#run", fact.callee());
  }

  // M23 (RED, CodeRabbit CR-1): a forName receiver literal that names the
  // canonical spelling cannot load the class — the member target is not
  // proven even when the index resolves the canonical.
  @Test
  void forNameCanonicalReceiverIsNotPromoted() throws IOException {
    AnalysisContext context = context("m23", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws Exception {
                Class.forName("demo.Outer.Inner").getDeclaredMethod("run");
              }
            }
            class Outer { static class Inner { public void run() { } } }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "getDeclaredMethod", "#audit");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, fact.certainty(), fact.toString());
    assertEquals("binary_name_mismatch",
        fact.evidence().get(0).attributes().get("diagnostic"));
  }

  // M24 (RED, CodeRabbit CR-1): inner-class constructors take the
  // enclosing instance as the reflection-visible first parameter — the
  // zero-arg site mismatches, the enclosing-type site matches.
  @Test
  void innerClassConstructorComparesInTheReflectionDomain() throws IOException {
    AnalysisContext context = context("m24", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void zeroArg() throws Exception {
                Class.forName("demo.Outer$Inner").getDeclaredConstructor();
              }
              void enclosing() throws Exception {
                Class.forName("demo.Outer$Inner").getDeclaredConstructor(Outer.class);
              }
            }
            class Outer {
              class Inner { Inner() { } }
            }
            """));
    System.setProperty(FLAG, "true");
    List<SemanticCallFacts.Fact> facts = facts(context);
    SemanticCallFacts.Fact zeroArg = fact(facts, "getDeclaredConstructor", "#zeroArg");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, zeroArg.certainty(), zeroArg.toString());
    assertEquals("shape_mismatch",
        zeroArg.evidence().get(0).attributes().get("diagnostic"));
    SemanticCallFacts.Fact enclosing = fact(facts, "getDeclaredConstructor", "#enclosing");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, enclosing.certainty(),
        enclosing.toString());
    assertEquals("demo.Outer$Inner#<init>", enclosing.callee());
    assertEquals("(Ldemo/Outer;)V",
        enclosing.evidence().get(0).attributes().get("targetDescriptor"));
  }

  // M25 (control, CodeRabbit CR-1): static nested constructors keep the
  // source parameter vector.
  @Test
  void staticNestedConstructorKeepsSourceVector() throws IOException {
    AnalysisContext context = context("m25", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws Exception {
                Class.forName("demo.Outer$Stat").getDeclaredConstructor();
              }
            }
            class Outer {
              static class Stat { Stat() { } }
            }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "getDeclaredConstructor", "#audit");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, fact.certainty(), fact.toString());
    assertEquals("demo.Outer$Stat#<init>", fact.callee());
  }

  // M26 (RED, CodeRabbit CR-2): enum constructors carry the synthetic
  // name/ordinal parameters in the reflection domain.
  @Test
  void enumConstructorsCarrySyntheticParameters() throws IOException {
    AnalysisContext context = context("m26", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void zeroArg() throws Exception {
                Class.forName("demo.Color").getDeclaredConstructor();
              }
              void synthetics() throws Exception {
                Class.forName("demo.Color").getDeclaredConstructor(String.class, int.class);
              }
            }
            enum Color { RED; Color() { } }
            """));
    System.setProperty(FLAG, "true");
    List<SemanticCallFacts.Fact> facts = facts(context);
    SemanticCallFacts.Fact zeroArg = fact(facts, "getDeclaredConstructor", "#zeroArg");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, zeroArg.certainty(), zeroArg.toString());
    assertEquals("shape_mismatch",
        zeroArg.evidence().get(0).attributes().get("diagnostic"));
    SemanticCallFacts.Fact synthetics = fact(facts, "getDeclaredConstructor", "#synthetics");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, synthetics.certainty(),
        synthetics.toString());
    assertEquals("demo.Color#<init>", synthetics.callee());
    assertEquals("(Ljava/lang/String;I)V",
        synthetics.evidence().get(0).attributes().get("targetDescriptor"));
  }

  // M27 (RED, CodeRabbit CR-2): the enclosing binary is derived by removing
  // the owner's simple-name suffix, never by the last '$' — a '$' can belong
  // to the simple name (demo.Outer.Inner$Part's enclosing is demo.Outer).
  @Test
  void dollarSimpleNamesKeepTheTrueEnclosingBinary() throws IOException {
    AnalysisContext context = context("m27", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws Exception {
                Class.forName("demo.Outer$Inner$Part").getDeclaredConstructor(Outer.class);
              }
            }
            class Outer {
              class Inner$Part { Inner$Part() { } }
            }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "getDeclaredConstructor", "#audit");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, fact.certainty(), fact.toString());
    assertEquals("demo.Outer$Inner$Part#<init>", fact.callee());
    assertEquals("(Ldemo/Outer;)V",
        fact.evidence().get(0).attributes().get("targetDescriptor"));
  }

  // M28 (RED, CodeRabbit CR-2 outside-diff): an index-proven unnamed-package
  // binary name (JLS 13.1: binary == simple name) renders a valid descriptor.
  @Test
  void unnamedPackageTargetsRenderDescriptors() throws IOException {
    AnalysisContext context = context("m28", Map.of(
        "Owner.java", """
            public class Owner {
              public Token make() { return null; }
            }
            class Token { }
            """,
        "Probe.java", """
            public class Probe {
              void audit() throws Exception {
                Class.forName("Owner").getDeclaredMethod("make");
              }
            }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "getDeclaredMethod", "#audit");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, fact.certainty(), fact.toString());
    assertEquals("Owner#make", fact.callee());
    assertEquals("()LToken;",
        fact.evidence().get(0).attributes().get("targetDescriptor"));
  }

  // A1 (RED, CodeRabbit CR-1): a parameter type with duplicate source
  // declarations is ambiguous — the vector is unresolvable, never a match.
  @Test
  void duplicateParameterTypesAbstain() throws IOException {
    Path first = sourceRoot("a1-first");
    Path second = sourceRoot("a1-second");
    write(first, "demo/Token.java", "package demo; public class Token { }\n");
    write(second, "demo/Token.java", "package demo; public class Token { }\n");
    write(first, "demo/Probe.java", """
        package demo;
        public class Probe {
          void audit() throws Exception {
            Class.forName("demo.Present").getDeclaredMethod("m", Token.class);
          }
        }
        class Present {
          void m(Token token) { }
        }
        """);
    Path root = first.getParent().getParent().getParent();
    AnalysisContext context = AnalysisContext.builder()
        .id("a1")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("javac-reflection-target-test")
        .javaRelease(17)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("first/src/main/java", first, AnalysisContext.Scope.PRODUCTION, true)
        .sourceRoot("second/src/main/java", second, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "getDeclaredMethod", "#audit");
    assertFalse(fact.certainty() == SemanticCallFacts.Certainty.DEFINITE, fact.toString());
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED, fact.certainty());
  }

  // V2: getConstructor visibility gate; the declared variant sees it.
  @Test
  void constructorVisibilityGate() throws IOException {
    AnalysisContext context = context("v2", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void lookup() throws Exception {
                Class.forName("demo.Widget").getConstructor(String.class);
              }
              void declared() throws Exception {
                Class.forName("demo.Widget").getDeclaredConstructor(String.class);
              }
            }
            class Widget {
              private Widget(String label) { }
            }
            """));
    System.setProperty(FLAG, "true");
    List<SemanticCallFacts.Fact> facts = facts(context);
    SemanticCallFacts.Fact lookup = fact(facts, "getConstructor", "#lookup");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, lookup.certainty(), lookup.toString());
    assertEquals("visibility_mismatch",
        lookup.evidence().get(0).attributes().get("diagnostic"));
    SemanticCallFacts.Fact declared = fact(facts, "getDeclaredConstructor", "#declared");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, declared.certainty(), declared.toString());
    assertEquals("demo.Widget#<init>", declared.callee());
  }

  // M11: annotation names (including '$' and Unicode) recorded from javac.
  @Test
  void annotationShapesAreRecordedFromJavac() throws IOException {
    AnalysisContext context = context("m11", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws Exception {
                Class.forName("demo.Present").getDeclaredMethod("handle", String.class);
              }
            }
            class Present {
              @Märk$Odd
              void handle(@SuppressWarnings("unused") String value) { }
            }
            @interface Märk$Odd { }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "getDeclaredMethod", "#audit");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, fact.certainty(), fact.toString());
    String annotations = fact.evidence().get(0).attributes().get("annotationNames");
    assertTrue(annotations != null && annotations.contains("demo.Märk$Odd"),
        String.valueOf(annotations));
  }

  // E1: error-typed member -> POSSIBLE with a diagnostic.
  @Test
  void errorTypedMemberIsPossible() throws IOException {
    AnalysisContext context = context("e1", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws Exception {
                Class.forName("demo.Present").getDeclaredMethod("handle");
              }
            }
            class Present {
              void handle(missing.Thing value) { }
            }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "getDeclaredMethod", "#audit");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, fact.certainty(), fact.toString());
    assertEquals("error_member",
        fact.evidence().get(0).attributes().get("diagnostic"));
  }

  // E3: duplicate-flagged owner -> POSSIBLE, never a picked side.
  @Test
  void duplicateFlaggedOwnerIsPossible() throws IOException {
    AnalysisContext context = duplicateOwnerContext("e3");
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "Class.forName", "#audit");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, fact.certainty(), fact.toString());
    assertEquals("duplicate_target",
        fact.evidence().get(0).attributes().get("diagnostic"));
  }

  // E4: unrelated per-file javac errors do not block a clean target.
  @Test
  void unrelatedFileErrorsDoNotBlockCleanTargets() throws IOException {
    AnalysisContext context = context("e4", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws ClassNotFoundException {
                Class.forName("demo.Present");
              }
            }
            class Present { }
            """,
        "demo/Broken.java", """
            package demo;
            public class Broken {
              missing.Dep marker;
            }
            """));
    System.setProperty(FLAG, "true");
    SemanticCallFacts.Fact fact = fact(facts(context), "Class.forName", "#audit");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, fact.certainty(), fact.toString());
    assertEquals("demo.Present", fact.callee());
  }

  // E2: index integrity failure (required missing classpath) -> no promotion.
  @Test
  void missingClasspathBlocksAllPromotion() throws IOException {
    AnalysisContext context = context("e2", true, Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws ClassNotFoundException {
                Class.forName("demo.Present");
              }
            }
            class Present { }
            """));
    System.setProperty(FLAG, "true");
    List<SemanticCallFacts.Fact> facts = facts(context);
    assertFalse(facts.stream()
        .anyMatch(item -> item.certainty() == SemanticCallFacts.Certainty.DEFINITE),
        facts.toString());
    SemanticCallFacts.Fact fact = fact(facts, "Class.forName", "#audit");
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED, fact.certainty());
    assertEquals("demo.Present#unknown", fact.callee());
  }

  // E5: missing source root -> partial index cannot prove uniqueness.
  @Test
  void missingSourceRootBlocksAllPromotion() throws IOException {
    Path sourceRoot = sourceRoot("e5");
    write(sourceRoot, "demo/Probe.java", """
        package demo;
        public class Probe {
          void audit() throws ClassNotFoundException {
            Class.forName("demo.Present");
          }
        }
        class Present { }
        """);
    Path root = sourceRoot.getParent().getParent();
    AnalysisContext context = AnalysisContext.builder()
        .id("e5")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("javac-reflection-target-test")
        .javaRelease(17)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        // A second REQUIRED root whose directory does not exist: the index
        // proceeds over the present root and records missing_source_root,
        // so the partial index must not prove any uniqueness.
        .sourceRoot("src/other/java", root.resolve("src/other/java"),
            AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
    System.setProperty(FLAG, "true");
    List<SemanticCallFacts.Fact> facts = facts(context);
    assertFalse(facts.stream()
        .anyMatch(item -> item.certainty() == SemanticCallFacts.Certainty.DEFINITE),
        facts.toString());
    SemanticCallFacts.Fact fact = fact(facts, "Class.forName", "#audit");
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED, fact.certainty());
  }

  // G-series: flag-off goldens — exact current textual output.
  @Test
  void flagOffForNameKeepsTextualTarget() throws IOException {
    AnalysisContext context = context("g-forname", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              void audit() throws Exception {
                Class.forName("demo.Present").getDeclaredMethod("handle", String.class);
              }
            }
            class Present {
              void handle(String value) { }
            }
            """));
    System.setProperty(FLAG, "false"); // opt-out golden (#66)
    List<SemanticCallFacts.Fact> facts = facts(context);
    SemanticCallFacts.Fact forName = fact(facts, "Class.forName", "#audit");
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED, forName.certainty());
    assertEquals(SemanticCallFacts.Status.ABSTAINED, forName.status());
    assertEquals("demo.Present#unknown", forName.callee());
    assertFalse(forName.evidence().get(0).attributes().containsKey("javacSymbol"));
    SemanticCallFacts.Fact lookup = fact(facts, "getDeclaredMethod", "#audit");
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED, lookup.certainty());
    assertEquals("unknown#handle", lookup.callee());
    assertFalse(lookup.evidence().get(0).attributes().containsKey("targetDescriptor"));
  }

  private List<SemanticCallFacts.Fact> facts(AnalysisContext context) {
    List<DynamicFeature> features = new SourceMetadataScanner(context).dynamicFeatures();
    SemanticCallFacts all = new JavaAnalysisAdapter().analyze(context, new AnalysisGraph(
        List.of(),
        features,
        java.util.Set.of(),
        "SootUp",
        "3.0.1",
        0,
        0));
    return all.allFacts().stream()
        .filter(item -> item.relationshipType().equals("reflection"))
        .toList();
  }

  private SemanticCallFacts.Fact fact(
      List<SemanticCallFacts.Fact> facts, String symbol, String callerSuffix) {
    return facts.stream()
        .filter(item -> item.caller().endsWith(callerSuffix)
            && item.evidence().get(0).symbols().contains(symbol))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.toString()));
  }

  private AnalysisContext context(String id, Map<String, String> files) throws IOException {
    return context(id, false, files);
  }

  private AnalysisContext context(String id, boolean classpathRequired, Map<String, String> files)
      throws IOException {
    Path sourceRoot = sourceRoot(id);
    for (Map.Entry<String, String> entry : files.entrySet()) {
      write(sourceRoot, entry.getKey(), entry.getValue());
    }
    Path root = sourceRoot.getParent().getParent();
    AnalysisContext.Builder builder = AnalysisContext.builder()
        .id(root.getFileName().toString())
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("javac-reflection-target-test")
        .javaRelease(17)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true);
    if (!classpathRequired) {
      // E2 owns the required-classpath trigger; every other cell keeps the
      // optional classpath so missing_classpath does not co-fire.
      builder.dependencyClasspathRequired(false);
    } else {
      builder.dependencyClasspath(List.of(root.resolve("lib/absent.jar")));
    }
    return builder
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
  }

  /** E3: two roots declaring the same binary name -> duplicate-flagged. */
  private AnalysisContext duplicateOwnerContext(String id) throws IOException {
    Path root = temp.resolve(id);
    Path first = root.resolve("first/src/main/java");
    Path second = root.resolve("second/src/main/java");
    write(first, "demo/Dup.java", "package demo; public class Dup { }\n");
    write(second, "demo/Dup.java", "package demo; public class Dup { }\n");
    write(first, "demo/Probe.java", """
        package demo;
        public class Probe {
          void audit() throws ClassNotFoundException {
            Class.forName("demo.Dup");
          }
        }
        """);
    return AnalysisContext.builder()
        .id(id)
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("javac-reflection-target-test")
        .javaRelease(17)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("first/src/main/java", first, AnalysisContext.Scope.PRODUCTION, true)
        .sourceRoot("second/src/main/java", second, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
  }

  private Path sourceRoot(String id) {
    return temp.resolve(id).resolve("src/main/java");
  }

  private static void write(Path sourceRoot, String relative, String content)
      throws IOException {
    Path file = sourceRoot.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }
}
