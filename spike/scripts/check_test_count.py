#!/usr/bin/env python3
"""Assert both surefire executions ran the expected number of tests.

The adapter suite runs in two executions (default plus a large-archive
execution with its own heap and reports directory). `mvn test` prints two
"Tests run" lines and neither is the gate total, so the count gate has to sum
them here.
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path


def count(directory: Path) -> int:
    total = 0
    for report in sorted(directory.glob("TEST-*.xml")):
        root = ElementTree.parse(report).getroot()
        total += int(root.get("tests", "0"))
    return total


def main() -> int:
    minimum = int(sys.argv[1]) if len(sys.argv) > 1 else 302
    default = count(Path("target/surefire-reports"))
    large = count(Path("target/surefire-reports-large"))
    total = default + large
    print(
        f"surefire tests: default={default} large={large} "
        f"total={total} (recorded baseline {minimum})"
    )
    if large < 1:
        print("ERROR: large-archive execution ran no tests", file=sys.stderr)
        return 1
    if total < minimum:
        print("ERROR: test count decreased below the recorded baseline", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
