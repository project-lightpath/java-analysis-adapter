package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Proves each accuracy detector fires on a synthetic control. */
class JavacResolverMetricsTest {
  @Test
  void definiteWhereShadowExpectedIsAWrongDefinite() {
    JavacResolverMetrics metrics = Evaluation.evaluate(
        cell(JavacResolverCell.Expectation.SHADOWED),
        result(fact("DEFINITE", "java.lang.Object"), List.of()));
    assertEquals(1, metrics.wrongDefinite());
    assertEquals(1, metrics.added());
    assertEquals(1, metrics.unexpectedFact());
  }

  @Test
  void missingRequiredSiteIsLost() {
    JavacResolverMetrics metrics = Evaluation.evaluate(
        cell(JavacResolverCell.Expectation.DEFINITE),
        JavacResolverResult.empty("control"));
    assertEquals(1, metrics.lost());
  }

  @Test
  void possibleWhereDefiniteExpectedIsDowngraded() {
    JavacResolverMetrics metrics = Evaluation.evaluate(
        cell(JavacResolverCell.Expectation.DEFINITE),
        result(fact("POSSIBLE", "java.lang.Object"), List.of()));
    assertEquals(1, metrics.downgraded());
    assertEquals(0, metrics.wrongDefinite());
  }

  @Test
  void unexpectedPossibleFactIsAddedWithoutBecomingWrongDefinite() {
    JavacResolverMetrics metrics = Evaluation.evaluate(
        cell(JavacResolverCell.Expectation.SHADOWED),
        result(fact("POSSIBLE", "java.lang.Object"), List.of()));
    assertEquals(1, metrics.added());
    assertEquals(0, metrics.wrongDefinite());
  }

  @Test
  void differentCanonicalTypeIsWrongServiceType() {
    JavacResolverMetrics metrics = Evaluation.evaluate(
        cell(JavacResolverCell.Expectation.DEFINITE),
        result(fact("DEFINITE", "java.lang.String"), List.of()));
    assertEquals(1, metrics.wrongServiceType());
  }

  @Test
  void ambiguousExpectationWithoutDiagnosticMismatches() {
    JavacResolverMetrics metrics = Evaluation.evaluate(
        cell(JavacResolverCell.Expectation.AMBIGUOUS),
        JavacResolverResult.empty("control"));
    assertEquals(1, metrics.abstentionMismatch());
  }

  private static JavacResolverCell cell(JavacResolverCell.Expectation expectation) {
    return new JavacResolverCell(
        "control", JavacResolverCell.Family.PRECEDENCE, "demo/Bridge.java", "demo.Bridge",
        "audit", 17, Map.of("demo/Bridge.java", "package demo; class Bridge { }"),
        List.of(), true,
        List.of(new JavacResolverCell.ExpectedSite(expectation, "java.lang.Object")));
  }

  private static JavacResolverResult result(
      JavacResolverFact fact, List<JavacResolverAbstention> abstentions) {
    return new JavacResolverResult("control", List.of(fact), abstentions);
  }

  private static JavacResolverFact fact(String certainty, String serviceType) {
    return new JavacResolverFact(
        "control", "demo/Bridge.java", "demo.Bridge", 3, 1, 30, serviceType,
        "java.util.ServiceLoader", "load", "PUBLIC_STATIC", certainty, List.of());
  }
}
