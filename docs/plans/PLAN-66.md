# PLAN-66 — Flip `flpath.java.symbolindex` default to ON (umbrella #20 final step)

Issue: project-lightpath/java-analysis-adapter#66 (parent #20, closes it).
Branch: `issue-66-symbolindex-default-on` from `origin/main` @ `6dace5d`
(the PR #65 merge — every javac consumer has landed flag-gated).

## Preflight (verified on this main, 2026-11-25, JDK 27)

- **The seam is single-point.** `JavacSourceSymbolIndex.enabled()` is
  `Boolean.getBoolean("flpath.java.symbolindex")` (default off). Every
  consumer reaches the flag through `of(context)`/`enabled()`:
  `JavacAnnotationAccess` (#22), `JavacFrameworkAnnotations` /
  `JavacFrameworkQualifierFamilies` (#24), `JavacMemberCorrelation` and the
  descriptor dialects (#23), `JvmBinaryNames` (#60), `JavacReflectionTargets`
  (#25). One production line flips the default.
- **The #26 harness is flag-independent** (exhaustive grep over
  `JavacResolverAccuracyHarness`, `JavacResolverAccuracyTest`,
  `JavacResolverMetricsTest`, `JavacResolverCell`: zero references to
  `flpath.java.symbolindex`). `BaselineMode.LIVE_TEXTUAL` runs the
  production textual heuristics directly against probe text; javac is
  already the scoring authority ("javac is the authority; the textual
  baseline is scored at those positions"). The issue's "the 21 wrong-type
  cells become fixed expectations under the javac path" is therefore
  ALREADY the harness's structure: the pinned wrong-type cells document
  the TEXTUAL (now opt-out) path's defects, and the javac facts they are
  scored against are the default-path answer. No baseline inversion is
  needed; the sanctioned harness work is comment-only (statements that
  "production flag-off stays textual" become "the opt-out path stays
  textual"), enumerated in item 8.
- **Empirical blast-radius spike (the plan's load-bearing measurement).**
  The full suite was run with the flag forced on globally
  (`JAVA_TOOL_OPTIONS=-Dflpath.java.symbolindex=true make adapter-test`;
  consumers read the property per call, so this simulates the flipped
  default for every NON-flag-writing test). Result: 697 executions
  (default=583, large=1, sootup=113), exactly **3 failing methods**, all
  in `JavaAnalysisAdapterTest`:
  1. `springInjectionParametersRequireCanonicalQualifiers` —
     `[Qualifier:fast]` vs `[Qualifier:fast, Qualifier:custom]`. **Real
     defect** (see item 2 below): the fixture's `@demo.Qualifier("custom")`
     lookalike is excluded by the textual path but admitted by the javac
     qualifier path — a false framework fact, the primary bug class.
  2. `springRepositoryFactsRequireCanonicalContractsAndKnownMethods` —
     callee `demo.CustomRepository#findByEmail(String)` vs
     `...(java.lang.String)`. The designed #23 flag-on delta (canonical
     parameter types in `repositoryMethodSignature`); golden update.
  3. `dynamicSitesKeepCallersAndEquivalentReflectionSpellings` (line 7103)
     — proxy `targetDescriptor` `(String)` vs `(java.lang.String)`. The
     designed #23 comma-canonical dialect delta; golden update.
  The spike's limit, stated for review: `JAVA_TOOL_OPTIONS` sets a real
  system property, so test arms that call `System.clearProperty(FLAG)`
  ran flag-OFF even under the spike. Those arms are exactly the
  flag-writing `@Isolated` classes, and item 3 converts every one of them
  to an explicit opt-out (`setProperty(FLAG, "false")`) — semantics
  preserved by construction, so the unmeasured combination
  (default-on + former clearProperty arms) cannot observe a different
  flag state than the cell was written for.
- **Diagnostics invariant.** Index diagnostics live on
  `index.diagnostics()` only; nothing in the flip touches
  `ServiceLoaderDescriptorScanner.isUnique` or `scan.diagnostics()`
  (zero scan-global blast radius; stated per AGENTS). Pinned by a new
  cell (D1).
- **Cost lane.** With the default on, every scan that reaches a consumer
  builds the per-context index once (WeakHashMap per context instance,
  PLAN-21). The spike ran the full 697-execution suite in 3:21 wall with
  the flag forced on — no OOM, no timeout; per-build `wallMillis()` /
  `peakHeapBytes()` are recorded in RESOLUTION-66 against #21's receipts.
- **Gates on this main**: `make adapter-test` floor 534, observed 697;
  `make java-lint`; `make evidence-validate` (present on this main's
  Makefile). JDK 17 divergence (#64): the changed production classes are
  small edits to existing files (no new class approaches GodClass), but
  lint is verified on helios08 with `~/jdk17` and a scratch checkout
  before pushing per the standing rule.

## Scope (binding)

Production sources in `src/main`, plus one new test class and sanctioned
edits to existing tests (every one enumerated in items 3, 4, 8 — zero
unsanctioned test edits).

1. **Flip the default (the whole production change outside item 2).**
   `JavacSourceSymbolIndex.enabled()` becomes an explicit OPT-OUT:
   ```java
   static boolean enabled() {
     return !"false".equalsIgnoreCase(
         System.getProperty("flpath.java.symbolindex"));
   }
   ```
   Semantics: property absent ⇒ ON (the new default); `"true"` ⇒ ON;
   `"false"` (case-insensitive) ⇒ OFF (field bisection escape hatch);
   any other value ⇒ ON (an unrecognized value keeps the default — the
   opt-out must be deliberate). The class javadoc and `enabled()` javadoc
   ("default off") are updated to the opt-out wording. No other
   production file changes semantics: every consumer already routes
   through `of()`/`enabled()`.
2. **Fix the qualifier lookalike window (defect found by the spike,
   red-first).** In `JavacFrameworkQualifierFamilies.mirrorFamilies`, the
   canonical-Spring guard runs per-BLOCK:
   `SPRING_BEAN_ANNOTATION_TYPES.containsKey(name) &&
    !canonicalSpringAnnotation(file, block, name)` — so one canonical
   `@Qualifier("fast")` occurrence unlocks EVERY same-simple-name mirror
   in the block, including a user-defined `@demo.Qualifier("custom")`
   lookalike the textual path excludes (the textual
   `annotationQualifiers` guards each occurrence against its OWN source
   `annotation.source()`). Fix: the mirror arm applies
   `canonicalSpringAnnotation` against the occurrence source that
   `takeMirror` already parsed (`ParsedAnnotation.source()`), passed into
   `mirrorFamilies`. Weakness disposition (AGENTS), scoped to the
   GUARDED arms only, with the full call-site inventory (the
   guarded/unguarded split is per-SITE-KIND, not per-framework —
   verified at every caller of
   `JavacFrameworkQualifierFamilies.qualifiers(file, block, mirrors,
   guarded)`):
   - `FrameworkBeanScanning` (bean-site qualifiers): `guarded =
     !quarkus` — Spring guarded, Quarkus not. **Fix applies.**
   - `FrameworkInjectionScanning.injectionParameters` (parameter
     qualifiers): `guarded = spring`. **Fix applies.**
   - `FrameworkInjectionScanning` constructor annotation block
     (`qualifiers(..., false)`, hardcoded, all frameworks): UNGUARDED
     for Spring and Quarkus alike. **Fix must NOT apply.**
   - `JavacFrameworkQualifierFamilies.injectionQualifiers` wrapper
     (field/method annotation blocks, `guarded = false` hardcoded at
     the wrapper): UNGUARDED for both frameworks — the textual
     fallback (`qualifierNames(annotations)` →
     `qualifierNames(null, block)`) is equally unguarded, so a Spring
     `@Autowired @demo.Qualifier("custom")` field emits
     `Qualifier:custom` in BOTH flag states by parity. **Fix must NOT
     apply** — pinned by `quarkusUserDefinedNamedStillEmitsUnguarded`
     (Quarkus `@Inject @Named("custom")` field) and by a NEW Spring
     unguarded control in C6, so neither direction of the split can
     drift silently.
   The per-occurrence guard replaces the block-level guard inside the
   occurrence loop when `guarded` is true; a `guarded` arm keeps its
   semantics otherwise unchanged. Mechanically, the guard moves to the
   occurrence loop: `mirrorFamilies` gains the occurrence's
   `ParsedAnnotation` (today `takeMirror` parses it and discards it —
   the fix surfaces it, returning or re-parsing) and the
   remaining-mirrors call passes `null` for it, documented as inert by
   the unreachability argument below. **Crossed-pairing closure (same
   weak seam, all manifestations per AGENTS):** `captureMatches` pairs
   by simple name only and cannot distinguish a canonical mirror from
   a same-simple-name lookalike; when the occurrence resolves
   canonically (qualified spelling or canonical import) but its OWN
   mirror was dropped (error-typed annotations are absent from the
   mirrors on some compilers — a first-class scenario per the class
   javadoc and `mirrorCanReplace`'s value-less arm; the canonicity
   check is purely textual, so "import resolves" and "spring mirror
   dropped" co-occur in a source-only scan without the framework jar
   on the classpath), the surviving lookalike mirror supplies the
   VALUE under the passing guard — a lookalike-derived fact emitted
   where the textual path emits the canonical one (wrong today
   flag-on; the flip must not promote it). Fix: in the guarded arm,
   when the occurrence resolves canonically, require the paired
   mirror's `canonicalName()` to be that canonical type — the
   occurrence's canonical FQN is the qualified spelling when fully
   qualified, else the FQN of the canonical import matched through
   `importedTypes`, and the check is STRICT EQUALITY against that FQN,
   never set membership (a deliberate divergence from the `uniqueMirror`
   precedent: the multi-canonical families — `Named` → jakarta/javax,
   `Resource`, `Transactional`, `RolesAllowed` — would let a
   `javax`-imported occurrence take a `jakarta` mirror's value under
   set membership, the same fabricated-value window); otherwise
   decline the mirror replacement and fall to the textual occurrence
   answer (which already carries the per-occurrence guard and value —
   always available for a canonical occurrence, because spring
   canonical FQNs are fully ASCII so the capture is the complete
   simple name and `parseAnnotation` extracts name+arguments
   faithfully; a `$`/Unicode-truncated capture can never be canonical).
   Coverage status stated honestly (probe-verified on JDK 27, scratch
   probe outside the tree): javac RETAINS the error-typed spring
   mirror (value-less — `mirrorCanReplace` already falls those to
   textual), so the crossed-pairing check is DEFENSE-IN-DEPTH, not
   red-able in isolation on the probed toolchain; the C6
   dropped-canonical-mirror twin therefore pins the GUARD MOVE (it is
   red pre-fix: the block-level guard lets the lookalike mirror
   emit), and a pre-GREEN scratch probe on the CI JDK (17, helios08
   if needed) records whether any documented mirror-absence cause
   (type-use/nested occurrence shapes) makes the crossed-pairing path
   reachable — if one is constructible, a twin is added for it; if
   not, the check ships as reviewed defense-in-depth with the probe
   receipt.
   This affects both the Qualifier/Named family and the
   SPRING_BEAN_ANNOTATION_TYPES family
   (a `@demo.Component` lookalike in a block that also has a canonical
   `@Component` has the same window); the fix closes both because it
   replaces the guard itself and adds the pairing check. The
   remaining-mirrors loop needs NO
   guard change, and the `uniqueMirror`-style arm round 1 specified
   for it is unreachable dead code, retracted: that loop invokes
   `mirrorFamilies` only for mirrors whose simple name has an EMPTY
   ASCII prefix, while every `SPRING_BEAN_ANNOTATION_TYPES` key is
   ASCII-initial — the guard precondition can never hold there, today
   or after the fix (the loop is fail-safe by construction;
   unconsumed ASCII-named mirrors already decline the site).
3. **Sanctioned mechanical test conversion: flag-off arms become opt-out
   goldens.** In every flag-writing `@Isolated` + `SAME_THREAD` class,
   each `System.clearProperty(FLAG)` that establishes a FLAG-OFF arm
   becomes `System.setProperty(FLAG, "false")` (comment: opt-out golden).
   This preserves each cell's meaning exactly under the new default.
   Classes (exhaustive — every test file referencing the flag):
   `JavacAnnotationMigrationTest`, `JavacFrameworkAnnotationMigrationTest`,
   `JavacBinaryNameMigrationTest`, `JavacParameterAnnotationTest`,
   `JavacReflectionTargetMigrationTest`, `JavacTypeShapeMigrationTest`,
   `JavacSourceSymbolIndexTest`. `@AfterEach clearFlag` and the
   `withFlag` finally-block KEEP `clearProperty` (they restore the
   DEFAULT, which is now ON — correct because the classes are
   `@Isolated`).
4. **Sanctioned existing-test edits beyond the mechanical conversion
   (each keeps a fail-closed twin or an exact pinned value):**
   - `JavacSourceSymbolIndexTest.flagOffYieldsNoIndexAndNoCompilation`
     inverts its meaning: becomes
     `optOutYieldsNoIndexAndNoCompilation` — `setProperty(FLAG,"false")`
     ⇒ `of()` empty, `COMPILATIONS` delta 0, `enabled()` false. (The
     default-on positive is the NEW class's cell 1; the inversion is the
     issue's named edit.)
   - `JavacSourceSymbolIndexTest.flagDoesNotChangeScanOutput` (cell 12):
     its two arms were clearProperty vs `"true"` — under the flip both
     would be ON and the cell vacuous. Rework, committed in RED_66:
     arm 1 becomes `setProperty(FLAG,"false")` (opt-out) vs arm 2 the
     default (ON); the assertion (ServiceLoader scan + resolution
     identical across flag states — the ServiceLoader slice is
     flag-independent) is unchanged and non-vacuous. The flag-on arm's
     present-index assertion stays and is EXPECTED-RED at RED (the
     default is still off there).
   - `JavaAnalysisAdapterTest.springRepositoryFactsRequireCanonical
     ContractsAndKnownMethods`: callee and evidence-symbol expectations
     for `demo.CustomRepository#findByEmail` update to the current
     flag-on spellings (`java.lang.String` parameter spelling in the
     callee; the symbols assertion is updated to the observed flag-on
     rendering, verified at RED against the `"true"` arm before the
     flip lands). This is the acceptance criterion ("default output
     matches the current flag-on output") applied to a golden, not a
     behavior change.
   - `JavaAnalysisAdapterTest.dynamicSitesKeepCallersAndEquivalent
     ReflectionSpellings` (line 7103f): proxy `targetDescriptor`
     expectations `(String)`/`(int)` for `demo.Overloaded#run` update to
     the flag-on comma-canonical spellings (`(java.lang.String)` /
     `(int)` — primitives unchanged). Same acceptance rationale.
   - `springInjectionParametersRequireCanonicalQualifiers` is NOT edited:
     it is the red pin for item 2 and must pass unmodified once the
     guard is fixed.
5. **New test class `JavacSymbolIndexDefaultOnTest`**
   (`@Isolated` + `SAME_THREAD`, writes the flag, `@AfterEach` restores):
   - **C1 (RED): default builds the index.** The cell's first statement
     explicitly ensures the property is absent at entry
     (clear-or-assert-null; no reliance on a previous cell's cleanup or
     JUnit method ordering): `of(context)` present, `COMPILATIONS`
     delta 1, `enabled()` true.
   - **C2 (RED): explicit opt-out disables the index.**
     `setProperty(FLAG,"false")` ⇒ `of()` empty, delta 0, `enabled()`
     false (green at RED only in its `"false"`-is-off half; the
     absent-property half is C1).
   - **C3 (RED): `"true"` and unrecognized values keep the default on.**
     `"true"` ⇒ present; `"xyz"` ⇒ present (pins the
     only-`"false"`-opts-out semantics; boundary cells for the
     `equalsIgnoreCase` arithmetic per AGENTS: `"FALSE"`, `" False "`
     (whitespace is NOT trimmed — stays ON, pinned), `""` (empty string
     ⇒ ON — an empty value is not the deliberate opt-out, pinned)).
   - **C4 (RED): default scan output equals flag-on output.** A
     representative scan over an annotation-repair fixture (the #22
     `@SuppressWarnings`-style triggered-repair shape): default-arm
     facts equal `"true"`-arm facts, and differ from the `"false"`-arm
     textual facts where javac repairs (non-vacuity: the on-arm carries
     the repaired canonical spelling, the opt-out arm the textual one).
   - **C5: opt-out reproduces today's default.** The same fixture run
     with `"false"` produces the exact pinned textual values (the
     existing migration goldens already own this class of pin; this cell
     pins it at the default-seam level: opt-out output == the committed
     textual expectation, an exact-value assertion, not a comparison).
   - **C6 (RED): the qualifier lookalike stays excluded under the javac
     path.** The `springInjectionParametersRequireCanonicalQualifiers`
     fixture shape (spring `@Qualifier("fast")` +
     `@demo.Qualifier("custom")` + `@Value("@Qualifier(literal)")` decoy)
     run with `setProperty(FLAG,"true")` ⇒ parameters carry exactly
     `[Qualifier:fast]`. RED today (flag-on admits the lookalike);
     after GREEN the same assertion holds under the default. Every C6
     twin places the CANONICAL annotation and the lookalike in the SAME
     guarded block — the defect window only opens when a canonical
     occurrence makes the block-level guard pass, so a lookalike-only
     twin would be vacuous against the pre-fix code. Twins:
     - a swapped-ORDER twin (`@demo.Qualifier("custom")` declared
       BEFORE `@Qualifier("fast")` ⇒ still exactly `[Qualifier:fast]`)
       — the per-occurrence guard checks the OCCURRENCE's canonicity
       while the emitted value comes from the paired MIRROR, so a
       crossed occurrence↔mirror pairing would emit the lookalike's
       value under a passing guard; the twin pins the pairing;
     - a `@demo.Component` bean-annotation lookalike (same guard, bean
       family);
     - a dropped-canonical-mirror twin: canonical `@Qualifier("fast")`
       + `@demo.Qualifier("custom")` with the SPRING annotation made
       unresolvable (no spring stub on the compile classpath) ⇒ still
       exactly `[Qualifier:fast]`, never `Qualifier:custom` from the
       lookalike mirror. Probe-verified premise (JDK 27): javac
       RETAINS the error-typed spring mirror value-less, so this twin
       pins the GUARD MOVE (red pre-fix via the block-level guard;
       post-fix the value-less spring mirror fails `mirrorCanReplace`
       and the occurrence falls to the textual answer) — the
       crossed-pairing check itself is defense-in-depth unless the
       CI-JDK probe finds a constructible mirror-absence shape (see
       item 2's coverage status);
     - an unguarded-path CONTROL pair: a Quarkus/CDI user-defined
       `@demo.Named` field still emits `Named:custom` under the javac
       path (mirroring `quarkusUserDefinedNamedStillEmitsUnguarded`),
       AND a Spring `@Autowired @demo.Qualifier("custom")` field still
       emits `Qualifier:custom` in both flag states — the
       field/method/constructor annotation-block sites are unguarded
       for BOTH frameworks by parity with the textual fallback, and no
       existing test pins the Spring side (grep: zero hits), so this
       control closes it;
     - a Unicode-initial pure-index-mirror cell re-designated as an
       EMIT-path pin (the remaining-mirrors append's non-vacuity — the
       correction still fires), expected-green at RED; it cannot pin a
       guard regression (the guard precondition is unreachable on that
       loop — see item 2's retraction).
   - **D1 (RED for non-vacuity): index diagnostics never reach
     `scan.diagnostics()`.** A default-on scan whose index emits
     `missing_classpath` (required absent entry): the index's own
     `diagnostics()` is non-empty (non-vacuity), and
     `scan.diagnostics()` is byte-identical to the `"false"` arm's
     (empty of index rows) — the #62 invariant under the new default.
6. **Makefile floor raise.** After GREEN, the observed execution count
   (697 + new cells) replaces 534 in `SurefireTestCounter 534`
   (adapter-test target). The count must never decrease (697 baseline).
7. **Cost and diagnostics review (issue acceptance).**
   RESOLUTION-66.md records: per-context `wallMillis()`/`peakHeapBytes()`
   for a representative default-on index build, compared with
   RESOLUTION-21's receipts; whole-suite wall time under the new default
   vs the 697-execution flag-off baseline (measured during the GREEN
   gate run); the D1 diagnostic-invariant receipt.
8. **Comment-only sanctioned edits (no assertion changes):** statements
   in test/production comments that say the flag defaults to off or that
   "production flag-off stays textual" are updated to the opt-out
   wording — `JavacSourceSymbolIndex` javadoc (item 1) and the
   flag-writing classes' class javadocs. The #26 harness files
   (`JavacResolverCell`, `JavacResolverAccuracyHarness`,
   `JavacResolverMetricsTest`, `JavacResolverAccuracyTest`) are
   untouched entirely: case-insensitive search for "flag" over them
   returns no symbolindex references — the only flag-off comments there
   concern `flpath.java.resolver`, a different flag out of scope for
   this issue. The #26 harness CODE and its pinned defect sets are
   untouched (preflight evidence: flag-independent by construction).

## Non-goals

- Removing the textual path or the opt-out flag (the escape hatch stays).
- The #62 per-member evidence channel (separate issue; D1 only pins the
  existing invariant).
- `BoundedFrameworkModelTypeParsing` / injection contract matching (#61,
  still textual, unaffected).
- Renaming the flag or introducing a new property.
- Any change to the ServiceLoader slice (already javac-by-default).

## Certainty model

- Default (no property, `"true"`, unrecognized) ⇒ every landed consumer
  uses javac shapes; output equals today's flag-on output (acceptance;
  pinned by C4 and the two golden updates).
- `-Dflpath.java.symbolindex=false` ⇒ byte-identical to today's default
  (textual), pinned by the converted opt-out goldens and C5.
- The lookalike fix restores the textual path's per-occurrence
  canonical-Spring guard on the javac path, and adds the crossed-
  pairing check (a canonically-resolving occurrence never takes its
  value from a non-canonical same-simple-name mirror) — a lookalike
  annotation is EXCLUDED in both flag states and a canonical
  occurrence never emits a lookalike's value (fail-closed; the
  false-framework-fact windows close, never widen).
- Zero new scan-global diagnostics (D1).

## TDD sequence

- **PLAN_66** (this file): committed first.
- **Plan review**: Engram-grounded adversarial subagent, CodeRabbit
  style; findings addressed; loop until no new addressable findings.
- **RED_66**: `JavacSymbolIndexDefaultOnTest` (C1–C6, D1) plus the
  sanctioned mechanical conversions (item 3), the
  `JavacSourceSymbolIndexTest` inversion AND the cell-12 rework (item
  4) — the mechanical conversions pass at RED (they preserve current
  behavior under the still-off default) and are documented as such in
  the commit message; the expected-RED set is C1 (absent-property
  arms), C3, C4, C6, D1's non-vacuity arm, and the cell-12 rework's
  default-arm present-index assertion. Run `make adapter-test` at RED
  to confirm the expected-red cells fail and the rest of the suite
  stays green (floor receipt: 697 + new cells); `make java-lint`;
  `make evidence-validate`.
- **GREEN_66**: the `enabled()` flip + javadoc (item 1), the
  per-occurrence canonical-Spring guard (item 2), the two
  `JavaAnalysisAdapterTest` golden updates (item 4, values verified
  against the `"true"` arm), the Makefile floor raise (item 6). Full
  gates; helios08 JDK 17 lint verification before pushing.
- **Implementation review**: a NEW Engram-grounded adversarial subagent
  (CodeRabbit style) over the full change; findings addressed red-first
  with mutation checks; loop until clean.
- **EVIDENCE_66**: `docs/resolutions/RESOLUTION-66.md` — gate counts,
  cost receipts (item 7), mutation checks (revert `enabled()` ⇒ C1/C3
  fail; revert the guard fix ⇒ C6 and
  `springInjectionParametersRequireCanonicalQualifiers` fail; revert a
  golden update ⇒ the corresponding adapter test fails; restore
  `clearProperty` in one conversion site ⇒ that opt-out golden fails
  under the default), residuals.

Gates after every item: `make adapter-test` (execution count never below
697; Makefile floor raised to the GREEN count), `make java-lint` (zero
new findings; JDK 17 verified on helios08 per #64 when any doubt),
`make evidence-validate`.

## Residuals / risks

- An unrecognized property value (e.g. `"flase"`) silently keeps the
  default ON — pinned by C3 as deliberate semantics; a typo'd opt-out
  does not disable the index (documented; the alternative — warn on
  unrecognized values — adds a diagnostics channel this issue keeps
  closed).
- The per-occurrence guard fix changes `mirrorFamilies`'s signature
  (occurrence source parameter); if PMD forces helper restructuring,
  behavior stays identical and the placement is documented in the commit
  message.
- The spike measured non-flag-writing tests only (the
  `JAVA_TOOL_OPTIONS`/`clearProperty` interaction, stated in the
  preflight); the conversion in item 3 makes the unmeasured combination
  unreachable by construction. Residual risk is a flag-writing cell
  whose fixture behaves differently under default-on vs `"true"` —
  impossible: both are ON states for the consumer.
- Suite cost: every scan now builds one index per context instance. The
  spike (forced-on, 697 executions, 3:21) shows no timeout/OOM at the
  current pool sizing; RESOLUTION-66 records the per-build cost. If CI
  shows pressure, the pool sizing is a follow-up under #20, not a
  behavior change.
- The #26 harness defect pins remain LIVE_TEXTUAL against the production
  textual heuristics — they document the opt-out path after this issue;
  if the textual path is ever removed, the pins become FROZEN history
  (out of scope here).

## Plan-review record

Round 1 (Engram-grounded adversarial subagent, CodeRabbit style): 6
addressable findings (1 HIGH, 2 MEDIUM, 2 LOW, 1 NIT), all incorporated:
1. HIGH — the item-2 weakness disposition as first drafted would have
   applied the guard to the UNGUARDED `injectionQualifiers` path,
   breaking the pinned `quarkusUserDefinedNamedStillEmitsUnguarded`
   parity. Scoped to the GUARDED arms (superseded by round 2's
   per-site-kind inventory).
2. MEDIUM — the cell-12 rework was unassigned to a commit and its
   default-arm present-index assertion missing from the expected-RED
   enumeration; now assigned to RED_66 and enumerated.
3. MEDIUM — item 8 named `JavacResolverCell`/harness comments that do
   not exist (the harness's only flag-off comments concern
   `flpath.java.resolver`); the clause is dropped and the harness files
   declared untouched.
4. LOW — C1's flag-absence precondition relied on test ordering; now
   the cell's first statement ensures absence explicitly.
5. LOW — the remaining-mirrors guard cited the `uniqueMirror`
   precedent with decline semantics (superseded by round 2's
   unreachability retraction).
6. NIT — C6 twins now must co-occur with the canonical annotation in
   the same guarded block.

Round 2 (delta-only, on the round-1 edits): 3 new addressable findings
(2 MEDIUM, 1 LOW), all incorporated:
1. MEDIUM — the guarded/unguarded split is per-SITE-KIND, not
   per-framework: the constructor annotation block and the
   field/method `injectionQualifiers` wrapper are unguarded for BOTH
   frameworks (a Spring `@Autowired @demo.Qualifier` field emits in
   both flag states, matching the unguarded textual fallback), and no
   existing test pinned the Spring unguarded side. Item 2 now carries
   the four-group call-site inventory, and C6 adds the Spring
   unguarded control.
2. MEDIUM — the round-1 remaining-mirrors guard arm was unreachable
   dead code (that loop only sees empty-ASCII-prefix simple names;
   every SPRING_BEAN_ANNOTATION_TYPES key is ASCII-initial).
   Retracted; the Unicode twin is re-designated as an EMIT-path pin,
   expected-green at RED.
3. LOW — C6 lacked a source-order twin for the occurrence↔mirror
   pairing (the guard checks the occurrence, the value comes from the
   paired mirror); a swapped-order twin now pins the pairing.

Round 3 (delta-only, on the round-2 edits): 2 new addressable findings
(1 MEDIUM, 1 LOW), both incorporated:
1. MEDIUM — the per-occurrence guard alone left a crossed-pairing
   window: `captureMatches` pairs by simple name only, so a
   canonically-resolving occurrence whose OWN mirror was dropped
   (error-typed spring annotation, source-only scan) takes the
   surviving lookalike mirror's VALUE under the passing guard — a
   pre-existing flag-on manifestation of the same weak seam the flip
   would promote. Item 2 now requires the paired mirror's
   `canonicalName()` to match the occurrence's canonical resolution in
   the guarded arm (else fall to the textual occurrence answer), and
   C6 gains the dropped-canonical-mirror twin.
2. LOW — item 2 gave two inconsistent descriptions of the guard's
   mechanical placement; now specified: the guard moves to the
   occurrence loop with the occurrence's `ParsedAnnotation` surfaced
   from `takeMirror`, the remaining-mirrors call passes `null`
   (inert by the unreachability argument).

Round 4 (delta-only, on the round-3 edits): 2 new addressable findings
(1 MEDIUM, 1 LOW), both incorporated:
1. MEDIUM — the dropped-canonical-mirror twin's premise was
   empirically false on the host toolchain (scratch probe, JDK 27:
   javac retains the error-typed spring mirror, value-less — the
   repo's own `mirrorCanReplace` javadoc says values are dropped, not
   mirrors). The twin is re-specified as pinning the GUARD MOVE, the
   crossed-pairing check is documented as defense-in-depth (not
   red-able in isolation on the probed JDK), and a pre-GREEN CI-JDK
   probe records whether any mirror-absence shape makes the path
   reachable (twin added if constructible, probe receipt if not).
2. LOW — the pairing check's canonical-type derivation is now
   specified: strict equality against the occurrence's resolved FQN
   (qualified spelling or `importedTypes` import FQN), never set
   membership — the `uniqueMirror` precedent would silently accept
   cross-family `javax`/`jakarta` value substitution.

Verified across rounds: the per-BLOCK/per-OCCURRENCE root cause, the
exhaustive `mirrorFamilies` caller inventory, the exhaustive item-3
class list, the `equalsIgnoreCase(null)` safety, the #23 provenance of
both golden updates, the harness flag-independence, D1's non-vacuity,
the issue-acceptance mapping, the four-group guarded/unguarded
inventory, the remaining-mirrors unreachability, the swapped-order
twin's red-ability and post-fix output, and the textual-fallback
availability for canonical occurrences.

Confidence score: **0.92** (0.84 → 0.87 → 0.89 → 0.91 → 0.92 across
plan review). —
the production change is one line plus one bounded defect fix; the
blast radius was measured empirically (3 failing methods, each
dispositioned); the test-surface conversion is mechanical and
semantics-preserving by construction; the harness impact was verified
flag-independent by exhaustive grep; the guard-fix scoping is now
grounded in the verified four-group call-site inventory with controls
on both sides of the split. Residual risk: the `mirrorFamilies` guard
fix touches a dense correlation seam (mitigated by the per-site-kind
scoping and the lookalike matrix with order and unguarded controls),
and the golden spellings for the repository symbols assertion must be
verified against the observed flag-on rendering at RED rather than
assumed.
