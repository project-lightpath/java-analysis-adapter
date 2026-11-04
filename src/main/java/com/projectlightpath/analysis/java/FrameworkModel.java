package com.projectlightpath.analysis.java;

import java.util.List;

/** Project-owned framework enrichment boundary; it never mutates the provider graph. */
public interface FrameworkModel {
  AnalysisContext.Framework framework();

  List<SemanticCallFacts.Fact> enrich(AnalysisContext context, AnalysisGraph graph);
}
