package com.projectlightpath.analysis.java;

import static com.projectlightpath.analysis.java.SpikeContractCollections.byField;
import static com.projectlightpath.analysis.java.SpikeContractCollections.elements;
import static com.projectlightpath.analysis.java.SpikeContractCollections.fieldTextSet;
import static com.projectlightpath.analysis.java.SpikeContractCollections.findByText;
import static com.projectlightpath.analysis.java.SpikeContractCollections.first;
import static com.projectlightpath.analysis.java.SpikeContractCollections.idSet;
import static com.projectlightpath.analysis.java.SpikeContractCollections.textSet;
import static com.projectlightpath.analysis.java.SpikeContractJavacRules.containsHostPath;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.eq;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.numEquals;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.require;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.text;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.truthy;
import static com.projectlightpath.analysis.java.SpikeContractValidator.CERTAINTY_STATES;
import static com.projectlightpath.analysis.java.SpikeContractValidator.REQUIRED_CASES;
import static com.projectlightpath.analysis.java.SpikeContractValidator.REQUIRED_FSI_CASES;

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
 * coverage and the normalized analyzer results (SootUp, CodeQL, host paths). The javac
 * resolver rules live in {@link SpikeContractJavacCellChecks}. Ported from the
 * corresponding blocks of spike/scripts/validate_spike.py.
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
    new SpikeContractJavacCellChecks(documents).checkAll();
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
    Set<String> fixtureFrameworks = new HashSet<>();
    for (JsonNode result : analyzerResults) {
      fixtureFrameworks.add(text(result.path("fixture"), "framework"));
    }
    require(fixtureFrameworks.equals(Set.of("quarkus", "spring-boot")),
        "analyzer results must cover both frameworks");
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
    String fixtureFramework = text(result.path("fixture"), "framework");
    require(fixtureFramework != null
        && Set.of("quarkus", "spring-boot").contains(fixtureFramework),
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
    require(result.path("observations").isArray(),
        providerName + " observations are missing");
    for (JsonNode item : elements(result.path("observations"))) {
      require(!item.path("certainty").isMissingNode(),
          providerName + " observation certainty is missing");
      certainties.add(text(item, "certainty"));
      require(truthy(item.path("evidence")), providerName + " observations need evidence");
    }
    require(!containsHostPath(result), providerName + " result contains a host path");
  }

  /** Analyzer observation coverage against the matrix cases (Python 303-310). */
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
    require(sootupQuarkus.path("limitations").isArray(),
        "SootUp Quarkus limitations are missing");
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
      // Python summed the raw numbers (52.5 + 12 = 64.5 != 64), so the comparison must
      // not truncate fractional counts onto integers.
      Double productionFacts = numericOrNull(coverage.path("productionFacts"));
      Double testFactsFiltered = numericOrNull(coverage.path("testFactsFiltered"));
      require(productionFacts != null && testFactsFiltered != null
          && numEquals(coverage.path("rawFacts"), productionFacts + testFactsFiltered),
          "CodeQL fact scope counts must balance");
      require(testFactsFiltered != null && testFactsFiltered > 0,
          "CodeQL test-source filtering evidence is missing");
    }
    require(numEquals(documents.codeqlQuarkus().path("coverage").path("rawFacts"), 64),
        "CodeQL Quarkus measurement changed unexpectedly");
    require(numEquals(documents.codeqlFsi().path("coverage").path("rawFacts"), 2356),
        "CodeQL FSI measurement changed unexpectedly");
  }

  /** A node's numeric value as a Double, or null when absent or non-numeric. */
  private static Double numericOrNull(JsonNode node) {
    return node != null && node.isNumber() ? node.doubleValue() : null;
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
      if (evidence.isObject()
          && SpikeContractCollections.fieldNames(evidence).contains("caller")) {
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
