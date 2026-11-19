#!/usr/bin/env python3
"""Run the adapter test suite in method-level shards.

The full Maven suite is dominated by one large test class whose cases each
compile a fixture and build a SootUp scene. Running each JVM serially costs
~10 minutes; splitting the test methods across independent project copies and
JVM forks brings the wall time down to the longest shard.

Usage:
    python3 spike/scripts/sharded_test.py [--parallel N] [--keep] [--filter PATTERN]

The script compiles the test classes once, discovers annotated test methods
from the sources, distributes them round-robin, and runs every shard in its own
copy of the project (so Maven's target/surefire temp files never collide).
Results are aggregated from the per-shard surefire XML reports.
"""

from __future__ import annotations

import argparse
import glob
import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

TEST_ANNOTATIONS = ("Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate")
ANNOTATION = "@(?:" + "|".join(TEST_ANNOTATIONS) + r")\b"
METHOD = re.compile(
    ANNOTATION
    + r"\s+(?:@[^\n]*\s+)*?"
    + r"(?:public\s+|private\s+|protected\s+)?"
    + r"(?:void|[A-Za-z0-9_<>,\[\] .]+)\s+(\w+)\s*\("
)
IGNORED = {".git", "target", ".serena", ".zvec-grep", "__pycache__", ".idea"}


def repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def maven_binary(root: Path) -> str:
    configured = os.environ.get("MAVEN_BIN")
    if configured:
        return configured
    found = shutil.which("mvn")
    if found:
        return found
    bootstrapped = subprocess.run(
        ["bash", str(root / "spike/scripts/bootstrap-maven.sh")],
        capture_output=True, text=True, check=True)
    return bootstrapped.stdout.strip().splitlines()[-1]


def discover(root: Path) -> list[tuple[str, list[str]]]:
    """Returns (class, [test method]) pairs discovered from annotations."""
    classes: list[tuple[str, list[str]]] = []
    for path in sorted((root / "src/test/java").rglob("*.java")):
        source = path.read_text(encoding="utf-8", errors="replace")
        methods = METHOD.findall(source)
        if not methods:
            continue
        name = path.stem
        classes.append((name, methods))
    return classes


def shard(classes: list[tuple[str, list[str]]], parallel: int) -> list[str]:
    shards: list[list[tuple[str, list[str]]]] = [[] for _ in range(parallel)]
    position = 0
    for name, methods in classes:
        if not methods:
            shards[position % parallel].append((name, []))
            position += 1
            continue
        for method in methods:
            shards[position % parallel].append((name, [method]))
            position += 1
    specs = []
    for entries in shards:
        by_class: dict[str, list[str]] = {}
        for name, methods in entries:
            by_class.setdefault(name, []).extend(methods)
        parts = [name if not methods else name + "#" + "+".join(methods)
                 for name, methods in by_class.items()]
        if parts:
            specs.append(",".join(parts))
    return specs


def copy_project(root: Path, destination: Path) -> None:
    shutil.copytree(
        root, destination,
        ignore=shutil.ignore_patterns(*IGNORED, "runs"),
        symlinks=True)
    target = destination / "target"
    target.mkdir(exist_ok=True)
    for child in ("classes", "test-classes"):
        os.symlink(root / "target" / child, target / child)


def run_shard(maven: str, base: Path, index: int, spec: str) -> tuple[int, str, Path]:
    directory = base / f"shard{index:02d}"
    copy_project(Path.cwd(), directory)
    log = base / f"shard{index:02d}.log"
    with log.open("w", encoding="utf-8") as output:
        result = subprocess.run(
            [maven, "-q", "-B", "surefire:test", f"-Dtest={spec}",
             "-DfailIfNoTests=false", "-f", "pom.xml"],
            cwd=directory, stdout=output, stderr=subprocess.STDOUT)
    return result.returncode, spec, directory


def aggregate(base: Path) -> tuple[int, int, int, int, int]:
    tests = failures = errors = skipped = reports = 0
    for path in glob.glob(str(base / "shard*/target/surefire-reports/TEST-*.xml")):
        root = ET.parse(path).getroot()
        tests += int(root.get("tests", 0))
        failures += int(root.get("failures", 0))
        errors += int(root.get("errors", 0))
        skipped += int(root.get("skipped", 0))
        reports += 1
    return tests, failures, errors, skipped, reports


def main() -> int:
    parser = argparse.ArgumentParser()
    default_parallel = int(os.environ.get("SHARDS", min(16, os.cpu_count() or 4)))
    parser.add_argument("--parallel", type=int, default=default_parallel)
    parser.add_argument("--keep", action="store_true")
    args = parser.parse_args()

    root = repo_root()
    os.chdir(root)
    maven = maven_binary(root)
    subprocess.run([maven, "-q", "-B", "test-compile", "-f", "pom.xml"], check=True)

    specs = shard(discover(root), max(1, args.parallel))
    base = Path(tempfile.mkdtemp(prefix="adapter-shards-"))
    print(f"running {len(specs)} shards (parallel={args.parallel})", flush=True)
    failures: list[int] = []
    with ThreadPoolExecutor(max_workers=max(1, args.parallel)) as pool:
        futures = [pool.submit(run_shard, maven, base, index, spec)
                   for index, spec in enumerate(specs)]
        for future in futures:
            code, spec, _ = future.result()
            if code != 0:
                failures.append(code)
                print(f"FAILED shard: {spec[:120]}", flush=True)

    tests, failed, errors, skipped, reports = aggregate(base)
    print(f"tests={tests} failures={failed} errors={errors} skipped={skipped} "
          f"reports={reports} shards={len(specs)}")
    if not args.keep:
        shutil.rmtree(base, ignore_errors=True)
    else:
        print(f"shard workspaces kept in {base}")
    return 1 if failures or failed or errors else 0


if __name__ == "__main__":
    sys.exit(main())
