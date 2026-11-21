package com.projectlightpath.analysis.java;

import java.util.List;

/** Facts, abstentions, and selected source evidence for one resolution path. */
record JavacResolverResult(
    String cellId,
    List<JavacResolverFact> facts,
    List<JavacResolverAbstention> abstentions,
    String selectedPath,
    String selectedTopLevel) {

  JavacResolverResult {
    facts = List.copyOf(facts);
    abstentions = List.copyOf(abstentions);
  }

  JavacResolverResult(
      String cellId, List<JavacResolverFact> facts, List<JavacResolverAbstention> abstentions) {
    this(cellId, facts, abstentions, "", "");
  }

  static JavacResolverResult empty(String cellId) {
    return new JavacResolverResult(cellId, List.of(), List.of());
  }
}
