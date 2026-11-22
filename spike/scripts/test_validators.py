#!/usr/bin/env python3
"""Synthetic regression tests for the contract and changed-coverage validators."""

from __future__ import annotations

import importlib.util
import contextlib
import io
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
                "src/test/java/demo/MainTest.java": {12},
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


class PreparationDiagnosticsPrinterTest(unittest.TestCase):
    """Covers fail-visible preparation diagnostics for the manual runner (#40)."""

    def load_printer(self):
        try:
            return load_module(
                "print_preparation_diagnostics", "print_preparation_diagnostics.py")
        except (FileNotFoundError, AssertionError):
            self.fail(
                "spike/scripts/print_preparation_diagnostics.py is missing; "
                "the manual runner cannot surface preparation failures")

    def run_printer(self, report: Path):
        module = self.load_printer()
        stderr = io.StringIO()
        stdout = io.StringIO()
        with patch.object(sys, "argv", ["print_preparation_diagnostics.py", str(report)]):
            with contextlib.redirect_stderr(stderr):
                with contextlib.redirect_stdout(stdout):
                    code = module.main()
        return code, stdout.getvalue(), stderr.getvalue()

    def test_prints_status_and_each_diagnostic(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "preparation-report.json"
            report.write_text(json.dumps({
                "status": "failed",
                "profileId": "fsi-spring-boot-production",
                "diagnostics": [
                    {
                        "code": "host_execution_forbidden",
                        "phase": "preparation",
                        "severity": "error",
                        "message": "prepare-manual is disabled",
                    },
                    {
                        "code": "acquisition_failed",
                        "phase": "acquisition",
                        "severity": "error",
                        "message": "local source path is unavailable",
                    },
                ],
            }), encoding="utf-8")
            code, _, stderr = self.run_printer(report)
            self.assertEqual(0, code)
            self.assertIn("failed", stderr)
            self.assertIn("host_execution_forbidden", stderr)
            self.assertIn("prepare-manual is disabled", stderr)
            self.assertIn("acquisition_failed", stderr)
            self.assertIn("local source path is unavailable", stderr)

    def test_missing_report_file_is_actionable(self):
        code, _, stderr = self.run_printer(Path("does-not-exist-report.json"))
        self.assertEqual(0, code)
        self.assertIn("does-not-exist-report.json", stderr)
        self.assertIn("not found or not a file", stderr)

    def test_directory_at_report_path_is_actionable(self):
        with tempfile.TemporaryDirectory() as directory:
            code, _, stderr = self.run_printer(Path(directory))
            self.assertEqual(0, code)
            self.assertIn("not found or not a file", stderr)

    def test_report_without_diagnostics_says_so(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "preparation-report.json"
            report.write_text(json.dumps({"status": "failed"}), encoding="utf-8")
            code, _, stderr = self.run_printer(report)
            self.assertEqual(0, code)
            self.assertIn("failed", stderr)
            self.assertIn("no diagnostics", stderr)
            empty = Path(directory) / "empty-diagnostics.json"
            empty.write_text(
                json.dumps({"status": "failed", "diagnostics": []}),
                encoding="utf-8")
            code, _, stderr = self.run_printer(empty)
            self.assertEqual(0, code)
            self.assertIn("no diagnostics", stderr)

    def test_falsy_profile_id_is_still_shown(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "preparation-report.json"
            report.write_text(
                json.dumps({"status": "failed", "profileId": ""}),
                encoding="utf-8")
            code, _, stderr = self.run_printer(report)
            self.assertEqual(0, code)
            self.assertIn("profile:", stderr)

    def test_argv_misuse_prints_usage_and_exits_zero(self):
        module = self.load_printer()
        for argv in (["print_preparation_diagnostics.py"],
                     ["print_preparation_diagnostics.py", "a", "b"]):
            with patch.object(sys, "argv", argv):
                with contextlib.redirect_stderr(io.StringIO()) as stderr:
                    code = module.main()
            self.assertEqual(0, code)
            self.assertIn("usage:", stderr.getvalue())
        # Pin the explicit-argv usage branch as well, not just the sys.argv default.
        for direct in ([], ["a", "b"]):
            with contextlib.redirect_stderr(io.StringIO()) as stderr:
                code = module.main(argv=direct)
            self.assertEqual(0, code)
            self.assertIn("usage:", stderr.getvalue())

    def test_non_dict_top_level_json_is_actionable(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "preparation-report.json"
            report.write_text("[1, 2]", encoding="utf-8")
            code, _, stderr = self.run_printer(report)
            self.assertEqual(0, code)
            self.assertIn("expected a JSON object", stderr)

    def test_non_dict_and_non_string_diagnostics_still_print(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "preparation-report.json"
            report.write_text(json.dumps({
                "status": "failed",
                "diagnostics": [
                    "plain-string",
                    {"code": 123, "severity": None, "phase": "p",
                     "message": {"nested": "obj"}},
                ],
            }), encoding="utf-8")
            code, _, stderr = self.run_printer(report)
            self.assertEqual(0, code)
            self.assertIn("plain-string", stderr)
            self.assertIn("123", stderr)
            self.assertIn("nested", stderr)

    def test_malformed_report_is_actionable(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "preparation-report.json"
            report.write_text("not json", encoding="utf-8")
            code, _, stderr = self.run_printer(report)
            self.assertEqual(0, code)
            self.assertIn("unreadable", stderr)


if __name__ == "__main__":
    unittest.main()
