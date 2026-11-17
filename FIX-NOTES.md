# FLPATH-5078 follow-up: two correctness fixes in the external seekable-ZIP prototype

Status: READY_FOR_INDEPENDENT_REVIEW (external prototype only; NOT production integrated).

Workdir: /private/var/folders/r7/gktmmltd1zq7wqhsjjslwsm80000gn/T/opencode/pr7-5078-fix-Wkg2ZqA
Reproduce: python3 verify.py <label>   (compiles workdir SeekProbe.java/Spill.java
against the clean production checkout a2f36294abc0d4ecd0e92b4657d5a4f79ea7c30b,
runs the full suite; exit 0 = all checks pass)
JDK: Temurin 21.0.13+9, javac --release 17 (Java 17 runtime parity NOT established).
Children: -Xmx32m, 90s process-group deadline, scoped temp cleanup, residual checked.

Sources: SeekProbe.java/Spill.java copied from the prototype
(sha256 92920a67.../87860be1...), fixed only in this directory.
Originals in ../pr7-seekable-revision-prjUvQCc remain byte-identical; production
checkout clean before/after.

## Fix 1 — failed-decompression accounting (SeekProbe.java body(), was lines ~219-228)
Inflater.inflate() can emit output and then throw DataFormatException. The charge
only ran after a successful return, so exceptional output escaped the shared
expansion budget. Now the getBytesWritten() delta around each inflate() call
charges produced bytes exactly once for successful AND exceptional calls, then
rethrows invalid_deflate. Exhausted-budget state, the one-sentinel-byte boundary
semantics, per-entry maximum and transactional rollback are unchanged (charge
still consumes min(bytes,remaining) and requires bytes<=remaining; the redundant
outer catch(DataFormatException) was removed because it no longer compiles).
JDK behavior pinned by the independent review: after DataFormatException,
getBytesWritten() reports the bytes emitted before the throw (4096 in the fixture).

## Fix 2 — classic ZIP entry-count boundary (SeekProbe.java directory(), was line 80)
expected==65535 alone no longer implies a ZIP64 locator is required; 65535 is a
valid classic unsigned-16-bit count (Python zipfile allowZip64=False and JDK
ZipFile both accept it). sentinel is now bytes==U32 || offset==U32 only. Strict
validation retained: genuine size/offset sentinels without a locator still fail
missing_zip64_locator; locator/zip64-EOCD record structure, bounds, counts,
impossible_entry_count and the walk's cen_count_mismatch (actual enumerated count
must equal expected) all still apply.

## Harness corrections applied in verify.py
- Every run: timeout, nonzero exit, stderr crash/OOM, residual temp files, or
  opened!=closed FAILS validation (sanity checks per run).
- Successful non-faulted runs assert complete expected metadata: exact D-line
  equality with the independent JDK ZipFile reference engine (module-many-provides
  order-insensitively — engines emit module-provides descriptors in different
  order, identical content).
- Failure-injection runs assert exact diagnostics (seek_result_incomplete with
  injected_spill_read_failure; seek_incomplete/selected_size_or_crc_mismatch) AND
  absence of partial results (no D lines / only prior-archive evidence).

## Before (original sources, results-before-final.json): 8 checks failed
- defect1_budget_escape: 5x invalid_deflate, expanded=0 (4096 bytes escaped).
- defect1_certainty_escape: later valid archive produced a descriptor on the
  escaped budget; expanded=21 only.
- defect2_classic_65535: missing_zip64_locator, no descriptor (reference: 1).
- defect2_neg_count_mismatch: rejected with missing_zip64_locator instead of
  cen_count_mismatch.
- All other 303 checks passed (differential, resource-boundary, cleanup, fault
  injection, malformed corpus).

## After (fixed sources, results-after-final2.json): 0 of 311 checks failed
- defect1: diagnostics [invalid_deflate, archive_decompression_limit x4] and
  [invalid_deflate, archive_decompression_limit x5]; expanded=4096 exactly once;
  no descriptor from the later valid archive.
- defect2: classic 65535 accepted, D line identical to reference; claimed 65535
  with 65534 real entries -> cen_count_mismatch; size sentinel still
  missing_zip64_locator; genuine ZIP64 (zip64-70003-late.jar) accepted and
  identical to reference.

## Independent subagent review (openai/gpt-6.1-sol, medium reasoning, Engram-grounded)
Verdict: APPROVE, 96% confidence, no escalation. Confirmed exact-once charging,
preserved budget/sentinel/rollback semantics, no ZIP64 strictness weakening, and
that recorded before/after results support both fixes (8 -> 0 of 311 checks;
inflater probe re-run: bytesWritten=4096). Two minor harness weaknesses were
reported and BOTH fixed, then the suite re-run:
- extra-zip64-size corpus acceptance is now pinned to the spec rule (lengths
  0/8/16/24/28 accepted with reference-identical metadata; all others rejected
  with invalid_zip64_extra_block_length) instead of accepting any diagnostic.
- certainty assertions now pin 'R certainty=POSSIBLE' instead of line presence.
Hardened re-run (results-after-review-hardened.json): 0 of 315 checks failed.

## Remaining limitations
- Lab controls (spike.* budgets) are not proposed product defaults.
- No Java 17 runtime parity (compiled --release 17, executed on JDK 21.0.13+9).
- Module-provides descriptor ordering differs between seek and reference engines
  (content-identical); documented, not fixed.
- Known pre-existing prototype exclusions unchanged (prefixed ZIP64, ignored
  payload integrity, snapshot/concurrency/cancellation/transport durability).
