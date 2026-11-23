package com.projectlightpath.analysis.java;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * Renders JSON exactly as Python's {@code json.dumps(node, indent=2)} (#45 step 3): two-space
 * indentation, {@code ": "} after keys, {@code ,} plus newline between items, {@code {}} and
 * {@code []} for empty containers, and ensure_ascii string escaping (control characters,
 * lowercase {@code \\uXXXX}, surrogate pairs) so emitted evidence files are byte-identical
 * to the Python validator's.
 */
final class PythonJson {

  private PythonJson() {}

  /** Renders the tree exactly as Python {@code json.dumps(node, indent=2)}. */
  static String dumps(JsonNode node) {
    StringBuilder out = new StringBuilder();
    writeValue(node, out, 0);
    return out.toString();
  }

  private static void writeValue(JsonNode node, StringBuilder out, int depth) {
    if (node == null || node.isNull()) {
      out.append("null");
    } else if (node.isObject()) {
      writeObject(node, out, depth);
    } else if (node.isArray()) {
      writeArray(node, out, depth);
    } else if (node.isBoolean()) {
      out.append(node.asBoolean() ? "true" : "false");
    } else if (node.isFloatingPointNumber()) {
      out.append(pythonFloat(node.doubleValue()));
    } else if (node.isNumber()) {
      out.append(node.asText());
    } else {
      writeString(node.asText(), out);
    }
  }

  /**
   * Python's float repr: the shortest decimal that round-trips to the exact binary
   * double, positional form for 1e-4 <= |x| < 1e16 (always with a fractional part),
   * exponent form {@code de+NN} outside that range. The digits come from rounding the
   * EXACT binary value (BigDecimal) to successive significant-digit counts and checking
   * the round-trip, never from re-parsing a mantissa: Java's Double.toString digits on
   * JDK 17 can differ from CPython's shortest repr, and a re-parse loses digits.
   */
  private static String pythonFloat(double value) {
    if (Double.isNaN(value)) {
      return "NaN";
    }
    if (value == Double.POSITIVE_INFINITY) {
      return "Infinity";
    }
    if (value == Double.NEGATIVE_INFINITY) {
      return "-Infinity";
    }
    if (value == 0.0) {
      return 1.0 / value < 0 ? "-0.0" : "0.0";
    }
    String sign = value < 0 ? "-" : "";
    java.math.BigDecimal exact = new java.math.BigDecimal(value).abs();
    for (int digits = 1; digits <= 17; digits++) {
      java.math.BigDecimal candidate = exact.round(
          new java.math.MathContext(digits, java.math.RoundingMode.HALF_EVEN));
      String formatted = formatRepr(sign, candidate);
      if (Double.parseDouble(formatted) == value) {
        return formatted;
      }
    }
    // Unreachable for finite doubles (17 significant digits always round-trip).
    return sign + exact.round(new java.math.MathContext(17)).toPlainString();
  }

  /** Renders a rounded nonzero magnitude in Python's repr positional/exponent form. */
  private static String formatRepr(String sign, java.math.BigDecimal candidate) {
    java.math.BigDecimal stripped = candidate.stripTrailingZeros();
    int exponent = stripped.precision() - stripped.scale() - 1;
    if (exponent < -4 || exponent > 15) {
      String digits = stripped.unscaledValue().toString();
      String mantissa = digits.length() == 1 ? digits
          : digits.charAt(0) + "." + digits.substring(1);
      return sign + mantissa + "e" + (exponent < 0 ? "-" : "+")
          + String.format(java.util.Locale.ROOT, "%02d", Math.abs(exponent));
    }
    String positional = stripped.toPlainString();
    return sign + (positional.indexOf('.') < 0 ? positional + ".0" : positional);
  }

  private static void writeObject(JsonNode node, StringBuilder out, int depth) {
    if (node.isEmpty()) {
      out.append("{}");
      return;
    }
    out.append("{\n");
    boolean first = true;
    for (Map.Entry<String, JsonNode> field : node.properties()) {
      if (!first) {
        out.append(",\n");
      }
      first = false;
      indent(out, depth + 1);
      writeString(field.getKey(), out);
      out.append(": ");
      writeValue(field.getValue(), out, depth + 1);
    }
    out.append('\n');
    indent(out, depth);
    out.append('}');
  }

  private static void writeArray(JsonNode node, StringBuilder out, int depth) {
    if (node.isEmpty()) {
      out.append("[]");
      return;
    }
    out.append("[\n");
    boolean first = true;
    for (JsonNode element : node) {
      if (!first) {
        out.append(",\n");
      }
      first = false;
      indent(out, depth + 1);
      writeValue(element, out, depth + 1);
    }
    out.append('\n');
    indent(out, depth);
    out.append(']');
  }

  /** Python ensure_ascii string escaping: control chars, lowercase \\uXXXX. */
  private static void writeString(String value, StringBuilder out) {
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        default -> out.append(c < 0x20 || c > 0x7e
            ? String.format("\\u%04x", (int) c) : String.valueOf(c));
      }
    }
    out.append('"');
  }

  private static void indent(StringBuilder out, int depth) {
    out.append("  ".repeat(depth));
  }
}
