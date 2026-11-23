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
    } else if (node.isNumber()) {
      out.append(node.asText());
    } else {
      writeString(node.asText(), out);
    }
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
