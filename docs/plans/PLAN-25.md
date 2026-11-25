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
  visible abstention. The DEFINITION cells match this design; the
  ABSTENTION grades diverge by design (the harness resolver uses
  task-global `taskHasError` and POSSIBLE-with-name for runtime-only
  literals, while production keeps runtime strings UNRESOLVED per the
  issue's "runtime strings stay POSSIBLE/unresolved as today", and
  production's error authority is per-entry `errorType` — the seam
  convention since #22 — not per-task). The harness itself stays untouched
  (BaselineMode LIVE_TEXTUAL; production flag-off stays textual).
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
     `missing_classpath`, `missing_source_root` ⇒
     every call returns the no-evidence sentinel (all facts unchanged).
     `missing_source_root` is included because the build proceeds over
     the REMAINING roots: a literal naming a type declared in both a
     present and a missing root would see exactly one entry — fabricated
     uniqueness, the primary bug class. `release_clamped` and per-file
     javac error rows do NOT disable the path (they manifest as
     `errorType` on the specific entries, which abstain per-target).
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
   - **Member targets — method symbols** (`getMethod`,
     `getDeclaredMethod`): the site's first quoted literal is the member
     name; a method site with NO quoted literal (computed name) ⇒ no
     evidence (runtime path). The remaining arguments are class-literal
     parameter shapes. The OWNER resolves only from the site's own
     receiver expression — the receiver RANGE is extracted on the
     literal-masked source backward from the method-name start to the
     previous top-level delimiter (`;`, `{`, `}`, or start-of-file),
     whitespace-trimmed (the mask is offset-preserving; the masked range
     maps 1:1 onto the structural source):
     - receiver is a `forName("L")` invocation (static or chained shape
       ending in `.forName(...)`): L is recovered by reading the
       STRUCTURAL source inside the receiver range (the mask hides the
       literal; the offsets align) via `firstQuotedArgument` anchored at
       the `forName` token — non-literal ⇒ no evidence. Owner then follows
       the class-target ladder on L;
     - receiver is `<spelling>.class` — matched against the SUFFIX of the
       trimmed receiver range (a leading statement keyword such as
       `return`/`throw`/`assert`/`case`/`yield`/`else`/`do`/`new` is not
       part of the receiver; the receiver is the longest dotted-identifier
       chain ending at the shape, whose start is preceded by an accepted
       pre-chain boundary: `= ( , ; { } && || ? : -> ! + - * / < >` or
       whitespace-after-keyword — anything else makes the receiver
       unrecognized ⇒ no evidence) ⇒
       qualified spelling resolves directly through `index.type(spelling)`
       (exactly one unflagged non-error entry, else
       no-evidence/diagnostic as above); simple spelling resolves through
       `scanner.resolveReferenceType` and is then CONFIRMED by
       `index.type(canonical)` (unconfirmed ⇒ no evidence — never
       fabricated);
     - any other receiver (simple variable, `getClass()`, parenthesized,
       field access) ⇒ no evidence (dataflow out of scope — runtime path).
     Member selection: `index.members(ownerName, nameLiteral)`; map each
     class-literal argument (`X.class`, `X[].class`, `int.class`, …) to a
     canonical erased spelling (primitives direct; arrays append `[]`;
     reference spellings resolved as the receiver spellings above — an
     argument spelling that cannot be resolved ⇒ NO evidence for the
     whole site, not `shape_mismatch`: the mismatch diagnostic must
     claim index knowledge the index does not have) and compare the
     vector to each member's `parameterTypes()`.
   - **Member targets — constructor symbols** (`getConstructor`,
     `getDeclaredConstructor`): these sites carry NO name literal
     (`firstQuotedArgument` is null; TextLayer12 maps them to
     `unknown#unknown`). Constructor members are selected WITHOUT name
     derivation — filter the resolved owner entry's own `members()` by
     `kind == "constructor"` (deriving the simple name from the binary
     name is wrong at the `$` boundary: `demo.Cash$Flow`'s simple name is
     `Cash$Flow`, not `Flow`); EVERY argument is a parameter class
     literal; the owner resolves from the receiver exactly as for method
     symbols; selection compares the argument vector against the
     constructor members' `parameterTypes()` (zero-arg
     `getDeclaredConstructor()` ⇒ the zero-parameter constructor, if
     unique — multiple zero-parameter constructors are impossible in
     valid source, so the only multiplicity source is duplicate binary
     names, already handled by the owner-level `duplicate_target`).
     Keyed `#<init>` in the output (the caller-identity convention).
     Extraction anchoring: the method-name start is recovered from the
     symbol text at/after `site.offset` — chained lookup sites carry it
     exactly (`lookup.methodStart()`); receiver-call and this-receiver
     sites anchor at the receiver identifier/field name, whose backward
     ranges cannot suffix-match a `.class`/forName shape and always fail
     closed to no evidence.
   - **Visibility gate (public-only lookup APIs).** `getMethod` and
     `getConstructor` return PUBLIC members at runtime; a non-public
     match throws `NoSuchMethodException`. `MemberInfo` gains an additive
     `publicMember` flag (see item 7) and the public-only symbols require
     it for a source-bound match; a shape match on a non-public member
     downgrades to POSSIBLE with diagnostic `visibility_mismatch`.
     `getDeclaredMethod`/`getDeclaredConstructor` see every visibility —
     no gate.
     - exactly one shape-matching member, owner unflagged, member
       `!errorType` ⇒ **source-bound**: `keyedTarget =
       binaryOwner + "#" + memberName` (`#<init>` for constructors),
       `jvmDescriptor = JavacShapeDescriptors.jvmDescriptorFromMember`,
       parameterTypes + annotationNames recorded. The vector match
       requires EQUALITY (same length, same spellings) — a site with
       fewer class-literal arguments than the member's parameters is a
       shape mismatch ( boxing/varargs are NOT inferred: `x(String...)`
       is selected only by `x(String[].class)`, exactly as reflection
       itself resolves it).
     - owner resolved but no member matches the name, no member matches
       the shape vector, more than one matches, or the matching member is
       `errorType` ⇒ POSSIBLE-grade with diagnostic
       (`member_not_declared` / `shape_mismatch` / `ambiguous_member` /
       `error_member`).
     - owner unresolved (receiver of an unrecognized shape) ⇒ no evidence.
   - **Per-entry error authority:** javac ERROR diagnostic rows elsewhere
     in the compilation (an unrelated file referencing `missing.Dep`) do
     NOT block a clean target: the deciding signal is the target entry's
     own `errorType`/duplicate flags — the seam convention since #22
     (deliberate divergence from the harness resolver's task-global
     `taskHasError`, pinned by cell E4 below).
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
   (the resolver object is stateless and cheap; the expensive part — the
   javac index — is already cached per `AnalysisContext`, so one `of()`
   per `reflectionFeatures` call — which the scanner recomputes per
   consumer — costs nothing beyond a small allocation). It passes the
   evidence into the DynamicFeature. The textual `target`/
   `targetDescriptor` fields keep their current values flag-off; flag-on
   the FACT rendering (next item) prefers the javac keying — the
   DynamicFeature's textual fields remain the fallback record of the raw
   site.
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
   typing, `JavacMemberCorrelation`, the comma-canonical dialects, the
   #26 harness, `DynamicCertaintyPolicy` (edges and framework facts keep
   their forcing — a DEFINITE source-bound reflection fact claims only
   the target SHAPE, not dispatch; invoke/newInstance sites stay
   UNRESOLVED), and proxy features. ONE justified exception to the
   index-schema freeze: `MemberInfo` — not `MemberTypes`, which stays shape-only — gains an additive
   `publicMember` flag (set from `Element.getModifiers()` in
   `ElementReader`, the single construction site; a hand-written
   accessor keeps every existing reader and test compiling unchanged) —
   required by the visibility gate above; without it, `getMethod`/
   `getConstructor` could promote non-public members the runtime APIs
   never return (a false DEFINITE, the primary bug class).

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
  with `demo.Present` in the source set → DEFINITE, EMITTED, callee keyed
  `demo.Present`, attributes carry `javacSymbol=demo.Present`,
  `sourceBound=true` (the `demo.Present#unknown` textual form is the
  G-series flag-off twin's assertion, not this cell's).
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
  with `public void run()` declared (simple spelling, same package; and a
  qualified `demo.Present.class` twin) → DEFINITE.
- **M7 (control): variable receiver.** `Class<?> c = Present.class;
  c.getMethod("run")` → UNRESOLVED unchanged (no dataflow).
- **M8 (control): getClass() receiver.** → UNRESOLVED unchanged.
- **M9 (RED): member not declared.** `getMethod("missing")` on a resolved
  source owner → POSSIBLE, diagnostic `member_not_declared`.
- **M10 (RED): constructor target.**
  `Class.forName("demo.Present").getDeclaredConstructor(String.class)` →
  DEFINITE, callee `demo.Present#<init>`, descriptor
  `(Ljava/lang/String;)V`.
- **M12 (RED): inherited member never mis-attributed.** Source owner
  `Child extends Base` (both in the source set), `Base` declares `run()`,
  `Child` does not redeclare it; site
  `Class.forName("demo.Child").getMethod("run")` → POSSIBLE, diagnostic
  `member_not_declared` — never attributed to `demo.Base#run` (the index
  holds declared members only; inherited lookup is a documented
  non-goal).
- **M13 (RED): nested-type class-literal parameters.** Member
  `x(Outer.Inner p)` selected by `x(Outer.Inner.class)` (qualified
  spelling) → DEFINITE with `java.lang.Outer`-style canonical vector and
  `$` binary keying; the SIMPLE spelling twin (`x(Inner.class)` where
  `Inner` is a sibling's nested type, out of scope per JLS 6.5.5.1) fails
  closed to no-evidence (site stays UNRESOLVED) — pinned as the
  documented fail-closed shape, mirroring the #60 interaction.
- **M14 (control): non-literal member name.**
  `getMethod(variableName)` → UNRESOLVED unchanged.
- **M15 (RED): implicit default constructor fails closed.**
  `Class.forName("demo.NoCtor").getDeclaredConstructor()` where
  `demo.NoCtor` declares NO constructor → POSSIBLE, diagnostic
  `member_not_declared` (the index holds declared members only; the
  synthesized default constructor is a documented coverage gap, safe
  direction).
- **M16 (RED): `$`-simple-name owner constructor.** Owner
  `demo.Cash$Flow` with an explicit `Cash$Flow(String)` constructor;
  site `getDeclaredConstructor(String.class)` chained on
  `Class.forName("demo.Cash$Flow")` → DEFINITE, callee
  `demo.Cash$Flow#<init>` (kind-filtered selection, no simple-name
  derivation — the `$` boundary cell).
- **V1 (RED): getMethod cannot promote non-public members.**
  `getMethod("run")` naming a private `run` on a resolved source owner →
  POSSIBLE, diagnostic `visibility_mismatch` (runtime `getMethod` throws
  NoSuchMethodException — never DEFINITE).
- **V2 (RED): getConstructor cannot promote non-public constructors.**
  `getConstructor(String.class)` naming a private constructor → POSSIBLE,
  diagnostic `visibility_mismatch`; the `getDeclaredConstructor` twin on
  the same constructor → DEFINITE (declared variants see every
  visibility).
- **M17 (RED): assignment-receiver shape.**
  `Method m = Present.class.getMethod("run");` (the canonical real-world
  spelling) → DEFINITE (the `=` pre-chain boundary).
- **M18 (control, GREEN at RED): recognized receiver naming a non-source
  type.** `String.class.getMethod("trim")` → UNRESOLVED unchanged
  (receiver recognized, index has no `java.lang.String` entry — the
  JDK-owner-member fail-closed path).
- **E5 (GREEN at RED): missing source root fail-close.** Context with
  one included root pointing at a nonexistent directory (index
  `missing_source_root`) and a present root carrying a source-bound
  forName literal → NO fact is promoted (the partial index cannot prove
  uniqueness; same stub argument as E2, doubles as the post-GREEN
  mutation check for the extended fail-close set).
- **M11 (RED): annotation/parameter evidence.** Source-bound member whose
  declaration carries annotations including a `$`/Unicode annotation name
  (`@demo.Märk$Odd`) → evidence `annotationNames` match javac canonical
  rendering; parameter shapes recorded (the issue's annotation-shape
  cell).
- **E1 (RED): error member.** Owner resolved, target member's parameter
  references a missing type (`errorType` member) → POSSIBLE, diagnostic
  `error_member`.
- **E2 (GREEN at RED): index integrity fail-close.** Context with a
  required absent classpath entry (index `missing_classpath`) → NO fact
  is promoted; every reflection fact byte-identical to flag-off (with
  the no-evidence stub the flag-on path is already byte-identical, so
  this cell is green at RED and doubles as the post-GREEN mutation
  check for the integrity gate).
- **E4 (RED): unrelated per-file javac errors do not block a clean
  target.** A second source file references `missing.Dep` (javac error
  row, that entry error-typed); `Class.forName("demo.Present")` with a
  clean unflagged `demo.Present` entry → still DEFINITE (per-entry error
  authority; the R3 harness divergence, pinned explicitly).
- **E3 (RED): duplicate-flagged owner.** Two files declaring the same
  binary name; forName literal naming it → POSSIBLE, diagnostic
  `duplicate_target` (never DEFINITE).
- **G-series (flag-off goldens, GREEN at RED):** every F/M fixture run
  flag-off → exact current output: textual callee (`demo.Present#unknown`
  / `unknown#handle`), UNRESOLVED, ABSTAINED, `basis`-only attributes.
  Count-form where per-site identity is not observable.
- Pairwise (AGENTS): target kind × flag state (F1 vs G), name shape ×
  index answer (F2/F3/F7/E3), receiver shape × literal (M1/M6/M7/M8),
  member × overload count (M2), member kind × name-literal presence
  (M10 vs M14), Unicode annotation × member resolution (M11), loadClass ×
  forName (F6 vs F1), inheritance × member lookup (M12), nesting ×
  parameter vector (M13), per-file error × clean target (E4), visibility
  × lookup API (V1/V2 vs M1's declared variant), constructor presence ×
  declaration (M10 vs M15), `$`-name × constructor selection (M16),
  pre-chain boundary × receiver shape (M17 vs M6), source-root presence ×
  promotion (E5).

## Boundary probes (pre-GREEN, scratch, not committed)

Empirical probes of the receiver/argument arithmetic per AGENTS: empty
receiver text, single-character receiver, receiver at start-of-file,
prefix/suffix overlap (a receiver whose spelling ends with the method
name), whitespace inside `X . class` and `forName ( "L" )`, parenthesized
receivers, `this.`-prefixed receivers, class-literal arrays
(`String[].class` vs `String [].class`), assignment/arrow pre-chain
boundaries (`var m = Present.class...`, `x -> Present.class...`,
`case X -> Present.class...`), and `#`/`$` boundaries in the
keyed target join. Plus the mask-alignment probe: the literal-masked
receiver range and the structural-source literal offsets must align
1:1 (the L recovery depends on it) — asserted against the compiled
`JavacReflectionTargets` with a masked-literal fixture before GREEN;
findings fold back into the implementation and (if a shape is reachable)
into a cell.

## TDD sequence

- **PLAN_25** (this file): committed first.
- **Plan review**: Engram-grounded adversarial subagent, CodeRabbit
  style; findings addressed; loop until no new addressable findings.
- **RED_25**: `JavacReflectionTargetMigrationTest` with the matrix above,
  plus a behavior-neutral stub (`JavacReflectionTargets` always returning
  no-evidence, `JavacTargetEvidence` record, `DynamicFeature` component
  defaulted) documented in the commit. Expected RED: F1–F3, F6, F7, M1–M6,
  M9–M13, M15, M16, V1, V2, M17, E1, E3, E4. Expected GREEN at RED: F4,
  F5, M7, M8, M14, M18, E2, E5, G-series. Run `make adapter-test` at RED to confirm the expected-red
  cells fail and the rest of the suite stays green; record the observed
  execution count in the commit message as the floor receipt (652
  expected on this main; the floor in the Makefile stays 534).
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
- The implicit default constructor is not indexed (declared members
  only): `getDeclaredConstructor()` on a class with no declared
  constructor yields `member_not_declared` POSSIBLE while the runtime
  succeeds via the synthesized constructor — fail-closed coverage gap,
  pinned by M15; indexing synthesized members is a follow-up candidate.
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

Confidence score: **0.88** (0.78 → 0.82 → 0.85 → 0.88 across review
rounds). Round 3 (fresh Engram-grounded adversarial subagent): 4
addressable findings + 1 NIT, all incorporated — `missing_source_root`
added to the whole-path fail-close set (HIGH: a partial index over
remaining roots fabricates uniqueness for types declared in both a
present and a missing root; E5 cell), the pre-chain boundary set
enumerated with the assignment-receiver shape probed and pinned (M17),
`publicMember` pinned to `MemberInfo` only (MemberTypes stays
shape-only), and the extraction-anchor wording corrected (receiver-call
and this-receiver sites anchor at the receiver and fail closed). The
round-3 review also verified the publicMember feasibility (6→7
components, single construction site, zero direct test construction),
the declared-variant visibility semantics (no access check at lookup),
and that the suffix anchor defeats the adversarial receiver shapes.
