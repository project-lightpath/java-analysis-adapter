# FLPATH-5124 — seekable ZIP metadata reader

## What changed

`ServiceLoaderDescriptorArchiveSource` no longer opens `java.util.zip.ZipFile`
before applying the descriptor budgets. A bounded, seekable reader parses the
EOCD/ZIP64 directory, validates each central/local record, spills selected
entries and referenced ranges through a two-tape external sort, and only then
inflates selected service files and `module-info.class`.

The production call sites, descriptor model, diagnostics ordering, WAR
diagnostics, module handling and rollback semantics are unchanged. The new
classes are package-private:

- `ServiceLoaderDescriptorArchiveConfig` — per-archive raw-I/O, spill, sort and
  merge budgets plus the temp root; the channel opener and cleanup hook are
  test seams.
- `ServiceLoaderDescriptorSpill` — owner-only temp root, live spill quota,
  two-tape merge sort, per-sort 16 MiB in-memory quotas (32 MiB per archive).
- `ServiceLoaderDescriptorZipDirectory` — EOCD/ZIP64/CEN parser, local/data
  descriptor validation and the entry `InputStream`.
- `ServiceLoaderDescriptorZipEntry` — one validated central record.

## Policy gate (ratified numbers)

| Control | Value | Scope |
|---|---|---|
| Enumeration | 2 GiB | scan-global (FLPATH-5123) |
| Retained descriptor slots | 16 MiB at 512 B/candidate | scan-global (FLPATH-5123) |
| Retained selected names | 4 MiB (UTF-8 + 48 B String header) | scan-global, new |
| Decompressed payload | 16 MiB | scan-global (unchanged) |
| Raw directory/header I/O | 2 GiB | per archive |
| Spill live bytes | 2 GiB | per archive |
| In-memory sort | 32 MiB total (16 MiB selected + 16 MiB ranges) | per archive |
| Merge run | 16 MiB | per archive |
| Archive size | 512 MiB | unchanged |

The prototype's 1 MiB per-entry materialization control is **not** adopted:
production has never had it, and introducing it changed the existing
`moduleBudgetExhaustionIsDiagnosed` contract (a 16 MiB+1 service file must
reach the shared decompression limit). The shared 16 MiB payload budget remains
the materialization bound. Recorded as residual R2.

No wall-clock deadline is added (ops-owned, prototype deprioritized). Recorded
as residual R3.

## Compatibility gate

| Input | Decision |
|---|---|
| Classic ZIP, <= 65,535 entries | accept; 65,535 count alone is not a ZIP64 sentinel |
| ZIP64 with locator and consistent EOCD | accept |
| Size/offset sentinel without locator | reject (`invalid_archive`) |
| Prefixed/SFX classic | accept (computed prefix) |
| Prefixed ZIP64 | reject (prototype-strict) |
| Encrypted, multi-disk, unsupported method/flag | reject (`invalid_archive`) |
| Data descriptor (4/8-byte, with or without signature, including CRC == signature) | accept after validating both candidate layouts |
| Local/CEN name/flags/method/size/CRC mismatch | reject |
| Overlapping referenced ranges | reject (duplicate CEN records pointing at one local range are allowed) |
| Duplicate selected name | first CEN occurrence wins, no diagnostic (TreeMap parity) |
| Name/comment encoding | strict UTF-8 for both EFS states; an EFS-clear non-UTF-8 legacy name is rejected as `invalid_legacy_name` instead of being silently dropped after replacement |
| Malformed non-ZIP64 extra bytes | tolerated (JDK `ZipFile` parity); ZIP64 tag is strict |
| Selected payload CRC | verified at EOF |
| Archive > 512 MiB | `archive_size_limit` before open |

New scan-global diagnostic codes and blast radius: they block DEFINITE
promotion for the whole scan (`ServiceLoaderDescriptorScanner.isUnique`):

- `archive_raw_io_limit` — per-archive raw I/O over 2 GiB; archive rolled back.
- `archive_spill_limit` — per-archive live spill over 2 GiB; archive rolled back.
- `archive_spill_error` — temp I/O or cleanup failure; archive path and a
  sanitized detail (temp root stripped). On the success-path cleanup failure
  descriptors are kept; every other spill failure rolls the archive back.
- `descriptor_scan_interrupted` — current archive rolled back and later sources
  skipped; thread interrupt is cleared after cleanup.

## Test gate

- `make adapter-test` on the default JDK 27: 372 default + 1 large-archive
  test, 0 failures.
- `make adapter-test` on Temurin 17.0.21.1+2: 372 default + 1 large-archive
  test, 0 failures.
- `make java-lint`: PASS with the reviewed 22-entry maintainability baseline
  for the new parser/spill state machines; 0 CPD duplications; 0 suppressions.
- `make evidence-validate`: PASS.

New tests:

- `ServiceLoaderSeekableZipTest` — EOCD count mismatch, prefixed classic,
  EFS-clear decoding and malformed-EFS rejection, data descriptors with and
  without signature, encrypted-entry rejection, differential parity against
  `ZipFile`, exceptional DEFLATE accounting, walk- and payload-phase interrupt
  rollback/cleanup (with the flag cleared), spill quota, successful cleanup,
  long-name budget, mutating channel.
- `ServiceLoaderDescriptorSpillTest` — external sort order, multi-run merge,
  quota, interruption, tape/root cleanup failures, cleanup.
- `ServiceLoaderDescriptorRetentionSeamTest` — exact name budget, snapshot
  restore, release symmetry.

The existing classic-65,535 and 1,000,001-entry ZIP64 tests remain green.

A Java 17 path-order difference surfaced in the full gate:
`TreeSet<Path>` natural order differs for non-ASCII names between runtimes. The
directory candidate set now uses file-name order then path order, preserving
the smallest-first contract on both runtimes.

## Boundedness gate

Probe: `SeekableZipBoundednessProbe`, Temurin 17, `-Xmx128m`.

| Entries | Scan ms | Descriptors | Diagnostics | Peak heap used | Max RSS |
|---|---|---|---|---|---|
| 1,000 | 77 | 1 | 0 | 7.2 MB | — |
| 100,000 | 3,338 | 1 | 0 | 82.0 MB | — |
| 1,000,000 | 101,040 | 1 | 0 | 80.8 MB | 161.6 MB |

Heap is flat from 100k to 1M entries at the adopted 32 MiB sort budget; the
remaining ~80 MB is the JVM plus the bounded sort buffer, not an entry-count
term. RSS at 1M is 161.6 MB. Temp roots are removed on success, format/entry
failure, interruption and quota failure; a JVM crash can leave one owner-only
`flpath-5124-spill-` root (ops cleanup, no cross-process deletion).

This probe is manual evidence, not a JUnit test. The 1M-entry JUnit test runs
under the large-archive execution at `-Xmx1g`.

## Remaining residuals

- R1: `MAX_ARCHIVE_BYTES = 512 MiB` retained; larger archives are rejected
  before open.
- R2: no 1 MiB per-entry materialization cap; the shared 16 MiB decompressed
  budget and stream size/CRC validation bound payload materialization.
- R3: no wall-clock deadline.
- R4: enumeration/retention are cumulative volumes, not live peaks
  (FLPATH-5123).
- R5: prefixed ZIP64 unsupported.
- R6: malformed-structure strictness is higher than `ZipFile` (overlap,
  local/CEN disagreement); valid-archive parity is tested.
- R7: SootUp's archive class-loading path is unchanged.
- R8: crash residue requires ops cleanup.
- R9: no tenant/analysis aggregate spill quota; scans are sequential per
  analysis, so at most one spill root is live per analysis. Concurrent
  analyses are bounded by the caller's concurrency.
- R10: changed-line coverage is evidenced, not gated. JaCoCo 0.8.13 on
  Temurin 17, instrumented `ServiceLoader*`/`JavaAnalysisAdapterTest` subset:
  `ZipDirectory` 88.5%, `Spill` 83.9%, `Config` 92.6%, `ArchiveSource` 83.0%,
  `ZipEntry` 100%, aggregate 85.8% (483/563 changed/new lines). The POM carries
  an empty `jacoco.agent` property in both Surefire argLines; no coverage gate
  is added to `make`.

## Review closure

- Plan review: rounds 1-9, findings folded into PLAN-5124 §10.
- Post-implementation review: 5 findings; closed in `REVIEW_1`
  (payload-phase interrupt, flag clearing, descriptor/EFS/encryption tests,
  coverage receipt, close-diagnostic mapping).
- CodeRabbit (`@coderabbitai full review`): 12 actionable comments; closed in
  `REVIEW_2`:
  - own/close the channel before spill construction;
  - close `selectedSort` if `rangeSort` construction fails;
  - include directory entries in overlap validation;
  - drop retained comments from selected entries;
  - propagate raw-I/O and interrupt failures from payload reads;
  - handle `DirectoryIteratorException` during spill cleanup;
  - prefer a nonempty EOCD candidate and validate the CEN signature;
  - only interpret a ZIP64 locator when a sentinel requires it;
  - preserve ZIP64 descriptor width from the CEN sentinel;
  - shift the stream buffer after a partial bulk read;
  - try-with-resources for test sorts and no leaked `Files.list` streams;
  - remove per-archive/tape `deleteOnExit` registrations (crash residue R8).
- A second `@coderabbitai full review` after the quota reset posted 7 findings;
  closed in `REVIEW_3`:
  - surface `sort.close()` failures instead of swallowing them;
  - wrap spill root/tape creation failures in `SpillException`;
  - use the opened channel's size (and re-apply the size limit);
  - translate interruption during the channel seek;
  - reject undecodable EFS-clear legacy names instead of silently dropping
    them after replacement (`invalid_legacy_name`);
  - validate a ZIP64 locator even when classic fields fit, with a classic
    fallback when it is not required;
  - reject a truncated ZIP64 extra block before the opaque-extra fallback.

## Six production-integration preconditions

1. **Java runtime** — full suite and boundedness probe on Temurin 17.0.21.1+2;
   default JDK 27 also green.
2. **Policy** — §"Policy gate"; the 1 MiB waiver is explicit (R2).
3. **Compatibility** — §"Compatibility gate".
4. **Boundedness** — §"Boundedness gate"; CEN, selected/ranges spill,
   retained names, payload and temp lifetime are bounded.
5. **Tests** — §"Test gate"; prototype regressions ported as equivalence-class
   tests, existing suite green.
6. **Review** — fresh adversarial review of the production diff is performed
   after this document and its findings are dispositioned before merge.
