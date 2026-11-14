#!/usr/bin/env python3
"""Validate the immutable FLPATH-5078 RED/GREEN/EVIDENCE ledger."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
PRODUCTION_PREFIX = "src/main/java/"


def require(condition: bool, message: str) -> None:
    """Abort validation with a precise evidence diagnostic."""
    if not condition:
        raise SystemExit(f"TDD evidence validation failed: {message}")


def sha256(path: Path) -> str:
    """Return the SHA-256 digest of one evidence file."""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git(root: Path, *arguments: str) -> str:
    """Run a read-only git query in the selected repository."""
    result = subprocess.run(
        ["git", *arguments], cwd=root, capture_output=True, text=True, check=False
    )
    require(result.returncode == 0, f"git {' '.join(arguments)} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def commit_exists(root: Path, commit: str) -> bool:
    """Return whether commit resolves in the repository."""
    result = subprocess.run(
        ["git", "cat-file", "-e", f"{commit}^{{commit}}"],
        cwd=root,
        capture_output=True,
        check=False,
    )
    return result.returncode == 0


def changed_paths(root: Path, commit: str) -> list[str]:
    """List paths changed by one commit."""
    output = git(root, "diff-tree", "--no-commit-id", "--name-only", "-r", commit)
    return [line for line in output.splitlines() if line]


def production_tree(root: Path, commit: str) -> list[str]:
    """List the production tree recorded by one commit."""
    output = git(root, "ls-tree", "-r", "--name-only", commit, "--", "src/main/java")
    return [line for line in output.splitlines() if line]


def validate_run_record(record: dict[str, Any], kind: str) -> None:
    """Validate one red or green sidecar before checking its git provenance."""
    required = {"schemaVersion", "finding", "selector", "command", "testCount", "outputSha256"}
    require(required.issubset(record), f"{kind} record is missing {sorted(required - set(record))}")
    require(record["schemaVersion"] == "0.1.0", f"unsupported {kind} record schema")
    require(record["testCount"] > 0, f"{kind} record must prove a nonzero test count")
    require(len(record["outputSha256"]) == 64, f"{kind} output hash is missing")
    require("src/main/java/" not in record.get("productionManifest", ""),
            f"{kind} production manifest must describe the unchanged tree")
    if kind == "red":
        require(record.get("exitCode", 0) != 0, "red record must have a failing exit code")
        require(record.get("behavioralAssertion") is True,
                "red record must mark an assertion-based behavioral failure")
        require(record.get("compileOnly") is not True,
                "compile-only failures cannot be red evidence")
        require("commit" not in record, "red record must not contain its own commit hash")
    else:
        require(record.get("exitCode") == 0, "green record must have exit code zero")


def load_json(path: Path) -> dict[str, Any]:
    """Load one JSON sidecar."""
    require(path.is_file(), f"missing evidence sidecar: {path}")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise SystemExit(f"TDD evidence validation failed: invalid JSON in {path}: {error}") from error
    require(isinstance(value, dict), f"evidence sidecar must be an object: {path}")
    return value


def validate_ledger(root: Path, ledger_path: Path) -> int:
    """Validate every committed RED/GREEN pair recorded by the ledger."""
    require(ledger_path.is_file(), f"missing ledger: {ledger_path}")
    entries = [
        json.loads(line)
        for line in ledger_path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    require(entries, "ledger contains no evidence entries")

    previous_evidence = None
    for index, entry in enumerate(entries, start=1):
        require(entry.get("schemaVersion") == "0.1.0", f"entry {index} has an unsupported schema")
        for field in ("finding", "redCommit", "greenCommit", "redParent", "greenParent",
                      "redRecord", "greenRecord", "redRecordSha256", "greenRecordSha256"):
            require(entry.get(field), f"entry {index} is missing {field}")
        red_commit = entry["redCommit"]
        green_commit = entry["greenCommit"]
        require(commit_exists(root, red_commit), f"entry {index} red commit does not exist")
        require(commit_exists(root, green_commit), f"entry {index} green commit does not exist")
        expected_parent = entry["redParent"]
        if previous_evidence is not None:
            require(expected_parent == previous_evidence,
                    f"entry {index} red parent is not the previous evidence commit")
        require(git(root, "rev-parse", f"{red_commit}^") == git(root, "rev-parse", expected_parent),
                f"entry {index} red parent provenance is incorrect")
        require(git(root, "rev-parse", f"{green_commit}^") == git(root, "rev-parse", red_commit),
                f"entry {index} green parent must be the red commit")
        require(git(root, "merge-base", "--is-ancestor", red_commit, green_commit) == "",
                f"entry {index} red commit is not an ancestor of green")

        red_paths = changed_paths(root, red_commit)
        require(not any(path.startswith(PRODUCTION_PREFIX) for path in red_paths),
                f"entry {index} red commit changes production files: {red_paths}")
        require(production_tree(root, red_commit) == production_tree(root, expected_parent),
                f"entry {index} red production tree differs from its parent")

        red_record_path = root / entry["redRecord"]
        green_record_path = root / entry["greenRecord"]
        red_record = load_json(red_record_path)
        green_record = load_json(green_record_path)
        validate_run_record(red_record, "red")
        validate_run_record(green_record, "green")
        require(sha256(red_record_path) == entry["redRecordSha256"],
                f"entry {index} red sidecar hash does not match the ledger")
        require(sha256(green_record_path) == entry["greenRecordSha256"],
                f"entry {index} green sidecar hash does not match the ledger")
        require(red_record["finding"] == entry["finding"] == green_record["finding"],
                f"entry {index} finding identity differs across evidence")
        require(red_record["selector"] == green_record["selector"],
                f"entry {index} selector differs across evidence")
        previous_evidence = entry.get("evidenceCommit", green_commit)
        require(commit_exists(root, previous_evidence), f"entry {index} evidence commit does not exist")

    return len(entries)


def self_test() -> int:
    """Exercise the assertion and red/green marker checks with fixtures."""
    red = {
        "schemaVersion": "0.1.0",
        "finding": "fixture",
        "selector": "ExampleTest#behavior",
        "command": "mvn -Dtest=ExampleTest#behavior test",
        "testCount": 1,
        "outputSha256": "0" * 64,
        "exitCode": 1,
        "behavioralAssertion": True,
        "compileOnly": False,
        "productionManifest": "unchanged",
    }
    green = {**red, "exitCode": 0, "behavioralAssertion": False}
    validate_run_record(red, "red")
    validate_run_record(green, "green")
    require(red["testCount"] > 0, "self-test fixture was not executed")
    require(red["behavioralAssertion"] is True, "self-test behavioral marker was not checked")
    print("validate_tdd_evidence self-test: 2 fixtures passed")
    return 0


def main() -> int:
    """Run the requested validator mode."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--verify", type=Path)
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    require(args.verify is not None, "use --self-test or --verify LEDGER")
    count = validate_ledger(ROOT, ROOT / args.verify)
    print(f"validate_tdd_evidence: {count} entries passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
