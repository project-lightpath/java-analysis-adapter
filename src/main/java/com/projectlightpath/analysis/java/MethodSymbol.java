package com.projectlightpath.analysis.java;

import java.util.Objects;

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
    int separator = body.indexOf(": ");
    if (separator < 0) {
      throw new IllegalArgumentException("invalid Soot method signature: " + signature);
    }
    String owner = body.substring(0, separator);
    String method = body.substring(separator + 2);
    int open = method.indexOf('(');
    if (open < 0) {
      return new MethodSymbol(owner, method.trim(), signature);
    }
    String beforeParameters = method.substring(0, open).trim();
    int nameStart = beforeParameters.lastIndexOf(' ');
    String name = nameStart < 0 ? beforeParameters : beforeParameters.substring(nameStart + 1);
    return new MethodSymbol(owner, name, signature);
  }
}
