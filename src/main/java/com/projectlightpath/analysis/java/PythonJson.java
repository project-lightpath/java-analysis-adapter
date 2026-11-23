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
   * Python's float repr: shortest round-trip digits, positional form for 1e-4 <= |x| <
   * 1e16 (always with a fractional part), exponent form {@code de+NN} outside that
   * range. Java's {@code Double.toString} agrees on digits but switches to exponent form
   * at different thresholds, so the value is re-rendered here.
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
    String java = Double.toString(value);
    int exponentMarker = java.indexOf('E');
    if (exponentMarker < 0) {
      // Java positional (1e-3 <= |x| < 1e7) is inside Python's positional range.
      return java;
    }
    double mantissa = Double.parseDouble(java.substring(0, exponentMarker));
    int exponent = Integer.parseInt(java.substring(exponentMarker + 1));
    if (exponent >= 16 || exponent <= -5) {
      String digits = Double.toString(mantissa);
      if (digits.endsWith(".0")) {
        digits = digits.substring(0, digits.length() - 2);
      }
      return digits + "e" + (exponent < 0 ? "-" : "+")
          + String.format("%02d", Math.abs(exponent));
    }
    String positional = new java.math.BigDecimal(java.substring(0, exponentMarker))
        .scaleByPowerOfTen(exponent).toPlainString();
    if (positional.indexOf('.') < 0) {
      return positional + ".0";
    }
    // BigDecimal keeps the mantissa's trailing zeros; Python's repr drops them.
    int end = positional.length();
    while (end > 0 && positional.charAt(end - 1) == '0') {
      end--;
    }
    positional = positional.substring(0, end);
    return positional.endsWith(".") ? positional + "0" : positional;
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
