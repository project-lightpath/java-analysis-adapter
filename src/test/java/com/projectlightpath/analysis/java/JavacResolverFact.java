package com.projectlightpath.analysis.java;

import java.util.List;

/** One compiler-resolved ServiceLoader fact with symbol evidence. */
record JavacResolverFact(
    String cellId,
    String path,
    String topLevel,
    int line,
    int column,
    int offset,
    String serviceType,
    String owner,
    String member,
    String accessibility,
    String certainty,
    String promotion,
    List<JavacResolverDiagnostic> diagnostics) {

  JavacResolverFact {
    diagnostics = List.copyOf(diagnostics);
  }
}
