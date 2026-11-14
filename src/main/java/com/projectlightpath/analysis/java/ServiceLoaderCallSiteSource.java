package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Source-position and lexical checks used to correlate ServiceLoader call sites. */
final class ServiceLoaderCallSiteSource {
  private static final String EXPRESSION_BOUNDARIES = ",;)]}?=+-*/%";
  private static final Pattern LAMBDA = Pattern.compile(
      "\\(([^()]*)\\)\\s*->|([A-Za-z_$][\\w$]*)\\s*->");

  private ServiceLoaderCallSiteSource() {}

  /** Correlates a line-only bytecode call with the ServiceLoader expression that can own it. */
  static boolean invokeIsLexicallyBound(
      String sourceText, ServiceLoaderCallSite site, String targetMethod) {
    return invokeIsLexicallyBound(sourceText, site, targetMethod, -1);
  }

  /** Correlates a target invocation using its source line when bytecode provides one. */
  static boolean invokeIsLexicallyBound(
      String sourceText, ServiceLoaderCallSite site, String targetMethod, int targetLine) {
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
    List<Integer> providerTargets = new ArrayList<>();
    while (matcher.find()) {
      if (targetLine > 0 && lineAt(sourceText, matcher.start()) != targetLine) {
        continue;
      }
      if (isProviderOwnedTarget(sourceText, closing + 1, matcher.start())) {
        providerTargets.add(matcher.start());
      }
    }
    if (providerTargets.size() != 1) {
      return false;
    }
    int targetOffset = providerTargets.get(0);
    return !hasExpressionBoundary(sourceText, closing + 1, targetOffset);
  }

  private static boolean isProviderOwnedTarget(String sourceText, int start, int targetOffset) {
    if (sourceText.charAt(targetOffset) == ':') {
      return true;
    }
    String receiver = receiverName(sourceText, targetOffset);
    if (receiver == null) {
      return false;
    }
    Matcher lambda = LAMBDA.matcher(sourceText);
    lambda.region(start, targetOffset);
    while (lambda.find()) {
      String parameters = lambda.group(1) != null ? lambda.group(1) : lambda.group(2);
      if (containsParameter(parameters, receiver)
          && lambdaContainsTarget(sourceText, lambda.end(), targetOffset)) {
        return true;
      }
    }
    return false;
  }

  private static String receiverName(String sourceText, int targetOffset) {
    int end = targetOffset - 1;
    while (end >= 0 && Character.isWhitespace(sourceText.charAt(end))) {
      end--;
    }
    if (end < 0 || !Character.isJavaIdentifierPart(sourceText.charAt(end))) {
      return null;
    }
    int start = end;
    while (start >= 0 && Character.isJavaIdentifierPart(sourceText.charAt(start))) {
      start--;
    }
    if (start >= 0 && Character.isJavaIdentifierStart(sourceText.charAt(start))) {
      return sourceText.substring(start, end + 1);
    }
    return sourceText.substring(start + 1, end + 1);
  }

  private static boolean containsParameter(String parameters, String receiver) {
    if (parameters == null || parameters.isBlank()) {
      return false;
    }
    for (String parameter : parameters.split(",")) {
      Matcher name = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*$").matcher(parameter.trim());
      if (name.find() && name.group(1).equals(receiver)) {
        return true;
      }
    }
    return false;
  }

  private static boolean lambdaContainsTarget(String sourceText, int arrowEnd, int targetOffset) {
    int bodyStart = arrowEnd;
    while (bodyStart < sourceText.length()
        && Character.isWhitespace(sourceText.charAt(bodyStart))) {
      bodyStart++;
    }
    if (bodyStart > targetOffset) {
      return false;
    }
    if (bodyStart < sourceText.length() && sourceText.charAt(bodyStart) == '{') {
      int bodyEnd = matchingDelimiter(sourceText, bodyStart, '{', '}');
      return bodyEnd > targetOffset;
    }
    return expressionEnd(sourceText, bodyStart) > targetOffset;
  }

  private static int expressionEnd(String sourceText, int start) {
    DelimiterDepth depth = new DelimiterDepth();
    for (int index = start; index < sourceText.length(); index++) {
      char value = sourceText.charAt(index);
      if (depth.atTopLevel()
          && (value == ',' || value == ';' || value == ')' || value == ']'
              || value == '}')) {
        return index;
      }
      depth.accept(value);
    }
    return sourceText.length();
  }

  private static int lineAt(String sourceText, int offset) {
    int line = 1;
    for (int index = 0; index < offset && index < sourceText.length(); index++) {
      if (sourceText.charAt(index) == '\n') {
        line++;
      }
    }
    return line;
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
