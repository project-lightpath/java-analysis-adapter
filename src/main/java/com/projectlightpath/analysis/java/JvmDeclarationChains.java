package com.projectlightpath.analysis.java;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The file-set declaration-chain rule of the canonical→binary derivation
 * (issue #60): a canonical equal to a scanned file's top-level declaration
 * name is top-level (binary == canonical); a canonical extending it is
 * proven only through a DIRECT-member declaration chain — every remaining
 * segment names a type declaration contained in the previous owner's body
 * with no intervening type declaration (containment alone would accept a
 * grandchild, and inherited member types declare nothing at all). Split
 * from {@code JvmBinaryNames} for PMD's GodClass threshold; behavior is
 * identical to the inlined form.
 */
final class JvmDeclarationChains {
  private JvmDeclarationChains() {}

  /**
   * The distinct binary spellings the file-set declarations prove for the
   * canonical: null when NO top-level declaration prefix matches (fall
   * through); otherwise the (possibly empty) list of proven binaries —
   * one is the answer, zero or more-than-one abstain.
   */
  static List<String> fileSetBinaries(
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
   * body: no third type declaration lies between them, and the candidate
   * sits at the owner's TYPE-BODY level — zero brace depth from the
   * owner's body opening. Containment alone would accept a grandchild
   * (Helper.Inner inside Child) or a LOCAL class in ANY braced context —
   * method body, static or instance initializer, lambda, anonymous class
   * body (whose real binary is {@code Owner$1Name}, not
   * {@code Owner$Name}) — for a canonical naming an inherited or other
   * member, fabricating a binary name that does not exist.
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
    return atTypeBodyLevel(file, owner, candidate);
  }

  /**
   * Whether the candidate sits at the owner's type-body level: the brace
   * depth between the owner's body opening and the candidate start is
   * zero, counted on the comment- and literal-masked source so braces in
   * comments or strings cannot skew it. Every braced context — method
   * and constructor bodies, static and instance initializers, lambda and
   * anonymous class bodies — raises the depth, so a local class in any
   * of them is never a direct member.
   */
  private static boolean atTypeBodyLevel(
      SourceMetadataScannerState.SourceFile file,
      SourceMetadataScannerState.TypeInfo owner,
      SourceMetadataScannerState.TypeInfo candidate) {
    String masked = SourceMetadataScanner.maskStringLiterals(
        SourceMetadataScanner.stripComments(file.source()));
    int bodyOpening = JavaSourceText.typeBodyOpening(masked, owner.start());
    if (bodyOpening < 0 || bodyOpening >= candidate.start()) {
      return false;
    }
    int depth = 0;
    for (int index = bodyOpening + 1; index < candidate.start(); index++) {
      char character = masked.charAt(index);
      if (character == '{') {
        depth++;
      } else if (character == '}') {
        depth--;
      }
    }
    return depth == 0;
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

  private static String qualified(String packageName, String name) {
    return packageName == null || packageName.isEmpty()
        ? name : packageName + "." + name;
  }
}
