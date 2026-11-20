package com.projectlightpath.analysis.java;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Accuracy counters for one path over one cell. */
record JavacResolverMetrics(
    int wrongDefinite,
    int wrongServiceType,
    int lost,
    int downgraded,
    int added,
    int unexpectedFact,
    int abstentionMismatch) {

  static JavacResolverMetrics sum(List<JavacResolverMetrics> values) {
    int wrongDefinite = 0;
    int wrongServiceType = 0;
    int lost = 0;
    int downgraded = 0;
    int added = 0;
    int unexpectedFact = 0;
    int abstentionMismatch = 0;
    for (JavacResolverMetrics value : values) {
      wrongDefinite += value.wrongDefinite;
      wrongServiceType += value.wrongServiceType;
      lost += value.lost;
      downgraded += value.downgraded;
      added += value.added;
      unexpectedFact += value.unexpectedFact;
      abstentionMismatch += value.abstentionMismatch;
    }
    return new JavacResolverMetrics(wrongDefinite, wrongServiceType, lost, downgraded,
        added, unexpectedFact, abstentionMismatch);
  }

  Map<String, Object> asMap() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("wrongDefinite", wrongDefinite);
    value.put("wrongServiceType", wrongServiceType);
    value.put("lost", lost);
    value.put("downgraded", downgraded);
    value.put("added", added);
    value.put("unexpectedFact", unexpectedFact);
    value.put("abstentionMismatch", abstentionMismatch);
    return value;
  }
}
