# PLAN-16 — javac-backed source-symbol resolver accuracy spike and GO/NO-GO

Branch: `spike/javac-symbol-resolver` (from `origin/main` at `0ced20a`).
Issue: project-lightpath/java-analysis-adapter#16.
Companion documents: `AGENTS.md`. The A-cell text is copied into this plan
from `PLAN-9.md` on `origin/issue-9/certainty-gaps`; this plan does not depend
on PR #12. The baseline is explicitly `0ced20a`, **before** PR #12's fixes.

## Implementation note (post-spike)

FLPATH-5142 added the production seam: `JavacServiceLoaderSiteResolver` in
`src/main`, flag-gated by `-Dflpath.java.resolver=javac`, with
`ServiceLoaderSourceCache` using compiler-bound sites when enabled. The
default path and output remain textual. The "test scope only" wording below
is the original spike record; the production seam is now present and
flag-gated.

## Scope (binding)

This is a spike. It must not change default fact output, must not delete or
rewire the textual resolvers, and must not merge into PR #12 / issue #9 work.
The javac resolver and the accuracy harness live in **test scope** behind a
test seam (`JavacSourceSymbolResolver`, test sources only). Production sources
are unchanged. Dynamic/reflection and descriptor logic are out of scope and
untouched. No production diagnostic is added, so the
`ServiceLoaderScanner.isUnique` scan-global blast radius is unchanged.

The spike answers one question: **can javac eliminate wrong DEFINITE
ServiceLoader source facts on the PR #12 matrix without losing more facts than
the current textual path?**

## Fact and abstention model

A **fact** is one call bound by the compiler to the public static member
`load` of `java.util.ServiceLoader`. A fact carries:

- relative source path, line, column, offset, enclosing top-level type;
- canonical `serviceType` (not a binary name);
- owner `java.util.ServiceLoader`, member `load`;
- accessibility evidence (`PUBLIC`, `STATIC`);
- certainty `DEFINITE` or `POSSIBLE`;
- diagnostics attached to the downgrade.

An **abstention** is a syntactic `*.load(...)` site that is not bound. It
carries the same position, a reason (`SHADOWED`, `AMBIGUOUS`, `MISSING_INPUT`,
`ERROR_TYPE`, `NO_CLASS_LITERAL`), and diagnostics. Facts and abstentions are
separate collections, so an ambiguous class name cannot be silently reported
as "no fact". A non-null `Trees.getElement` is not enough: only
`ElementKind.METHOD` whose enclosing type is `java.util.ServiceLoader` and
whose name is `load` produces a fact (A2/A5/A10/A13/A14/A17/A20 resolve the
*type* `ServiceLoader`, not the member, and must produce zero facts).

The **baseline is a site proxy, not production DEFINITE**. It drives
`ServiceLoaderSourceCache.source(scanner, caller)` (the caller `MethodSymbol`,
never the `java.util.ServiceLoader` owner) and canonicalizes each site's
`serviceType` through `scanner.resolveReferenceType` with the site's
`fallbackServiceType`. It is labelled `baselineAttributedSites`; it does not
claim `ServiceLoaderDescriptorScanner.isUnique` or a descriptor-backed
DEFINITE. `ServiceLoaderCallSiteReader.read` needs a real Soot `CallEdge` and
is therefore not fabricated in the harness.

### Expectations (per site)

A cell carries `expectedSites`: a list of `(offset, kind, canonicalType)`.

- `DEFINITE`: exactly one bound fact, DEFINITE, expected canonical type.
- `POSSIBLE`: at least one bound fact and no DEFINITE.
- `AMBIGUOUS`: no fact, at least one abstention with an ambiguity diagnostic.
- `MISSING_INPUT`: no DEFINITE; either a POSSIBLE fact or an abstention, and
  at least one missing-input/error-type diagnostic.
- `SHADOWED`: no fact and no ambiguity/missing-input diagnostic.

Multi-site cells (N20-N23) list one expectation per site.

### Metrics (per path, per cell, per site)

- `wrongDefinite`: DEFINITE where the expectation is not DEFINITE.
- `wrongServiceType`: a fact whose canonical service type differs.
- `lost`: expected DEFINITE but no fact.
- `downgraded`: expected DEFINITE but only POSSIBLE.
- `added`: any fact where the expectation is SHADOWED or AMBIGUOUS (the
  issue-term metric; a POSSIBLE fact is not expected there).
- `unexpectedFact`: the union behind `added`, retained for report clarity; it
  excludes MISSING_INPUT, where a POSSIBLE fact is expected.
- `abstentionMismatch`: AMBIGUOUS/MISSING_INPUT expectation without a
  matching abstention or diagnostic. A DEFINITE fact on MISSING_INPUT is
  already caught by `wrongDefinite`.
- `wallMillis`, `peakHeapBytes`, `deterministic`.

Each metric is proved to fire by a synthetic unit control in
`JavacResolverMetricsTest`; fixture rows alone do not prove the detector. The
downgrade control is synthetic, not a gating catalog cell, so a deliberate
POSSIBLE never sits in the GO table.

### GO thresholds (asserted by `JavacResolverAccuracyTest`)

Javac path: `wrongDefinite = 0`, `wrongServiceType = 0`, `lost = 0`,
`downgraded = 0`, `added = 0`, `unexpectedFact = 0`,
`abstentionMismatch = 0`, `deterministic = true`, `peakHeapBytes` below the
`-Xmx256m` ceiling (no OOM), and `blockedRequiredCells = 0`. Baseline
`wrongDefinite` is recorded and must not be zero (the golden above); the
comparison is the GO evidence, not a baseline pass condition.

## Spec derivation (not example enumeration)

### JLS precedence and scope cells (N-series)

Derived from JLS 6.5 / 6.4.1 weakest to strongest: on-demand import <
same-package type in another compilation unit < single-type import <
same-compilation-unit type < inherited member types < local class <
in-scope variable (parameter, local, field) < lambda parameter < catch
parameter < instanceof pattern variable. JLS 6.4.1 makes a wildcard import the
weakest form; a same-package type beats it. Fully qualified names bypass
shadowing. JLS 6.3 supplies scope extents: resources span the try block,
for-each variables span the for statement, catch parameters the catch block,
lambda parameters the lambda body only.

| Cell | Scope / interaction | Expected sites |
|---|---|---|
| N1 | `import java.util.*` alone | DEFINITE |
| N2 | same-package `ServiceLoader` in another file beats wildcard | SHADOWED |
| N3 | single-type `import java.util.ServiceLoader` | DEFINITE |
| N4 | foreign single-type import, no wildcard | SHADOWED |
| N5 | wildcard + foreign single-type import | SHADOWED |
| N6 | same-CU top-level `ServiceLoader` | SHADOWED |
| N7 | same-CU nested `ServiceLoader` | SHADOWED |
| N8 | local class in a method | SHADOWED |
| N9 | field named `ServiceLoader` | SHADOWED |
| N10 | method parameter named `ServiceLoader` | SHADOWED |
| N11 | lambda parameter, call inside lambda body | SHADOWED |
| N12 | catch parameter, call inside catch block only | SHADOWED |
| N13 | instanceof pattern variable, call inside true arm | SHADOWED |
| N14 | try-with-resources variable, call inside try block | SHADOWED |
| N15 | for-each variable, call inside for statement | SHADOWED |
| N16 | inherited member type from a direct superclass | SHADOWED |
| N17 | unrelated same-name type in another package | DEFINITE |
| N18 | fully qualified call survives an inherited-member shadow | DEFINITE |
| N19 | same-package top-level in a differently named file | SHADOWED |
| N20 | lambda x local class: local class shadows inside its body only | site inside local: SHADOWED; site after local in lambda: DEFINITE |
| N21 | catch x nested type: nested shadow ends with the catch block | site inside catch: SHADOWED; site after catch: DEFINITE |
| N22 | switch pattern (release 21) shadows only its arm | site in arm: SHADOWED; site after switch: DEFINITE |
| N23 | lambda x switch: switch pattern inside a lambda body | site in arm: SHADOWED; site after lambda: DEFINITE |
| N24a | same-CU top-level type x inherited member type: top-level shadow site | SHADOWED |
| N24b | same-CU top-level type x inherited member type: inherited-member site | SHADOWED |
| N25 | site `Bridge extends other.Base`, public member, single-type import | SHADOWED |
| N26 | same, on-demand import `other.*` | SHADOWED |
| N27 | same, qualified `extends other.Base` | SHADOWED |
| N28 | site `Bridge extends other.Base`, package-private member, cross-package | DEFINITE (not inherited, JLS 8.5) |
| N29 | site `Bridge extends other.Base`, package-private member, same package | SHADOWED |
| N30 | site `Bridge extends other.Base`, protected member, cross-package | SHADOWED |
| N31 | site `Bridge extends other.Base`, private member | DEFINITE (never inherited) |

N22/N23 require `--release 21`; the harness records `java.version`, the
release per cell, and blocked cells. A blocked required cell fails the accuracy
test (it is not silently skipped) because the JDK here is 27. GO treats a
blocked required cell as a failed threshold.

The site-side inheritance cells N25-N27 cover the three `extends`-resolution
branches for a public member; N28-N31 cover package-private/private/protected
accessibility variants on the qualified branch. The three-branch B4/B5
cross-product (protected/package-private through single-type import and
on-demand import), B6 (deeper nested member), and B7 (superinterface member)
remain documented residuals, not claimed as covered. C10-C12 parity is
covered by the D-series below.

### PR #12 A-series cells (static-import owner, inheritance, annotations)

Reproduced from `PLAN-9.md`, with javac/spec ground truth, not textual parity.
Where `PLAN-9.md` records a documented textual over-abstention (A7), the
harness expectation is the **spec** result and the baseline row records the
lost fact. A7 therefore demonstrates a javac gain, not a javac regression.

| Cell | Fixture | Expected (spec) |
|---|---|---|
| A1 | `import static a.Tools.*`; unrelated `b.Tools` has a public member | DEFINITE (owner mismatch) |
| A2 | `a.Tools` declares a public member, with `java.util.*` | AMBIGUOUS |
| A3 | only `import static a.Tools.ServiceLoader` with `java.util.*` | SHADOWED (static single-member type wins) |
| A4 | `a.Tools` member is package-private, site in `demo` | DEFINITE (not accessible at import site) |
| A5 | A4 with site in `a` | AMBIGUOUS (accessible) |
| A6 | `a.Tools` member is private | DEFINITE (not importable) |
| A7 | `a.Tools` member is protected, site in `demo` | DEFINITE (not accessible, JLS 7.5.4) |
| A8 | owner outside selected files; unrelated `b.Tools` selected | POSSIBLE (owner unproven, error diagnostic) |
| A9 | owner outside selected files, no lookalike | POSSIBLE (owner unproven, error diagnostic) |
| A10 | owner extends `a.Base`; Base has public member | AMBIGUOUS (inherited member) |
| A11 | owner extends missing `external.Base` | POSSIBLE (inheritance unproven, error diagnostic) |
| A12 | owner extends clean `a.Plain` | DEFINITE (member set proven absent) |
| A13 | owner implements `a.Iface` with member | AMBIGUOUS (interface member) |
| A14 | interface owner extends `a.First, a.Second`; Second has member | AMBIGUOUS (second superinterface) |
| A15 | owner extends `a.Base extends external.X` | POSSIBLE (grandparent unproven) |
| A16 | Base member is package-private, site in `demo` | DEFINITE (inherited but not accessible) |
| A16b | A16 with site in `a` | AMBIGUOUS (inherited and accessible) |
| A17 | public member annotated `@$Outer.Ann({Object.class})` | AMBIGUOUS (qualified annotation) |
| A20 | owner extends annotated `@Mark a.Base` with member; `@Mark` is `@Target(TYPE_USE)` | AMBIGUOUS (annotated extends) |
| A21 | owner declares private member; superclass public member | DEFINITE (private hides inherited) |

A18/A19 are type-shape probes, not call facts:
`String @$Ann... args` must resolve to `java.lang.String[]`, and
`@Ann(value=1) String p` must resolve to `java.lang.String`. `$Ann` and `Ann`
are `@Target(TYPE_USE)`. The javac side erases type annotations recursively
(`ArrayType` component erasure plus `[]`) instead of comparing
`TypeMirror.toString()` text. The textual side calls
`SourceMetadataScanner.parameterType`; on `0ced20a` it returns the known-bad
`String@$Ann[]` and `(value=1)String`. Those exact textual values are pinned
as baseline losses (drift detector), **not** asserted for parity.

### `$` binary-name cells (D-series)

JLS 13.1: `$` is legal in identifiers and marks nested binary names. Canonical
names use `.`; binary names use `$`; local/anonymous classes have an empty
canonical name. Facts are therefore keyed by `(relative path, offset,
enclosing top-level type)`, never by binary owner alone. Each D-cell asserts
both the javac fact **and** the baseline `scanner.sourceFile(binaryOwner)`
result. Baseline owners are the production binary spellings.

| Cell | Fixture / binary owner | javac | baseline `sourceFile` |
|---|---|---|---|
| D1 | top-level `demo.Cash$Flow` + decoy `demo/Cash.java`; owner `demo.Cash$Flow` | DEFINITE | wrong decoy `demo.Cash` |
| D2 | nested `demo.Outer.Inner`; owner `demo.Outer$Inner` | DEFINITE | correct `demo.Outer` |
| D3 | top-level `demo.Outer$Inner` + decoy `demo/Outer.java`; owner `demo.Outer$Inner` | DEFINITE, canonical `demo.Outer$Inner` | wrong decoy `demo.Outer` |
| D4 | package `a$b`, owner `a$b.Outer$Inner` + default-package decoy `a.java` | DEFINITE | wrong `a` |
| D5 | anonymous/local owner `demo.Bridge$1` / `$1Local` | DEFINITE keyed by position | correct `demo.Bridge` |
| D6 | leading `$`: `demo.$Flow`; owner `demo.$Flow` | DEFINITE | `Optional.empty()` (truncates to `demo.`) |
| D7 | adjacent `$$`: `demo.Cash$$Flow` + decoy `demo/Cash.java` | DEFINITE | wrong decoy `demo.Cash` |
| D8 | single-character `demo.$`; owner `demo.$` | DEFINITE | `Optional.empty()` (truncates to `demo.`) |
| D9 | package `$`: owner `a$b.Outer` + default-package decoy `a.java` | DEFINITE, canonical `a$b.Outer` | wrong `a` (must never be accepted by javac) |

D1/D3/D7/D9 are prefix/suffix-overlap probes; D8 is the exact-boundary
probe; D6 is the empty-suffix probe. The D-cells are scored against the
textual `sourceFile` baseline. C10-C12 helper parity
(`declaredType`/`methodSource`/`sourceLineContains`) and the "all five
sourceFile callers" claim moved to residuals after implementation review.

### Incomplete-input cells (I-series)

Issue constraint 3: error types or missing files produce `POSSIBLE`/unresolved
plus a specific diagnostic; no DEFINITE from unproven input.

**Task-global downgrade:** if any compilation unit in the cell's compiler task
has an ERROR diagnostic, every bound fact in the task is POSSIBLE and carries
the diagnostic. This is required because A11/A15/I5 errors are in the owner
file (`a/Tools.java` or `a/Base.java`), not in the site compilation unit. It
is conservative and mirrors the repository's scan-global certainty rule
locally in the harness.

| Cell | Fixture | Expected |
|---|---|---|
| I1 | `ServiceLoader.load(Missing.class)`, no `Missing` | MISSING_INPUT (POSSIBLE fact + diagnostic) |
| I2 | missing single-type import in the same unit, valid `Object.class` site | MISSING_INPUT (POSSIBLE) |
| I3 | missing superclass, valid `Object.class` site | MISSING_INPUT (POSSIBLE) |
| I4 | duplicate binary name in two explicit compilation units | MISSING_INPUT (POSSIBLE) |
| I5 | `a.Base` declares `Missing payload;`; `a.Tools extends a.Base` with no `ServiceLoader` member | MISSING_INPUT (site binds, task error -> POSSIBLE) |

I4 passes both `r1/demo/Dup.java` and `r2/demo/Dup.java` as explicit units.
I5 defines the error-typed inherited member shape. The test asserts a
non-empty diagnostic with its compiler code, not one hard-coded code, because
javac's specific error code varies by shape.

## Baseline golden (pinned executables, not prose counts)

The test pins the baseline cell sets and totals `(wrongDefinite, lost, added,
abstentionMismatch)`; per-cell canonical offsets/types are recorded in the
observation rather than asserted as a second positional contract.
On `0ced20a` the live `ServiceLoaderSourceCache` proxy emits one site for
A9, A10, A11, A13, A14, A15, A16b, A20 (eight wrong DEFINITE proxies) and
none for A1, A4, A7 (three lost facts). A7 is the documented textual
over-abstention. A16 is a correct-baseline guard (one site, expected
DEFINITE). A16b is the same-package control and is a baseline false positive.
The exact tuples are written by the harness into the observation and asserted
in the test, so baseline drift is visible.

## Implementation shape (test scope only)

Files:

- `src/test/java/com/projectlightpath/analysis/java/JavacSourceSymbolResolver.java`
  — compiler-backed resolver using `JavaCompiler` / `JavacTask` / `Trees`.
  Options: `-proc:none`, `--release <cell release>`, `-encoding UTF-8`,
  explicit `-classpath`, explicit `-sourcepath`, fresh `-d` per cell. It never
  relies on the ambient `java.class.path`.
- `JavacResolverCell.java` — id, family, `expectedSites` list, source map,
  extra explicit compilation units, classpath, release, binary/canonical
  owner.
- `JavacResolverFixtureCatalog.java` — N/A/D/I cells.
- `JavacResolverAccuracyHarness.java` — runs both paths, computes facts,
  abstentions, metrics, peak heap, wall time; writes deterministic JSON with
  relative paths only.
- `JavacResolverHarnessMain.java` — subprocess entry. Accepts
  `--report <unique path>` (the main runs two fresh in-process passes for a
  real determinism flag); samples heap per phase with `MemoryMXBean`; one
  `-Xmx256m` child for the whole run, with `peakHeapBytes` recorded separately
  for the baseline and javac phases.
- `JavacResolverMetricsTest.java` — synthetic negative controls for every
  metric, including `downgraded`, `added`/`unexpectedFact`, and
  `wrongServiceType`.
- `JavacResolverAccuracyTest.java` — resolves the child classpath from
  `surefire.test.class.path` (fallback `java.class.path`), uses
  `java.home/bin/java`, spawns the harness twice with unique reports, asserts
  thresholds, determinism, baseline golden, evidence completeness,
  diagnostics, type probes, and report schema.

A flag-gated production seam now exists in `src/main`; the report is copied to
`spike/results/javac-symbol-resolver-observation.json` before
`make evidence-validate`; only relative paths are stored.
`spike/scripts/validate_spike.py` gains a schema/count/no-host-path check for
that file, so `make evidence-validate` actually covers it. Diagnostics store
`Diagnostic.getCode()`, `getMessage(Locale.ROOT)`, and relative source/line/
column — never `toString()`, absolute paths, or temp classpaths.

## TDD sequence

- **RED_16**: add the cell catalog, harness, metrics tests, accuracy test, and
  a behavior-neutral `JavacSourceSymbolResolver` stub returning an empty
  resolution. The stub exists only so RED compiles; it is documented in the
  commit. RED gate: `mvn -Dtest=JavacResolverAccuracyTest test` fails as
  expected, and `mvn -Dtest='!JavacResolverAccuracyTest' test` keeps the 291
  baseline tests green. Full `make adapter-test` is not expected to pass at
  RED because the new test is intentionally failing.
- **GREEN_16**: implement the resolver; targeted test passes. Copy the report
  to `spike/results/`, then run the full `make adapter-test`,
  `make java-lint`, `make evidence-validate`.
- **EVIDENCE_16**: commit the generated observation, `RESOLUTION-16.md`
  (baseline vs prototype table, GO/NO-GO, residuals), and gate receipts.

## Gates

- `make adapter-test` — count must not decrease from 291 (GREEN/EVIDENCE).
- `make java-lint` — 0 PMD/CPD findings. Note: `pom.xml` sets
  `<includeTests>false</includeTests>`, so this gate is **vacuous for
  test-scope spike code**; the EVIDENCE commit states that and keeps the test
  code style-checked by review instead of claiming PMD coverage.
- `make evidence-validate` — pass, including the new report check.

## Residuals / risks

- The production seam is flag-gated; production wiring is now present and
  default enablement is the follow-up.
- The harness covers ServiceLoader source facts only. Other name-resolution
  consumers (`StaticImportOwnerResolver` does not exist on this branch; PR #12
  consumers, framework annotations, reflection) are not migrated here.
- Task-global ERROR downgrade is deliberately conservative: an unrelated error
  anywhere in the cell downgrades every fact. This mirrors the safe direction
  but may over-abstain; the harness records it.
- Duplicate binary names are detected as compiler diagnostics; the prototype
  does not choose a winner.
- Peak heap is sampled by `MemoryMXBean` per phase in a fresh `-Xmx256m` JVM,
  not JFR; it is indicative, and the run fails if the ceiling is exhausted.
- Switch-pattern cells require JDK >= 21; the harness fails a blocked required
  cell rather than silently dropping it.
- A18/A19 type-shape probes assert separate textual and javac expectations;
  the textual known-bad values are pinned as baseline losses.
