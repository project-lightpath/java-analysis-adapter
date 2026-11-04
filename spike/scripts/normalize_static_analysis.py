#!/usr/bin/env python3
"""Normalize ignored SootUp/CodeQL run output into portable spike evidence."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
FSI_ROOT = ROOT.parent / "spring-boot-example-application"
FIXTURE_ALIASES = {"quarkus": "quarkus", "spring": "fsi", "spring-boot": "fsi", "fsi": "fsi"}


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def canonical_fixture(fixture: str) -> str:
    try:
        return FIXTURE_ALIASES[fixture]
    except KeyError as error:
        raise SystemExit(f"unsupported fixture/framework: {fixture}") from error


def tree_fingerprint(path: Path) -> str | None:
    if not path.is_dir():
        return None
    digest = hashlib.sha256()
    for file_path in sorted(path.rglob("*")):
        if not file_path.is_file() or {"target", ".git", ".jdtls-data"}.intersection(file_path.parts):
            continue
        digest.update(file_path.relative_to(path).as_posix().encode())
        digest.update(hashlib.sha256(file_path.read_bytes()).digest())
    return digest.hexdigest()


def file_fingerprint(path: Path) -> str | None:
    if not path.is_file():
        return None
    return hashlib.sha256(path.read_bytes()).hexdigest()


def command_output(command: list[str], cwd: Path | None = None) -> str | None:
    if shutil.which(command[0]) is None:
        return None
    process = subprocess.run(command, cwd=cwd, capture_output=True, text=True, check=False)
    return (process.stdout + process.stderr).strip() or None


def java_major() -> int | None:
    version = command_output(["java", "-version"])
    if not version:
        return None
    match = re.search(r'version "(\d+)', version)
    return int(match.group(1)) if match else None


def maven_version() -> str | None:
    version = command_output(["mvn", "-version"])
    if version is None:
        bootstrap = ROOT / "spike/scripts/bootstrap-maven.sh"
        if bootstrap.is_file():
            result = subprocess.run(["bash", str(bootstrap)], capture_output=True, text=True, check=False)
            if result.returncode == 0 and result.stdout.strip():
                version = command_output([result.stdout.strip().splitlines()[-1], "-version"])
    if not version:
        return None
    match = re.search(r"Apache Maven ([^\s]+)", version)
    return match.group(1) if match else version.splitlines()[0]


def portable_path(value: str | None) -> str:
    if not value:
        return "unknown"
    normalized = value.replace("\\", "/")
    for marker in ("generated-src/main/", "src/main/", "src/test/"):
        position = normalized.find(marker)
        if position >= 0:
            return normalized[position:]
    if normalized.startswith("/"):
        return "external/" + Path(normalized).name
    return normalized


def soot_method(signature: str) -> tuple[str, str]:
    body = signature.strip("<>")
    class_name, method_body = body.split(": ", 1)
    method_match = re.search(r"\s([^\s(]+)\([^)]*\)$", method_body)
    if not method_match:
        return class_name, method_body
    return class_name, method_match.group(1)


def soot_edges(raw: dict[str, Any], algorithm: str = "cha") -> list[dict[str, Any]]:
    analysis = next(item for item in raw["analyses"] if item["algorithm"] == algorithm)
    edges: list[dict[str, Any]] = []
    for call in analysis["applicationCalls"]:
        caller_class, caller_method = soot_method(call["caller"])
        callee_class, callee_method = soot_method(call["callee"])
        edges.append(
            {
                "callerClass": caller_class,
                "callerMethod": caller_method,
                "calleeClass": callee_class,
                "calleeMethod": callee_method,
                "line": call["line"],
                "sourcePath": portable_path(call.get("sourcePath")),
            }
        )
    return edges


def csv_edges(path: Path) -> tuple[list[dict[str, Any]], int, int]:
    with path.open(newline="", encoding="utf-8") as stream:
        rows = list(csv.DictReader(stream))
    edges = [
        {
            "callerClass": row["col0"],
            "callerMethod": row["col1"],
            "calleeClass": row["col2"],
            "calleeMethod": row["col3"],
            "line": int(row["col5"]),
            "sourcePath": portable_path(row["col4"]),
            "callerQualifiedName": row["col6"],
            "calleeQualifiedName": row["col7"],
        }
        for row in rows
    ]
    production = sum(edge["sourcePath"].startswith(("src/main/", "generated-src/main/")) for edge in edges)
    return edges, production, len(edges) - production


def matches(
    edge: dict[str, Any],
    caller_class: str,
    caller_method: str,
    callee_class: str | tuple[str, ...],
    callee_method: str,
) -> bool:
    classes = (callee_class,) if isinstance(callee_class, str) else callee_class
    return (
        edge["callerClass"] == caller_class
        and edge["callerMethod"] == caller_method
        and edge["calleeClass"] in classes
        and edge["calleeMethod"] == callee_method
    )


def matching_edge(
    edges: list[dict[str, Any]],
    caller_class: str,
    caller_method: str,
    callee_class: str | tuple[str, ...],
    callee_method: str,
    source_contains: str | None = None,
) -> dict[str, Any] | None:
    for edge in edges:
        if source_contains and source_contains not in edge["sourcePath"]:
            continue
        if matches(edge, caller_class, caller_method, callee_class, callee_method):
            return edge
    return None


def evidence(edge: dict[str, Any] | None, basis: str) -> list[dict[str, Any]]:
    if edge is None:
        return [{"basis": basis}]
    return [
        {
            "basis": basis,
            "caller": f'{edge["callerClass"]}#{edge["callerMethod"]}',
            "callee": f'{edge["calleeClass"]}#{edge["calleeMethod"]}',
            "sourcePath": edge["sourcePath"],
            "line": edge["line"],
        }
    ]


def observation(
    case_id: str,
    classification: str,
    certainty: str,
    outcome: str,
    observed: str,
    evidence_items: list[dict[str, Any]],
    limitation: str | None = None,
) -> dict[str, Any]:
    result: dict[str, Any] = {
        "caseId": case_id,
        "classification": classification,
        "certainty": certainty,
        "observed": observed,
        "outcome": outcome,
        "evidence": evidence_items,
    }
    if limitation:
        result["limitation"] = limitation
    return result


def quarkus_sootup(raw: dict[str, Any]) -> list[dict[str, Any]]:
    edges = soot_edges(raw)
    direct = matching_edge(
        edges,
        "com.projectlightpath.fixture.target.ServerUserAuthService",
        "handleUserAuthRequestMessage",
        "com.projectlightpath.fixture.target.UserAuthPublicKey",
        "doAuth",
    )
    interface = matching_edge(
        edges,
        "com.projectlightpath.fixture.api.TransferResource",
        "transfer",
        "com.projectlightpath.fixture.service.DefaultTransferService",
        "transfer",
    )
    callback = matching_edge(
        edges,
        "com.projectlightpath.fixture.auth.CallbackDispatcher",
        "lambda$0",
        "com.projectlightpath.fixture.auth.RecordingAuthCallback",
        "onAuthenticated",
    )
    loader = matching_edge(
        edges,
        "com.projectlightpath.fixture.audit.ServiceLoaderBridge",
        "lambda$0",
        "com.projectlightpath.fixture.audit.DefaultAuditProvider",
        "record",
    )
    generated = matching_edge(
        edges,
        "com.projectlightpath.fixture.service.DefaultTransferService",
        "transfer",
        "com.projectlightpath.fixture.generated.GeneratedTransferMapper",
        "toResponse",
    )
    return [
        observation(
            "direct-call",
            "existing_support",
            "definite",
            "definite_static_path",
            "ServerUserAuthService#handleUserAuthRequestMessage -> UserAuthPublicKey#doAuth",
            evidence(direct, "SootUp CHA incoming application call"),
            "RTA did not retain this edge under the all-application-method entry-point policy; CHA is the selected evidence view.",
        ),
        observation(
            "interface-dispatch",
            "configuration_work",
            "possible",
            "possible_static_path",
            "TransferResource#transfer -> DefaultTransferService#transfer",
            evidence(interface, "SootUp CHA concrete implementation edge"),
            "The requested TransferService interface vertex is absent from the graph result; CDI bean selection remains external context.",
        ),
        observation(
            "cdi-injection",
            "configuration_work",
            "possible",
            "possible_static_path",
            "TransferResource constructor accepts TransferService and CHA reaches DefaultTransferService",
            evidence(interface, "constructor signature plus CHA concrete implementation edge"),
            "SootUp does not model CDI qualifiers, alternatives, producers, or client proxies.",
        ),
        observation(
            "framework-entrypoint",
            "confirmed_gap",
            "unresolved",
            "unknown_without_framework_model",
            "TransferResource#transfer has no incoming framework caller",
            [{"basis": "no incoming application call in SootUp CHA", "target": "com.projectlightpath.fixture.api.TransferResource#transfer"}],
            "Quarkus REST route registration must be supplied as framework metadata; no incoming Java caller is not evidence that the route is absent or dead code.",
        ),
        observation(
            "callback-lambda",
            "configuration_work",
            "possible",
            "possible_static_path",
            "CallbackDispatcher lambda -> RecordingAuthCallback#onAuthenticated",
            evidence(callback, "SootUp CHA concrete callback implementation edge"),
            "The interface callback target is not a unique runtime implementation without registration/context evidence.",
        ),
        observation(
            "service-loader",
            "configuration_work",
            "possible",
            "possible_static_path",
            "ServiceLoaderBridge lambda -> DefaultAuditProvider#record",
            evidence(loader, "SootUp CHA concrete implementor edge"),
            "The graph does not prove META-INF/services provider selection; preserve possible certainty.",
        ),
        observation(
            "reflection",
            "confirmed_gap",
            "unresolved",
            "unknown",
            "ReflectiveTarget#validate has no incoming target edge",
            [{"basis": "SootUp graph contains reflective API activity but no target edge", "target": "com.projectlightpath.fixture.reflective.ReflectiveTarget#validate"}],
            "Class.forName, getDeclaredMethod, and Method.invoke remain open-world behavior.",
        ),
        observation(
            "dynamic-proxy",
            "confirmed_gap",
            "unresolved",
            "unknown",
            "AuthProxyFactory proxy creation has no concrete AuthCallback#onAuthenticated edge",
            [{"basis": "SootUp graph contains Proxy.newProxyInstance but no proxy target edge", "target": "com.projectlightpath.fixture.auth.AuthCallback#onAuthenticated"}],
            "Proxy handler and runtime interface dispatch require explicit modeling.",
        ),
        observation(
            "generated-source",
            "existing_support",
            "definite",
            "definite_static_path",
            "DefaultTransferService#transfer -> GeneratedTransferMapper#toResponse",
            evidence(generated, "SootUp CHA application call with generated class input"),
            "The generated source root must remain part of the selected build profile.",
        ),
        observation(
            "build-profile",
            "configuration_work",
            "unresolved",
            "partial",
            "Production application classes were analyzed; production and test builds are separate inputs",
            [{"basis": "SootUp run configuration", "profile": "production"}],
            "The probe does not import both Maven profiles into one graph; profile selection must be recorded in AnalysisContext.",
        ),
    ]


def quarkus_codeql(edges: list[dict[str, Any]]) -> list[dict[str, Any]]:
    direct = matching_edge(edges, "com.projectlightpath.fixture.target.ServerUserAuthService", "handleUserAuthRequestMessage", "com.projectlightpath.fixture.target.UserAuthPublicKey", "doAuth")
    interface = matching_edge(edges, "com.projectlightpath.fixture.api.TransferResource", "transfer", "com.projectlightpath.fixture.service.TransferService", "transfer")
    callback = next((edge for edge in edges if edge["sourcePath"].endswith("CallbackDispatcher.java") and edge["calleeClass"] == "com.projectlightpath.fixture.auth.AuthCallback" and edge["calleeMethod"] == "onAuthenticated"), None)
    loader = next((edge for edge in edges if edge["sourcePath"].endswith("ServiceLoaderBridge.java") and edge["calleeClass"] == "com.projectlightpath.fixture.audit.AuditProvider" and edge["calleeMethod"] == "record"), None)
    generated = matching_edge(edges, "com.projectlightpath.fixture.service.DefaultTransferService", "transfer", "com.projectlightpath.fixture.generated.GeneratedTransferMapper", "toResponse")
    reflective_api = next((edge for edge in edges if edge["sourcePath"].endswith("ReflectiveInvoker.java") and edge["calleeClass"] == "java.lang.reflect.Method" and edge["calleeMethod"] == "invoke"), None)
    proxy_api = next((edge for edge in edges if edge["sourcePath"].endswith("AuthProxyFactory.java") and edge["calleeClass"] == "java.lang.reflect.Proxy" and edge["calleeMethod"] == "newProxyInstance"), None)
    return [
        observation("direct-call", "existing_support", "definite", "definite_static_path", "ServerUserAuthService#handleUserAuthRequestMessage -> UserAuthPublicKey#doAuth", evidence(direct, "CodeQL Call/Callee source fact")),
        observation("interface-dispatch", "configuration_work", "possible", "possible_static_path", "TransferResource#transfer -> TransferService#transfer", evidence(interface, "CodeQL source call to interface target"), "The query exposes the source-level interface edge but does not select a CDI implementation."),
        observation("cdi-injection", "configuration_work", "possible", "possible_static_path", "TransferResource#transfer uses constructor-injected TransferService", evidence(interface, "CodeQL source call plus fixture constructor context"), "CDI bean selection is not part of the buildless query model."),
        observation("framework-entrypoint", "confirmed_gap", "unresolved", "unknown_without_framework_model", "TransferResource#transfer has no incoming Java caller", [{"basis": "no production CodeQL call fact targets the REST method", "target": "com.projectlightpath.fixture.api.TransferResource#transfer"}], "@Path/@POST route registration requires a Quarkus framework model; no incoming Java caller is not evidence that the route is absent or dead code."),
        observation("callback-lambda", "configuration_work", "possible", "possible_static_path", "anonymous callback -> AuthCallback#onAuthenticated", evidence(callback, "CodeQL source call fact"), "The callback registration and runtime scheduling are not proved by this query."),
        observation("service-loader", "configuration_work", "possible", "possible_static_path", "anonymous ServiceLoader consumer -> AuditProvider#record", evidence(loader, "CodeQL source call fact"), "The query sees the interface call but does not bind META-INF/services to one provider."),
        observation("reflection", "confirmed_gap", "unresolved", "unknown", "ReflectiveInvoker calls Method#invoke but no edge targets ReflectiveTarget#validate", evidence(reflective_api, "CodeQL reflective API fact without target resolution"), "String-based Class.forName and Method.invoke targets remain unresolved."),
        observation("dynamic-proxy", "confirmed_gap", "unresolved", "unknown", "AuthProxyFactory calls Proxy#newProxyInstance but no proxy callback target is emitted", evidence(proxy_api, "CodeQL dynamic proxy API fact without target resolution"), "Proxy handler and runtime interface dispatch remain open-world."),
        observation("generated-source", "existing_support", "definite", "definite_static_path", "DefaultTransferService#transfer -> GeneratedTransferMapper#toResponse", evidence(generated, "CodeQL source fact from generated-src"), "Generated-source inclusion is visible in the production source facts but still depends on profile context."),
        observation("build-profile", "configuration_work", "unresolved", "partial", "CodeQL buildless extraction included production and test source facts; normalization retained production facts", [{"basis": "CodeQL --build-mode=none", "productionScope": "src/main and generated-src/main", "testFactsFiltered": True}], "The database was not created from a compiled Maven build; profile and dependency fidelity remain limited."),
    ]


def fsi_sootup(raw: dict[str, Any]) -> list[dict[str, Any]]:
    edges = soot_edges(raw)
    controller = matching_edge(edges, "com.redhat.lightwell.controller.TransferController", "transfer", "com.redhat.lightwell.service.TransactionService", "transfer")
    audit = matching_edge(edges, "com.redhat.lightwell.service.TransactionService", "transfer", "com.redhat.lightwell.service.AuditService", "log")
    return [
        observation("spring-mvc-entrypoint", "confirmed_gap", "unresolved", "unknown_without_framework_model", "TransferController#transfer has no incoming Java caller", [{"basis": "no incoming application call in SootUp CHA", "target": "com.redhat.lightwell.controller.TransferController#transfer"}], "Spring MVC route registration requires framework metadata; no incoming Java caller is not evidence that the route is absent or dead code."),
        observation("transfer-controller-to-service", "existing_support", "definite", "definite_static_path", "TransferController#transfer -> TransactionService#transfer", evidence(controller, "SootUp CHA application call")),
        observation("transaction-service-to-audit", "existing_support", "definite", "definite_static_path", "TransactionService#transfer -> AuditService#log", evidence(audit, "SootUp CHA application call"), "Multiple TransactionService methods call AuditService#log; this evidence selects the transfer method."),
        observation("transaction-service-to-jpa", "configuration_work", "unresolved", "unknown_without_framework_model", "No incoming graph edge resolved to AccountRepository#findById", [{"basis": "requested repository method is absent from SootUp graph", "target": "com.redhat.lightwell.repository.AccountRepository#findById"}], "Spring Data supplies the generated repository implementation outside application bytecode."),
        observation("spring-bean-callback", "confirmed_gap", "unresolved", "unknown_without_framework_model", "SecurityConfig#securityFilterChain has no incoming Java caller", [{"basis": "no incoming application call in SootUp CHA", "target": "com.redhat.lightwell.config.SecurityConfig#securityFilterChain"}], "ApplicationContext invokes @Bean methods through framework machinery."),
        observation("startup-callback", "confirmed_gap", "unresolved", "unknown_without_framework_model", "DataSeeder#run has no incoming Java caller", [{"basis": "no incoming application call in SootUp CHA", "target": "com.redhat.lightwell.config.DataSeeder#run"}], "Spring Boot invokes CommandLineRunner callbacks during startup."),
    ]


def fsi_codeql(edges: list[dict[str, Any]]) -> list[dict[str, Any]]:
    controller = matching_edge(edges, "com.redhat.lightwell.controller.TransferController", "transfer", "com.redhat.lightwell.service.TransactionService", "transfer")
    audit = matching_edge(edges, "com.redhat.lightwell.service.TransactionService", "transfer", "com.redhat.lightwell.service.AuditService", "log")
    jpa = next((edge for edge in edges if edge["callerClass"] == "com.redhat.lightwell.service.TransactionService" and edge["callerMethod"] == "transfer" and edge["calleeMethod"] == "findById" and edge["calleeClass"].startswith("org.springframework.data.repository.CrudRepository")), None)
    return [
        observation("spring-mvc-entrypoint", "confirmed_gap", "unresolved", "unknown_without_framework_model", "TransferController#transfer has no incoming production Java caller", [{"basis": "no production CodeQL call fact targets the controller method", "target": "com.redhat.lightwell.controller.TransferController#transfer"}], "@PostMapping and Spring MVC route registration require a framework model; no incoming Java caller is not evidence that the route is absent or dead code."),
        observation("transfer-controller-to-service", "existing_support", "definite", "definite_static_path", "TransferController#transfer -> TransactionService#transfer", evidence(controller, "CodeQL source call fact")),
        observation("transaction-service-to-audit", "existing_support", "definite", "definite_static_path", "TransactionService#transfer -> AuditService#log", evidence(audit, "CodeQL source call fact")),
        observation("transaction-service-to-jpa", "configuration_work", "possible", "possible_static_path", "TransactionService#transfer -> CrudRepository<Account,Long>#findById", evidence(jpa, "CodeQL source call to Spring Data repository abstraction"), "The generated AccountRepository implementation and proxy identity are not resolved by this query."),
        observation("spring-bean-callback", "confirmed_gap", "unresolved", "unknown_without_framework_model", "SecurityConfig#securityFilterChain has no incoming production Java caller", [{"basis": "no production CodeQL call fact targets the @Bean method", "target": "com.redhat.lightwell.config.SecurityConfig#securityFilterChain"}], "ApplicationContext callback modeling is outside this query."),
        observation("startup-callback", "confirmed_gap", "unresolved", "unknown_without_framework_model", "DataSeeder#run has no incoming production Java caller", [{"basis": "no production CodeQL call fact targets CommandLineRunner.run", "target": "com.redhat.lightwell.config.DataSeeder#run"}], "Spring Boot lifecycle callback modeling is outside this query."),
    ]


def raw_measurements(provider: str, raw: Any, production_count: int | None = None, test_count: int | None = None) -> dict[str, Any]:
    if provider == "sootup":
        return {
            "selectedAlgorithm": "cha",
            "analyses": [
                {
                    "algorithm": item["algorithm"],
                    "graphMillis": item["graphMillis"],
                    "methodCount": item["methodCount"],
                    "callCount": item["callCount"],
                }
                for item in raw["analyses"]
            ],
        }
    return {"query": {"rawFactCount": len(raw), "productionFactCount": production_count, "testFactCount": test_count}}


def provenance(provider: str, fixture: str, raw: Any, project_root: Path, classpath_mode: str) -> dict[str, Any]:
    fixture_id = "java21-quarkus" if fixture == "quarkus" else "fsi-spring-boot"
    framework_id = "quarkus" if fixture == "quarkus" else "spring-boot"
    context_id = "quarkus-production" if fixture == "quarkus" else "spring-boot-production"
    classpath_file = project_root / "target" / "sootup-classpath.txt"
    generated_root = project_root / "generated-src" / "main" / "java"
    source_fingerprint = tree_fingerprint(project_root)
    baseline_revision = command_output(["git", "rev-parse", "HEAD"], cwd=project_root)
    if provider == "sootup":
        classpath_label = "no-deps" if classpath_mode == "none" else "with-deps"
        return {
            "runner": "spike/scripts/run_static_analysis.py",
            "rawArtifact": f"spike/runs/sootup-{('java21-quarkus' if fixture == 'quarkus' else 'fsi-spring')}-{classpath_label}.json",
            "commandTemplate": "python3 spike/scripts/run_static_analysis.py --provider sootup --fixture " + ("quarkus" if fixture == "quarkus" else "spring"),
            "configuration": {
                "framework": framework_id,
                "analysisContextId": context_id,
                "algorithms": ["cha", "rta"],
                "selectedAlgorithm": "cha",
                "entryPointPolicy": "all concrete application methods",
                "classpathMode": classpath_mode,
                "runtimeInput": "DefaultRuntimeAnalysisInputLocation",
                "applicationClasses": raw["fixture"]["applicationClasses"],
                "applicationMethods": raw["fixture"]["applicationMethods"],
            },
            "toolchain": {
                "analyzerRuntimeJavaMajor": java_major(),
                "targetJavaRelease": raw["fixture"]["javaRelease"],
                "mavenVersion": maven_version(),
                "providerVersion": raw["provider"]["version"],
            },
            "fingerprints": {
                "source": source_fingerprint,
                "generatedSource": tree_fingerprint(generated_root),
                "dependencyClasspath": file_fingerprint(classpath_file),
                "gitRevision": baseline_revision,
            },
        }
    return {
        "runner": "spike/scripts/run_static_analysis.py",
        "rawArtifact": f"spike/runs/codeql-{fixture_id}.csv",
        "commandTemplate": "python3 spike/scripts/run_static_analysis.py --provider codeql --fixture " + ("quarkus" if fixture == "quarkus" else "spring"),
        "configuration": {
            "framework": framework_id,
            "analysisContextId": context_id,
            "buildMode": "none",
            "sourceScope": "production facts normalized from src/main and generated-src/main",
            "testFactsRetainedForCoverage": True,
            "query": "spike/contracts/codeql/reverse-calls.ql",
        },
        "toolchain": {
            "cliVersion": "2.27.4",
            "javaPackVersion": "9.3.4",
            "analyzerRuntimeJavaMajor": java_major(),
            "targetJavaRelease": raw["fixture"]["javaRelease"] if isinstance(raw, dict) else (21 if fixture == "quarkus" else 17),
            "mavenVersion": maven_version(),
        },
        "fingerprints": {
            "source": source_fingerprint,
            "generatedSource": tree_fingerprint(generated_root),
            "gitRevision": baseline_revision,
            "dependencyClasspath": "not-consumed-by-buildless-extractor",
        },
    }


def build_result(provider: str, fixture: str, raw: Any, project_root: Path, classpath_mode: str) -> dict[str, Any]:
    fixture_id = "java21-quarkus" if fixture == "quarkus" else "fsi-spring-boot"
    framework_id = "quarkus" if fixture == "quarkus" else "spring-boot"
    framework_name = "Quarkus" if fixture == "quarkus" else "Spring Boot"
    context_id = "quarkus-production" if fixture == "quarkus" else "spring-boot-production"
    if provider == "sootup":
        observations = quarkus_sootup(raw) if fixture == "quarkus" else fsi_sootup(raw)
        edges = None
        classpath_entries = raw["configuration"]["libraryClasspathEntries"]
        measurements = raw_measurements(provider, raw)
        provider_info = {"name": "SootUp", "version": raw["provider"]["version"], "algorithms": raw["provider"]["algorithms"]}
    else:
        edges, production_count, test_count = csv_edges(Path(raw["inputPath"]))
        observations = quarkus_codeql(edges) if fixture == "quarkus" else fsi_codeql(edges)
        classpath_entries = 0
        measurements = raw_measurements(provider, edges, production_count, test_count)
        provider_info = {"name": "CodeQL for Java/Kotlin", "version": "2.27.4", "javaPackVersion": "9.3.4"}

    limitations: list[str]
    coverage: dict[str, Any]
    if provider == "sootup" and fixture == "quarkus":
        sootup_version = raw["provider"]["version"]
        full_manifest_path = ROOT / "spike/runs/sootup-java21-quarkus-full-provenance.json"
        application_manifest_path = ROOT / "spike/runs/sootup-java21-quarkus-none-provenance.json"
        full_manifest = load_json(full_manifest_path) if full_manifest_path.is_file() else {}
        application_manifest = load_json(application_manifest_path) if application_manifest_path.is_file() else {}
        full_status = full_manifest.get("status", "unknown")
        application_status = application_manifest.get("status", "unknown")
        if full_status == "passed":
            limitations = [
                f"Full Quarkus dependency-classpath run passed in SootUp {sootup_version} with {classpath_entries} Maven runtime-scope entries; deployment and test artifacts are excluded.",
                "CHA and RTA disagree on some edges under the all-application-method entry-point policy; unresolved behavior is not promoted to definite.",
            ]
            coverage = {
                "fullDependencyRun": "passed",
                "fullDependencyManifest": "spike/runs/sootup-java21-quarkus-full-provenance.json",
                "applicationOnlyRun": application_status,
                "applicationOnlyManifest": "spike/runs/sootup-java21-quarkus-none-provenance.json",
                "dependencyClasspathEntries": classpath_entries,
                "classpathScope": "runtime",
            }
        else:
            limitations = [
                f"Full Quarkus dependency-classpath run failed in SootUp {sootup_version}; retain the failure as partial coverage.",
                "The checked result is application-only plus the JDK runtime; the dependency classpath was not silently treated as covered.",
                "CHA and RTA disagree on some edges under the all-application-method entry-point policy; unresolved behavior is not promoted to definite.",
            ]
            coverage = {
                "fullDependencyRun": full_status,
                "fullDependencyManifest": "spike/runs/sootup-java21-quarkus-full-provenance.json",
                "applicationOnlyRun": application_status,
                "applicationOnlyManifest": "spike/runs/sootup-java21-quarkus-none-provenance.json",
                "dependencyClasspathEntries": classpath_entries,
                "classpathScope": "runtime",
            }
    elif provider == "codeql":
        limitations = [
            "CodeQL database creation used --build-mode=none because compiled-build extraction is unavailable on the macOS 27 host.",
            "The raw query includes test-source facts; normalization counts them and retains only production paths for candidate observations.",
            "The reverse-call query emits source-level Call/Callee facts, not framework, CDI, ServiceLoader, reflection, or proxy runtime bindings.",
        ]
        coverage = {"buildMode": "none", "rawFacts": measurements["query"]["rawFactCount"], "productionFacts": measurements["query"]["productionFactCount"], "testFactsFiltered": measurements["query"]["testFactCount"]}
    else:
        limitations = [
            "The FSI baseline is external and pinned; generated Spring Data implementations and framework callbacks are outside application source call edges.",
            "CHA/RTA results are graph evidence only and do not model Spring container ownership.",
        ]
        coverage = {"dependencyClasspathEntries": classpath_entries, "applicationScope": "production"}

    result: dict[str, Any] = {
        "schemaVersion": "0.2.0",
        "issue": "FLPATH-5000",
        "adr": "ADR-001",
        "frameworkModel": "spike/contracts/framework-model.json",
        "analysisContextId": context_id,
        "provider": provider_info,
        "fixture": {
            "id": fixture_id,
            "framework": framework_id,
            "frameworkName": framework_name,
            "javaRelease": raw["fixture"]["javaRelease"] if provider == "sootup" else (21 if fixture == "quarkus" else 17),
            "profile": "production",
            "applicationClasses": raw["fixture"]["applicationClasses"] if provider == "sootup" else None,
            "applicationMethods": raw["fixture"]["applicationMethods"] if provider == "sootup" else None,
        },
        "authority": {
            "targetBuildVerified": True,
            "analysisScope": "production",
            "hostPathsOmitted": True,
        },
        "provenance": provenance(provider, fixture, raw, project_root, classpath_mode),
        "coverage": coverage,
        "measurements": measurements,
        "observations": observations,
        "limitations": limitations,
        "recommendation": {
            "decision": "Retain as an evidence source with explicit framework/dynamic abstention; do not use this result alone as a production SemanticCallFacts provider.",
            "adapterBoundary": "Normalize source/bytecode edges as definite or possible only when their evidence is unique; emit unresolved for missing framework or dynamic context.",
        },
    }
    if provider == "codeql":
        result["rawInput"] = "spike/runs/" + ("codeql-java21-quarkus.csv" if fixture == "quarkus" else "codeql-fsi-spring.csv")
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--provider", choices=("sootup", "codeql"), required=True)
    parser.add_argument("--fixture", choices=("quarkus", "spring", "spring-boot", "fsi"), required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--project-root", type=Path)
    parser.add_argument("--classpath-mode", choices=("none", "full"), default="full")
    args = parser.parse_args()
    args.fixture = canonical_fixture(args.fixture)

    input_path = args.input if args.input.is_absolute() else ROOT / args.input
    output_path = args.output if args.output.is_absolute() else ROOT / args.output
    project_root = args.project_root or (ROOT / "spike/java21-quarkus" if args.fixture == "quarkus" else FSI_ROOT)
    project_root = project_root.resolve()

    if args.provider == "sootup":
        raw = load_json(input_path)
    else:
        edges, production_count, test_count = csv_edges(input_path)
        raw = {
            "inputPath": str(input_path),
            "fixture": {"javaRelease": 21 if args.fixture == "quarkus" else 17},
        }
        # Keep the parsed rows available to build_result without duplicating CSV output.
        raw["_edges"] = edges
        raw["_productionCount"] = production_count
        raw["_testCount"] = test_count

    if args.provider == "codeql":
        # build_result reads the same CSV through the portable relative input path.
        raw["inputPath"] = str(input_path)
    result = build_result(args.provider, args.fixture, raw, project_root, args.classpath_mode)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(output_path)


if __name__ == "__main__":
    main()
