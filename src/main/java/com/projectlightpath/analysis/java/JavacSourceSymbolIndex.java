package com.projectlightpath.analysis.java;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * RED_21 stub for the javac source-symbol index.
 *
 * <p>This stub exists only so the RED commit compiles: {@link #of} always
 * returns an empty optional, every read method is a no-op, and the
 * compiler is never invoked. The GREEN commit replaces the bodies with
 * the real one-parse-per-context index; the member surface here is the
 * contract the tests compile against.
 */
final class JavacSourceSymbolIndex {
  /** Test seam: number of javac tasks the index build path started. */
  static final AtomicLong COMPILATIONS = new AtomicLong();

  /** Test seam: compiler supplier, injectable to simulate unavailability. */
  static final AtomicReference<Supplier<JavaCompiler>> COMPILER =
      new AtomicReference<>(ToolProvider::getSystemJavaCompiler);

  private JavacSourceSymbolIndex() {}

  /** Reports whether the index seam flag is enabled (default off). */
  static boolean enabled() {
    return Boolean.getBoolean("flpath.java.symbolindex");
  }

  /** Returns the per-context index; empty while the flag is off (stub: always). */
  static Optional<JavacSourceSymbolIndex> of(AnalysisContext context) {
    return Optional.empty();
  }

  /** Returns every indexed type sharing a canonical or binary name. */
  List<TypeInfo> type(String canonicalOrBinaryName) {
    return List.of();
  }

  /** Returns the top-level and nested types declared in one source file. */
  List<TypeInfo> typesIn(Path sourceFile) {
    return List.of();
  }

  /** Returns the annotations declared in one package-info.java unit. */
  List<AnnotationInfo> packageAnnotations(Path packageInfoFile) {
    return List.of();
  }

  /** Returns every member with the given name on every entry sharing the type name. */
  List<MemberInfo> members(String typeName, String memberName) {
    return List.of();
  }

  /** Returns the index-level and compiler diagnostics recorded at build time. */
  List<IndexDiagnostic> diagnostics() {
    return List.of();
  }

  /** Wall time of the index build in milliseconds. */
  long wallMillis() {
    return 0;
  }

  /** Peak sampled heap during the index build in bytes. */
  long peakHeapBytes() {
    return 0;
  }

  /** Declaration kind plus the error and duplicate-binary-name flags of one type. */
  record TypeShape(String declarationKind, boolean errorType, boolean duplicateBinaryName) {}

  /** One indexed type declaration. */
  record TypeInfo(
      String canonicalName,
      String binaryName,
      String nestingKind,
      Path file,
      List<AnnotationInfo> annotations,
      List<MemberInfo> members,
      TypeShape shape) {}

  /** One indexed member declaration. */
  record MemberInfo(
      String name,
      String kind,
      List<AnnotationInfo> annotations,
      List<String> parameterTypes,
      String returnType,
      boolean errorType) {}

  /** One annotation with its rendered element values. */
  record AnnotationInfo(String canonicalName, Map<String, String> values) {}

  /** One diagnostic row; path is never absolute. */
  record IndexDiagnostic(String code, String message, String path, long line, long column) {}
}
