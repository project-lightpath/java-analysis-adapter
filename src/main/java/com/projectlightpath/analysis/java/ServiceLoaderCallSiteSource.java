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
  private static final Pattern PARAMETER_NAME = Pattern.compile(
      "([A-Za-z_$][\\w$]*)\\s*$");
  private static final Pattern DIRECT_PROVIDER_CHAIN = Pattern.compile(
      "\\s*\\.\\s*findFirst\\s*\\(\\s*\\)\\s*\\.\\s*orElseThrow\\s*\\(\\s*\\)\\s*");

  private ServiceLoaderCallSiteSource() {}

  /** Correlates a line-only bytecode call with the ServiceLoader expression that can own it. */
  static boolean invokeIsLexicallyBound(
      String sourceText, ServiceLoaderCallSite site, String targetMethod) {
    return invokeIsLexicallyBound(sourceText, site, targetMethod, -1, null);
  }

  /** Correlates a target invocation using its source line when bytecode provides one. */
  static boolean invokeIsLexicallyBound(
      String sourceText, ServiceLoaderCallSite site, String targetMethod, int targetLine) {
    return invokeIsLexicallyBound(sourceText, site, targetMethod, targetLine, null);
  }

  /** Correlates a target invocation using the bytecode receiver when it is available. */
  static boolean invokeIsLexicallyBound(
      String sourceText,
      ServiceLoaderCallSite site,
      String targetMethod,
      int targetLine,
      String targetReceiver) {
    if (!validTargetMethod(targetMethod)) {
      return false;
    }
    String structuralSource = ServiceLoaderSourceMasker.mask(sourceText);
    SourceBounds bounds = loadBounds(structuralSource, site);
    if (bounds == null) {
      return false;
    }
    List<Integer> targetOffsets = targetOffsets(structuralSource, targetMethod, bounds);
    List<Integer> scopedTargets = targetsOnRequestedLine(
        structuralSource, targetOffsets, targetLine);
    if (targetReceiver == null && scopedTargets.size() > 1) {
      return false;
    }
    if (targetReceiver != null) {
      List<Integer> receiverMatches = scopedTargets.stream()
          .filter(offset -> targetReceiver.equals(receiverName(structuralSource, offset)))
          .toList();
      if (!receiverMatches.isEmpty()) {
        scopedTargets = receiverMatches;
      } else if (scopedTargets.size() > 1) {
        // Bytecode locals are not guaranteed to retain source parameter names. Keep
        // unique source targets eligible, but do not guess between same-line calls.
        return false;
      }
    }
    List<Integer> providerTargets = providerTargets(
        structuralSource, scopedTargets, bounds.expressionStart());
    return providerTargets.size() == 1
        && !hasExpressionBoundary(
            structuralSource, bounds.expressionStart(), providerTargets.get(0));
  }

  private static boolean validTargetMethod(String targetMethod) {
    return targetMethod != null && !targetMethod.isBlank() && !targetMethod.equals("<init>");
  }

  private static SourceBounds loadBounds(String sourceText, ServiceLoaderCallSite site) {
    int loadWord = sourceText.indexOf("load", site.offset());
    int opening = loadWord < 0 ? -1 : sourceText.indexOf('(', loadWord + "load".length());
    int closing = opening < 0 ? -1 : matchingDelimiter(sourceText, opening, '(', ')');
    int end = closing < 0 ? -1 : statementEnd(sourceText, site.offset());
    return end > closing ? new SourceBounds(closing + 1, end) : null;
  }

  private static List<Integer> targetOffsets(
      String sourceText, String targetMethod, SourceBounds bounds) {
    String methodPattern = "(?:\\.|::)\\s*"
        + Pattern.quote(targetMethod)
        + "(?=\\s*(?:\\(|[;,.)}]))";
    Matcher matcher = Pattern.compile(methodPattern).matcher(sourceText);
    matcher.region(bounds.expressionStart(), bounds.expressionEnd());
    List<Integer> offsets = new ArrayList<>();
    while (matcher.find()) {
      offsets.add(matcher.start());
    }
    return offsets;
  }

  private static List<Integer> targetsOnRequestedLine(
      String sourceText, List<Integer> offsets, int targetLine) {
    if (targetLine <= 0) {
      return offsets;
    }
    return offsets.stream()
        .filter(offset -> lineAt(sourceText, offset) == targetLine)
        .toList();
  }

  private static List<Integer> providerTargets(
      String sourceText, List<Integer> offsets, int expressionStart) {
    List<Integer> owned = new ArrayList<>();
    for (int targetOffset : offsets) {
      if (isProviderOwnedTarget(sourceText, expressionStart, targetOffset)) {
        owned.add(targetOffset);
      }
    }
    return owned;
  }

  private static boolean isProviderOwnedTarget(String sourceText, int start, int targetOffset) {
    if (sourceText.charAt(targetOffset) == ':') {
      // A method reference does not expose enough source information to prove
      // that its receiver is the ServiceLoader provider. Abstain rather than
      // attributing an unrelated reference to the provider edge.
      return false;
    }
    String receiver = receiverName(sourceText, targetOffset);
    if (receiver == null) {
      return isDirectProviderOwnedTarget(sourceText, start, targetOffset);
    }
    Matcher lambda = LAMBDA.matcher(sourceText);
    lambda.region(start, targetOffset);
    boolean priorLambda = false;
    while (lambda.find()) {
      String parameters = lambda.group(1) != null ? lambda.group(1) : lambda.group(2);
      if (lambdaContainsTarget(sourceText, lambda.end(), targetOffset)) {
        // The first lambda whose body contains the target is the outermost
        // lexical owner. Do not let an unrelated nested lambda lend its
        // parameter ownership to a ServiceLoader edge.
        return !priorLambda
            && containsParameter(parameters, receiver)
            && !parameterReassigned(sourceText, lambda.end(), targetOffset, receiver);
      }
      // Optional.filter returns the same provider value when its predicate
      // matches. Unlike map, it cannot replace the value flowing to the next
      // operation, so retain ServiceLoader ownership through that predicate.
      priorLambda = priorLambda || !isValuePreservingLambda(sourceText, lambda.start());
    }
    return false;
  }

  private static boolean isValuePreservingLambda(String sourceText, int lambdaStart) {
    int opening = lambdaStart - 1;
    while (opening >= 0 && Character.isWhitespace(sourceText.charAt(opening))) {
      opening--;
    }
    if (opening < 0 || sourceText.charAt(opening) != '(') {
      return false;
    }
    int end = opening - 1;
    while (end >= 0 && Character.isWhitespace(sourceText.charAt(end))) {
      end--;
    }
    int start = end;
    while (start >= 0 && Character.isJavaIdentifierPart(sourceText.charAt(start))) {
      start--;
    }
    return end >= start + 1
        && sourceText.substring(start + 1, end + 1).equals("filter");
  }

  private static boolean isDirectProviderOwnedTarget(
      String sourceText, int expressionStart, int targetOffset) {
    return DIRECT_PROVIDER_CHAIN.matcher(sourceText.substring(expressionStart, targetOffset))
        .matches();
  }

  private static boolean parameterReassigned(
      String sourceText, int lambdaEnd, int targetOffset, String receiver) {
    Matcher writes = parameterWritePattern(receiver).matcher(sourceText);
    writes.region(lambdaEnd, targetOffset);
    return writes.find();
  }

  private static Pattern parameterWritePattern(String receiver) {
    String name = Pattern.quote(receiver);
    return Pattern.compile(
        "(?<![A-Za-z0-9_$.])" + name
            + "\\s*(?:=(?!=)|\\+=|-=|\\*=|/=|%=|&=|\\|=|\\^=|<<=|>>=|>>>=|\\+\\+|--)"
            + "|(?:\\+\\+|--)\\s*" + name);
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
    int qualifier = start;
    while (qualifier >= 0 && Character.isWhitespace(sourceText.charAt(qualifier))) {
      qualifier--;
    }
    if (qualifier >= 0 && sourceText.charAt(qualifier) == '.') {
      return null;
    }
    return sourceText.substring(start + 1, end + 1);
  }

  private static boolean containsParameter(String parameters, String receiver) {
    if (parameters == null || parameters.isBlank()) {
      return false;
    }
    for (String parameter : parameters.split(",")) {
      Matcher name = PARAMETER_NAME.matcher(parameter.trim());
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
    String structuralSource = ServiceLoaderSourceMasker.mask(sourceText);
    int edgeStart = lineStartOffset(structuralSource, line);
    if (edgeStart < 0 || site.line() > line) {
      return false;
    }
    if (site.line() == line) {
      int lineEnd = structuralSource.indexOf('\n', site.offset());
      int end = lineEnd < 0 ? structuralSource.length() : lineEnd;
      int terminator = topLevelDelimiter(structuralSource, site.offset(), end, ';');
      return terminator < 0 || isClosingBlockSuffix(structuralSource, terminator + 1, end);
    }
    return !hasStatementBoundary(structuralSource, site.offset(), edgeStart);
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

  private record SourceBounds(int expressionStart, int expressionEnd) {}
}
