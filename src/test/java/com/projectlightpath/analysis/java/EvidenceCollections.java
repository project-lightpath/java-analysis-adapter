package com.projectlightpath.analysis.java;

import static com.projectlightpath.analysis.java.EvidenceSemantics.require;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collection helpers shared by the spike-contract rule classes (#45 step 2): array/object
 * traversal and text-field lookups that reproduce Python's iteration semantics, with
 * non-textual set elements kept visible as a sentinel (Python's {@code set()} keeps them).
 */
final class EvidenceCollections {
  /**
   * Prefix marking a set element that is present but non-textual: Python's {@code set()}
   * keeps such elements and counts distinct values distinctly ({@code {17, 18}} has size
   * 2), so the sentinel carries the value's JSON text to preserve both distinctness and
   * duplicate detection. A textual value starting with the prefix is escaped by doubling
   * it, so no textual string can collide with a sentinel (non-textual JSON never
   * serializes to a leading NUL). Residual divergences, both reject-direction: Python
   * collapses {@code True} with {@code 1}, and {@code 17} with {@code 17.0}, where the
   * JSON texts differ.
   */
  static final String NON_TEXTUAL_PREFIX = "\u0000";

  private EvidenceCollections() {}

  /** Text values of an array field; non-textual elements surface as prefixed sentinels. */
  static Set<String> textSet(JsonNode array) {
    Set<String> values = new LinkedHashSet<>();
    if (array != null && array.isArray()) {
      array.forEach(element ->
          values.add(element.isTextual() ? escape(element.asText())
              : NON_TEXTUAL_PREFIX + element));
    }
    return values;
  }

  /** Doubles the sentinel prefix so a textual value can never equal a sentinel. */
  private static String escape(String value) {
    return value.startsWith(NON_TEXTUAL_PREFIX)
        ? NON_TEXTUAL_PREFIX + value : value;
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

  /**
   * Text values of one field across a node list. A missing field rejects loudly (Python
   * raised KeyError reading it); a non-textual value surfaces as a value-distinct
   * sentinel, so equality comparisons break and uniqueness comparisons keep distinct
   * values distinct, exactly like Python's set of mixed elements.
   */
  static Set<String> idSet(List<JsonNode> nodes, String field) {
    Set<String> values = new LinkedHashSet<>();
    for (JsonNode node : nodes) {
      JsonNode value = node.path(field);
      require(!value.isMissingNode(), "missing " + field + " in a list entry");
      values.add(value.isTextual() ? escape(value.asText())
          : NON_TEXTUAL_PREFIX + value);
    }
    return values;
  }

  /** Maps a list by one text field; later entries overwrite, like Python dict build. */
  static Map<String, JsonNode> byField(List<JsonNode> nodes, String field) {
    Map<String, JsonNode> byField = new LinkedHashMap<>();
    for (JsonNode node : nodes) {
      String key = EvidenceSemantics.text(node, field);
      if (key != null) {
        byField.put(key, node);
      }
    }
    return byField;
  }

  /** The first node whose text field equals the value, or null. */
  static JsonNode findByText(List<JsonNode> nodes, String field, String value) {
    for (JsonNode node : nodes) {
      if (EvidenceSemantics.eq(EvidenceSemantics.text(node, field), value)) {
        return node;
      }
    }
    return null;
  }
}
