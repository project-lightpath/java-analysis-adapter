#!/usr/bin/env python3
"""Record reproducibility and provenance for one FLPATH-5000 analyzer run."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import shlex
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def command_output(command: list[str]) -> str | None:
    if shutil.which(command[0]) is None:
        return None
    process = subprocess.run(command, capture_output=True, text=True, check=False)
    return (process.stdout + process.stderr).strip()


def maven_output() -> str | None:
    installed = shutil.which("mvn")
    if installed:
        return command_output([installed, "-version"])
    bootstrap = ROOT / "spike/scripts/bootstrap-maven.sh"
    if bootstrap.exists():
        process = subprocess.run(["bash", str(bootstrap)], capture_output=True, text=True, check=False)
        if process.returncode == 0:
            path = process.stdout.strip().splitlines()[-1]
            return command_output([path, "-version"])
    return None


def tree_fingerprint(path: Path) -> str:
    digest = hashlib.sha256()
    for file_path in sorted(path.rglob("*")):
        if not file_path.is_file() or "target" in file_path.parts:
            continue
        relative = file_path.relative_to(path).as_posix().encode()
        digest.update(relative)
        digest.update(hashlib.sha256(file_path.read_bytes()).digest())
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--provider", default="Eclipse JDT Language Server")
    parser.add_argument("--provider-version", default="1.63.0")
    parser.add_argument("--profile", choices=("production", "test"), default="production")
    parser.add_argument("--command", required=True)
    parser.add_argument("--status", choices=("passed", "failed", "not_run"), default=None)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    command = shlex.split(args.command)
    result = None
    if args.status is None:
        result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, check=False)
        status = "passed" if result.returncode == 0 else "failed"
    else:
        status = args.status

    java_version = command_output(["java", "-version"])
    java_major = None
    if java_version:
        for token in java_version.split('"'):
            if token and token[0].isdigit():
                java_major = token.split(".")[0]
                break

    payload = {
        "schemaVersion": "0.1.0",
        "recordedAt": datetime.now(timezone.utc).isoformat(),
        "status": status,
        "authoritative": java_major == "21",
        "issue": "FLPATH-5000",
        "adr": "ADR-001",
        "provider": {"name": args.provider, "version": args.provider_version},
        "profile": args.profile,
        "platform": {"system": platform.system(), "release": platform.release(), "machine": platform.machine()},
        "toolchain": {"java": java_version, "maven": maven_output()},
        "source": {
            "gitRevision": command_output(["git", "rev-parse", "HEAD"]),
            "repositoryFingerprint": tree_fingerprint(ROOT),
            "fixtureFingerprint": tree_fingerprint(ROOT / "spike" / "java21-quarkus"),
        },
        "command": args.command,
        "limitations": ([] if java_major == "21" else ["run was not made with JDK 21"]),
    }
    if result is not None:
        payload["exitCode"] = result.returncode
        payload["stdout"] = result.stdout[-4000:]
        payload["stderr"] = result.stderr[-4000:]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(args.output)
    if status == "failed":
        raise SystemExit(result.returncode if result is not None and result.returncode else 1)


if __name__ == "__main__":
    main()
