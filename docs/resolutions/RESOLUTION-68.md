# RESOLUTION-68 — Nested out-of-scope/excluded source roots never contribute files

Issue: project-lightpath/java-analysis-adapter#68 (filed from CodeRabbit
CR-1 on PR #67; parent #20).
Branch: `issue-68-nested-root-scope` from `origin/main` @ `5c050ec`.
Commits: PLAN_68 (6 plan-review rounds), RED_68 `7346b25`,
GREEN_68 `50dc90b`, REVIEW_68_1 `a3a94d5`, REVIEW_68_2, EVIDENCE_68.

## Decision (the issue's open question)

**EXCLUDE** (the owner's disposition): test code never runs in
production, so a nested declared root that is excluded
(`included=false`) or out-of-scope (TEST under PRODUCTION) never
contributes `.java` files to the analysis — in both walkers,
symmetrically, regardless of flag state. The most-specific DECLARED
root governs: an included nested root under an excluded parent still
contributes.

## What changed

- **Two predicate members** on `AnalysisContext` (via
  `AnalysisContextAccessors`): `declaredRootsBySpecificity()` (every
  declared root, any inclusion/scope, in the existing specificity
  order) and `includesSourceFile(file, declared)` — the file's
  most-specific declared containing root must be included and in the
  analysis scope; fail-open default `true` when no declared root
  contains the file (the walked root governs; the lexical/symlink
  residual inherits the pre-fix behavior, never wider).
- **Both walkers filter through the predicate** with the declared
  list hoisted once per walk: `SourceMetadataScannerState.files()` and
  `JavacSourceSymbolIndex.Inputs.collectRoot` (the mirror constraint
  holds — the filter only removes entries, so attribution of kept
  files is unchanged).
- **The raw-count rule**: `Inputs.units` distinguishes raw
  (pre-filter) discovery from post-filter discovery;
  `missing_source` fires only when ZERO `.java` files exist
  pre-filter. The all-excluded state intentionally yields a CLEAN
  empty index (no integrity row) — walker symmetry guards
  completeness, and the absent-owner abstention in
  `JavacReflectionTargets` guards correctness independently (the
  coupling is documented on the counter).
- **One sanctioned existing-test restructuring**:
  `generatedProvenanceUsesOnlyIncludedActiveScopeNestedRoots` now
  asserts its own name's semantics. Pre-fix probe receipt: its 2
  facts were `demo.generated.ExcludedMapper#map` and
  `demo.generated.TestMapper#map` — files from the EXCLUDED root and
  the TEST-scope root, both labeled `sourceRootIncluded=true` /
  `scope=production` (false provenance, the primary bug class).
  Post-fix: exactly one fact for the new `IncludedMapper`, all four
  attribute pins retained, none for the excluded/test mappers.
- New `NestedSourceRootScopeTest` (8 cells: N1–N8). Makefile floor
  709 → 717.

## The resource lane (out of scope, honestly)

`ServiceLoaderDescriptorAuthorization.authorizedPath`'s lane has three
arms (fallback / sweep-unreachable / directory), none closable by a
mechanical predicate without an owner decision on
explicit-selection-vs-scope and a scan-global diagnostic
(`descriptor_path_outside_context`) that would block DEFINITE
promotion for the whole scan. Filed as **#69** with the full analysis
(plan-review rounds 2–3); `SourceArchiveDescriptorRouting.displayPath`
is open with it.

## GO evidence (gates, this host, JDK 27)

- `make adapter-test`: **717 executions** (default=603, large=1,
  sootup=113), zero failures — 709 baseline + the 8 new cells; floor
  raised to 717. RED receipt: the 8 expected-red units failed
  (default lane 603 with 7 expected failures + the restructured
  provenance test) and everything else stayed green across all three
  lanes.
- `make java-lint`: PASS, zero findings outside the baseline.
  **JDK 17 verification (#64)**: PASS on helios08 with `~/jdk17`
  (17.0.21.1) over a `git archive` scratch checkout.
- `make evidence-validate`: pass.
- Boundary probes (scratch, outside the tree): `Path.startsWith` is
  segment-wise (sibling `test` does not match `test-java`); the
  same-path contradictory declaration resolves deterministically via
  the generated-first tie-break (the excluded generated declaration
  governs — degenerate residual, recorded); single-character nested
  root matches.

## Mutation checks (red-first)

- Remove the scanner filter ⇒ N1/N3 fail (2/2 red, verified).
- Remove the index filter ⇒ N2/N4 fail (2/2 red, verified).
- Replace the most-specific rule with naive prefix exclusion (any
  containing excluded root drops the file) ⇒ N5 fails (1/1 red,
  verified — the over-exclusion guard).

## Review rounds

- Plan review: 6 rounds, findings 5 → 4 → 1 → 1 → 1 → 0; all
  incorporated (see PLAN-68's plan-review record). The significant
  rounds: round 2's HIGH retraction of the resource-lane gate
  (the `explicitlySelected` fallback makes loop-refinement a no-op;
  whole-gating is an unsanctioned semantics change with a scan-global
  diagnostic), and round 3's three-arm enumeration of the lane.
- Implementation review: round 1 found 5 addressable findings
  (0 HIGH, 0 MEDIUM — unused import, the raw-count coupling
  documentation, double normalization dispositioned optional,
  assertEquals style, sequential-walk note), all addressed in
  REVIEW_68_1; round 2 verified all five with one NIT (comment
  precision on the two guards), folded into REVIEW_68_2. The
  reviewer's exhaustive blast-radius search confirmed no other test
  changed meaning or passes vacuously.

## Residuals

- Same-path contradictory declarations: the `rootsBySpecificity`
  tie-break (generated first, then declaredPath) governs
  deterministically; probe-recorded, not validated away (the only
  same-path suite fixtures declare both roots included/PRODUCTION).
- Symlink-alias lexical containment: a declared alias and the walked
  spelling can differ lexically; the predicate fails open to the
  pre-#68 behavior (never wider). Shared with the existing
  `mostSpecificRoot`/`displayPath` semantics.
- `SootUpCallGraphExtractor.sourcePath` can attribute a graph owner's
  file via a parent included root's path resolution; reachable only
  for owners in the compiled application classes. Recorded.
- The resource lane (three arms) and `displayPath`: #69.
- Perf: one linear containment scan per file over the hoisted
  declared-roots list; negligible against the per-file parse.

Confidence score: **0.91** — the defect, its only existing pin, and
every mutation check are probe- or red-verified; the fix is a
symmetric filter over the existing, well-tested specificity mechanism
with the over-exclusion edge pinned in both directions (N5 +
mutation 3); the blast radius on the 717-test suite is measured (one
sanctioned restructure, no vacuous survivors per the reviewer's
exhaustive search); and the resource lane is honestly excluded with
its blocker analysis recorded in #69 rather than half-fixed.
