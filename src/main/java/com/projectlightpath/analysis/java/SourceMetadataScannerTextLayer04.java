package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerTextLayer04 extends SourceMetadataScannerTextLayer03 {

  SourceMetadataScannerTextLayer04(AnalysisContext context) {
    super(context);
  }



static boolean keywordAt(String source, String keyword, int index) {
    return source.startsWith(keyword, index)
        && (index == 0 || !Character.isJavaIdentifierPart(source.charAt(index - 1)))
        && (index + keyword.length() == source.length()
            || !Character.isJavaIdentifierPart(source.charAt(index + keyword.length())));
  }

  /** Finds a word boundary keyword after a declaration-header offset. */
static int keywordIndexAfter(String source, String keyword, int offset) {
    Matcher matcher = Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b")
        .matcher(source);
    return matcher.find(offset) ? matcher.start() : -1;
  }

static boolean isBeanNameAnnotation(String name) {
    return Set.of(
        "Service", "Component", "Repository", "Controller", "RestController",
        "Configuration", "Bean").contains(name);
  }

  /** Extracts the first quoted annotation value or returns its trimmed expression. */
static String quotedValue(String expression) {
    if (expression == null || expression.isBlank()) {
      return "";
    }
    Matcher quoted = QUOTED.matcher(expression.trim());
    return quoted.find() ? quoted.group(1) : expression.trim();
  }

  /** Removes whitespace inside nested generic arguments without changing declarations. */
static String compactGenericWhitespace(String source) {
    StringBuilder result = new StringBuilder(source.length());
    int depth = 0;
    for (int index = 0; index < source.length(); index++) {
      char character = source.charAt(index);
      if (character == '<') {
        depth++;
        result.append(character);
      } else if (character == '>' && depth > 0) {
        depth--;
        result.append(character);
      } else if (Character.isWhitespace(character) && depth > 0) {
        continue;
      } else {
        result.append(character);
      }
    }
    return result.toString();
  }

  /** Splits comma-separated Java expressions while respecting nested delimiters. */
static List<String> splitArguments(String source) {
    List<String> result = new ArrayList<>();
    int depth = 0;
    int start = 0;
    boolean quoted = false;
    boolean escaped = false;
    for (int index = 0; index < source.length(); index++) {
      char character = source.charAt(index);
      if (character == '"' && !escaped) {
        quoted = !quoted;
      }
      if (quoted) {
        escaped = character == '\\' && !escaped;
        continue;
      }
      escaped = false;
      if (character == '<' || character == '{' || character == '(') {
        depth++;
      } else if (character == '>' || character == '}' || character == ')') {
        depth--;
      } else if (character == ',' && depth == 0) {
        result.add(source.substring(start, index));
        start = index + 1;
      }
    }
    result.add(source.substring(start));
    return result;
  }

  /** Returns canonical names for every supported Spring annotation family. */
static Set<String> canonicalSpringAnnotationTypes(String simpleName) {
    Set<String> result = new LinkedHashSet<>();
    result.addAll(SPRING_ROUTE_ANNOTATION_TYPES.getOrDefault(simpleName, List.of()));
    result.addAll(SPRING_PROXY_ANNOTATION_TYPES.getOrDefault(simpleName, List.of()));
    result.addAll(SPRING_BEAN_ANNOTATION_TYPES.getOrDefault(simpleName, List.of()));
    return Set.copyOf(result);
  }
}
