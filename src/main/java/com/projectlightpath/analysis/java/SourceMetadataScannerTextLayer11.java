package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerTextLayer11 extends SourceMetadataScannerTextLayer10 {

  SourceMetadataScannerTextLayer11(AnalysisContext context) {
    super(context);
  }



  /** Builds a stable overload discriminator without changing the public callee identity. */
static String methodDescriptor(MethodInfo method) {
    String parameters = method.parameters().trim();
    if (parameters.isEmpty()) {
      return "()";
    }
    return "(" + String.join(",", splitTopLevelArguments(parameters).stream()
        .map(SourceMetadataScanner::parameterType)
        .toList()) + ")";
  }

  /** Extracts constructor bodies so dynamic sites retain an explicit initializer caller. */
static List<MethodInfo> constructors(SourceFile file) {
    String maskedSource = maskAnnotationArguments(file.structuralSource());
    List<MethodInfo> result = new ArrayList<>();
    for (TypeInfo type : typeDeclarations(file)) {
      Pattern constructor = Pattern.compile(
          "(?m)(?:^|[;{}\\n])\\s*"
              + "(?:(?:@[A-Za-z_][\\w.]*(?:\\([^)]*\\))?\\s*)|"
              + "(?:(?:public|protected|private|static|final|synchronized|strictfp)\\s+))*"
              + Pattern.quote(type.name())
              + "\\s*\\(([^)]*)\\)\\s*(?:throws [^{;]+)?\\{");
      Matcher matcher = constructor.matcher(maskedSource);
      while (matcher.find()) {
        int start = matcher.start();
        String annotationBlock = file.source().substring(start, matcher.start(1));
        int annotationOffset = annotationBlock.indexOf('@');
        int lineOffset = annotationOffset < 0 ? matcher.start(1) : start + annotationOffset;
        result.add(new MethodInfo(
            "<init>",
            file.source().substring(matcher.start(1), matcher.end(1)),
            annotationBlock,
            start,
            matcher.end(),
            file.lineAt(lineOffset),
            ""));
      }
    }
    return result.stream()
        .distinct()
        .sorted(Comparator.comparingInt(MethodInfo::start))
        .toList();
  }

static boolean isInitializerBlockOpening(SourceFile file, String source, int opening) {
    for (TypeInfo type : typeDeclarations(file)) {
      int typeOpening = typeBodyOpening(source, type.start());
      if (typeOpening == opening) {
        return false;
      }
    }
    String header = memberHeader(source, opening);
    return header.isBlank() || header.matches("(?s)\\s*static\\s*");
  }

static TypeInfo innermostType(SourceFile file, int index) {
    return typeDeclarations(file).stream()
        .filter(type -> type.start() <= index && type.end() >= index)
        .max(Comparator.comparingInt(TypeInfo::start))
        .orElse(null);
  }

static TypeInfo enclosingType(SourceFile file, TypeInfo type) {
    return typeDeclarations(file).stream()
        .filter(candidate -> candidate.start() < type.start() && candidate.end() >= type.start())
        .max(Comparator.comparingInt(TypeInfo::start))
        .orElse(null);
  }

static int skipTypeUseAnnotations(String source, int position) {
    while (position < source.length() && source.charAt(position) == '@') {
      int nameEnd = qualifiedIdentifierEnd(source, skipWhitespace(source, position + 1));
      if (nameEnd < 0) {
        return -1;
      }
      position = skipWhitespace(source, nameEnd);
      if (position < source.length() && source.charAt(position) == '(') {
        int closing = matchingDelimiter(source, position, '(', ')');
        if (closing < 0) {
          return -1;
        }
        position = skipWhitespace(source, closing + 1);
      }
    }
    return position;
  }

  /** Adds source annotation spellings to evidence symbols. */
static void addSymbols(List<String> symbols, String block, String... names) {
    for (String name : names) {
      for (AnnotationOccurrence occurrence : annotationOccurrences(block, name)) {
        symbols.add(occurrence.source());
      }
    }
  }

  /** Returns the annotation-bearing declaration prefix for one nested source type. */
static String typeAnnotationBlock(SourceFile file, int typeStart) {
    String maskedSource = maskAnnotationArguments(file.structuralSource());
    int boundary = typeStart - 1;
    while (boundary >= 0 && "{};".indexOf(maskedSource.charAt(boundary)) < 0) {
      boundary--;
    }
    return file.source().substring(boundary + 1, typeStart);
  }

  /** Extracts supported conditional registration annotations. */
static List<String> conditionAnnotations(String block) {
    List<String> result = new ArrayList<>();
    for (String name : List.of(
        "Conditional", "ConditionalOnBean", "ConditionalOnMissingBean", "ConditionalOnClass",
        "ConditionalOnMissingClass", "ConditionalOnProperty", "ConditionalOnExpression")) {
      for (AnnotationOccurrence occurrence : annotationOccurrences(block, name)) {
        result.add(occurrence.source());
      }
    }
    return List.copyOf(result);
  }

static List<String> resourceQualifier(String arguments) {
    String value = attributeValue(arguments, "name");
    if (value == null) {
      value = attributeValue(arguments, "value");
    }
    String quoted = quotedValue(value);
    return quoted.isBlank() ? List.of() : List.of("Resource:" + quoted);
  }

static List<String> beanNameQualifier(String arguments) {
    String value = attributeValue(arguments, "value");
    if (value == null) {
      value = attributeValue(arguments, "name");
    }
    String quoted = quotedValue(value);
    return quoted.isBlank() ? List.of() : List.of("BeanName:" + quoted);
  }
}
