#!/usr/bin/env python3
"""Validate JaCoCo coverage for executable lines changed between two commits.

The Git-unavailable fallback sidecar has the explicit shape
``{"base": "...", "head": "...", "files": {"path.java": [1, 2]}}``.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


HUNK = re.compile(r"@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def git_changed_lines(root: Path, base: str, head: str, suffix: str) -> dict[str, set[int]]:
    diff = subprocess.check_output(
        ["git", "-C", str(root), "diff", "--no-renames", "--unified=0", base, head, "--", f"*{suffix}"],
        text=True,
    )
    changed: dict[str, set[int]] = {}
    current: str | None = None
    next_line: int | None = None
    for line in diff.splitlines():
        if line.startswith("+++ b/"):
            current = line[6:]
            next_line = None
            changed.setdefault(current, set())
            continue
        match = HUNK.match(line)
        if match and current is not None:
            next_line = int(match.group(1))
            count = int(match.group(2) or "1")
            changed[current].update(range(next_line, next_line + count))
            next_line += count
            continue
        if current is not None and next_line is not None and line.startswith("+") and not line.startswith("+++"):
            next_line += 1
    return changed


def changed_lines(root: Path, base: str, head: str, suffix: str, evidence: Path) -> dict[str, set[int]]:
    try:
        return git_changed_lines(root, base, head, suffix)
    except (FileNotFoundError, subprocess.CalledProcessError) as error:
        sidecar = evidence.parent / "changed-lines.json"
        if not sidecar.is_file():
            raise RuntimeError(f"git unavailable and changed-line sidecar is missing: {sidecar}") from error
        raw = json.loads(sidecar.read_text(encoding="utf-8"))
        if not isinstance(raw, dict) or raw.get("base") != base or raw.get("head") != head:
            raise RuntimeError(
                "changed-line sidecar revisions do not match the requested base and head"
            ) from error
        files = raw.get("files")
        if not isinstance(files, dict):
            raise RuntimeError("changed-line sidecar files must be an object") from error
        return {path: {int(line) for line in lines} for path, lines in files.items()}


def source_key(package: str, name: str, source_root: str) -> str:
    package_path = package.strip("/").replace(".", "/")
    return "/".join(part for part in (source_root, package_path, name) if part)


def jacoco_lines(report: Path, source_root: str) -> tuple[dict[str, set[int]], dict[str, set[int]]]:
    root = ET.parse(report).getroot()
    executable: dict[str, set[int]] = {}
    covered: dict[str, set[int]] = {}

    def add(package: str, name: str, line_number: int, is_covered: bool) -> None:
        key = source_key(package, name, source_root)
        executable.setdefault(key, set()).add(line_number)
        if is_covered:
            covered.setdefault(key, set()).add(line_number)

    for package in root.findall(".//package"):
        package_name = package.attrib.get("name", "")
        for sourcefile in package.findall("sourcefile"):
            name = sourcefile.attrib["name"]
            for line in sourcefile.findall("line"):
                add(package_name, name, int(line.attrib["nr"]), int(line.attrib.get("ci", "0")) > 0)
        # Sourcefile nodes normally union nested classes. Keep class parsing as
        # a fallback and union it into the same line sets when present.
        for clazz in package.findall("class"):
            name = clazz.attrib.get("sourcefilename")
            if not name:
                continue
            for line in clazz.findall("lines/line"):
                add(package_name, name, int(line.attrib["nr"]), int(line.attrib.get("ci", "0")) > 0)
    return executable, covered


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--path", default="", help="repository path prefix for a nested module")
    parser.add_argument(
        "--allow-missing",
        action="append",
        default=[],
        help="changed source path allowed to be absent from JaCoCo (repeatable)",
    )
    parser.add_argument("--threshold", type=float, default=90.0)
    args = parser.parse_args()
    root = Path.cwd()
    if not args.report.is_file():
        raise SystemExit(f"missing JaCoCo report: {args.report}")
    changed = {
        path: lines
        for path, lines in changed_lines(root, args.base, args.head, ".java", args.evidence).items()
    }
    source_root = "/".join(part for part in (args.path.strip("/"), "src/main/java") if part)
    production_prefix = source_root.rstrip("/") + "/"
    changed = {
        path: lines
        for path, lines in changed.items()
        if lines and path.startswith(production_prefix)
    }
    executable, covered = jacoco_lines(args.report, source_root)
    missing_files = sorted(path for path in changed if path not in executable)
    allowed_missing = sorted(set(args.allow_missing))
    unexpected_missing = [path for path in missing_files if path not in allowed_missing]
    denominator = 0
    numerator = 0
    files: dict[str, dict[str, int]] = {}
    for path, lines in sorted(changed.items()):
        executable_changed = lines & executable.get(path, set())
        covered_changed = executable_changed & covered.get(path, set())
        if executable_changed:
            files[path] = {"covered": len(covered_changed), "executable": len(executable_changed)}
        denominator += len(executable_changed)
        numerator += len(covered_changed)
    percentage = numerator * 100.0 / denominator if denominator else 0.0
    result = {
        "base": args.base,
        "head": args.head,
        "report": str(args.report),
        "reportSha256": sha256(args.report),
        "covered": numerator,
        "executable": denominator,
        "percentage": round(percentage, 2),
        "threshold": args.threshold,
        "files": files,
        "missingFiles": missing_files,
        "allowlistedMissingFiles": [path for path in missing_files if path in allowed_missing],
    }
    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    args.evidence.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if unexpected_missing:
        print(
            "changed files are missing from JaCoCo: " + ", ".join(unexpected_missing),
            file=sys.stderr,
        )
        return 1
    if denominator == 0:
        print("changed executable-line denominator is zero", file=sys.stderr)
        return 1
    print(f"changed executable lines: {numerator}/{denominator} ({percentage:.2f}%)")
    if percentage < args.threshold:
        print(f"coverage below required threshold: {args.threshold:.2f}%", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ET.ParseError, OSError, ValueError, RuntimeError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
