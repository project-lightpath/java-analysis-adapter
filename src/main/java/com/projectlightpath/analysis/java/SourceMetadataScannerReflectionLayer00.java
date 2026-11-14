package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerReflectionLayer00 extends SourceMetadataScannerTextSupport {

  SourceMetadataScannerReflectionLayer00(AnalysisContext context) {
    super(context);
  }



static void addChainedForNameReceivers(
      List<ChainedReceiver> result,
      String source,
      Pattern pattern,
      ChainedReceiverKind kind) {
    Matcher matcher = pattern.matcher(source);
    while (matcher.find()) {
      int opening = matcher.end() - 1;
      int closing = matchingDelimiter(source, opening, '(', ')');
      if (closing >= 0) {
        result.add(new ChainedReceiver(
            matcher.start(), closing + 1,
            source.substring(matcher.start(), closing + 1), kind));
      }
    }
  }

static void addChainedFieldReceivers(
      List<ChainedReceiver> result,
      String source,
      Pattern pattern,
      ChainedReceiverKind kind) {
    Matcher matcher = pattern.matcher(source);
    while (matcher.find()) {
      result.add(new ChainedReceiver(
          matcher.start(), matcher.end(), source.substring(matcher.start(), matcher.end()), kind));
    }
  }

static boolean tokenAt(String source, int start, String token) {
    int end = start + token.length();
    return start >= 0
        && end <= source.length()
        && source.startsWith(token, start)
        && (end == source.length() || !Character.isJavaIdentifierPart(source.charAt(end)));
  }

static String dynamicSymbol(String method, String type) {
    if (method.equals("loadClass")) {
      return "ClassLoader.loadClass";
    }
    if (method.equals("invoke")) {
      return "Method.invoke";
    }
    if (method.equals("newInstance")) {
      return "Constructor".equals(type) ? "Constructor.newInstance" : "Class.newInstance";
    }
    return method;
  }

static String lambdaParameterName(String parameter) {
    Matcher name = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*$").matcher(parameter.trim());
    return name.find() ? name.group(1) : "";
  }

static boolean lambdaBodyContains(String source, int lambdaEnd, int offset) {
    int bodyStart = lambdaEnd;
    while (bodyStart < source.length() && Character.isWhitespace(source.charAt(bodyStart))) {
      bodyStart++;
    }
    if (bodyStart >= source.length()) {
      return false;
    }
    if (source.charAt(bodyStart) == '{') {
      return matchingDelimiter(source, bodyStart, '{', '}') >= offset;
    }
    int boundary = source.indexOf(';', bodyStart);
    return offset >= bodyStart && (boundary < 0 || offset < boundary);
  }

static void addSite(List<DynamicSite> result, String source, int offset, String symbol) {
    if (result.stream().anyMatch(site -> site.offset() == offset && site.symbol().equals(symbol))) {
      return;
    }
    result.add(new DynamicSite(offset, symbol, dynamicTarget(source, offset, symbol)));
  }

static boolean isBareReceiver(String source, int receiverStart) {
    int previous = receiverStart - 1;
    while (previous >= 0 && Character.isWhitespace(source.charAt(previous))) {
      previous--;
    }
    return previous < 0 || source.charAt(previous) != '.';
  }

static String stripOuterParentheses(String expression) {
    String result = expression;
    while (result.startsWith("(")
        && matchingDelimiter(result, 0, '(', ')') == result.length() - 1) {
      result = result.substring(1, result.length() - 1).trim();
    }
    return result;
  }

static boolean hasParameter(MethodInfo owner, String variable) {
    return parameters(owner.parameters()).stream().anyMatch(parameter -> parameter.name().equals(variable));
  }

  boolean parameterMatchesType(
      SourceFile file, MethodInfo owner, String type, String variable, int offset) {
    return parameters(owner.parameters()).stream()
        .filter(parameter -> parameter.name().equals(variable))
        .map(Parameter::type)
        .map(parameterType -> stripBalancedGenericArguments(parameterType).trim())
        .anyMatch(parameterType -> isJdkDeclaredType(file, parameterType, offset, type));
  }

static Optional<LocalDeclaration> simpleLocalDeclaration(
      String source, MethodInfo owner, String variable, int offset) {
    Pattern declaration = Pattern.compile(
        "\\b([A-Za-z_$][\\w$.]*(?:\\s*<[^;=(){}]+>)?(?:\\s*\\[\\])*)\\s+"
            + Pattern.quote(variable)
            + "\\s*(?:=\\s*([^;{}]+))?(?=\\s*[;,:)])");
    Matcher matcher = declaration.matcher(source);
    matcher.region(owner.start(), Math.min(offset, source.length()));
    LocalDeclaration selected = null;
    while (matcher.find()) {
      String declaredType = matcher.group(1).trim();
      if (!isDeclarationKeyword(declaredType)
          && declarationIsActive(source, matcher.start(1), offset, owner.start())) {
        selected = new LocalDeclaration(
            declaredType,
            matcher.group(2) == null ? "" : matcher.group(2).trim(),
            matcher.start(1),
            matcher.start(2));
      }
    }
    return Optional.ofNullable(selected);
  }

static List<String> splitFieldDeclarators(String source) {
    return splitBalanced(source, ',', true);
  }
}
