package com.projectlightpath.analysis.java;

import static com.projectlightpath.analysis.java.EvidenceCollections.elements;
import static com.projectlightpath.analysis.java.EvidenceSemantics.eq;
import static com.projectlightpath.analysis.java.EvidenceSemantics.require;
import static com.projectlightpath.analysis.java.EvidenceSemantics.text;
import static com.projectlightpath.analysis.java.EvidenceContractValidator.MISSING_INPUT_CODES;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Javac resolver site rules of the spike-contract gate (#45 step 2): site-level agreement
 * between expected and observed facts per cell — offsets, expectation arms (DEFINITE,
 * POSSIBLE, MISSING_INPUT, AMBIGUOUS, SHADOWED, NO_FACT), and duplicate-offset rejection.
 * Ported from Python 358-395 of spike/scripts/validate_spike.py.
 */
final class EvidenceJavacSiteChecks {

  private EvidenceJavacSiteChecks() {}

  /** Site-level agreement between expected and observed javac facts (Python 358-367). */
  static void checkJavacCellSites(JsonNode cell, String id, JsonNode javac) {
    require(javac.path("abstentions").isArray(),
        "javac resolver cell " + id + " does not record abstentions");
    List<JsonNode> expected = elements(cell.path("expected"));
    require(!expected.isEmpty(), id + " has no expected sites");
    Set<Long> expectedOffsets = new HashSet<>();
    for (JsonNode site : expected) {
      Long offset = EvidenceSemantics.wholeNumberOrNull(site.path("offset"));
      require(offset != null && offset >= 0, id + " has an unaligned expected site");
      expectedOffsets.add(offset);
    }
    Map<Long, JsonNode> factsByOffset = byOffset(elements(javac.path("facts")), id,
        "duplicate fact offsets");
    Map<Long, JsonNode> abstentionsByOffset = byOffset(
        elements(javac.path("abstentions")), id, "duplicate abstention offsets");
    require(factsByOffset.keySet().stream().allMatch(expectedOffsets::contains),
        id + " has a fact outside its expected sites");
    require(abstentionsByOffset.keySet().stream().allMatch(expectedOffsets::contains),
        id + " has an abstention outside its expected sites");
    for (JsonNode site : expected) {
      checkExpectedSite(site, id, factsByOffset, abstentionsByOffset);
    }
  }

  /** One expected site's fact-side arms (Python 374-384). */
  private static void checkExpectedSite(JsonNode site, String id,
      Map<Long, JsonNode> factsByOffset, Map<Long, JsonNode> abstentionsByOffset) {
    Long offset = EvidenceSemantics.wholeNumberOrNull(site.path("offset"));
    String expectation = text(site, "expectation");
    require(expectation != null,
        id + " has an expected site without an expectation at " + offset);
    JsonNode fact = offset == null ? null : factsByOffset.get(offset);
    JsonNode abstention = offset == null ? null : abstentionsByOffset.get(offset);
    if (eq(expectation, "DEFINITE") || eq(expectation, "POSSIBLE")) {
      require(fact != null && eq(text(fact, "certainty"), expectation),
          id + " missing " + expectation + " at " + offset);
      require(fact != null && eq(text(fact, "serviceType"), text(site, "serviceType")),
          id + " service type mismatch at " + offset);
    } else if (eq(expectation, "MISSING_INPUT")) {
      require(fact != null && eq(text(fact, "certainty"), "POSSIBLE"),
          id + " missing POSSIBLE at " + offset);
      require(fact != null && eq(text(fact, "serviceType"), text(site, "serviceType")),
          id + " service type mismatch at " + offset);
      require(fact != null && hasAnyCode(fact.path("diagnostics"), MISSING_INPUT_CODES),
          id + " missing-input diagnostic missing at " + offset);
    } else {
      checkAbstentionSite(expectation, id, offset, fact, abstention);
    }
  }

  /** One expected site's abstention-side arms (Python 385-395). */
  private static void checkAbstentionSite(String expectation, String id, Long offset,
      JsonNode fact, JsonNode abstention) {
    if (eq(expectation, "AMBIGUOUS")) {
      require(fact == null, id + " has a fact at an ambiguous site " + offset);
      require(abstention != null, id + " missing ambiguity abstention at " + offset);
      require(abstention != null && hasAmbiguityEvidence(abstention.path("diagnostics")),
          id + " ambiguity evidence missing at " + offset);
    } else if (eq(expectation, "SHADOWED")) {
      require(fact == null, id + " has a fact at a shadowed site " + offset);
      require(abstention != null && eq(text(abstention, "reason"), "SHADOWED"),
          id + " shadow evidence missing at " + offset);
    } else if (eq(expectation, "NO_FACT")) {
      require(fact == null, id + " unexpected fact at " + offset);
      require(abstention != null && eq(text(abstention, "reason"), "NO_CLASS_LITERAL"),
          id + " missing NO_CLASS_LITERAL at " + offset);
    }
  }

  /** Maps fact/abstention nodes by offset, rejecting duplicate offsets. */
  private static Map<Long, JsonNode> byOffset(List<JsonNode> nodes, String id,
      String duplicateMessage) {
    Map<Long, JsonNode> byOffset = new LinkedHashMap<>();
    for (JsonNode node : nodes) {
      Long offset = EvidenceSemantics.wholeNumberOrNull(node.path("offset"));
      require(offset != null && byOffset.putIfAbsent(offset, node) == null,
          id + " has " + duplicateMessage);
    }
    return byOffset;
  }

  /** True when any diagnostic code contains one of the missing-input codes. */
  private static boolean hasAnyCode(JsonNode diagnostics, List<String> codes) {
    for (JsonNode diagnostic : elements(diagnostics)) {
      String code = text(diagnostic, "code");
      for (String candidate : codes) {
        if (code != null && code.contains(candidate)) {
          return true;
        }
      }
    }
    return false;
  }

  /** True when any diagnostic mentions ambiguity in its message or code. */
  private static boolean hasAmbiguityEvidence(JsonNode diagnostics) {
    for (JsonNode diagnostic : elements(diagnostics)) {
      String code = text(diagnostic, "code");
      String message = text(diagnostic, "message");
      if ((message != null && message.toLowerCase().contains("ambiguous"))
          || (code != null && code.contains("ambiguous"))) {
        return true;
      }
    }
    return false;
  }
}
