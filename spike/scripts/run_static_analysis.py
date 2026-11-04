#!/usr/bin/env python3
"""Run the pinned FLPATH-5000 SootUp or CodeQL probe with provenance."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shlex
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
FSI_ROOT = ROOT.parent / "spring-boot-example-application"
SOOTUP_POM = ROOT / "spike/probes/sootup/pom.xml"
CODEQL_QUERY = ROOT / "spike/contracts/codeql/reverse-calls.ql"
FIXTURE_ALIASES = {"quarkus": "quarkus", "spring": "fsi", "spring-boot": "fsi", "fsi": "fsi"}
SOOTUP_VERSION = "3.0.1"


TARGETS = {
    "quarkus": [
        "direct-call=<com.projectlightpath.fixture.target.UserAuthPublicKey: boolean doAuth(java.lang.String, boolean)>",
        "interface-dispatch=<com.projectlightpath.fixture.service.TransferService: com.projectlightpath.fixture.api.TransferResource$TransferResponse transfer(com.projectlightpath.fixture.api.TransferResource$TransferRequest)>",
        "framework-entrypoint=<com.projectlightpath.fixture.api.TransferResource: com.projectlightpath.fixture.api.TransferResource$TransferResponse transfer(com.projectlightpath.fixture.api.TransferResource$TransferRequest)>",
        "callback-lambda=<com.projectlightpath.fixture.auth.AuthCallback: void onAuthenticated(java.lang.String)>",
        "service-loader=<com.projectlightpath.fixture.audit.AuditProvider: void record(java.lang.String)>",
        "reflection=<com.projectlightpath.fixture.reflective.ReflectiveTarget: boolean validate(java.lang.String)>",
        "dynamic-proxy=<com.projectlightpath.fixture.auth.AuthCallback: void onAuthenticated(java.lang.String)>",
        "generated-source=<com.projectlightpath.fixture.generated.GeneratedTransferMapper: com.projectlightpath.fixture.api.TransferResource$TransferResponse toResponse(java.lang.String, boolean)>",
    ],
    "fsi": [
        "spring-mvc-entrypoint=<com.redhat.lightwell.controller.TransferController: com.redhat.lightwell.model.dto.TransactionResponse transfer(com.redhat.lightwell.model.dto.TransferRequest)>",
        "transfer-controller-to-service=<com.redhat.lightwell.service.TransactionService: com.redhat.lightwell.model.dto.TransactionResponse transfer(com.redhat.lightwell.model.dto.TransferRequest)>",
        "transaction-service-to-audit=<com.redhat.lightwell.service.AuditService: void log(java.lang.String,java.lang.String,java.lang.Long,java.lang.String)>",
        "transaction-service-to-jpa=<com.redhat.lightwell.repository.AccountRepository: java.util.Optional findById(java.lang.Object)>",
        "spring-bean-callback=<com.redhat.lightwell.config.SecurityConfig: org.springframework.security.web.SecurityFilterChain securityFilterChain(org.springframework.security.config.annotation.web.builders.HttpSecurity)>",
        "startup-callback=<com.redhat.lightwell.config.DataSeeder: void run(java.lang.String[])>",
    ],
}


def run(command: list[str], cwd: Path = ROOT) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=cwd, capture_output=True, text=True, check=False)


def maven_executable() -> str:
    installed = shutil.which("mvn")
    if installed:
        return installed
    bootstrap = ROOT / "spike/scripts/bootstrap-maven.sh"
    result = run(["bash", str(bootstrap)])
    if result.returncode != 0:
        raise SystemExit(result.stderr or "Maven bootstrap failed")
    return result.stdout.strip().splitlines()[-1]


def java_executable() -> str:
    return os.environ.get("JAVA", shutil.which("java") or "java")


def version(command: list[str]) -> str | None:
    try:
        result = run(command)
    except FileNotFoundError:
        return None
    return (result.stdout + result.stderr).strip() or None


def java_major(java: str) -> int | None:
    value = version([java, "-version"])
    match = re.search(r'version "(\d+)', value or "")
    return int(match.group(1)) if match else None


def fingerprint(path: Path) -> str | None:
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


def git_revision(path: Path) -> str | None:
    result = run(["git", "rev-parse", "HEAD"], cwd=path)
    return result.stdout.strip() if result.returncode == 0 else None


def canonical_fixture(fixture: str) -> str:
    try:
        return FIXTURE_ALIASES[fixture]
    except KeyError as error:
        raise SystemExit(f"unsupported fixture/framework: {fixture}") from error


def fixture_config(fixture: str) -> tuple[Path, int, list[str], list[str]]:
    fixture = canonical_fixture(fixture)
    if fixture == "quarkus":
        project = ROOT / "spike/java21-quarkus"
        source_roots = [project / "src/main/java", project / "generated-src/main/java"]
        return project, 21, [str(path) for path in source_roots], TARGETS[fixture]
    if not FSI_ROOT.is_dir():
        raise SystemExit(f"FSI baseline is missing: {FSI_ROOT}")
    return FSI_ROOT, 17, [str(FSI_ROOT / "src/main/java")], TARGETS[fixture]


def classpath_for(project: Path, mode: str, mvn: str) -> tuple[str, int, str | None]:
    classpath_file = project / "target/sootup-classpath.txt"
    if mode == "none":
        return "", 0, file_fingerprint(classpath_file)
    command = [
        mvn,
        "-B",
        "-ntp",
        "-q",
        "-f",
        str(project / "pom.xml"),
        "dependency:build-classpath",
        "-Dmdep.outputFile=target/sootup-classpath.txt",
        "-DincludeScope=runtime",
    ]
    result = run(command)
    if result.returncode != 0:
        raise SystemExit(result.stderr or "Could not build analyzer classpath")
    classpath = classpath_file.read_text(encoding="utf-8").strip()
    return classpath, len(classpath.split(os.pathsep)) if classpath else 0, file_fingerprint(classpath_file)


def build_fixture(project: Path, fixture: str, mvn: str) -> None:
    if fixture == "quarkus":
        command = [mvn, "-B", "-ntp", "-Pproduction", "package", "-DskipTests", "-f", str(project / "pom.xml")]
    else:
        command = [mvn, "-B", "-ntp", "test", "-f", str(project / "pom.xml")]
    result = run(command)
    if result.returncode != 0:
        raise SystemExit((result.stdout + result.stderr)[-8000:])


def sootup_run(args: argparse.Namespace) -> dict[str, Any]:
    project, target_release, source_roots, targets = fixture_config(args.fixture)
    mvn = maven_executable()
    build_fixture(project, args.fixture, mvn)
    probe_build = run([mvn, "-B", "-ntp", "-f", str(SOOTUP_POM), "package", "-DskipTests"])
    if probe_build.returncode != 0:
        raise SystemExit((probe_build.stdout + probe_build.stderr)[-8000:])
    dependency_result = run([
        mvn,
        "-B",
        "-ntp",
        "-q",
        "-f",
        str(SOOTUP_POM),
        "dependency:build-classpath",
        "-Dmdep.outputAbsoluteArtifactFilename=true",
        "-Dmdep.outputFile=/dev/stdout",
    ])
    if dependency_result.returncode != 0:
        raise SystemExit(dependency_result.stderr or "Could not build SootUp probe classpath")
    probe_dependencies = dependency_result.stdout.strip().splitlines()[-1]
    library_classpath, classpath_entries, dependency_fingerprint = classpath_for(project, args.classpath_mode, mvn)
    application_root = project / "target/classes"
    classpath_label = "no-deps" if args.classpath_mode == "none" else "with-deps"
    output = args.output or ROOT / "spike/runs" / f"sootup-{('java21-quarkus' if args.fixture == 'quarkus' else 'fsi-spring')}-{classpath_label}.json"
    command = [
        java_executable(),
        "-Xmx4g",
        "-Dorg.slf4j.simpleLogger.defaultLogLevel=error",
        "-cp",
        f"{ROOT / 'spike/probes/sootup/target/classes'}{os.pathsep}{probe_dependencies}",
        "com.projectlightpath.spike.SootUpProbe",
        f"--fixture-id={'java21-quarkus' if args.fixture == 'quarkus' else 'fsi-spring-boot'}",
        "--profile=production",
        f"--java-release={target_release}",
        f"--application-root={application_root}",
        f"--library-classpath={library_classpath}",
        f"--source-root={os.pathsep.join(source_roots)}",
    ]
    command.extend(f"--target={target}" for target in targets)
    command.append(f"--output={output}")
    result = run(command)
    if result.returncode == 0:
        status = "passed"
    else:
        status = "failed"
    manifest = {
        "schemaVersion": "0.2.0",
        "recordedAt": datetime.now(timezone.utc).isoformat(),
        "status": status,
        "authoritative": java_major(java_executable()) == target_release and status == "passed",
        "issue": "FLPATH-5000",
        "adr": "ADR-001",
        "provider": {"name": "SootUp", "version": SOOTUP_VERSION, "algorithms": ["cha", "rta"]},
        "fixture": {"id": "java21-quarkus" if args.fixture == "quarkus" else "fsi-spring-boot", "framework": "quarkus" if args.fixture == "quarkus" else "spring-boot", "analysisContextId": "quarkus-production" if args.fixture == "quarkus" else "spring-boot-production", "javaRelease": target_release, "profile": "production"},
        "platform": {"system": platform.system(), "release": platform.release(), "machine": platform.machine()},
        "toolchain": {"java": version([java_executable(), "-version"]), "maven": version([mvn, "-version"])},
        "source": {"gitRevision": git_revision(ROOT), "fixtureFingerprint": fingerprint(project), "dependencyClasspathFingerprint": dependency_fingerprint},
        "configuration": {"classpathMode": args.classpath_mode, "classpathEntries": classpath_entries, "applicationRoot": str(application_root), "sourceRoots": source_roots},
        "command": shlex.join(command),
        "output": str(output),
        "exitCode": result.returncode,
        "stdout": result.stdout[-4000:],
        "stderr": result.stderr[-8000:],
        "limitations": (["full Quarkus dependency-classpath run failed; retain the failure as partial coverage"] if args.fixture == "quarkus" and args.classpath_mode == "full" and status == "failed" else []),
    }
    manifest_path = args.manifest or ROOT / "spike/runs" / f"sootup-{('java21-quarkus' if args.fixture == 'quarkus' else 'fsi-spring')}-{args.classpath_mode}-provenance.json"
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(manifest_path)
    if status == "failed" and not args.allow_failure:
        raise SystemExit(result.returncode or 1)
    return manifest


def codeql_binary() -> Path:
    configured = os.environ.get("CODEQL_BIN")
    candidates = [
        Path(configured) if configured else None,
        Path.home() / ".cache/project-lightpath/codeql/2.27.4/bundle/codeql/codeql",
        Path.home() / ".cache/project-lightpath/codeql/2.27.4/codeql/codeql",
    ]
    for candidate in candidates:
        if candidate and candidate.is_file() and os.access(candidate, os.X_OK):
            return candidate
    raise SystemExit("CodeQL 2.27.4 not found; set CODEQL_BIN to the pinned executable")


def codeql_run(args: argparse.Namespace) -> dict[str, Any]:
    project, target_release, _, _ = fixture_config(args.fixture)
    codeql = codeql_binary()
    fixture_id = "java21-quarkus" if args.fixture == "quarkus" else "fsi-spring-boot"
    database = args.database or ROOT / "spike/.cache/codeql" / f"{fixture_id}-none"
    if database.exists():
        if not args.replace:
            raise SystemExit(f"CodeQL database exists; pass --replace to recreate {database}")
        shutil.rmtree(database)
    logdir = database.parent / f"{database.name}-log"
    database_command = [
        str(codeql),
        "database",
        "create",
        str(database),
        "--language=java",
        f"--source-root={project}",
        "--build-mode=none",
        "--threads=0",
        "--ram=4096",
        f"--logdir={logdir}",
    ]
    database_result = run(database_command)
    if database_result.returncode != 0:
        raise SystemExit((database_result.stdout + database_result.stderr)[-10000:])
    raw_name = "codeql-java21-quarkus" if args.fixture == "quarkus" else "codeql-fsi-spring"
    bqrs = args.bqrs or ROOT / "spike/runs" / f"{raw_name}.bqrs"
    csv_path = args.output or ROOT / "spike/runs" / f"{raw_name}.csv"
    packs = os.environ.get("CODEQL_PACKS", str(codeql.parent / "qlpacks"))
    query_command = [
        str(codeql),
        "query",
        "run",
        f"--additional-packs={packs}",
        f"--database={database}",
        f"--output={bqrs}",
        str(CODEQL_QUERY),
    ]
    query_result = run(query_command)
    if query_result.returncode != 0:
        raise SystemExit((query_result.stdout + query_result.stderr)[-10000:])
    decode_command = [str(codeql), "bqrs", "decode", "--format=csv", f"--output={csv_path}", str(bqrs)]
    decode_result = run(decode_command)
    if decode_result.returncode != 0:
        raise SystemExit((decode_result.stdout + decode_result.stderr)[-10000:])
    manifest = {
        "schemaVersion": "0.2.0",
        "recordedAt": datetime.now(timezone.utc).isoformat(),
        "status": "passed",
        "authoritative": False,
        "issue": "FLPATH-5000",
        "adr": "ADR-001",
        "provider": {"name": "CodeQL for Java/Kotlin", "cliVersion": "2.27.4", "javaPackVersion": "9.3.4"},
        "fixture": {"id": fixture_id, "framework": "quarkus" if args.fixture == "quarkus" else "spring-boot", "analysisContextId": "quarkus-production" if args.fixture == "quarkus" else "spring-boot-production", "javaRelease": target_release, "profile": "production"},
        "platform": {"system": platform.system(), "release": platform.release(), "machine": platform.machine()},
        "toolchain": {"codeql": version([str(codeql), "version"]), "java": version([java_executable(), "-version"])},
        "source": {"gitRevision": git_revision(ROOT), "fixtureFingerprint": fingerprint(project)},
        "configuration": {"buildMode": "none", "database": str(database), "sourceRoot": str(project), "query": str(CODEQL_QUERY), "packs": packs},
        "commands": [shlex.join(database_command), shlex.join(query_command), shlex.join(decode_command)],
        "artifacts": {"database": str(database), "bqrs": str(bqrs), "csv": str(csv_path)},
        "limitations": ["compiled build extraction was not used; database was created with --build-mode=none on macOS 27"],
    }
    manifest_path = args.manifest or ROOT / "spike/runs" / f"{raw_name}-provenance.json"
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(manifest_path)
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--provider", choices=("sootup", "codeql"), required=True)
    parser.add_argument("--fixture", choices=("quarkus", "spring", "spring-boot", "fsi"), required=True)
    parser.add_argument("--classpath-mode", choices=("none", "full"), default="full")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--database", type=Path)
    parser.add_argument("--bqrs", type=Path)
    parser.add_argument("--replace", action="store_true")
    parser.add_argument("--allow-failure", action="store_true")
    args = parser.parse_args()
    args.fixture = canonical_fixture(args.fixture)
    if args.provider == "sootup":
        sootup_run(args)
    else:
        codeql_run(args)


if __name__ == "__main__":
    main()
