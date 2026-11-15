package com.projectlightpath.analysis.java;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Shared bounded source model for the two first-class framework inputs.
 *
 * <p>It models only evidence that is explicit in source or in the selected
 * context. Container selection, proxy generation, and startup ordering remain
 * possible or unresolved when the corresponding runtime/build metadata is not
 * captured.
 */
abstract class BoundedFrameworkModelTypeParsing extends BoundedFrameworkModelIdentity implements FrameworkModel {

  BoundedFrameworkModelTypeParsing(AnalysisContext.Framework framework) {
    super(framework);
  }


  /** Resolves a complete type expression, retaining canonical generic arguments. */
  static Optional<String> canonicalType(
      String source,
      SourceMetadataScannerState.SourceFile sourceFile,
      SourceMetadataScanner scanner) {
    String normalized = source == null ? "" : source.trim().replaceAll("\\s+", "");
    if (normalized.isBlank() || normalized.startsWith("?")) {
      return Optional.empty();
    }
    if (normalized.endsWith("[]")) {
      return canonicalType(normalized.substring(0, normalized.length() - 2), sourceFile, scanner)
          .map(value -> value + "[]");
    }
    int opening = normalized.indexOf('<');
    if (opening < 0) {
      return primitiveType(normalized).or(() -> scanner.resolveReferenceType(normalized, sourceFile));
    }
    int closing = matchingGenericDelimiter(normalized, opening);
    if (closing != normalized.length() - 1) {
      return Optional.empty();
    }
    Optional<String> raw = canonicalType(normalized.substring(0, opening), sourceFile, scanner);
    if (raw.isEmpty()) {
      return Optional.empty();
    }
    List<String> arguments = splitGenericArguments(
        normalized.substring(opening + 1, closing));
    List<String> canonicalArguments = new ArrayList<>();
    for (String argument : arguments) {
      Optional<String> canonicalArgument = canonicalType(argument, sourceFile, scanner);
      if (canonicalArgument.isEmpty()) {
        return Optional.empty();
      }
      canonicalArguments.add(canonicalArgument.get());
    }
    return Optional.of(raw.get() + "<" + String.join(",", canonicalArguments) + ">");
  }

  /** Reports whether a type expression declares generic arguments. */
  static boolean hasGenericArguments(String source) {
    return source != null && source.indexOf('<') >= 0;
  }

  /** Returns JVM-independent primitive type spellings for generic comparison. */
  static Optional<String> primitiveType(String value) {
    return switch (value) {
      case "boolean", "byte", "char", "short", "int", "long", "float", "double", "void" ->
          Optional.of(value);
      default -> Optional.empty();
    };
  }

  /** Splits nested generic arguments at top-level commas. */
  static List<String> splitGenericArguments(String source) {
    List<String> result = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int index = 0; index < source.length(); index++) {
      char character = source.charAt(index);
      if (character == '<') {
        depth++;
      } else if (character == '>') {
        depth--;
      } else if (character == ',' && depth == 0) {
        result.add(source.substring(start, index));
        start = index + 1;
      }
    }
    result.add(source.substring(start));
    return result;
  }

  /** Finds the closing angle bracket for a generic expression. */
  static int matchingGenericDelimiter(String source, int opening) {
    int depth = 0;
    for (int index = opening; index < source.length(); index++) {
      char character = source.charAt(index);
      if (character == '<') {
        depth++;
      } else if (character == '>' && --depth == 0) {
        return index;
      }
    }
    return -1;
  }
}
