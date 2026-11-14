#!/usr/bin/env python3
"""Unit tests for changed-production coverage evidence."""

from pathlib import Path
import sys
import unittest


SCRIPT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

import validate_changed_coverage  # noqa: E402


class ValidateChangedCoverageTest(unittest.TestCase):
    def test_self_test_fixtures_pass(self) -> None:
        self.assertEqual(validate_changed_coverage.self_test(), 0)

    def test_threshold_is_ninety_percent(self) -> None:
        self.assertEqual(validate_changed_coverage.THRESHOLD, 90.0)


if __name__ == "__main__":
    unittest.main()
