package com.projectlightpath.analysis.java;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the receiver expression of one reflection lookup site (issue #25):
 * the method-name start, the backward-scanned receiver range, and the
 * owner NAME that range provably asserts — a class-literal spelling
 * (qualified directly, simple through the textual resolver) or the literal
 * of a static {@code Class.forName} invocation read from the structural
 * source through the offset-preserving literal mask. Any other shape
 * yields nothing: the site stays on the runtime-string path.
 */
final class JavacReflectionReceivers {

  /** Statement keywords that may precede a receiver chain without owning it. */
  private static final Set<String> KEYWORDS = Set.of(
      "return", "throw", "assert", "case", "yield", "else", "do", "new",
      "if", "while", "for", "switch", "try", "catch", "finally", "synchronized");

  /** Expression delimiters that may terminate the prefix before a chain. */
  private static final String BOUNDARY_CHARS = "=(),;{}&|?:!+-*/<>";

  /** A dotted identifier chain ending at a class literal, anchored at the range end. */
  private static final Pattern CLASS_LITERAL_CHAIN = Pattern.compile(
      "([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*\\.\\s*class\\s*$");

  /** A static Class.forName invocation (masked literal) anchored at the range end. */
  private static final Pattern FOR_NAME_CHAIN = Pattern.compile(
      "([A-Za-z_$][\\w$.]*)\\s*\\.\\s*forName\\s*\\(\\s*\"[^\"]*\"\\s*\\)\\s*$");

  private final SourceMetadataScanner scanner;

  JavacReflectionReceivers(SourceMetadataScanner scanner) {
    this.scanner = scanner;
  }

  /** The method-name start at or after the site offset (both site shapes). */
  int methodNameStart(String masked, String symbol, int siteOffset) {
    Matcher matcher = Pattern.compile("\\b" + Pattern.quote(symbol) + "\\s*\\(")
        .matcher(masked);
    matcher.region(Math.min(siteOffset, masked.length()), masked.length());
    return matcher.find() ? matcher.start() : -1;
  }

  /** Scans back to the previous statement delimiter; start-of-file otherwise. */
  static int rangeStart(String masked, int methodNameStart) {
    for (int index = methodNameStart - 1; index >= 0; index--) {
      char character = masked.charAt(index);
      if (character == ';' || character == '{' || character == '}') {
        return index + 1;
      }
    }
    return 0;
  }

  /**
   * The receiver range before one lookup's method name, trimmed; the range
   * retains the chain's connecting dot, which no receiver expression ends
   * with, so trailing dots are stripped.
   */
  static String receiverRange(String masked, int methodNameStart) {
    return masked.substring(rangeStart(masked, methodNameStart), methodNameStart)
        .trim().replaceAll("[.\\s]+$", "");
  }

  /**
   * The owner NAME the receiver provably asserts: a class-literal spelling
   * (qualified used directly, simple normalized by the textual resolver —
   * the index confirms it later, so an unconfirmed candidate is never
   * published), or the literal of a static {@code Class.forName} /
   * {@code java.lang.Class.forName} invocation. Anything else — simple
   * variables, {@code getClass()}, parenthesized or wrapped receivers —
   * yields nothing (dataflow out of scope, fail closed).
   */
  java.util.Optional<String> ownerName(
      SourceMetadataScannerState.SourceFile file,
      String structural,
      String range,
      int rangeStart) {
    Matcher classLiteral = CLASS_LITERAL_CHAIN.matcher(range);
    if (classLiteral.find() && prefixAccepted(range, classLiteral.start(1))) {
      return classLiteralOwnerName(file, classLiteral.group(1), rangeStart);
    }
    Matcher forName = FOR_NAME_CHAIN.matcher(range);
    if (!forName.find() || !prefixAccepted(range, forName.start(1))) {
      return java.util.Optional.empty();
    }
    String chain = forName.group(1);
    // Only the static API spellings are accepted; an instance-qualified or
    // static-imported forName is not revalidated here and fails closed.
    if (!chain.equals("Class") && !chain.equals("java.lang.Class")) {
      return java.util.Optional.empty();
    }
    String literal = SourceMetadataScannerTextLayer10
        .firstQuotedArgument(structural, rangeStart + forName.start(1));
    return java.util.Optional.ofNullable(literal);
  }

  private java.util.Optional<String> classLiteralOwnerName(
      SourceMetadataScannerState.SourceFile file, String spelling, int offset) {
    if (spelling.contains(".")) {
      return java.util.Optional.of(spelling);
    }
    return scanner.resolveReferenceType(spelling, file, offset);
  }

  /**
   * Reports whether the text before a receiver chain is a boundary the
   * chain can follow: nothing, a statement keyword, or an expression
   * delimiter. Anything else (an identifier, a call argument) means the
   * chain is not the site's receiver — fail closed.
   */
  private static boolean prefixAccepted(String range, int chainStart) {
    String prefix = range.substring(0, chainStart).trim();
    if (prefix.isEmpty() || KEYWORDS.contains(prefix)) {
      return true;
    }
    char last = prefix.charAt(prefix.length() - 1);
    return BOUNDARY_CHARS.indexOf(last) >= 0;
  }
}
