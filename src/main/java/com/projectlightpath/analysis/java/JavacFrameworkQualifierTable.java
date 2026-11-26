package com.projectlightpath.analysis.java;

import java.util.List;

/**
 * The six-family qualifier table applied to one index mirror's rendered
 * arguments, with the per-occurrence canonical-Spring guard (issue #66).
 *
 * <p>The guarded arm resolves the guard PER OCCURRENCE: a mirror can stand
 * in for its occurrence only when that occurrence itself resolves to a
 * canonical Spring type — the pre-#66 block-level guard let a user-defined
 * same-simple-name lookalike (e.g. {@code @demo.Qualifier}) ride a
 * canonical {@code @Qualifier} elsewhere in the same block. When the
 * occurrence resolves canonically, the paired mirror's canonical name must
 * equal that resolution by STRICT equality (never set membership: the
 * multi-FQN families would let a {@code javax} occurrence take a
 * {@code jakarta} mirror's value); a mismatch declines the mirror
 * replacement (null) and the caller falls to the textual occurrence
 * answer. A null occurrence (the remaining-mirrors loop, pure index
 * corrections) skips the guarded block: every
 * {@code SPRING_BEAN_ANNOTATION_TYPES} key is ASCII-initial and that loop
 * only sees empty-ASCII-prefix names, so the block is unreachable there by
 * construction.
 */
final class JavacFrameworkQualifierTable {

  private JavacFrameworkQualifierTable() {}

  /** Applies the six-family table to one mirror's rendered arguments. */
  static List<String> mirrorFamilies(
      SourceMetadataScannerState.SourceFile file,
      JavacSourceSymbolIndex.AnnotationInfo mirror,
      boolean guarded,
      SourceMetadataScannerState.ParsedAnnotation occurrence) {
    String args = JavacFrameworkAnnotations.renderedArguments(mirror);
    String name = JavacFrameworkAnnotations.simpleName(mirror);
    if (guarded
        && SourceMetadataScannerState.SPRING_BEAN_ANNOTATION_TYPES.containsKey(name)) {
      String canonical = occurrenceCanonicalFqn(file, occurrence, name);
      if (canonical == null) {
        // The lookalike shape: this occurrence does not resolve to a
        // canonical Spring type, exactly the textual per-occurrence
        // guard.
        return List.of();
      }
      if (!canonical.equals(mirror.canonicalName())) {
        return null;
      }
    }
    if (name.equals("Resource")) {
      return SourceMetadataScanner.resourceQualifier(args);
    }
    if (SourceMetadataScanner.isBeanNameAnnotation(name)) {
      return SourceMetadataScanner.beanNameQualifier(args);
    }
    if (SourceMetadataScannerState.NON_QUALIFIER_ANNOTATIONS.contains(name)) {
      return List.of();
    }
    if (name.equals("Qualifier") || name.equals("Named")) {
      return List.of(name + ":" + SourceMetadataScanner.quotedValue(
          args.replaceFirst("^value\\s*=\\s*", "").trim()));
    }
    return List.of(name);
  }

  /**
   * Resolves the canonical Spring FQN one occurrence denotes, mirroring
   * {@code canonicalAnnotationOccurrences}: the qualified spelling when it
   * is itself canonical, else the canonical import matched through
   * {@code importedTypes}; null when the occurrence is a lookalike.
   */
  private static String occurrenceCanonicalFqn(
      SourceMetadataScannerState.SourceFile file,
      SourceMetadataScannerState.ParsedAnnotation occurrence,
      String name) {
    if (occurrence == null) {
      return null;
    }
    List<String> canonical =
        SourceMetadataScannerState.SPRING_BEAN_ANNOTATION_TYPES.get(name);
    if (canonical == null) {
      return null;
    }
    String type = SourceMetadataScanner.annotationType(occurrence.source());
    if (canonical.contains(type)) {
      return type;
    }
    if (type.equals(name)) {
      return SourceMetadataScanner.importedTypes(name, file).stream()
          .filter(canonical::contains)
          .findFirst()
          .orElse(null);
    }
    return null;
  }
}
