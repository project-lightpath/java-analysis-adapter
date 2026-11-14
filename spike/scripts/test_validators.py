#!/usr/bin/env python3
"""Synthetic regression tests for the contract and changed-coverage validators."""

from __future__ import annotations

import importlib.util
import json
import os
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).parent


def load_module(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / filename)
    if spec is None or spec.loader is None:
        raise AssertionError(f"cannot load validator module: {filename}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


COVERAGE = load_module("validate_changed_coverage", "validate_changed_coverage.py")
CONTRACT = load_module("validate_contract_fixtures", "validate_contract_fixtures.py")


class ChangedCoverageValidatorTest(unittest.TestCase):
    """Covers revision binding and missing JaCoCo entries."""

    def test_sidecar_must_match_requested_revisions(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory) / "evidence.json"
            sidecar = evidence.parent / "changed-lines.json"
            sidecar.write_text(json.dumps({
                "base": "wrong-base",
                "head": "head",
                "files": {"src/Main.java": [4]},
            }), encoding="utf-8")
            with patch.object(
                COVERAGE,
                "git_changed_lines",
                side_effect=subprocess.CalledProcessError(1, "git"),
            ):
                with self.assertRaisesRegex(RuntimeError, "revisions do not match"):
                    COVERAGE.changed_lines(Path(directory), "base", "head", ".java", evidence)

    def test_missing_jacoco_file_is_reported_and_can_be_allowlisted(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "jacoco.xml"
            evidence = Path(directory) / "coverage.json"
            report.write_text("report", encoding="utf-8")
            changed = {
                "src/main/java/demo/Missing.java": {10},
                "src/main/java/demo/Present.java": {11},
            }
            executable = {"src/main/java/demo/Present.java": {11}}
            covered = {"src/main/java/demo/Present.java": {11}}
            argv = [
                "validate_changed_coverage.py",
                "--base", "base",
                "--head", "head",
                "--report", str(report),
                "--evidence", str(evidence),
            ]
            with patch.object(COVERAGE, "git_changed_lines", return_value=changed), \
                    patch.object(COVERAGE, "jacoco_lines", return_value=(executable, covered)), \
                    patch.object(sys, "argv", argv):
                self.assertEqual(1, COVERAGE.main())
            result = json.loads(evidence.read_text(encoding="utf-8"))
            self.assertEqual(["src/main/java/demo/Missing.java"], result["missingFiles"])

            allowlisted = Path(directory) / "allowlisted.json"
            argv.append("--allow-missing")
            argv.append("src/main/java/demo/Missing.java")
            argv[argv.index("--evidence") + 1] = str(allowlisted)
            with patch.object(COVERAGE, "git_changed_lines", return_value=changed), \
                    patch.object(COVERAGE, "jacoco_lines", return_value=(executable, covered)), \
                    patch.object(sys, "argv", argv):
                self.assertEqual(0, COVERAGE.main())


class ContractFixtureValidatorTest(unittest.TestCase):
    """Covers tracked symlink types and manifest ownership of the VPE fixture."""

    @staticmethod
    def mode(path: Path) -> str:
        actual = path.lstat().st_mode
        if stat.S_ISLNK(actual):
            return "120000"
        return "100755" if stat.S_IMODE(actual) & stat.S_IXUSR else "100644"

    def run_validator(self, java_root: Path, vpe_root: Path, manifest: Path, evidence: Path):
        argv = [
            "validate_contract_fixtures.py",
            "--java-root", str(java_root),
            "--vpe-root", str(vpe_root),
            "--vpe-sha", "vpe-commit",
            "--vpe-tree-manifest", str(manifest),
            "--evidence-root", str(evidence),
        ]
        with patch.object(sys, "argv", argv):
            return CONTRACT.main()

    def fixture_roots(self):
        directory = tempfile.TemporaryDirectory()
        root = Path(directory.name)
        java_root = root / "java"
        vpe_root = root / "vpe"
        fixture = "spike/contracts/dependency-coordinate-manifest.json"
        vpe_fixture = "analysiscontext/testdata/dependency-coordinate-manifest.json"
        (java_root / fixture).parent.mkdir(parents=True)
        (vpe_root / vpe_fixture).parent.mkdir(parents=True)
        content = b'{"schemaVersion":"0.1.0"}\n'
        (java_root / fixture).write_bytes(content)
        (vpe_root / vpe_fixture).write_bytes(content)
        return directory, root, java_root, vpe_root, fixture, vpe_fixture

    def entry(self, root: Path, path: str) -> dict[str, str]:
        candidate = root / path
        return {
            "path": path,
            "mode": self.mode(candidate),
            "blob": CONTRACT.git_blob(candidate, candidate.lstat().st_mode),
        }

    def test_tracked_symlink_type_is_validated_before_permissions(self):
        directory, root, java_root, vpe_root, _, vpe_fixture = self.fixture_roots()
        try:
            target = vpe_root / "target.txt"
            target.write_text("target", encoding="utf-8")
            link = vpe_root / "link.txt"
            os.symlink("target.txt", link)
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({
                "commit": "vpe-commit",
                "entries": [
                    self.entry(vpe_root, vpe_fixture),
                    self.entry(vpe_root, "target.txt"),
                    self.entry(vpe_root, "link.txt"),
                ],
            }), encoding="utf-8")
            self.assertEqual(0, self.run_validator(
                java_root, vpe_root, manifest, root / "evidence"))
        finally:
            directory.cleanup()

    def test_unmanifested_vpe_fixture_is_rejected(self):
        directory, root, java_root, vpe_root, _, _ = self.fixture_roots()
        try:
            other = vpe_root / "tracked.txt"
            other.write_text("tracked", encoding="utf-8")
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({
                "commit": "vpe-commit",
                "entries": [self.entry(vpe_root, "tracked.txt")],
            }), encoding="utf-8")
            with self.assertRaisesRegex(SystemExit, "not listed"):
                self.run_validator(java_root, vpe_root, manifest, root / "evidence")
        finally:
            directory.cleanup()


if __name__ == "__main__":
    unittest.main()
