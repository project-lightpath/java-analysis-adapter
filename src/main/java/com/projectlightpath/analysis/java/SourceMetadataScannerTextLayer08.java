package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerTextLayer08 extends SourceMetadataScannerTextLayer07 {

  SourceMetadataScannerTextLayer08(AnalysisContext context) {
    super(context);
  }



static int qualifiedIdentifierEnd(String source, int start) {
    int position = start;
    if (position >= source.length() || !Character.isJavaIdentifierStart(source.charAt(position))) {
      return -1;
    }
    position++;
    while (position < source.length() && Character.isJavaIdentifierPart(source.charAt(position))) {
      position++;
    }
    while (true) {
      int dot = skipWhitespace(source, position);
      if (dot >= source.length() || source.charAt(dot) != '.') {
        return position;
      }
      int segmentStart = skipWhitespace(source, dot + 1);
      if (segmentStart >= source.length()
          || !Character.isJavaIdentifierStart(source.charAt(segmentStart))) {
        return -1;
      }
      position = segmentStart + 1;
      while (position < source.length() && Character.isJavaIdentifierPart(source.charAt(position))) {
        position++;
      }
    }
  }

  /** Finds declared top-level and nested Java types with bounded body ranges. */
static List<TypeInfo> typeDeclarations(SourceFile file) {
    String source = maskStringLiterals(file.structuralSource());
    Matcher matcher = TYPE_DECLARATION.matcher(source);
    List<TypeInfo> result = new ArrayList<>();
    while (matcher.find()) {
      int opening = typeBodyOpening(source, matcher.end());
      if (opening < 0) {
        continue;
      }
      int closing = matchingDelimiter(source, opening, '{', '}');
      if (closing < 0) {
        continue;
      }
      result.add(new TypeInfo(matcher.group(1), matcher.group(2), matcher.start(), closing));
    }
    return List.copyOf(result);
  }

  /** Parses a single literal or literal array route expression. */
static Optional<List<String>> literalPathValues(String expression) {
    String selected = expression == null ? "" : expression.trim();
    if (selected.startsWith("{") && selected.endsWith("}")
        && matchingDelimiter(selected, 0, '{', '}') == selected.length() - 1) {
      String body = selected.substring(1, selected.length() - 1).trim();
      if (body.isEmpty()) {
        return Optional.of(List.of());
      }
      List<String> result = new ArrayList<>();
      for (String item : splitArguments(body)) {
        Optional<String> literal = literalString(item.trim());
        if (literal.isEmpty()) {
          return Optional.empty();
        }
        result.add(literal.get());
      }
      return Optional.of(List.copyOf(result));
    }
    Optional<String> literal = literalString(selected);
    return literal.map(List::of);
  }

  /** Finds a word boundary keyword in a declaration header. */
static int keywordIndex(String source, String keyword) {
    return keywordIndexAfter(source, keyword, 0);
  }

static int topLevelKeywordIndex(String source, String keyword) {
    HeaderDepth depth = new HeaderDepth();
    for (int index = 0; index <= source.length() - keyword.length(); index++) {
      depth.consume(source.charAt(index));
      if (depth.topLevel() && keywordAt(source, keyword, index)) {
        return index;
      }
    }
    return -1;
  }

static ParsedAnnotation parseAnnotation(String source, Matcher matcher) {
    String name = simpleName(matcher.group(1));
    int end = skipWhitespace(source, matcher.end());
    String arguments = "";
    int annotationEnd = matcher.end();
    if (end < source.length() && source.charAt(end) == '(') {
      int closing = matchingDelimiter(source, end, '(', ')');
      if (closing >= 0) {
        arguments = source.substring(end + 1, closing).trim();
        annotationEnd = closing + 1;
      }
    }
    return new ParsedAnnotation(
        name, arguments, source.substring(matcher.start(), annotationEnd));
  }

static List<String> typeNames(String source) {
    List<String> result = new ArrayList<>();
    for (String raw : splitArguments(source)) {
      String type = stripBalancedGenericArguments(raw.trim()).trim();
      if (!type.isBlank()) {
        result.add(type);
      }
    }
    return result;
  }

static List<String> mediaValues(List<String> values) {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (String value : values) {
      String normalized = mediaType(value);
      if (!normalized.isBlank()) {
        result.add(normalized);
      }
    }
    return List.copyOf(result);
  }

static String attributeValue(String args, String attribute) {
    List<String> parts = splitArguments(args);
    for (String part : parts) {
      int equals = part.indexOf('=');
      if (equals >= 0 && part.substring(0, equals).trim().matches("[A-Za-z_][\\w]*")
          && part.substring(0, equals).trim().equals(attribute)) {
        return part.substring(equals + 1).trim();
      }
    }
    return attribute.equals("value") && !args.isBlank() && parts.stream().noneMatch(
        part -> part.indexOf('=') >= 0 && part.substring(0, part.indexOf('=')).trim().matches("[A-Za-z_][\\w]*"))
        ? args.trim()
        : null;
  }
}
