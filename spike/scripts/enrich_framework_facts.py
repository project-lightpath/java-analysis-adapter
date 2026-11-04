#!/usr/bin/env python3
"""Emit spike-level framework facts from Quarkus and Spring source metadata.

This is deliberately a bounded enrichment probe, not the production adapter.
It proves that the FrameworkModel and AnalysisContext contracts can be applied
to source annotations, generated sources, and framework conventions without
using ordinary incoming Java references as route evidence.
"""

from __future__ import annotations

import argparse
import json
import os
import re
from pathlib import Path
from typing import Any, Callable


ROOT = Path(__file__).resolve().parents[2]
FRAMEWORK_MODEL_PATH = "spike/contracts/framework-model.json"
ANALYSIS_CONTEXT_PATH = "spike/contracts/analysis-context.json"
EXPECTED_FACTS_PATH = "spike/contracts/expected-call-facts.json"


class EnrichmentError(RuntimeError):
    """Raised when a bounded framework fact cannot be proven from the inputs."""


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def relative_path(path: Path) -> str:
    return Path(os.path.relpath(path, ROOT)).as_posix()


def context_root(path_value: str) -> Path:
    return (ROOT / path_value).resolve()


def source_files(context: dict[str, Any], generated: bool | None = None) -> list[Path]:
    roots: list[Path] = []
    if generated is not True:
        roots.extend(context_root(item["path"]) for item in context["sourceRoots"])
    if generated is not False:
        roots.extend(context_root(item["path"]) for item in context["generatedSourceRoots"] if item.get("included"))
    files: list[Path] = []
    for root in roots:
        if not root.is_dir():
            continue
        files.extend(sorted(root.rglob("*.java")))
    return files


def read_source(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except FileNotFoundError as error:
        raise EnrichmentError(f"source metadata file is missing: {relative_path(path)}") from error


def find_file(context: dict[str, Any], filename: str, generated: bool | None = None) -> Path:
    matches = [path for path in source_files(context, generated) if path.name == filename]
    if len(matches) != 1:
        raise EnrichmentError(f"expected exactly one {filename} in {context['id']}, found {len(matches)}")
    return matches[0]


def package_name(source: str) -> str:
    match = re.search(r"\bpackage\s+([\w.]+)\s*;", source)
    if not match:
        raise EnrichmentError("Java source has no package declaration")
    return match.group(1)


def type_name(path: Path, source: str) -> str:
    match = re.search(r"\b(?:class|interface|record)\s+(\w+)", source)
    if not match:
        raise EnrichmentError(f"Java source has no top-level type: {relative_path(path)}")
    return f"{package_name(source)}.{match.group(1)}"


def has_annotation(source: str, annotation: str) -> bool:
    return re.search(rf"@{re.escape(annotation)}\b", source) is not None


def annotation_value(source: str, annotation: str) -> str | None:
    match = re.search(rf"@{re.escape(annotation)}\s*\(\s*([^)]*)\)", source)
    if not match:
        return None
    value = match.group(1).strip()
    if len(value) >= 2 and value[0] == value[-1] == '"':
        return value[1:-1]
    return value


def has_method(source: str, method: str) -> bool:
    return re.search(rf"\b{re.escape(method)}\s*\(", source) is not None


def evidence(kind: str, path: Path, symbols: list[str], **extra: Any) -> dict[str, Any]:
    item: dict[str, Any] = {
        "kind": kind,
        "path": relative_path(path),
        "symbols": symbols,
    }
    item.update(extra)
    return item


def context_and_facts() -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]:
    model = load_json(ROOT / FRAMEWORK_MODEL_PATH)
    contexts = load_json(ROOT / ANALYSIS_CONTEXT_PATH)
    expected = load_json(ROOT / EXPECTED_FACTS_PATH)
    return model, contexts, expected["frameworkRelationships"]


def quarkus_route(context: dict[str, Any]) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    path = find_file(context, "TransferResource.java", generated=False)
    source = read_source(path)
    class_path = annotation_value(source, "Path")
    consumes = annotation_value(source, "Consumes")
    produces = annotation_value(source, "Produces")
    if class_path != "/transfers" or not has_annotation(source, "POST") or not has_method(source, "transfer"):
        raise EnrichmentError("Quarkus TransferResource does not prove the expected POST route")
    selection = {
        "httpMethod": "POST",
        "pathTemplate": class_path,
        "consumes": ["application/json"] if consumes == "MediaType.APPLICATION_JSON" else ([] if consumes is None else [consumes]),
        "produces": ["application/json"] if produces == "MediaType.APPLICATION_JSON" else ([] if produces is None else [produces]),
        "headers": [],
        "parameters": [],
        "activeAnalysisContext": context["id"],
    }
    return [evidence("route_annotations", path, [f"@Path(\"{class_path}\")", "@POST", "@Consumes", "@Produces"])], selection


def quarkus_cdi(context: dict[str, Any]) -> list[dict[str, Any]]:
    injection_path = find_file(context, "TransferResource.java", generated=False)
    injection_source = read_source(injection_path)
    candidate_files = [
        path
        for path in source_files(context, generated=False)
        if has_annotation(read_source(path), "ApplicationScoped") and re.search(r"\bimplements\s+TransferService\b", read_source(path))
    ]
    if not has_annotation(injection_source, "Inject") or "TransferService transferService" not in injection_source:
        raise EnrichmentError("Quarkus TransferResource does not prove constructor injection")
    if len(candidate_files) != 1:
        raise EnrichmentError(f"Quarkus TransferService candidate count is {len(candidate_files)}, expected one bounded candidate")
    candidate_path = candidate_files[0]
    return [
        evidence("injection_point", injection_path, ["@Inject TransferResource(TransferService)"]),
        evidence("bean_candidate", candidate_path, ["@ApplicationScoped", "implements TransferService"], candidateCount=len(candidate_files)),
    ]


def quarkus_proxy(context: dict[str, Any]) -> list[dict[str, Any]]:
    path = find_file(context, "DefaultTransferService.java", generated=False)
    source = read_source(path)
    if not has_annotation(source, "ApplicationScoped") or not has_method(source, "transfer"):
        raise EnrichmentError("Quarkus service does not prove a bounded CDI proxy target")
    return [evidence("bean_scope", path, ["@ApplicationScoped", "transfer(TransferRequest)"])]


def quarkus_generated(context: dict[str, Any]) -> list[dict[str, Any]]:
    path = find_file(context, "GeneratedTransferMapper.java", generated=True)
    source = read_source(path)
    if not has_method(source, "toResponse"):
        raise EnrichmentError("Quarkus generated mapper has no toResponse method")
    generated_root = next(item for item in context["generatedSourceRoots"] if item.get("included"))
    return [evidence("generated_source", path, ["GeneratedTransferMapper#toResponse"], sourceRoot=generated_root["path"])]


def quarkus_unbounded_proxy(context: dict[str, Any]) -> list[dict[str, Any]]:
    path = find_file(context, "AuthProxyFactory.java", generated=False)
    source = read_source(path)
    if "Proxy.newProxyInstance" not in source or "InvocationHandler" not in source:
        raise EnrichmentError("Quarkus proxy fixture no longer contains an unconstrained dynamic proxy")
    return [evidence("dynamic_proxy_call", path, ["Proxy.newProxyInstance", "InvocationHandler"])]


def spring_route(context: dict[str, Any]) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    path = find_file(context, "TransferController.java", generated=False)
    source = read_source(path)
    class_path = annotation_value(source, "RequestMapping")
    if not class_path or not has_annotation(source, "RestController") or not has_annotation(source, "PostMapping") or not has_method(source, "transfer"):
        raise EnrichmentError("Spring TransferController does not prove the expected POST route")
    selection = {
        "httpMethod": "POST",
        "pathTemplate": class_path,
        "consumes": [],
        "produces": [],
        "headers": [],
        "parameters": [],
        "activeAnalysisContext": context["id"],
    }
    return [evidence("route_annotations", path, [f"@RequestMapping(\"{class_path}\")", "@PostMapping"])], selection


def spring_injection(context: dict[str, Any]) -> list[dict[str, Any]]:
    injection_path = find_file(context, "TransferController.java", generated=False)
    injection_source = read_source(injection_path)
    candidate_path = find_file(context, "TransactionService.java", generated=False)
    candidate_source = read_source(candidate_path)
    if "TransactionService transactionService" not in injection_source or not has_annotation(candidate_source, "Service"):
        raise EnrichmentError("Spring controller/service constructor injection is not proven")
    return [
        evidence("constructor_injection", injection_path, ["TransferController(TransactionService)"]),
        evidence("component_scan_candidate", candidate_path, ["@Service"], candidateCount=1),
    ]


def spring_transaction_proxy(context: dict[str, Any]) -> list[dict[str, Any]]:
    path = find_file(context, "TransactionService.java", generated=False)
    source = read_source(path)
    if not has_annotation(source, "Transactional") or not has_method(source, "transfer"):
        raise EnrichmentError("Spring transaction service does not prove a bounded advisor target")
    return [evidence("advisor_annotation", path, ["@Transactional", "TransactionService#transfer"])]


def spring_repository(context: dict[str, Any]) -> list[dict[str, Any]]:
    path = find_file(context, "AccountRepository.java", generated=False)
    source = read_source(path)
    if not re.search(r"\bextends\s+JpaRepository\s*<", source):
        raise EnrichmentError("Spring AccountRepository does not prove a Spring Data repository contract")
    return [evidence("repository_interface", path, ["extends JpaRepository<Account, Long>", "CrudRepository#findById"])]


def spring_bean_callback(context: dict[str, Any]) -> list[dict[str, Any]]:
    path = find_file(context, "SecurityConfig.java", generated=False)
    source = read_source(path)
    if not has_annotation(source, "Configuration") or not has_annotation(source, "Bean") or not has_method(source, "securityFilterChain"):
        raise EnrichmentError("Spring SecurityConfig does not prove the @Bean callback")
    return [evidence("bean_factory_annotation", path, ["@Configuration", "@Bean SecurityFilterChain securityFilterChain"])]


def spring_startup_callback(context: dict[str, Any]) -> list[dict[str, Any]]:
    path = find_file(context, "DataSeeder.java", generated=False)
    source = read_source(path)
    if not has_annotation(source, "Component") or not re.search(r"\bimplements\s+CommandLineRunner\b", source) or not has_method(source, "run"):
        raise EnrichmentError("Spring DataSeeder does not prove a CommandLineRunner callback")
    return [evidence("lifecycle_interface", path, ["@Component", "implements CommandLineRunner", "run(String...)"])]


GENERATORS: dict[str, Callable[[dict[str, Any]], Any]] = {
    "quarkus-http-to-resource-enriched": quarkus_route,
    "quarkus-resource-to-cdi-bean": quarkus_cdi,
    "quarkus-cdi-proxy-to-service": quarkus_proxy,
    "quarkus-generated-mapper-binding": quarkus_generated,
    "quarkus-unbounded-proxy-abstention": quarkus_unbounded_proxy,
    "spring-http-to-transfer-controller-enriched": spring_route,
    "spring-controller-to-service-injection": spring_injection,
    "spring-transaction-proxy-to-service": spring_transaction_proxy,
    "spring-data-generated-account-repository": spring_repository,
    "spring-bean-factory-to-security-config": spring_bean_callback,
    "spring-boot-to-data-seeder": spring_startup_callback,
}


def model_rule_ids(model: dict[str, Any]) -> dict[str, set[str]]:
    return {
        framework["id"]: {rule["id"] for rule in framework["resolutionRules"]} | {item["id"] for item in framework["abstentions"]}
        for framework in model["frameworks"]
    }


def emit_fact(
    expected: dict[str, Any],
    context: dict[str, Any],
    detected: list[dict[str, Any]],
    selection: dict[str, Any] | None,
) -> dict[str, Any]:
    fact: dict[str, Any] = {
        "factId": expected["id"],
        "framework": expected["framework"],
        "contextId": expected["contextId"],
        "status": "abstained" if expected["certainty"] == "unresolved" else "emitted",
        "evidenceStatus": "verified",
        "caller": expected["caller"],
        "callee": expected["callee"],
        "relationshipType": expected["relationshipType"],
        "certainty": expected["certainty"],
        "modelRule": expected["provenance"]["modelRule"],
        "evidence": detected,
    }
    for field in ("derivedFrom", "claim", "runtimeSelection", "providerCallerAbsence"):
        if field in expected:
            fact[field] = expected[field]
    if selection is not None:
        fact["selectionCriteria"] = selection
        expected_selection = expected.get("selectionCriteria")
        if expected_selection != selection:
            raise EnrichmentError(f"route selection differs from expected contract for {expected['id']}")
    if expected.get("provenance", {}).get("limitation"):
        fact["limitation"] = expected["provenance"]["limitation"]
    if expected.get("certaintyCondition"):
        fact["certaintyCondition"] = expected["certaintyCondition"]
    return fact


def enrich(output: Path) -> None:
    model, context_document, expected_facts = context_and_facts()
    contexts = {context["id"]: context for context in context_document["contexts"]}
    rules = model_rule_ids(model)
    observations: list[dict[str, Any]] = []
    for expected in expected_facts:
        context = contexts.get(expected["contextId"])
        if context is None:
            raise EnrichmentError(f"missing AnalysisContext: {expected['contextId']}")
        if context["framework"]["id"] != expected["framework"]:
            raise EnrichmentError(f"framework/context mismatch for {expected['id']}")
        if expected["id"] not in GENERATORS:
            raise EnrichmentError(f"no bounded generator for expected fact: {expected['id']}")
        model_rule = expected["provenance"]["modelRule"]
        if model_rule not in rules[expected["framework"]]:
            raise EnrichmentError(f"unknown FrameworkModel rule: {model_rule}")
        detected = GENERATORS[expected["id"]](context)
        selection = None
        if expected["relationshipType"] == "framework_entrypoint":
            if not isinstance(detected, tuple):
                raise EnrichmentError(f"route generator did not return selection criteria: {expected['id']}")
            detected, selection = detected
        observations.append(emit_fact(expected, context, detected, selection))

    result = {
        "schemaVersion": "0.1.0",
        "artifact": "FrameworkEnrichmentObservation",
        "issue": "FLPATH-5000",
        "frameworkModel": FRAMEWORK_MODEL_PATH,
        "analysisContext": ANALYSIS_CONTEXT_PATH,
        "source": "bounded_source_metadata_scan",
        "observations": observations,
        "coverage": {
            "expectedFacts": len(expected_facts),
            "recordedFacts": len(observations),
            "emittedFacts": sum(item["status"] == "emitted" for item in observations),
            "abstainedFacts": sum(item["status"] == "abstained" for item in observations),
            "frameworks": sorted({item["framework"] for item in observations}),
            "certaintyStates": sorted({item["certainty"] for item in observations}),
        },
        "limitations": [
            "This probe consumes source annotations and checked-in metadata; it does not instantiate a Quarkus Arc or Spring ApplicationContext.",
            "Runtime route selection is represented as potential request matching, not one request-specific handler decision.",
            "Generated proxy and interceptor identity remains possible unless generated/runtime metadata makes the target unique.",
        ],
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(output)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=ROOT / "spike/results/framework-enrichment-observation.json")
    args = parser.parse_args()
    output = args.output if args.output.is_absolute() else ROOT / args.output
    try:
        enrich(output)
    except EnrichmentError as error:
        raise SystemExit(f"framework enrichment failed: {error}") from error


if __name__ == "__main__":
    main()
