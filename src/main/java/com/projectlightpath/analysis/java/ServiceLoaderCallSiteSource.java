package com.projectlightpath.analysis.java;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Source-position and lexical checks used to correlate ServiceLoader call sites. */
final class ServiceLoaderCallSiteSource {
  private static final String EXPRESSION_BOUNDARIES = ",;)]}?=+-*/%";

  private ServiceLoaderCallSiteSource() {}

  /** Correlates a line-only bytecode call with the ServiceLoader expression that can own it. */
  static boolean invokeIsLexicallyBound(
      String sourceText, ServiceLoaderCallSite site, String targetMethod) {
    if (targetMethod == null || targetMethod.isBlank() || targetMethod.equals("<init>")) {
      return false;
    }
    int loadWord = sourceText.indexOf("load", site.offset());
    if (loadWord < 0) {
      return false;
    }
    int opening = sourceText.indexOf('(', loadWord + "load".length());
    if (opening < 0) {
      return false;
    }
    int closing = matchingDelimiter(sourceText, opening, '(', ')');
    if (closing < 0) {
      return false;
    }
    int end = statementEnd(sourceText, site.offset());
    if (end <= closing) {
      return false;
    }
    String methodPattern = "(?:\\.|::)\\s*"
        + Pattern.quote(targetMethod)
        + "(?=\\s*(?:\\(|[;,.)}]))";
    Matcher matcher = Pattern.compile(methodPattern).matcher(sourceText);
    matcher.region(closing + 1, end);
    if (!matcher.find()) {
      return false;
    }
    int targetOffset = matcher.start();
    if (matcher.find()) {
      return false;
    }
    return !hasExpressionBoundary(sourceText, closing + 1, targetOffset);
  }

  /** Returns whether a source call and bytecode line belong to one expression statement. */
  static boolean sameStatement(
      String sourceText, ServiceLoaderCallSite site, int line) {
    int edgeStart = lineStartOffset(sourceText, line);
    if (edgeStart < 0 || site.line() > line) {
      return false;
    }
    if (site.line() == line) {
      int lineEnd = sourceText.indexOf('\n', site.offset());
      int end = lineEnd < 0 ? sourceText.length() : lineEnd;
      int terminator = topLevelDelimiter(sourceText, site.offset(), end, ';');
      return terminator < 0 || isClosingBlockSuffix(sourceText, terminator + 1, end);
    }
    return !hasStatementBoundary(sourceText, site.offset(), edgeStart);
  }

  private static boolean hasStatementBoundary(String sourceText, int start, int end) {
    DelimiterDepth depth = new DelimiterDepth();
    for (int index = start; index < end; index++) {
      char value = sourceText.charAt(index);
      if (depth.atTopLevel()
          && (value == ',' || value == ';' || value == ')' || value == ']'
              || value == '}' || value == '{')) {
        return true;
      }
      depth.accept(value);
    }
    return false;
  }

  private static boolean hasExpressionBoundary(String sourceText, int start, int end) {
    DelimiterDepth depth = new DelimiterDepth();
    for (int index = start; index < end; index++) {
      char value = sourceText.charAt(index);
      if (depth.atTopLevel() && isExpressionBoundary(sourceText, index, end, value)) {
        return true;
      }
      depth.accept(value);
    }
    return false;
  }

  private static boolean isExpressionBoundary(
      String sourceText, int index, int end, char value) {
    if (EXPRESSION_BOUNDARIES.indexOf(value) >= 0) {
      return true;
    }
    return (value == '&' || value == '|')
        && index + 1 < end && sourceText.charAt(index + 1) == value;
  }

  private static int statementEnd(String sourceText, int start) {
    int terminator = topLevelDelimiter(sourceText, start, sourceText.length(), ';');
    return terminator < 0 ? sourceText.length() : terminator + 1;
  }

  private static boolean isClosingBlockSuffix(String sourceText, int start, int end) {
    for (int index = start; index < end; index++) {
      char value = sourceText.charAt(index);
      if (!Character.isWhitespace(value) && value != '}') {
        return false;
      }
    }
    return true;
  }

  private static int matchingDelimiter(String sourceText, int opening, char open, char close) {
    int depth = 0;
    for (int index = opening; index < sourceText.length(); index++) {
      char value = sourceText.charAt(index);
      if (value == open) {
        depth++;
      } else if (value == close && --depth == 0) {
        return index;
      }
    }
    return -1;
  }

  private static int topLevelDelimiter(String sourceText, int start, int end, char delimiter) {
    DelimiterDepth depth = new DelimiterDepth();
    for (int index = start; index < end; index++) {
      char value = sourceText.charAt(index);
      if (value == delimiter && depth.atTopLevel()) {
        return index;
      }
      depth.accept(value);
    }
    return -1;
  }

  private static int lineStartOffset(String sourceText, int line) {
    if (line <= 0) {
      return -1;
    }
    int currentLine = 1;
    for (int index = 0; index < sourceText.length(); index++) {
      if (currentLine == line) {
        return index;
      }
      if (sourceText.charAt(index) == '\n') {
        currentLine++;
      }
    }
    return currentLine == line ? sourceText.length() : -1;
  }

  private static final class DelimiterDepth {
    private int parentheses;
    private int brackets;
    private int braces;

    boolean atTopLevel() {
      return parentheses == 0 && brackets == 0 && braces == 0;
    }

    void accept(char value) {
      switch (value) {
        case '(' -> parentheses++;
        case ')' -> parentheses = decrement(parentheses);
        case '[' -> brackets++;
        case ']' -> brackets = decrement(brackets);
        case '{' -> braces++;
        case '}' -> braces = decrement(braces);
        default -> { }
      }
    }

    private static int decrement(int depth) {
      return depth == 0 ? 0 : depth - 1;
    }
  }
}
