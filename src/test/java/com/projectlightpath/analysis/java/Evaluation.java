package com.projectlightpath.analysis.java;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compares one path's facts and abstentions with a cell's per-site expectations. */
final class Evaluation {
  private Evaluation() {}

  static JavacResolverMetrics evaluate(JavacResolverCell cell, JavacResolverResult result) {
    Map<Integer, Object> actual = new HashMap<>();
    for (JavacResolverFact fact : result.facts()) {
      actual.put(fact.offset(), fact);
    }
    for (JavacResolverAbstention abstention : result.abstentions()) {
      actual.putIfAbsent(abstention.offset(), abstention);
    }
    int wrongDefinite = 0;
    int wrongServiceType = 0;
    int lost = 0;
    int downgraded = 0;
    int added = 0;
    int unexpected = 0;
    int abstentionMismatch = 0;
    Set<Integer> matched = new HashSet<>();
    for (JavacResolverCell.ExpectedSite site : cell.expectedSites()) {
      Object found = actual.get(site.offset());
      if (found == null) {
        switch (site.expectation()) {
          case DEFINITE -> lost++;
          case POSSIBLE, AMBIGUOUS, MISSING_INPUT, NON_LITERAL_FORNAME, UNRESOLVED_FORNAME ->
            abstentionMismatch++;
          default -> { }
        }
        continue;
      }
      matched.add(site.offset());
      if (found instanceof JavacResolverFact fact) {
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
                && !hasEvidence(fact.diagnostics(), site.expectation())) {
              abstentionMismatch++;
            }
          }
          case AMBIGUOUS, SHADOWED, NO_FACT, NON_LITERAL_FORNAME, UNRESOLVED_FORNAME -> {
            added++;
            unexpected++;
            if (definite) {
              wrongDefinite++;
            }
          }
        }
      } else {
        JavacResolverAbstention abstention = (JavacResolverAbstention) found;
        switch (site.expectation()) {
          case DEFINITE, POSSIBLE, MISSING_INPUT -> lost++;
          case AMBIGUOUS -> {
            if (!hasEvidence(abstention.diagnostics(), site.expectation())) {
              abstentionMismatch++;
            }
          }
          default -> { }
        }
        String expectedReason = expectedReason(site.expectation());
        if (expectedReason != null && !expectedReason.equals(abstention.reason())) {
          abstentionMismatch++;
        }
      }
    }
    for (Map.Entry<Integer, Object> entry : actual.entrySet()) {
      if (matched.contains(entry.getKey())) {
        continue;
      }
      if (entry.getValue() instanceof JavacResolverFact fact) {
        added++;
        unexpected++;
        if ("DEFINITE".equals(fact.certainty())) {
          wrongDefinite++;
        }
      } else {
        abstentionMismatch++;
      }
    }
    return new JavacResolverMetrics(
        wrongDefinite, wrongServiceType, lost, downgraded, added, unexpected, abstentionMismatch);
  }

  private static String expectedReason(JavacResolverCell.Expectation expectation) {
    return switch (expectation) {
      case AMBIGUOUS -> "AMBIGUOUS";
      case SHADOWED -> "SHADOWED";
      case NO_FACT -> "NO_CLASS_LITERAL";
      case NON_LITERAL_FORNAME -> "NON_LITERAL_FORNAME";
      case UNRESOLVED_FORNAME -> "UNRESOLVED_FORNAME";
      default -> null;
    };
  }

  private static boolean hasEvidence(
      List<JavacResolverDiagnostic> diagnostics, JavacResolverCell.Expectation expectation) {
    if (diagnostics.isEmpty()) {
      return false;
    }
    return diagnostics.stream().anyMatch(diagnostic -> switch (expectation) {
      case AMBIGUOUS -> diagnostic.code().contains("ref.ambiguous")
          || diagnostic.message().toLowerCase(java.util.Locale.ROOT).contains("ambiguous");
      case MISSING_INPUT -> diagnostic.code().contains("doesnt.exist")
          || diagnostic.code().contains("cant.resolve")
          || diagnostic.code().contains("duplicate.class")
          || diagnostic.code().contains("not.def.public")
          || diagnostic.message().toLowerCase(java.util.Locale.ROOT).contains("does not exist")
          || diagnostic.message().toLowerCase(java.util.Locale.ROOT).contains("cannot find symbol");
      default -> false;
    });
  }
}
