#!/usr/bin/env python3
"""Print preparation-report diagnostics for a failed manual integration run.

The manual integration runner aborts with a bare non-zero exit status when
the VPE prepare-manual step fails; the actual diagnostics are only in the
preparation report JSON. This helper makes those failures visible: it prints
the report status and every diagnostic to stderr. It always exits 0 so it
never masks the runner's own failure exit code (#40).
"""

from __future__ import annotations

import json
import sys
from pathlib import Path


def main(argv: list[str] | None = None) -> int:
    arguments = sys.argv[1:] if argv is None else argv
    if len(arguments) != 1:
        print(
            "usage: print_preparation_diagnostics.py PREPARATION_REPORT_JSON",
            file=sys.stderr,
        )
        return 0
    report_path = Path(arguments[0])
    if not report_path.is_file():
        print(
            f"preparation report not found or not a file: {report_path}",
            file=sys.stderr,
        )
        return 0
    try:
        report = json.loads(report_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        print(
            f"preparation report is unreadable: {report_path} ({error})",
            file=sys.stderr,
        )
        return 0
    if not isinstance(report, dict):
        print(
            f"preparation report is unreadable: {report_path} "
            "(expected a JSON object)",
            file=sys.stderr,
        )
        return 0
    status = report.get("status", "unknown")
    profile = report.get("profileId")
    header = f"preparation report: {report_path} (status: {status}"
    if profile is not None:
        header += f", profile: {profile}"
    header += ")"
    print(header, file=sys.stderr)
    diagnostics = report.get("diagnostics")
    if diagnostics is None:
        print("no diagnostics were recorded in the report", file=sys.stderr)
        return 0
    if not isinstance(diagnostics, list):
        print(
            f"diagnostics field is malformed (not a list): {diagnostics!r}",
            file=sys.stderr,
        )
        return 0
    if not diagnostics:
        print("no diagnostics were recorded in the report", file=sys.stderr)
        return 0
    for diagnostic in diagnostics:
        if not isinstance(diagnostic, dict):
            print(f"  - {diagnostic!r}", file=sys.stderr)
            continue
        code = diagnostic.get("code", "unknown_code")
        severity = diagnostic.get("severity", "unknown_severity")
        phase = diagnostic.get("phase", "unknown_phase")
        message = diagnostic.get("message", "")
        print(
            f"  - [{severity}] {code} (phase: {phase}): {message}",
            file=sys.stderr,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
