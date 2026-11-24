package com.projectlightpath.analysis.java;

import java.util.Optional;
import java.util.StringJoiner;

/**
 * Canonical→binary name derivation for the TEXTUAL JVM-descriptor dialect
 * (issue #60): both dialects render binary names, and the textual path has
 * no javac mirror, so the binary spelling is derived from the resolved
 * canonical name through three rules, most definite first:
 *
 * <ol>
 * <li>Index lookup (flag-on, DEFINITE): the javac index carries
 *     {@code Elements.getBinaryName} for every indexed type; a canonical
 *     naming exactly one unflagged entry maps to its binary name.
 * <li>File-set top-level (DEFINITE): a canonical equal to one scanned
 *     file's top-level {@code typeName()} is a top-level type — binary
 *     equals canonical (this also preserves {@code $}-in-simple-name
 *     top-level types such as {@code demo.Cash$Flow}).
 * <li>Segment boundary rule (convention-grounded, fail-closed guarded):
 *     the first uppercase-initial segment starts the type chain; the
 *     package prefix keeps dots, the chain joins with {@code '$'}. A
 *     {@code '$'} anywhere in the canonical means a {@code $}-named simple
 *     identifier (source references to nested types use dots), so the
 *     spelling passes through unchanged. A boundary at segment 0 (default
 *     package or an UPPERCASE PACKAGE — textually indistinguishable) and
 *     empty segments abstain.
 * </ol>
 */
final class JvmBinaryNames {
  private JvmBinaryNames() {}

  /** Returns the binary name of one resolved canonical type spelling. */
  static Optional<String> binaryName(SourceMetadataScanner scanner, String canonical) {
    String normalized = canonical == null ? "" : canonical.trim();
    if (normalized.isEmpty()) {
      return Optional.empty();
    }
    Optional<String> fromIndex = fromIndex(scanner, normalized);
    if (fromIndex.isPresent()) {
      return fromIndex;
    }
    if (isFileSetTopLevel(scanner, normalized)) {
      return Optional.of(normalized);
    }
    return segmentRule(normalized);
  }

  private static Optional<String> fromIndex(
      SourceMetadataScanner scanner, String canonical) {
    return JavacSourceSymbolIndex.of(scanner.context)
        .flatMap(index -> index.type(canonical).stream()
            .filter(entry -> !entry.shape().duplicateBinaryName())
            .limit(2)
            .reduce((left, right) -> null))
        .map(JavacSourceSymbolIndex.TypeInfo::binaryName);
  }

  private static boolean isFileSetTopLevel(
      SourceMetadataScanner scanner, String canonical) {
    return scanner.files().stream()
        .map(SourceMetadataScannerState.SourceFile::typeName)
        .filter(canonical::equals)
        .count() == 1;
  }

  private static Optional<String> segmentRule(String canonical) {
    String[] segments = canonical.split("\\.", -1);
    int boundary = -1;
    for (int index = 0; index < segments.length; index++) {
      if (segments[index].isEmpty()) {
        return Optional.empty();
      }
      if (segments[index].indexOf('$') >= 0) {
        return Optional.of(canonical);
      }
      if (boundary < 0 && startsUppercase(segments[index])) {
        boundary = index;
      }
    }
    if (boundary < 0) {
      return Optional.of(canonical);
    }
    if (boundary == 0) {
      return Optional.empty();
    }
    return Optional.of(packagePrefix(segments, boundary)
        + "." + typeChain(segments, boundary));
  }

  private static boolean startsUppercase(String segment) {
    return !segment.isEmpty() && Character.isUpperCase(segment.charAt(0));
  }

  private static String packagePrefix(String[] segments, int boundary) {
    StringJoiner prefix = new StringJoiner(".");
    for (int index = 0; index < boundary; index++) {
      prefix.add(segments[index]);
    }
    return prefix.toString();
  }

  private static String typeChain(String[] segments, int boundary) {
    StringJoiner chain = new StringJoiner("$");
    for (int index = boundary; index < segments.length; index++) {
      chain.add(segments[index]);
    }
    return chain.toString();
  }
}
