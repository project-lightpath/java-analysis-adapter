package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerTextLayer22 extends SourceMetadataScannerTextLayer21 {

  SourceMetadataScannerTextLayer22(AnalysisContext context) {
    super(context);
  }



  boolean isJdkTypeOrSubclass(
      String candidate, String expectedType, Set<String> visited) {
    if (candidate.equals(expectedType)) {
      return true;
    }
    if (!visited.add(candidate) || !expectedType.equals("java.lang.ClassLoader")) {
      return false;
    }
    TypeReference reference = findTypeReference(candidate);
    if (reference != null
        && resolveParentTypes(reference).stream()
            .anyMatch(parent -> isJdkTypeOrSubclass(parent, expectedType, visited))) {
      return true;
    }
    return Optional.ofNullable(KNOWN_CLASSLOADER_SUPERTYPES.get(candidate))
        .map(parent -> isJdkTypeOrSubclass(parent, expectedType, visited))
        .orElse(false);
  }

  /** Resolves each comma-separated contract against the declaring source file. */
  void addResolvedContracts(Set<String> contracts, String source, SourceFile file) {
    for (String type : contractTypeNames(source)) {
      resolveContractExpression(type, file).ifPresent(contracts::add);
    }
  }

  /** Resolves a contract spelling while retaining concrete arguments and raw fallback. */
  Optional<String> resolveContractExpression(String type, SourceFile sourceFile) {
    String normalized = compactGenericWhitespace(type == null ? "" : type).trim();
    int opening = normalized.indexOf('<');
    if (opening < 0) {
      return resolveContractType(normalized, sourceFile);
    }
    int closing = matchingDelimiter(normalized, opening, '<', '>');
    if (closing != normalized.length() - 1) {
      return Optional.empty();
    }
    Optional<String> raw = resolveContractType(normalized.substring(0, opening), sourceFile);
    if (raw.isEmpty()) {
      return Optional.empty();
    }
    List<String> arguments = new ArrayList<>();
    for (String argument : splitArguments(normalized.substring(opening + 1, closing))) {
      Optional<String> resolved = resolveContractExpression(argument, sourceFile);
      if (resolved.isEmpty()) {
        return raw;
      }
      arguments.add(resolved.get());
    }
    return Optional.of(raw.get() + "<" + String.join(",", arguments) + ">");
  }

  /** Splits contract declarations without erasing their generic arguments. */
  static List<String> contractTypeNames(String source) {
    return splitArguments(source).stream()
        .map(type -> compactGenericWhitespace(type.trim()))
        .filter(type -> !type.isBlank())
        .toList();
  }

  boolean isJdkDeclaredType(
      SourceFile file,
      String spelling,
      int offset,
      String expectedSimpleName,
      Set<String> typeVariables) {
    String normalized = stripBalancedGenericArguments(spelling == null ? "" : spelling).trim();
    Optional<TypeVariableBounds> bounds = typeVariableBounds(file, normalized, offset);
    if (bounds.isPresent()) {
      if (!typeVariables.add(normalized)) {
        return false;
      }
      return bounds.get().bounds().stream()
          .anyMatch(bound -> isJdkDeclaredType(
              file,
              bound,
              bounds.get().declarationOffset(),
              expectedSimpleName,
              new HashSet<>(typeVariables)));
    }
    String expectedType = resolveJdkTypeName(expectedSimpleName);
    return resolveOwnerType(normalized, file, offset)
        .map(resolved -> isJdkTypeOrSubclass(resolved, expectedType, new HashSet<>()))
        .orElse(false);
  }

  /** Extracts resolved implemented interfaces and the direct superclass from one type header. */
  Set<String> contracts(SourceFile file) {
    Set<String> contracts = new LinkedHashSet<>();
    String header = maskStringLiterals(typeHeader(file));
    int implementsIndex = topLevelKeywordIndex(header, "implements");
    int extendsIndex = topLevelKeywordIndex(header, "extends");
    if (implementsIndex >= 0) {
      int end = topLevelKeywordIndex(header, "permits");
      String implemented = header.substring(
          implementsIndex + "implements".length(), end >= 0 ? end : header.length());
      addResolvedContracts(contracts, implemented, file);
    }
    if (extendsIndex >= 0) {
      int end = implementsIndex >= 0 ? implementsIndex : topLevelKeywordIndex(header, "permits");
      String extended = header.substring(
          extendsIndex + "extends".length(), end >= 0 ? end : header.length());
      List<String> extendedTypes = contractTypeNames(extended);
      if (header.matches("(?s).*\\binterface\\s+\\w+.*")) {
        addResolvedContracts(contracts, String.join(",", extendedTypes), file);
      } else if (!extendedTypes.isEmpty()) {
        resolveContractExpression(extendedTypes.get(0), file).ifPresent(contracts::add);
      }
    }
    return Set.copyOf(contracts);
  }

  boolean isJdkDeclaredType(
      SourceFile file, String spelling, int offset, String expectedSimpleName) {
    return isJdkDeclaredType(file, spelling, offset, expectedSimpleName, new HashSet<>());
  }
}
