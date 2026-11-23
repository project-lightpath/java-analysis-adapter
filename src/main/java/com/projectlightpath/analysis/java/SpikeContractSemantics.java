package com.projectlightpath.analysis.java;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Python-semantics helpers shared by the spike-contract rule classes (#45 step 2): each
 * helper reproduces one Python behavior the port must preserve — {@code ==} on JSON
 * scalars, truthiness, set/dict iteration, prefix-anchored regex matching — so the rule
 * classes read like the original script.
 */
final class SpikeContractSemantics {
  /** Aborts validation with a stable diagnostic when a contract fails. */
  static void require(boolean condition, String message) {
    if (!condition) {
      throw new SpikeContractValidator.ValidationError(message);
    }
  }

  /** The field's text, or null when absent, non-textual, or the parent is null. */
  static String text(JsonNode parent, String field) {
    if (parent == null) {
      return null;
    }
    JsonNode node = parent.path(field);
    return node.isTextual() ? node.asText() : null;
  }

  /** Null-safe string equality with Python's {@code ==} outcome for present values. */
  static boolean eq(String actual, String expected) {
    return expected.equals(actual);
  }

  /**
   * Python numeric equality against a JSON number: the string "21" does not equal 21, and
   * 21.0 does.
   */
  static boolean numEquals(JsonNode node, double expected) {
    return node != null && node.isNumber() && node.doubleValue() == expected;
  }

  /** A node's numeric value as a Long, or null when absent or non-numeric. */
  static Long numberOrNull(JsonNode node) {
    return node != null && node.isNumber() ? node.longValue() : null;
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

  /** Text values of an array field, skipping non-textual elements. */
  static Set<String> textSet(JsonNode array) {
    Set<String> values = new LinkedHashSet<>();
    if (array != null && array.isArray()) {
      array.forEach(element -> {
        if (element.isTextual()) {
          values.add(element.asText());
        }
      });
    }
    return values;
  }

  /** Elements of an array node, or an empty list for any other shape. */
  static List<JsonNode> elements(JsonNode array) {
    List<JsonNode> values = new ArrayList<>();
    if (array != null && array.isArray()) {
      array.forEach(values::add);
    }
    return values;
  }

  /** Field names of an object node, or an empty set for any other shape. */
  static Set<String> fieldNames(JsonNode object) {
    Set<String> names = new LinkedHashSet<>();
    if (object != null && object.isObject()) {
      object.fieldNames().forEachRemaining(names::add);
    }
    return names;
  }

  /** Field values of an object node, or an empty list for any other shape. */
  static List<JsonNode> fieldValues(JsonNode object) {
    List<JsonNode> values = new ArrayList<>();
    if (object != null && object.isObject()) {
      object.forEach(values::add);
    }
    return values;
  }

  /** The first element of an array, or a missing node when absent. */
  static JsonNode first(JsonNode array) {
    return array != null && array.isArray() && !array.isEmpty() ? array.get(0)
        : MissingNode.getInstance();
  }

  /** Text values of one field across array elements. */
  static Set<String> fieldTextSet(JsonNode array, String field) {
    return idSet(elements(array), field);
  }

  /** Text values of one field across a node list, skipping missing values. */
  static Set<String> idSet(List<JsonNode> nodes, String field) {
    Set<String> values = new LinkedHashSet<>();
    for (JsonNode node : nodes) {
      String value = text(node, field);
      if (value != null) {
        values.add(value);
      }
    }
    return values;
  }

  /** Maps a list by one text field; later entries overwrite, like Python dict build. */
  static Map<String, JsonNode> byField(List<JsonNode> nodes, String field) {
    Map<String, JsonNode> byField = new LinkedHashMap<>();
    for (JsonNode node : nodes) {
      String key = text(node, field);
      if (key != null) {
        byField.put(key, node);
      }
    }
    return byField;
  }

  /** The first node whose text field equals the value, or null. */
  static JsonNode findByText(List<JsonNode> nodes, String field, String value) {
    for (JsonNode node : nodes) {
      if (eq(text(node, field), value)) {
        return node;
      }
    }
    return null;
  }
}
