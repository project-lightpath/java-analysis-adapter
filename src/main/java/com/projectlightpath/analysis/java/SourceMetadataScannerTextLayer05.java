package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerTextLayer05 extends SourceMetadataScannerTextLayer04 {

  SourceMetadataScannerTextLayer05(AnalysisContext context) {
    super(context);
  }



  /** Checks whether a simple {@code @Produces} resolves to a CDI import. */
static boolean importsCdiProducer(SourceFile file) {
    Matcher matcher = Pattern.compile("(?m)\\bimport\\s+([A-Za-z_][\\w.]*(?:\\.\\*)?)\\s*;")
        .matcher(maskStringLiterals(file.structuralSource()));
    boolean explicitCdi = false;
    boolean explicitOther = false;
    boolean cdiWildcard = false;
    while (matcher.find()) {
      String imported = matcher.group(1);
      if (CDI_PRODUCER_ANNOTATIONS.contains(imported)) {
        explicitCdi = true;
      } else if (imported.endsWith(".Produces")) {
        explicitOther = true;
      }
      if (imported.equals("jakarta.enterprise.inject.*")
          || imported.equals("javax.enterprise.inject.*")) {
        cdiWildcard = true;
      }
    }
    if (explicitOther) {
      return false;
    }
    if (explicitCdi) {
      return true;
    }
    return cdiWildcard;
  }

  /** Extracts the annotation type name from its source spelling. */
static String annotationType(String source) {
    String normalized = source == null ? "" : source.trim();
    if (normalized.startsWith("@")) {
      normalized = normalized.substring(1);
    }
    int opening = normalized.indexOf('(');
    return opening < 0 ? normalized : normalized.substring(0, opening).trim();
  }

static String stripComments(String source) {
    return JavaSourceText.stripComments(source);
  }

static String maskStringLiterals(String source) {
    return JavaSourceText.maskStringLiterals(source);
  }

  /** Removes nested generic arguments from a declaration without leaving trailing brackets. */
static String stripBalancedGenericArguments(String source) {
    StringBuilder result = new StringBuilder(source.length());
    int depth = 0;
    for (int index = 0; index < source.length(); index++) {
      char character = source.charAt(index);
      if (character == '<') {
        depth++;
      } else if (character == '>' && depth > 0) {
        depth--;
      } else if (depth == 0) {
        result.append(character);
      }
    }
    return result.toString();
  }

static int matchingDelimiter(String source, int opening, char open, char close) {
    return JavaSourceText.matchingDelimiter(source, opening, open, close);
  }

  /** Normalizes common media type constants and literals. */
static String mediaType(String value) {
    String normalized = value.trim();
    String constant = normalized.substring(normalized.lastIndexOf('.') + 1);
    if (constant.endsWith("_VALUE")) {
      constant = constant.substring(0, constant.length() - "_VALUE".length());
    }
    normalized = switch (constant) {
      case "APPLICATION_JSON" -> "application/json";
      case "APPLICATION_XML" -> "application/xml";
      case "APPLICATION_FORM_URLENCODED" -> "application/x-www-form-urlencoded";
      case "MULTIPART_FORM_DATA" -> "multipart/form-data";
      case "TEXT_PLAIN" -> "text/plain";
      case "ALL" -> "*/*";
      default -> normalized;
    };
    if (normalized.endsWith("_VALUE")) {
      normalized = normalized.substring(0, normalized.length() - "_VALUE".length());
    }
    return normalized.toLowerCase(Locale.ROOT);
  }

  /** Merges annotation values without duplicates. */
static List<String> mergeValues(List<String> first, List<String> second) {
    LinkedHashSet<String> result = new LinkedHashSet<>(first);
    result.addAll(second);
    return List.copyOf(result);
  }

static List<String> overrideValues(List<String> inherited, List<String> declared) {
    return declared.isEmpty() ? inherited : declared;
  }

  /** Joins route path fragments and normalizes their slash boundaries. */
static String joinPath(String prefix, String suffix) {
    String left = prefix == null || prefix.isBlank() ? "" : prefix.trim();
    String right = suffix == null || suffix.isBlank() ? "" : suffix.trim();
    if (left.isEmpty() && right.isEmpty()) {
      return "/";
    }
    String joined = (left + "/" + right).replaceAll("/{2,}", "/");
    if (!joined.startsWith("/")) {
      joined = "/" + joined;
    }
    return joined.length() > 1 && joined.endsWith("/") ? joined.substring(0, joined.length() - 1) : joined;
  }
}
