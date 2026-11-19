# FLPATH-5123 evidence — byte-denominated enumeration budgets

Issue: https://redhat.atlassian.net/browse/FLPATH-5123
Branch: `flpath-5123/enumeration-budgets`
Base: `origin/main` @ `6a46a85`
Plan: `PLAN-5123.md` (v5, adversarial plan review `PLAN_REVIEW_CLEAN`)

## Change

The shared `MAX_ENUMERATED_ENTRIES = 1024` counter is gone. ServiceLoader
descriptor enumeration is bounded by a 2 GiB cumulative byte budget and
retained descriptor metadata by a 16 MiB cumulative budget; decompression stays
at 16 MiB. Both new budgets emit byte-denominated diagnostics
(`descriptor_enumeration_limit`, `descriptor_retention_limit`), roll the
current source back with zero partial results, and are scan-global, so one
diagnostic downgrades every ServiceLoader edge in the analysis to POSSIBLE.

## TDD trail

- `PLAN_5123` — spec-derived cells, gates, residuals, confidence 0.84.
- `RED_1` — new cells (1,600-entry dirs/archives, shared budget, classic
  65,535, 1,000,001 ZIP64, exhaustion/exact boundaries, UTF-8/extra charging,
  retention, certainty, orthogonality, ordering, defaults) plus a documented
  inert compile stub and package-private state-injection seam. 18 cells failed
  for the intended reason.
- `GREEN_1` — implementation, count cap deleted, surefire large-archive split.
  Closes both `RED_1` and `RED_2` (RED_1/RED_2 both landed before the first
  GREEN because the review round happened against the plan-era API).
- `RED_2` — review round 1 cells (retention restore, module-info atomicity,
  certainty, literal byte terms, classic EOCD/locator/payload); closed by
  `GREEN_1` plus the `GREEN_2` follow-up where noted.
- `RED_3` / `GREEN_2` — review round 2: directory and archive module-retention
  failures roll the whole source back; archive rollback is exception-safe.
- `RED_4` / `GREEN_3` — archive pre-state characterization; lint-driven
  `ServiceLoaderDescriptorEntryNames` extraction and mutable-list contract.
- `RED_5` / `GREEN_4` — PR review round: decompression partial-result and
  directory-listing/module-info cells, observed single-file retention charging,
  non-descriptor entries rejected before charging, and the test-count gate.

## Gates (final tree)

- `make adapter-test`: **302** tests (301 default + 1 large-archive),
  0 failures (baseline before RED: 275 tests, 0 failures). The target removes
  both report directories, then runs `spike/scripts/check_test_count.py 302`,
  which sums them and requires the large-archive execution to have run at
  least one test and the total to be at least 302.
- `make java-lint`: PASS, 0 findings outside the reviewed baseline, 0 CPD.
- `make evidence-validate`: passes (10 acceptance criteria, 3 candidates,
  10 capability cases, 21 expected relationships, 10 observed results).

## Acceptance evidence

1. **ssc-demo baseline** (`../spring-boot-example-application` @ its pinned
   checkout, 104 dependency jars): production `ServiceLoaderDescriptorScanner`
   returns **54 descriptors** with **0** enumeration, retention or decompression
   diagnostics. 12 pre-existing `versioned_module_info_unsupported`
   diagnostics remain (unrelated diagnostic-only condition; not a budget
   failure). Before this change the Jira records 5/54 descriptors with 99
   enumeration diagnostics. This is external evidence: reproduce with
   `spike/probes/flpath-5123/README.md`; it is not covered by
   `make evidence-validate`.
2. **65,535-entry classic ZIP** (`ServiceLoaderCoverageTest`): complete, one
   descriptor with its provider payload, EOCD count `0xFFFF`, no ZIP64 locator,
   zero diagnostics.
3. **1,000,001-entry ZIP64** (`@Tag("large-archive")`): complete, one
   descriptor, zero diagnostics. Local fixture: 99 MB, ~1.5 s write, ~0.2 s
   enumerate, ~74 MB `ZipFile` CEN heap.
4. **Exhaustion**: archive/directory enumeration and retention exhaustion each
   produce one byte-denominated diagnostic, zero descriptors from that source,
   restored retention, and POSSIBLE certainty. Exact-boundary cells pass at the
   charged total and fail at total - 1.

## Measurements and residuals

Local fixture measurements were taken on JDK 27 (macOS) with `-Xmx1g`; the
prototype's 22.4 MB heap and flat-memory claim is the seekable-parser result,
not the `java.util.zip.ZipFile` production path. Residuals R1-R11 are recorded
in `PLAN-5123.md`; the load-bearing ones are:

- R1: the 1 MiB per-entry control is prototype-only and was not added.
- R3: `ZipFile` still indexes the central directory eagerly (~74 MB for 1M
  entries); FLPATH-5124 is the durable fix.
- R6: all three budgets are cumulative scan-global volumes, not peak bounds.
- R11: provider-line payload amplification stays bounded only by the 16 MiB
  decompression budget (pre-existing).

## Review trail

- Plan review: v1 -> v2 -> v3 -> v4, `PLAN_REVIEW_CLEAN` on v4.
- Implementation review round 1 (9 findings): retention rollback restoration,
  module-info charging/atomicity, byte-term and classic-ZIP rigor, certainty
  coverage, budget javadoc and literal defaults — all addressed in
  `RED_2`/`GREEN_2`.
- Implementation review round 2 (3 findings): directory/archive
  module-retention partial results and exception-safe archive rollback —
  addressed in `RED_3`/`GREEN_2`; the archive pre-state cell is characterized
  in `RED_4`.
- Implementation review round 3: no new blocker or high finding; only the
  documented residuals below and the mutable-list contract (now documented).
- PR review round 4 (fresh PR-level reviewer): 10 findings addressed in
  `RED_5`/`GREEN_4` and this evidence update: decompression partial-result cell,
  directory-listing/module-info contract, comment-term cell, observed
  single-file retention charge, non-descriptor charge rejection, observable
  test-count gate, reproducible baseline probe, and evidence-trail numbering.
- PR review round 5 (fresh reviewer): 4 findings addressed in
  `RED_6`/`GREEN_5`: no-descriptor candidates (unreadable, decompression
  exhausted, duplicate, unprocessed after an early stop) release their
  retention slot; the misnamed archive-exhaustion cell no longer claims
  rollback coverage; plan version and count-gate wording corrected.
- CodeRabbit: rate-limited on this repository; `@coderabbitai full review` was
  posted and returned "Review rate limited", so no bot findings exist yet. The
  command must be re-run after the quota resets before merge.

## Java 17 parity

No JDK 17 runtime is installed in this environment (`/usr/libexec/java_home -V`
fails; only the local JDK 27 is available). Compilation targets `--release 17`;
runtime parity remains a mandatory CI gate before merge.
