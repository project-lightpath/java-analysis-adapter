package com.projectlightpath.analysis.java;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * <li>Type-qualified spelling (DEFINITE for the qualifier): when the
 *     ORIGINAL spelling is {@code Qualifier.Member...} with a simple
 *     first segment that resolves to a type in the referencing file's
 *     scope, the source itself asserts the qualifier is a type — the
 *     boundary is proven there and the tail joins with {@code '$'}.
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
      if (eligible.size() == 1) {
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
    List<String> declared = fileSetBinaries(scanner, normalized, segments);
    if (declared != null) {
      // A top-level declaration prefix matched: the tail is proven (one
      // binary), ambiguous (more than one), or NOT a direct member chain
      // (inherited or deeper nesting) — all resolved here, never by
      // falling through to a weaker rule once a file-set owner is known.
      return declared.size() == 1
          ? Optional.of(declared.get(0))
          : Optional.empty();
    }
    return spellingBinary(scanner, normalized, spelling, sourceFile)
        .or(() -> dollarGuard(normalized));
  }

  /**
   * The distinct binary spellings the file-set declarations prove for the
   * canonical: null when NO top-level declaration prefix matches (fall
   * through); otherwise the (possibly empty) list of proven binaries —
   * one is the answer, zero or more-than-one abstain.
   */
  private static List<String> fileSetBinaries(
      SourceMetadataScanner scanner, String canonical, List<String> segments) {
    Set<String> results = new LinkedHashSet<>();
    boolean prefixMatched = false;
    for (SourceMetadataScannerState.SourceFile file : scanner.files()) {
      for (SourceMetadataScannerState.TypeInfo top : topLevelDeclarations(file)) {
        String topName = qualified(file.packageName(), top.name());
        if (canonical.equals(topName)) {
          prefixMatched = true;
          results.add(topName);
        } else if (canonical.startsWith(topName + ".")) {
          prefixMatched = true;
          collectChain(file, top, segments, topName.split("\\.", -1).length,
              topName, results);
        }
      }
    }
    return prefixMatched ? List.copyOf(results) : null;
  }

  /** Enumerates every DIRECT-member declaration chain below one top-level declaration. */
  private static void collectChain(
      SourceMetadataScannerState.SourceFile file,
      SourceMetadataScannerState.TypeInfo owner,
      List<String> segments,
      int next,
      String binarySoFar,
      Set<String> results) {
    if (next == segments.size()) {
      results.add(binarySoFar);
      return;
    }
    for (SourceMetadataScannerState.TypeInfo candidate
        : SourceMetadataScanner.typeDeclarations(file)) {
      if (candidate.name().equals(segments.get(next))
          && candidate.start() > owner.start()
          && candidate.end() < owner.end()
          && isDirectMember(file, owner, candidate)) {
        collectChain(file, candidate, segments, next + 1,
            binarySoFar + "$" + segments.get(next), results);
      }
    }
  }

  /**
   * Whether {@code candidate} is declared DIRECTLY inside {@code owner}'s
   * body: no third type declaration lies between them. Containment alone
   * would accept a grandchild (Helper.Inner inside Child) for a canonical
   * naming an inherited or other member — fabricating a binary name that
   * does not exist.
   */
  private static boolean isDirectMember(
      SourceMetadataScannerState.SourceFile file,
      SourceMetadataScannerState.TypeInfo owner,
      SourceMetadataScannerState.TypeInfo candidate) {
    for (SourceMetadataScannerState.TypeInfo other
        : SourceMetadataScanner.typeDeclarations(file)) {
      if (other != owner && other != candidate
          && other.start() < candidate.start()
          && other.end() > candidate.end()
          && other.start() > owner.start()
          && other.end() < owner.end()) {
        return false;
      }
    }
    return true;
  }

  private static List<SourceMetadataScannerState.TypeInfo> topLevelDeclarations(
      SourceMetadataScannerState.SourceFile file) {
    List<SourceMetadataScannerState.TypeInfo> all =
        SourceMetadataScanner.typeDeclarations(file);
    return all.stream()
        .filter(candidate -> all.stream().noneMatch(other ->
            other != candidate
                && other.start() < candidate.start()
                && other.end() > candidate.end()))
        .toList();
  }

  /**
   * The type-qualified spelling rule: a spelling like {@code Map.Entry}
   * (simple first segment, resolvable in the referencing file's scope)
   * asserts the qualifier is a TYPE — the boundary is proven there and
   * the remaining segments join the binary chain. Simple and
   * fully-qualified spellings assert nothing and return empty.
   */
  private static Optional<String> spellingBinary(
      SourceMetadataScanner scanner,
      String canonical,
      String spelling,
      SourceMetadataScannerState.SourceFile sourceFile) {
    if (spelling == null || sourceFile == null) {
      return Optional.empty();
    }
    String trimmed = spelling.trim();
    int dot = trimmed.indexOf('.');
    if (dot <= 0 || !SIMPLE_NAME.matcher(trimmed.substring(0, dot)).matches()) {
      return Optional.empty();
    }
    String qualifierSpelling = trimmed.substring(0, dot);
    Optional<String> qualifier =
        scanner.resolveReferenceType(qualifierSpelling, sourceFile);
    if (qualifier.isEmpty() || !canonical.startsWith(qualifier.get() + ".")) {
      return Optional.empty();
    }
    String tail = canonical.substring(qualifier.get().length() + 1);
    if (!trimmed.substring(dot + 1).equals(tail)) {
      return Optional.empty();
    }
    return binaryName(scanner, qualifier.get(), qualifier.get(), sourceFile)
        .map(prefix -> prefix + "$" + tail.replace('.', '$'));
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

  private static String qualified(String packageName, String name) {
    return packageName == null || packageName.isEmpty()
        ? name : packageName + "." + name;
  }
}
