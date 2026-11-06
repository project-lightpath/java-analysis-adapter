#!/usr/bin/env python3
"""Validate the manual VPE AnalysisContext -> Java facts handoff."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


DEFAULT_REVISION = "01a18a1b40e151ffcc1d7bef86bf335f87999750"


class ValidationError(RuntimeError):
    pass


def load(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValidationError(message)


def matching(facts: list[dict[str, Any]], predicate) -> list[dict[str, Any]]:
    return [fact for fact in facts if predicate(fact)]


def validate(context: dict[str, Any], facts: dict[str, Any], expected_revision: str) -> dict[str, Any]:
    require(context.get("schemaVersion") == "0.1.0", "unsupported AnalysisContext schema")
    require("status" not in context, "manual context output must be the direct AnalysisContext, not a report wrapper")
    context_id = context.get("id")
    require(context_id, "AnalysisContext id is missing")
    require(context.get("sourceRevision") == expected_revision, "AnalysisContext source revision is not pinned")
    require(context.get("analysisScope") == "production", "manual handoff must use production scope")
    require(context.get("applicationClasses"), "compiled application classes are missing")
    require(context.get("dependencyClasspath"), "dependency classpath is missing")
    require(context.get("build", {}).get("artifact"), "build artifact is missing")

    require(facts.get("artifact") == "SemanticCallFacts", "unexpected facts artifact")
    require(facts.get("contextId") == context_id, "facts/context IDs differ")
    require(facts.get("framework") == context.get("framework"), "facts/context frameworks differ")
    metadata = facts.get("runMetadata", {})
    require(metadata.get("contextId") == context_id, "run metadata context ID differs")
    require(metadata.get("providerName") == "SootUp", "unexpected provider")
    require(metadata.get("providerVersion") == "3.0.1", "unexpected SootUp version")
    require(metadata.get("graphMethodCount", 0) > 0, "provider graph has no methods")
    require(metadata.get("graphCallCount", 0) > 0, "provider graph has no calls")

    provenance = metadata.get("provenance", {})
    for field in (
        "sourceRepository",
        "sourceRevision",
        "sourceFingerprint",
        "generatedSourceFingerprint",
        "dependencyFingerprint",
        "buildFingerprint",
        "toolchainFingerprint",
        "analyzerFingerprint",
        "gitRevision",
        "providerRuntime",
        "profileDigest",
        "preparedAt",
    ):
        require(provenance.get(field), f"provenance field is missing: {field}")
    require(provenance.get("sourceRevision") == expected_revision, "facts source revision is not pinned")
    handoff = metadata.get("handoff", {})
    require(handoff.get("buildFingerprint") == provenance.get("buildFingerprint"), "build fingerprint was not preserved")
    require(handoff.get("buildArtifact") == context.get("build", {}).get("artifact"), "build artifact was not preserved")

    provider_facts = facts.get("providerFacts", [])
    framework_facts = facts.get("frameworkFacts", [])
    all_facts = provider_facts + framework_facts
    require(provider_facts, "provider facts are empty")
    require(framework_facts, "framework facts are empty")
    require(all(fact.get("factSet") == "provider" for fact in provider_facts), "provider fact set was mixed")
    require(all(fact.get("factSet") == "framework" for fact in framework_facts), "framework fact set was mixed")
    require(all(fact.get("contextId") == context_id for fact in all_facts), "fact context provenance is inconsistent")
    certainty_states = {fact.get("certainty") for fact in all_facts}
    require({"definite", "possible"}.issubset(certainty_states), "definite/possible certainty states were not preserved")
    require(certainty_states <= {"definite", "possible", "unresolved"}, "unknown certainty state was emitted")
    for fact in all_facts:
        if fact.get("certainty") == "unresolved":
            require(fact.get("status") == "abstained", f"unresolved fact was emitted: {fact.get('id')}")

    provider_ids = {fact.get("id") for fact in provider_facts}
    framework_ids = {fact.get("id") for fact in framework_facts}
    for link in facts.get("links", []):
        require(link.get("frameworkFactId") in framework_ids, "link points to an unknown framework fact")
        require(link.get("providerFactId") in provider_ids, "link points to an unknown provider fact")
        require(link.get("certaintyPreserved") is True, "fact link did not preserve certainty")

    def has_framework(relationship: str, predicate=lambda _: True) -> list[dict[str, Any]]:
        return matching(
            framework_facts,
            lambda fact: fact.get("relationshipType") == relationship and predicate(fact),
        )

    def has_provider(predicate) -> list[dict[str, Any]]:
        return matching(provider_facts, predicate)

    spring_route_criteria = {
        "httpMethod": "POST",
        "pathTemplate": "/api/transfers",
        "consumes": [],
        "produces": [],
        "headers": [],
        "parameters": [],
        "activeAnalysisContext": context_id,
    }
    spring_route = has_framework(
        "framework_entrypoint",
        lambda fact: fact.get("caller") == "external HTTP POST /api/transfers"
        and fact.get("callee") == "com.redhat.lightwell.controller.TransferController#transfer"
        and fact.get("selectionCriteria") == spring_route_criteria,
    )
    require(spring_route, "exact Spring MVC POST /api/transfers route evidence is missing")
    require(
        all(fact.get("providerCallerAbsence") == "not_evidence_of_absence" for fact in spring_route),
        "route provider-caller absence semantics were not preserved",
    )
    require(
        not has_provider(lambda fact: fact.get("relationshipType") == "framework_entrypoint"),
        "synthetic provider framework-entrypoint facts were emitted",
    )

    evidence = [
        {
            "caseId": "spring-mvc-entrypoint",
            "status": "observed",
            "factIds": [fact["id"] for fact in spring_route],
            "basis": "Spring MVC route annotations produced the exact framework-owned POST /api/transfers entrypoint fact.",
        },
        {
            "caseId": "transfer-controller-to-service",
            "status": "observed",
            "factIds": [
                fact["id"]
                for fact in has_framework(
                    "dependency_injection",
                    lambda fact: "TransferController" in fact.get("caller", "")
                    and "TransactionService" in fact.get("callee", ""),
                )
            ],
            "basis": "Spring constructor injection produced a bounded candidate fact.",
        },
        {
            "caseId": "transaction-service-to-audit",
            "status": "observed",
            "factIds": [
                fact["id"]
                for fact in has_provider(
                    lambda fact: fact.get("relationshipType") == "invokes"
                    and "TransactionService" in fact.get("caller", "")
                    and "AuditService#log" in fact.get("callee", "")
                )
            ],
            "basis": "SootUp provider graph preserved the application service-to-audit call.",
        },
        {
            "caseId": "transaction-service-to-jpa",
            "status": "observed",
            "factIds": [
                fact["id"]
                for fact in has_framework(
                    "generated_implementation",
                    lambda fact: "Repository" in fact.get("callee", "")
                    and fact.get("id", "").startswith("spring-data-generated-"),
                )
            ],
            "basis": "Spring Data repository contract produced a generated implementation fact.",
        },
        {
            "caseId": "spring-bean-callback",
            "status": "observed",
            "factIds": [
                fact["id"]
                for fact in has_framework(
                    "lifecycle_callback",
                    lambda fact: "SecurityConfig#securityFilterChain" in fact.get("callee", ""),
                )
            ],
            "basis": "Spring @Bean configuration produced a lifecycle callback fact.",
        },
        {
            "caseId": "startup-callback",
            "status": "observed",
            "factIds": [
                fact["id"]
                for fact in has_framework(
                    "lifecycle_callback",
                    lambda fact: "DataSeeder#run" in fact.get("callee", ""),
                )
            ],
            "basis": "Spring Boot CommandLineRunner produced a startup callback fact.",
        },
    ]
    for item in evidence:
        require(item["factIds"], f"capability evidence is missing: {item['caseId']}")

    return {
        "schemaVersion": "0.1.0",
        "artifact": "ManualIntegrationCapabilityEvidence",
        "contextId": context_id,
        "framework": facts["framework"],
        "sourceRevision": expected_revision,
        "provider": {"name": metadata["providerName"], "version": metadata["providerVersion"]},
        "coverage": facts.get("coverage", {}),
        "capabilityEvidence": evidence,
        "limitations": handoff.get("limitations", []),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--context", type=Path, required=True)
    parser.add_argument("--facts", type=Path, required=True)
    parser.add_argument("--source-revision", default=DEFAULT_REVISION)
    parser.add_argument("--output", type=Path, default=Path("spike/runs/manual-capability-evidence.json"))
    args = parser.parse_args()
    try:
        result = validate(load(args.context), load(args.facts), args.source_revision)
    except (ValidationError, KeyError, json.JSONDecodeError) as error:
        raise SystemExit(f"manual integration validation failed: {error}") from error
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(
        f"validated manual integration: {result['contextId']}, "
        f"{result['coverage'].get('providerFactCount', 0)} provider facts, "
        f"{result['coverage'].get('frameworkFactCount', 0)} framework facts, "
        f"{len(result['capabilityEvidence'])} capability cases"
    )
    print(args.output)


if __name__ == "__main__":
    main()
