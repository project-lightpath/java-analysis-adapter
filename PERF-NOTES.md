# FLPATH-5078 — performance experiments and settled numbers

Status: external prototype evidence only. Canonical reviewed artifacts (root of
this workdir) are untouched; everything perf-related lives in perf-exp/ and was
verified behavior-identical with knobs off (perf-exp/verify-perf.py: full
317-check suite, 0 failures).

Environments: helios08.lab.eng.tlv2.redhat.com (40-core x86_64, Fedora 43,
JDK 21.0.12.1, /root/flpath-5078-bench) and local macOS (JDK 21.0.13+9).
Benchmarks: bench_memory.py (memory-vs-entries sweep, results
bench-memory-vs-entries.json / bench-memory-helios08.json); perf sweeps below.
Fixtures: bench-fixtures/entries-N.jar (1k..1M+2M, incl. hand-built ZIP64) and
decomp-*.jar (raw-DEFLATE service payloads). Accuracy was the pass condition
everywhere: exit 0, zero diagnostics, exactly one correct descriptor,
cenEntries == N, no residual temp files.

## 1. Memory-vs-entries (uncapped enumeration) — SETTLED
- Heap flat: 10-12 MB (at -Xmx32m) / 22.4 MB (at -Xmx128m) / 42.3 MB (at
  -Xmx256m) from 1k through 2,000,000 entries. GC ergonomics, not workload.
- RSS flat: ~94 MB (128m) / ~113-116 MB (256m) at 1M and 2M entries.
- Spill disk linear ~136 B/entry; raw directory reads ~122 B/entry.
- Capacity formula: max entries ~ min(rawMax/122 B, spillMax/136 B).
- 128 MiB heap verified accurate at 1M and 2M entries. See POLICY-NUMBERS.md.

## 2. Decompression budget sweep (16/32/64/96 MiB, -Xmx128m)
- Step function, per-archive all-or-nothing (transactional rollback): a 21.7 MiB
  selected payload yields 0 descriptors at 16 MiB and 6,000 at >=32 MiB.
- Memory cost ~zero (heap peak 24-37 MB transient; bytes streamed, not held).
- Scan time rises with budget (more payload inflated): completeness knob,
  NOT a speed knob.

## 3. Read buffers (spike.readAhead, 2-slot LRU read coalescing)
- Seeks 7,000,004 -> 2,000,063 at 1M entries, but scan 68.6 s -> 64.9 s (-5%).
- Single-slot cache thrashes: the walk alternates CEN and local-header streams.
- VERDICT: not worth enabling.

## 4. In-memory sort fallback (spike.sortMemory) — THE LEVER
Sort.sort() keeps the whole entry buffer in heap when the estimated cost
(256 + 2*nameLen bytes/entry, ~290 B/entry) fits under the cap; falls back to
the disk sort otherwise. next()/rewind()/rollback semantics unchanged.
- 50k entries: 2,549 ms -> 340 ms (7.5x); spill 23.8 MB -> ~0.
- 100k entries: 5,598 ms -> 526 ms (10.6x); spill 54 MB -> ~0.
- 1M entries at sortMemory=1 GiB, -Xmx256m: 71.0 s -> 3.8 s (18.7x), RSS 184 MB;
  at -Xmx128m: 5.5 s (GC pressure; works).
- 32 MiB cap covers ~110k entries; 200k at 32 MiB still spills (only 9% gain).

## 5. Bigger merge runs (spike.runBytes) — the disk-mode fallback
- 1M entries: 71.0 s (256 KiB) -> 45.2 s (4 MiB) -> 33.1 s (16 MiB runs);
  spill churn 748 MB -> 340 MB; RSS +25 MB.

## Settled recommendation (if performance matters)
- spike.sortMemory = 64-96 MiB at 256 MiB heap: every realistic archive
  (<= ~250-350k entries) sorts in memory; disk only for monsters.
- spike.runBytes = 16 MiB as the disk-mode fallback.
- Do not bother with read-ahead; do not touch the decompression budget for speed.

## Caveats
- All runs JDK 21 (two vendors/patches); Java 17 runtime parity unverified.
- Single-JVM, cold-cache, non-concurrent; no load/concurrency measurements.
- perf-exp is an experiment: any production adoption goes through the
  integration-PR review gates in prod-integration-review.md.

## Fixture lessons (recorded for reproducibility)
- Python zlib.compress emits zlib-wrapped streams; the prototype inflates RAW
  DEFLATE (Inflater(true)) — use compressobj(wbits=-15).
- Hand-built ZIP64 EOCDs: the classic EOCD must carry the true CEN offset;
  SeekProbe correctly rejects a mismatch (zip64_eocd_mismatch) where python
  zipfile tolerates it.
- First perf-variant batch had a spilled-flag gating bug (sortMemory=0 silently
  took the in-memory path, invalidating controls); caught before interpreting,
  fixed with an explicit inMemory flag decided at finish().
