package com.projectlightpath.analysis.java;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Canonical→binary name derivation for the TEXTUAL JVM-descriptor dialect
 * (issue #60): both dialects render binary names, and the textual path has
 * no javac mirror, so a {@code '$'} boundary is inserted only where
 * EVIDENCE proves it — capitalization is never evidence. Most definite
 * first:
 *
 * <ol>
 * <li>Index lookup (flag-on, DEFINITE): the javac index carries
 *     {@code Elements.getBinaryName} for every indexed type; a canonical
 *     naming exactly one unflagged entry maps to its binary name. An
 *     AMBIGUOUS lookup (two or more unflagged entries sharing the
 *     canonical, e.g. a nested {@code p.Outer.Inner} next to a top-level
 *     type in a package literally named {@code p.Outer}) abstains — never
 *     throws, never picks a side.
 * <li>File-set declarations (DEFINITE): a canonical equal to a scanned
 *     file's top-level declaration name is top-level (binary ==
 *     canonical); a canonical extending it resolves through the nested
 *     declaration chain (every remaining segment names a type declaration
 *     contained in the previous one's body, enumerated exhaustively).
 *     Zero proven chains falls through; more than one DISTINCT binary
 *     abstains.
 * <li>Type-qualified spelling (ABSTAINS): when the ORIGINAL spelling is
 *     {@code Qualifier.Member...} with a simple first segment that
 *     resolves to a type in the referencing file's scope, the spelling
 *     asserts a member of that qualifier — but this point is only
 *     reached when no file-set declaration prefix matched, so the
 *     qualifier is outside the file set and nothing proves whether it
 *     DECLARES the member or INHERITS it (different binary owners). The
 *     derivation abstains rather than append an unproven tail.
 * <li>Otherwise the JLS-default package reading keeps binary ==
 *     canonical (the pre-#60 rendering for these shapes), guarded by two
 *     abstentions: a {@code '$'} in a segment BEFORE the last is
 *     ambiguous (a {@code $}-named simple identifier with further nesting
 *     vs a package segment) and abstains; empty segments abstain.
 * </ol>
 */
final class JvmBinaryNames {
  private static final Pattern SIMPLE_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

  private JvmBinaryNames() {}

  /** Returns the binary name of one resolved canonical type spelling. */
  static Optional<String> binaryName(
      SourceMetadataScanner scanner,
      String canonical,
      String spelling,
      SourceMetadataScannerState.SourceFile sourceFile) {
    String normalized = canonical == null ? "" : canonical.trim();
    if (normalized.isEmpty()) {
      return Optional.empty();
    }
    List<String> segments = List.of(normalized.split("\\.", -1));
    if (segments.stream().anyMatch(String::isEmpty)) {
      return Optional.empty();
    }
    Optional<JavacSourceSymbolIndex> index = JavacSourceSymbolIndex.of(scanner.context);
    if (index.isPresent()) {
      List<JavacSourceSymbolIndex.TypeInfo> indexed = index.get().type(normalized);
      List<JavacSourceSymbolIndex.TypeInfo> eligible = indexed.stream()
          .filter(entry -> !entry.shape().duplicateBinaryName())
          .toList();
      if (eligible.size() == 1 && eligible.size() == indexed.size()) {
        // A single eligible entry is accepted only when it is the ONLY
        // indexed match: a flagged sibling is a competing declaration for
        // the same canonical name, so its presence makes the binary
        // unproven.
        return Optional.of(eligible.get(0).binaryName());
      }
      if (!indexed.isEmpty()) {
        // Any index match without exactly one eligible entry — ambiguous
        // matches, and matches whose entries are all duplicate-flagged —
        // abstains: falling through to a weaker rule could return a
        // binary name the index itself rejected.
        return Optional.empty();
      }
    }
    List<String> declared = JvmDeclarationChains.fileSetBinaries(scanner, normalized, segments);
    if (declared != null) {
      // A top-level declaration prefix matched: the tail is proven (one
      // binary), ambiguous (more than one), or NOT a direct member chain
      // (inherited or deeper nesting) — all resolved here, never by
      // falling through to a weaker rule once a file-set owner is known.
      return declared.size() == 1
          ? Optional.of(declared.get(0))
          : Optional.empty();
    }
    if (isTypeQualifiedSpelling(scanner, normalized, spelling, sourceFile)) {
      // A type-qualified spelling asserts a MEMBER of its qualifier. This
      // point is only reached when no file-set declaration prefix
      // matched the canonical, so the qualifier is outside the file set
      // (or java.lang): nothing proves whether it DECLARES the member or
      // INHERITS it, and the two readings carry different binary owners.
      // Abstain — never append an unproven tail, and never fall through
      // to the package reading, which would contradict the spelling's
      // own member assertion.
      return Optional.empty();
    }
    return dollarGuard(normalized);
  }

  /**
   * Whether the spelling is type-qualified with a resolvable qualifier:
   * {@code Qualifier.Member...} with a simple first segment that resolves
   * to a type in the referencing file's scope, a canonical extending that
   * qualifier, and a matching tail. Such a spelling asserts a member
   * relation; simple and fully-qualified spellings assert nothing.
   */
  private static boolean isTypeQualifiedSpelling(
      SourceMetadataScanner scanner,
      String canonical,
      String spelling,
      SourceMetadataScannerState.SourceFile sourceFile) {
    if (spelling == null || sourceFile == null) {
      return false;
    }
    String trimmed = spelling.trim();
    int dot = trimmed.indexOf('.');
    if (dot <= 0 || !SIMPLE_NAME.matcher(trimmed.substring(0, dot)).matches()) {
      return false;
    }
    Optional<String> qualifier =
        scanner.resolveReferenceType(trimmed.substring(0, dot), sourceFile);
    if (qualifier.isEmpty() || !canonical.startsWith(qualifier.get() + ".")) {
      return false;
    }
    return trimmed.substring(dot + 1)
        .equals(canonical.substring(qualifier.get().length() + 1));
  }

  /**
   * The JLS-default package reading with the {@code '$'} ambiguity guard:
   * binary == canonical, unless a {@code '$'} appears in a segment before
   * the last (ambiguous between a {@code $}-named identifier with further
   * nesting and a package segment), which abstains.
   */
  private static Optional<String> dollarGuard(String canonical) {
    int lastDot = canonical.lastIndexOf('.');
    for (int index = canonical.indexOf('$'); index >= 0;
        index = canonical.indexOf('$', index + 1)) {
      if (lastDot < 0 || index < lastDot) {
        return Optional.empty();
      }
    }
    return Optional.of(canonical);
  }
}
