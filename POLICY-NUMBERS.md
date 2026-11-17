# FLPATH-5078 — settled enumeration/resource policy numbers

Grounded in: baseline triage (spring-boot-example-application, 104 jars / 35,112
entries), cross-machine benchmark (macOS JDK 21.0.13 + helios08 JDK 21.0.12,
1k–1M entries, 11/11 accurate on both), and 2M-entry confirmation at -Xmx256m.
All measurements in /root/flpath-5078-bench (helios08) and this workdir.

## Settled policy (recommendation for the integration PR)

| Control | Value | Grounding |
|---|---|---|
| Entry-count cap | NONE (remove MAX_ENUMERATED_ENTRIES) | heap flat 10–42 MB from 1k to 2M entries; count caps reject legitimate archives (baseline trips 1,024 after ~9 jars; FSI uber-JARs exceed 65,535) |
| JVM heap | 128 MiB per analysis (256 MiB optional) | measured at -Xmx128m: peak 22.4 MB, RSS ~94 MB, accurate at 1M AND 2M entries; 32 MiB also works (11.9 MB peak) if tighter co-tenancy matters |
| Directory-read budget (rawMax) | 2 GiB | ~122 B/entry measured -> ~16M entries; baseline consumes 12.9 MB |
| Spill quota (spillMax) | 2 GiB | ~136 B/entry measured -> ~15M entries, aligned with rawMax; baseline consumes 731 KB |
| Decompressed budget | 16 MiB shared (unchanged) | existing production semantic; orthogonal to entry count |
| Per-entry materialization | 1 MiB (unchanged) | service files / module-info are tiny; blocks pathological entries |
| Wall-clock deadline | recommended, TBD by ops | only remaining unbounded axis; user has deprioritized time |

Capacity formula: max entries ~ min(rawMax/122 B, spillMax/136 B).
2 GiB each -> ~15–16M entries, ~42 MB heap, ~116 MB RSS observed ceiling shape.

## Ops sizing notes
- Temp storage: peak live spill ~136 B/entry (2M entries -> 272 MB peak,
  1.6 GB written + 1.6 GB read churn per scan). Size temp volume for
  concurrent scans accordingly; crash cleanup of spill roots is required.
- Memory does NOT grow with entries by design (external spill sorting);
  RSS observed flat ~113–116 MB at 1M and 2M entries with -Xmx256m.
- Budget exhaustion degrades gracefully: precise byte-denominated diagnostic
  (experimental_raw_io_budget / spill quota) + transactional rollback, no
  partial results — verified by fault-injection tests.

## Rejected alternatives, with evidence
- 1,024 shared cap (current production): trips on ~9 median jars; baseline app
  yields 5 of 54 descriptors (91% blind). Not a tuning question.
- 65,535 per-archive cap: classic-format boundary only; FSI uber-JARs are
  ZIP64 and exceed it (synthetic corpus merge: 163,417 entries).
- Any count cap: no number is both safe and useful; heap is not entry-bound.

## Caveats
- Java 17 runtime parity still unverified (all runs on JDK 21).
- Numbers are single-JVM, cold-cache, non-concurrent; production sizing under
  tenant concurrency needs a follow-up load measurement.
- These are recommendations from prototype evidence; the integration PR's
  fresh adversarial review must ratify them (per prod-integration-review.md
  policy gate).
