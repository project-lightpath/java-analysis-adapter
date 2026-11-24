package com.projectlightpath.analysis.java;

import java.util.Optional;

/**
 * Renders correlated index member shapes into the consumer dialects: the
 * bounded-model JVM descriptor ({@code Ljava/lang/String;}) from binary
 * parameter and return types ({@code demo.Outer$Inner} for nested types —
 * the JVM descriptor requires the binary name, never the canonical dotted
 * spelling). Binary names render directly — no resolution step — and any
 * blank component abstains so matching falls back rather than fabricating.
 */
final class JavacShapeDescriptors {
  private JavacShapeDescriptors() {}

  /** Builds a JVM descriptor from one correlated index member's binary
   * shapes (bounded-model dialect). Binary names render directly — no
   * resolution step — and any blank component abstains (Optional.empty)
   * so matching falls back rather than fabricating.
   */
  static Optional<String> jvmDescriptorFromMember(
      JavacSourceSymbolIndex.MemberInfo member) {
    StringBuilder descriptor = new StringBuilder("(");
    for (String parameter : member.parameterBinaryTypes()) {
      Optional<String> part = binaryJvmDescriptor(parameter);
      if (part.isEmpty()) {
        return Optional.empty();
      }
      descriptor.append(part.orElseThrow());
    }
    descriptor.append(')');
    return binaryJvmDescriptor(member.returnBinaryType())
        .map(value -> descriptor.append(value).toString());
  }

  /** Renders one binary (already qualified or primitive) type spelling. */
  private static Optional<String> binaryJvmDescriptor(String binary) {
    String normalized = binary == null ? "" : binary.trim();
    if (normalized.isEmpty()) {
      return Optional.empty();
    }
    if (normalized.endsWith("[]")) {
      return binaryJvmDescriptor(
          normalized.substring(0, normalized.length() - 2)).map(value -> "[" + value);
    }
    return switch (normalized) {
      case "void" -> Optional.of("V");
      case "boolean" -> Optional.of("Z");
      case "byte" -> Optional.of("B");
      case "char" -> Optional.of("C");
      case "short" -> Optional.of("S");
      case "int" -> Optional.of("I");
      case "long" -> Optional.of("J");
      case "float" -> Optional.of("F");
      case "double" -> Optional.of("D");
      default -> normalized.contains(".")
          ? Optional.of("L" + normalized.replace('.', '/') + ";")
          : Optional.empty();
    };
  }
}
