package com.projectlightpath.analysis.java;

import static com.projectlightpath.analysis.java.SpikeContractValidator.CERTAINTY_STATES;
import static com.projectlightpath.analysis.java.SpikeContractValidator.JAVAC_METRICS;
import static com.projectlightpath.analysis.java.SpikeContractValidator.MISSING_INPUT_CODES;
import static com.projectlightpath.analysis.java.SpikeContractValidator.REQUIRED_CASES;
import static com.projectlightpath.analysis.java.SpikeContractValidator.REQUIRED_FSI_CASES;
import static com.projectlightpath.analysis.java.SpikeContractValidator.REQUIRED_JAVAC_CELLS;
import static com.projectlightpath.analysis.java.SpikeContractJavacRules.containsHostPath;
import static com.projectlightpath.analysis.java.SpikeContractJavacRules.javacContainsHostPath;
import static com.projectlightpath.analysis.java.SpikeContractJavacRules.recomputeTotals;
import static com.projectlightpath.analysis.java.SpikeContractJavacRules.totalsAsLongMap;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.byField;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.elements;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.eq;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.fieldNames;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.fieldTextSet;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.findByText;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.first;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.idSet;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.numberOrNull;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.numEquals;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.require;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.text;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.textSet;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.truthy;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Observation-level rules of the spike-contract gate (#45 step 2): capability-case
 * coverage, the normalized analyzer results (SootUp, CodeQL, host paths), and the javac
 * resolver GO/NO-GO observation. Ported from the corresponding blocks of
 * spike/scripts/validate_spike.py.
 */
final class SpikeContractObservationChecks {
  private final SpikeContractValidator.Documents documents;
  private final Path root;

  SpikeContractObservationChecks(SpikeContractValidator.Documents documents, Path root) {
    this.documents = documents;
    this.root = root;
  }

  /** Runs every observation-level rule in the Python gate's order. */
  void checkAll() {
    checkObservationCoverage();
    checkAnalyzerResults();
    checkJavacResolver();
  }

  /** JDT LS and SootUp observation coverage of capability cases (Python 266-273). */
  private void checkObservationCoverage() {
    List<JsonNode> observations = elements(documents.observation().path("observations"));
    require(REQUIRED_CASES.equals(idSet(observations, "caseId")),
        "observation result must cover every capability case");
    require(anyObservation(observations, "reflection", "confirmed_gap"),
        "reflection gap must be recorded");
    require(anyObservation(observations, "direct-call", "existing_support"),
        "direct-call support must be recorded");
    require(anyObservation(observations, "framework-entrypoint", "confirmed_gap"),
        "framework entrypoint gap must be recorded");
    for (String caseId : List.of("callback-lambda", "service-loader")) {
      JsonNode entry = findByText(elements(
          documents.sootupQuarkus().path("observations")), "caseId", caseId);
      require(entry != null && hasConcreteEdge(entry),
          caseId + " must retain a concrete normalized edge");
    }
  }

  /** Normalized analyzer results: pins, coverage, host paths (Python 275-334). */
  private void checkAnalyzerResults() {
    require(Files.isRegularFile(root.resolve("spike/scripts/run_static_analysis.py")),
        "static-analysis runner is missing");
    require(Files.isRegularFile(root.resolve("spike/scripts/normalize_static_analysis.py")),
        "static-analysis normalizer is missing");
    List<JsonNode> analyzerResults = List.of(documents.sootupQuarkus(),
        documents.sootupFsi(), documents.codeqlQuarkus(), documents.codeqlFsi());
    Map<String, JsonNode> contextById = byField(
        elements(documents.analysisContext().path("contexts")), "id");
    Set<String> analyzerCertainties = new HashSet<>();
    for (JsonNode result : analyzerResults) {
      checkAnalyzerResult(result, contextById, analyzerCertainties);
    }
    require(analyzerCertainties.containsAll(CERTAINTY_STATES),
        "analyzer results must preserve certainty states");
    checkAnalyzerCoverage(analyzerResults);
    checkSootupPins();
    checkCodeqlPins();
    for (String candidateId : List.of("sootup", "codeql")) {
      JsonNode candidate = findByText(elements(
          documents.candidates().path("candidateSet")), "id", candidateId);
      require(candidate != null && !hasUnverifiedAcceptance(candidate),
          candidateId + " still has an unverified acceptance result after its evidence run");
    }
  }

  /** One normalized analyzer result (Python 279-299). */
  private void checkAnalyzerResult(JsonNode result, Map<String, JsonNode> contextById,
      Set<String> certainties) {
    String providerName = text(result.path("provider"), "name");
    require(eq(text(result, "issue"), "FLPATH-5000"),
        "analyzer result issue mismatch");
    require(truthy(result.path("authority").path("hostPathsOmitted")),
        "normalized analyzer result must omit host paths");
    require(Set.of("quarkus", "spring-boot")
        .contains(text(result.path("fixture"), "framework")),
        "analyzer result must identify Quarkus or Spring Boot");
    require(eq(text(result, "frameworkModel"), "spike/contracts/framework-model.json"),
        "normalized analyzer result must reference FrameworkModel");
    String analysisContextId = text(result, "analysisContextId");
    require(contextById.containsKey(analysisContextId),
        "normalized analyzer result must reference AnalysisContext");
    JsonNode context = contextById.get(analysisContextId);
    require(context != null && eq(text(context.path("framework"), "id"),
        text(result.path("fixture"), "framework")),
        "normalized analyzer result context/framework mismatch");
    for (JsonNode item : elements(result.path("observations"))) {
      certainties.add(text(item, "certainty"));
      require(truthy(item.path("evidence")), providerName + " observations need evidence");
    }
    require(!containsHostPath(result), providerName + " result contains a host path");
  }

  /** Analyzer observation coverage against the matrix cases (Python 303-310, 330). */
  private void checkAnalyzerCoverage(List<JsonNode> analyzerResults) {
    Set<String> requiredQuarkusCases = fieldTextSet(
        documents.matrix().path("cases"), "id");
    Map<String, Set<String>> frameworkCoverage = coverageByFramework(
        documents.matrix().path("frameworkCoverage"));
    require(frameworkCoverage.getOrDefault("quarkus", Set.of())
        .equals(requiredQuarkusCases),
        "Quarkus framework coverage must match capability cases");
    require(idSet(elements(documents.sootupQuarkus().path("observations")), "caseId")
        .equals(requiredQuarkusCases),
        "SootUp Quarkus observation coverage is incomplete");
    require(idSet(elements(documents.codeqlQuarkus().path("observations")), "caseId")
        .equals(requiredQuarkusCases),
        "CodeQL Quarkus observation coverage is incomplete");
    require(frameworkCoverage.getOrDefault("spring-boot", Set.of())
        .equals(REQUIRED_FSI_CASES),
        "Spring Boot framework coverage must match baseline cases");
    require(idSet(elements(documents.sootupFsi().path("observations")), "caseId")
        .equals(REQUIRED_FSI_CASES),
        "SootUp FSI observation coverage is incomplete");
    require(idSet(elements(documents.codeqlFsi().path("observations")), "caseId")
        .equals(REQUIRED_FSI_CASES),
        "CodeQL FSI observation coverage is incomplete");
    Set<String> fixtureFrameworks = new HashSet<>();
    for (JsonNode result : analyzerResults) {
      fixtureFrameworks.add(text(result.path("fixture"), "framework"));
    }
    require(fixtureFrameworks.equals(Set.of("quarkus", "spring-boot")),
        "analyzer results must cover both frameworks");
  }

  /** SootUp version, coverage, and measurement pins (Python 312-320). */
  private void checkSootupPins() {
    JsonNode sootupQuarkus = documents.sootupQuarkus();
    require(eq(text(sootupQuarkus.path("provider"), "version"), "3.0.1"),
        "SootUp version must be pinned");
    JsonNode coverage = sootupQuarkus.path("coverage");
    require(eq(text(coverage, "fullDependencyRun"), "passed"),
        "SootUp full Quarkus runtime analysis must pass");
    require(eq(text(coverage, "applicationOnlyRun"), "passed"),
        "SootUp application-only result must pass");
    require(eq(text(coverage, "classpathScope"), "runtime"),
        "SootUp Quarkus classpath scope must be runtime");
    require(numEquals(coverage.path("dependencyClasspathEntries"), 118),
        "SootUp Quarkus runtime classpath measurement changed unexpectedly");
    boolean illegalArgumentReported = false;
    for (JsonNode limitation : elements(sootupQuarkus.path("limitations"))) {
      if (limitation.isTextual()
          && limitation.asText().contains("IllegalArgumentException")) {
        illegalArgumentReported = true;
      }
    }
    require(!illegalArgumentReported,
        "SootUp Quarkus bytecode failure must not be reported after the runtime-scope fix");
    require(numEquals(documents.sootupFsi().path("coverage")
        .path("dependencyClasspathEntries"), 104),
        "SootUp FSI runtime classpath measurement changed unexpectedly");
    JsonNode firstAnalysis = first(
        documents.sootupFsi().path("measurements").path("analyses"));
    require(numEquals(firstAnalysis.path("methodCount"), 6224),
        "SootUp FSI CHA method measurement changed unexpectedly");
    require(numEquals(firstAnalysis.path("callCount"), 7853),
        "SootUp FSI CHA call measurement changed unexpectedly");
  }

  /** CodeQL version and fact-scope pins (Python 322-329). */
  private void checkCodeqlPins() {
    for (JsonNode result : List.of(documents.codeqlQuarkus(), documents.codeqlFsi())) {
      JsonNode coverage = result.path("coverage");
      require(eq(text(result.path("provider"), "version"), "2.27.4"),
          "CodeQL version must be pinned");
      require(eq(text(result.path("provider"), "javaPackVersion"), "9.3.4"),
          "CodeQL Java pack must be pinned");
      require(eq(text(coverage, "buildMode"), "none"),
          "CodeQL build-mode limitation must be explicit");
      Long productionFacts = numberOrNull(coverage.path("productionFacts"));
      Long testFactsFiltered = numberOrNull(coverage.path("testFactsFiltered"));
      require(productionFacts != null && testFactsFiltered != null
          && numEquals(coverage.path("rawFacts"), productionFacts + testFactsFiltered),
          "CodeQL fact scope counts must balance");
      require(numberOrNull(coverage.path("testFactsFiltered")) != null
          && coverage.path("testFactsFiltered").doubleValue() > 0,
          "CodeQL test-source filtering evidence is missing");
    }
    require(numEquals(documents.codeqlQuarkus().path("coverage").path("rawFacts"), 64),
        "CodeQL Quarkus measurement changed unexpectedly");
    require(numEquals(documents.codeqlFsi().path("coverage").path("rawFacts"), 2356),
        "CodeQL FSI measurement changed unexpectedly");
  }

  /** Javac resolver GO/NO-GO observation (Python 336-418). */
  private void checkJavacResolver() {
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
    require(recomputeTotals(javacResolver).equals(totalsAsLongMap(javacTotals)),
        "javac resolver totals must equal the per-cell sum");
    require(!javacContainsHostPath(javacResolver),
        "javac resolver observation contains a host path");
  }

  /** One javac resolver cell's metrics, facts, and blocked rule (Python 349-357). */
  private void checkJavacCell(JsonNode cell) {
    String id = text(cell, "id");
    boolean blocked = truthy(cell.path("blocked"));
    if (blocked) {
      require(Set.of("N22", "N23").contains(id),
          "unexpected blocked javac resolver cell " + id);
    }
    JsonNode javac = cell.path("javac");
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
      checkJavacCellSites(cell, id, javac);
    }
  }

  /** Site-level agreement between expected and observed javac facts (Python 358-367). */
  private void checkJavacCellSites(JsonNode cell, String id, JsonNode javac) {
    List<JsonNode> expected = elements(cell.path("expected"));
    require(!expected.isEmpty(), id + " has no expected sites");
    Set<Long> expectedOffsets = new HashSet<>();
    for (JsonNode site : expected) {
      Long offset = numberOrNull(site.path("offset"));
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
  private void checkExpectedSite(JsonNode site, String id,
      Map<Long, JsonNode> factsByOffset, Map<Long, JsonNode> abstentionsByOffset) {
    Long offset = numberOrNull(site.path("offset"));
    String expectation = text(site, "expectation");
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
  private void checkAbstentionSite(String expectation, String id, Long offset,
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

  /** True when any observation matches the case id and classification. */
  private boolean anyObservation(List<JsonNode> observations, String caseId,
      String classification) {
    for (JsonNode item : observations) {
      if (eq(text(item, "caseId"), caseId)
          && eq(text(item, "classification"), classification)) {
        return true;
      }
    }
    return false;
  }

  /** True when any evidence entry has "caller" (key in an object, or substring). */
  private boolean hasConcreteEdge(JsonNode entry) {
    for (JsonNode evidence : elements(entry.path("evidence"))) {
      if (evidence.isObject() && fieldNames(evidence).contains("caller")) {
        return true;
      }
      if (evidence.isTextual() && evidence.asText().contains("caller")) {
        return true;
      }
    }
    return false;
  }

  /** True when any acceptance value is the literal "unverified". */
  private boolean hasUnverifiedAcceptance(JsonNode candidate) {
    for (JsonNode value : candidate.path("acceptance")) {
      if (eq(value.isTextual() ? value.asText() : null, "unverified")) {
        return true;
      }
    }
    return false;
  }

  /** True when any diagnostic code contains one of the missing-input codes. */
  private boolean hasAnyCode(JsonNode diagnostics, List<String> codes) {
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
  private boolean hasAmbiguityEvidence(JsonNode diagnostics) {
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

  /** Maps fact/abstention nodes by offset, rejecting duplicate offsets. */
  private Map<Long, JsonNode> byOffset(List<JsonNode> nodes, String id,
      String duplicateMessage) {
    Map<Long, JsonNode> byOffset = new LinkedHashMap<>();
    for (JsonNode node : nodes) {
      Long offset = numberOrNull(node.path("offset"));
      require(offset != null && byOffset.putIfAbsent(offset, node) == null,
          id + " has " + duplicateMessage);
    }
    return byOffset;
  }

  /** Per-framework case-id coverage, later entries overwriting like Python. */
  private Map<String, Set<String>> coverageByFramework(JsonNode frameworkCoverage) {
    Map<String, Set<String>> coverage = new LinkedHashMap<>();
    for (JsonNode item : elements(frameworkCoverage)) {
      String framework = text(item, "framework");
      if (framework != null) {
        coverage.put(framework, textSet(item.path("caseIds")));
      }
    }
    return coverage;
  }
}
