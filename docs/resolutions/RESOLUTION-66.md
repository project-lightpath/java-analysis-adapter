# RESOLUTION-66 — `flpath.java.symbolindex` default flipped to ON (umbrella #20 closed)

Issue: project-lightpath/java-analysis-adapter#66 (parent #20).
Branch: `issue-66-symbolindex-default-on` from `origin/main` @ `6dace5d`.
Commits: PLAN_66 (5 plan-review rounds), RED_66 `3aa3f9e`,
GREEN_66 `4730ffe`, REVIEW_66_1 `989c339`, EVIDENCE_66.

## What changed

- **The default flip (one production seam).**
  `JavacSourceSymbolIndex.enabled()` is now an explicit OPT-OUT:
  property absent, `"true"`, or any unrecognized value ⇒ ON (the new
  default for every landed consumer — #22 annotation repair, #24
  framework annotations, #23 shape matching, #60 binary descriptors,
  #25 reflection targets); `"false"` (case-insensitive) ⇒ OFF, the
  field-bisection escape hatch. The opt-out must be deliberate; an
  unrecognized value keeps the default (pinned by C3, including the
  `"FALSE"`/`" False "`/`""` boundaries of the `equalsIgnoreCase`
  arithmetic).
- **Qualifier lookalike fix (defect found by the preflight spike,
  red-first).** The canonical-Spring guard on the guarded qualifier
  arms was per-BLOCK: one canonical `@Qualifier("fast")` occurrence
  unlocked every same-simple-name mirror in the block, so a
  user-defined `@demo.Qualifier("custom")` lookalike emitted a false
  framework qualifier. The guard is now per-OCCURRENCE
  (`occurrenceCanonicalFqn`, mirroring the textual
  `canonicalAnnotationOccurrences` shape-for-shape), with a
  crossed-pairing closure: a canonically-resolving occurrence requires
  the paired mirror's `canonicalName()` to equal that resolution by
  STRICT equality (never set membership — the multi-FQN families
  would let a `javax` occurrence take a `jakarta` mirror's value),
  else the mirror replacement declines and the textual occurrence
  answer is used. PMD forced the family table out of the families
  class into `JavacFrameworkQualifierTable` (CognitiveComplexity 24,
  GodClass WMC 52 in the merged class); behavior identical, placement
  documented in the GREEN commit per AGENTS. The unguarded arms
  (constructor/field/method annotation-block sites, both frameworks)
  keep user-defined same-simple-name emission — textual parity, pinned
  by controls in both directions.
- **Test surface.** 57 flag-off arms across the seven flag-writing
  classes became explicit `setProperty(FLAG, "false")` opt-out goldens
  (semantics preserved by construction); `@AfterEach`/`withFlag`
  finally keep `clearProperty` (they restore the default, now ON).
  `JavacSourceSymbolIndexTest` cell 1 inverted to the opt-out cell;
  cell 12 reworked to opt-out vs default arms. New
  `JavacSymbolIndexDefaultOnTest` (12 cells: C1–C6, D1). Golden
  updates for the designed #23 default-on deltas in
  `JavaAnalysisAdapterTest` (canonical repository callees/symbols,
  comma-canonical proxy descriptors) and
  `SpringDataDelegationLadderTest` (`repositoryMethod` attribute).
  Makefile floor 534 → 709.

## The #26 harness

Untouched, verified flag-independent (exhaustive grep: zero
`flpath.java.symbolindex` references in the harness files; javac is
already the scoring authority and `LIVE_TEXTUAL` runs the production
textual heuristics directly, which remain the opt-out path's
implementation). The 21 pinned wrong-type cells document the opt-out
path after this issue. Only two stale comments elsewhere were
reworded (REVIEW_66_1).

## GO evidence (gates, this host, JDK 27)

- `make adapter-test`: **709 executions** (default=595, large=1,
  sootup=113), zero failures — 697 baseline + the 12 new cells; floor
  raised to 709. RED receipt: the 10 expected-red units failed and
  everything else stayed green (default lane 595 with 9 expected
  failures; full suite minus the expected-red units: BUILD SUCCESS
  across all three lanes).
- `make java-lint`: PASS, zero findings outside the baseline — after
  the PMD-forced `JavacFrameworkQualifierTable` extraction (the first
  GREEN run had 2 new findings: CognitiveComplexity 24 on `qualifiers`
  and GodClass WMC 52; the extraction resolved one pre-existing
  baseline finding as a side effect). **JDK 17 verification (#64)**:
  `make java-lint` on helios08 with `~/jdk17` (17.0.21.1) over a
  `git archive` scratch checkout: PASS, 0 new findings.
- `make evidence-validate`: pass.
- CI-JDK probe (crossed-pairing reachability, scratch outside the
  tree): on **JDK 17** javac attributes an unresolvable-import
  `@Qualifier("fast")` occurrence to a same-package
  `demo.Qualifier(values="fast")` mirror — the crossed-pairing
  scenario IS live on CI's compiler, and
  `lookalikeWithTheCanonicalMirrorUnresolvedStaysExcluded` is its pin
  there (the strict-FQN check declines the demo mirror; the textual
  occurrence answer emits `Qualifier:fast`). On **JDK 27** javac
  retains the error-typed spring mirror value-less
  (`mirrorCanReplace` falls it to textual), so the same cell pins the
  guard move. Correct under both; the probe receipt is the divergence
  record.
- Cost: whole-suite `make adapter-test` wall **2:36** under the new
  default (709 executions) vs **3:21** for the 697-execution forced-on
  spike — no timeout/OOM pressure at the existing pool sizing
  (8 slots, 1.5 GB each). Per-build index cost on a single-file
  fixture: `wallMillis=25`; `peakHeapBytes=0` (the before/after heap
  sampler shows no delta on tiny builds; RESOLUTION-21's per-context
  cost table remains the reference for larger source sets). The
  diagnostics invariant holds: index diagnostics never reach
  `scan.diagnostics()` (D1: default-on scan with an index
  `missing_classpath` row has scan diagnostics identical to the
  opt-out arm and free of every index code).

## Mutation checks (red-first)

- Revert `enabled()` to `Boolean.getBoolean(...)` ⇒
  `defaultBuildsTheIndex`, `unrecognizedValuesKeepTheDefaultOn`,
  `defaultScanMatchesFlagOnOutput` all fail (3/3 red, verified).
- Remove the qualifier guard block ⇒
  `qualifierLookalikesStayExcludedUnderTheJavacPath` and
  `beanSiteNamedLookalikesStayExcludedUnderTheJavacPath` fail (2/2
  red, verified); `springInjectionParametersRequireCanonicalQualifiers`
  is the same defect's original spike pin (red pre-fix by
  construction).
- Revert a golden update ⇒ the corresponding adapter/delegation test
  fails (each failed red before its update; the values were captured
  from the observed default-on output, not assumed).
- Restore a `clearProperty` in a converted arm ⇒ that opt-out golden
  observes the ON default and fails wherever the two paths differ
  (covered by the RED-state behavior of the same arms).

## Spike limitation, stated honestly

The preflight forced-on spike (`JAVA_TOOL_OPTIONS` with the property
set) reported 3 failing methods, but UNDERCOUNTED the blast radius:
the flag-writing classes' `clearProperty` arms removed the property
mid-JVM and warmed textual caches that later tests read
(order-dependent), so `SpringDataDelegationLadderTest`'s golden passed
under the spike and failed under the real flip. The GREEN full-suite
run is the authoritative blast radius (4 golden-updated methods in
total: 3 from the spike + 1 cache-hidden). The plan's mechanical
conversion does not remove this measurement class (a `"false"` window
equally disables the index); it is recorded here as the reason
forced-on spikes cannot substitute for a default-flip suite run.

## Review rounds

- Plan review: 6 rounds (1 initial + 5 delta), findings 6 → 3 → 2 →
  2 → 1 → 0; all incorporated (see PLAN-66's plan-review record).
- Implementation review: round 1 found 4 addressable findings
  (2 LOW, 2 NIT; zero HIGH/MEDIUM — stale opt-out comments, dead
  import, double parse), all addressed in REVIEW_66_1; round 2
  verified all fixes with 0 new findings.

## Residuals

- An unrecognized property value (e.g. `"flase"`) silently keeps the
  default ON — deliberate (C3); a typo'd opt-out does not disable the
  index. A warn-on-unrecognized channel was rejected to keep the
  diagnostics surface closed in this issue.
- Every scan now builds one index per `AnalysisContext` instance; the
  suite shows no pressure at the current pool sizing, but
  memory-constrained hosts already have the serial-mode escape
  (`JAVA_TOOL_OPTIONS=-Djunit.jupiter.execution.parallel.enabled=false`).
  Pool resizing, if CI ever needs it, is a follow-up under #20.
- The crossed-pairing check is exercised end-to-end on CI's JDK 17
  (lookalike attribution) and via the guard move on JDK 27; a
  same-simple-name order-crossing in `takeMirror` (first-match in
  declaration order) is not independently constructible on either
  probed toolchain — the strict-FQN check covers it as
  defense-in-depth.
- The textual (opt-out) path and its #26 pinned defect sets remain
  live code; removing them is future work under #20 if ever.
- `peakHeapBytes` is a before/after sampler, not JFR; indicative only
  (inherited from #21).

Confidence score: **0.90** — the production change is one seam plus
one bounded defect fix, both mutation-verified red-first; the blast
radius was measured by the authoritative full-suite run (not just the
spike, whose undercount is documented above); the guard scoping is
grounded in the verified four-group call-site inventory with controls
on both sides of the guarded/unguarded split; and the JDK 17/27
divergence is probe-recorded with the test cell correct under both.
