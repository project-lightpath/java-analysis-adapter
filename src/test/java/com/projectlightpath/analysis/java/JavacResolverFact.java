package com.projectlightpath.analysis.java;

import java.util.List;

/** One compiler-resolved fact with symbol evidence. */
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
    List<JavacResolverDiagnostic> diagnostics,
    String annotationName,
    String annotationValue) {

  JavacResolverFact {
    diagnostics = List.copyOf(diagnostics);
  }

  /**
   * ServiceLoader-shaped facts carry no annotation observation; this overload
   * keeps the original construction sites unchanged.
   */
  JavacResolverFact(
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
    this(cellId, path, topLevel, line, column, offset, serviceType, owner, member,
        accessibility, certainty, promotion, diagnostics, "", "");
  }
}
