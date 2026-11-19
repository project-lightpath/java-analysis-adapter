package com.projectlightpath.analysis.java;

import java.util.List;

/** Facts and abstentions produced for one cell by one resolution path. */
record JavacResolverResult(
    String cellId,
    List<JavacResolverFact> facts,
    List<JavacResolverAbstention> abstentions) {

  JavacResolverResult {
    facts = List.copyOf(facts);
    abstentions = List.copyOf(abstentions);
  }

  static JavacResolverResult empty(String cellId) {
    return new JavacResolverResult(cellId, List.of(), List.of());
  }
}
