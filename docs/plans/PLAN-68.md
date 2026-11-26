# PLAN-68 — Nested out-of-scope/excluded source roots never contribute files

Issue: project-lightpath/java-analysis-adapter#68 (filed from CodeRabbit
CR-1 on PR #67; parent #20).
Branch: `issue-68-nested-root-scope` from `origin/main` @ `5c050ec`
(the PR #67 merge — the default-on flip is on main).

## Decision (recorded, the issue's open question)

**EXCLUDE** — the owner's disposition: test code never runs in
production, so it is categorically out of scope for an adapter whose
contract is production facts about production code. A nested root that
is excluded (`included=false`) or out-of-scope (e.g. a TEST-scope root
under a PRODUCTION-scope parent) must not contribute `.java` files to
the analysis, in EITHER walker, symmetrically. The alternative
(document-as-intended) is rejected: nothing in the repo documents the
current sweep as intended, and it produces false provenance (below).

## Preflight (probe-verified on this main, 2026-11-26, JDK 27)

- **Both walkers sweep nested non-included roots, identically.**
  `SourceMetadataScannerState.files()` and
  `JavacSourceSymbolIndex.Inputs.units()` each walk every included root
  recursively (`Files.walk` + `putIfAbsent`, most-specific-first) with
  no exclusion of nested declared roots that are excluded or
  out-of-scope. Probe (nested included TEST-scope root under an
  included PRODUCTION root): scanner reads `[App.java, Extra.java]`,
  index resolves `type("demo.Extra")`.
- **The defect is load-bearing in an existing test.**
  `JavaAnalysisAdapterTest.generatedProvenanceUsesOnlyIncludedActive
  ScopeNestedRoots` writes files ONLY under a nested excluded generated
  root and a nested TEST-scope generated root, then asserts 2
  `generated_implementation` facts with
  `sourceRoot=generated-src/main/java, included=true, scope=production`.
  Probe receipt — the 2 facts are exactly `demo.generated.Excluded
  Mapper#map` and `demo.generated.TestMapper#map`: files from the
  EXCLUDED root and the TEST root are emitted as facts labeled
  included/production. **False provenance — the primary bug class.**
  The test's NAME states the intended semantics; its assertions pass
  only because of the walk defect. The fix aligns behavior with the
  test's name; the plan sanctions restructuring the test (item 4).
- **Loader reality.** The production loader REJECTS excluded ordinary
  roots (`AnalysisContextLoaderLayer05.validateOrdinaryRoot`: "ordinary
  source root must be included"). Excluded roots therefore exist only
  as GENERATED collection members; out-of-scope (TEST under
  PRODUCTION) roots are loadable for both kinds. The fix covers both
  shapes; Builder-constructed contexts (the existing test's shape) are
  the primary reachable fixture.
- **Walker audit (weakness disposition, every consumer of
  `includedSourceRoots()`):**
  - `SourceMetadataScannerState.files()` — **fix applies** (the .java
    walk).
  - `JavacSourceSymbolIndex.Inputs.collectRoot` — **fix applies** (the
    .java walk; must stay the scanner's mirror, PLAN-21's constraint).
  - `ServiceLoaderDescriptorReader.scan` — walks per-root
    `../resources/META-INF/services` directories, not .java sources;
    the nested-root question does not apply to resource descriptors
    (a nested root's resources are not swept by a parent's resources
    directory). Audited, unchanged.
  - `SootUpCallGraphExtractor.sourcePath` — resolves
    `root.path().resolve(relative)` per included root for call-graph
    owners; a nested-excluded file could be attributed via the parent
    root's resolve, but only for owners present in the application
    bytecode (compiled production classes do not contain test-only
    owners in the production lane). Audited; recorded as a residual
    (same-path resolution for graph owners), not changed here.
  - `ServiceLoaderDescriptorAuthorization.authorizedPath` — authorizes
    ANY path under an included root; a descriptor source physically
    inside a nested excluded/out-of-scope root is authorized via the
    parent, and this is REACHABLE (explicitly-selected OBSERVED
    metadata can point at arbitrary files/directories,
    `ServiceLoaderDescriptorReader.scan` lines 64-72 pass their
    candidates through this gate). Same manifestation class —
    **fix applies** (item 2b): the gate uses the same
    most-specific-declared-root predicate (a descriptor path inside a
    nested non-included root is not authorized).
  - `SourceArchiveDescriptorRouting.displayPath` — display-path
    attribution via `mostSpecificRoot` over `includedSourceRoots()`;
    once `authorizedPath` excludes nested non-included descriptor
    sources, this renderer is unreachable for them. Audited residual
    (attribution rendering, not inclusion).
  - `BoundedFrameworkModelGeneratedFacts.generatedRootAttributes` —
    computes attributes per GENERATED FILE (post-walk); once the walk
    excludes nested non-included files, no generated file can carry a
    nested root's provenance. Unchanged.
  - `displayPath` / `mostSpecificRoot` callers — operate on the
    post-walk file set; unchanged.
- **The specificity mechanism already exists.**
  `AnalysisContext.rootsBySpecificity` (path depth, generated first,
  then declaredPath) and `AnalysisContext.mostSpecificRoot(file,
  roots)` provide the most-specific-declared-root resolution the fix
  needs; `includedSourceRoots()` is `included &&
  scope == analysisScope` over source + generated roots.

## Scope (binding)

Production sources in `src/main`, one new test class, and ONE
sanctioned existing-test restructuring (item 4).

1. **Shared scope predicate (the whole production change).** Two
   package-private members on `AnalysisContext` (via
   `AnalysisContextAccessors`, where `analysisScope` and the root
   accessors live):
   - `List<SourceRoot> declaredRootsBySpecificity()` — every declared
     root (source + generated, ANY inclusion or scope), sorted by the
     existing `rootsBySpecificity`.
   - `boolean includesSourceFile(Path file, List<SourceRoot>
     declaredBySpecificity)` — walks the most-specific-first list,
     finds the FIRST declared root containing the normalized file
     path, and returns `root.included() && root.scope() ==
     analysisScope`; when no declared root contains the file (not
     reachable during a walk — the walked parent is declared and
     contains it) the safe default is `true`. The MOST-SPECIFIC
     declared root governs: an included nested root under an excluded
     parent still contributes (the depth-first sort reaches the nested
     root first), and a file under an excluded nested root is dropped
     even though the parent is included.
2. **Both walkers filter through the predicate, symmetrically.** Each
   walk hoists `declaredRootsBySpecificity()` ONCE (the sort is not
   per-file) and adds one filter stage:
   - `SourceMetadataScannerState.files()`:
     `.filter(path -> context.includesSourceFile(path, declared))`.
   - `JavacSourceSymbolIndex.Inputs.collectRoot`: the same filter in
     the stream (the index stays the scanner's mirror; the #22–#25
     parity suites enforce it).
   - **2b. `ServiceLoaderDescriptorAuthorization.authorizedPath`** uses
     the same predicate: a candidate descriptor path is authorized
     only when its most-specific declared containing root is included
     and in scope (a path inside a nested excluded/out-of-scope root
     is NOT authorized via the parent). The gate hoists the
     declared-roots list per scan, not per path.
   No other walker changes (audit above). Zero new diagnostics: an
   excluded nested root is a legal, declared context state — silently
   narrower is CORRECT here (the current behavior silently WIDENS,
   which is the defect; the safe direction per AGENTS is not scanning
   what the scope excludes). Stated per AGENTS: no scan-global
   diagnostic blast radius.
   **All-filtered-out parity (plan-review round 1):** post-fix, a
   Builder-reachable state has an included root whose only `.java`
   files sit under a nested excluded root. The index's `missing_source`
   diagnostic ("no compilation units were discovered") must NOT fire
   for that state — the source is not missing, it is scope-excluded —
   or flag-on diagnoses what flag-off silently accepts (a parity
   asymmetry in exactly this plan's shape). Implementation:
   `collectRoot` distinguishes raw discovery (before the predicate)
   from post-filter discovery; `missing_source` fires only when the
   RAW count is zero. The scanner stays silent in both states (its
   current behavior). Pinned by N8.
3. **New test class `NestedSourceRootScopeTest`** (plain
   `@Test`, no flag writes — the predicate is flag-independent; the
   index cells exercise the default-on path):
   - **N1 (RED): scanner excludes nested TEST-scope root files.**
     PRODUCTION source root with `demo/App.java`; nested included
     TEST-scope root with `demo/Extra.java` ⇒ `files()` contains only
     App.
   - **N2 (RED): index excludes the same.** `type("demo.Extra")`
     empty; `type("demo.App")` present (non-vacuity).
   - **N3 (RED): scanner excludes nested EXCLUDED generated-root
     files.** Included generated root with nested excluded generated
     child root ⇒ the child's files absent, the parent's own files
     present.
   - **N4 (RED): index excludes the same** (`type()` on the child's
     type empty).
   - **N5 (characterization, the specificity edge): an included nested
     root under an EXCLUDED parent still contributes** — most-specific
     governs; both walkers include the file, attributed to the nested
     included root.
   - **N6 (parity): flag-on == flag-off file sets, non-vacuous
     oracle.** The N3/N4 generated-root fixture (which produces
     observable `generated_implementation` facts via
     `FrameworkMetadata`, the existing test's shape — NOT the plain
     `App.java` fixture, whose projections are empty and would make
     the cell vacuous): the scanner's file-set projection AND the
     framework-fact projection are equal across
     `-Dflpath.java.symbolindex=false` and the default; both exclude
     the nested files (green at RED — both currently include — and
     stays green post-fix; the parity invariant is the pin). The
     oracle is stated here so the RED commit can prove non-vacuity:
     the on-arm's projection is non-empty for the included mapper.
   - **N8 (RED): all-filtered-out parity.** An included root whose
     only `.java` files sit under a nested excluded root: scanner
     `files()` empty, the index's `diagnostics()` contains NO
     `missing_source` row (scope-excluded is not missing), identical
     across flag states.
   - **N7 (RED): end-to-end provenance.** The
     `generatedProvenanceUsesOnlyIncludedActiveScopeNestedRoots`
     fixture shape PLUS an `IncludedMapper` directly under the
     included generated root ⇒ exactly ONE generated_implementation
     fact (`demo.generated.IncludedMapper#map`) with
     `sourceRoot=generated-src/main/java, included=true,
     scope=production`; ZERO facts for Excluded/Test mappers.
   - **N9 (RED): the resource lane.** A descriptor source (a
     `META-INF/services` file or a metadata-selected file) physically
     inside a nested excluded generated root: NOT authorized/ scanned
     via the parent included root (the `authorizedPath` gate applies
     the same predicate) — no descriptor facts from it, while the same
     descriptor directly under the included root IS scanned
     (non-vacuity).
   - **Boundary probes (pre-GREEN, scratch, per AGENTS):** the
     `Path.startsWith` segment arithmetic (a sibling directory whose
     name is a prefix of the nested root's name —
     `test-extra` vs `test` — must not match), a nested root EQUAL in
     path to its parent (same path declared twice with different
     flags — the existing `rootsBySpecificity` tie-break governs,
     probed and recorded as a degenerate same-path residual), and a
     single-character nested root name.
4. **Sanctioned existing-test edit (exactly one).**
   `JavaAnalysisAdapterTest.generatedProvenanceUsesOnlyIncludedActive
   ScopeNestedRoots` is restructured to its own name's semantics: add
   an `IncludedMapper` under the included generated root; assert
   exactly ONE generated fact for it retaining ALL FOUR attribute
   pins (`sourceRoot=generated-src/main/java`, `sourceRootIncluded=
   true`, `sourceRootScope=production`, AND
   `sourceRootFingerprint=included-fingerprint`), and NO facts whose
   callee is `ExcludedMapper` or `TestMapper` (fail-closed twins in
   both directions). The current assertions are the
   misattribution's pin (probe receipt in the preflight); the
   restructuring is the fix's red-first owner, and the commit message
   carries the probe evidence. Zero edits to any other existing test.
   Adjacent audit note: `JavaAnalysisAdapterTest` line ~209 nests an
   included generated root under an included source root (both in
   scope) — audited, unaffected by the fix (most-specific declared
   root is the included generated root; attribution unchanged), so the
   "exactly one" count is verifiable.
5. **Makefile floor raise** to the observed post-fix count (709 + new
   cells; the restructured test is count-neutral).

## Non-goals

- Resource-descriptor scanning (`ServiceLoaderDescriptorReader`) —
  audited, not a .java walk.
- `SootUpCallGraphExtractor.sourcePath` same-path resolution for
  graph owners (residual, audited above).
- Loader validation of contradictory same-path root declarations
  (degenerate; recorded as a residual).
- Changing `includedSourceRoots()` itself or the loader's
  excluded-ordinary-root rejection.

## Certainty model

- A file under a walked included root is analyzed iff its
  most-specific DECLARED containing root is included and in the
  analysis scope — in both walkers, in both flag states (parity).
- Excluded and out-of-scope nested roots contribute NOTHING: no
  scanner files, no index entries, no facts, no provenance. The
  current false-provenance facts (`included=true` labels on
  excluded/test files) disappear — fail-closed.
- An included nested root under an excluded parent still contributes
  (most-specific governs) — pinned by N5 so the fix cannot
  over-exclude.
- Zero new diagnostics (an excluded nested root is legal state, not an
  incomplete scan).

## TDD sequence

- **PLAN_68** (this file): committed first.
- **Plan review**: Engram-grounded adversarial subagent, CodeRabbit
  style; loop until no new addressable findings.
- **RED_68**: `NestedSourceRootScopeTest` (N1–N9) plus the sanctioned
  restructuring of `generatedProvenanceUsesOnlyIncludedActiveScope
  NestedRoots`. Expected-RED: N1–N4, N7, N8, N9 (and the restructured
  existing test — its no-facts-for-Excluded/Test assertions fail
  today). Expected-green at RED: N5, N6. Full gates at RED (expected
  failures enumerated in the commit message; floor receipt 709 + new).
- **GREEN_68**: the two predicate members (item 1), the two walker
  filters + the all-filtered-out diagnostic rule, and the
  `authorizedPath` gate (item 2/2b). Boundary probes before the
  commit. Full gates; JDK 17 lint on helios08 if any doubt (#64 — the
  touched classes are small additions to existing files).
- **Implementation review**: a NEW Engram-grounded adversarial
  subagent over the full change; findings red-first; loop until clean.
- **EVIDENCE_68**: `docs/resolutions/RESOLUTION-68.md` — probe
  receipts (the misattribution before/after), gate counts, mutation
  checks (revert the scanner filter ⇒ N1/N3 fail; revert the index
  filter ⇒ N2/N4 fail; invert the most-specific edge ⇒ N5 fails),
  residuals.

Gates after every item: `make adapter-test` (count never below 709;
floor raised to the GREEN count), `make java-lint` (zero new
findings), `make evidence-validate`.

## Residuals / risks

- Same-path contradictory declarations (one path declared as included
  PRODUCTION and excluded TEST): `rootsBySpecificity`'s deterministic
  tie-break (generated first, then declaredPath) governs; probed and
  documented, not validated away (the loader's ordinary-root rules
  already prevent the common shapes).
- `SootUpCallGraphExtractor.sourcePath` can attribute a graph owner's
  file via a parent included root's path resolution even when the file
  sits under a nested excluded root — reachable only for owners in the
  compiled application classes; recorded as the same-path resolution
  residual.
- The generated-roots provenance attributes
  (`BoundedFrameworkModelGeneratedFacts`) are computed per generated
  file; post-fix no excluded nested file can be a generated file, so
  the attribute path is unreachable for them (no change needed).
- Performance: one additional most-specific-root scan per file over
  the declared-roots list (hoisted sort, linear containment scan);
  bounded by (files × declared roots) path-prefix comparisons —
  negligible against the per-file parse the walkers already pay.
- Symlink-alias containment (plan-review round 1): root and file
  containment is LEXICAL (`toAbsolutePath().normalize()` +
  `Path.startsWith`); this repo's fixtures declare roots through
  symlinked aliases (`ServiceLoaderSafetyLifetimeTest`), where a
  declared alias and the walked spelling can differ lexically. In
  that shape the predicate's fail-open default (`true` when no
  declared root lexically contains the file) keeps today's
  attribution — the pre-#68 behavior, never wider. Recorded as a
  residual shared with the existing `mostSpecificRoot`/`displayPath`
  lexical semantics (PLAN-21 recorded the same class for `typesIn`).
- `SourceArchiveDescriptorRouting.displayPath` attribution for
  excluded-nested descriptor paths — unreachable after the
  `authorizedPath` fix (audited residual).

## Plan-review record

Round 1 (Engram-grounded adversarial subagent, CodeRabbit style): 5
addressable findings (3 MEDIUM, 2 LOW) + 1 NIT, all incorporated:
1. MEDIUM — the walker audit missed
   `ServiceLoaderDescriptorAuthorization.authorizedPath` (reachable
   false-inclusion in the resource lane via OBSERVED metadata) and
   `SourceArchiveDescriptorRouting.displayPath`; `authorizedPath` is
   now in scope (item 2b, cell N9), `displayPath` is an audited
   residual (unreachable after 2b).
2. MEDIUM — post-fix all-filtered-out state would make the index's
   `missing_source` diagnostic asymmetric with the scanner; the
   diagnostic now fires only when the RAW (pre-predicate) discovery
   count is zero, pinned by N8.
3. MEDIUM — N6 as drafted was vacuous (plain fixture, empty
   projections); re-specified over the generated-root fixture with a
   stated non-empty oracle.
4. LOW — item 4 now retains all FOUR attribute pins including the
   fingerprint.
5. LOW — the fail-open default's lexical-containment limit under
   symlinked root aliases is recorded as a residual (behavior = today's,
   never wider).
6. NIT — the adjacent nested-included test (JavaAnalysisAdapterTest
   ~line 209) is audited and noted unaffected, making the
   "exactly one existing test" count auditable.

Verified by the reviewer: both walkers' sweep, the existing test's
misattribution pin, the loader's excluded-ordinary-root rejection,
the specificity mechanism's correctness (including N5's
preserved-today semantics and the deeper-excluded-root case),
predicate placement legality, the exhaustive nested-root test search
(exactly one test to restructure), the exhaustive `Files.walk` audit
(no missed .java walker), and N6's green-at-RED status.

Confidence score: **0.88** (0.86 → 0.88 after plan-review round 1) —
the defect and its only existing pin are probe-verified; the fix is a
symmetric filter over an existing, well-tested specificity mechanism,
now covering the resource lane's reachable manifestation too; the
blast radius on the 709-test suite is measured at RED (the
restructured test is the only sanctioned edit). Residual risk: the
most-specific-governs edge (N5) inverts the intuition of naive prefix
exclusion and must be pinned exactly; the resource-lane gate change
(N9) touches authorization semantics with fewer existing patterns to
copy; and the symlink-alias lexical residual is inherited, not fixed.
