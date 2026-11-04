package com.projectlightpath.analysis.java;

import java.util.List;
import java.util.Set;

/** Provider-neutral view of the SootUp base graph used by the enrichment layer. */
public record AnalysisGraph(
    List<CallEdge> calls,
    List<DynamicFeature> dynamicFeatures,
    Set<String> applicationClasses,
    String providerName,
    String providerVersion,
    int methodCount,
    int callCount) {
  public AnalysisGraph {
    calls = List.copyOf(calls == null ? List.of() : calls);
    dynamicFeatures = List.copyOf(dynamicFeatures == null ? List.of() : dynamicFeatures);
    applicationClasses = Set.copyOf(applicationClasses == null ? Set.of() : applicationClasses);
    if (providerName == null || providerName.isBlank()) {
      throw new IllegalArgumentException("provider name must not be blank");
    }
    if (providerVersion == null || providerVersion.isBlank()) {
      throw new IllegalArgumentException("provider version must not be blank");
    }
    if (methodCount < 0 || callCount < 0) {
      throw new IllegalArgumentException("graph counts must not be negative");
    }
  }

  public static AnalysisGraph empty() {
    return new AnalysisGraph(List.of(), List.of(), Set.of(), "SootUp", "3.0.1", 0, 0);
  }
}
