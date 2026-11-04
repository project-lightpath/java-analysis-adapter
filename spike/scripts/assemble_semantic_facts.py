#!/usr/bin/env python3
"""Assemble a spike-only SemanticCallFacts handoff without hiding evidence origins.

This is intentionally not the production adapter. It proves that normalized
SootUp observations and framework-enrichment facts can be carried together
while remaining separate, provenance-bearing relationship sets.
"""

from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
EXPECTED_FACTS_PATH = ROOT / "spike/contracts/expected-call-facts.json"
ENRICHMENT_PATH = ROOT / "spike/results/framework-enrichment-observation.json"
PROVIDER_RESULTS = (
    ("quarkus", ROOT / "spike/results/sootup-3.0.1-java21-quarkus-observation.json"),
    ("spring-boot", ROOT / "spike/results/sootup-3.0.1-fsi-spring-observation.json"),
)


class AssemblyError(RuntimeError):
    """Raised when the bounded handoff cannot preserve the target contract."""


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def relative_path(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def provider_set(framework: str, path: Path) -> dict[str, Any]:
    result = load_json(path)
    if result["provider"]["name"] != "SootUp" or result["provider"]["version"] != "3.0.1":
        raise AssemblyError(f"unexpected SootUp provider metadata for {framework}")
    if result["fixture"]["framework"] != framework:
        raise AssemblyError(f"provider/framework mismatch for {framework}")
    if result["analysisContextId"] not in {"quarkus-production", "spring-boot-production"}:
        raise AssemblyError(f"provider result has no selected AnalysisContext: {relative_path(path)}")

    observations: list[dict[str, Any]] = []
    for observation in result["observations"]:
        item = copy.deepcopy(observation)
        item["factSet"] = "provider"
        item["evidenceOrigin"] = "SootUpNormalizedObservation"
        observations.append(item)

    return {
        "framework": framework,
        "contextId": result["analysisContextId"],
        "provider": copy.deepcopy(result["provider"]),
        "coverage": copy.deepcopy(result["coverage"]),
        "observations": observations,
        "sourceArtifact": relative_path(path),
    }


def framework_set(expected: dict[str, Any], enrichment: dict[str, Any]) -> list[dict[str, Any]]:
    expected_by_id = {item["id"]: item for item in expected["frameworkRelationships"]}
    observations: list[dict[str, Any]] = []
    for observation in enrichment["observations"]:
        fact_id = observation["factId"]
        if fact_id not in expected_by_id:
            raise AssemblyError(f"framework enrichment fact is not in the target contract: {fact_id}")
        item = copy.deepcopy(observation)
        item["factSet"] = "framework"
        item["evidenceOrigin"] = "FrameworkEnrichmentObservation"
        observations.append(item)
    if {item["factId"] for item in observations} != set(expected_by_id):
        raise AssemblyError("framework enrichment does not cover every target framework fact")
    return observations


def links(
    expected: dict[str, Any],
    framework_facts: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    provider_ids = {item["id"] for item in expected["relationships"]}
    result: list[dict[str, Any]] = []
    for fact in framework_facts:
        derived_from = fact.get("derivedFrom")
        if derived_from is None:
            continue
        if derived_from not in provider_ids:
            raise AssemblyError(f"framework fact {fact['factId']} derives from unknown provider fact: {derived_from}")
        result.append(
            {
                "frameworkFactId": fact["factId"],
                "providerContractRelationshipId": derived_from,
                "relation": "enriches_without_replacing",
                "certaintyPreserved": True,
            }
        )
    return result


def assemble(output: Path) -> None:
    expected = load_json(EXPECTED_FACTS_PATH)
    enrichment = load_json(ENRICHMENT_PATH)
    provider_results = [provider_set(framework, path) for framework, path in PROVIDER_RESULTS]
    framework_facts = framework_set(expected, enrichment)
    enrichment_links = links(expected, framework_facts)

    provider_observations = [
        observation
        for result in provider_results
        for observation in result["observations"]
    ]
    certainty_states = {
        observation["certainty"]
        for observation in provider_observations + framework_facts
    }
    if certainty_states != {"definite", "possible", "unresolved"}:
        raise AssemblyError("assembled facts must preserve definite, possible, and unresolved")

    result = {
        "schemaVersion": "0.1.0",
        "artifact": "SemanticCallFactsSpikeObservation",
        "issue": "FLPATH-5000",
        "scope": "spike_only_no_production_adapter",
        "frameworkModel": "spike/contracts/framework-model.json",
        "analysisContext": "spike/contracts/analysis-context.json",
        "contract": {
            "source": "spike/contracts/expected-call-facts.json",
            "providerRelationshipIds": [item["id"] for item in expected["relationships"]],
            "frameworkRelationshipIds": [item["id"] for item in expected["frameworkRelationships"]],
        },
        "relationshipSets": {
            "provider": {
                "description": "SootUp normalized provider observations; no framework facts are inserted here.",
                "results": provider_results,
            },
            "framework": {
                "description": "FrameworkModel-enriched facts; kept separate from provider observations.",
                "sourceArtifact": "spike/results/framework-enrichment-observation.json",
                "observations": framework_facts,
            },
        },
        "links": enrichment_links,
        "mergePolicy": {
            "providerAndFrameworkFactsRemainDistinguishable": True,
            "frameworkFactsNeverReplaceProviderFacts": True,
            "providerCallerAbsenceDoesNotDisproveRoute": True,
            "unresolvedDynamicBehaviorRemainsUnresolved": True,
            "certaintyStates": sorted(certainty_states),
        },
        "coverage": {
            "providerResultCount": len(provider_results),
            "providerObservationCount": len(provider_observations),
            "frameworkFactCount": len(framework_facts),
            "frameworkEmittedFacts": sum(item["status"] == "emitted" for item in framework_facts),
            "frameworkAbstainedFacts": sum(item["status"] == "abstained" for item in framework_facts),
            "linkedFrameworkFacts": len(enrichment_links),
            "frameworks": sorted({result["framework"] for result in provider_results}),
        },
        "limitations": [
            "This assembles a provenance-preserving handoff; it does not add synthetic nodes to the SootUp graph.",
            "Provider observations remain the analyzer's normalized evidence and framework facts remain source-metadata enrichment.",
            "Arc, Spring ApplicationContext, generated proxy identity, and runtime interceptor ordering are not captured by this spike.",
        ],
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(output)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=ROOT / "spike/results/semantic-call-facts-spike-observation.json")
    args = parser.parse_args()
    output = args.output if args.output.is_absolute() else ROOT / args.output
    try:
        assemble(output)
    except (AssemblyError, FileNotFoundError, KeyError) as error:
        raise SystemExit(f"semantic facts assembly failed: {error}") from error


if __name__ == "__main__":
    main()
