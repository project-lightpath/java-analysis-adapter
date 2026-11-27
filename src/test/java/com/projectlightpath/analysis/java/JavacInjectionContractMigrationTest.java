package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * javac index shapes for the injection-contract type matching behind the
 * {@code flpath.java.symbolindex} flag, for issue #61.
 *
 * <p>Each repair cell asserts the flag-on index-shape answer and its
 * flag-off textual golden (count-form where fact identity strings are
 * involved); each abstention/parity cell pins that a decline leaves the
 * textual answer byte-identical. The class runs same-thread and
 * {@link Isolated isolated} because it writes the flag.
 */
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class JavacInjectionContractMigrationTest {
  /** Opt-out flag: defaults ON since #66; "false" pins the textual path. */
  private static final String FLAG = "flpath.java.symbolindex";

  @TempDir Path temp;

  @AfterEach
  void clearFlag() {
    System.clearProperty(FLAG);
  }

  // ------------------------------------------------------------------
  // A. Requested-side shapes
  // ------------------------------------------------------------------

  // A1: the acceptance cell — an on-demand JDK import is a textual vacuum
  // (probe P2: resolveReferenceType("List") is empty under java.util.*);
  // the correlated member's parameter shape repairs the raw comparison and
  // the binding row promotes the match to DEFINITE flag-on. Flag-off keeps
  // the textual abstention (UNRESOLVED, no candidate).
  @Test
  void onDemandJdkImportConstructorInjectionMatchesProducerDefinitely()
      throws IOException {
    Path sourceRoot = root("a1");
    write(sourceRoot, "demo/Config.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component public class Config {
          @org.springframework.context.annotation.Bean
          public java.util.List<String> listBean() { return null; }
        }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import java.util.*;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(List list) { }
        }
        """);
    AnalysisContext context = springContext(sourceRoot, List.of(producerRow(
        "listBean", "java.util.List", "demo.Config", "listBean")));
    System.setProperty(FLAG, "false");
    SemanticCallFacts off = analyze(context);
    System.setProperty(FLAG, "true");
    SemanticCallFacts on = analyze(context);
    assertEquals(1, injections(off, "demo.Consumer").size(),
        "flag-off golden: one injection fact, unresolved");
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED,
        injection(off, "demo.Consumer").certainty(),
        "flag-off golden: the textual vacuum abstains");
    assertEquals(1, injections(on, "demo.Consumer").size(),
        on.toString());
    SemanticCallFacts.Fact fact = injection(on, "demo.Consumer");
    assertTrue(fact.callee().contains("listBean"),
        "flag-on: the repaired raw comparison matches the producer: "
            + fact.callee());
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, fact.certainty(),
        "flag-on: the index-proven match is DEFINITE-eligible");
  }

  // A2: the field-injection shape arm (fields carry type shapes).
  @Test
  void fieldInjectionOnDemandJdkImportMatches() throws IOException {
    Path sourceRoot = root("a2");
    write(sourceRoot, "demo/Config.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component public class Config {
          @org.springframework.context.annotation.Bean
          public java.util.List<String> listBean() { return null; }
        }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import java.util.*;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired private List list;
        }
        """);
    AnalysisContext context = springContext(sourceRoot);
    System.setProperty(FLAG, "false");
    SemanticCallFacts off = analyze(context);
    System.setProperty(FLAG, "true");
    SemanticCallFacts on = analyze(context);
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED,
        injection(off, "demo.Consumer").certainty());
    assertTrue(injection(on, "demo.Consumer").callee().contains("listBean"),
        "flag-on: the field member's shape repairs the match");
  }

  // A3: the method-injection shape arm.
  @Test
  void methodInjectionOnDemandJdkImportMatches() throws IOException {
    Path sourceRoot = root("a3");
    write(sourceRoot, "demo/Config.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component public class Config {
          @org.springframework.context.annotation.Bean
          public java.util.List<String> listBean() { return null; }
        }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import java.util.*;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public void setList(List list) { }
        }
        """);
    AnalysisContext context = springContext(sourceRoot);
    System.setProperty(FLAG, "false");
    SemanticCallFacts off = analyze(context);
    System.setProperty(FLAG, "true");
    SemanticCallFacts on = analyze(context);
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED,
        injection(off, "demo.Consumer#setList").certainty());
    assertTrue(injection(on, "demo.Consumer#setList").callee()
            .contains("listBean"),
        "flag-on: the correlated method's parameter shape repairs the match");
  }

  // A4: the file-INDEPENDENT identity — the same spelling under an explicit
  // import and under an on-demand import keeps ONE identity: both consumers
  // match the same producer flag-on; flag-off only the explicit one does.
  @Test
  void importStyleIndependenceKeepsOneIdentity() throws IOException {
    Path sourceRoot = root("a4");
    write(sourceRoot, "demo/Config.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component public class Config {
          @org.springframework.context.annotation.Bean
          public java.util.List<String> listBean() { return null; }
        }
        """);
    write(sourceRoot, "demo/ExplicitConsumer.java", """
        package demo;
        import java.util.List;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class ExplicitConsumer {
          @Autowired public ExplicitConsumer(List list) { }
        }
        """);
    write(sourceRoot, "demo/OnDemandConsumer.java", """
        package demo;
        import java.util.*;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class OnDemandConsumer {
          @Autowired public OnDemandConsumer(List list) { }
        }
        """);
    AnalysisContext context = springContext(sourceRoot);
    System.setProperty(FLAG, "false");
    SemanticCallFacts off = analyze(context);
    System.setProperty(FLAG, "true");
    SemanticCallFacts on = analyze(context);
    assertTrue(injection(off, "demo.ExplicitConsumer").callee()
            .contains("listBean"),
        "flag-off golden: the explicit import resolves textually");
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED,
        injection(off, "demo.OnDemandConsumer").certainty(),
        "flag-off golden: the on-demand import is a textual vacuum");
    assertTrue(injection(on, "demo.ExplicitConsumer").callee()
            .contains("listBean"));
    assertTrue(injection(on, "demo.OnDemandConsumer").callee()
            .contains("listBean"),
        "flag-on: both import styles keep ONE type identity");
  }

  // A5: a $-named annotation corrupts the textual parameter spelling
  // (probe P7: "@Mar$k Greeter" extracts type "$k Greeter") AND pollutes
  // the parameter qualifiers — the shape arm repairs the TYPE, but the
  // qualifier ladder must still decline the match: no bypass.
  @Test
  void dollarAnnotationCorruptionRepairsShapeWithoutBypassingQualifiers()
      throws IOException {
    Path sourceRoot = root("a5");
    write(sourceRoot, "demo/Greeter.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component public class Greeter { }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(@Mar$k Greeter greeter) { }
        }
        """);
    AnalysisContext context = springContext(sourceRoot);
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.InjectionPoint point = injectionPoint(
        scanner, "demo.Consumer", "<init>");
    // Seam level: the corrupted spelling is repaired to the index shape.
    assertEquals(Optional.of("demo.Greeter"),
        JavacInjectionTypeShapes.of(scanner).requestedShape(point, 0),
        "the $-corrupted spelling repairs to the member's parameter shape");
    // End to end: the qualifier pollution still declines the match.
    SemanticCallFacts on = analyze(context);
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED,
        injection(on, "demo.Consumer").certainty(),
        "the shape arm must not bypass the qualifier ladder");
  }

  // A6: the candidate-side arm — an unqualified nested producer return
  // spelling is a textual vacuum (uppercase first segment, no same-package
  // top-level match); the producer correlation's canonical return repairs
  // the candidate raw.
  @Test
  void producerNestedTypeCandidateShapeRepairs() throws IOException {
    Path sourceRoot = root("a6");
    write(sourceRoot, "demo/Outer.java", """
        package demo;
        public class Outer { public static class Inner { } }
        """);
    write(sourceRoot, "demo/Config.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component public class Config {
          @org.springframework.context.annotation.Bean
          public Outer.Inner innerBean() { return null; }
        }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(demo.Outer.Inner inner) { }
        }
        """);
    AnalysisContext context = springContext(sourceRoot);
    System.setProperty(FLAG, "false");
    SemanticCallFacts off = analyze(context);
    System.setProperty(FLAG, "true");
    SemanticCallFacts on = analyze(context);
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED,
        injection(off, "demo.Consumer").certainty(),
        "flag-off golden: the nested producer spelling is a textual vacuum");
    assertTrue(injection(on, "demo.Consumer").callee().contains("innerBean"),
        "flag-on: the producer correlation's canonical return repairs "
            + "the candidate raw");
  }

  // A7: a same-arity constructor overload pair never repairs — the unique
  // member lookup abstains on ambiguity, fail closed.
  @Test
  void sameArityOverloadPairAbstains() throws IOException {
    Path sourceRoot = root("a7");
    write(sourceRoot, "demo/Config.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component public class Config {
          @org.springframework.context.annotation.Bean
          public java.util.List<String> listBean() { return null; }
          @org.springframework.context.annotation.Bean
          public java.util.Set<String> setBean() { return null; }
        }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import java.util.*;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(List list) { }
          @Autowired public Consumer(Set set) { }
        }
        """);
    AnalysisContext context = springContext(sourceRoot);
    System.setProperty(FLAG, "false");
    SemanticCallFacts off = analyze(context);
    System.setProperty(FLAG, "true");
    SemanticCallFacts on = analyze(context);
    assertEquals(injections(off, "demo.Consumer").size(),
        injections(on, "demo.Consumer").size(),
        "an ambiguous overload group abstains: flag-on is byte-identical");
    for (SemanticCallFacts.Fact fact : injections(on, "demo.Consumer")) {
      assertEquals(SemanticCallFacts.Certainty.UNRESOLVED, fact.certainty(),
          "the ambiguous overload keeps the textual abstention: " + fact);
    }
  }

  // A8: an ambiguous owner never repairs — the same FQN declared in two
  // selected roots is a duplicate binary name (flagged unusable), so the
  // unique-owner lookup declines. (A multi-type file whose owner name
  // resolves uniquely repairs LEGITIMATELY — the lookup's owner guard is
  // name-scoped, unlike the correlation seam's file-scoped guard; probe
  // P-RED measured that repair as correct.)
  @Test
  void ambiguousOwnerDeclines() throws IOException {
    Path project = temp.resolve("a8");
    Path sourceRoot = Files.createDirectories(
        project.resolve("src/main/java"));
    Path generatedRoot = Files.createDirectories(
        project.resolve("generated-src/main/java"));
    write(sourceRoot, "demo/Config.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component public class Config {
          @org.springframework.context.annotation.Bean
          public java.util.List<String> listBean() { return null; }
        }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import java.util.*;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(List list) { }
        }
        """);
    write(generatedRoot, "demo/Consumer.java", """
        package demo;
        import java.util.*;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(List list) { }
        }
        """);
    Path classes = Files.createDirectories(project.resolve("classes"));
    AnalysisContext context = AnalysisContext.builder()
        .id("a8")
        .framework(AnalysisContext.Framework.SPRING_BOOT)
        .frameworkVersion("2.7.18")
        .fixtureId("javac-injection-contract-test")
        .javaRelease(17)
        .repositoryRoot(project)
        .applicationClasses(classes)
        .selectedProfile("production")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot,
            AnalysisContext.Scope.PRODUCTION, true)
        .generatedSourceRoot("generated-src/main/java", generatedRoot,
            AnalysisContext.Scope.PRODUCTION, true, true,
            "generated-root-fingerprint")
        .dependencyClasspathRequired(false)
        .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
            "spring-bean-annotations", "source_annotations",
            AnalysisContext.MetadataStatus.OBSERVED, "src/main/java"))
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.InjectionPoint point = injectionPoint(
        scanner, "demo.Consumer", "<init>");
    assertEquals(Optional.empty(),
        JavacInjectionTypeShapes.of(scanner).requestedShape(point, 0),
        "a duplicate binary name is flagged unusable: the unique-owner "
            + "lookup declines");
    SemanticCallFacts on = analyze(context);
    assertTrue(injections(on, "demo.Consumer").stream()
            .allMatch(fact -> fact.certainty()
                == SemanticCallFacts.Certainty.UNRESOLVED),
        "the ambiguous owner keeps the textual abstention: " + on);
  }

  // A9: an error-typed member (an unresolvable parameter elsewhere in the
  // signature) blocks the member's shapes entirely.
  @Test
  void errorTypedMemberAbstains() throws IOException {
    Path sourceRoot = root("a9");
    write(sourceRoot, "demo/Config.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component public class Config {
          @org.springframework.context.annotation.Bean
          public java.util.List<String> listBean() { return null; }
        }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import java.util.*;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(List list, missing.Thing thing) { }
        }
        """);
    AnalysisContext context = springContext(sourceRoot);
    System.setProperty(FLAG, "false");
    SemanticCallFacts off = analyze(context);
    System.setProperty(FLAG, "true");
    SemanticCallFacts on = analyze(context);
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED,
        injection(off, "demo.Consumer").certainty());
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED,
        injection(on, "demo.Consumer").certainty(),
        "an error-typed member abstains: flag-on is byte-identical");
  }

  // A10: the phantom-constructor guard — a void method spelled like the
  // class inflates the textual constructor-site count above the index's;
  // the whole file's constructor shapes decline.
  @Test
  void phantomConstructorGuardDeclines() throws IOException {
    Path sourceRoot = root("a10");
    write(sourceRoot, "demo/Config.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component public class Config {
          @org.springframework.context.annotation.Bean
          public java.util.List<String> listBean() { return null; }
        }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import java.util.*;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(List list) { }
          public void Consumer(Set set) { }
        }
        """);
    AnalysisContext context = springContext(sourceRoot);
    System.setProperty(FLAG, "false");
    SemanticCallFacts off = analyze(context);
    System.setProperty(FLAG, "true");
    SemanticCallFacts on = analyze(context);
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED,
        injection(off, "demo.Consumer").certainty());
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED,
        injection(on, "demo.Consumer").certainty(),
        "a phantom constructor site declines the whole file: "
            + "flag-on is byte-identical");
  }

  // A11 (probe P5a): a varargs parameter keeps its textual parity — the
  // extractor normalizes "..." to "[]" and both arms render
  // java.lang.String[].
  @Test
  void varargsParameterKeepsParity() throws IOException {
    Path sourceRoot = root("a11");
    write(sourceRoot, "demo/Config.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component public class Config {
          @org.springframework.context.annotation.Bean
          public String[] stringBean() { return null; }
        }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(String... args) { }
        }
        """);
    AnalysisContext context = springContext(sourceRoot);
    System.setProperty(FLAG, "false");
    SemanticCallFacts off = analyze(context);
    System.setProperty(FLAG, "true");
    SemanticCallFacts on = analyze(context);
    assertTrue(injection(off, "demo.Consumer").callee().contains("stringBean"),
        "flag-off golden: the textual varargs resolution matches");
    assertTrue(injection(on, "demo.Consumer").callee().contains("stringBean"),
        "flag-on: the index shape agrees (java.lang.String[] parity)");
  }

  // A12 (probe P5b, pinned by the #23 corpus): a type-variable parameter
  // erases to its bound in the index — the shape repairs the unresolvable
  // "T" spelling.
  @Test
  void typeVariableParameterErasesToBound() throws IOException {
    Path sourceRoot = root("a12");
    write(sourceRoot, "demo/Config.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component public class Config {
          @org.springframework.context.annotation.Bean
          public java.lang.Number numberBean() { return null; }
        }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public <T extends Number> Consumer(T value) { }
        }
        """);
    AnalysisContext context = springContext(sourceRoot);
    System.setProperty(FLAG, "false");
    SemanticCallFacts off = analyze(context);
    System.setProperty(FLAG, "true");
    SemanticCallFacts on = analyze(context);
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED,
        injection(off, "demo.Consumer").certainty(),
        "flag-off golden: the T spelling is a textual vacuum");
    assertTrue(injection(on, "demo.Consumer").callee().contains("numberBean"),
        "flag-on: the erased bound (java.lang.Number) repairs the match");
  }

  // A13: the Quarkus arm — the same repair through @Inject constructor
  // injection.
  @Test
  void quarkusConstructorInjectionRepairs() throws IOException {
    Path sourceRoot = root("a13");
    write(sourceRoot, "demo/Config.java", """
        package demo;
        import jakarta.enterprise.context.ApplicationScoped;
        @ApplicationScoped public class Config {
          @jakarta.enterprise.inject.Produces
          public java.util.List<String> listBean() { return null; }
        }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import java.util.*;
        import jakarta.enterprise.context.ApplicationScoped;
        import jakarta.inject.Inject;
        @ApplicationScoped public class Consumer {
          @Inject public Consumer(List list) { }
        }
        """);
    AnalysisContext context = quarkusContext(sourceRoot);
    System.setProperty(FLAG, "false");
    SemanticCallFacts off = analyze(context);
    System.setProperty(FLAG, "true");
    SemanticCallFacts on = analyze(context);
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED,
        injection(off, "demo.Consumer").certainty(),
        "flag-off golden: the textual vacuum abstains");
    assertTrue(injection(on, "demo.Consumer").callee().contains("listBean"),
        "flag-on: the Quarkus injection repairs through the same seam");
  }

  // ------------------------------------------------------------------
  // B. Contract shapes
  // ------------------------------------------------------------------

  // B1: the contract arm — an implements spelling that is a textual vacuum
  // (a non-generic JDK interface through an on-demand import) matches
  // through the candidate's index supertype shapes.
  @Test
  void contractSupertypeIndexShapeMatches() throws IOException {
    Path sourceRoot = root("b1");
    write(sourceRoot, "demo/RecordBean.java", """
        package demo;
        import java.io.*;
        import org.springframework.stereotype.Component;
        @Component public class RecordBean implements Serializable {
        }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import java.io.Serializable;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(Serializable record) { }
        }
        """);
    AnalysisContext context = springContext(sourceRoot);
    System.setProperty(FLAG, "false");
    SemanticCallFacts off = analyze(context);
    System.setProperty(FLAG, "true");
    SemanticCallFacts on = analyze(context);
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED,
        injection(off, "demo.Consumer").certainty(),
        "flag-off golden: the candidate's implements spelling is a vacuum");
    assertTrue(injection(on, "demo.Consumer").callee()
            .contains("RecordBean"),
        "flag-on: the direct supertype shape matches the requested contract");
  }

  // B2: transitive contracts stay unmatched — the index supertypes are
  // DIRECT, mirroring the declared-contracts semantics.
  @Test
  void transitiveContractStaysUnmatched() throws IOException {
    Path sourceRoot = root("b2");
    write(sourceRoot, "demo/Roles.java", """
        package demo;
        public interface Roles { }
        """);
    write(sourceRoot, "demo/AdminRoles.java", """
        package demo;
        public interface AdminRoles extends Roles { }
        """);
    write(sourceRoot, "demo/RoleBean.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component public class RoleBean implements AdminRoles { }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(Roles roles) { }
        }
        """);
    AnalysisContext context = springContext(sourceRoot);
    System.setProperty(FLAG, "false");
    SemanticCallFacts off = analyze(context);
    System.setProperty(FLAG, "true");
    SemanticCallFacts on = analyze(context);
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED,
        injection(off, "demo.Consumer").certainty());
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED,
        injection(on, "demo.Consumer").certainty(),
        "a transitive contract does not match: the supertypes are direct");
  }

  // B3: an error-shaped candidate type keeps the textual ladder — no
  // contract shapes are published for an unresolvable supertype chain;
  // the textual contract ladder still answers (the declared extends
  // spelling resolves textually), so both flags agree (parity, probe
  // P-RED: the textual contract match is the pre-existing answer).
  @Test
  void errorSupertypeKeepsTextualLadder() throws IOException {
    Path sourceRoot = root("b3");
    write(sourceRoot, "demo/BrokenBean.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component public class BrokenBean extends missing.Base { }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(missing.Base base) { }
        }
        """);
    AnalysisContext context = springContext(sourceRoot);
    System.setProperty(FLAG, "false");
    SemanticCallFacts off = analyze(context);
    System.setProperty(FLAG, "true");
    SemanticCallFacts on = analyze(context);
    assertTrue(injection(off, "demo.Consumer").callee()
            .contains("BrokenBean"),
        "flag-off golden: the textual contract ladder answers");
    assertEquals(injection(off, "demo.Consumer").certainty(),
        injection(on, "demo.Consumer").certainty(),
        "an error-shaped type publishes no contract shapes: "
            + "flag-on is byte-identical");
  }

  // B4: the generics gate (C4) — a generic requested type with the same
  // ERASED contract but a different type argument must NOT match through
  // the index disjunct. Red under a gate-removal mutation.
  @Test
  void genericRequestedTypeDoesNotMatchSameErasedContract() throws IOException {
    Path sourceRoot = root("b4");
    write(sourceRoot, "demo/Repo.java", """
        package demo;
        public interface Repo<T> { }
        """);
    write(sourceRoot, "demo/OrderRepo.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component public class OrderRepo implements Repo<demo.Order> { }
        """);
    write(sourceRoot, "demo/Order.java", """
        package demo;
        public class Order { }
        """);
    write(sourceRoot, "demo/User.java", """
        package demo;
        public class User { }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(Repo<User> repo) { }
        }
        """);
    AnalysisContext context = springContext(sourceRoot);
    System.setProperty(FLAG, "false");
    SemanticCallFacts off = analyze(context);
    System.setProperty(FLAG, "true");
    SemanticCallFacts on = analyze(context);
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED,
        injection(off, "demo.Consumer").certainty(),
        "flag-off golden: the textual generic comparison refuses the "
            + "same-erased candidate");
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED,
        injection(on, "demo.Consumer").certainty(),
        "the erased index disjunct is gated on generic requested types: "
            + "Repo<User> must not match a Repo<Order> candidate");
  }

  // B5: the generics gate (C5) — a metadata-only row with the bare erased
  // type must not match a generic requested type through the disjunct
  // (with a producer match present, the row would otherwise enter the
  // eligible set and downgrade the DEFINITE). Red under a gate-removal
  // mutation.
  @Test
  void genericRequestedTypeDoesNotMatchErasedMetadataRow() throws IOException {
    Path sourceRoot = root("b5");
    write(sourceRoot, "demo/Repo.java", """
        package demo;
        public interface Repo<T> { }
        """);
    write(sourceRoot, "demo/User.java", "package demo; public class User { }\n");
    write(sourceRoot, "demo/Config.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component public class Config {
          @org.springframework.context.annotation.Bean
          public Repo<User> repoBean() { return null; }
        }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(Repo<User> repo) { }
        }
        """);
    AnalysisContext context = springContext(sourceRoot, List.of(
        producerRow("repoBean", "demo.Repo", "demo.Config", "repoBean"),
        row("erased-row", "Repo", "demo.Outside")));
    System.setProperty(FLAG, "false");
    SemanticCallFacts off = analyze(context);
    System.setProperty(FLAG, "true");
    SemanticCallFacts on = analyze(context);
    assertEquals(SemanticCallFacts.Certainty.DEFINITE,
        injection(off, "demo.Consumer").certainty(),
        "flag-off golden: the producer matches textually and the erased "
            + "row does not");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE,
        injection(on, "demo.Consumer").certainty(),
        "the metadata-row disjunct is gated on generic requested types: "
            + "the erased Repo row must not enter the eligible set");
  }

  // ------------------------------------------------------------------
  // C. Certainty / blast radius
  // ------------------------------------------------------------------

  // C2: no fabricated match — the shape is a canonical identity, never a
  // simple-name match: an unimported java.lang.String requested type does
  // not match a source demo.String candidate.
  @Test
  void shadowedSimpleNameDoesNotFabricateAMatch() throws IOException {
    Path sourceRoot = root("c2");
    write(sourceRoot, "demo/String.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component public class String { }
        """);
    write(sourceRoot, "app/Consumer.java", """
        package app;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(String text) { }
        }
        """);
    AnalysisContext context = springContext(sourceRoot);
    System.setProperty(FLAG, "false");
    SemanticCallFacts off = analyze(context);
    System.setProperty(FLAG, "true");
    SemanticCallFacts on = analyze(context);
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED,
        injection(off, "app.Consumer").certainty(),
        "flag-off golden: the java.lang rung resolves and does not match "
            + "the source candidate");
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED,
        injection(on, "app.Consumer").certainty(),
        "the index shape is a canonical identity, not a simple-name match");
  }

  // C3: the blast-radius pin — an abstaining injection fixture AND a
  // ServiceLoader DEFINITE site in one scan: promotion untouched,
  // diagnostics empty (the seam carries no evidence channel). The
  // ServiceLoader harness replicates the #62 D1 pattern (compiled
  // fixture + services descriptor in a dependency jar).
  @Test
  void abstainingInjectionDoesNotBlockServiceLoaderDefinitePromotion()
      throws IOException {
    Path project = temp.resolve("c3");
    Path source = Files.createDirectories(
        project.resolve("src/main/java"));
    Path classes = Files.createDirectories(project.resolve("classes"));
    Path dependencies = Files.createDirectories(project.resolve("deps"));
    write(source, "demo/Handler.java",
        "package demo; public interface Handler { void record(); }\n");
    write(source, "demo/HandlerImpl.java",
        "package demo; public class HandlerImpl implements Handler "
            + "{ public void record() {} }\n");
    write(source, "demo/App.java", """
        package demo;
        import java.util.ServiceLoader;
        class App {
          void call() { ServiceLoader.load(Handler.class).forEach(
              h -> h.record()); }
        }
        """);
    write(source, "demo/Consumer.java", """
        package demo;
        import java.util.*;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(List list) { }
        }
        """);
    // Self-contained stub Spring annotations so the WHOLE fixture compiles
    // (a source-only consumer would report javac_source_error and block
    // the ServiceLoader scan's DEFINITE promotion — the opposite of this
    // cell's intent).
    Path stubs = Files.createDirectories(project.resolve("stubs"));
    write(stubs, "org/springframework/stereotype/Component.java", """
        package org.springframework.stereotype;
        import java.lang.annotation.ElementType;
        import java.lang.annotation.Retention;
        import java.lang.annotation.RetentionPolicy;
        import java.lang.annotation.Target;
        @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
        public @interface Component { }
        """);
    write(stubs, "org/springframework/beans/factory/annotation/Autowired.java", """
        package org.springframework.beans.factory.annotation;
        import java.lang.annotation.ElementType;
        import java.lang.annotation.Retention;
        import java.lang.annotation.RetentionPolicy;
        import java.lang.annotation.Target;
        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.CONSTRUCTOR, ElementType.METHOD, ElementType.FIELD})
        public @interface Autowired { }
        """);
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
        "--release", "17", "-g", "-d", classes.toString(),
        stubs.resolve("org/springframework/stereotype/Component.java").toString(),
        stubs.resolve("org/springframework/beans/factory/annotation/Autowired.java").toString()));
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
        "--release", "17", "-g", "-cp", classes.toString(),
        "-d", classes.toString(),
        source.resolve("demo/Handler.java").toString(),
        source.resolve("demo/HandlerImpl.java").toString(),
        source.resolve("demo/App.java").toString(),
        source.resolve("demo/Consumer.java").toString()));
    Path unique = dependencies.resolve("unique.jar");
    try (java.util.zip.ZipOutputStream output = new java.util.zip.ZipOutputStream(
        Files.newOutputStream(unique))) {
      output.putNextEntry(
          new java.util.zip.ZipEntry("META-INF/services/demo.Handler"));
      output.write("demo.HandlerImpl\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      output.closeEntry();
    }
    AnalysisContext context = AnalysisContext.builder()
        .id("c3")
        .framework(AnalysisContext.Framework.SPRING_BOOT)
        .frameworkVersion("2.7.18")
        .fixtureId("javac-injection-contract-test")
        .javaRelease(17)
        .repositoryRoot(project)
        .applicationClasses(classes)
        .selectedProfile("production")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", source,
            AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspath(List.of(unique))
        .dependencyClasspathRequired(false)
        .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
            "spring-bean-annotations", "source_annotations",
            AnalysisContext.MetadataStatus.OBSERVED, "src/main/java"))
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
    System.setProperty(FLAG, "true");
    SemanticCallFacts on = analyze(context);
    assertTrue(on.providerFacts().stream()
            .filter(item -> item.relationshipType().equals("service_loader"))
            .anyMatch(item -> item.certainty()
                == SemanticCallFacts.Certainty.DEFINITE),
        "an abstaining injection fixture must not block ServiceLoader "
            + "DEFINITE promotion: " + on.providerFacts());
    assertTrue(on.diagnostics().isEmpty(),
        "the seam carries no evidence channel: " + on.diagnostics());
  }

  // ------------------------------------------------------------------
  // D. JLS 6.5/6.4.1 name-resolution matrix (seam-level identity cells;
  //    the repair answers apply where the 2-arg textual resolver fails)
  // ------------------------------------------------------------------

  // D6b: a same-compilation-unit nested type with no matching import — the
  // textual resolver has no same-CU rung (a vacuum); javac resolves the
  // CU's own nested type.
  @Test
  void sameCompilationUnitNestedTypeShape() throws IOException {
    Path sourceRoot = root("d6b");
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          public class Item { }
          @Autowired public Consumer(Item item) { }
        }
        """);
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner =
        new SourceMetadataScanner(springContext(sourceRoot));
    SourceMetadataScannerState.InjectionPoint point = injectionPoint(
        scanner, "demo.Consumer", "<init>");
    assertEquals(Optional.of("demo.Consumer.Item"),
        JavacInjectionTypeShapes.of(scanner).requestedShape(point, 0),
        "javac resolves the CU's own nested type where the textual "
            + "resolver has no rung");
    System.setProperty(FLAG, "false");
    SourceMetadataScanner textual =
        new SourceMetadataScanner(springContext(sourceRoot));
    assertEquals(Optional.empty(),
        JavacInjectionTypeShapes.of(textual).requestedShape(
            injectionPoint(textual, "demo.Consumer", "<init>"), 0),
        "flag-off: the seam is empty");
  }

  // D8: same-package x same-CU, repair form — the same-package simple name
  // exists only OUTSIDE the selected file set, so the textual rung is empty
  // and javac resolves the CU's own nested type.
  @Test
  void samePackageExternalRungFallsToNestedShape() throws IOException {
    Path sourceRoot = root("d8");
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          public class Item { }
          @Autowired public Consumer(Item item) { }
        }
        """);
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner =
        new SourceMetadataScanner(springContext(sourceRoot));
    SourceMetadataScannerState.InjectionPoint point = injectionPoint(
        scanner, "demo.Consumer", "<init>");
    assertEquals(Optional.of("demo.Consumer.Item"),
        JavacInjectionTypeShapes.of(scanner).requestedShape(point, 0),
        "an external same-package type is invisible to the textual rung "
            + "(files() only); javac resolves the nested type");
    System.setProperty(FLAG, "false");
    SourceMetadataScanner textual =
        new SourceMetadataScanner(springContext(sourceRoot));
    assertEquals(Optional.empty(),
        JavacInjectionTypeShapes.of(textual).requestedShape(
            injectionPoint(textual, "demo.Consumer", "<init>"), 0),
        "flag-off: the seam is empty");
  }

  // D8 populated twin: the same-package rung ANSWERS (a selected file
  // declares the simple name) — the JLS-wrong textual answer is kept by
  // repair-only (characterization of the residual).
  @Test
  void samePackagePopulatedRungKeepsTextualAnswer() throws IOException {
    Path sourceRoot = root("d8p");
    write(sourceRoot, "demo/Item.java",
        "package demo; public class Item { }\n");
    write(sourceRoot, "demo/Config.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component public class Config {
          @org.springframework.context.annotation.Bean
          public demo.Item itemBean() { return null; }
        }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          public class Item { }
          @Autowired public Consumer(Item item) { }
        }
        """);
    AnalysisContext context = springContext(sourceRoot);
    System.setProperty(FLAG, "false");
    SemanticCallFacts off = analyze(context);
    System.setProperty(FLAG, "true");
    SemanticCallFacts on = analyze(context);
    assertTrue(injection(off, "demo.Consumer").callee().contains("itemBean"),
        "flag-off golden: the same-package rung answers textually");
    assertTrue(injection(on, "demo.Consumer").callee().contains("itemBean"),
        "flag-on characterization: the populated rung's textual answer "
            + "is kept (the JLS-wrong nested-type resolution is a "
            + "documented residual)");
  }

  // D9: on-demand x same-CU, repair form — the on-demand import names a
  // package absent from the selected file set (legal Java), so the textual
  // rung is empty and javac resolves the CU's own nested type.
  @Test
  void onDemandExternalRungFallsToNestedShape() throws IOException {
    Path sourceRoot = root("d9");
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import absent.pkg.*;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          public class Item { }
          @Autowired public Consumer(Item item) { }
        }
        """);
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner =
        new SourceMetadataScanner(springContext(sourceRoot));
    SourceMetadataScannerState.InjectionPoint point = injectionPoint(
        scanner, "demo.Consumer", "<init>");
    assertEquals(Optional.of("demo.Consumer.Item"),
        JavacInjectionTypeShapes.of(scanner).requestedShape(point, 0),
        "an on-demand import of an absent package leaves the textual rung "
            + "empty; javac resolves the nested type");
    System.setProperty(FLAG, "false");
    SourceMetadataScanner offScanner =
        new SourceMetadataScanner(springContext(sourceRoot));
    assertEquals(Optional.empty(),
        JavacInjectionTypeShapes.of(offScanner).requestedShape(
            injectionPoint(offScanner, "demo.Consumer", "<init>"), 0),
        "flag-off: the seam is empty");
  }

  // D10: java.lang x same-CU — CHARACTERIZATION: the java.lang rung always
  // answers for a String-named nested type (JLS-wrong); repair-only keeps
  // the textual answer, pinned as the residual.
  @Test
  void javaLangRungKeepsTextualAnswerForNestedShadow() throws IOException {
    Path sourceRoot = root("d10");
    write(sourceRoot, "demo/Config.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component public class Config {
          @org.springframework.context.annotation.Bean
          public String stringBean() { return null; }
        }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          public class String { }
          @Autowired public Consumer(String text) { }
        }
        """);
    AnalysisContext context = springContext(sourceRoot);
    System.setProperty(FLAG, "false");
    SemanticCallFacts off = analyze(context);
    System.setProperty(FLAG, "true");
    SemanticCallFacts on = analyze(context);
    assertTrue(injection(off, "demo.Consumer").callee().contains("stringBean"),
        "flag-off golden: the java.lang rung answers textually");
    assertTrue(injection(on, "demo.Consumer").callee().contains("stringBean"),
        "flag-on characterization: the java.lang rung's textual answer is "
            + "kept (the nested String is a documented residual)");
  }

  // D11: an inherited member type (cross-file, single level — the AGENTS
  // scope): the unqualified spelling of a superclass's nested type has no
  // textual rung; javac resolves it.
  @Test
  void inheritedMemberTypeShape() throws IOException {
    Path sourceRoot = root("d11");
    write(sourceRoot, "demo/Base.java", """
        package demo;
        public class Base { public static class Item { } }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer extends Base {
          @Autowired public Consumer(Item item) { }
        }
        """);
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner =
        new SourceMetadataScanner(springContext(sourceRoot));
    SourceMetadataScannerState.InjectionPoint point = injectionPoint(
        scanner, "demo.Consumer", "<init>");
    assertEquals(Optional.of("demo.Base.Item"),
        JavacInjectionTypeShapes.of(scanner).requestedShape(point, 0),
        "javac resolves the inherited member type where the textual "
            + "resolver has no rung");
    System.setProperty(FLAG, "false");
    SourceMetadataScanner textual =
        new SourceMetadataScanner(springContext(sourceRoot));
    assertEquals(Optional.empty(),
        JavacInjectionTypeShapes.of(textual).requestedShape(
            injectionPoint(textual, "demo.Consumer", "<init>"), 0),
        "flag-off: the seam is empty");
  }

  // D1-D5, D7: resolver-rung ORDER parity — the textual rungs are
  // JLS-ordered (single-type import > same-package > on-demand >
  // java.lang), so each populated rung answers identically flag-on and
  // flag-off; the repair never overrides a non-null textual answer.
  @Test
  void resolverRungOrderKeepsParity() throws IOException {
    Path sourceRoot = root("d-rungs");
    write(sourceRoot, "demo/Greeter.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component public class Greeter { }
        """);
    write(sourceRoot, "other/Greeter.java",
        "package other; public class Greeter { }\n");
    // D1: on-demand x same-package — the same-package rung wins.
    write(sourceRoot, "demo/SamePackageConsumer.java", """
        package demo;
        import other.*;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class SamePackageConsumer {
          @Autowired public SamePackageConsumer(Greeter greeter) { }
        }
        """);
    // D2: on-demand x single-type import — the import wins.
    write(sourceRoot, "demo/ImportWinsConsumer.java", """
        package demo;
        import demo.Greeter;
        import other.*;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class ImportWinsConsumer {
          @Autowired public ImportWinsConsumer(Greeter greeter) { }
        }
        """);
    // D7: a single-type import of a same-package type resolves.
    write(sourceRoot, "demo/ExplicitSamePackageConsumer.java", """
        package demo;
        import demo.Greeter;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class ExplicitSamePackageConsumer {
          @Autowired public ExplicitSamePackageConsumer(Greeter greeter) { }
        }
        """);
    AnalysisContext context = springContext(sourceRoot);
    System.setProperty(FLAG, "false");
    SemanticCallFacts off = analyze(context);
    System.setProperty(FLAG, "true");
    SemanticCallFacts on = analyze(context);
    for (String owner : List.of("demo.SamePackageConsumer",
        "demo.ImportWinsConsumer", "demo.ExplicitSamePackageConsumer")) {
      assertTrue(injection(off, owner).callee().contains("demo.Greeter#"),
          "flag-off golden (" + owner + "): the textual rung order answers");
      assertTrue(injection(on, owner).callee().contains("demo.Greeter#"),
          "flag-on (" + owner + "): a non-null textual answer is never "
              + "overridden");
    }
  }

  // C5-positive: the metadata-row disjunct's observable — a metadata-only
  // row whose type matches only through the requested shape is HONESTLY
  // counted in the eligible set, downgrading an otherwise-DEFINITE match
  // to POSSIBLE (flag-off: the row does not match, DEFINITE).
  @Test
  void metadataOnlyRowIsCountedThroughTheRequestedShape() throws IOException {
    Path sourceRoot = root("c5");
    write(sourceRoot, "demo/Config.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component public class Config {
          @org.springframework.context.annotation.Bean
          public java.util.List<String> listBean() { return null; }
        }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import java.util.*;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(List list) { }
        }
        """);
    AnalysisContext context = springContext(sourceRoot, List.of(
        producerRow("listBean", "java.util.List", "demo.Config", "listBean"),
        row("other-list", "java.util.List", "demo.OtherConfig")));
    System.setProperty(FLAG, "false");
    SemanticCallFacts off = analyze(context);
    System.setProperty(FLAG, "true");
    SemanticCallFacts on = analyze(context);
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED,
        injection(off, "demo.Consumer").certainty(),
        "flag-off golden: the requested type is a textual vacuum, so no "
            + "match and no metadata-only row");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE,
        injection(on, "demo.Consumer").certainty(),
        "flag-on: the shape-proven metadata-only row is honestly counted "
            + "in the eligible set (a downgrade, never a fabrication) — "
            + "without the C5 disjunct this would be DEFINITE");
  }

  // ------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------

  private SemanticCallFacts analyze(AnalysisContext context) {
    return new JavaAnalysisAdapter().analyze(context);
  }

  private List<SemanticCallFacts.Fact> injections(
      SemanticCallFacts facts, String owner) {
    return facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .filter(item -> item.caller().contains(owner))
        .toList();
  }

  private SemanticCallFacts.Fact injection(
      SemanticCallFacts facts, String owner) {
    List<SemanticCallFacts.Fact> matches = injections(facts, owner);
    assertFalse(matches.isEmpty(), "no injection fact for " + owner + ": "
        + facts.frameworkFacts());
    return matches.get(0);
  }

  private SourceMetadataScannerState.InjectionPoint injectionPoint(
      SourceMetadataScanner scanner, String owner, String member) {
    return scanner.injectionPoints(AnalysisContext.Framework.SPRING_BOOT)
        .stream()
        .filter(point -> point.ownerType().equals(owner))
        .filter(point -> point.member().equals(member))
        .findFirst()
        .orElseThrow();
  }

  private Path root(String id) throws IOException {
    return Files.createDirectories(
        temp.resolve(id).resolve("src/main/java"));
  }

  private AnalysisContext springContext(Path sourceRoot)
      throws IOException {
    return springContext(sourceRoot, List.of());
  }

  private AnalysisContext springContext(
      Path sourceRoot, List<AnalysisContext.BindingCandidateMetadata> rows)
      throws IOException {
    return context(AnalysisContext.Framework.SPRING_BOOT, sourceRoot,
        "2.7.18", 17, rows);
  }

  private AnalysisContext quarkusContext(Path sourceRoot) throws IOException {
    return context(AnalysisContext.Framework.QUARKUS, sourceRoot,
        "3.40.1", 21, List.of());
  }

  private AnalysisContext context(
      AnalysisContext.Framework framework, Path sourceRoot,
      String frameworkVersion, int release,
      List<AnalysisContext.BindingCandidateMetadata> rows) throws IOException {
    Path project = sourceRoot.getParent().getParent().getParent();
    Path classes = Files.createDirectories(project.resolve("classes"));
    return AnalysisContext.builder()
        .id(project.getFileName().toString())
        .framework(framework)
        .frameworkVersion(frameworkVersion)
        .fixtureId("javac-injection-contract-test")
        .javaRelease(release)
        .repositoryRoot(project)
        .applicationClasses(classes)
        .selectedProfile("production")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot,
            AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .frameworkBindingMetadata(new AnalysisContext.FrameworkBindingMetadata(
            "0.1.0", rows, List.of(), List.of()))
        .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
            framework == AnalysisContext.Framework.QUARKUS
                ? "quarkus-cdi-annotations" : "spring-bean-annotations",
            "source_annotations",
            AnalysisContext.MetadataStatus.OBSERVED,
            "src/main/java"))
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
  }

  private static AnalysisContext.BindingCandidateMetadata row(
      String id, String candidateType, String declaringType) {
    return new AnalysisContext.BindingCandidateMetadata(
        id, candidateType, List.of(), List.of(), "bean", declaringType,
        null, null, List.of(), List.of(), true, false, false, null, null);
  }

  private static AnalysisContext.BindingCandidateMetadata producerRow(
      String id, String candidateType, String declaringType,
      String producerMethod) {
    return new AnalysisContext.BindingCandidateMetadata(
        id, candidateType, List.of(), List.of(), "producer", declaringType,
        producerMethod, null, List.of(), List.of(), true, false, false,
        null, null);
  }

  private static void write(Path root, String relative, String source)
      throws IOException {
    Path path = root.resolve(relative);
    Files.createDirectories(path.getParent());
    Files.writeString(path, source);
  }
}
