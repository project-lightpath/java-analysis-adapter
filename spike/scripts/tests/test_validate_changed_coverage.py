#!/usr/bin/env python3
"""Unit tests for changed-production coverage evidence."""

from pathlib import Path
import sys
import unittest
from types import SimpleNamespace
from unittest.mock import patch


SCRIPT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

import validate_changed_coverage  # noqa: E402


class ValidateChangedCoverageTest(unittest.TestCase):
    def test_self_test_fixtures_pass(self) -> None:
        self.assertEqual(validate_changed_coverage.self_test(), 0)

    def test_threshold_is_ninety_percent(self) -> None:
        self.assertEqual(validate_changed_coverage.THRESHOLD, 90.0)

    def test_unmapped_changed_lines_are_excluded_from_executable_denominator(self) -> None:
        executable, non_executable = validate_changed_coverage.partition_changed_lines(
            {10, 11, 12}, {10: (1, 0), 11: (0, 1)})
        self.assertEqual(executable, {10, 11})
        self.assertEqual(non_executable, {12})

    @patch("validate_changed_coverage.subprocess.run")
    def test_java_version_reads_adoptium_vendor_from_settings(
        self, run: object
    ) -> None:
        run.return_value = SimpleNamespace(
            returncode=0,
            stdout="",
            stderr='java.version = 21.0.13\njava.vendor = Eclipse Adoptium\n',
        )
        self.assertIn("Eclipse Adoptium", validate_changed_coverage.java21_version())
        run.assert_called_once_with(
            ["java", "-XshowSettings:properties", "-version"],
            capture_output=True,
            text=True,
            check=False,
        )


if __name__ == "__main__":
    unittest.main()
