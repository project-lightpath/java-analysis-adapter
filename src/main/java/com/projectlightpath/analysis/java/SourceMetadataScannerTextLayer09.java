package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerTextLayer09 extends SourceMetadataScannerTextLayer08 {

  SourceMetadataScannerTextLayer09(AnalysisContext context) {
    super(context);
  }



  /** Finds annotation occurrences with balanced argument extraction. */
static List<AnnotationOccurrence> annotationOccurrences(String block, String name) {
    List<AnnotationOccurrence> result = new ArrayList<>();
    String source = stripComments(block == null ? "" : block);
    String searchSource = maskStringLiterals(source);
    Matcher matcher = Pattern.compile(
        "@(?:[A-Za-z_][\\w.]*\\.)?" + Pattern.quote(name) + "\\b").matcher(searchSource);
    while (matcher.find()) {
      int end = matcher.end();
      while (end < source.length() && Character.isWhitespace(source.charAt(end))) {
        end++;
      }
      String arguments = "";
      if (end < source.length() && source.charAt(end) == '(') {
        int closing = matchingDelimiter(source, end, '(', ')');
        if (closing < 0) {
          continue;
        }
        arguments = source.substring(end + 1, closing);
        end = closing + 1;
      }
      result.add(new AnnotationOccurrence(
          arguments,
          source.substring(matcher.start(), end).replaceAll("\\s+", " ").trim()));
    }
    return List.copyOf(result);
  }

  /** Returns the outermost generic argument text from one declared type. */
static String genericArguments(String source) {
    int opening = source.indexOf('<');
    if (opening < 0) {
      return "";
    }
    int closing = matchingDelimiter(source, opening, '<', '>');
    return closing > opening ? source.substring(opening + 1, closing) : "";
  }

  /** Applies one on-demand import lookup to either source or JDK type candidates. */
static List<String> onDemandImportedTypeNames(
      String type, SourceFile sourceFile, Stream<String> candidates) {
    String simpleType = type;
    int nestedSeparator = type.indexOf('.');
    if (nestedSeparator >= 0) {
      simpleType = type.substring(0, nestedSeparator);
    }
    Set<String> packages = onDemandImportPackages(sourceFile);
    String suffix = type.substring(simpleType.length());
    String requestedSimpleType = simpleType;
    return candidates
        .filter(candidate -> packages.contains(packageName(candidate)))
        .filter(candidate -> qualifiedSimpleName(candidate).equals(requestedSimpleType))
        .map(candidate -> candidate + suffix)
        .distinct()
        .toList();
  }

  Optional<String> knownOwnerType(String normalized, SourceFile sourceFile) {
    if (normalized.equals("java.lang.Class") || normalized.equals("java.lang.reflect.Proxy")) {
      return Optional.of(normalized);
    }
    if (normalized.equals("java.lang.ClassLoader")
        || normalized.equals("java.lang.reflect.Method")
        || normalized.equals("java.lang.reflect.Constructor")
        || normalized.equals("java.lang.reflect.InvocationHandler")) {
      return Optional.of(normalized);
    }
    if (normalized.equals("Class")) {
      return Optional.of("java.lang.Class");
    }
    if (normalized.equals("Proxy") && hasOnDemandImport(sourceFile, "java.lang.reflect")) {
      return Optional.of("java.lang.reflect.Proxy");
    }
    return switch (normalized) {
      case "Method" -> Optional.of("java.lang.reflect.Method");
      case "Constructor" -> Optional.of("java.lang.reflect.Constructor");
      case "ClassLoader" -> Optional.of("java.lang.ClassLoader");
      case "InvocationHandler" -> Optional.of("java.lang.reflect.InvocationHandler");
      default -> Optional.empty();
    };
  }

static boolean isMethodTypeParameterPrefix(String prefix) {
    Set<String> modifiers = Set.of(
        "public", "protected", "private", "static", "final", "synchronized",
        "abstract", "native", "default", "strictfp");
    int index = 0;
    while (index < prefix.length()) {
      index = skipWhitespace(prefix, index);
      if (index >= prefix.length()) {
        return true;
      }
      if (prefix.charAt(index) == '@') {
        index = typeParameterAnnotationEnd(prefix, index);
        if (index < 0) {
          return false;
        }
        continue;
      }
      int tokenEnd = identifierEnd(prefix, index);
      if (tokenEnd == index || !modifiers.contains(prefix.substring(index, tokenEnd))) {
        return false;
      }
      index = tokenEnd;
    }
    return true;
  }

static String stripLeadingTypeParameterAnnotations(String parameter) {
    int index = 0;
    while (index < parameter.length()) {
      index = skipWhitespace(parameter, index);
      if (index >= parameter.length() || parameter.charAt(index) != '@') {
        break;
      }
      index = typeParameterAnnotationEnd(parameter, index);
      if (index < 0) {
        return parameter;
      }
    }
    return parameter.substring(index).trim();
  }

static void addInterfaceParents(
      List<String> result, String header, int extendsIndex, int permitsIndex) {
    if (extendsIndex >= 0) {
      int end = permitsIndex >= 0 ? permitsIndex : header.length();
      result.addAll(typeNames(header.substring(extendsIndex + "extends".length(), end)));
    }
  }
}
