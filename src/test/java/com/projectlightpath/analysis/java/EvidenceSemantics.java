package com.projectlightpath.analysis.java;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/**
 * Python-scalar-semantics helpers shared by the spike-contract rule classes (#45 step 2):
 * each helper reproduces one Python behavior the port must preserve — {@code ==} on JSON
 * scalars, truthiness, whole-number dict keys. Collection traversal lives in {@link
 * EvidenceCollections}.
 */
final class EvidenceSemantics {

  private EvidenceSemantics() {}

  /** Aborts validation with a stable diagnostic when a contract fails. */
  static void require(boolean condition, String message) {
    if (!condition) {
      throw new EvidenceContractValidator.ValidationError(message);
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

  /**
   * Null-safe string equality with Python's {@code ==} outcome for present values: a
   * null on either side is inequality, so the enclosing requirement fails with its
   * own diagnostic instead of a NullPointerException.
   */
  static boolean eq(String actual, String expected) {
    return expected != null && expected.equals(actual);
  }

  /**
   * Python numeric equality against a JSON number: the string "21" does not equal 21, and
   * 21.0 does.
   */
  static boolean numEquals(JsonNode node, double expected) {
    return node != null && node.isNumber() && node.doubleValue() == expected;
  }

  /**
   * A node's whole-number value as a Long, or null when absent, non-numeric, fractional,
   * or out of long range. Python dict keys treat 64.0 and 64 as equal but keep 64.5 and
   * oversized integers distinct, so an integral float maps onto its integer while a
   * fractional or out-of-range value must never silently truncate onto a key.
   */
  static Long wholeNumberOrNull(JsonNode node) {
    if (node == null || !node.isNumber()) {
      return null;
    }
    if (node.isIntegralNumber()) {
      return node.canConvertToLong() ? node.longValue() : null;
    }
    double value = node.doubleValue();
    if (node.isFloatingPointNumber() && value == Math.floor(value)
        && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE) {
      return (long) value;
    }
    return null;
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
}
