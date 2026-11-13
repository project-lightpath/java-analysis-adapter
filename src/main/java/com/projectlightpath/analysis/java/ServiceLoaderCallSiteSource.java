package com.projectlightpath.analysis.java;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Source-position and lexical checks used to correlate ServiceLoader call sites. */
final class ServiceLoaderCallSiteSource {
  private ServiceLoaderCallSiteSource() {}

  /**
   * Correlates a line-only bytecode call with the ServiceLoader expression that can own it.
   *
   * <p>SootUp's bytecode positions do not include a source column. A type match alone therefore
   * cannot distinguish {@code consume(ServiceLoader.load(S.class), provider.run())} from a
   * stream/lambda expression rooted at that load. Rejecting a sibling expression is conservative
   * and prevents a definite provider fact from being manufactured from an unrelated call.
   */
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
    return topLevelDelimiter(sourceText, closing + 1, targetOffset, ',') < 0;
  }

  static int methodStart(String sourceText, String methodSource, int edgeOffset) {
    if (methodSource.isBlank()) {
      return -1;
    }
    int start = sourceText.indexOf(methodSource);
    int selected = -1;
    int matches = 0;
    while (start >= 0) {
      if (edgeOffset < 0
          || (edgeOffset >= start && edgeOffset < start + methodSource.length())) {
        matches++;
        selected = start;
      }
      start = sourceText.indexOf(methodSource, start + 1);
    }
    return matches == 1 ? selected : -1;
  }

  static int lineStartOffset(String sourceText, int line) {
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

  static boolean sameStatement(
      String sourceText, ServiceLoaderCallSite site, int line) {
    int edgeStart = lineStartOffset(sourceText, line);
    if (edgeStart < 0 || site.line() > line) {
      return false;
    }
    if (site.line() == line) {
      int lineEnd = sourceText.indexOf('\n', site.offset());
      String remainder = sourceText.substring(
          site.offset(), lineEnd < 0 ? sourceText.length() : lineEnd);
      int terminator = remainder.indexOf(';');
      return terminator < 0 || remainder.substring(terminator + 1).isBlank();
    }
    String between = sourceText.substring(site.offset(), edgeStart);
    return between.indexOf(';') < 0
        && between.indexOf('{') < 0
        && between.indexOf('}') < 0;
  }

  private static int statementEnd(String sourceText, int start) {
    int terminator = topLevelDelimiter(sourceText, start, sourceText.length(), ';');
    return terminator < 0 ? sourceText.length() : terminator + 1;
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
