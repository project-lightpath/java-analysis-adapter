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
 * javac framework-annotation migration: route, bean, and injection
 * annotation values behind the {@code flpath.java.symbolindex} flag.
 *
 * <p>The class runs same-thread and {@link Isolated isolated} from all
 * other classes because it writes the flag, which production scan code
 * reads.
 */
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class JavacFrameworkAnnotationMigrationTest {
  /** Opt-out flag: defaults ON since #66; "false" pins the textual path. */
  private static final String FLAG = "flpath.java.symbolindex";

  @TempDir Path temp;

  @AfterEach
  void clearFlag() {
    System.clearProperty(FLAG);
  }

  @Test
  void flagOffFrameworkOutputIsByteForByteTextual() throws IOException {
    AnalysisContext context = springFixture("flag-off");
    AnalysisContext baseline = springFixture("flag-off-baseline");
    System.setProperty(FLAG, "false"); // opt-out golden (#66)
    SourceMetadataScanner textual = new SourceMetadataScanner(baseline);
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    System.setProperty(FLAG, "true");
    scanner.routes(AnalysisContext.Framework.SPRING_BOOT);
    scanner.beanCandidates(AnalysisContext.Framework.SPRING_BOOT);
    scanner.injectionPoints(AnalysisContext.Framework.SPRING_BOOT);
    System.setProperty(FLAG, "false"); // opt-out golden (#66)
    // A flag-off re-scan after the flag-on scan must equal the independent
    // textual baseline: the static extracts were not poisoned.
    assertEquals(routeProjection(textual, AnalysisContext.Framework.SPRING_BOOT),
        routeProjection(scanner, AnalysisContext.Framework.SPRING_BOOT));
    assertEquals(beanProjection(textual, AnalysisContext.Framework.SPRING_BOOT),
        beanProjection(scanner, AnalysisContext.Framework.SPRING_BOOT));
    assertEquals(injectionProjection(textual, AnalysisContext.Framework.SPRING_BOOT),
        injectionProjection(scanner, AnalysisContext.Framework.SPRING_BOOT));
  }

  @Test
  void springRouteValuesMatchTextualOutputFlagOn() throws IOException {
    AnalysisContext context = springFixture("spring-routes");
    System.setProperty(FLAG, "false"); // opt-out golden (#66)
    List<String> off = routeProjection(
        new SourceMetadataScanner(context), AnalysisContext.Framework.SPRING_BOOT);
    System.setProperty(FLAG, "true");
    List<String> on = routeProjection(
        new SourceMetadataScanner(context), AnalysisContext.Framework.SPRING_BOOT);
    assertEquals(off, on);
  }

  @Test
  void springBeanAndInjectionQualifiersMatchTextualOutputFlagOn() throws IOException {
    AnalysisContext context = springFixture("spring-beans");
    System.setProperty(FLAG, "false"); // opt-out golden (#66)
    SourceMetadataScanner off = new SourceMetadataScanner(context);
    List<String> offBeans = beanProjection(off, AnalysisContext.Framework.SPRING_BOOT);
    List<String> offInjections =
        injectionProjection(off, AnalysisContext.Framework.SPRING_BOOT);
    System.setProperty(FLAG, "true");
    SourceMetadataScanner on = new SourceMetadataScanner(context);
    assertEquals(offBeans, beanProjection(on, AnalysisContext.Framework.SPRING_BOOT));
    assertEquals(offInjections,
        injectionProjection(on, AnalysisContext.Framework.SPRING_BOOT));
    // Non-vacuity: the migrated facts exist and carry the expected shapes.
    List<SourceMetadataScannerState.BeanCandidate> beans =
        on.beanCandidates(AnalysisContext.Framework.SPRING_BOOT);
    assertTrue(beans.stream().anyMatch(bean ->
        bean.qualifiers().equals(List.of("BeanName:namedService", "Qualifier:q"))
            && bean.profiles().equals(List.of("prod"))), beans.toString());
    List<SourceMetadataScannerState.InjectionPoint> injections =
        on.injectionPoints(AnalysisContext.Framework.SPRING_BOOT);
    assertTrue(injections.stream().anyMatch(point ->
        point.kind().equals("field")
            && point.qualifiers().equals(List.of("Qualifier:dep"))), injections.toString());
    assertTrue(injections.stream().anyMatch(point ->
        point.kind().equals("method")
            && point.member().equals("setHelper")
            && point.qualifiers().isEmpty()
            && point.parameters().get(0).qualifiers().equals(List.of("Qualifier:h"))),
        injections.toString());
    assertTrue(injections.stream().anyMatch(point ->
        point.kind().equals("constructor")
            && point.parameters().get(0).qualifiers().equals(List.of("Qualifier:c"))),
        injections.toString());
  }

  @Test
  void quarkusRouteAndQualifierValuesMatchTextualOutputFlagOn() throws IOException {
    AnalysisContext context = quarkusFixture("quarkus");
    System.setProperty(FLAG, "false"); // opt-out golden (#66)
    SourceMetadataScanner off = new SourceMetadataScanner(context);
    List<String> offRoutes = routeProjection(off, AnalysisContext.Framework.QUARKUS);
    List<String> offBeans = beanProjection(off, AnalysisContext.Framework.QUARKUS);
    List<String> offInjections = injectionProjection(off, AnalysisContext.Framework.QUARKUS);
    System.setProperty(FLAG, "true");
    SourceMetadataScanner on = new SourceMetadataScanner(context);
    assertEquals(offRoutes, routeProjection(on, AnalysisContext.Framework.QUARKUS));
    assertEquals(offBeans, beanProjection(on, AnalysisContext.Framework.QUARKUS));
    assertEquals(offInjections, injectionProjection(on, AnalysisContext.Framework.QUARKUS));
    assertTrue(on.routes(AnalysisContext.Framework.QUARKUS).stream().anyMatch(route ->
        route.path().equals("/greet") && route.httpMethod().equals("POST")),
        on.routes(AnalysisContext.Framework.QUARKUS).toString());
  }

  @Test
  void annotationLikeTextInStringsAndCommentsCreatesNoFacts() throws IOException {
    AnalysisContext context = quarkusFixture("decoys");
    for (boolean flag : new boolean[] {false, true}) {
      if (flag) {
        System.setProperty(FLAG, "true");
      } else {
        System.setProperty(FLAG, "false"); // opt-out golden (#66)
      }
      SourceMetadataScanner scanner = new SourceMetadataScanner(context);
      assertTrue(scanner.routes(AnalysisContext.Framework.QUARKUS).stream()
          .noneMatch(route -> route.path().contains("fake")),
          scanner.routes(AnalysisContext.Framework.QUARKUS).toString());
      assertTrue(scanner.injectionPoints(AnalysisContext.Framework.QUARKUS).stream()
          .noneMatch(point -> point.member().equals("decoy")),
          scanner.injectionPoints(AnalysisContext.Framework.QUARKUS).toString());
    }
  }

  @Test
  void lookalikeAnnotationsDoNotMatchCanonicalFrameworks() throws IOException {
    Map<String, String> files = Map.of(
        "demo/GetMapping.java", "package demo; public @interface GetMapping {"
            + " String[] value() default {}; }",
        "demo/Named.java", "package demo; public @interface Named { String value() default \"\"; }",
        "demo/Lookalike.java", """
            package demo;
            import demo.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class Lookalike {
              @GetMapping("/x") public String x() { return ""; }
            }
            """,
        "demo/LookalikeService.java", """
            package demo;
            import demo.Named;
            import org.springframework.stereotype.Service;
            @Service @Named("user")
            public class LookalikeService {}
            """);
    AnalysisContext context = springContext("lookalike", files);
    for (boolean flag : new boolean[] {false, true}) {
      if (flag) {
        System.setProperty(FLAG, "true");
      } else {
        System.setProperty(FLAG, "false"); // opt-out golden (#66)
      }
      SourceMetadataScanner scanner = new SourceMetadataScanner(context);
      assertTrue(scanner.routes(AnalysisContext.Framework.SPRING_BOOT).stream()
          .noneMatch(route -> route.method().name().equals("x")),
          scanner.routes(AnalysisContext.Framework.SPRING_BOOT).toString());
      List<SourceMetadataScannerState.BeanCandidate> beans =
          scanner.beanCandidates(AnalysisContext.Framework.SPRING_BOOT);
      // The user-defined @Named is not canonical: the guard suppresses it,
      // so the bean keeps no lookalike qualifier in either flag state.
      assertTrue(beans.stream().filter(bean -> bean.candidateId().equals("demo.LookalikeService"))
          .allMatch(bean -> bean.qualifiers().isEmpty()), beans.toString());
    }
  }

  @Test
  void conditionsRenderFromIndexMirrorsFlagOn() throws IOException {
    AnalysisContext context = springFixture("conditions");
    System.setProperty(FLAG, "false"); // opt-out golden (#66)
    List<SourceMetadataScannerState.BeanCandidate> off =
        new SourceMetadataScanner(context).beanCandidates(AnalysisContext.Framework.SPRING_BOOT);
    System.setProperty(FLAG, "true");
    List<SourceMetadataScannerState.BeanCandidate> on =
        new SourceMetadataScanner(context).beanCandidates(AnalysisContext.Framework.SPRING_BOOT);
    List<String> offConditions = off.stream()
        .filter(bean -> bean.candidateId().equals("demo.QualifiedService"))
        .findFirst().orElseThrow().conditions();
    List<String> onConditions = on.stream()
        .filter(bean -> bean.candidateId().equals("demo.QualifiedService"))
        .findFirst().orElseThrow().conditions();
    assertEquals(List.of("@ConditionalOnProperty(name = \"enabled\")"), offConditions);
    // Intended flag-on delta (#22 canonical-spelling precedent): the
    // condition evidence is the index-rendered canonical spelling.
    assertEquals(List.of(
        "@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name=\"enabled\")"),
        onConditions);
  }

  @Test
  void dollarAndUnicodeQualifiersAreCorrectedAtBeanSiteFlagOn() throws IOException {
    Map<String, String> files = Map.of(
        "demo/Weird.java", """
            package demo;
            import org.springframework.stereotype.Service;
            @Service @Mar$k @Ωmega public class Weird {}
            @interface Mar$k {}
            @interface Ωmega {}
            """);
    AnalysisContext context = springContext("dollar-bean", files);
    System.setProperty(FLAG, "false"); // opt-out golden (#66)
    List<String> off = qualifierListOf(new SourceMetadataScanner(context), "demo.Weird");
    System.setProperty(FLAG, "true");
    List<String> on = qualifierListOf(new SourceMetadataScanner(context), "demo.Weird");
    // Textual: the $ name emits its ASCII prefix and the Unicode name is
    // invisible; the index corrects both (documented flag-on delta).
    assertEquals(List.of("Mar"), off);
    assertEquals(List.of("Mar$k", "Ωmega"), on);
  }

  @Test
  void dollarNamedFieldQualifierIsCorrectedFlagOn() throws IOException {
    Map<String, String> files = Map.of(
        "demo/Holder.java", """
            package demo;
            import org.springframework.stereotype.Component;
            import org.springframework.beans.factory.annotation.Autowired;
            @Component public class Holder {
              @Autowired @Mar$k private String value;
            }
            @interface Mar$k {}
            """);
    AnalysisContext context = springContext("dollar-field", files);
    System.setProperty(FLAG, "false"); // opt-out golden (#66)
    List<String> off = fieldQualifiers(new SourceMetadataScanner(context), "value");
    System.setProperty(FLAG, "true");
    List<String> on = fieldQualifiers(new SourceMetadataScanner(context), "value");
    assertEquals(List.of("Mar"), off);
    assertEquals(List.of("Mar$k"), on);
  }

  @Test
  void localClassOwnersFallBackToTextual() throws IOException {
    Map<String, String> files = Map.of(
        "demo/Outer.java", """
            package demo;
            import org.springframework.stereotype.Component;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.beans.factory.annotation.Qualifier;
            @Component public class Outer {
              private void method(boolean flag) {
                if (flag) {
                  class Local {
                    @Autowired @Qualifier("first") private String first;
                  }
                } else {
                  Object anonymous = new Object() {};
                  class Local {
                    @Autowired @Qualifier("second") private String second;
                  }
                }
              }
              private void other() {
                class Local2 {
                  class Nested {
                    @Autowired @Qualifier("nested") private String nested;
                  }
                }
              }
            }
            """);
    AnalysisContext context = springContext("local-owners", files);
    System.setProperty(FLAG, "false"); // opt-out golden (#66)
    String off = new SourceMetadataScanner(context)
        .injectionPoints(AnalysisContext.Framework.SPRING_BOOT).toString();
    System.setProperty(FLAG, "true");
    String on = new SourceMetadataScanner(context)
        .injectionPoints(AnalysisContext.Framework.SPRING_BOOT).toString();
    assertEquals(off, on);
    assertTrue(off.contains("Qualifier:first"), off);
    assertTrue(off.contains("Qualifier:second"), off);
    assertTrue(off.contains("Qualifier:nested"), off);
  }

  @Test
  void sameArityOverloadsFallBackToTextual() throws IOException {
    Map<String, String> files = Map.of(
        "demo/Overloads.java", """
            package demo;
            import org.springframework.stereotype.Component;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.beans.factory.annotation.Qualifier;
            @Component public class Overloads {
              @Autowired public void configure(@Qualifier("a") String a) {}
              @Autowired public void configure(@Qualifier("b") Integer a) {}
            }
            """);
    AnalysisContext context = springContext("overloads", files);
    System.setProperty(FLAG, "false"); // opt-out golden (#66)
    String off = new SourceMetadataScanner(context)
        .injectionPoints(AnalysisContext.Framework.SPRING_BOOT).toString();
    System.setProperty(FLAG, "true");
    String on = new SourceMetadataScanner(context)
        .injectionPoints(AnalysisContext.Framework.SPRING_BOOT).toString();
    assertEquals(off, on);
  }

  @Test
  void commentInParameterListFallsBackToTextual() throws IOException {
    Map<String, String> files = Map.of(
        "demo/Comments.java", """
            package demo;
            import org.springframework.stereotype.Component;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.beans.factory.annotation.Qualifier;
            @Component public class Comments {
              public Comments(@Qualifier("a") String a /* , extra */, String b) {}
              @Autowired public void run() {}
            }
            """);
    AnalysisContext context = springContext("comment-arity", files);
    System.setProperty(FLAG, "false"); // opt-out golden (#66)
    String off = new SourceMetadataScanner(context)
        .injectionPoints(AnalysisContext.Framework.SPRING_BOOT).toString();
    System.setProperty(FLAG, "true");
    String on = new SourceMetadataScanner(context)
        .injectionPoints(AnalysisContext.Framework.SPRING_BOOT).toString();
    assertEquals(off, on);
  }

  @Test
  void typeUseReturnAnnotationFallsBackToTextual() throws IOException {
    Map<String, String> files = Map.of(
        "demo/TypeUse.java", """
            package demo;
            import java.lang.annotation.*;
            @Target(ElementType.TYPE_USE) @interface Marker {}
            """,
        "demo/Typed.java", """
            package demo;
            import org.springframework.stereotype.Component;
            import org.springframework.beans.factory.annotation.Autowired;
            @Component public class Typed {
              @Autowired @Marker public String value(String input) { return ""; }
            }
            """);
    AnalysisContext context = springContext("type-use", files);
    System.setProperty(FLAG, "false"); // opt-out golden (#66)
    String off = new SourceMetadataScanner(context)
        .injectionPoints(AnalysisContext.Framework.SPRING_BOOT).toString();
    System.setProperty(FLAG, "true");
    String on = new SourceMetadataScanner(context)
        .injectionPoints(AnalysisContext.Framework.SPRING_BOOT).toString();
    assertEquals(off, on);
    // The textual answer emits the type-use spelling as a qualifier; the
    // flag-on path must keep it (fallback), not drop it.
    assertTrue(off.contains("Marker"), off);
  }

  @Test
  void nestedAnnotationArgumentsFallBackToTextual() throws IOException {
    Map<String, String> files = Map.of(
        "demo/Role.java", "package demo; public @interface Role { String value() default \"\"; }",
        "demo/Foo.java", """
            package demo;
            public @interface Foo { Role role() default @Role; }
            """,
        "demo/Nested.java", """
            package demo;
            import org.springframework.stereotype.Component;
            import org.springframework.beans.factory.annotation.Autowired;
            @Component public class Nested {
              @Autowired @Foo(role = @Role("x")) public void setThing(String thing) {}
            }
            """);
    AnalysisContext context = springContext("nested-args", files);
    System.setProperty(FLAG, "false"); // opt-out golden (#66)
    String off = new SourceMetadataScanner(context)
        .injectionPoints(AnalysisContext.Framework.SPRING_BOOT).toString();
    System.setProperty(FLAG, "true");
    String on = new SourceMetadataScanner(context)
        .injectionPoints(AnalysisContext.Framework.SPRING_BOOT).toString();
    assertEquals(off, on);
  }

  @Test
  void staticImportEnumHttpMethodsAreFoundFlagOnOnly() throws IOException {
    Map<String, String> files = Map.of(
        "org/springframework/web/bind/annotation/RequestMethod.java",
        "package org.springframework.web.bind.annotation;"
            + " public enum RequestMethod { GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS }",
        "org/springframework/web/bind/annotation/RequestMapping.java",
        """
            package org.springframework.web.bind.annotation;
            public @interface RequestMapping {
              String[] value() default {};
              RequestMethod[] method() default {};
            }
            """,
        "demo/StaticEnum.java", """
            package demo;
            import static org.springframework.web.bind.annotation.RequestMethod.GET;
            import static org.springframework.web.bind.annotation.RequestMethod.POST;
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController public class StaticEnum {
              @RequestMapping(method = {GET, POST}) public String both() { return ""; }
            }
            """);
    AnalysisContext context = springContext("static-enum", files);
    System.setProperty(FLAG, "false"); // opt-out golden (#66)
    List<SourceMetadataScannerState.Route> off = new SourceMetadataScanner(context)
        .routes(AnalysisContext.Framework.SPRING_BOOT);
    System.setProperty(FLAG, "true");
    List<SourceMetadataScannerState.Route> on = new SourceMetadataScanner(context)
        .routes(AnalysisContext.Framework.SPRING_BOOT);
    // Textual regex needs the RequestMethod. prefix: the static-import
    // spelling yields no method, so the route is dropped flag-off.
    assertTrue(off.stream().noneMatch(route -> route.method().name().equals("both")),
        off.toString());
    // Intended flag-on delta: the index renders the enum constants fully
    // qualified, so the regex finds both methods.
    assertTrue(on.stream().anyMatch(route ->
        route.method().name().equals("both") && route.httpMethod().equals("GET")),
        on.toString());
    assertTrue(on.stream().anyMatch(route ->
        route.method().name().equals("both") && route.httpMethod().equals("POST")),
        on.toString());
  }

  @Test
  void foldedStringConcatenationPathIsResolvedFlagOn() throws IOException {
    Map<String, String> files = Map.of(
        "org/springframework/web/bind/annotation/GetMapping.java",
        """
            package org.springframework.web.bind.annotation;
            public @interface GetMapping { String[] value() default {}; }
            """,
        "demo/Folded.java", """
            package demo;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController public class Folded {
              @GetMapping("a" + "b") public String folded() { return ""; }
            }
            """);
    AnalysisContext context = springContext("folded", files);
    System.setProperty(FLAG, "false"); // opt-out golden (#66)
    List<SourceMetadataScannerState.Route> off = new SourceMetadataScanner(context)
        .routes(AnalysisContext.Framework.SPRING_BOOT);
    System.setProperty(FLAG, "true");
    List<SourceMetadataScannerState.Route> on = new SourceMetadataScanner(context)
        .routes(AnalysisContext.Framework.SPRING_BOOT);
    // Intended flag-on delta: the index resolves the folded constant while
    // the textual literal extraction cannot (the route is dropped flag-off).
    assertTrue(off.stream().noneMatch(route -> route.method().name().equals("folded")),
        off.toString());
    assertTrue(on.stream().anyMatch(route ->
        route.method().name().equals("folded") && route.path().equals("/ab")),
        on.toString());
  }

  @Test
  void emptyMappingAnnotationKeepsTheEmptyPathCardinality() throws IOException {
    Map<String, String> files = Map.of(
        "demo/Empty.java", """
            package demo;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController public class Empty {
              @GetMapping public String none() { return ""; }
            }
            """);
    AnalysisContext context = springContext("empty-mapping", files);
    for (boolean flag : new boolean[] {false, true}) {
      if (flag) {
        System.setProperty(FLAG, "true");
      } else {
        System.setProperty(FLAG, "false"); // opt-out golden (#66)
      }
      List<SourceMetadataScannerState.Route> routes =
          new SourceMetadataScanner(context).routes(AnalysisContext.Framework.SPRING_BOOT);
      assertTrue(routes.stream().anyMatch(route ->
          route.method().name().equals("none") && route.path().equals("/")),
          routes.toString());
    }
  }

  @Test
  void repeatedScansAreDeterministicFlagOn() throws IOException {
    AnalysisContext first = springFixture("determinism-a");
    AnalysisContext second = springFixture("determinism-b");
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scannerA = new SourceMetadataScanner(first);
    SourceMetadataScanner scannerB = new SourceMetadataScanner(second);
    assertEquals(
        routeProjection(scannerA, AnalysisContext.Framework.SPRING_BOOT),
        routeProjection(scannerB, AnalysisContext.Framework.SPRING_BOOT));
    assertEquals(
        beanProjection(scannerA, AnalysisContext.Framework.SPRING_BOOT),
        beanProjection(scannerB, AnalysisContext.Framework.SPRING_BOOT));
    assertEquals(
        injectionProjection(scannerA, AnalysisContext.Framework.SPRING_BOOT),
        injectionProjection(scannerB, AnalysisContext.Framework.SPRING_BOOT));
  }

  /** Route facts without file identity, so different roots compare equal. */
  private List<String> routeProjection(SourceMetadataScanner scanner, AnalysisContext.Framework fw) {
    return scanner.routes(fw).stream()
        .map(route -> route.method().name() + "|" + route.httpMethod() + "|" + route.path()
            + "|" + route.consumes() + "|" + route.produces()
            + "|" + route.headers() + "|" + route.parameters())
        .toList();
  }

  /**
   * Bean facts without file identity or conditions, so different roots
   * compare equal; the condition spelling is the intended flag-on delta
   * and is asserted separately.
   */
  private List<String> beanProjection(SourceMetadataScanner scanner, AnalysisContext.Framework fw) {
    return scanner.beanCandidates(fw).stream()
        .map(bean -> bean.candidateId() + "|" + bean.kind() + "|" + bean.qualifiers()
            + "|" + bean.profiles() + "|" + bean.primary()
            + "|" + bean.producerMethod())
        .toList();
  }

  /** Injection facts without file identity, so different roots compare equal. */
  private List<String> injectionProjection(
      SourceMetadataScanner scanner, AnalysisContext.Framework fw) {
    return scanner.injectionPoints(fw).stream()
        .map(point -> point.ownerType() + "|" + point.member() + "|" + point.kind()
            + "|" + point.qualifiers() + "|" + point.parameters())
        .toList();
  }

  @Test
  void nestedSameSimpleNameAnnotationArgumentFallsBackOnValues() throws IOException {
    Map<String, String> files = Map.of(
        "org/springframework/context/annotation/Profile.java",
        "package org.springframework.context.annotation;"
            + " public @interface Profile { String[] value() default {}; }",
        "org/springframework/stereotype/Service.java",
        "package org.springframework.stereotype;"
            + " public @interface Service { String value() default \"\"; }",
        "demo/Wrapper.java", """
            package demo;
            import org.springframework.context.annotation.Profile;
            public @interface Wrapper { Profile value(); }
            """,
        "demo/Wrapped.java", """
            package demo;
            import org.springframework.stereotype.Service;
            import org.springframework.context.annotation.Profile;
            @Service @Wrapper(@Profile("inner")) @Profile("prod")
            public class Wrapped {}
            """);
    AnalysisContext context = springContext("nested-values", files);
    System.setProperty(FLAG, "false"); // opt-out golden (#66)
    List<String> off = profilesOf(new SourceMetadataScanner(context), "demo.Wrapped");
    System.setProperty(FLAG, "true");
    List<String> on = profilesOf(new SourceMetadataScanner(context), "demo.Wrapped");
    // The textual consumers union over every occurrence, including the one
    // nested inside @Wrapper's arguments; the index path answers from the
    // single declaration mirror, so it must decline, not drop the inner
    // value.
    assertEquals(off, on);
    assertEquals(List.of("inner", "prod"), on);
  }

  @Test
  void methodSpelledLikeTheClassFallsBackToTextual() throws IOException {
    Map<String, String> files = Map.of(
        "org/springframework/stereotype/Component.java",
        "package org.springframework.stereotype; public @interface Component {}",
        "org/springframework/beans/factory/annotation/Autowired.java",
        "package org.springframework.beans.factory.annotation; public @interface Autowired {}",
        "org/springframework/beans/factory/annotation/Qualifier.java",
        "package org.springframework.beans.factory.annotation;"
            + " public @interface Qualifier { String value() default \"\"; }",
        "demo/Phantom.java", """
            package demo;
            import org.springframework.stereotype.Component;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.beans.factory.annotation.Qualifier;
            @Component public class Phantom {
              @Autowired public Phantom(@Qualifier("c") Object real) {}
              @Autowired public void Phantom(@Qualifier("m") int phantom) {}
            }
            """);
    AnalysisContext context = springContext("phantom", files);
    for (boolean flag : new boolean[] {false, true}) {
      if (flag) {
        System.setProperty(FLAG, "true");
      } else {
        System.setProperty(FLAG, "false"); // opt-out golden (#66)
      }
      List<SourceMetadataScannerState.InjectionPoint> points =
          new SourceMetadataScanner(context)
              .injectionPoints(AnalysisContext.Framework.SPRING_BOOT);
      // The unanchored constructor regex also matches the void method
      // spelled like the class, but with an EMPTY annotations group, so it
      // is never selected (the real constructor makes the selection
      // explicit); the index constructor count (1) still disagrees with
      // the textual site count (2), so the whole file declines and the
      // real constructor keeps its textual parameter qualifier.
      assertEquals(1, points.size(), points.toString());
      assertTrue(points.get(0).parameters().get(0).qualifiers()
          .equals(List.of("Qualifier:c")), points.toString());
    }
  }

  @Test
  void fqcnSpelledAnnotationsMatchSimpleNameMirrors() throws IOException {
    Map<String, String> files = Map.of(
        "org/springframework/stereotype/Service.java",
        "package org.springframework.stereotype;"
            + " public @interface Service { String value() default \"\"; }",
        "demo/FullyQualified.java", """
            package demo;
            @org.springframework.stereotype.Service("fq")
            public class FullyQualified {}
            """);
    AnalysisContext context = springContext("fqcn", files);
    System.setProperty(FLAG, "false"); // opt-out golden (#66)
    List<String> off = qualifierListOf(
        new SourceMetadataScanner(context), "demo.FullyQualified");
    System.setProperty(FLAG, "true");
    List<String> on = qualifierListOf(
        new SourceMetadataScanner(context), "demo.FullyQualified");
    // The textual pipeline normalizes the dotted capture to the simple
    // name (BeanName:fq), and the parity guard accepts a package-qualified
    // capture for its simple-name mirror, so the site migrates and both
    // flag states agree.
    assertEquals(off, on);
    assertEquals(List.of("BeanName:fq"), on);
  }

  @Test
  void quarkusLocalClassOwnersFallBackToTextual() throws IOException {
    Map<String, String> files = Map.of(
        "jakarta/inject/Inject.java", "package jakarta.inject; public @interface Inject {}",
        "jakarta/inject/Named.java",
        "package jakarta.inject; public @interface Named { String value() default \"\"; }",
        "jakarta/enterprise/context/ApplicationScoped.java",
        "package jakarta.enterprise.context;"
            + " public @interface ApplicationScoped {}",
        "demo/LocalOwner.java", """
            package demo;
            import jakarta.enterprise.context.ApplicationScoped;
            import jakarta.inject.Inject;
            import jakarta.inject.Named;
            @ApplicationScoped public class LocalOwner {
              private void method(boolean flag) {
                if (flag) {
                  class Local {
                    @Inject @Named("first") private String first;
                  }
                } else {
                  Object anonymous = new Object() {};
                  class Local {
                    @Inject @Named("second") private String second;
                  }
                }
              }
            }
            """);
    AnalysisContext context = context(
        "quarkus-local", AnalysisContext.Framework.QUARKUS, files);
    System.setProperty(FLAG, "false"); // opt-out golden (#66)
    String off = new SourceMetadataScanner(context)
        .injectionPoints(AnalysisContext.Framework.QUARKUS).toString();
    System.setProperty(FLAG, "true");
    String on = new SourceMetadataScanner(context)
        .injectionPoints(AnalysisContext.Framework.QUARKUS).toString();
    assertEquals(off, on);
    assertTrue(off.contains("Named:first"), off);
    assertTrue(off.contains("Named:second"), off);
  }

  @Test
  void quarkusUserDefinedNamedStillEmitsUnguarded() throws IOException {
    Map<String, String> files = Map.of(
        "jakarta/inject/Inject.java", "package jakarta.inject; public @interface Inject {}",
        "demo/Named.java",
        "package demo; public @interface Named { String value() default \"\"; }",
        "jakarta/enterprise/context/ApplicationScoped.java",
        "package jakarta.enterprise.context;"
            + " public @interface ApplicationScoped {}",
        "demo/UserNamed.java", """
            package demo;
            import jakarta.enterprise.context.ApplicationScoped;
            import jakarta.inject.Inject;
            import demo.Named;
            @ApplicationScoped public class UserNamed {
              @Inject @Named("custom") private String value;
            }
            """);
    AnalysisContext context = context(
        "quarkus-user-named", AnalysisContext.Framework.QUARKUS, files);
    System.setProperty(FLAG, "false"); // opt-out golden (#66)
    List<String> off = quarkusFieldQualifiers(new SourceMetadataScanner(context), "value");
    System.setProperty(FLAG, "true");
    List<String> on = quarkusFieldQualifiers(new SourceMetadataScanner(context), "value");
    // The unguarded arm reproduces simple-name semantics: a user-defined
    // @Named still emits its qualifier in both flag states.
    assertEquals(off, on);
    assertEquals(List.of("Named:custom"), on);
  }

  @Test
  void nestedArgumentsFallBackOnConditionsAndPaths() throws IOException {
    AnalysisContext spring = springContext("nested-conditions", Map.of(
        "org/springframework/stereotype/Service.java",
        "package org.springframework.stereotype;"
            + " public @interface Service { String value() default \"\"; }",
        "org/springframework/boot/autoconfigure/condition/ConditionalOnProperty.java",
        "package org.springframework.boot.autoconfigure.condition;"
            + " public @interface ConditionalOnProperty { String name() default \"\"; }",
        "demo/ConditionWrapper.java", """
            package demo;
            import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
            public @interface ConditionWrapper { ConditionalOnProperty value(); }
            """,
        "demo/WrappedConditions.java", """
            package demo;
            import org.springframework.stereotype.Service;
            import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
            @Service @ConditionWrapper(@ConditionalOnProperty(name = "inner"))
            @ConditionalOnProperty(name = "real")
            public class WrappedConditions {}
            """));
    AnalysisContext quarkus = context(
        "nested-paths", AnalysisContext.Framework.QUARKUS, Map.of(
            "jakarta/ws/rs/Path.java",
            "package jakarta.ws.rs; public @interface Path { String value() default \"\"; }",
            "demo/PathWrapper.java",
            "package demo; public @interface PathWrapper { jakarta.ws.rs.Path value(); }",
            "demo/WrappedPath.java", """
                package demo;
                import jakarta.ws.rs.Path;
                @PathWrapper(@Path("/inner")) @Path("/real")
                public class WrappedPath {
                  @POST public String hello() { return ""; }
                }
                """));
    System.setProperty(FLAG, "false"); // opt-out golden (#66)
    List<String> offConditions = conditionsOf(
        new SourceMetadataScanner(spring), "demo.WrappedConditions");
    List<String> offPaths = pathsOf(new SourceMetadataScanner(quarkus));
    System.setProperty(FLAG, "true");
    // The textual consumers union over the nested occurrence; the index
    // path must decline rather than drop it.
    assertEquals(offConditions, conditionsOf(
        new SourceMetadataScanner(spring), "demo.WrappedConditions"));
    assertEquals(offPaths, pathsOf(new SourceMetadataScanner(quarkus)));
    assertEquals(2, offConditions.size(), offConditions.toString());
    assertTrue(offPaths.contains("/real") && offPaths.contains("/inner"),
        offPaths.toString());
  }

  private List<String> conditionsOf(SourceMetadataScanner scanner, String id) {
    return scanner.beanCandidates(AnalysisContext.Framework.SPRING_BOOT).stream()
        .filter(bean -> bean.candidateId().equals(id))
        .findFirst().orElseThrow().conditions();
  }

  private List<String> pathsOf(SourceMetadataScanner scanner) {
    return scanner.routes(AnalysisContext.Framework.QUARKUS).stream()
        .map(SourceMetadataScannerState.Route::path)
        .distinct()
        .toList();
  }

  @Test
  void renderIsUsableToleratesEscapedQuotes() {
    // #48: an escaped rendering keeps an even effective quote count; an
    // unbalanced one (what the pre-fix renderer emitted for a value
    // containing a quote) still declines.
    assertTrue(JavacFrameworkAnnotations.renderIsUsable("value=\"a\\\"b\""));
    assertTrue(JavacFrameworkAnnotations.renderIsUsable("ch=\"\\\\\""));
    assertFalse(JavacFrameworkAnnotations.renderIsUsable("value=\"a\"b\""));
  }

  @Test
  void octalSpelledQuoteQualifierKeepsSourceDistinctness() throws IOException {
    // CodeRabbit 85-1: a decoded quote (here octal-spelled \042) renders
    // escaped, and the escape-blind QUOTED extraction truncates BOTH
    // spellings to the same prefix — distinct source spellings would
    // collapse to one qualifier value flag-on. The mirror must decline
    // decoded quotes so the textual spelling survives.
    Map<String, String> files = Map.of(
        "org/springframework/stereotype/Component.java",
        "package org.springframework.stereotype; public @interface Component {}",
        "org/springframework/beans/factory/annotation/Autowired.java",
        "package org.springframework.beans.factory.annotation; public @interface Autowired {}",
        "org/springframework/beans/factory/annotation/Qualifier.java",
        "package org.springframework.beans.factory.annotation; public @interface Qualifier {"
            + " String value() default \"\"; }",
        "demo/Octal.java", """
            package demo;
            import org.springframework.stereotype.Component;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.beans.factory.annotation.Qualifier;
            @Component public class Octal {
              @Autowired @Qualifier("\042red") private String value;
            }
            """);
    AnalysisContext context = springContext("octal-quote", files);
    System.setProperty(FLAG, "false"); // opt-out golden (#66)
    List<String> off = fieldQualifiers(new SourceMetadataScanner(context), "value");
    System.setProperty(FLAG, "true");
    List<String> on = fieldQualifiers(new SourceMetadataScanner(context), "value");
    assertEquals(off, on);
    assertTrue(off.toString().contains("\\042red"), off.toString());
  }

  @Test
  void escapedQuoteInRenderedValueKeepsMirrorParity() throws IOException {
    Map<String, String> files = Map.of(
        "org/springframework/stereotype/Component.java",
        "package org.springframework.stereotype; public @interface Component {}",
        "org/springframework/beans/factory/annotation/Autowired.java",
        "package org.springframework.beans.factory.annotation; public @interface Autowired {}",
        "demo/Quoted.java", """
            package demo;
            import org.springframework.stereotype.Component;
            import org.springframework.beans.factory.annotation.Autowired;
            @Component public class Quoted {
              @Autowired @Label("a\\"b") @Foo(role = @Role("x")) private String value;
            }
            @interface Label { String value() default ""; }
            @interface Role { String value() default ""; }
            @interface Foo { Role role() default @Role; }
            """);
    AnalysisContext context = springContext("quoted", files);
    System.setProperty(FLAG, "false"); // opt-out golden (#66)
    List<String> off = fieldQualifiers(new SourceMetadataScanner(context), "value");
    System.setProperty(FLAG, "true");
    List<String> on = fieldQualifiers(new SourceMetadataScanner(context), "value");
    // #48 fixed: the renderer re-escapes the quote inside "a\"b", so the
    // rendered arguments keep an even effective quote count and the
    // mirror no longer declines the Label occurrence on quote count
    // (Foo/Role still decline via the nested-@ rule). This cell pins
    // OUTPUT parity only — the mirror-stands-in mechanism itself is
    // pinned by the unit cells (rendered spellings, renderIsUsable
    // tolerance) and their mutation receipts; the textual answer and
    // the mirror answer stay equal either way.
    assertEquals(off, on);
    assertTrue(on.contains("Label") && on.contains("Foo") && on.contains("Role"),
        on.toString());
  }

  @Test
  void dollarPrefixCaptureKeepsItsOwnOccurrenceFact() throws IOException {
    // jakarta.inject.Named is deliberately NOT declared: it stays
    // unresolved, so Temurin 17 drops its mirror while JDK 27 keeps a
    // value-less one — both paths must keep the @Named("x") fact and
    // attribute Named$k to its own occurrence.
    Map<String, String> files = Map.of(
        "jakarta/inject/Inject.java", "package jakarta.inject; public @interface Inject {}",
        "demo/DollarPrefix.java", """
            package demo;
            import jakarta.inject.Inject;
            import jakarta.enterprise.context.ApplicationScoped;
            @ApplicationScoped public class DollarPrefix {
              @Inject @Named("x") @Named$k private String value;
            }
            @interface Named$k {}
            """,
        "jakarta/enterprise/context/ApplicationScoped.java",
        "package jakarta.enterprise.context;"
            + " public @interface ApplicationScoped {}");
    AnalysisContext context = context(
        "dollar-prefix", AnalysisContext.Framework.QUARKUS, files);
    System.setProperty(FLAG, "false"); // opt-out golden (#66)
    List<String> off = quarkusFieldQualifiers(
        new SourceMetadataScanner(context), "value");
    System.setProperty(FLAG, "true");
    List<String> on = quarkusFieldQualifiers(
        new SourceMetadataScanner(context), "value");
    // The @Named("x") occurrence must not consume the Named$k mirror and
    // silently drop its own value-bearing fact. Flag-off: the truncated
    // @Named$k capture emits the Named family and the CDI enrichment
    // rewrites the empty content to the field name. Flag-on: the $-name
    // mirror corrects its own bare occurrence.
    assertEquals(List.of("Named:x", "Named:value"), off);
    assertEquals(List.of("Named:x", "Named$k"), on);
  }

  @Test
  void quarkusFoldedPathIsResolvedFlagOnOnly() throws IOException {
    Map<String, String> files = Map.of(
        "jakarta/ws/rs/Path.java",
        "package jakarta.ws.rs; public @interface Path { String value() default \"\"; }",
        "demo/FoldedPath.java", """
            package demo;
            import jakarta.ws.rs.Path;
            @Path("greet" + "ing") public class FoldedPath {
              @POST public String hello() { return ""; }
            }
            """);
    AnalysisContext context = context(
        "quarkus-folded", AnalysisContext.Framework.QUARKUS, files);
    System.setProperty(FLAG, "false"); // opt-out golden (#66)
    List<SourceMetadataScannerState.Route> off = new SourceMetadataScanner(context)
        .routes(AnalysisContext.Framework.QUARKUS);
    System.setProperty(FLAG, "true");
    List<SourceMetadataScannerState.Route> on = new SourceMetadataScanner(context)
        .routes(AnalysisContext.Framework.QUARKUS);
    // QUARKUS values-path index engagement (non-vacuity on the CI JDK):
    // the folded constant resolves only through the mirror; the textual
    // literal extraction cannot, so the class is not routeable flag-off.
    assertTrue(off.isEmpty(), off.toString());
    assertTrue(on.stream().anyMatch(route ->
        route.path().equals("/greeting") && route.httpMethod().equals("POST")),
        on.toString());
  }

  @Test
  void dollarNamedWithArgumentsAtFieldSiteIsUndiscoverable() throws IOException {
    // jakarta.inject.Named is deliberately NOT declared (unresolved).
    Map<String, String> files = Map.of(
        "jakarta/inject/Inject.java", "package jakarta.inject; public @interface Inject {}",
        "jakarta/enterprise/context/ApplicationScoped.java",
        "package jakarta.enterprise.context;"
            + " public @interface ApplicationScoped {}",
        "demo/DollarArgs.java", """
            package demo;
            import jakarta.inject.Inject;
            import jakarta.enterprise.context.ApplicationScoped;
            @ApplicationScoped public class DollarArgs {
              @Inject @Named$k("y") private String value;
            }
            @interface Named$k {}
            """);
    AnalysisContext context = context(
        "dollar-args", AnalysisContext.Framework.QUARKUS, files);
    for (boolean flag : new boolean[] {false, true}) {
      if (flag) {
        System.setProperty(FLAG, "true");
      } else {
        System.setProperty(FLAG, "false"); // opt-out golden (#66)
      }
      // The INJECT_FIELD discovery regex cannot span the arguments after a
      // $-named annotation (its type class excludes the parens), so the
      // field is undiscoverable in BOTH flag states — the round-6 review's
      // hypothetical field-site "$-name with arguments" divergence has no
      // reachable input.
      assertTrue(new SourceMetadataScanner(context)
          .injectionPoints(AnalysisContext.Framework.QUARKUS).stream()
          .noneMatch(point -> point.member().equals("value")));
    }
  }

  private List<String> profilesOf(SourceMetadataScanner scanner, String id) {
    return scanner.beanCandidates(AnalysisContext.Framework.SPRING_BOOT).stream()
        .filter(bean -> bean.candidateId().equals(id))
        .findFirst().orElseThrow().profiles();
  }

  private List<String> quarkusFieldQualifiers(SourceMetadataScanner scanner, String field) {
    return scanner.injectionPoints(AnalysisContext.Framework.QUARKUS).stream()
        .filter(point -> point.kind().equals("field") && point.member().equals(field))
        .findFirst().orElseThrow().qualifiers();
  }

  private List<String> qualifierListOf(SourceMetadataScanner scanner, String id) {
    return scanner.beanCandidates(AnalysisContext.Framework.SPRING_BOOT).stream()
        .filter(bean -> bean.candidateId().equals(id))
        .findFirst().orElseThrow().qualifiers();
  }

  private List<String> fieldQualifiers(SourceMetadataScanner scanner, String field) {
    return scanner.injectionPoints(AnalysisContext.Framework.SPRING_BOOT).stream()
        .filter(point -> point.kind().equals("field") && point.member().equals(field))
        .findFirst().orElseThrow().qualifiers();
  }

  private AnalysisContext springFixture(String id) throws IOException {
    return springContext(id, Map.ofEntries(
        Map.entry("org/springframework/web/bind/annotation/RequestMethod.java",
            "package org.springframework.web.bind.annotation;"
                + " public enum RequestMethod { GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS }"),
        Map.entry("org/springframework/web/bind/annotation/RestController.java",
            "package org.springframework.web.bind.annotation; public @interface RestController {}"),
        Map.entry("org/springframework/web/bind/annotation/RequestMapping.java",
            """
            package org.springframework.web.bind.annotation;
            public @interface RequestMapping {
              String[] value() default {};
              String[] path() default {};
              RequestMethod[] method() default {};
              String[] consumes() default {};
              String[] produces() default {};
              String[] headers() default {};
              String[] params() default {};
            }
            """),
        Map.entry("org/springframework/web/bind/annotation/GetMapping.java",
            """
            package org.springframework.web.bind.annotation;
            public @interface GetMapping {
              String[] value() default {};
              String[] path() default {};
              String[] consumes() default {};
              String[] produces() default {};
              String[] headers() default {};
              String[] params() default {};
            }
            """),
        Map.entry("org/springframework/web/bind/annotation/PostMapping.java",
            """
            package org.springframework.web.bind.annotation;
            public @interface PostMapping {
              String[] value() default {};
              String[] path() default {};
              String[] consumes() default {};
              String[] produces() default {};
              String[] headers() default {};
              String[] params() default {};
            }
            """),
        Map.entry("org/springframework/stereotype/Service.java",
            "package org.springframework.stereotype;"
                + " public @interface Service { String value() default \"\"; }"),
        Map.entry("org/springframework/context/annotation/Configuration.java",
            "package org.springframework.context.annotation; public @interface Configuration {}"),
        Map.entry("org/springframework/context/annotation/Bean.java",
            "package org.springframework.context.annotation; public @interface Bean {}"),
        Map.entry("org/springframework/context/annotation/Profile.java",
            "package org.springframework.context.annotation;"
                + " public @interface Profile { String[] value() default {}; }"),
        Map.entry("org/springframework/beans/factory/annotation/Autowired.java",
            "package org.springframework.beans.factory.annotation; public @interface Autowired {}"),
        Map.entry("org/springframework/beans/factory/annotation/Qualifier.java",
            "package org.springframework.beans.factory.annotation;"
                + " public @interface Qualifier { String value() default \"\"; }"),
        Map.entry("org/springframework/boot/autoconfigure/condition/ConditionalOnProperty.java",
            "package org.springframework.boot.autoconfigure.condition;"
                + " public @interface ConditionalOnProperty { String name() default \"\"; }"),
        Map.entry("demo/Api.java", """
            package demo;
            import org.springframework.web.bind.annotation.RestController;
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.RequestMethod;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.PostMapping;
            @RestController @RequestMapping(value = "/api", headers = "X-Class=1")
            public class Api {
              @GetMapping("/items") public String list() { return ""; }
              @PostMapping(value = {"/items", "/items2"}, consumes = "application/json",
                  produces = "application/json", headers = "X-Req=1", params = "mode=full")
              public String create(String body) { return ""; }
              @RequestMapping(method = RequestMethod.GET) public String plain() { return ""; }
            }
            """),
        Map.entry("demo/QualifiedService.java", """
            package demo;
            import org.springframework.stereotype.Service;
            import org.springframework.beans.factory.annotation.Qualifier;
            import org.springframework.context.annotation.Profile;
            import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
            import org.springframework.beans.factory.annotation.Autowired;
            @Service("namedService") @Qualifier("q") @Profile("prod")
            @ConditionalOnProperty(name = "enabled")
            public class QualifiedService {
              @Autowired @Qualifier("dep") private Object dependency;
              @Autowired public void setHelper(@Qualifier("h") Object helper) {}
              public QualifiedService(@Qualifier("c") Object dep) {}
            }
            """),
        Map.entry("demo/Config.java", """
            package demo;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            import org.springframework.beans.factory.annotation.Qualifier;
            @Configuration public class Config {
              @Bean @Qualifier("produced") public Object thing() { return new Object(); }
            }
            """)));
  }

  private AnalysisContext quarkusFixture(String id) throws IOException {
    return context(id, AnalysisContext.Framework.QUARKUS, Map.of(
        "jakarta/inject/Inject.java", "package jakarta.inject; public @interface Inject {}",
        "jakarta/inject/Named.java",
        "package jakarta.inject; public @interface Named { String value() default \"\"; }",
        "jakarta/annotation/Resource.java",
        "package jakarta.annotation; public @interface Resource { String name() default \"\"; }",
        "jakarta/ws/rs/Path.java",
        "package jakarta.ws.rs; public @interface Path { String value() default \"\"; }",
        "jakarta/ws/rs/Consumes.java",
        "package jakarta.ws.rs; public @interface Consumes { String[] value() default {}; }",
        "jakarta/ws/rs/Produces.java",
        "package jakarta.ws.rs; public @interface Produces { String[] value() default {}; }",
        "demo/GreetingResource.java", """
            package demo;
            import jakarta.ws.rs.Path;
            import jakarta.ws.rs.POST;
            import jakarta.ws.rs.Consumes;
            import jakarta.ws.rs.Produces;
            @Path("/greet") public class GreetingResource {
              @POST @Consumes("text/plain") @Produces("text/plain")
              public String hello(String body) { return ""; }
            }
            """,
        "demo/Greeter.java", """
            package demo;
            import jakarta.enterprise.context.ApplicationScoped;
            import jakarta.inject.Named;
            import jakarta.inject.Inject;
            import jakarta.annotation.Resource;
            @ApplicationScoped @Named("greeter")
            public class Greeter {
              @Inject @Resource(name = "greeting") private String greeting;
              @Inject public void configure(@Named("cfg") String config) {}
              @Inject public void setAll(@Named("a") String a, @Named("b") String... rest) {}
              private String decoy = "@Path(\\"/fake\\") @Inject";
              // @Inject @Path("/comment") String commented;
            }
            """));
  }

  private AnalysisContext springContext(String id, Map<String, String> files)
      throws IOException {
    return context(id, AnalysisContext.Framework.SPRING_BOOT, files);
  }

  private AnalysisContext context(
      String id, AnalysisContext.Framework framework, Map<String, String> files)
      throws IOException {
    Path root = temp.resolve(id);
    Path sourceRoot = root.resolve("src/main/java");
    for (Map.Entry<String, String> entry : files.entrySet()) {
      Path file = sourceRoot.resolve(entry.getKey());
      Files.createDirectories(file.getParent());
      Files.writeString(file, entry.getValue());
    }
    return AnalysisContext.builder()
        .id(id)
        .framework(framework)
        .frameworkVersion(framework == AnalysisContext.Framework.SPRING_BOOT ? "3.4.0" : "3.40.1")
        .fixtureId("javac-framework-annotation-test")
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
