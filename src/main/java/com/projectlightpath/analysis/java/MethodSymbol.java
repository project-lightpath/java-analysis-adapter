package com.projectlightpath.analysis.java;

import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Stable method identity retained alongside the compact owner#name display. */
public record MethodSymbol(String owner, String name, String descriptor) {
  public MethodSymbol {
    if (owner == null || owner.isBlank()) {
      throw new IllegalArgumentException("method owner must not be blank");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("method name must not be blank");
    }
    descriptor = descriptor == null ? "" : descriptor;
  }

  public String displayName() {
    return owner + "#" + name;
  }

  public static MethodSymbol fromSootSignature(String signature) {
    Objects.requireNonNull(signature, "Soot method signature");
    String body = signature.trim();
    if (body.startsWith("<") && body.endsWith(">")) {
      body = body.substring(1, body.length() - 1);
    }
    int separator = body.indexOf(':');
    if (separator < 0) {
      throw new IllegalArgumentException("invalid Soot method signature: " + signature);
    }
    String owner = body.substring(0, separator).trim();
    String method = body.substring(separator + 1).trim();
    int open = method.indexOf('(');
    if (open < 0) {
      throw new IllegalArgumentException("invalid Soot method signature: " + signature);
    }
    int close = method.lastIndexOf(')');
    if (close < open) {
      throw new IllegalArgumentException("invalid Soot method signature: " + signature);
    }
    String beforeParameters = method.substring(0, open).trim();
    int nameStart = beforeParameters.lastIndexOf(' ');
    if (nameStart < 0 || nameStart == beforeParameters.length() - 1) {
      throw new IllegalArgumentException("invalid Soot method signature: " + signature);
    }
    String returnType = beforeParameters.substring(0, nameStart).trim();
    String name = beforeParameters.substring(nameStart + 1).trim();
    String parameters = method.substring(open + 1, close).trim();
    String descriptor = "(" + splitTypes(parameters).stream()
        .map(MethodSymbol::jvmType)
        .reduce(new StringBuilder(), StringBuilder::append, StringBuilder::append)
        + ")" + jvmType(returnType);
    return new MethodSymbol(owner, name, descriptor);
  }

  /** Converts Soot's Java-like type spelling into the JVM descriptor spelling. */
  private static String jvmType(String type) {
    String normalized = eraseGenericArguments(type == null ? "" : type.trim())
        .replace("...", "[]")
        .replaceAll("\\s+", "");
    if (normalized.isBlank()) {
      throw new IllegalArgumentException("method type must not be blank");
    }
    if (normalized.startsWith("[")) {
      return normalized.replace('.', '/');
    }
    int dimensions = 0;
    while (normalized.endsWith("[]")) {
      dimensions++;
      normalized = normalized.substring(0, normalized.length() - 2);
    }
    String primitive = Map.of(
        "boolean", "Z",
        "byte", "B",
        "char", "C",
        "short", "S",
        "int", "I",
        "long", "J",
        "float", "F",
        "double", "D",
        "void", "V").get(normalized);
    String base = primitive != null
        ? primitive
        : normalized.startsWith("L") && normalized.endsWith(";")
            ? normalized.replace('.', '/')
            : "L" + normalized.replace('.', '/') + ";";
    return "[".repeat(dimensions) + base;
  }

  /** Splits a Soot parameter list without splitting nested generic arguments. */
  private static List<String> splitTypes(String parameters) {
    if (parameters.isBlank()) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    int start = 0;
    int angleDepth = 0;
    for (int index = 0; index < parameters.length(); index++) {
      char character = parameters.charAt(index);
      if (character == '<') {
        angleDepth++;
      } else if (character == '>') {
        angleDepth = Math.max(0, angleDepth - 1);
      } else if (character == ',' && angleDepth == 0) {
        result.add(parameters.substring(start, index).trim());
        start = index + 1;
      }
    }
    result.add(parameters.substring(start).trim());
    return List.copyOf(result);
  }

  /** Erases Java-like generic arguments from a Soot type spelling. */
  private static String eraseGenericArguments(String value) {
    StringBuilder result = new StringBuilder(value.length());
    int depth = 0;
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == '<') {
        depth++;
      } else if (character == '>') {
        depth = Math.max(0, depth - 1);
      } else if (depth == 0) {
        result.append(character);
      }
    }
    return result.toString();
  }
}
