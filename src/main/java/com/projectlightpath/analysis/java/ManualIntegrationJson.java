package com.projectlightpath.analysis.java;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * JSON traversal helpers for the manual-integration validator (#45 step 3): null-safe
 * text fields, array elements, id sets, and Python truthiness/positivity.
 */
final class ManualIntegrationJson {

  private ManualIntegrationJson() {}

  /** The field's text, or null when absent, non-textual, or the parent is null. */
  static String text(JsonNode parent, String field) {
    if (parent == null) {
      return null;
    }
    JsonNode node = parent.path(field);
    return node.isTextual() ? node.asText() : null;
  }

  /** Elements of an array node, or an empty list for any other shape. */
  static List<JsonNode> elements(JsonNode array) {
    List<JsonNode> values = new ArrayList<>();
    if (array != null && array.isArray()) {
      array.forEach(values::add);
    }
    return values;
  }

  /** Text ids of a node list, skipping facts without one. */
  static Set<String> idSet(List<JsonNode> nodes) {
    Set<String> ids = new LinkedHashSet<>();
    for (JsonNode node : nodes) {
      String id = text(node, "id");
      if (id != null) {
        ids.add(id);
      }
    }
    return ids;
  }

  /** Python truthiness: false for missing, null, false, zero, empty string/container. */
  static boolean truthy(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return false;
    }
    if (node.isBoolean()) {
      return node.asBoolean();
    }
    if (node.isNumber()) {
      return node.doubleValue() != 0.0;
    }
    if (node.isTextual()) {
      return !node.asText().isEmpty();
    }
    if (node.isArray() || node.isObject()) {
      return !node.isEmpty();
    }
    return true;
  }

  /** Python {@code > 0} on a JSON number: false for missing and non-numeric values. */
  static boolean positive(JsonNode node) {
    return node != null && node.isNumber() && node.doubleValue() > 0;
  }
}
