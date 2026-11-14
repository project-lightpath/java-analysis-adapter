#!/usr/bin/env python3
"""Validate the cross-repository contract fixture and staged VPE tree."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import sys
from pathlib import Path


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def git_blob(path: Path, mode: str) -> str:
    if stat.S_ISLNK(mode):
        data = os.readlink(path).encode()
    else:
        data = path.read_bytes()
    header = f"blob {len(data)}\0".encode()
    return hashlib.sha1(header + data).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-root", type=Path, required=True)
    parser.add_argument("--vpe-root", type=Path, required=True)
    parser.add_argument("--vpe-sha", required=True)
    parser.add_argument("--vpe-tree-manifest", type=Path, required=True)
    parser.add_argument("--evidence-root", type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads(args.vpe_tree_manifest.read_text(encoding="utf-8"))
    if manifest.get("commit") != args.vpe_sha:
        raise SystemExit("VPE tree manifest commit does not match --vpe-sha")
    expected = {entry["path"]: entry for entry in manifest.get("entries", [])}
    if not expected:
        raise SystemExit("VPE tree manifest contains no tracked entries")
    for path, entry in expected.items():
        candidate = args.vpe_root / path
        try:
            mode = stat.S_IMODE(candidate.lstat().st_mode)
        except FileNotFoundError as error:
            raise SystemExit(f"VPE tree file is missing: {path}") from error
        expected_mode = int(entry["mode"], 8) & 0o7777
        if mode != expected_mode:
            raise SystemExit(f"VPE tree mode mismatch: {path}")
        if git_blob(candidate, candidate.lstat().st_mode) != entry["blob"]:
            raise SystemExit(f"VPE tree blob mismatch: {path}")
    java_fixture = args.java_root / "spike/contracts/dependency-coordinate-manifest.json"
    vpe_fixture = args.vpe_root / "analysiscontext/testdata/dependency-coordinate-manifest.json"
    java_bytes = java_fixture.read_bytes()
    vpe_bytes = vpe_fixture.read_bytes()
    if java_bytes != vpe_bytes:
        raise SystemExit("Java and VPE dependency-coordinate fixtures differ")
    result = {
        "vpeSha": args.vpe_sha,
        "trackedEntries": len(expected),
        "fixtureSha256": sha256_bytes(java_bytes),
        "fixtureBytesEqual": True,
    }
    args.evidence_root.mkdir(parents=True, exist_ok=True)
    (args.evidence_root / "contract-fixtures.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"contract fixtures equal: {result['fixtureSha256']}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
