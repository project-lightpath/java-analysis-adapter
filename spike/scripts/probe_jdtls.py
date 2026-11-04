#!/usr/bin/env python3
"""Probe JDT LS call hierarchy for one fixture target using standard LSP."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import queue
import re
import shlex
import subprocess
import sys
import threading
import time
from pathlib import Path
from urllib.parse import quote


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_TARGET = ROOT / "spike/java21-quarkus/src/main/java/com/projectlightpath/fixture/target/UserAuthPublicKey.java"


class JsonRpc:
    def __init__(self, process: subprocess.Popen[bytes]):
        self.process = process
        self.messages: queue.Queue[dict] = queue.Queue()
        self.notifications: list[dict] = []
        self.next_id = 1
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self) -> None:
        stream = self.process.stdout
        assert stream is not None
        while True:
            header = stream.readline()
            if not header:
                return
            if not header.lower().startswith(b"content-length:"):
                continue
            length = int(header.split(b":", 1)[1].strip())
            while stream.readline().strip():
                pass
            body = stream.read(length)
            if not body:
                return
            self.messages.put(json.loads(body.decode("utf-8")))

    def send(self, payload: dict) -> None:
        encoded = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        stream = self.process.stdin
        assert stream is not None
        stream.write(f"Content-Length: {len(encoded)}\r\n\r\n".encode("ascii") + encoded)
        stream.flush()

    def request(self, method: str, params: dict, timeout: float = 60.0) -> dict:
        request_id = self.next_id
        self.next_id += 1
        self.send({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                message = self.messages.get(timeout=max(0.1, deadline - time.monotonic()))
            except queue.Empty:
                break
            if message.get("id") == request_id:
                return message
            self._handle_server_message(message)
        raise TimeoutError(f"timed out waiting for {method}")

    def drain(self, seconds: float) -> None:
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                self._handle_server_message(self.messages.get(timeout=0.2))
            except queue.Empty:
                pass

    def _handle_server_message(self, message: dict) -> None:
        if "method" not in message:
            self.notifications.append(message)
            return
        if "id" in message:
            method = message["method"]
            if method == "workspace/configuration":
                result = [{} for _ in message.get("params", {}).get("items", [])]
            elif method == "workspace/applyEdit":
                result = {"applied": False}
            else:
                result = None
            self.send({"jsonrpc": "2.0", "id": message["id"], "result": result})
        else:
            self.notifications.append(message)


def choose_config(home: Path) -> Path:
    machine = platform.machine().lower()
    candidates = ["config_mac_arm", "config_mac"] if platform.system() == "Darwin" and "arm" in machine else []
    candidates += ["config_linux", "config_linux_arm"]
    for candidate in candidates:
        path = home / candidate
        if path.is_dir():
            return path
    raise SystemExit(f"no JDT LS configuration found under {home}")


def source_position(path: Path, method: str) -> dict:
    text = path.read_text(encoding="utf-8")
    match = re.search(rf"\b{re.escape(method)}\s*\(", text)
    if not match:
        raise SystemExit(f"method {method} not found in {path}")
    before = text[: match.start()]
    return {"line": before.count("\n"), "character": match.start() - before.rfind("\n") - 1}


def command_output(command: list[str]) -> str | None:
    try:
        process = subprocess.run(command, capture_output=True, text=True, check=False)
    except FileNotFoundError:
        return None
    return (process.stdout + process.stderr).strip()


def tree_fingerprint(path: Path) -> str:
    digest = hashlib.sha256()
    for file_path in sorted(path.rglob("*")):
        if not file_path.is_file() or "target" in file_path.parts or ".jdtls-data" in file_path.parts:
            continue
        digest.update(file_path.relative_to(path).as_posix().encode())
        digest.update(hashlib.sha256(file_path.read_bytes()).digest())
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    jdtls_home = os.environ.get("JDTLS_HOME")
    parser.add_argument("--jdtls-home", type=Path, default=Path(jdtls_home) if jdtls_home else None)
    parser.add_argument("--java-executable", default=os.environ.get("JDTLS_JAVA", "java"))
    parser.add_argument("--workspace", type=Path, default=ROOT / "spike/java21-quarkus")
    parser.add_argument("--target-file", type=Path, default=DEFAULT_TARGET)
    parser.add_argument("--method", default="doAuth")
    parser.add_argument("--profile", choices=("production", "test"), default="production")
    parser.add_argument("--target-java-release", type=int, default=21)
    parser.add_argument("--data-dir", type=Path)
    parser.add_argument("--startup-timeout", type=float, default=180.0)
    parser.add_argument("--wait-seconds", type=float, default=20.0)
    parser.add_argument("--output", type=Path, default=ROOT / "spike/runs/jdtls-probe.json")
    args = parser.parse_args()
    if args.jdtls_home is None:
        raise SystemExit("set JDTLS_HOME or pass --jdtls-home")

    launcher = next(args.jdtls_home.glob("plugins/org.eclipse.equinox.launcher_*.jar"), None)
    if launcher is None:
        raise SystemExit(f"JDT LS launcher not found under {args.jdtls_home}")
    configuration = choose_config(args.jdtls_home)
    data_dir = args.data_dir or ROOT / "spike/.cache/jdtls-data" / f"{args.target_file.stem}-{args.method}"
    command = [
        args.java_executable,
        "-Declipse.application=org.eclipse.jdt.ls.core.id1",
        "-Declipse.product=org.eclipse.jdt.ls.core.product",
        "-Dlog.protocol=true",
        "-Dlog.level=WARNING",
        "-Xmx1G",
        "--add-modules=ALL-SYSTEM",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "-jar", str(launcher),
        "-configuration", str(configuration),
        "-data", str(data_dir),
    ]
    process = subprocess.Popen(command, cwd=args.workspace, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    rpc = JsonRpc(process)
    root_uri = args.workspace.resolve().as_uri()
    initialize = rpc.request(
        "initialize",
        {
            "processId": os.getpid(),
            "rootUri": root_uri,
            "workspaceFolders": [{"uri": root_uri, "name": args.workspace.name}],
            "capabilities": {
                "workspace": {"configuration": True, "workspaceFolders": True},
                "textDocument": {"callHierarchy": {"dynamicRegistration": False}},
            },
            "initializationOptions": {"bundles": []},
        },
        timeout=args.startup_timeout,
    )
    rpc.send({"jsonrpc": "2.0", "method": "initialized", "params": {}})
    rpc.send({"jsonrpc": "2.0", "method": "workspace/didChangeConfiguration", "params": {"settings": {"java": {"import": {"maven": {"enabled": True}}}}}})
    rpc.drain(args.wait_seconds)

    target_uri = args.target_file.resolve().as_uri()
    rpc.send({"jsonrpc": "2.0", "method": "textDocument/didOpen", "params": {"textDocument": {"uri": target_uri, "languageId": "java", "version": 1, "text": args.target_file.read_text(encoding="utf-8")}}})
    rpc.drain(3.0)
    position = source_position(args.target_file, args.method)
    symbols = rpc.request("textDocument/documentSymbol", {"textDocument": {"uri": target_uri}})
    prepared = rpc.request("textDocument/prepareCallHierarchy", {"textDocument": {"uri": target_uri}, "position": position})
    incoming = None
    items = prepared.get("result") or []
    if items:
        incoming = rpc.request("callHierarchy/incomingCalls", {"item": items[0]})

    stderr = b""
    if process.stderr is not None:
        try:
            stderr = process.stderr.read1(4096)
        except (AttributeError, BlockingIOError):
            pass
    try:
        rpc.request("shutdown", {}, timeout=5)
        rpc.send({"jsonrpc": "2.0", "method": "exit", "params": {}})
    except (TimeoutError, BrokenPipeError):
        pass
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()

    java_version = command_output([args.java_executable, "-version"])
    java_major_match = re.search(r'version "(\d+)', java_version or "")
    java_major = int(java_major_match.group(1)) if java_major_match else None
    result = {
        "schemaVersion": "0.1.0",
        "issue": "FLPATH-5000",
        "adr": "ADR-001",
        "recordedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "targetJavaRelease": args.target_java_release,
        "analyzerRuntimeJavaMajor": java_major,
        "authoritativeForTargetRelease": java_major is not None and java_major >= args.target_java_release,
        "provider": {"name": "Eclipse JDT Language Server", "version": "1.63.0"},
        "profile": args.profile,
        "workspace": str(args.workspace.resolve()),
        "fixtureFingerprint": tree_fingerprint(args.workspace),
        "gitRevision": command_output(["git", "rev-parse", "HEAD"]),
        "javaVersion": java_version,
        "command": shlex.join([sys.executable, *sys.argv]),
        "analyzerConfiguration": {
            "configuration": str(configuration),
            "dataDirectory": str(data_dir),
            "startupTimeout": args.startup_timeout,
            "waitSeconds": args.wait_seconds,
        },
        "target": {"file": str(args.target_file.resolve()), "method": args.method, "position": position},
        "initialize": initialize,
        "documentSymbols": symbols,
        "preparedCallHierarchy": prepared,
        "incomingCalls": incoming,
        "notifications": rpc.notifications[-100:],
        "stderr": stderr.decode("utf-8", errors="replace")[-4000:],
        "status": "verified" if incoming and incoming.get("result") is not None else "unverified",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(args.output)


if __name__ == "__main__":
    main()
