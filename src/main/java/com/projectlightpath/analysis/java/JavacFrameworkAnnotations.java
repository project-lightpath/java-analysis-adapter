package com.projectlightpath.analysis.java;

import java.util.List;
import java.util.Optional;

/**
 * javac framework-annotation read layer for one scanner's context (RED_24
 * stub: every lookup declines, so all migrated call sites keep the textual
 * answer in both flag states; the GREEN_24 commit fills the correlation).
 *
 * <p>The flag gate is call-site-local: the framework scanning layers choose
 * this helper and fall back to the textual statics whenever a lookup here
 * declines. Shared static helpers are never modified.
 */
final class JavacFrameworkAnnotations {
  private final AnalysisContext context;

  private JavacFrameworkAnnotations(AnalysisContext context) {
    this.context = context;
  }

  /** Returns the helper bound to one scanner's context. */
  static JavacFrameworkAnnotations of(SourceMetadataScanner scanner) {
    return new JavacFrameworkAnnotations(scanner.context);
  }

  /** Reports whether the index seam flag is enabled. */
  boolean enabled() {
    return JavacSourceSymbolIndex.enabled();
  }

  /** Returns the type-level annotation mirrors of a file's first type. */
  Optional<List<JavacSourceSymbolIndex.AnnotationInfo>> typeAnnotations(
      SourceMetadataScannerState.SourceFile file) {
    return Optional.empty();
  }

  /** Returns the unique index member matching owner, name, kind, and arity. */
  Optional<JavacSourceSymbolIndex.MemberInfo> member(
      String owner, String name, String kind, int arity) {
    return Optional.empty();
  }

  /** Returns the per-parameter annotation mirrors of one member (#37 API). */
  Optional<List<List<JavacSourceSymbolIndex.AnnotationInfo>>> parameterAnnotations(
      String owner, String name, String kind, int arity, int parsedCount) {
    return Optional.empty();
  }

  /** Six-family qualifier table on index mirrors (guarded arm optional). */
  Optional<List<String>> qualifiers(
      SourceMetadataScannerState.SourceFile file,
      String block,
      List<JavacSourceSymbolIndex.AnnotationInfo> mirrors,
      boolean guarded) {
    return Optional.empty();
  }

  /** Qualifier table plus the injection enrichment derivations. */
  Optional<List<String>> injectionQualifiers(
      SourceMetadataScannerState.SourceFile file,
      String block,
      List<JavacSourceSymbolIndex.AnnotationInfo> mirrors,
      String defaultName,
      boolean cdi,
      boolean setterMethod) {
    return Optional.empty();
  }

  /** Attribute values of one gate-accepted annotation, from the index. */
  Optional<List<String>> values(
      List<JavacSourceSymbolIndex.AnnotationInfo> mirrors,
      String block,
      String annotation,
      String attribute,
      boolean canonical) {
    return Optional.empty();
  }

  /** Spring mapping paths (value/path attributes) from the index. */
  Optional<List<String>> mappingPaths(
      List<JavacSourceSymbolIndex.AnnotationInfo> mirrors,
      SourceMetadataScannerState.SourceFile file,
      String block,
      String annotation) {
    return Optional.empty();
  }

  /** RequestMapping http methods from the index. */
  Optional<List<String>> requestMappingHttpMethods(
      List<JavacSourceSymbolIndex.AnnotationInfo> mirrors,
      SourceMetadataScannerState.SourceFile file,
      String block) {
    return Optional.empty();
  }

  /** Media values of one gate-accepted annotation, from the index. */
  Optional<List<String>> mediaValues(
      List<JavacSourceSymbolIndex.AnnotationInfo> mirrors,
      SourceMetadataScannerState.SourceFile file,
      String block,
      String annotation,
      String attribute) {
    return Optional.empty();
  }

  /** QUARKUS literal path values from the index. */
  Optional<List<String>> pathValues(
      List<JavacSourceSymbolIndex.AnnotationInfo> mirrors,
      String block,
      String annotation) {
    return Optional.empty();
  }

  /** Reports whether a mapping annotation names an explicit path attribute. */
  Optional<Boolean> hasExplicitMappingPath(
      List<JavacSourceSymbolIndex.AnnotationInfo> mirrors,
      SourceMetadataScannerState.SourceFile file,
      String block,
      String annotation) {
    return Optional.empty();
  }

  /** Condition-annotation evidence, rendered from the index mirrors. */
  Optional<List<String>> conditionAnnotations(
      List<JavacSourceSymbolIndex.AnnotationInfo> mirrors,
      SourceMetadataScannerState.SourceFile file,
      String block,
      List<String> names,
      boolean canonical) {
    return Optional.empty();
  }
}
