package com.projectlightpath.analysis.java;

import java.util.List;

/** One syntactic load site that the compiler did not bind to java.util.ServiceLoader. */
record JavacResolverAbstention(
    String cellId,
    String path,
    int line,
    int column,
    int offset,
    String reason,
    List<JavacResolverDiagnostic> diagnostics) {

  JavacResolverAbstention {
    diagnostics = List.copyOf(diagnostics);
  }
}
