package com.projectlightpath.analysis.java;

import static com.projectlightpath.analysis.java.SpikeContractValidator.CERTAINTY_STATES;
import static com.projectlightpath.analysis.java.SpikeContractValidator.MANDATORY_CRITERIA;
import static com.projectlightpath.analysis.java.SpikeContractValidator.PINNED_BASELINE_COMMIT;
import static com.projectlightpath.analysis.java.SpikeContractValidator.REQUIRED_CASES;
import static com.projectlightpath.analysis.java.SpikeContractValidator.REQUIRED_EVIDENCE;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.byField;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.elements;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.eq;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.fieldNames;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.fieldTextSet;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.fieldValues;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.first;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.idSet;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.numEquals;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.require;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.text;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.textSet;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.truthy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Document-level rules of the spike-contract gate (#45 step 2): the capability matrix,
 * framework model, binding metadata, AnalysisContext, JDT LS observations, acceptance
 * criteria, candidate assessment, FSI baseline, capability cases, and synthetic advisory.
 * Ported from the corresponding blocks of spike/scripts/validate_spike.py.
 */
final class SpikeContractDocumentChecks {
  private final SpikeContractValidator.Documents documents;

  SpikeContractDocumentChecks(SpikeContractValidator.Documents documents) {
    this.documents = documents;
  }

  /** Runs every document-level rule in the Python gate's order. */
  void checkAll() {
    checkMatrix();
    checkFrameworkModel();
    checkAnalysisContext();
    checkJdtlsObservations();
    checkAcceptanceAndCandidates();
    checkBaseline();
    checkCases();
    checkAdvisory();
  }

  /** Matrix citations, fixture identity, and framework registration (Python 47-59). */
  private void checkMatrix() {
    JsonNode matrix = documents.matrix();
    require(eq(text(matrix, "adr"), "ADR-001"), "matrix must cite ADR-001");
    require(eq(text(matrix, "jiraIssue"), "FLPATH-5000"), "matrix must cite FLPATH-5000");
    require(eq(text(matrix.path("provider"), "name"), "Eclipse JDT Language Server"),
        "provider mismatch");
    JsonNode fixture = matrix.path("fixture");
    require(numEquals(fixture.path("javaRelease"), 21), "fixture must target Java 21");
    require(eq(text(fixture, "framework"), "quarkus"),
        "primary fixture must identify Quarkus");
    String productionProfile = text(fixture, "productionProfile");
    require(productionProfile != null
        && !productionProfile.equals(text(fixture, "testProfile")),
        "profiles must differ");
    require(textSet(matrix.path("requiredEvidence")).containsAll(REQUIRED_EVIDENCE),
        "framework enrichment evidence requirements are missing");
    JsonNode registered = first(matrix.path("referenceBaselines"));
    require(eq(text(registered, "id"), "fsi-spring-boot"),
        "FSI baseline must be registered");
    require(eq(text(registered, "framework"), "spring-boot"),
        "Spring Boot baseline must identify its framework");
    require(fieldTextSet(matrix.path("frameworks"), "id")
        .equals(Set.of("quarkus", "spring-boot")),
        "both framework inputs must be registered");
    require(fieldTextSet(matrix.path("frameworks"), "fixtureId")
        .equals(Set.of("java21-quarkus", "fsi-spring-boot")),
        "framework fixture identities must be unique");
    require(coverageByFramework(matrix.path("frameworkCoverage")).keySet()
        .equals(Set.of("quarkus", "spring-boot")),
        "framework coverage must include Quarkus and Spring Boot");
  }

  /** Framework model and binding metadata shape (Python 62-100). */
  private void checkFrameworkModel() {
    JsonNode model = documents.frameworkModel();
    require(eq(text(model, "artifact"), "FrameworkModel"),
        "framework model artifact mismatch");
    require(eq(text(model, "scope"), "spike_only_no_production_adapter"),
        "framework model must remain spike-scoped");
    require(textSet(model.path("certaintyPolicy").path("states")).equals(CERTAINTY_STATES),
        "framework model certainty states changed");
    require(truthy(model.path("certaintyPolicy").path("promotionGuard")),
        "framework model promotion guard is missing");
    Set<String> edgeTypes = fieldTextSet(model.path("edgeTypes"), "id");
    require(edgeTypes.containsAll(SpikeContractValidator.REQUIRED_EDGE_TYPES),
        "framework model edge types are incomplete");
    require(fieldTextSet(model.path("frameworks"), "id")
        .equals(Set.of("quarkus", "spring-boot")),
        "framework model must cover Quarkus and Spring Boot");
    checkBindingMetadata();
    for (JsonNode framework : elements(model.path("frameworks"))) {
      checkFrameworkEntry(framework, edgeTypes);
    }
  }

  /** Binding metadata artifact, payload, and certainty rules (Python 77-82). */
  private void checkBindingMetadata() {
    JsonNode binding = documents.bindingMetadata();
    require(eq(text(binding, "artifact"), "FrameworkBindingMetadata"),
        "binding metadata artifact mismatch");
    require(eq(text(binding, "schemaVersion"), "0.1.0"),
        "binding metadata schema version mismatch");
    require(fieldNames(binding.path("payload"))
        .equals(Set.of("candidates", "resolvedBindings", "proxyTargets")),
        "binding metadata payload is incomplete");
    require(fieldNames(binding.path("certaintyRules")).containsAll(CERTAINTY_STATES),
        "binding metadata certainty rules are incomplete");
    require(truthy(binding.path("supportedCommonSubsets").path("cdi")),
        "CDI binding subset is missing");
    require(truthy(binding.path("supportedCommonSubsets").path("spring")),
        "Spring binding subset is missing");
  }

  /** One framework model entry: fixture, sources, rules, abstentions (Python 91-100). */
  private void checkFrameworkEntry(JsonNode framework, Set<String> edgeTypes) {
    String id = text(framework, "id");
    require(Set.of("java21-quarkus", "fsi-spring-boot")
        .contains(text(framework, "fixtureId")),
        "unknown fixture in " + id + " framework model");
    require(truthy(framework.path("metadataSources")),
        id + " framework metadata sources are missing");
    require(truthy(framework.path("resolutionRules")),
        id + " framework resolution rules are missing");
    require(truthy(framework.path("abstentions")),
        id + " framework abstentions are missing");
    for (JsonNode rule : elements(framework.path("resolutionRules"))) {
      require(edgeTypes.contains(text(rule, "edgeType")),
          "unknown framework edge type in " + id + " resolution rules");
      require(CERTAINTY_STATES.contains(text(rule, "certainty")),
          "invalid framework certainty in " + id + " resolution rules");
    }
    for (JsonNode abstention : elements(framework.path("abstentions"))) {
      require(eq(text(abstention, "behavior"), "unresolved"),
          id + " abstentions must remain unresolved");
    }
  }

  /** AnalysisContext artifact, coverage, and per-context fields (Python 102-118). */
  private void checkAnalysisContext() {
    JsonNode contextDocument = documents.analysisContext();
    require(eq(text(contextDocument, "artifact"), "AnalysisContext"),
        "analysis context artifact mismatch");
    require(eq(text(contextDocument.path("optionalContracts"), "frameworkBindingMetadata"),
        "spike/contracts/framework-binding-metadata.json"),
        "AnalysisContext binding metadata contract is missing");
    require(eq(text(contextDocument, "scope"), "spike_only_no_production_adapter"),
        "analysis context must remain spike-scoped");
    require(textSet(contextDocument.path("certaintyPolicy").path("states"))
        .equals(CERTAINTY_STATES), "analysis context certainty states changed");
    Map<String, JsonNode> contextById = byField(
        elements(contextDocument.path("contexts")), "id");
    require(contextById.keySet()
        .equals(Set.of("quarkus-production", "spring-boot-production")),
        "analysis context must cover both framework inputs");
    Set<String> modelFrameworks = fieldTextSet(
        documents.frameworkModel().path("frameworks"), "id");
    require(frameworkIds(contextDocument.path("contexts")).equals(modelFrameworks),
        "analysis contexts must match framework model");
    Set<String> requiredFields = textSet(contextDocument.path("requiredFields"));
    for (JsonNode context : contextById.values()) {
      checkAnalysisContextEntry(context, text(context, "id"), requiredFields);
    }
    require(eq(text(contextById.get("quarkus-production").path("fixture"), "id"),
        "java21-quarkus"), "Quarkus context fixture mismatch");
    require(eq(text(contextById.get("spring-boot-production").path("fixture"), "id"),
        "fsi-spring-boot"), "Spring Boot context fixture mismatch");
  }

  /** One analysis context entry's required fields and truthy inputs (Python 109-116). */
  private void checkAnalysisContextEntry(JsonNode context, String id,
      Set<String> requiredFields) {
    for (String field : requiredFields) {
      require(!context.path(field).isMissingNode(),
          id + " is missing a required AnalysisContext field");
    }
    require(truthy(context.path("sourceRoots")), id + " source roots are missing");
    require(truthy(context.path("generatedSourceRoots")),
        id + " generated source roots are missing");
    require(truthy(context.path("build").path("selectedProfile")),
        id + " selected build profile is missing");
    require(eq(text(context.path("build"), "analysisScope"), "production"),
        id + " analysis scope must be production");
    require(truthy(context.path("frameworkMetadata")),
        id + " framework metadata inputs are missing");
    require(truthy(context.path("provenance")),
        id + " provenance requirements are missing");
  }

  /** JDT LS observation fixture and context references (Python 119-125). */
  private void checkJdtlsObservations() {
    JsonNode observation = documents.observation();
    JsonNode baselineObservation = documents.baselineObservation();
    require(numEquals(observation.path("fixture").path("javaRelease"), 21),
        "observation must target Java 21");
    require(eq(text(observation.path("fixture"), "framework"), "quarkus"),
        "Quarkus observation must identify its framework");
    require(eq(text(observation, "frameworkModel"),
        "spike/contracts/framework-model.json"),
        "JDT LS Quarkus observation must reference FrameworkModel");
    require(eq(text(observation, "analysisContextId"), "quarkus-production"),
        "JDT LS Quarkus observation context mismatch");
    require(eq(text(baselineObservation, "frameworkModel"),
        "spike/contracts/framework-model.json"),
        "JDT LS Spring observation must reference FrameworkModel");
    require(eq(text(baselineObservation, "analysisContextId"), "spring-boot-production"),
        "JDT LS Spring observation context mismatch");
    require(numEquals(observation.path("provider").path("runtimeJava"), 25),
        "JDT LS runtime evidence must be recorded");
  }

  /** Acceptance criteria and candidate assessment shape (Python 127-140). */
  private void checkAcceptanceAndCandidates() {
    JsonNode acceptance = documents.acceptance();
    List<JsonNode> criteria = elements(acceptance.path("criteria"));
    Set<String> criteriaIds = idSet(criteria, "id");
    require(criteriaIds.size() == criteria.size(),
        "acceptance criterion ids must be unique");
    require(criteriaIds.containsAll(MANDATORY_CRITERIA),
        "mandatory acceptance criteria are missing");
    Set<String> outcomes = textSet(acceptance.path("assessmentOutcomes"));
    JsonNode candidates = documents.candidates();
    require(fieldTextSet(candidates.path("candidateSet"), "id")
        .equals(Set.of("jdtls", "sootup", "codeql")),
        "candidate set must cover JDT LS, SootUp, and CodeQL");
    require(fieldTextSet(candidates.path("fixtures"), "id")
        .equals(Set.of("java21-quarkus", "fsi-spring-boot")),
        "candidate assessment must name both evaluation fixtures");
    require(fieldTextSet(candidates.path("fixtures"), "framework")
        .equals(Set.of("quarkus", "spring-boot")),
        "candidate assessment must name both frameworks");
    for (JsonNode candidate : elements(candidates.path("candidateSet"))) {
      checkCandidateEntry(candidate, criteriaIds, outcomes);
    }
  }

  /** One candidate's acceptance coverage, outcomes, and gaps (Python 136-140). */
  private void checkCandidateEntry(JsonNode candidate, Set<String> criteriaIds,
      Set<String> outcomes) {
    String id = text(candidate, "id");
    require(fieldNames(candidate.path("acceptance")).equals(criteriaIds),
        id + " must assess every acceptance criterion");
    for (JsonNode value : fieldValues(candidate.path("acceptance"))) {
      require(outcomes.contains(value.isTextual() ? value.asText() : null),
          "invalid acceptance outcome for " + id);
    }
    require(truthy(candidate.path("gaps")), id + " must identify closure gaps");
    for (JsonNode gap : elements(candidate.path("gaps"))) {
      require(truthy(gap.path("estimate")) && truthy(gap.path("closure")),
          id + " gaps need closure estimates");
    }
  }

  /** FSI baseline pin and recorded Spring gaps (Python 142-149). */
  private void checkBaseline() {
    JsonNode baseline = documents.baseline();
    JsonNode baselineObservation = documents.baselineObservation();
    require(eq(text(baseline, "commit"), PINNED_BASELINE_COMMIT),
        "FSI baseline commit must be pinned");
    require(eq(text(baseline, "framework"), "spring-boot"),
        "FSI baseline framework must be recorded");
    require(numEquals(baseline.path("build").path("testsPassed"), 121),
        "FSI baseline test evidence must be recorded");
    require(eq(text(baselineObservation.path("baseline"), "commit"),
        PINNED_BASELINE_COMMIT), "FSI observation must match pinned baseline");
    require(eq(text(baselineObservation.path("baseline"), "framework"), "spring-boot"),
        "FSI observation framework must be recorded");
    List<JsonNode> observations = elements(baselineObservation.path("observations"));
    require(observations.size() >= 7, "FSI baseline observation coverage is incomplete");
    require(anyObservation(observations, "spring-mvc-entrypoint", "confirmed_gap"),
        "Spring MVC entrypoint gap must be recorded");
    require(anyObservation(observations, "spring-bean-callback", "confirmed_gap"),
        "Spring bean callback gap must be recorded");
  }

  /** Capability case vocabulary, uniqueness, and outcomes (Python 151-171). */
  private void checkCases() {
    JsonNode matrix = documents.matrix();
    Set<String> statuses = textSet(matrix.path("statusVocabulary"));
    List<JsonNode> cases = elements(matrix.path("cases"));
    Set<String> caseIds = idSet(cases, "id");
    require(caseIds.size() == cases.size(), "case ids must be unique");
    require(caseIds.containsAll(REQUIRED_CASES), "required capability cases are missing");
    for (JsonNode entry : cases) {
      String id = text(entry, "id");
      require(statuses.contains(text(entry, "classification")),
          "invalid classification for " + id);
      require(truthy(entry.path("expectedOutcome")),
          "missing expected outcome for " + id);
      require(truthy(entry.path("mvpImpact")), "missing MVP impact for " + id);
    }
  }

  /** Synthetic advisory target shape (Python 173-175). */
  private void checkAdvisory() {
    List<JsonNode> symbols = elements(documents.advisory().path("affectedSymbols"));
    require(symbols.size() == 2, "synthetic advisory must contain two target symbols");
    for (JsonNode symbol : symbols) {
      require(eq(text(symbol, "status"), "candidate"),
          "targets must remain candidates until attested");
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

  /** Framework ids referenced by analysis-context entries. */
  private Set<String> frameworkIds(JsonNode contexts) {
    Set<String> ids = new LinkedHashSet<>();
    for (JsonNode context : elements(contexts)) {
      String id = text(context.path("framework"), "id");
      if (id != null) {
        ids.add(id);
      }
    }
    return ids;
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
