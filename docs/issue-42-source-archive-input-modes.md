# Issue #42 — Source-archive input modes and routing

## What changed

The adapter now takes an **explicit input mode** selected by the caller or
SBOM, never guessed from file contents:

| Mode | Meaning | Descriptor/module truth |
|---|---|---|
| `SOURCE_TREE` (default when `inputMode` is absent) | preprovisioned cloned repository checkout | checked-in `src/main/resources/META-INF/services` plus any dependency binaries (mixed mode prefers binaries, provenance recorded per descriptor kind) |
| `SOURCE_ARCHIVE` | one or more `-sources.jar` ZIPs of `.java` files | SBOM-resolved dependency binaries; when none are resolved, the scan is marked incomplete |
| `BINARY_ARCHIVE` | dependency artifacts only | the #34 seekable-reader path, unchanged |

`SOURCE_ARCHIVE` runs extract each archive into one owner-only
`flpath-42-sources-` temporary root (POSIX 700 where supported) and route
the extracted trees through the existing source/javac analysis path as the
run's required source roots. The archives are never descriptor-scanned as
binaries. Every extraction failure rejects the run with
`IllegalArgumentException` after recursive cleanup; a sources JAR can no
longer produce a complete-looking descriptor scan with zero descriptors.

## Input-mode contract (caller/SBOM expectations)

- `inputMode` (`"SOURCE_TREE" | "SOURCE_ARCHIVE" | "BINARY_ARCHIVE"`) and
  `sourceArchives` (list of archive paths) are optional fields of the
  serialized AnalysisContext handoff. Unknown mode values, wildcard paths,
  symlinks, and missing files are rejected at load time.
- Build-time invariants: `SOURCE_ARCHIVE` requires ≥1 source archive and
  forbids declared source roots and literal dependency-classpath overlap;
  `SOURCE_TREE` and `BINARY_ARCHIVE` forbid source archives;
  `BINARY_ARCHIVE` also forbids source roots and requires ≥1 dependency
  classpath entry.
- A `SOURCE_ARCHIVE` run still requires `applicationClasses` (the compiled
  application from the build) and an existing `repositoryRoot` (the
  natural choice is the directory holding the provisioned archives);
  `dependencyClasspathRequired` must be false when no binaries are
  provisioned.
- A source archive is expected in Maven `-sources.jar` layout: packages at
  the top level. Repo-layout archives are extracted as-is; their
  `src/main/resources` services are not discovered by the resources
  convention (residual R8).
- Freshness does not yet fingerprint source archives (residual R7).

## New scan-global diagnostics and blast radius

Both diagnostics block **DEFINITE promotion for the whole scan** (they
enter `ServiceLoaderDescriptorScanner.isUnique`'s `diagnostics.isEmpty()`
conjunct), not just the archive that caused them:

- `descriptor_metadata_unavailable_for_source_archive` — a SOURCE_ARCHIVE
  run resolved no dependency binaries, so no descriptor or module metadata
  exists for the scan. ServiceLoader-dependent facts — including facts
  backed by `applicationClasses` descriptors — stay POSSIBLE, and the
  evidence carries `scanStatus: incomplete` instead of a silent
  zero-descriptor "complete" scan.
- `source_archive_on_dependency_classpath` — a wildcard dependency-classpath
  expansion resolved a declared source archive. The entry is excluded from
  the descriptor scan and the scan is marked incomplete.

## Limitations (not diagnostics)

An explicitly selected `SOURCE_TREE` with no resolved dependency binaries
records a scan limitation ("dependency binaries are unavailable…") that
surfaces in non-unique facts' limitation text. Limitations never block
DEFINITE promotion. The default mode (no `inputMode` field) records
nothing, byte-identical to previous behavior.

## Mixed mode

When both source inputs and SBOM-resolved binaries exist, binaries are
authoritative for descriptor/module truth: the existing
`classpathBacked` merge preference selects the binary descriptor, and the
`meta_inf_services` vs `source_meta_inf_services` descriptor kinds record
which side each descriptor came from.

## Extraction safety

- Enumeration and entry streams reuse the #34 seekable ZIP reader
  (read-only; the binary descriptor path is unchanged).
- Entry names are rejected when empty, absolute, containing `\`, NUL, `*`,
  or `.`/`..` segments, resolving to the extraction root itself, duplicated
  (non-directory names; duplicate directory entries are idempotent), or in
  file/directory conflict (including case-insensitive filesystem
  collisions, which surface as sanitized rejections).
- Bounds: 512 MiB total decompressed bytes per run (new policy), the 512
  MiB compressed archive-size gate re-applied before open, per-entry
  written length verified against the central directory, CRC verified by
  the seekable stream.
- The extractor only creates directories and files inside its fresh
  owner-only root and never follows links it did not create. Symlink-mode
  central-directory entries have no representation in the #34 entry record
  and extract as regular files (residual R1).
- Interruption cleans up and clears the thread interrupt flag; on any
  failed run the pending interrupt is cleared before the rejection
  propagates, so it cannot leak into later phases. Crash residue (one
  owner-only `flpath-42-sources-` root) requires ops cleanup, parity with
  the #34 spill root (residual R2); there is no `deleteOnExit`
  registration.
- `analyze(context, graph)` (the prebuilt-graph overload) rejects
  SOURCE_ARCHIVE contexts: a prebuilt graph cannot carry the extracted
  source roots, so the run fails closed instead of silently skipping the
  source/javac half.
- RunMetadata reports `sourceRoots` as empty for SOURCE_ARCHIVE runs (the
  caller's context has none by the mode invariant); `sourceArchives` in
  the input handoff is the record of what fed the run.
- The loader's `inputMode` values are the exact uppercase enum names
  (`SOURCE_TREE`, `SOURCE_ARCHIVE`, `BINARY_ARCHIVE`), unlike the
  lowercase-tolerant `framework`/`analysisScope` fields.

## Residuals

R1 symlink-mode entries undetectable without modifying the #34 parser;
R2 crash residue is ops cleanup; R3 the adapter does not compile sources
itself (`applicationClasses` required); R4 post-analysis cleanup failure
is best-effort and unreported; R5 no per-tenant extraction quota; R6 no
new provenance channel (existing descriptor kinds only); R7 source
archives are not freshness-fingerprinted; R8 repo-layout archives'
resources are not scanned; R9 sources that do not compile cleanly keep
runs at POSSIBLE through the existing `javac_source_error` diagnostics;
R10 a symlinked provisioning directory holding a source archive is
undetected (the final component is checked; read-only impact).

## Review closure

Adversarial plan review: 17 findings, folded into §1 before
implementation (wildcard-classpath leak, extraction budgets, display-path
stability, default-mode parity, provisioning contract, layout contract).

Post-implementation review (independent subagent): 12 findings.
Addressed in code and tests: F1 (prebuilt-graph overload fails closed),
F2 (TEST scope rejected), F3 (entry-name `*` rejected), F5/F6 (residue
assertions moved to the test temp dir, removing the tmpdir race), F7
(SOURCE_ARCHIVE display paths fall back to repository-relative for
non-extracted inputs), F9 (matrix cells: empty middle segment, wildcard
entry name, case-variant collision, directory-then-file conflict,
duplicate archive names, cross-archive budget accumulation, repo-layout
extraction, BINARY_ARCHIVE × source archives, loader BINARY_ARCHIVE
parse, TEST-scope cell, wildcard exclusion made observable).
Dispositioned as documented: F4 (RunMetadata reports empty sourceRoots;
sourceArchives is the input record), F8 (broadened interrupt clearing,
commented in code), F10 (uppercase inputMode contract, documented), F11
(docs wording fixed), F12 (R10 residual).

## Test gate

- `make adapter-test` on the default JDK: 442 tests total (371 recorded
  baseline; new tests in `SourceArchiveInputModeTest`,
  `SourceArchiveExtractorTest`, `SourceArchiveRoutingTest`,
  `SourceArchiveAdapterTest`).
- `make java-lint`: PASS, no new findings.
- `make evidence-validate`: PASS.
