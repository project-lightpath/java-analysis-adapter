#!/usr/bin/env python3
"""Validate the checked-in FLPATH-5000 spike contracts without third-party tools."""

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def load(relative: str) -> dict:
    with (ROOT / relative).open(encoding="utf-8") as stream:
        return json.load(stream)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"contract validation failed: {message}")


def main() -> None:
    matrix = load("spike/capability-matrix.json")
    acceptance = load("spike/acceptance-criteria.json")
    candidates = load("spike/candidate-matrix.json")
    baseline = load("spike/baselines/fsi-spring-boot.json")
    advisory = load("spike/contracts/synthetic-advisory.json")
    facts = load("spike/contracts/expected-call-facts.json")
    framework_model = load("spike/contracts/framework-model.json")
    analysis_context = load("spike/contracts/analysis-context.json")
    enrichment = load("spike/results/framework-enrichment-observation.json")
    semantic_facts = load("spike/results/semantic-call-facts-spike-observation.json")
    observation = load("spike/results/jdtls-1.63.0-java21-observation.json")
    baseline_observation = load("spike/results/jdtls-1.63.0-fsi-spring-observation.json")
    sootup_quarkus = load("spike/results/sootup-3.0.1-java21-quarkus-observation.json")
    sootup_fsi = load("spike/results/sootup-3.0.1-fsi-spring-observation.json")
    codeql_quarkus = load("spike/results/codeql-2.27.4-java21-quarkus-observation.json")
    codeql_fsi = load("spike/results/codeql-2.27.4-fsi-spring-observation.json")

    require(matrix["adr"] == "ADR-001", "matrix must cite ADR-001")
    require(matrix["jiraIssue"] == "FLPATH-5000", "matrix must cite FLPATH-5000")
    require(matrix["provider"]["name"] == "Eclipse JDT Language Server", "provider mismatch")
    require(matrix["fixture"]["javaRelease"] == 21, "fixture must target Java 21")
    require(matrix["fixture"]["framework"] == "quarkus", "primary fixture must identify Quarkus")
    require(matrix["fixture"]["productionProfile"] != matrix["fixture"]["testProfile"], "profiles must differ")
    require({"framework_model_contract", "analysis_context_contract", "synthetic_framework_fact_evidence"}.issubset(matrix["requiredEvidence"]), "framework enrichment evidence requirements are missing")
    require(matrix["referenceBaselines"][0]["id"] == "fsi-spring-boot", "FSI baseline must be registered")
    require(matrix["referenceBaselines"][0]["framework"] == "spring-boot", "Spring Boot baseline must identify its framework")
    require({framework["id"] for framework in matrix["frameworks"]} == {"quarkus", "spring-boot"}, "both framework inputs must be registered")
    require({framework["fixtureId"] for framework in matrix["frameworks"]} == {"java21-quarkus", "fsi-spring-boot"}, "framework fixture identities must be unique")
    framework_coverage = {item["framework"]: set(item["caseIds"]) for item in matrix["frameworkCoverage"]}
    require(set(framework_coverage) == {"quarkus", "spring-boot"}, "framework coverage must include Quarkus and Spring Boot")

    certainty_states = {"definite", "possible", "unresolved"}
    require(framework_model["artifact"] == "FrameworkModel", "framework model artifact mismatch")
    require(framework_model["scope"] == "spike_only_no_production_adapter", "framework model must remain spike-scoped")
    require(set(framework_model["certaintyPolicy"]["states"]) == certainty_states, "framework model certainty states changed")
    require(framework_model["certaintyPolicy"]["promotionGuard"], "framework model promotion guard is missing")
    framework_edge_types = {edge_type["id"] for edge_type in framework_model["edgeTypes"]}
    required_edge_types = {
        "framework_entrypoint",
        "dependency_injection",
        "proxy_dispatch",
        "lifecycle_callback",
        "generated_implementation",
    }
    require(required_edge_types.issubset(framework_edge_types), "framework model edge types are incomplete")
    model_frameworks = {framework["id"] for framework in framework_model["frameworks"]}
    require(model_frameworks == {"quarkus", "spring-boot"}, "framework model must cover Quarkus and Spring Boot")
    model_rules = {
        framework["id"]: {rule["id"] for rule in framework["resolutionRules"]}
        for framework in framework_model["frameworks"]
    }
    model_abstentions = {
        framework["id"]: {abstention["id"] for abstention in framework["abstentions"]}
        for framework in framework_model["frameworks"]
    }
    for framework in framework_model["frameworks"]:
        require(framework["fixtureId"] in {"java21-quarkus", "fsi-spring-boot"}, f"unknown fixture in {framework['id']} framework model")
        require(framework["metadataSources"], f"{framework['id']} framework metadata sources are missing")
        require(framework["resolutionRules"], f"{framework['id']} framework resolution rules are missing")
        require(framework["abstentions"], f"{framework['id']} framework abstentions are missing")
        for rule in framework["resolutionRules"]:
            require(rule["edgeType"] in framework_edge_types, f"unknown framework edge type in {framework['id']} resolution rules")
            require(rule["certainty"] in certainty_states, f"invalid framework certainty in {framework['id']} resolution rules")
        for abstention in framework["abstentions"]:
            require(abstention["behavior"] == "unresolved", f"{framework['id']} abstentions must remain unresolved")

    require(analysis_context["artifact"] == "AnalysisContext", "analysis context artifact mismatch")
    require(analysis_context["scope"] == "spike_only_no_production_adapter", "analysis context must remain spike-scoped")
    require(set(analysis_context["certaintyPolicy"]["states"]) == certainty_states, "analysis context certainty states changed")
    context_by_id = {context["id"]: context for context in analysis_context["contexts"]}
    require(set(context_by_id) == {"quarkus-production", "spring-boot-production"}, "analysis context must cover both framework inputs")
    require({context["framework"]["id"] for context in context_by_id.values()} == model_frameworks, "analysis contexts must match framework model")
    for context in context_by_id.values():
        require(all(field in context for field in analysis_context["requiredFields"]), f"{context['id']} is missing a required AnalysisContext field")
        require(context["sourceRoots"], f"{context['id']} source roots are missing")
        require(context["generatedSourceRoots"], f"{context['id']} generated source roots are missing")
        require(context["build"]["selectedProfile"], f"{context['id']} selected build profile is missing")
        require(context["build"]["analysisScope"] == "production", f"{context['id']} analysis scope must be production")
        require(context["frameworkMetadata"], f"{context['id']} framework metadata inputs are missing")
        require(context["provenance"], f"{context['id']} provenance requirements are missing")
    require(context_by_id["quarkus-production"]["fixture"]["id"] == "java21-quarkus", "Quarkus context fixture mismatch")
    require(context_by_id["spring-boot-production"]["fixture"]["id"] == "fsi-spring-boot", "Spring Boot context fixture mismatch")
    require(observation["fixture"]["javaRelease"] == 21, "observation must target Java 21")
    require(observation["fixture"]["framework"] == "quarkus", "Quarkus observation must identify its framework")
    require(observation["frameworkModel"] == "spike/contracts/framework-model.json", "JDT LS Quarkus observation must reference FrameworkModel")
    require(observation["analysisContextId"] == "quarkus-production", "JDT LS Quarkus observation context mismatch")
    require(baseline_observation["frameworkModel"] == "spike/contracts/framework-model.json", "JDT LS Spring observation must reference FrameworkModel")
    require(baseline_observation["analysisContextId"] == "spring-boot-production", "JDT LS Spring observation context mismatch")
    require(observation["provider"]["runtimeJava"] == 25, "JDT LS runtime evidence must be recorded")

    criteria_ids = {criterion["id"] for criterion in acceptance["criteria"]}
    require(len(criteria_ids) == len(acceptance["criteria"]), "acceptance criterion ids must be unique")
    require({"AC-01", "AC-02", "AC-04", "AC-06", "AC-08", "AC-10"}.issubset(criteria_ids), "mandatory acceptance criteria are missing")
    assessment_outcomes = set(acceptance["assessmentOutcomes"])
    candidate_ids = {candidate["id"] for candidate in candidates["candidateSet"]}
    require(candidate_ids == {"jdtls", "sootup", "codeql"}, "candidate set must cover JDT LS, SootUp, and CodeQL")
    fixture_ids = {fixture["id"] for fixture in candidates["fixtures"]}
    require(fixture_ids == {"java21-quarkus", "fsi-spring-boot"}, "candidate assessment must name both evaluation fixtures")
    require({fixture["framework"] for fixture in candidates["fixtures"]} == {"quarkus", "spring-boot"}, "candidate assessment must name both frameworks")
    for candidate in candidates["candidateSet"]:
        require(set(candidate["acceptance"]) == criteria_ids, f"{candidate['id']} must assess every acceptance criterion")
        require(all(value in assessment_outcomes for value in candidate["acceptance"].values()), f"invalid acceptance outcome for {candidate['id']}")
        require(candidate["gaps"], f"{candidate['id']} must identify closure gaps")
        require(all(gap.get("estimate") and gap.get("closure") for gap in candidate["gaps"]), f"{candidate['id']} gaps need closure estimates")

    require(baseline["commit"] == "01a18a1b40e151ffcc1d7bef86bf335f87999750", "FSI baseline commit must be pinned")
    require(baseline["framework"] == "spring-boot", "FSI baseline framework must be recorded")
    require(baseline["build"]["testsPassed"] == 121, "FSI baseline test evidence must be recorded")
    require(baseline_observation["baseline"]["commit"] == baseline["commit"], "FSI observation must match pinned baseline")
    require(baseline_observation["baseline"]["framework"] == "spring-boot", "FSI observation framework must be recorded")
    require(len(baseline_observation["observations"]) >= 7, "FSI baseline observation coverage is incomplete")
    require(any(item["caseId"] == "spring-mvc-entrypoint" and item["classification"] == "confirmed_gap" for item in baseline_observation["observations"]), "Spring MVC entrypoint gap must be recorded")
    require(any(item["caseId"] == "spring-bean-callback" and item["classification"] == "confirmed_gap" for item in baseline_observation["observations"]), "Spring bean callback gap must be recorded")

    statuses = set(matrix["statusVocabulary"])
    cases = matrix["cases"]
    case_ids = [case["id"] for case in cases]
    require(len(case_ids) == len(set(case_ids)), "case ids must be unique")
    required_cases = {
        "direct-call",
        "interface-dispatch",
        "cdi-injection",
        "framework-entrypoint",
        "callback-lambda",
        "service-loader",
        "reflection",
        "dynamic-proxy",
        "generated-source",
        "build-profile",
    }
    require(required_cases.issubset(case_ids), "required capability cases are missing")
    for case in cases:
        require(case["classification"] in statuses, f"invalid classification for {case['id']}")
        require(case.get("expectedOutcome"), f"missing expected outcome for {case['id']}")
        require(case.get("mvpImpact"), f"missing MVP impact for {case['id']}")

    symbols = advisory["affectedSymbols"]
    require(len(symbols) == 2, "synthetic advisory must contain two target symbols")
    require(all(symbol["status"] == "candidate" for symbol in symbols), "targets must remain candidates until attested")

    require(facts["schemaVersion"] == "0.2.0", "expected call-fact contract must include framework enrichment")
    require(facts["frameworkModel"] == "spike/contracts/framework-model.json", "expected facts must reference FrameworkModel")
    require(facts["analysisContext"] == "spike/contracts/analysis-context.json", "expected facts must reference AnalysisContext")
    require(set(facts["fixtures"]) == {"java21-quarkus", "fsi-spring-boot"}, "expected facts must name both framework fixtures")
    relationships = facts["relationships"]
    framework_relationships = facts["frameworkRelationships"]
    require(len(relationships) >= 8, "expected call-fact coverage is incomplete")
    require(len(framework_relationships) >= 10, "framework enrichment fact coverage is incomplete")
    all_relationships = relationships + framework_relationships
    require(len({relationship["id"] for relationship in all_relationships}) == len(all_relationships), "expected relationship ids must be unique")
    certainty_values = {relationship["certainty"] for relationship in all_relationships}
    require(certainty_states.issubset(certainty_values), "facts must preserve certainty states")
    require(all(relationship["certainty"] in certainty_states for relationship in all_relationships), "facts must use only normalized certainty states")
    require({relationship["framework"] for relationship in framework_relationships} == {"quarkus", "spring-boot"}, "framework facts must cover both frameworks")
    require({relationship["contextId"] for relationship in framework_relationships} == set(context_by_id), "framework facts must reference both AnalysisContexts")
    require({relationship["relationshipType"] for relationship in framework_relationships} >= required_edge_types, "framework facts must cover all enrichment edge types")
    require(any(relationship["certainty"] == "unresolved" for relationship in framework_relationships), "framework facts must retain an unresolved dynamic example")
    route_relationships = [relationship for relationship in framework_relationships if relationship["relationshipType"] == "framework_entrypoint"]
    require(route_relationships, "framework facts must include route mappings")
    require(all(relationship["claim"] == "registered_route_mapping" for relationship in route_relationships), "route facts must assert registered mappings")
    require(all(relationship["runtimeSelection"] == "potential_request_match" for relationship in route_relationships), "route facts must describe potential runtime selection")
    require(all(relationship["providerCallerAbsence"] == "not_evidence_of_absence" for relationship in route_relationships), "provider caller absence must not disprove routes")
    route_selection_fields = {"httpMethod", "pathTemplate", "consumes", "produces", "headers", "parameters", "activeAnalysisContext"}
    for relationship in route_relationships:
        selection = relationship["selectionCriteria"]
        require(route_selection_fields.issubset(selection), f"route fact {relationship['id']} is missing runtime selection criteria")
        require(selection["httpMethod"], f"route fact {relationship['id']} needs an HTTP method")
        require(selection["pathTemplate"].startswith("/"), f"route fact {relationship['id']} needs a path template")
        require(selection["activeAnalysisContext"] == relationship["contextId"], f"route fact {relationship['id']} context mismatch")
    for relationship in framework_relationships:
        require(relationship["relationshipType"] in framework_edge_types, f"unknown framework relationship type for {relationship['id']}")
        require(relationship["certainty"] in certainty_states, f"invalid framework certainty for {relationship['id']}")
        require(relationship["sourceEvidence"], f"framework fact {relationship['id']} needs source evidence")
        require(relationship["provenance"]["modelRule"], f"framework fact {relationship['id']} needs model provenance")
        model_rule = relationship["provenance"]["modelRule"]
        require(model_rule in model_rules[relationship["framework"]] or model_rule in model_abstentions[relationship["framework"]], f"unknown model rule {model_rule} for {relationship['id']}")
        if model_rule in model_abstentions[relationship["framework"]]:
            require(relationship["certainty"] == "unresolved", f"abstention fact {relationship['id']} must remain unresolved")
    require(any(relationship["relationshipType"] == "service_loader" for relationship in relationships), "service loader case missing")
    require(any(relationship["relationshipType"] == "reflection" for relationship in relationships), "reflection case missing")

    require(enrichment["artifact"] == "FrameworkEnrichmentObservation", "framework enrichment observation artifact mismatch")
    require(enrichment["frameworkModel"] == "spike/contracts/framework-model.json", "framework enrichment must reference FrameworkModel")
    require(enrichment["analysisContext"] == "spike/contracts/analysis-context.json", "framework enrichment must reference AnalysisContext")
    enrichment_by_id = {item["factId"]: item for item in enrichment["observations"]}
    expected_framework_ids = {relationship["id"] for relationship in framework_relationships}
    require(set(enrichment_by_id) == expected_framework_ids, "framework enrichment must emit every expected framework fact")
    require(enrichment["coverage"]["recordedFacts"] == len(enrichment_by_id), "framework enrichment coverage count is incorrect")
    require(enrichment["coverage"]["emittedFacts"] == sum(item["certainty"] != "unresolved" for item in enrichment["observations"]), "framework emitted fact count is incorrect")
    require(enrichment["coverage"]["abstainedFacts"] == sum(item["certainty"] == "unresolved" for item in enrichment["observations"]), "framework abstention count is incorrect")
    require(set(enrichment["coverage"]["certaintyStates"]) == certainty_states, "framework enrichment must preserve certainty states")
    for fact in enrichment["observations"]:
        expected = next(relationship for relationship in framework_relationships if relationship["id"] == fact["factId"])
        expected_status = "abstained" if expected["certainty"] == "unresolved" else "emitted"
        require(fact["status"] == expected_status and fact["evidenceStatus"] == "verified", f"framework fact {fact['factId']} status/evidence is incorrect")
        require(fact["framework"] == expected["framework"], f"framework fact {fact['factId']} framework mismatch")
        require(fact["contextId"] == expected["contextId"], f"framework fact {fact['factId']} context mismatch")
        require(fact["certainty"] == expected["certainty"], f"framework fact {fact['factId']} certainty changed")
        require(fact["relationshipType"] == expected["relationshipType"], f"framework fact {fact['factId']} relationship type changed")
        require(fact["modelRule"] == expected["provenance"]["modelRule"], f"framework fact {fact['factId']} model provenance changed")
        require(fact["evidence"], f"framework fact {fact['factId']} has no detected source evidence")
        if expected["relationshipType"] == "framework_entrypoint":
            require(fact["selectionCriteria"] == expected["selectionCriteria"], f"framework fact {fact['factId']} route criteria changed")

    require(semantic_facts["artifact"] == "SemanticCallFactsSpikeObservation", "semantic facts spike artifact mismatch")
    require(semantic_facts["scope"] == "spike_only_no_production_adapter", "semantic facts assembly must remain spike-scoped")
    require(semantic_facts["frameworkModel"] == "spike/contracts/framework-model.json", "semantic facts must reference FrameworkModel")
    require(semantic_facts["analysisContext"] == "spike/contracts/analysis-context.json", "semantic facts must reference AnalysisContext")
    relationship_sets = semantic_facts["relationshipSets"]
    require(set(relationship_sets) == {"provider", "framework"}, "semantic facts must keep provider and framework sets separate")
    provider_results = relationship_sets["provider"]["results"]
    framework_facts = relationship_sets["framework"]["observations"]
    require(len(provider_results) == 2, "semantic facts must include Quarkus and Spring provider results")
    require({result["framework"] for result in provider_results} == {"quarkus", "spring-boot"}, "semantic facts provider results must cover both frameworks")
    require(all(result["provider"]["name"] == "SootUp" and result["provider"]["version"] == "3.0.1" for result in provider_results), "semantic facts provider version must be pinned")
    require(all(item["factSet"] == "provider" and item["evidenceOrigin"] == "SootUpNormalizedObservation" for result in provider_results for item in result["observations"]), "provider fact provenance was not preserved")
    require(all(item["factSet"] == "framework" and item["evidenceOrigin"] == "FrameworkEnrichmentObservation" for item in framework_facts), "framework fact provenance was not preserved")
    require({item["factId"] for item in framework_facts} == expected_framework_ids, "semantic facts framework coverage changed")
    require(semantic_facts["coverage"]["providerResultCount"] == 2, "semantic facts provider result count is incorrect")
    require(semantic_facts["coverage"]["frameworkFactCount"] == len(framework_facts), "semantic facts framework count is incorrect")
    require(semantic_facts["coverage"]["frameworkEmittedFacts"] == 10, "semantic facts emitted framework count is incorrect")
    require(semantic_facts["coverage"]["frameworkAbstainedFacts"] == 1, "semantic facts abstention count is incorrect")
    require(all(link["relation"] == "enriches_without_replacing" and link["certaintyPreserved"] for link in semantic_facts["links"]), "semantic facts links must preserve certainty and provenance")
    require(semantic_facts["mergePolicy"]["providerAndFrameworkFactsRemainDistinguishable"], "semantic facts merge policy must preserve set separation")
    require(semantic_facts["mergePolicy"]["frameworkFactsNeverReplaceProviderFacts"], "semantic facts merge policy must prevent silent replacement")
    require(semantic_facts["mergePolicy"]["providerCallerAbsenceDoesNotDisproveRoute"], "semantic facts route absence policy is missing")
    require(semantic_facts["mergePolicy"]["unresolvedDynamicBehaviorRemainsUnresolved"], "semantic facts dynamic abstention policy is missing")
    require(set(semantic_facts["mergePolicy"]["certaintyStates"]) == certainty_states, "semantic facts must preserve certainty states")

    observed_ids = {item["caseId"] for item in observation["observations"]}
    require(required_cases == observed_ids, "observation result must cover every capability case")
    require(any(item["caseId"] == "reflection" and item["classification"] == "confirmed_gap" for item in observation["observations"]), "reflection gap must be recorded")
    require(any(item["caseId"] == "direct-call" and item["classification"] == "existing_support" for item in observation["observations"]), "direct-call support must be recorded")
    require(any(item["caseId"] == "framework-entrypoint" and item["classification"] == "confirmed_gap" for item in observation["observations"]), "framework entrypoint gap must be recorded")

    analyzer_results = [sootup_quarkus, sootup_fsi, codeql_quarkus, codeql_fsi]
    require((ROOT / "spike/scripts/run_static_analysis.py").is_file(), "static-analysis runner is missing")
    require((ROOT / "spike/scripts/normalize_static_analysis.py").is_file(), "static-analysis normalizer is missing")
    analyzer_certainties: set[str] = set()
    for result in analyzer_results:
        require(result["issue"] == "FLPATH-5000", "analyzer result issue mismatch")
        require(result["authority"]["hostPathsOmitted"], "normalized analyzer result must omit host paths")
        require(result["fixture"]["framework"] in {"quarkus", "spring-boot"}, "analyzer result must identify Quarkus or Spring Boot")
        require(result["frameworkModel"] == "spike/contracts/framework-model.json", "normalized analyzer result must reference FrameworkModel")
        require(result["analysisContextId"] in context_by_id, "normalized analyzer result must reference AnalysisContext")
        require(context_by_id[result["analysisContextId"]]["framework"]["id"] == result["fixture"]["framework"], "normalized analyzer result context/framework mismatch")
        analyzer_certainties.update(item["certainty"] for item in result["observations"])
        require(all(item["evidence"] for item in result["observations"]), f"{result['provider']['name']} observations need evidence")

        def contains_host_path(value: object) -> bool:
            if isinstance(value, str):
                return value.startswith("/") or "/Users/" in value or "/private/var/" in value
            if isinstance(value, list):
                return any(contains_host_path(item) for item in value)
            if isinstance(value, dict):
                return any(contains_host_path(item) for item in value.values())
            return False

        require(not contains_host_path(result), f"{result['provider']['name']} result contains a host path")

    require(analyzer_certainties >= {"definite", "possible", "unresolved"}, "analyzer results must preserve certainty states")

    required_quarkus_cases = {case["id"] for case in matrix["cases"]}
    require(framework_coverage["quarkus"] == required_quarkus_cases, "Quarkus framework coverage must match capability cases")
    require({item["caseId"] for item in sootup_quarkus["observations"]} == required_quarkus_cases, "SootUp Quarkus observation coverage is incomplete")
    require({item["caseId"] for item in codeql_quarkus["observations"]} == required_quarkus_cases, "CodeQL Quarkus observation coverage is incomplete")
    required_fsi_cases = {"spring-mvc-entrypoint", "transfer-controller-to-service", "transaction-service-to-audit", "transaction-service-to-jpa", "spring-bean-callback", "startup-callback"}
    require(framework_coverage["spring-boot"] == required_fsi_cases, "Spring Boot framework coverage must match baseline cases")
    require({item["caseId"] for item in sootup_fsi["observations"]} == required_fsi_cases, "SootUp FSI observation coverage is incomplete")
    require({item["caseId"] for item in codeql_fsi["observations"]} == required_fsi_cases, "CodeQL FSI observation coverage is incomplete")

    require(sootup_quarkus["provider"]["version"] == "3.0.1", "SootUp version must be pinned")
    require(sootup_quarkus["coverage"]["fullDependencyRun"] == "passed", "SootUp full Quarkus runtime analysis must pass")
    require(sootup_quarkus["coverage"]["applicationOnlyRun"] == "passed", "SootUp application-only result must pass")
    require(sootup_quarkus["coverage"]["classpathScope"] == "runtime", "SootUp Quarkus classpath scope must be runtime")
    require(sootup_quarkus["coverage"]["dependencyClasspathEntries"] == 118, "SootUp Quarkus runtime classpath measurement changed unexpectedly")
    require(not any("IllegalArgumentException" in limitation for limitation in sootup_quarkus["limitations"]), "SootUp Quarkus bytecode failure must not be reported after the runtime-scope fix")
    require(sootup_fsi["coverage"]["dependencyClasspathEntries"] == 104, "SootUp FSI runtime classpath measurement changed unexpectedly")
    require(sootup_fsi["measurements"]["analyses"][0]["methodCount"] == 6224, "SootUp FSI CHA method measurement changed unexpectedly")
    require(sootup_fsi["measurements"]["analyses"][0]["callCount"] == 7853, "SootUp FSI CHA call measurement changed unexpectedly")

    for result in (codeql_quarkus, codeql_fsi):
        require(result["provider"]["version"] == "2.27.4", "CodeQL version must be pinned")
        require(result["provider"]["javaPackVersion"] == "9.3.4", "CodeQL Java pack must be pinned")
        require(result["coverage"]["buildMode"] == "none", "CodeQL build-mode limitation must be explicit")
        require(result["coverage"]["rawFacts"] == result["coverage"]["productionFacts"] + result["coverage"]["testFactsFiltered"], "CodeQL fact scope counts must balance")
        require(result["coverage"]["testFactsFiltered"] > 0, "CodeQL test-source filtering evidence is missing")
    require(codeql_quarkus["coverage"]["rawFacts"] == 64, "CodeQL Quarkus measurement changed unexpectedly")
    require(codeql_fsi["coverage"]["rawFacts"] == 2356, "CodeQL FSI measurement changed unexpectedly")
    require({result["fixture"]["framework"] for result in analyzer_results} == {"quarkus", "spring-boot"}, "analyzer results must cover both frameworks")

    for candidate_id in ("sootup", "codeql"):
        candidate = next(item for item in candidates["candidateSet"] if item["id"] == candidate_id)
        require("unverified" not in candidate["acceptance"].values(), f"{candidate_id} still has an unverified acceptance result after its evidence run")

    print(f"validated {len(criteria_ids)} acceptance criteria, {len(candidate_ids)} candidates, {len(cases)} capability cases, {len(all_relationships)} expected relationships, and {len(observed_ids)} observed results")


if __name__ == "__main__":
    main()
