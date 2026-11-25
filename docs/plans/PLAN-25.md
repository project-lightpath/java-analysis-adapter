# PLAN-25 — javac shapes for source-bound reflection targets

Issue: project-lightpath/java-analysis-adapter#25 (parent #20, last open
sub-issue).
Branch: `issue-25-javac-reflection-shapes` from `origin/main` @ `910682b`
(the PR #63 merge — MemberTypes binary shapes are on main).

## Preflight (verified on this main, 2026-11-25)

The production reflection path and every seam this plan touches:

- `SourceMetadataScanner.reflectionSites(file)` extracts `DynamicSite(offset,
  symbol, target)` sites with the reflection-layer regex machinery
  (receiver typing: `isJdkApiOwner`, `isDeclaredVariableBefore`,
  `hasVisibleField`, `parameterMatchesType`; annotation-tolerant declaration
  regexes `FIELD_START` / `SHARED_DECLARATION`). `dynamicTarget`
  (TextLayer12) keys targets textually: `Class.forName`/`loadClass` →
  `<firstQuotedLiteral>#unknown`; `getMethod`/`getDeclaredMethod` →
  `unknown#<firstQuotedLiteral>`; everything else `unknown#unknown`.
- `DynamicScanning.reflectionFeatures` wraps each site in
  `DynamicFeature(REFLECTION, caller, target, path, symbols, limitation,
  line, offset)` with the limitation "Open-world reflection APIs do not
  identify a unique runtime target."
- `ProviderFactNormalizer.dynamicFact` hardcodes
  `SemanticCallFacts.Certainty.UNRESOLVED` → `Fact.provider` →
  `Status.ABSTAINED`. `Fact.provider` already derives
  `Status.EMITTED` for any non-UNRESOLVED certainty — no status plumbing
  is missing.
- The open-world forcing (`DynamicCertaintyPolicy`) applies to CALL EDGES
  by relationship type (`callFact`) and to FRAMEWORK facts
  (`attachExactProviderIdentities` → `abstainDynamic`). The
  `DynamicFeature` provider facts are created UNRESOLVED at the source and
  are NOT re-forced by any sweep — promoting them requires only
  `dynamicFact`.
- The never-promote guard (`dynamicBehaviorNeverPromotesAnalyzerEdgesTo
  Definite`) uses a NON-LITERAL `Class.forName(typeName)` fixture — runtime
  strings stay UNRESOLVED under this plan; that test is untouched and a
  new flag-on control pins the same property for literals naming
  non-source types.
- The #26 harness reference semantics (JavacSourceSymbolResolver,
  REFLECTION_SOURCE_BOUND cells R1–R8): source-bound binary-name literal →
  DEFINITE; canonical dotted spelling of a nested type → binary-name
  mismatch, not DEFINITE; runtime-only → not DEFINITE; non-literal →
  visible abstention. The harness stays untouched (BaselineMode
  LIVE_TEXTUAL; production flag-off stays textual).
- The index (`JavacSourceSymbolIndex`, flag `flpath.java.symbolindex`,
  default off, per-context cached) provides `type(name)` (distinct-entry
  union of the canonical and binary maps), `members(typeName, memberName)`,
  `TypeInfo.shape().errorType()/duplicateBinaryName()`, `MemberInfo` with
  canonical+binary parameter/return shapes (`MemberTypes`), constructors
  named by the owner's simple name, and its own `diagnostics()` channel
  (`missing_classpath`, `index_build_error`, `javac_unavailable`,
  `missing_source`, `release_clamped`, `duplicate_binary_name`, javac error
  rows). Field-like members keep `returnType == ""` (pinned by
  `JavacSourceSymbolIndexTest`) — NOT touched by this plan.
- `JavacShapeDescriptors.jvmDescriptorFromMember` renders binary JVM
  descriptors from a correlated member.
- `firstQuotedArgument` (TextLayer12) and `splitTopLevelArguments` exist
  for literal/argument extraction; `scanner.resolveReferenceType`
  (textual, import/same-package) exists for simple-spelling resolution.
- `dynamicEvidenceAttributes` currently emits `basis` (+ `targetDescriptor`
  for proxy features) — additive javac attributes extend it.

### Interpretation of the issue scope (stated for review)

The issue's area is "SourceMetadataScannerReflectionLayer\*
annotation/parameter/type regexes"; its goal is javac shapes for
SOURCE-BOUND reflection targets while runtime strings keep the current
path. This plan reads "annotation/parameter shapes" as the shapes OF THE
TARGET the reflection code names (the member's erased parameter shapes and
its annotation canonical names), recorded from javac, and the target's
identity keyed by binary names (the same `$`-joined convention the caller
identities use, per the reflection-facts tests). The RECEIVER-typing
regexes that decide site detection are NOT rewired: #23 already javac-backed
their parameter-matching path (`parameterMatchesType`), field/local
receiver typing is not representable in the index (fields carry no type;
locals are not members), and re-typing receivers would destabilize the
exhaustively pinned site-classification matrix
(`dynamicSitesKeepCallersAndEquivalentReflectionSpellings`). That
alternative is enumerated in Residuals as a follow-up candidate.

## Scope (binding)

Production sources in `src/main` only, plus one new test class. Flag-gated
by `flpath.java.symbolindex` (default off → **default output unchanged,
pinned by flag-off goldens**). Zero edits to existing tests.

1. **`JavacTargetEvidence`** (new production record, package-private):
   the per-site javac symbol evidence — `boolean sourceBound`, `String
   keyedTarget` (binary owner keying), `String targetKind` ("class" |
   "method" | "constructor"), `String jvmDescriptor`, `List<String>
   parameterTypes` (canonical erased), `List<String> annotationNames`
   (member/type annotation canonical names), `String diagnosticCode`
   (blank when none). At most one of sourceBound / diagnosticCode is set;
   both blank ⇒ no evidence (runtime string, unchanged path).
2. **`JavacReflectionTargets`** (new production class, patterned on
   `JavacMemberCorrelation`): `static of(SourceMetadataScanner)`;
   `JavacTargetEvidence evidence(SourceFile file, DynamicSite site)`.
   - **Whole-path fail-close:** flag off, or the index carries any of
     `index_build_error`, `javac_unavailable`, `missing_source`,
     `missing_classpath` ⇒ every call returns the no-evidence sentinel
     (all facts unchanged). `release_clamped` and per-file javac error rows
     do NOT disable the path (they manifest as `errorType` on the specific
     entries, which abstain per-target).
   - **Class targets** (symbols `Class.forName`, `ClassLoader.loadClass`):
     literal = `firstQuotedArgument(structuralSource, offset)`; null
     (non-literal) ⇒ no evidence. `index.type(literal)`:
     - exactly one entry, unflagged, `!errorType`, and
       `binaryName.equals(literal)` ⇒ **source-bound** (forName resolves
       binary names): `keyedTarget = binaryName`, kind "class",
       annotations = the type's declaration annotation canonical names.
     - entries exist but the match is a canonical-spelling-only hit
       (`binaryName` differs from the literal — the `demo.Outer.Inner`
       shape) ⇒ POSSIBLE-grade, diagnostic `binary_name_mismatch`.
     - entry `errorType`, or duplicate-flagged, or ambiguous (more than
       one distinct entry) ⇒ POSSIBLE-grade, diagnostic `error_target` /
       `duplicate_target` / `ambiguous_target`.
     - no entry ⇒ no evidence (runtime string — unchanged UNRESOLVED).
   - **Member targets** (symbols `getMethod`, `getDeclaredMethod`,
     `getConstructor`, `getDeclaredConstructor`): the site's first quoted
     literal is the member name; the remaining arguments are
     class-literal parameter shapes. The OWNER resolves only from the
     site's own receiver expression — extracted on the literal-masked
     source backward from the method-name start to the previous top-level
     delimiter (`;`, `{`, `}`, or start-of-file), whitespace-trimmed:
     - receiver is a `forName("L")` invocation (static or chained shape
       ending in `.forName("L")`) ⇒ owner via the class-target ladder on L;
     - receiver is `<spelling>.class` ⇒ qualified spelling resolves
       directly through `index.type(spelling)` (exactly one unflagged
       non-error entry, else no-evidence/diagnostic as above); simple
       spelling resolves through `scanner.resolveReferenceType` and is
       then CONFIRMED by `index.type(canonical)` (unconfirmed ⇒ no
       evidence — never fabricated);
     - any other receiver (simple variable, `getClass()`, parenthesized,
       field access) ⇒ no evidence (dataflow out of scope — runtime path).
     Member selection: `index.members(ownerName, nameLiteral)`; map each
     class-literal argument (`X.class`, `X[].class`, `int.class`, …) to a
     canonical erased spelling (primitives direct; arrays append `[]`;
     reference spellings resolved as above) and compare the vector to each
     member's `parameterTypes()`; constructors (getConstructor family) map
     `<init>` semantics through the owner-simple-name index entries,
     keyed `#<init>` in the output (the caller-identity convention).
     - exactly one shape-matching member, owner unflagged, member
       `!errorType` ⇒ **source-bound**: `keyedTarget =
       binaryOwner + "#" + memberName` (`#<init>` for constructors),
       `jvmDescriptor = JavacShapeDescriptors.jvmDescriptorFromMember`,
       parameterTypes + annotationNames recorded.
     - owner resolved but no member matches the name, no member matches
       the shape vector, more than one matches, or the matching member is
       `errorType` ⇒ POSSIBLE-grade with diagnostic
       (`member_not_declared` / `shape_mismatch` / `ambiguous_member` /
       `error_member`).
     - owner unresolved (receiver of an unrecognized shape) ⇒ no evidence.
   - **Untouched symbols:** `Method.invoke`, `Constructor.newInstance`,
     `Class.newInstance`, proxy sites — receiver dataflow, always
     no-evidence (runtime path, never promoted).
3. **`DynamicFeature`** gains one component `JavacTargetEvidence
   targetEvidence` (nullable; the two compatibility constructors default
   it to null; the compact constructor normalizes). All existing
   construction sites updated mechanically; `dynamicFeatureId` does NOT
   include the evidence (ID stability; the flag-on target/keying change
   already flows through the existing `target`/`targetDescriptor` fields).
4. **`DynamicScanning.reflectionFeatures`** (flag-on) computes the
   evidence per REFLECTION site via `JavacReflectionTargets.of(scanner)`
   (one instance per scan) and passes it into the DynamicFeature. The
   textual `target`/`targetDescriptor` fields keep their current values
   flag-off; flag-on the FACT rendering (next item) prefers the javac
   keying — the DynamicFeature's textual fields remain the fallback
   record of the raw site.
5. **`ProviderFactNormalizer.dynamicFact`** consumes the evidence:
   - `sourceBound` ⇒ certainty DEFINITE, callee = `keyedTarget`,
     `targetDescriptor` attribute = `jvmDescriptor` (member targets),
     limitation "Source-defined reflection target; shape proven by the
     javac source-symbol index." (status EMITTED via `Fact.provider`).
   - diagnostic present ⇒ certainty POSSIBLE, callee = keyedTarget when
     known (else the textual target), limitation naming the diagnostic
     code, evidence attribute `diagnostic = <code>`.
   - no evidence ⇒ byte-identical to today (UNRESOLVED, ABSTAINED, same
     limitation, same attributes).
   - `dynamicEvidenceAttributes` gains additive keys when evidence is
     present: `javacSymbol`, `reflectionTargetKind`, `sourceBound`,
     `parameterTypes` (comma-joined canonical shapes), `annotationNames`
     (comma-joined), `diagnostic` — the "record the javac symbol evidence"
     acceptance.
6. **Zero scan-global diagnostics.** `ServiceLoaderDescriptorScanner`,
   `scan.diagnostics()`, `isUnique`, and the `SemanticCallFacts.Diagnostic`
   channel are untouched. The "plus a diagnostic" acceptance is carried by
   the per-fact evidence attribute + limitation (the per-member evidence
   direction #62 tracks). Blast radius statement per AGENTS: routing the
   diagnostic into `scan.diagnostics()` would block DEFINITE promotion for
   the WHOLE scan (`isUnique`) — any scan containing one
   `Class.forName("no.such.Type")` would downgrade every ServiceLoader
   DEFINITE; that blast radius is rejected, fail-closed per-fact instead.
7. **Zero changes** to: the site-classification regexes and receiver
   typing, the index schema (`MemberInfo`/`MemberTypes`/`ElementReader`),
   `JavacMemberCorrelation`, the comma-canonical dialects, the #26
   harness, `DynamicCertaintyPolicy` (edges and framework facts keep
   their forcing — a DEFINITE source-bound reflection fact claims only
   the target SHAPE, not dispatch; invoke/newInstance sites stay
   UNRESOLVED), and proxy features.

## Certainty model

- Flag on + literal target + index proves unique unflagged non-error
  ownership and (for members) a unique shape match ⇒ **DEFINITE** (the
  javac-compiled shape of a source-defined target; the harness R1/R8
  semantics).
- Flag on + index sees the name but cannot prove the shape (error,
  duplicate, ambiguity, canonical-vs-binary mismatch, member
  not-declared/shape-mismatch) ⇒ **POSSIBLE** + per-fact diagnostic —
  never DEFINITE (false-DEFINITE is the primary bug class).
- Flag on + name not in the index (runtime string), non-literal argument,
  unresolvable receiver, or index integrity failure ⇒ the EXISTING
  UNRESOLVED/ABSTAINED output, byte-identical.
- Flag off ⇒ byte-identical current output for every fact (goldens).
- Runtime strings are never promoted to DEFINITE by the new path — pinned
  by controls (F4, F5, M7, M8, E2).

## Test matrix (`JavacReflectionTargetMigrationTest`, new file)

`@Isolated` + `SAME_THREAD` (writes the flag), fixture helpers copied from
the `JavacBinaryNameMigrationTest` pattern; facts asserted through the
full `analyze` path (never vacuously — each cell asserts the fact's
certainty/status/callee/attributes). Derived from the equivalence classes
of (target kind × name shape × index answer × receiver shape × flag
state), not from examples:

- **F1 (RED): source-defined class target.** `Class.forName("demo.Present")`
  with `demo.Present` in the source set → DEFINITE, EMITTED, callee
  `demo.Present#unknown`→ keyed `demo.Present`, attributes carry
  `javacSymbol=demo.Present`, `sourceBound=true`.
- **F2 (RED): nested binary-name keying.** `Class.forName("demo.Outer$Inner")`
  → DEFINITE, callee keyed `demo.Outer$Inner` (the `$`-joined caller
  convention).
- **F3 (RED): canonical dotted spelling of a nested type.**
  `Class.forName("demo.Outer.Inner")` → POSSIBLE, diagnostic
  `binary_name_mismatch`, never DEFINITE (forName loads binary names).
- **F4 (control): runtime-only literal.** `Class.forName("java.lang.String")`
  → UNRESOLVED, ABSTAINED, byte-identical to flag-off (never DEFINITE).
- **F5 (control): non-literal.** `Class.forName(System.getProperty("x"))`
  → UNRESOLVED, no javac attributes.
- **F6 (RED): loadClass.** `ClassLoader.loadClass("demo.Present")` (a
  declared `ClassLoader` receiver) → DEFINITE keyed `demo.Present`.
- **F7 (RED): `$`/Unicode name shapes.** `Class.forName("demo.Café")` and
  a `$`-simple-name top-level type (`demo.Cash$Flow`) → DEFINITE with the
  exact binary keying (boundary cells for the lookup arithmetic).
- **M1 (RED): chained member target.**
  `Class.forName("demo.Present").getDeclaredMethod("handle", String.class)`
  with `void handle(String s)` in source → DEFINITE, callee
  `demo.Present#handle`, `targetDescriptor` `(Ljava/lang/String;)V`,
  parameterTypes `java.lang.String` recorded.
- **M2 (RED): overload differentiation.** Two `run` overloads
  (`int` / `String`); two sites selecting each by class literal → each
  site DEFINITE with ITS OWN member (mutation-sensitive pin for the
  vector matching).
- **M3 (RED): varargs.** `x(String...)` selected by `x(String[].class)` →
  DEFINITE (erased `java.lang.String[]`).
- **M4 (RED): generic erasure.** `x(java.util.List<String>)` selected by
  `x(List.class)` → DEFINITE (`java.util.List`).
- **M5 (RED): array + primitive shapes.** `x(int[])` by `int[].class` →
  DEFINITE (`int[]`).
- **M6 (RED): class-literal receiver.** `Present.class.getMethod("run")`
  (simple spelling, same package; and a qualified `demo.Present.class`
  twin) → DEFINITE.
- **M7 (control): variable receiver.** `Class<?> c = Present.class;
  c.getMethod("run")` → UNRESOLVED unchanged (no dataflow).
- **M8 (control): getClass() receiver.** → UNRESOLVED unchanged.
- **M9 (RED): member not declared.** `getMethod("missing")` on a resolved
  source owner → POSSIBLE, diagnostic `member_not_declared`.
- **M10 (RED): constructor target.**
  `Class.forName("demo.Present").getDeclaredConstructor(String.class)` →
  DEFINITE, callee `demo.Present#<init>`, descriptor
  `(Ljava/lang/String;)V`.
- **M11 (RED): annotation/parameter evidence.** Source-bound member whose
  declaration carries annotations including a `$`/Unicode annotation name
  (`@demo.Märk$Odd`) → evidence `annotationNames` match javac canonical
  rendering; parameter shapes recorded (the issue's annotation-shape
  cell).
- **E1 (RED): error member.** Owner resolved, target member's parameter
  references a missing type (`errorType` member) → POSSIBLE, diagnostic
  `error_member`.
- **E2 (RED): index integrity fail-close.** Context with a required
  absent classpath entry (index `missing_classpath`) → NO fact is
  promoted; every reflection fact byte-identical to flag-off.
- **E3 (RED): duplicate-flagged owner.** Two files declaring the same
  binary name; forName literal naming it → POSSIBLE, diagnostic
  `duplicate_target` (never DEFINITE).
- **G-series (flag-off goldens, GREEN at RED):** every F/M fixture run
  flag-off → exact current output: textual callee (`demo.Present#unknown`
  / `unknown#handle`), UNRESOLVED, ABSTAINED, `basis`-only attributes.
  Count-form where per-site identity is not observable.
- Pairwise (AGENTS): target kind × flag state (F1 vs G), name shape ×
  index answer (F2/F3/F7/E3), receiver shape × literal (M1/M6/M7/M8),
  member × overload count (M2), Unicode annotation × member resolution
  (M11), loadClass × forName (F6 vs F1).

## Boundary probes (pre-GREEN, scratch, not committed)

Empirical probes of the receiver/argument arithmetic per AGENTS: empty
receiver text, single-character receiver, receiver at start-of-file,
prefix/suffix overlap (a receiver whose spelling ends with the method
name), whitespace inside `X . class` and `forName ( "L" )`, parenthesized
receivers, `this.`-prefixed receivers, class-literal arrays
(`String[].class` vs `String [].class`), and `#`/`$` boundaries in the
keyed target join. Probes run against the compiled
`JavacReflectionTargets` before GREEN; findings fold back into the
implementation and (if a shape is reachable) into a cell.

## TDD sequence

- **PLAN_25** (this file): committed first.
- **Plan review**: Engram-grounded adversarial subagent, CodeRabbit
  style; findings addressed; loop until no new addressable findings.
- **RED_25**: `JavacReflectionTargetMigrationTest` with the matrix above,
  plus a behavior-neutral stub (`JavacReflectionTargets` always returning
  no-evidence, `JavacTargetEvidence` record, `DynamicFeature` component
  defaulted) documented in the commit. Expected RED: F1–F3, F6, F7, M1–M6,
  M9–M11, E1–E3. Expected GREEN at RED: F4, F5, M7, M8, G-series. Run
  `make adapter-test` at RED to confirm the expected-red cells fail and
  the rest of the suite stays green.
- **GREEN_25**: minimal implementation — `JavacTargetEvidence`,
  `JavacReflectionTargets` (class + member ladders, fail-close),
  `DynamicFeature` component, `reflectionFeatures` wiring,
  `dynamicFact` certainty/limitation/attributes. Boundary probes before
  the commit. Full gates.
- **Implementation review**: a NEW Engram-grounded adversarial subagent
  (CodeRabbit style) over the full change; findings addressed red-first;
  loop until clean.
- **EVIDENCE_25**: `docs/resolutions/RESOLUTION-25.md` — gate counts,
  mutation checks (revert the dynamicFact promotion → F1/M1 fail; revert
  the integrity fail-close → E2 fails; revert the parameter-vector
  matching → M2 fails; revert the binary keying → F2 fails; revert the
  per-fact diagnostic channel → F3/M9/E1 fail), probe receipts, residuals.

Gates after every item: `make adapter-test` (execution count never below
the main floor **652**; Makefile floor 534), `make java-lint` (zero new
findings — per #64, if any new/changed class could approach the GodClass
threshold, verify under JDK 17 on helios08 with a scratch checkout before
pushing), `make evidence-validate`.

## Non-goals

- Receiver-variable dataflow (which `Class`/`Method` object a variable
  holds) — variable-receiver member targets stay unresolved.
- Rewiring the site-classification receiver-typing regexes to javac (see
  Interpretation; #23 owns the parameter path; field/local shapes are not
  in the index).
- Field reflection targets (`getField`/`getDeclaredField`) — no dynamic
  site extracts them today; adding site extraction is new-surface work,
  not shape work. Filed as a follow-up candidate under #20.
- Resolving runtime strings through the classpath (issue non-goal).
- Inherited/overridden member resolution for `getMethod` (public-method
  lookup sees inherited members; the index holds declared members only) —
  an inherited-only member yields `member_not_declared` POSSIBLE, never a
  wrong owner attribution.
- Flipping the flag default (umbrella #20's final step), TCCL/dynamic
  policy changes, descriptor dialect changes (#60 landed them).
- Scan-global diagnostics (rejected with blast-radius rationale above).

## Residuals / risks

- A `getMethod` site naming an inherited member downgrades to POSSIBLE
  with `member_not_declared` even when the superclass is in the source
  set — fail-closed, documented; inherited-member walking is a follow-up
  (same class as the FLPATH-5124 residual).
- Simple-spelling class-literal receivers depend on the textual
  `resolveReferenceType` for the candidate canonical, but the index
  CONFIRMS ownership before any promotion — a wrong textual resolution
  yields no index confirmation (abstention), never a fabricated owner.
- `Class.forName("a" + "b")` folded concatenations are non-literal to the
  textual extractor ⇒ no evidence (runtime path) — matches the harness's
  NON_LITERAL_FORNAME semantics.
- The per-fact diagnostic channel predates #62's proper per-member
  evidence design; if #62 lands a richer channel, the attributes migrate
  mechanically.
- Local/anonymous owner keying: `forName` literals naming local classes
  (`demo.Outer$1Local`) resolve through the binary-name map — keyed
  DEFINITE when unique; the member ladder inherits this.
- PMD: `JavacReflectionTargets` is split into helper-sized methods from
  the start (class ladder, member ladder, receiver parsing, literal
  mapping); if a placement change is forced, behavior stays identical and
  the placement is documented in the commit message.

Confidence score: **0.78** (pre-plan-review). The seams are verified on
this main (no policy sweep re-forces the dynamic facts; `Fact.provider`
already emits EMITTED; the index carries every shape needed). Main risks:
the receiver-expression extraction arithmetic (mitigated by mandatory
empirical probes), the reviewer's reading of the diagnostics channel
(mitigated by the explicit blast-radius rejection and the #62 alignment),
and the possibility that the issue intended receiver re-typing rather than
target shapes (mitigated by the stated interpretation + the harness R-cell
semantics, which this design matches cell-for-cell).
