package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * RED_16 compile stub: behavior-neutral placeholders for the javac prototype.
 *
 * <p>This stub exists only so the failing accuracy tests compile. It returns no
 * facts and no parameter type; GREEN_16 replaces it with the compiler-backed
 * implementation.
 */
final class JavacSourceSymbolResolver {
  private JavacSourceSymbolResolver() {}

  static JavacResolverResult resolve(
      JavacResolverCell cell, List<Path> unitRoots, Path classesDirectory) throws IOException {
    return JavacResolverResult.empty(cell.id());
  }

  static String parameterType(
      String source, String className, String methodName, int parameterIndex, Path workRoot)
      throws IOException {
    return "<stub>";
  }
}
