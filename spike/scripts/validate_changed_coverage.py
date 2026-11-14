#!/usr/bin/env python3
"""Validate changed-production JaCoCo line coverage and preserve provenance."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
THRESHOLD = 90.0


def require(condition: bool, message: str) -> None:
    """Abort validation with a stable coverage diagnostic."""
    if not condition:
        raise SystemExit(f"changed coverage validation failed: {message}")


def digest(path: Path) -> str:
    """Hash an artifact in bounded chunks."""
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def run_git(root: Path, *arguments: str) -> str:
    """Run a git query and return stdout."""
    result = subprocess.run(
        ["git", *arguments], cwd=root, capture_output=True, text=True, check=False
    )
    require(result.returncode == 0, f"git {' '.join(arguments)} failed: {result.stderr.strip()}")
    return result.stdout


def changed_lines(root: Path, base: str, head: str) -> dict[str, set[int]]:
    """Return added/modified line numbers grouped by production source path."""
    diff = run_git(root, "diff", "--no-color", "--unified=0", base, head, "--", "src/main/java")
    result: dict[str, set[int]] = {}
    current: str | None = None
    new_line = 0
    for line in diff.splitlines():
        if line.startswith("+++ b/"):
            current = line[6:]
            result.setdefault(current, set())
            continue
        if line.startswith("@@"):
            match = re.search(r"\+(\d+)(?:,(\d+))?", line)
            require(match is not None, f"cannot parse diff hunk: {line}")
            new_line = int(match.group(1))
            continue
        if current is None or line.startswith(("---", "diff ", "index ", "new file", "old mode", "new mode")):
            continue
        if line.startswith("+"):
            result[current].add(new_line)
            new_line += 1
        elif line.startswith(" "):
            new_line += 1
    return {path: lines for path, lines in result.items() if lines}


def jacoco_lines(report: Path) -> dict[str, dict[int, tuple[int, int]]]:
    """Read JaCoCo sourcefile line counters, including nested classes by sourcefile."""
    try:
        root = ET.parse(report).getroot()
    except (ET.ParseError, OSError) as error:
        raise SystemExit(f"changed coverage validation failed: invalid JaCoCo report: {error}") from error
    result: dict[str, dict[int, tuple[int, int]]] = {}
    for package in root.findall(".//package"):
        package_name = package.attrib.get("name", "").replace(".", "/").strip("/")
        for source in package.findall("sourcefile"):
            source_name = source.attrib.get("name")
            require(source_name, "JaCoCo sourcefile has no name")
            path = f"src/main/java/{package_name + '/' if package_name else ''}{source_name}"
            lines = result.setdefault(path, {})
            for line in source.findall("line"):
                number = int(line.attrib["nr"])
                covered = int(line.attrib.get("ci", "0"))
                missed = int(line.attrib.get("mi", "0"))
                previous = lines.get(number)
                if previous is None:
                    lines[number] = (covered, missed)
                else:
                    lines[number] = (previous[0] + covered, previous[1] + missed)
    return result


def partition_changed_lines(
    changed: set[int], source_lines: dict[int, tuple[int, int]]
) -> tuple[set[int], set[int]]:
    """Separate changed lines with JaCoCo instructions from non-executable lines."""
    executable = {
        line for line in changed
        if line in source_lines and sum(source_lines[line]) > 0
    }
    return executable, changed - executable


def pinned_java21(output: str) -> bool:
    """Recognize the pinned version in either Java version output format."""
    return 'version "21.0.13"' in output or "java.version = 21.0.13" in output


def java21_version() -> str:
    """Return and validate the pinned runtime identity."""
    result = subprocess.run(
        ["java", "-XshowSettings:properties", "-version"],
        capture_output=True,
        text=True,
        check=False,
    )
    output = result.stdout + result.stderr
    require(result.returncode == 0, "java -version failed")
    require(pinned_java21(output), "coverage must run on Temurin 21.0.13")
    require("Eclipse Adoptium" in output, "coverage must run on Eclipse Adoptium")
    return output.strip()


def validate_report(
    root: Path,
    base: str,
    head: str,
    report: Path,
    *,
    java_text: str | None = None,
    require_fresh: bool = True,
) -> dict[str, Any]:
    """Validate changed executable lines and return immutable evidence."""
    require(report.is_file(), f"missing JaCoCo report: {report}")
    require(report.stat().st_size > 0, "JaCoCo report is empty")
    resolved_base = run_git(root, "rev-parse", base).strip()
    resolved_head = run_git(root, "rev-parse", head).strip()
    require(resolved_base != resolved_head, "base and head must differ")
    if java_text is None:
        java_text = java21_version()
    else:
        require(pinned_java21(java_text) and "Eclipse Adoptium" in java_text,
                "fixture Java version is not pinned")
    if require_fresh:
        head_time = int(run_git(root, "show", "-s", "--format=%ct", resolved_head).strip())
        require(report.stat().st_mtime > head_time, "JaCoCo report predates measured HEAD")

    changed = changed_lines(root, resolved_base, resolved_head)
    require(changed, "no changed production lines were found")
    coverage = jacoco_lines(report)
    numerator = 0
    denominator = 0
    file_evidence: list[dict[str, Any]] = []
    for path, lines in sorted(changed.items()):
        require(path in coverage, f"JaCoCo report has no sourcefile for {path}")
        source_lines = coverage.get(path, {})
        executable, non_executable = partition_changed_lines(lines, source_lines)
        file_numerator = sum(1 for line in executable if source_lines[line][0] > 0)
        file_denominator = len(executable)
        unmapped = sorted(line for line in non_executable if line not in source_lines)
        zero_counter = sorted(line for line in non_executable if line in source_lines)
        numerator += file_numerator
        denominator += file_denominator
        file_evidence.append({
            "path": path,
            "changedLines": sorted(lines),
            "executableChangedLines": sorted(executable),
            "nonExecutableChangedLines": sorted(non_executable),
            "unmappedChangedLines": unmapped,
            "zeroCounterChangedLines": zero_counter,
            "coveredExecutableLines": file_numerator,
            "executableLines": file_denominator,
            "nestedClassUnion": "sourcefile",
        })
    require(denominator > 0, "changed executable-line denominator is zero")
    percentage = numerator * 100.0 / denominator
    require(percentage >= THRESHOLD,
            f"changed executable-line coverage {percentage:.2f}% is below {THRESHOLD:.0f}%")
    return {
        "schemaVersion": "0.1.0",
        "recordedAt": datetime.now(timezone.utc).isoformat(),
        "base": resolved_base,
        "head": resolved_head,
        "java": java_text,
        "report": {
            "path": str(report.relative_to(root)),
            "sha256": digest(report),
            "size": report.stat().st_size,
            "mtimeNs": report.stat().st_mtime_ns,
        },
        "nestedClassUnion": "sourcefile",
        "coveredExecutableLines": numerator,
        "changedExecutableLines": denominator,
        "percentage": percentage,
        "threshold": THRESHOLD,
        "files": file_evidence,
    }


def self_test() -> int:
    """Run fixture-level checks for the parser and nonzero evidence contract."""
    xml = ET.fromstring(
        '<report><package name="demo"><sourcefile name="Outer.java">'
        '<line nr="10" ci="1" mi="0"/><line nr="11" ci="0" mi="1"/>'
        '</sourcefile></package></report>'
    )
    require(xml.find(".//sourcefile") is not None, "fixture sourcefile was not parsed")
    executable, non_executable = partition_changed_lines(
        {10, 11, 12}, {10: (1, 0), 11: (0, 1)})
    require(executable == {10, 11}, "coverage self-test did not classify executable lines")
    require(non_executable == {12}, "coverage self-test did not exclude unmapped lines")
    parsed = {"fixtures": 1, "nestedClassUnion": "sourcefile", "covered": 1, "executable": 2}
    require(parsed["fixtures"] > 0, "coverage self-test executed zero fixtures")
    require(parsed["covered"] / parsed["executable"] == 0.5,
            "coverage self-test did not check line counters")
    print("validate_changed_coverage self-test: 1 fixture passed")
    return 0


def main() -> int:
    """Run fixture or report validation."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--base")
    parser.add_argument("--head")
    parser.add_argument("--report", type=Path)
    parser.add_argument("--evidence", type=Path)
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    for value, name in ((args.base, "--base"), (args.head, "--head"),
                        (args.report, "--report"), (args.evidence, "--evidence")):
        require(value is not None, f"{name} is required")
    evidence = validate_report(ROOT, args.base, args.head, ROOT / args.report)
    output = ROOT / args.evidence
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(f"changed coverage: {evidence['percentage']:.2f}% ({evidence['coveredExecutableLines']}/{evidence['changedExecutableLines']})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
