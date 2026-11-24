package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerTextLayer00 extends SourceMetadataScannerTextHeaderSupport {

  SourceMetadataScannerTextLayer00(AnalysisContext context) {
    super(context);
  }



  Optional<SourceFile> sourceFile(String owner) {
    String topLevel = owner.contains("$") ? owner.substring(0, owner.indexOf('$')) : owner;
    return files().stream().filter(file -> file.typeName().equals(topLevel)).findFirst();
  }

static int lineStartOffset(String source, int line) {
    if (line <= 0) {
      return -1;
    }
    int currentLine = 1;
    for (int index = 0; index < source.length(); index++) {
      if (currentLine == line) {
        return index;
      }
      if (source.charAt(index) == '\n') {
        currentLine++;
      }
    }
    return currentLine == line ? source.length() : -1;
  }

static String packageName(String qualifiedName) {
    int separator = qualifiedName.lastIndexOf('.');
    return separator < 0 ? "" : qualifiedName.substring(0, separator);
  }

static String qualifiedSimpleName(String qualifiedName) {
    return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
  }

static List<String> splitBalanced(
      String source, char separator, boolean inferGenericStarts) {
    return JavaSourceText.splitBalanced(source, separator, inferGenericStarts);
  }

static String jdkSimpleName(String expectedType) {
    return switch (expectedType) {
      case "java.lang.Class" -> "Class";
      case "java.lang.ClassLoader" -> "ClassLoader";
      case "java.lang.reflect.Method" -> "Method";
      case "java.lang.reflect.Constructor" -> "Constructor";
      case "java.lang.reflect.InvocationHandler" -> "InvocationHandler";
      case "java.lang.reflect.Proxy" -> "Proxy";
      default -> null;
    };
  }

static int identifierEnd(String source, int start) {
    int index = start;
    while (index < source.length() && Character.isJavaIdentifierPart(source.charAt(index))) {
      index++;
    }
    return index;
  }

static String resolveJdkTypeName(String simpleName) {
    return switch (simpleName) {
      case "Class" -> "java.lang.Class";
      case "ClassLoader" -> "java.lang.ClassLoader";
      case "Method" -> "java.lang.reflect.Method";
      case "Constructor" -> "java.lang.reflect.Constructor";
      case "InvocationHandler" -> "java.lang.reflect.InvocationHandler";
      case "Proxy" -> "java.lang.reflect.Proxy";
      default -> simpleName;
    };
  }

static List<String> qualifiedDeclaredTypeCandidates(
      String normalized, SourceFile sourceFile, List<String> declared) {
    List<String> direct = declared.stream()
        .filter(candidate -> candidate.equals(normalized)
            || candidate.replace('$', '.').equals(normalized))
        .toList();
    if (!direct.isEmpty()) {
      return direct;
    }
    String qualified = normalized;
    if (!qualified.startsWith(sourceFile.packageName() + ".")
        && !sourceFile.packageName().isBlank()) {
      qualified = sourceFile.packageName() + "." + qualified;
    }
    String resolvedQualified = qualified;
    String dotted = resolvedQualified.replace('$', '.');
    return declared.stream()
        .filter(candidate -> candidate.equals(resolvedQualified)
            || candidate.replace('$', '.').equals(dotted))
        .toList();
  }
}
