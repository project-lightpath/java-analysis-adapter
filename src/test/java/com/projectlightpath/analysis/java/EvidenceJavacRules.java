package com.projectlightpath.analysis.java;

import static com.projectlightpath.analysis.java.EvidenceCollections.elements;
import static com.projectlightpath.analysis.java.EvidenceContractValidator.JAVAC_METRICS;
import static com.projectlightpath.analysis.java.EvidenceContractValidator.REQUIRED_JAVAC_CELLS;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Host-path walkers and javac-metric recomputation for the spike-contract gate (#45 step
 * 2): recursive JSON walks that reject machine-specific paths in normalized results, and
 * the per-cell totals sum behind the GO/NO-GO totals check. Ported from the corresponding
 * blocks of spike/scripts/validate_spike.py.
 */
final class EvidenceJavacRules {
  /** The javac host-path walker's drive-letter predicate (Python raw regex). */
  private static final Pattern DRIVE_LETTER = Pattern.compile("[A-Za-z]:[\\\\/]");

  private EvidenceJavacRules() {}

  /** Rejects machine-specific POSIX paths anywhere in a normalized analyzer result. */
  static boolean containsHostPath(JsonNode node) {
    if (node == null) {
      return false;
    }
    if (node.isTextual()) {
      String value = node.asText();
      return value.startsWith("/") || value.contains("/Users/")
          || value.contains("/private/var/");
    }
    for (JsonNode child : node) {
      if (containsHostPath(child)) {
        return true;
      }
    }
    return false;
  }

  /** Rejects POSIX and Windows machine-specific paths in the javac observation. */
  static boolean javacContainsHostPath(JsonNode node) {
    if (node == null) {
      return false;
    }
    if (node.isTextual()) {
      String value = node.asText();
      return value.startsWith("/") || value.startsWith("\\\\")
          || DRIVE_LETTER.matcher(value).lookingAt() || value.contains("/Users/")
          || value.contains("/private/var/") || value.contains("/tmp/")
          || value.contains("\\\\Users\\\\");
    }
    for (JsonNode child : node) {
      if (javacContainsHostPath(child)) {
        return true;
      }
    }
    return false;
  }

  /** The per-cell sum of every javac metric. */
  static Map<String, Long> recomputeTotals(JsonNode observation) {
    Map<String, Long> totals = new LinkedHashMap<>();
    for (String metric : JAVAC_METRICS) {
      totals.put(metric, 0L);
    }
    for (JsonNode cell : elements(observation.path("cells"))) {
      JsonNode metrics = cell.path("javac").path("metrics");
      for (String metric : JAVAC_METRICS) {
        JsonNode value = metrics.path(metric);
        if (value.isNumber()) {
          totals.merge(metric, value.longValue(), Long::sum);
        }
      }
    }
    return totals;
  }

  /** The recorded javac totals block as a long map. */
  static Map<String, Long> totalsAsLongMap(JsonNode totals) {
    Map<String, Long> values = new LinkedHashMap<>();
    for (String metric : JAVAC_METRICS) {
      JsonNode value = totals.path(metric);
      values.put(metric, value.isNumber() ? value.longValue() : null);
    }
    return values;
  }

  /** The required javac resolver cell inventory (71 ids, including compound labels). */
  static Set<String> requiredJavacCells() {
    return REQUIRED_JAVAC_CELLS;
  }
}
