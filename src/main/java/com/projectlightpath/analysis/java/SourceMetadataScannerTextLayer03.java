package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerTextLayer03 extends SourceMetadataScannerTextLayer02 {

  SourceMetadataScannerTextLayer03(AnalysisContext context) {
    super(context);
  }



static int fieldInitializerEnd(String source, int start) {
    int parentheses = 0;
    int brackets = 0;
    int braces = 0;
    for (int index = start; index < source.length(); index++) {
      switch (source.charAt(index)) {
        case '(' -> parentheses++;
        case ')' -> parentheses = Math.max(0, parentheses - 1);
        case '[' -> brackets++;
        case ']' -> brackets = Math.max(0, brackets - 1);
        case '{' -> braces++;
        case '}' -> braces = Math.max(0, braces - 1);
        case ';' -> {
          if (parentheses == 0 && brackets == 0 && braces == 0) {
            return index;
          }
        }
        default -> { }
      }
    }
    return -1;
  }

static int skipWhitespace(String source, int start) {
    int position = start;
    while (position < source.length() && Character.isWhitespace(source.charAt(position))) {
      position++;
    }
    return position;
  }

static int matchingGenericDelimiter(String source, int opening) {
    return JavaSourceText.matchingGenericDelimiter(source, opening);
  }

static int typeBodyOpening(String source, int start) {
    return JavaSourceText.typeBodyOpening(source, start);
  }

static Optional<String> fixedHttpMethod(String annotationName) {
    return switch (annotationName) {
      case "POST", "PostMapping" -> Optional.of("POST");
      case "GET", "GetMapping" -> Optional.of("GET");
      case "PUT", "PutMapping" -> Optional.of("PUT");
      case "PATCH", "PatchMapping" -> Optional.of("PATCH");
      case "DELETE", "DeleteMapping" -> Optional.of("DELETE");
      case "HEAD" -> Optional.of("HEAD");
      case "OPTIONS" -> Optional.of("OPTIONS");
      default -> Optional.empty();
    };
  }

static List<String> intersectHttpMethods(
      List<String> methodMethods, List<String> classMethods) {
    Set<String> classMethodSet = new LinkedHashSet<>(classMethods);
    return methodMethods.stream()
        .filter(classMethodSet::contains)
        .toList();
  }

  /** Returns a Java annotation string only when its value is a complete literal. */
static Optional<String> literalString(String expression) {
    if (expression.length() < 2 || expression.charAt(0) != '"'
        || expression.charAt(expression.length() - 1) != '"') {
      return Optional.empty();
    }
    boolean escaped = false;
    for (int index = 1; index < expression.length() - 1; index++) {
      char character = expression.charAt(index);
      if (character == '"' && !escaped) {
        return Optional.empty();
      }
      if (character == '\\' && !escaped) {
        escaped = true;
      } else {
        escaped = false;
      }
    }
    return Optional.of(expression.substring(1, expression.length() - 1));
  }

static String routeKey(
      SourceFile file,
      MethodInfo method,
      String httpMethod,
      String path,
      List<String> consumes,
      List<String> produces,
      List<String> headers,
      List<String> parameters) {
    return String.join("|",
        file.displayPath(),
        file.typeName(),
        method.name(),
        method.parameters(),
        httpMethod,
        path,
        String.join(",", consumes),
        String.join(",", produces),
        String.join(",", headers),
        String.join(",", parameters));
  }

static int typeHeaderBodyStart(String source, int declarationStart) {
    HeaderDepth depth = new HeaderDepth();
    for (int index = declarationStart; index < source.length(); index++) {
      if (depth.consume(source.charAt(index))) {
        return index;
      }
    }
    return source.length();
  }
}
