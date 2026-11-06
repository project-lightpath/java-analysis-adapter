#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VPE_ROOT="${VPE_ROOT:-${ROOT}/../vulnerability-prioritization-engine}"
PROFILE="${PROFILE:-${VPE_ROOT}/profiles/fsi-spring-boot.json}"
RUN_ROOT="${RUN_ROOT:-${ROOT}/spike/runs}"

mkdir -p "${RUN_ROOT}"
export MAVEN_BIN="${MAVEN_BIN:-$(bash "${ROOT}/spike/scripts/bootstrap-maven.sh")}"
export JAVA_BIN="${JAVA_BIN:-${JAVA:-java}}"
RUN_DIR="$(mktemp -d "${RUN_ROOT}/manual-integration.XXXXXX")"
WORKSPACE="${RUN_DIR}/source"
mkdir -p "${WORKSPACE}"
CONTEXT="${RUN_DIR}/analysis-context.json"
REPORT="${RUN_DIR}/preparation-report.json"
FACTS="${RUN_DIR}/semantic-call-facts.json"
EVIDENCE="${RUN_DIR}/capability-evidence.json"

echo "[1/4] prepare immutable source, Maven build, and AnalysisContext"
(
  cd "${VPE_ROOT}"
  go run ./cmd/analysis-context \
    -mode prepare-manual \
    -profile "${PROFILE}" \
    -source-mode local \
    -workspace "${WORKSPACE}" \
    -maven-bin "${MAVEN_BIN}" \
    -java-bin "${JAVA_BIN}" \
    -output "${CONTEXT}" \
    -report "${REPORT}"
)

echo "[2/4] run Java adapter and emit SemanticCallFacts"
(
  cd "${ROOT}"
  make manual-analysis CONTEXT="${CONTEXT}" OUTPUT="${FACTS}"
)

echo "[3/4] validate provenance and capability evidence"
(
  cd "${ROOT}"
  python3 spike/scripts/validate_manual_integration.py \
    --context "${CONTEXT}" \
    --facts "${FACTS}" \
    --output "${EVIDENCE}"
)

echo "[4/4] manual integration passed"
echo "run directory: ${RUN_DIR}"
