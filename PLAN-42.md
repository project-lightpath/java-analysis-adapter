# PLAN-42 — Source-archive input modes and routing

Status: implemented; all gates green on the default JDK 27 and Temurin
17.0.21.1+2 (see §10).
Issue: project-lightpath/java-analysis-adapter#42.
Branch: `feature/issue-42-source-archive`, based on `origin/main` `487a14b` (merge of PR #34).
Confidence: **87%** post-amendment (see §9).

## 0. Governing facts (verified in this worktree)

- A sources JAR today can only enter through `dependencyClasspath` (or a
  wildcard like `lib/*`, which expands every `.jar`-named file with no
  sources/binary discrimination — `SootUpInputLocationFactory` `classifyWildcardEntry`);
  the #34 seekable reader enumerates it, finds no `META-INF/services` and no
  `module-info.class`, and the scan reports `diagnostics == []` with zero
  descriptors. Harm (corrected per review F1): `isUnique` cannot promote on
  zero descriptors (`providers.size() == 1` and `anyMatch(classpathBacked)`
  both fail), so the defect is the **false completeness signal** —
  `diagnosticEvidence` never reports `scanStatus: incomplete`
  (`ServiceLoaderDescriptorScanner:210-217`) and the limitation text
  misattributes the cause as non-uniqueness (`:227-237`).
- Mode must be explicit caller/SBOM state, never guessed from file contents
  (issue scope 1). The serialized handoff is
  `AnalysisContextLoader` → `ContextDocument` (Jackson record,
  `AnalysisContextLoaderSupport.ContextDocument:71`) → `AnalysisContext.Builder`.
  Root unknown JSON fields are tolerated (`FAIL_ON_UNKNOWN_PROPERTIES=false`).
- The existing source/javac analysis path is driven by
  `AnalysisContext.includedSourceRoots()`:
  `SourceMetadataScanner` walks the roots, `JavacServiceLoaderSiteResolver`
  compiles their files in-JVM, and `ServiceLoaderSourceCache` binds call
  sites. Extracted roots therefore route through the existing path unchanged
  once they appear as source roots.
- `authorizedPath` accepts any path under an included source root
  (`ServiceLoaderDescriptorAuthorization:87`), so extracted roots are
  authorized without touching repository-root logic. `displayPath` uses
  `mostSpecificRoot` over included roots likewise.
- `applicationClasses` is a required directory in both the JSON handoff
  (`AnalysisContextLoaderLayer06.contextInputs`) and the graph provider
  (`SootUpBaseGraphProvider.analyze:21`). A SOURCE_ARCHIVE run still gets
  the compiled application from the build; the source archive replaces the
  repo checkout, not the build artifacts.
- The #34 binary path must not change:
  `ServiceLoaderDescriptorArchiveSource`, `ServiceLoaderDescriptorSpill`,
  `ServiceLoaderDescriptorZipDirectory`, `ServiceLoaderDescriptorZipEntry`,
  `ServiceLoaderDescriptorArchiveConfig` are consumed read-only.
- AGENTS.md process law: RED/GREEN/EVIDENCE commit triples, gates after
  every item (`make adapter-test` count must not decrease — the Makefile
  gate is a minimum (`check_test_count.py 371`), `make java-lint` zero new
  findings, `make evidence-validate`), zero edits to existing tests unless
  sanctioned here (§8 sanctions none).

## 1. Decisions this plan makes (policy gate)

| Decision | Choice | Basis |
|---|---|---|
| Mode representation | `AnalysisContext.InputMode` enum `{SOURCE_TREE, SOURCE_ARCHIVE, BINARY_ARCHIVE}` + nullable builder field; accessor returns `SOURCE_TREE` when unset; package-private `explicitInputMode()` distinguishes default from caller-selected | Explicit contract; default preserves every existing caller/test fact byte-for-byte; the SOURCE_TREE limitation (below) fires only on an explicitly selected mode |
| Source archives | New `List<Path> sourceArchives` on the builder (`sourceArchive(Path)`, `sourceArchives(List)`); loader rejects `*` in the path (review F12) | A sources JAR is an input, not a classpath entry |
| Mode invariants (build-time) | `SOURCE_ARCHIVE` ⇒ ≥1 source archive, **no sourceRoots** (ambiguous provisioning, review F16), sourceArchives ∩ dependencyClasspath(literal) = ∅; `SOURCE_TREE`/`BINARY_ARCHIVE` ⇒ no source archives; `BINARY_ARCHIVE` ⇒ no source roots **and ≥1 dependency classpath entry** (the required-root check is extended so a binary-only context is buildable, review F2) | Fail closed on ambiguous provisioning |
| Wildcard leak (review F3) | At scan time, when any **post-expansion** `dependencyResolution.entries()` entry equals a source archive path, emit scan-global diagnostic `source_archive_on_dependency_classpath` (blocks DEFINITE) and skip that entry | literal-path disjointness cannot see `lib/*` expansions |
| Extraction enumeration | Reuse `ServiceLoaderDescriptorZipDirectory` + `ServiceLoaderDescriptorArchiveConfig.defaults()`; **re-apply the 512 MiB archive-file-size gate before open** (it lives in `ArchiveSource.scan`, not the directory, review F4) | Issue: "#34 seekable reader remains the correct way to enumerate the ZIP" |
| Extraction budgets (review F4) | Dedicated extraction `ScanState` via `withBudgets`: decompressed budget = 512 MiB (a **new policy**, not parity — `MAX_ARCHIVE_BYTES` is a compressed-file-size gate), enumeration/retention at #34 defaults; its budget diagnostics translate into the sanitized archive-level rejection | keeps extraction accounting independent of the descriptor scan's 16 MiB budget |
| Extraction failure | Whole-archive rejection via `IllegalArgumentException` from the adapter (fail closed, sanitized message — temp root stripped, review F11 covers FS-collision exception mapping — temp root removed first) | Extraction failure must never degrade to an empty-but-complete scan |
| Extraction root | One owner-only temp root per run, `flpath-42-sources-` prefix, POSIX 700 where supported (spill pattern); one derived source root per archive at `<tmp>/<archive-stem>` | parity with `ServiceLoaderDescriptorSpill` |
| Interrupt handling (review F13) | Extraction catches `InterruptedIOException`/`ClosedByInterruptException`, cleans up, then clears the thread interrupt flag (parity with `ArchiveSource:97-99`) | a leaked flag would abort later phases |
| Extracted bytes bound | 512 MiB total decompressed per run; per-entry written length must match CEN length; CRC verified by the #34 stream | bounded extraction |
| Entry-name rules | reject empty, leading `/`, any `.`/`..` component, `\`, NUL, wildcard `*`, and any name whose resolved target is not strictly under the extraction root; duplicate non-directory names rejected; FS collisions (`FileAlreadyExistsException`-class, case-variant names, file/dir prefix conflicts) mapped to the sanitized rejection | zip-slip and entry-conflict safety |
| Symlink defense | Creation-time: the extractor only ever `createDirectories`/`createFile` inside its fresh owner-only root and never follows links it did not create; symlink-mode CEN entries have no representation in `ServiceLoaderDescriptorZipEntry`, and the #34 parser is not to be modified — documented residual | issue constraint "do not modify the binary archive path" |
| Diagnostic | `descriptor_metadata_unavailable_for_source_archive` (scan-global; blocks DEFINITE via `isUnique`'s `diagnostics.isEmpty()` conjunct) when mode is SOURCE_ARCHIVE and `dependencyResolution.entries()` is empty. "Resolved binaries" = dependency entries only; `applicationClasses` does NOT count. Stated blast radius (review F10): in such runs, applicationClasses-backed ServiceLoader facts also drop to POSSIBLE — that is the intended scan-global behavior | acceptance criterion |
| Mixed mode | SOURCE_ARCHIVE (and SOURCE_TREE) + resolved dependency binaries: binaries stay authoritative (`classpathBacked` merge preference already prefers them); provenance kinds `meta_inf_services` vs `source_meta_inf_services` already record the split; no blocking diagnostic when binaries were scanned | issue scope 5 |
| SOURCE_TREE limitation | `Scan` gains an ordered `limitations` list; when the mode was **explicitly** set to SOURCE_TREE and dependency entries are empty, a limitation records that checked-in descriptors are the only descriptor source. Surfacing (review F7): appended to the fact's limitation string only when the resolution is already non-unique (a source-only descriptor is never classpathBacked, so uniqueness already fails); never a diagnostic, never blocks DEFINITE. Explicit-only trigger keeps the default mode byte-identical | issue scope 3 |
| BINARY_ARCHIVE behavior | Byte-identical to today's scan path; mode only forbids source roots/archives and requires ≥1 dependency entry | issue scope 4 |
| Display-path stability (reviews F5, F6) | For `inputMode == SOURCE_ARCHIVE` only: the reader's descriptor display path and `RunMetadata`'s source-root reporting use declaredPath-relative forms (archive file name) instead of the temp-root absolute path; default-mode paths are untouched. RED_04 asserts two runs produce identical JSON | temp roots are random per run |
| SOURCE_ARCHIVE provisioning contract (review F9) | `repositoryRoot` must still be an existing directory (loader `requiredDirectory`) — the directory holding the provisioned archives is the natural choice; `dependencyClasspathRequired` must be false for no-binary runs (`SootUpBaseGraphProvider:25-28`); documented in WI-5 | loader and graph-provider constraints |
| Archive layout contract (review F8) | A source archive is a Maven-style `-sources.jar`: packages at the top level. Repo-layout archives (containing `src/main/...`) are extracted as-is; their `src/main/resources` services are NOT discovered by the resources convention (`Reader:50-60` scans `root.parent/resources`); fail-closed only through the no-binaries diagnostic. TEST-scope sources inside a single PRODUCTION-root archive are analyzed as production. Both documented as residuals | layout-agnostic javac path |

## 2. Work items (RED → GREEN → EVIDENCE)

### WI-1 Input-mode contract (AnalysisContext + loader)
- `InputMode` enum, builder/accessor plumbing, build-time invariants above,
  and the `at least one required production/source root` check extended:
  SOURCE_ARCHIVE satisfies it with ≥1 source archive.
- Loader: `ContextDocument` gains optional `inputMode` (string; unknown
  value rejected) and `sourceArchives` (array of paths resolved like
  dependency entries against the repository root, must exist as regular
  non-symbolic files); `fromDocument` wires them into the builder.
- RED_01 tests: mode invariants (all cells: 3 modes × archive presence ×
  root presence), default mode, loader parsing/rejection, `Handoff`/JSON
  round-trip of new fields.

### WI-2 Safe extraction (`SourceArchiveExtractor`)
- Package-private `SourceArchiveExtractor.extract(List<Path> archives)` →
  record `Extraction(Path root, List<AnalysisContext.SourceRoot> roots,
  Runnable cleanup)`; one shared temp root, one derived root per archive
  (`root/<archive-stem>`, declaredPath = archive file name).
- Enumeration through `ServiceLoaderDescriptorZipDirectory`; entry-name
  validation and byte bounds per §1; writes only inside the fresh root.
- Cleanup: recursive best-effort delete; crash residue documented as a
  residual (same posture as #34 R8).
- RED_02 tests (failure classes, each asserting rejection + no residue):
  `../` escape, absolute name, `\` in name, NUL byte, `.`/`..` component,
  duplicate file entry, total-bytes budget (injectable small seam),
  invalid/non-ZIP archive, missing archive; success: files extracted with
  expected content, permissions owner-only where POSIX, cleanup removes
  the root, cleanup after failure removes the root.

### WI-3 Descriptor-scan routing and diagnostics
- `ServiceLoaderDescriptorReader.scan`:
  - SOURCE_ARCHIVE + empty dependency entries ⇒ diagnostic
    `descriptor_metadata_unavailable_for_source_archive` (path = first
    source archive) and skip nothing else (applicationClasses/source-root
    resources scanning proceeds exactly as today for extracted roots).
  - SOURCE_ARCHIVE + binaries present ⇒ unchanged scan (binaries
    authoritative).
  - SOURCE_TREE + empty dependency entries ⇒ Scan limitation (see §1).
  - BINARY_ARCHIVE ⇒ unchanged.
- `Scan` gains `limitations` with compatibility constructors.
- RED_03 tests: source-only JAR → diagnostic present, zero descriptors,
    ServiceLoader fact POSSIBLE with the diagnostic in evidence
    (`scanStatus: incomplete`), limitation text names the diagnostic; mixed →
    binary descriptor wins with `meta_inf_services` provenance and keeps
    DEFINITE when otherwise unique (cleanly-compiling fixture, review F17);
    explicitly-set SOURCE_TREE without binaries → limitation recorded, no
    diagnostic; default-mode SOURCE_TREE without binaries → byte-identical to
    today (no limitation); binary JAR parity (#34 facts unchanged under
    BINARY_ARCHIVE mode); source tree with checked-in descriptors parity;
    wildcard-expansion conflict → `source_archive_on_dependency_classpath`.

### WI-4 Adapter routing (`JavaAnalysisAdapter.analyze`)
- When `inputMode == SOURCE_ARCHIVE`: extract, build the derived context
  via a Builder copy whose sourceRoots are the extracted roots (required,
  included, PRODUCTION), run the normal `analyze`, delete the temp root in
  `finally`. Display-path stability per §1 (reviews F5/F6).
- `analyze(context, graph)` keeps caller-owned graph semantics: extraction
  still applies for source-root derivation (documented).
- RED_04 tests: end-to-end SOURCE_ARCHIVE run (compiled fixture app +
  sources JAR) produces bound call sites through the extracted tree and
  POSSIBLE facts with the diagnostic; extraction failure surfaces as
  IllegalArgumentException; no temp residue after the run; two runs produce
  identical output JSON (temp paths never leak).

### WI-5 Docs
- `docs/issue-42-source-archive-input-modes.md`: input-mode contract,
  SBOM expectations (who sets the mode, what each mode requires), the new
  diagnostic's scan-global blast radius, provenance rules for mixed mode,
  residuals (§7).

## 3. Equivalence-class matrix (tests derive from this, not examples)

| Axis | Cells |
|---|---|
| Mode | SOURCE_TREE (default), SOURCE_TREE (explicit), SOURCE_ARCHIVE, BINARY_ARCHIVE |
| Mode × source archives | present/absent (invalid combos rejected) |
| Mode × source roots | present/absent (BINARY_ARCHIVE and SOURCE_ARCHIVE reject roots; BINARY_ARCHIVE requires ≥1 dependency entry) |
| Mode × resolved binaries | present/absent (drives diagnostic vs authoritative binaries); wildcard expansion containing a source archive |
| Mode × default vs explicit | limitation fires only on explicit SOURCE_TREE |
| Entry name | valid deep, root-level file, directory entry, empty, `.`, `..`, leading `/`, `\`, NUL, `*`, duplicate file, directory-then-file conflict, name resolving to the root itself, case-variant collision (`Foo.java`/`foo.java`), file `a` + child `a/b` |
| Extraction bytes | under budget, exactly at budget, one byte over (seam-injected small budget) |
| Extraction outcome | success, per-entry rejection, archive-level rejection, missing archive, interrupt; residue after each |
| Loader | inputMode absent/present-valid/present-invalid, sourceArchives absent/empty/nonempty/missing-file/symlink/wildcard-rejected, SOURCE_ARCHIVE with sourceRoots rejected |

Pairwise interactions at minimum: mode × binaries presence (the routing
truth table), entry-name class × residue, loader × builder invariants,
archive layout (top-level packages vs repo layout) × resources scan.

## 4. What must not change (compatibility gate)

- Every diagnostic code, descriptor ordering, rollback and budget behavior
  of the #34 path; `ServiceLoaderDescriptorArchiveSource` and friends are
  read-only dependencies.
- All existing test facts (no edits to existing tests; new behavior only on
  new mode values that no existing caller sets).
- `isUnique` semantics; the new diagnostic blocks promotion only through
  the existing `diagnostics.isEmpty()` conjunct.

## 5. TDD accounting

RED_nn commits contain the failing tests for the work item (plus, if
unavoidable, behavior-neutral compile stubs documented in the message);
GREEN_nn contains the minimal fix; EVIDENCE_nn records gate receipts.
Gates after every item: `make adapter-test` (total must be ≥ 371 + new),
`make java-lint` (zero findings beyond the reviewed baseline), `make
evidence-validate`. Every commit that adds a diagnostic states its
scan-global blast radius in the commit message (AGENTS.md).

## 6. Review protocol

Adversarial plan review (subagent, coderabbit-style, engram-grounded)
before implementation; independent post-implementation change review;
findings dispositioned in this file before PR; `@coderabbitai full review`
on the PR after pushing, findings dispositioned before merge.

## 7. Residuals (documented, not fixed here)

- R1: symlink-mode CEN entries cannot be detected without modifying the
  #34 parser; defense is creation-time (fresh owner-only root, no link
  following). A symlink-mode entry extracts as a regular file.
- R2: extraction temp-root crash residue requires ops cleanup (parity with
  #34 R8); no `deleteOnExit` registration (parity with #34 REVIEW_2).
- R3: SOURCE_ARCHIVE still requires `applicationClasses` and an existing
  `repositoryRoot` (the compiled application and the provisioning root from
  the build); the adapter does not compile sources itself.
- R4: cleanup failure after a successful analysis is best-effort and not
  surfaced as a scan diagnostic (the Scan is already closed by then).
- R5: no per-tenant aggregate extraction quota (parity with #34 R9).
- R6: multi-level provenance for mixed mode reuses the existing
  classpathBacked/source kind split; no new provenance channel is added.
- R7: freshness ignores source archives (no archive fingerprint analog of
  `SourceRoot.fingerprint`); a changed sources jar with unchanged dependency
  fingerprints is not detected stale (review F14).
- R8: repo-layout source archives (containing `src/main/...`) do not get
  their `src/main/resources` services scanned by the resources convention;
  TEST-scope sources in a single PRODUCTION-root archive are analyzed as
  production (review F8).
- R9: sources jars that do not compile cleanly against the provided
  classpath keep SOURCE_ARCHIVE runs at POSSIBLE through the existing
  `javac_source_error` scan-global diagnostics (review F17).

## 8. Test-edit sanctions

None. All tests are new files or new methods in new test classes.

## 9. Confidence

**87%** post-amendment: the review's ten blocking findings (false harm
statement, unbuildable BINARY_ARCHIVE invariant, wildcard leak, extraction
budget/ScanState, RunMetadata and descriptor-path temp leaks, default-mode
limitation contradiction, layout contract, provisioning contract, diagnostic
condition) are folded into §1 with concrete mechanisms. Remaining risks:
(a) PMD placement constraints forcing helper moves (documented per AGENTS),
(b) the exact shape of the `Scan` limitations surfacing once
`ServiceLoaderDescriptorScanner.resolve` is touched, (c) RunMetadata JSON
shape changes needing careful default-mode parity checks.

## 10. Implementation record (EVIDENCE)

- RED_01/GREEN_01 (`8ff4c66`/`99ba013`): input-mode contract — 18 tests,
  mode invariants, loader wiring. PMD split `validateInputMode`.
- RED_02/GREEN_02 (`8052159`+`f2b2ba1`/`16d2ebe`): extraction safety —
  18 tests, `ServiceArchiveExtractor` with `ArchiveRun` split for PMD.
- RED_03/GREEN_03 (`6dc6d94`/`254f9fe`): scan routing — 7 tests,
  `SourceArchiveDescriptorRouting`, two scan-global diagnostics, Scan
  limitations, mode-aware display paths.
- RED_04/GREEN_04 (`165b639`/`08e1260`): adapter routing — 4 end-to-end
  tests, extraction into required source roots, original-context re-wrap
  so RunMetadata never leaks the temp root, fail-closed extraction.
- Gate receipts (after GREEN_04, docs in WI-5):
  - default JDK 27 (`openjdk 27.0.1.0.3`): `make adapter-test` 442 tests
    (default=337 large=1 sootup=104, baseline 371), 0 failures;
    `make java-lint` exit 0, no new findings; `make evidence-validate`
    PASS (10 criteria, 21 relationships).
  - Temurin 17.0.21.1+2: `make adapter-test` 442 tests, 0 failures.
- Deviations: none beyond PMD-forced helper placement (documented in the
  GREEN commit messages). Zero edits to existing tests.
- REVIEW_1 (`14cd855`): 12 post-implementation findings addressed or
  dispositioned (see docs §"Review closure"); gates re-run green on both
  JDKs at 453 tests.
- REVIEW_2 (`8d7093e`): round-2 re-review found no correctness issues;
  two test-only gaps (NUL cell, display-path fallback pin) closed plus a
  doc note. Final gates: JDK 27 and Temurin 17.0.21.1+2 both 455 tests,
  java-lint clean, evidence-validate PASS.
