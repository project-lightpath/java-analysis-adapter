package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Compares one path's facts and abstentions with a cell's per-site expectations. */
final class Evaluation {
  private Evaluation() {}

  static JavacResolverMetrics evaluate(JavacResolverCell cell, JavacResolverResult result) {
    List<Object> sites = new ArrayList<>();
    sites.addAll(result.facts());
    sites.addAll(result.abstentions());
    sites.sort(Comparator.comparingInt(Evaluation::offset));
    List<JavacResolverCell.ExpectedSite> expected = cell.expectedSites();
    int wrongDefinite = 0;
    int wrongServiceType = 0;
    int lost = 0;
    int downgraded = 0;
    int added = 0;
    int unexpected = 0;
    int abstentionMismatch = 0;
    int shared = Math.min(expected.size(), sites.size());
    for (int index = 0; index < shared; index++) {
      JavacResolverCell.ExpectedSite site = expected.get(index);
      Object actual = sites.get(index);
      if (actual instanceof JavacResolverFact fact) {
        boolean definite = "DEFINITE".equals(fact.certainty());
        switch (site.expectation()) {
          case DEFINITE -> {
            if (!definite) {
              downgraded++;
            }
            if (!fact.serviceType().equals(site.serviceType())) {
              wrongServiceType++;
            }
          }
          case POSSIBLE, MISSING_INPUT -> {
            if (definite) {
              wrongDefinite++;
            }
            if (!fact.serviceType().equals(site.serviceType())) {
              wrongServiceType++;
            }
            if (site.expectation() == JavacResolverCell.Expectation.MISSING_INPUT
                && fact.diagnostics().isEmpty()) {
              abstentionMismatch++;
            }
          }
          case AMBIGUOUS, SHADOWED -> {
            added++;
            unexpected++;
            if (definite) {
              wrongDefinite++;
            }
          }
        }
      } else {
        JavacResolverAbstention abstention = (JavacResolverAbstention) actual;
        switch (site.expectation()) {
          case DEFINITE -> lost++;
          case AMBIGUOUS, MISSING_INPUT -> {
            if (abstention.diagnostics().isEmpty()) {
              abstentionMismatch++;
            }
          }
          default -> { }
        }
      }
    }
    for (int index = shared; index < expected.size(); index++) {
      JavacResolverCell.ExpectedSite site = expected.get(index);
      switch (site.expectation()) {
        case DEFINITE -> lost++;
        case AMBIGUOUS, MISSING_INPUT -> abstentionMismatch++;
        default -> { }
      }
    }
    for (int index = shared; index < sites.size(); index++) {
      if (sites.get(index) instanceof JavacResolverFact fact) {
        added++;
        unexpected++;
        if ("DEFINITE".equals(fact.certainty())) {
          wrongDefinite++;
        }
      }
    }
    return new JavacResolverMetrics(
        wrongDefinite, wrongServiceType, lost, downgraded, added, unexpected, abstentionMismatch);
  }

  private static int offset(Object site) {
    if (site instanceof JavacResolverFact fact) {
      return fact.offset();
    }
    return ((JavacResolverAbstention) site).offset();
  }
}
