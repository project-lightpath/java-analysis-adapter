package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerTextLayer01 extends SourceMetadataScannerTextLayer00 {

  SourceMetadataScannerTextLayer01(AnalysisContext context) {
    super(context);
  }



static List<String> enclosingDeclaredTypes(
      String owner, String normalized, List<String> declared) {
    List<String> result = new ArrayList<>();
    while (!owner.isBlank()) {
      String nested = owner + "$" + normalized;
      if (declared.contains(nested)) {
        result.add(nested);
      }
      int separator = owner.lastIndexOf('$');
      if (separator < 0) {
        break;
      }
      owner = owner.substring(0, separator);
    }
    return result;
  }

static String normalizeDeclaredTypeName(String spelling, SourceFile sourceFile) {
    String normalized = spelling == null ? "" : spelling.trim();
    if (normalized.contains("$")) {
      return normalized;
    }
    int packageSeparator = normalized.indexOf('.');
    if (packageSeparator >= 0 && !sourceFile.packageName().isBlank()) {
      String packagePrefix = sourceFile.packageName() + ".";
      if (normalized.startsWith(packagePrefix)) {
        String remainder = normalized.substring(packagePrefix.length());
        return packagePrefix + remainder.replace('.', '$');
      }
    }
    return normalized;
  }

static boolean isDeclarationKeyword(String value) {
    return Set.of("return", "throw", "new", "if", "for", "while", "switch", "case", "catch", "assert")
        .contains(value);
  }

static boolean topLevelResourceDeclaration(String source, int opening, int declaration) {
    int parentheses = 0;
    int brackets = 0;
    int braces = 0;
    for (int index = opening + 1; index < declaration; index++) {
      switch (source.charAt(index)) {
        case '(' -> parentheses++;
        case ')' -> parentheses = Math.max(0, parentheses - 1);
        case '[' -> brackets++;
        case ']' -> brackets = Math.max(0, brackets - 1);
        case '{' -> braces++;
        case '}' -> braces = Math.max(0, braces - 1);
        default -> { }
      }
    }
    return parentheses == 0 && brackets == 0 && braces == 0;
  }

static int topLevelHeaderDelimiter(String source, int start, int end, char delimiter) {
    int parentheses = 0;
    int brackets = 0;
    int braces = 0;
    for (int index = start; index < end; index++) {
      switch (source.charAt(index)) {
        case '(' -> parentheses++;
        case ')' -> parentheses = Math.max(0, parentheses - 1);
        case '[' -> brackets++;
        case ']' -> brackets = Math.max(0, brackets - 1);
        case '{' -> braces++;
        case '}' -> braces = Math.max(0, braces - 1);
        default -> { }
      }
      if (source.charAt(index) == delimiter
          && parentheses == 0 && brackets == 0 && braces == 0) {
        return index;
      }
    }
    return -1;
  }

static int simpleStatementEnd(String source, int start) {
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
        case '}' -> {
          if (braces > 0) {
            braces--;
          }
        }
        case ';' -> {
          if (parentheses == 0 && brackets == 0 && braces == 0) {
            return index;
          }
        }
        default -> { }
      }
    }
    return source.length() - 1;
  }
}
