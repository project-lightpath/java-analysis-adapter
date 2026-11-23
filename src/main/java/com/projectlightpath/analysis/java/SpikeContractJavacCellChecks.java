package com.projectlightpath.analysis.java;

import static com.projectlightpath.analysis.java.SpikeContractCollections.elements;
import static com.projectlightpath.analysis.java.SpikeContractCollections.idSet;
import static com.projectlightpath.analysis.java.SpikeContractJavacRules.recomputeTotals;
import static com.projectlightpath.analysis.java.SpikeContractJavacRules.totalsAsLongMap;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.eq;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.numEquals;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.require;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.text;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.truthy;
import static com.projectlightpath.analysis.java.SpikeContractValidator.JAVAC_METRICS;
import static com.projectlightpath.analysis.java.SpikeContractValidator.REQUIRED_JAVAC_CELLS;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;

/**
 * Javac resolver GO/NO-GO cell rules of the spike-contract gate (#45 step 2): the cell
 * inventory, per-cell metrics and fact shape, and the totals consistency check. Site-level
 * agreement lives in {@link SpikeContractJavacSiteChecks}. Ported from Python 336-357 and
 * 396-418 of spike/scripts/validate_spike.py.
 */
final class SpikeContractJavacCellChecks {
  private final SpikeContractValidator.Documents documents;

  SpikeContractJavacCellChecks(SpikeContractValidator.Documents documents) {
    this.documents = documents;
  }

  /** Runs the javac resolver rules. */
  void checkAll() {
    JsonNode javacResolver = documents.javacResolver();
    require(eq(text(javacResolver, "schema"), "javac-symbol-resolver-observation.v1"),
        "javac resolver observation schema mismatch");
    require(truthy(javacResolver.path("deterministic")),
        "javac resolver observation must record determinism");
    JsonNode javacTotals = javacResolver.path("javacTotals");
    for (String metric : JAVAC_METRICS) {
      require(numEquals(javacTotals.path(metric), 0),
          "javac resolver GO metric " + metric + " must be zero");
    }
    List<JsonNode> cells = elements(javacResolver.path("cells"));
    require(idSet(cells, "id").equals(REQUIRED_JAVAC_CELLS),
        "javac resolver observation cell set is incomplete or has drifted");
    require(cells.size() == 71, "javac resolver observation must have 71 cells");
    for (JsonNode cell : cells) {
      checkJavacCell(cell);
    }
    // Python keyed the recomputed map off the document's own totals, so an extra
    // javacTotals key failed the equality; reject unknown keys explicitly.
    require(SpikeContractCollections.fieldNames(javacTotals)
        .equals(Set.copyOf(JAVAC_METRICS)),
        "javac resolver totals must equal the per-cell sum");
    require(recomputeTotals(javacResolver).equals(totalsAsLongMap(javacTotals)),
        "javac resolver totals must equal the per-cell sum");
    require(!SpikeContractJavacRules.javacContainsHostPath(javacResolver),
        "javac resolver observation contains a host path");
  }

  /** One javac resolver cell's metrics, facts, and blocked rule (Python 349-357). */
  private void checkJavacCell(JsonNode cell) {
    String id = text(cell, "id");
    boolean blocked = truthy(cell.path("blocked"));
    require(!cell.path("blocked").isMissingNode(),
        "javac resolver cell " + id + " does not record a blocked flag");
    if (blocked) {
      require(id != null && Set.of("N22", "N23").contains(id),
          "unexpected blocked javac resolver cell " + id);
    }
    JsonNode javac = cell.path("javac");
    require(javac.path("facts").isArray(),
        "javac resolver cell " + id + " does not record facts");
    for (String metric : JAVAC_METRICS) {
      require(numEquals(javac.path("metrics").path(metric), 0),
          "javac resolver cell " + id + " metric " + metric + " must be zero");
    }
    for (JsonNode fact : elements(javac.path("facts"))) {
      require(eq(text(fact, "owner"), "java.util.ServiceLoader"),
          "javac fact " + id + " owner mismatch");
      require(eq(text(fact, "member"), "load"),
          "javac fact " + id + " member mismatch");
      require(eq(text(fact, "accessibility"), "PUBLIC_STATIC"),
          "javac fact " + id + " accessibility evidence missing");
    }
    if (!blocked) {
      SpikeContractJavacSiteChecks.checkJavacCellSites(cell, id, javac);
    }
  }
}
