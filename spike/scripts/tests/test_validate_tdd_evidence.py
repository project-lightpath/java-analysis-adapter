#!/usr/bin/env python3
"""Unit tests for the TDD evidence validator."""

from pathlib import Path
import sys
import unittest


SCRIPT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

import validate_tdd_evidence  # noqa: E402


class ValidateTddEvidenceTest(unittest.TestCase):
    def test_self_test_fixtures_pass(self) -> None:
        self.assertEqual(validate_tdd_evidence.self_test(), 0)

    def test_red_record_requires_behavioral_marker(self) -> None:
        record = {
            "schemaVersion": "0.1.0",
            "finding": "fixture",
            "selector": "ExampleTest#behavior",
            "command": "mvn test",
            "testCount": 1,
            "outputSha256": "0" * 64,
            "exitCode": 1,
            "behavioralAssertion": False,
            "productionManifest": "unchanged",
        }
        with self.assertRaises(SystemExit):
            validate_tdd_evidence.validate_run_record(record, "red")


if __name__ == "__main__":
    unittest.main()
