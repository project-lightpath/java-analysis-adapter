# RESOLUTION-25 — javac shapes for source-bound reflection targets

Issue: project-lightpath/java-analysis-adapter#25 (parent #20, the last
open sub-issue).
Branch: `issue-25-javac-reflection-shapes`.
Commits: PLAN_25 `0fe4fb0` (+ review rounds `ad6372e`, `f3fd7b1`,
`b4c45c3`, `60ee680`, amendment `cc51abf`), RED_25 `265d73f`,
GREEN_25 `76f9bed` (+ JDK 17 lint fix `31fa372`),
REVIEW_25_1 `09f7b42`, REVIEW_25_2 (CodeRabbit CR-1),
EVIDENCE_25 `51f2176` (+ this amendment).

## What changed

Source-bound reflection targets use javac shapes behind
`flpath.java.symbolindex` (default **off** — default output byte-identical,
pinned by the flag-off golden and the full pre-existing suite):

- **`JavacReflectionTargets`** (new, with `JavacReflectionReceivers` and
  `JavacReflectionArguments` helpers): resolves reflection sites against
  the index.
  - **Class targets** (`Class.forName` / `ClassLoader.loadClass` string
    literals): a unique unflagged non-error index entry whose binary name
    equals the literal → **DEFINITE**, keyed by the binary name
    (`demo.Outer$Inner`, the caller-identity `$` convention), with the
    type's annotation canonical names recorded. A canonical-spelling-only
    hit (`demo.Outer.Inner`) → POSSIBLE `binary_name_mismatch` (forName
    loads binary names). Duplicate-flagged / ambiguous / error entries →
    POSSIBLE `duplicate_target` / `ambiguous_target` / `error_target`.
    Names absent from the index (runtime strings), non-literal arguments,
    and index integrity failures keep the pre-existing UNRESOLVED
    abstention byte-identically.
  - **Member targets** (`getMethod` family): the site's receiver is parsed
    on the offset-preserving literal mask (suffix-anchored class-literal
    chains and static `Class.forName("...")` chains; accepted pre-chain
    boundaries: statement keywords and `= ( ) , ; { } & | ? : -> ! + - *
    / < >`); the owner is index-confirmed before anything is published;
    the overload is selected by an EQUALITY match of the class-literal
    argument vector (primitives direct, java.lang simple names and
    import-resolved spellings normalized, dialect-unified through the
    index) against `parameterTypes()`; the method path filters
    `kind == "method"` and the constructor path selects kind-filtered
    constructor members keyed `#<init>` (no simple-name derivation — the
    `demo.Cash$Flow` `$` boundary). `getMethod`/`getConstructor`
    (public-only runtime APIs) additionally require the new
    `MemberInfo.publicMember` flag → POSSIBLE `visibility_mismatch`
    otherwise; the declared variants see every visibility. Inherited
    members (`getMethod` sees them at runtime) yield
    `member_not_declared` POSSIBLE — never attributed to the superclass.
  - **Whole-path fail-close** on index integrity rows:
    `index_build_error`, `javac_unavailable`, `missing_source`,
    `missing_classpath`, `missing_source_root`, `javac_global_error` — a
    partial or unbuildable index must not prove uniqueness.
- **`JavacTargetEvidence`** (new record): the per-site javac symbol
  evidence (keyed target, kind, JVM descriptor, canonical parameter
  shapes, annotation names, diagnostic code) and its own fact consumption
  (certainty / callee / limitation / evidence attributes).
- **`ProviderFactNormalizer.dynamicFact`** consumes the evidence:
  DEFINITE (shape proven, not dispatch), POSSIBLE with a per-fact
  `diagnostic` attribute + limitation, or the byte-identical UNRESOLVED
  abstention. **Zero scan-global diagnostics**:
  `ServiceLoaderDescriptorScanner`, `scan.diagnostics()` and `isUnique`
  are untouched — routing the diagnostic into `scan.diagnostics()` would
  block DEFINITE promotion for the WHOLE scan (one
  `Class.forName("no.such.Type")` would downgrade every ServiceLoader
  DEFINITE); the per-fact evidence attribute is the per-member channel
  #62 tracks.
- **Index change (the one justified schema exception)**: `MemberInfo`
  gains an additive `publicMember` component (`Element.getModifiers()` at
  the single construction site in `ElementReader`; every existing reader
  and test compiles unchanged) — required by the visibility gate.

Untouched, per the plan's binding scope: the site-classification regexes
and receiver typing (#23 already javac-backed `parameterMatchesType`),
`JavacMemberCorrelation`, the descriptor dialects (#60), the #26 harness,
`DynamicCertaintyPolicy` (edges and framework facts keep their open-world
forcing; `attachExactProviderIdentities` excludes reflection providers
from `derivedFrom` linking — verified), proxy features, and
field/local receiver re-typing (not representable in the index;
enumerated in Residuals).

## Acceptance evidence (issue #25)

- Source-defined reflection targets use javac shapes: F1/F2/F6/F7
  (classes, binary keying, `$`/Unicode names), M1–M6/M10/M13/M15–M17/M19/
  M21 (members, constructors, varargs/generic/array shapes, `$`-name
  owner, pre-chain boundaries, interface implicit public; the implicit
  default constructor IS indexed — probe-verified, javac synthesizes it).
- Runtime strings are not promoted to DEFINITE: F4 (runtime-only
  literal), F5 (non-literal), M7/M8 (variable and `getClass()`
  receivers), M14 (non-literal member name), M18 (JDK-owner class
  literal), E2/E5 (index integrity fail-close) — all UNRESOLVED,
  byte-identical to flag-off.
- Annotation/parameter shapes match javac: M11 (`$`/Unicode annotation
  canonical names recorded), M2 (per-overload descriptors), M3–M5
  (varargs/generic-erasure/array descriptors).
- Unresolved/error types produce POSSIBLE plus a diagnostic: F3
  (`binary_name_mismatch`), M9/M12/M15-class-family (`member_not_declared`,
  inherited members never mis-attributed), V1/V2 (`visibility_mismatch`,
  with the declared-variant DEFINITE twin), E1 (`error_member`),
  E3 (`duplicate_target`), V3/V4 (field/enum-constant namesakes never
  method targets).
- Default dynamic output unchanged with the flag off: the flag-off
  golden plus the full pre-existing suite (flag-off by default).
- Gates: `make adapter-test` **689 executions** (RED baseline 652 on this
  main; Makefile floor 534), zero failures; `make java-lint` PASS with 0
  new findings on BOTH the local JDK 27 and helios08 Temurin 17 (the #64
  environment-divergence discipline — JDK 17's PMD did flag the private
  `annotationNames` overload as unused where JDK 27 did not; fixed by
  distinct method names in `31fa372`); `make evidence-validate` pass.
  Note: during REVIEW_25_1 the local `make` shim was blocked by an
  unaccepted Xcode license; the gates were re-run with the Makefile's
  exact underlying commands (bootstrap maven, parallelism 8, 1536 MB per
  slot, `SurefireTestCounter`, the lint plugin chain, `validate_spike.py`)
  and re-verified through `make` on helios08.

## Mutation checks (executed, all red under exactly their own mutation)

1. The RED_25 stub (no evidence anywhere) → all 25 promotion/diagnostic
   cells fail; the 8 runtime-string controls stay green — the promotion,
   the diagnostics, and the never-promote property are each
   mutation-sensitive.
2. Integrity fail-close removed (`INTEGRITY_CODES` emptied) →
   `missingClasspathBlocksAllPromotion` and
   `missingSourceRootBlocksAllPromotion` both fail (the partial index
   promotes `demo.Present` to DEFINITE); restoring the set makes them
   pass.
3. Kind filter removed (review round 1, red-first) →
   `getMethodCannotPromoteFieldNamesakes` and
   `getMethodCannotPromoteEnumConstantNamesakes` fail (fabricated
   DEFINITE `demo.Present#run` / `demo.Color#RED`).
4. Leading-whitespace trim restored (review round 1, red-first) →
   `deeplyIndentedForNameAnchorReadsTheRightLiteral` fails (fabricated
   `demo.Evil#m` from the pre-chain call's quoted first argument).
5. Per-overload vector matching, binary keying, and the visibility gate
   are pinned by M2, F2/F7, and V1/V2/M21 respectively (each asserts the
   exact member/keying/grade its mutation would change).

## Review record

- **Plan review** (Engram-grounded adversarial subagents): 5 rounds,
  9 + 5 + 4 + 1 + 0 addressable findings, all incorporated. Material
  design changes: the constructor branch and its null-name rule, the
  mask-alignment specification, the visibility gate (`publicMember`), the
  `missing_source_root`/`javac_global_error` fail-close rows, the
  pre-chain boundary enumeration (`)` included), the E2/E5 red/green
  reclassification, and the argument-normalization amendment (arguments
  are normalized for comparison, never index-confirmed). Confidence
  0.78 → 0.89.
- **Implementation review** (fresh Engram-grounded adversarial
  subagents): round 1, 6 addressable findings (2 HIGH false-DEFINITE
  windows — the missing kind filter and the receiverRange leading-trim
  anchor shift; 1 MEDIUM `javac_global_error`; 2 LOW; 1 NIT), all
  addressed red-first in REVIEW_25_1; round 2 verified every
  incorporation (including the mutation sensitivity of the four new
  cells) and found **no new addressable findings** — clean.

## CodeRabbit round (PR #65)

CR-1 (full review): 4 findings (3 Major, 1 Minor), all verified
empirically — the inner-class constructor finding reproduced the exact
inverted behavior (zero-arg DEFINITE, enclosing-type mismatch,
probe-verified) — and all addressed red-first in REVIEW_25_2 with
mutation-sensitive pins (M22, M23, M24 both arms, M25 control, A1; 42
cells total):
1. Major (comment 4697679845) — a forName member-receiver literal naming
   the CANONICAL spelling (`Class.forName("demo.Outer.Inner")`) promoted
   to DEFINITE although the runtime API loads binary names; the member
   path lacked the class ladder's binary-name validation. Fixed:
   `LookupSite` retains the receiver provenance and forName receivers
   validate binary == literal → POSSIBLE `binary_name_mismatch`; class
   literals keep the canonical form. Pinned red by M23.
2. Major (comment 4697679858) — inner-class constructors compared
   javac's SOURCE parameter vector: a zero-arg lookup on a non-static
   nested owner fabricated DEFINITE while the correct
   `getDeclaredConstructor(Outer.class)` site mismatched. Fixed: the
   member's parameter vector and JVM descriptor carry the synthetic
   enclosing-instance parameter for non-static MEMBER nested owners (the
   new additive `TypeShape.staticType` distinguishes them;
   LOCAL/ANONYMOUS owners abstain with `inner_class_constructor` — the
   enclosing capture is not derivable from the nesting kind). Pinned red
   by M24 (both arms) with the M25 static-nested control.
3. Major (comment 4697679790) — a parameter class literal naming a
   duplicate-flagged type kept its spelling and could complete a
   DEFINITE match (the owner ladder already abstained on duplicates).
   Fixed: `canonicalThroughIndex` returns null on duplicate entries —
   the vector is unresolvable and the site stays UNRESOLVED. Pinned red
   by A1.
4. Minor (comment 4697679832) — partially qualified class-literal owners
   (`Outer.Inner.class`) failed closed to NONE. Fixed: dotted
   class-literal spellings resolve through the source context
   (`resolveReferenceType`) with the direct form as fallback; the index
   still confirms the candidate before anything is published. Pinned red
   by M22.

CR-2 (incremental review of 3e2acf6): 2 inline findings (both Major)
plus 1 outside-diff Minor, all verified red-first and addressed in
REVIEW_25_3:
1. Major (comment 4698434878) — enum constructors carry the synthetic
   `(String name, int ordinal)` parameters in the reflection domain; a
   zero-arg lookup on an enum promoted to DEFINITE `()V`. Fixed:
   `syntheticPrefixes` prepends `java.lang.String, int` for enum owners
   in both vectors (enums are never inner, so the two prefix kinds
   never combine). Pinned red by M26 (both arms: zero-arg →
   `shape_mismatch`, `(String.class, int.class)` → DEFINITE
   `(Ljava/lang/String;I)V`).
2. Major (comment 4698434894) — the enclosing BINARY was derived from
   the last `$`, which a `$`-named simple name corrupts
   (`demo.Outer$Inner$Part`'s enclosing is `demo.Outer`, not
   `demo.Outer$Inner`): the canonical match succeeded and a WRONG
   descriptor was published as proven. Fixed: both enclosing names are
   derived by removing the owner's exact simple-name suffix (from the
   canonical spelling), with a defensive abstention when the binary
   does not end with the simple name. Pinned red by M27
   (`(Ldemo/Outer;)V`, was `(Ldemo/Outer$Inner;)V`).
3. Minor (outside-diff, review 6062018094) — an index-proven
   unnamed-package binary name (JLS 13.1: binary == simple name) was
   rejected by the descriptor renderer's no-dot guard, so a DEFINITE
   fact omitted `targetDescriptor`. Fixed: `binaryJvmDescriptor`'s
   default arm renders dotless spellings (`LToken;`) — every input to
   that arm is index-proven; the textual dialect's dot-abstentions
   (`JvmBinaryNames`) are untouched. Pinned red by M28 (`()LToken;`,
   was absent). The #60 index-dialect consumers share the helper; the
   full suite confirms no pinned regression.
   Two further CR-2 comments (4698464848, 4698474829) are CodeRabbit's
   own analysis chains — the second acknowledges the reviewer's
   UNRESOLVED-vs-POSSIBLE correction — not findings.

Index schema note: `TypeShape` gained an additive `staticType` component
(the second justified exception, same construction discipline as
`publicMember`) — required to keep static nested constructors eligible
while non-static ones compare in the reflection domain.

## Behavior facts discovered at GREEN (probe-verified)

- A non-static MEMBER nested constructor's source parameter vector omits
  the synthetic enclosing-instance parameter (javac
  `ExecutableElement.getParameters()`); reflection sees it — the CR-1
  probe showed the inverted behavior before the fix.
- javac synthesizes the implicit default constructor into
  `getEnclosedElements` (kind `constructor`, empty `parameterTypes`) —
  the plan's "not indexed, fails closed" premise was wrong; M15 pins the
  DEFINITE resolution.
- Interface members carry the implicit `public` in
  `Element.getModifiers` — the visibility gate does not over-abstain
  (M21).
- `splitTopLevelArguments("")` yields one blank element — blank
  arguments are filtered before vector matching.
- The receiver range retains the chain's connecting dot before the
  method name (stripped) and must keep its LEADING whitespace (an early
  trim shifted the forName literal anchor — review round 1, finding 2).
- An escaped-quote forName literal (`"demo.C\"x,)"`) poisons the
  TEXTUAL extractor file-wide (pre-existing; the ladder fails closed to
  NONE for that file) — probe finding, not a regression.

## Residuals

- Receiver-variable dataflow (`Class<?> c = ...; c.getMethod(...)`) is
  out of scope: variable-receiver member targets stay UNRESOLVED (M7).
- `Class.forName("a" + "b")` folded concatenations and non-literal names
  keep the runtime path (the textual `firstQuotedArgument` requires the
  literal to be the entire first argument).
- `getMethod` naming an inherited member yields `member_not_declared`
  POSSIBLE even when the superclass is in the source set (the index holds
  declared members; inherited-member walking is the FLPATH-5124-class
  follow-up).
- A 3-argument `Class.forName(name, init, loader)` receiver is not
  recognized by the chained-receiver shape (fails closed to NONE).
- Static-imported `forName(...)` receivers are not revalidated by the
  ladder (only the `Class`/`java.lang.Class` static spellings are).
- The `diagnostic` acceptance is carried per fact (evidence attribute +
  limitation) — the scan-global channel is deliberately untouched (blast
  radius stated above); #62 tracks the proper per-member evidence design.
- Field reflection targets (`getField`/`getDeclaredField`) extract no
  dynamic sites today; adding them is new-surface work, not shape work —
  follow-up candidate under #20.
- The flag default stays off; flipping it is umbrella #20's final step.

## Resolved pre-existing baseline drift (informational)

`make java-lint` reports `resolved baseline findings: 1` on this branch
AND on untouched main (reproduced via `git stash` before any change) —
the #64 JDK 27-vs-17 environment divergence, not this change.
