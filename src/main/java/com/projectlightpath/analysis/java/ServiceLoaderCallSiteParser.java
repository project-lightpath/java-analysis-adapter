package com.projectlightpath.analysis.java;

import java.util.Set;
import java.util.regex.Pattern;

/** Lexical checks used to correlate ServiceLoader call sites with bytecode calls. */
final class ServiceLoaderCallSiteParser {
  /**
   * Non-lambda chained calls that cannot replace the flowing provider value.
   * Enumerated from the green chained call-site tests: every Optional chain
   * uses findFirst; orElseThrow appears only on the receiver==null direct
   * path, which DIRECT_PROVIDER_CHAIN in ServiceLoaderCallSiteSource covers
   * without this whitelist (and a whitelisted orElseThrow before a lambda
   * call such as configure(q -> q.record()) fabricates attribution).
   */
  private static final Set<String> VALUE_PRESERVING_CALLS = Set.of("findFirst");

  /**
   * Chain operations that may receive the provider lambda directly. A lambda
   * inside any other call's arguments (for example build(...) nested in
   * equals(...)) is not supplied by the ServiceLoader chain.
   */
  private static final Set<String> CHAIN_LAMBDA_CONSUMERS = Set.of(
      "map", "flatMap", "filter", "ifPresent", "ifPresentOrElse", "forEach",
      "peek", "orElseGet", "orElseThrow", "anyMatch", "allMatch", "noneMatch",
      "collect", "reduce", "takeWhile", "dropWhile");

  private ServiceLoaderCallSiteParser() {}

  /**
   * Detects a non-lambda method call between the load expression and the owning
   * lambda that can replace the flowing provider value (for example
   * {@code .replacement()}). Calls whose arguments introduce a lambda are
   * governed by the lambda walk in ServiceLoaderCallSiteSource; only
   * value-preserving calls stay attributed.
   */
  static boolean hasInterveningReplacingCall(String sourceText, int start, int lambdaStart) {
    int depth = 0;
    for (int index = start; index < lambdaStart; index++) {
      char value = sourceText.charAt(index);
      if (value == '(') {
        depth++;
      } else if (value == ')') {
        depth--;
      } else if (depth == 0 && value == '.' && replacesFlowingValue(sourceText, index, lambdaStart)) {
        return true;
      }
    }
    return false;
  }

  private static boolean replacesFlowingValue(String sourceText, int dot, int lambdaStart) {
    int nameEnd = dot + 1;
    while (nameEnd < lambdaStart && Character.isJavaIdentifierPart(sourceText.charAt(nameEnd))) {
      nameEnd++;
    }
    int opening = nameEnd;
    while (opening < lambdaStart && Character.isWhitespace(sourceText.charAt(opening))) {
      opening++;
    }
    if (nameEnd == dot + 1 || opening == lambdaStart || sourceText.charAt(opening) != '(') {
      return false;
    }
    String name = sourceText.substring(dot + 1, nameEnd);
    int closing = matchingParenthesis(sourceText, opening);
    int argumentEnd = closing > opening ? closing : lambdaStart;
    boolean lambdaArgument = sourceText.substring(opening, argumentEnd).contains("->");
    return !(lambdaArgument && CHAIN_LAMBDA_CONSUMERS.contains(name))
        && !VALUE_PRESERVING_CALLS.contains(name);
  }

  /** A type declaration heading a brace: annotations, modifiers, keyword. */
  private static final Pattern TYPE_DECLARATION_HEADER = Pattern.compile(
      "^\\s*(?:@[\\w$.]+(?:\\([^{}]*\\))?\\s+)*"
          + "(?:(?:public|private|protected|static|final|abstract|sealed|non-sealed|strictfp)\\s+)*"
          + "(?:class|interface|enum|record)\\b");
  /** An anonymous-class constructor invocation heading a brace. */
  private static final Pattern ANONYMOUS_CLASS_PREFIX = Pattern.compile(
      "\\bnew\\s+[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}$.]*(?:<[^()]*>)?\\s*$");

  /**
   * True when the target sits inside a type body (local, anonymous, or nested
   * declaration) that opens within the owning lambda: the inner same-named
   * parameter is a different variable, so the lambda's ownership must not
   * reach it. Covers both declaration-keyword bodies and anonymous-class
   * bodies (which have no declaration keyword).
   */
  static boolean insideTypeBodyDeclaredInLambda(
      String sourceText, int lambdaEnd, int targetOffset) {
    for (int index = lambdaEnd; index < targetOffset; index++) {
      if (sourceText.charAt(index) != '{' || !isTypeBodyOpening(sourceText, index)) {
        continue;
      }
      int closing = JavaSourceText.matchingDelimiter(sourceText, index, '{', '}');
      if (closing < 0 || closing >= targetOffset) {
        return true;
      }
    }
    return false;
  }

  private static boolean isTypeBodyOpening(String sourceText, int brace) {
    int boundary = Math.max(sourceText.lastIndexOf(';', brace - 1),
        Math.max(sourceText.lastIndexOf('{', brace - 1), sourceText.lastIndexOf('}', brace - 1)));
    String header = sourceText.substring(boundary + 1, brace).trim();
    if (TYPE_DECLARATION_HEADER.matcher(header).find()) {
      return true;
    }
    return isAnonymousClassHeader(header);
  }

  private static boolean isAnonymousClassHeader(String header) {
    if (!header.endsWith(")")) {
      return false;
    }
    int open = matchingParenthesisBackward(header, header.length() - 1);
    return open > 0 && ANONYMOUS_CLASS_PREFIX.matcher(header.substring(0, open).trim()).find();
  }

  private static int matchingParenthesis(String sourceText, int opening) {
    int depth = 0;
    for (int index = opening; index < sourceText.length(); index++) {
      char current = sourceText.charAt(index);
      if (current == '(') {
        depth++;
      } else if (current == ')' && --depth == 0) {
        return index;
      }
    }
    return -1;
  }

  private static int matchingParenthesisBackward(String sourceText, int closing) {
    int depth = 0;
    for (int index = closing; index >= 0; index--) {
      char value = sourceText.charAt(index);
      if (value == ')') {
        depth++;
      } else if (value == '(' && --depth == 0) {
        return index;
      }
    }
    return -1;
  }
}
