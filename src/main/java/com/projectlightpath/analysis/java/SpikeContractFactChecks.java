package com.projectlightpath.analysis.java;

import static com.projectlightpath.analysis.java.SpikeContractValidator.CERTAINTY_STATES;
import static com.projectlightpath.analysis.java.SpikeContractValidator.REQUIRED_EDGE_TYPES;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.byField;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.elements;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.eq;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.fieldNames;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.fieldTextSet;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.findByText;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.idSet;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.numEquals;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.require;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.text;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.textSet;
import static com.projectlightpath.analysis.java.SpikeContractSemantics.truthy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fact-level rules of the spike-contract gate (#45 step 2): the expected call-fact
 * contract (routes, provenance), the framework enrichment observation, and the semantic
 * facts assembly. Ported from the corresponding blocks of
 * spike/scripts/validate_spike.py.
 */
final class SpikeContractFactChecks {
  private final SpikeContractValidator.Documents documents;

  SpikeContractFactChecks(SpikeContractValidator.Documents documents) {
    this.documents = documents;
  }

  /** Runs every fact-level rule in the Python gate's order. */
  void checkAll() {
    checkExpectedFacts();
    checkEnrichment();
    checkSemanticFacts();
  }

  /** Expected call-fact contract, routes, and provenance (Python 177-216). */
  private void checkExpectedFacts() {
    JsonNode facts = documents.facts();
    require(eq(text(facts, "schemaVersion"), "0.2.0"),
        "expected call-fact contract must include framework enrichment");
    require(eq(text(facts, "frameworkModel"), "spike/contracts/framework-model.json"),
        "expected facts must reference FrameworkModel");
    require(eq(text(facts, "analysisContext"), "spike/contracts/analysis-context.json"),
        "expected facts must reference AnalysisContext");
    require(textSet(facts.path("fixtures"))
        .equals(Set.of("java21-quarkus", "fsi-spring-boot")),
        "expected facts must name both framework fixtures");
    List<JsonNode> relationships = elements(facts.path("relationships"));
    List<JsonNode> frameworkRelationships = elements(facts.path("frameworkRelationships"));
    require(relationships.size() >= 8, "expected call-fact coverage is incomplete");
    require(frameworkRelationships.size() >= 10,
        "framework enrichment fact coverage is incomplete");
    List<JsonNode> allRelationships = new ArrayList<>(relationships);
    allRelationships.addAll(frameworkRelationships);
    require(idSet(allRelationships, "id").size() == allRelationships.size(),
        "expected relationship ids must be unique");
    Set<String> certaintyValues = idSet(allRelationships, "certainty");
    require(certaintyValues.containsAll(CERTAINTY_STATES),
        "facts must preserve certainty states");
    require(CERTAINTY_STATES.containsAll(certaintyValues),
        "facts must use only normalized certainty states");
    require(idSet(frameworkRelationships, "framework")
        .equals(Set.of("quarkus", "spring-boot")),
        "framework facts must cover both frameworks");
    Map<String, JsonNode> contextById = byField(
        elements(documents.analysisContext().path("contexts")), "id");
    require(idSet(frameworkRelationships, "contextId").equals(contextById.keySet()),
        "framework facts must reference both AnalysisContexts");
    require(idSet(frameworkRelationships, "relationshipType")
        .containsAll(REQUIRED_EDGE_TYPES),
        "framework facts must cover all enrichment edge types");
    require(idSet(frameworkRelationships, "certainty").contains("unresolved"),
        "framework facts must retain an unresolved dynamic example");
    checkRouteRelationships(frameworkRelationships);
    checkFrameworkRelationshipProvenance(frameworkRelationships);
    require(anyRelationshipType(relationships, "service_loader"),
        "service loader case missing");
    require(anyRelationshipType(relationships, "reflection"), "reflection case missing");
  }

  /** Route facts: claims, runtime selection semantics, criteria (Python 194-205). */
  private void checkRouteRelationships(List<JsonNode> frameworkRelationships) {
    List<JsonNode> routes = new ArrayList<>();
    for (JsonNode relationship : frameworkRelationships) {
      if (eq(text(relationship, "relationshipType"), "framework_entrypoint")) {
        routes.add(relationship);
      }
    }
    require(!routes.isEmpty(), "framework facts must include route mappings");
    for (JsonNode relationship : routes) {
      require(eq(text(relationship, "claim"), "registered_route_mapping"),
          "route facts must assert registered mappings");
      require(eq(text(relationship, "runtimeSelection"), "potential_request_match"),
          "route facts must describe potential runtime selection");
      require(eq(text(relationship, "providerCallerAbsence"),
          "not_evidence_of_absence"),
          "provider caller absence must not disprove routes");
    }
    for (JsonNode relationship : routes) {
      checkRouteSelection(relationship);
    }
  }

  /** One route fact's runtime selection criteria (Python 199-205). */
  private void checkRouteSelection(JsonNode relationship) {
    String id = text(relationship, "id");
    JsonNode selection = relationship.path("selectionCriteria");
    require(selection.isObject()
        && fieldNames(selection).containsAll(SpikeContractValidator.ROUTE_SELECTION_FIELDS),
        "route fact " + id + " is missing runtime selection criteria");
    require(truthy(selection.path("httpMethod")),
        "route fact " + id + " needs an HTTP method");
    String pathTemplate = text(selection, "pathTemplate");
    require(pathTemplate != null && pathTemplate.startsWith("/"),
        "route fact " + id + " needs a path template");
    require(eq(text(selection, "activeAnalysisContext"),
        text(relationship, "contextId")), "route fact " + id + " context mismatch");
  }

  /** Framework facts must cite known model rules or abstentions (Python 206-214). */
  private void checkFrameworkRelationshipProvenance(List<JsonNode> frameworkRelationships) {
    Set<String> edgeTypes = fieldTextSet(
        documents.frameworkModel().path("edgeTypes"), "id");
    Map<String, Set<String>> modelRules = ruleSets("resolutionRules");
    Map<String, Set<String>> modelAbstentions = ruleSets("abstentions");
    for (JsonNode relationship : frameworkRelationships) {
      String id = text(relationship, "id");
      require(edgeTypes.contains(text(relationship, "relationshipType")),
          "unknown framework relationship type for " + id);
      require(CERTAINTY_STATES.contains(text(relationship, "certainty")),
          "invalid framework certainty for " + id);
      require(truthy(relationship.path("sourceEvidence")),
          "framework fact " + id + " needs source evidence");
      require(truthy(relationship.path("provenance").path("modelRule")),
          "framework fact " + id + " needs model provenance");
      String modelRule = text(relationship.path("provenance"), "modelRule");
      Set<String> rules = modelRules.getOrDefault(text(relationship, "framework"),
          Set.of());
      Set<String> abstentions = modelAbstentions.getOrDefault(
          text(relationship, "framework"), Set.of());
      require(rules.contains(modelRule) || abstentions.contains(modelRule),
          "unknown model rule " + modelRule + " for " + id);
      if (abstentions.contains(modelRule)) {
        require(eq(text(relationship, "certainty"), "unresolved"),
            "abstention fact " + id + " must remain unresolved");
      }
    }
  }

  /** Framework enrichment observation mirrors the expected facts (Python 218-239). */
  private void checkEnrichment() {
    JsonNode enrichment = documents.enrichment();
    require(eq(text(enrichment, "artifact"), "FrameworkEnrichmentObservation"),
        "framework enrichment observation artifact mismatch");
    require(eq(text(enrichment, "frameworkModel"),
        "spike/contracts/framework-model.json"),
        "framework enrichment must reference FrameworkModel");
    require(eq(text(enrichment, "analysisContext"),
        "spike/contracts/analysis-context.json"),
        "framework enrichment must reference AnalysisContext");
    List<JsonNode> observations = elements(enrichment.path("observations"));
    Map<String, JsonNode> enrichmentById = byField(observations, "factId");
    require(enrichmentById.keySet().equals(expectedFrameworkIds()),
        "framework enrichment must emit every expected framework fact");
    JsonNode coverage = enrichment.path("coverage");
    require(numEquals(coverage.path("recordedFacts"), enrichmentById.size()),
        "framework enrichment coverage count is incorrect");
    long emitted = 0;
    long abstained = 0;
    for (JsonNode fact : observations) {
      if (eq(text(fact, "certainty"), "unresolved")) {
        abstained++;
      } else {
        emitted++;
      }
    }
    require(numEquals(coverage.path("emittedFacts"), emitted),
        "framework emitted fact count is incorrect");
    require(numEquals(coverage.path("abstainedFacts"), abstained),
        "framework abstention count is incorrect");
    require(textSet(coverage.path("certaintyStates")).equals(CERTAINTY_STATES),
        "framework enrichment must preserve certainty states");
    List<JsonNode> frameworkRelationships = elements(
        documents.facts().path("frameworkRelationships"));
    for (JsonNode fact : observations) {
      checkEnrichmentFact(fact, findByText(frameworkRelationships, "id",
          text(fact, "factId")));
    }
  }

  /** One enrichment observation against its expected fact (Python 228-239). */
  private void checkEnrichmentFact(JsonNode fact, JsonNode expected) {
    String factId = text(fact, "factId");
    require(expected != null,
        "framework enrichment must emit every expected framework fact");
    String expectedStatus = eq(text(expected, "certainty"), "unresolved") ? "abstained"
        : "emitted";
    require(eq(text(fact, "status"), expectedStatus)
        && eq(text(fact, "evidenceStatus"), "verified"),
        "framework fact " + factId + " status/evidence is incorrect");
    require(eq(text(fact, "framework"), text(expected, "framework")),
        "framework fact " + factId + " framework mismatch");
    require(eq(text(fact, "contextId"), text(expected, "contextId")),
        "framework fact " + factId + " context mismatch");
    require(eq(text(fact, "certainty"), text(expected, "certainty")),
        "framework fact " + factId + " certainty changed");
    require(eq(text(fact, "relationshipType"), text(expected, "relationshipType")),
        "framework fact " + factId + " relationship type changed");
    require(eq(text(fact, "modelRule"), text(expected.path("provenance"), "modelRule")),
        "framework fact " + factId + " model provenance changed");
    require(truthy(fact.path("evidence")),
        "framework fact " + factId + " has no detected source evidence");
    if (eq(text(expected, "relationshipType"), "framework_entrypoint")) {
      require(fact.path("selectionCriteria").equals(expected.path("selectionCriteria")),
          "framework fact " + factId + " route criteria changed");
    }
  }

  /** Semantic facts assembly keeps provider and framework sets separate (Python 241-264). */
  private void checkSemanticFacts() {
    JsonNode semanticFacts = documents.semanticFacts();
    require(eq(text(semanticFacts, "artifact"), "SemanticCallFactsSpikeObservation"),
        "semantic facts spike artifact mismatch");
    require(eq(text(semanticFacts, "scope"), "spike_only_no_production_adapter"),
        "semantic facts assembly must remain spike-scoped");
    require(eq(text(semanticFacts, "frameworkModel"),
        "spike/contracts/framework-model.json"),
        "semantic facts must reference FrameworkModel");
    require(eq(text(semanticFacts, "analysisContext"),
        "spike/contracts/analysis-context.json"),
        "semantic facts must reference AnalysisContext");
    JsonNode relationshipSets = semanticFacts.path("relationshipSets");
    require(fieldNames(relationshipSets).equals(Set.of("provider", "framework")),
        "semantic facts must keep provider and framework sets separate");
    List<JsonNode> providerResults = elements(
        relationshipSets.path("provider").path("results"));
    List<JsonNode> frameworkFacts = elements(
        relationshipSets.path("framework").path("observations"));
    require(providerResults.size() == 2,
        "semantic facts must include Quarkus and Spring provider results");
    require(idSet(providerResults, "framework")
        .equals(Set.of("quarkus", "spring-boot")),
        "semantic facts provider results must cover both frameworks");
    for (JsonNode result : providerResults) {
      checkProviderResult(result);
    }
    for (JsonNode item : frameworkFacts) {
      require(eq(text(item, "factSet"), "framework")
          && eq(text(item, "evidenceOrigin"), "FrameworkEnrichmentObservation"),
          "framework fact provenance was not preserved");
    }
    require(idSet(frameworkFacts, "factId").equals(expectedFrameworkIds()),
        "semantic facts framework coverage changed");
    JsonNode coverage = semanticFacts.path("coverage");
    require(numEquals(coverage.path("providerResultCount"), 2),
        "semantic facts provider result count is incorrect");
    require(numEquals(coverage.path("frameworkFactCount"), frameworkFacts.size()),
        "semantic facts framework count is incorrect");
    require(numEquals(coverage.path("frameworkEmittedFacts"), 10),
        "semantic facts emitted framework count is incorrect");
    require(numEquals(coverage.path("frameworkAbstainedFacts"), 1),
        "semantic facts abstention count is incorrect");
    for (JsonNode link : elements(semanticFacts.path("links"))) {
      require(eq(text(link, "relation"), "enriches_without_replacing")
          && truthy(link.path("certaintyPreserved")),
          "semantic facts links must preserve certainty and provenance");
    }
    checkMergePolicy(semanticFacts.path("mergePolicy"));
  }

  /** One provider result's pinned version and provenance (Python 251-253). */
  private void checkProviderResult(JsonNode result) {
    require(eq(text(result.path("provider"), "name"), "SootUp")
        && eq(text(result.path("provider"), "version"), "3.0.1"),
        "semantic facts provider version must be pinned");
    for (JsonNode item : elements(result.path("observations"))) {
      require(eq(text(item, "factSet"), "provider")
          && eq(text(item, "evidenceOrigin"), "SootUpNormalizedObservation"),
          "provider fact provenance was not preserved");
    }
  }

  /** Semantic facts merge policy flags (Python 260-264). */
  private void checkMergePolicy(JsonNode mergePolicy) {
    require(truthy(mergePolicy.path("providerAndFrameworkFactsRemainDistinguishable")),
        "semantic facts merge policy must preserve set separation");
    require(truthy(mergePolicy.path("frameworkFactsNeverReplaceProviderFacts")),
        "semantic facts merge policy must prevent silent replacement");
    require(truthy(mergePolicy.path("providerCallerAbsenceDoesNotDisproveRoute")),
        "semantic facts route absence policy is missing");
    require(truthy(mergePolicy.path("unresolvedDynamicBehaviorRemainsUnresolved")),
        "semantic facts dynamic abstention policy is missing");
    require(textSet(mergePolicy.path("certaintyStates")).equals(CERTAINTY_STATES),
        "semantic facts must preserve certainty states");
  }

  /** The expected framework fact ids from the call-fact contract. */
  private Set<String> expectedFrameworkIds() {
    return idSet(elements(documents.facts().path("frameworkRelationships")), "id");
  }

  /** True when any relationship carries the type. */
  private boolean anyRelationshipType(List<JsonNode> relationships, String type) {
    for (JsonNode relationship : relationships) {
      if (eq(text(relationship, "relationshipType"), type)) {
        return true;
      }
    }
    return false;
  }

  /** Per-framework id sets for rules or abstentions. */
  private Map<String, Set<String>> ruleSets(String field) {
    Map<String, Set<String>> rules = new LinkedHashMap<>();
    for (JsonNode framework : elements(documents.frameworkModel().path("frameworks"))) {
      rules.put(text(framework, "id"),
          idSet(elements(framework.path(field)), "id"));
    }
    return rules;
  }
}
