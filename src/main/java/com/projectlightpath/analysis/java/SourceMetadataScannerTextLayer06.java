package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerTextLayer06 extends SourceMetadataScannerTextLayer05 {

  SourceMetadataScannerTextLayer06(AnalysisContext context) {
    super(context);
  }



static String qualify(String name, String packageName) {
    return name.contains(".") ? name : (packageName.isBlank() ? name : packageName + "." + name);
  }

static String simpleName(String value) {
    int dot = value.lastIndexOf('.');
    return dot < 0 ? value : value.substring(dot + 1);
  }

  /** Checks a token on the source line attributed to a provider call. */
  boolean sourceLineContains(MethodSymbol symbol, int line, String token) {
    if (line <= 0) {
      return false;
    }
    return sourceFile(symbol.owner())
        .map(file -> {
          String source = maskStringLiterals(file.structuralSource());
          int start = lineStartOffset(source, line);
          if (start < 0) {
            return false;
          }
          int end = source.indexOf('\n', start);
          String sourceLine = source.substring(start, end < 0 ? source.length() : end);
          return sourceLine.contains(token);
        })
        .orElse(false);
  }

static boolean methodContains(SourceFile file, MethodInfo method, int offset) {
    String source = file.structuralSource();
    if (method.start() > offset || method.end() <= 0 || method.end() > source.length()
        || source.charAt(method.end() - 1) != '{') {
      return false;
    }
    int closing = matchingDelimiter(source, method.end() - 1, '{', '}');
    return closing >= offset;
  }

  /** Resolves explicit imports that can qualify a source type spelling. */
static List<String> importedTypes(String type, SourceFile sourceFile) {
    String simpleType = type;
    int nestedSeparator = type.indexOf('.');
    if (nestedSeparator >= 0) {
      simpleType = type.substring(0, nestedSeparator);
    }
    Matcher matcher = Pattern.compile("(?m)^\\s*import\\s+([A-Za-z_][\\w.]*)\\s*;")
        .matcher(maskStringLiterals(sourceFile.structuralSource()));
    List<String> result = new ArrayList<>();
    while (matcher.find()) {
      String imported = matcher.group(1);
      if (imported.endsWith("." + simpleType)) {
        result.add(imported + type.substring(simpleType.length()));
      }
    }
    return result.stream().distinct().toList();
  }

static Set<String> onDemandImportPackages(SourceFile sourceFile) {
    Matcher matcher = Pattern.compile("(?m)^\\s*import\\s+([A-Za-z_][\\w.]*)\\.\\*\\s*;")
        .matcher(maskStringLiterals(sourceFile.structuralSource()));
    Set<String> packages = new LinkedHashSet<>();
    while (matcher.find()) {
      packages.add(matcher.group(1));
    }
    return packages;
  }

  /** Splits one call's arguments without crossing nested expressions. */
static List<String> splitTopLevelArguments(String arguments) {
    return splitBalanced(arguments, ',', false);
  }

  Optional<String> uniqueOnDemandOwner(
      List<String> candidates, String normalized, SourceFile sourceFile) {
    List<String> distinct = candidates.stream().distinct().toList();
    if (distinct.size() == 1) {
      return Optional.of(normalizeDeclaredTypeName(distinct.get(0), sourceFile));
    }
    return Optional.empty();
  }

static int typeParameterAnnotationEnd(String source, int start) {
    int index = start + 1;
    while (index < source.length()
        && (Character.isJavaIdentifierPart(source.charAt(index)) || source.charAt(index) == '.')) {
      index++;
    }
    index = skipWhitespace(source, index);
    if (index >= source.length() || source.charAt(index) != '(') {
      return index;
    }
    int closing = matchingDelimiter(source, index, '(', ')');
    return closing < 0 ? -1 : closing + 1;
  }

static List<String> splitTypeIntersectionBounds(String expression) {
    return splitBalanced(expression, '&', false);
  }

static boolean hasOnDemandImport(SourceFile sourceFile, String packageName) {
    return Pattern.compile("(?m)^\\s*import\\s+" + Pattern.quote(packageName) + "\\.\\*\\s*;")
        .matcher(maskStringLiterals(sourceFile.structuralSource()))
        .find();
  }

static Optional<DeclarationScope> catchScope(String source, int declaration) {
    Matcher matcher = Pattern.compile("\\bcatch\\s*\\(").matcher(source);
    while (matcher.find()) {
      int opening = matcher.end() - 1;
      int closing = matchingDelimiter(source, opening, '(', ')');
      if (closing < 0 || declaration <= opening || declaration >= closing) {
        continue;
      }
      int bodyStart = skipWhitespace(source, closing + 1);
      if (bodyStart < source.length() && source.charAt(bodyStart) == '{') {
        int bodyEnd = matchingDelimiter(source, bodyStart, '{', '}');
        return Optional.ofNullable(bodyEnd < 0 ? null : new DeclarationScope(bodyStart, bodyEnd));
      }
    }
    return Optional.empty();
  }
}
