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
    System.clearProperty(FLAG);
    SourceMetadataScanner textual = new SourceMetadataScanner(baseline);
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    System.setProperty(FLAG, "true");
    scanner.routes(AnalysisContext.Framework.SPRING_BOOT);
    scanner.beanCandidates(AnalysisContext.Framework.SPRING_BOOT);
    scanner.injectionPoints(AnalysisContext.Framework.SPRING_BOOT);
    System.clearProperty(FLAG);
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
    System.clearProperty(FLAG);
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
    System.clearProperty(FLAG);
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
    System.clearProperty(FLAG);
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
        System.clearProperty(FLAG);
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
        System.clearProperty(FLAG);
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
    System.clearProperty(FLAG);
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
        "demo/Mar$k.java", "package demo; public @interface Mar$k {}",
        "demo/Omega.java", "package demo; public @interface Ωmega {}",
        "demo/Weird.java", """
            package demo;
            import org.springframework.stereotype.Service;
            import demo.Mar$k;
            import demo.Omega;
            @Service @Mar$k @Ωmega public class Weird {}
            """);
    AnalysisContext context = springContext("dollar-bean", files);
    System.clearProperty(FLAG);
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
        "demo/Mar$k.java", "package demo; public @interface Mar$k {}",
        "demo/Holder.java", """
            package demo;
            import org.springframework.stereotype.Component;
            import org.springframework.beans.factory.annotation.Autowired;
            import demo.Mar$k;
            @Component public class Holder {
              @Autowired @Mar$k private String value;
            }
            """);
    AnalysisContext context = springContext("dollar-field", files);
    System.clearProperty(FLAG);
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
    System.clearProperty(FLAG);
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
    System.clearProperty(FLAG);
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
    System.clearProperty(FLAG);
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
    System.clearProperty(FLAG);
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
    System.clearProperty(FLAG);
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
    System.clearProperty(FLAG);
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
    System.clearProperty(FLAG);
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
        System.clearProperty(FLAG);
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
