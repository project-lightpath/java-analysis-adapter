# RESOLUTION-60 — Index shape fidelity: binary names in both JVM descriptor dialects

Issue: project-lightpath/java-analysis-adapter#60 (parent #20).
Branch: `issue-60-index-shape-fidelity`.
Commits: PLAN_60 `2e681ad`, plan review `a9ccf3b`, RED_60 `06f3f23`,
GREEN_60 `48a036b`, REVIEW_60 `a643240`, REVIEW_60_2 `699b8b7`,
EVIDENCE_60 (this commit).

## What changed

Both JVM-descriptor dialects render binary names — the issue's acceptance
("both the index-rendered and the textual jvmDescriptor switch to binary
names in the SAME change so the dialects never diverge"):

- **`JavacSourceSymbolIndex.MemberInfo`** folds its type shapes into a
  nested `MemberTypes` record carrying canonical AND binary parameter and
  return spellings (`Elements.getBinaryName`; erasure first; arrays append
  `[]`; ERROR renders blank; constructors `void`). Hand-written delegating
  accessors keep every existing caller and test compiling unchanged. The
  type-spelling renderers moved from `ElementReader` into a
  `MemberTypeShapes` helper — PMD GodClass forced the placement; behavior
  identical, documented in the GREEN commit per AGENTS.
- **`JavacShapeDescriptors.jvmDescriptorFromMember`** renders from the
  binary shapes: nested types render `Ldemo/Outer$Inner;`, never
  `Ldemo/Outer/Inner;`.
- **`JvmBinaryNames`** (new): the canonical→binary derivation for the
  textual dialect — an EVIDENCE ladder (capitalization is never
  evidence): (a) flag-on index lookup (`Elements.getBinaryName`,
  DEFINITE; an ambiguous lookup abstains), (b) file-set declarations
  (exact top-level name → binary == canonical; extending canonical →
  exhaustive nested declaration chain walk; multiple distinct chains
  abstain), (c) type-qualified spellings whose first segment resolves in
  scope (the source asserts the qualifier is a type), (d) otherwise the
  JLS-default package reading (binary == canonical) with abstentions for
  `$` in a pre-last segment and empty segments. See the CodeRabbit round
  below for why the original capitalization heuristic was replaced.
- **`BoundedFrameworkModelCandidateMetadata.jvmDescriptor`** wires the
  seam in BOTH flag states (flag-off, and flag-on textual fallback for
  non-correlated members).

Untouched, per the plan's binding scope: the comma-canonical dialects
(`JavacAnnotationAccess.methodDescriptor`, `repositoryMethodSignature`),
canonical-name comparisons (`parameterMatchesType`,
`correlatedFirstParameterIs`, producer bean types, bijection vectors),
error-type abstentions, `memberHasErrorType` logic, the compilation setup,
and diagnostics (zero new diagnostics, zero scan-global blast radius).

## Facet 1 disposition (the issue's premise does not hold at main)

Probe-verified at `f6756d1` (scratch `IndexProbe`, not committed; PLAN-60
Preflight): the index already compiles the FULL selected source set in ONE
`JavacTask` — a producer returning a top-level type declared in another
file has `errorType=false` and correlates flag-on; even a qualified nested
cross-file return (`Outer.Inner`) resolves. RESOLUTION-23's "the index
compiles per-file" behavior fact was a misdiagnosis: the #23 fixture's
unqualified `Inner` fails JLS 6.5.5.1 scoping (a sibling class's nested
type is not in scope by package membership — javac: `cannot find symbol:
class Inner`), which no sourcepath or joint compilation can resolve. The
issue's expected flip of `producerReturnTypeAbstainsOnUnprovableIndex
Shapes` is therefore impossible for that fixture in any compilation
shape; the existing abstention is the correct behavior and stays. What
#60 adds for facet 1 is positive characterization pins:

- `crossFileTopLevelReturnsCorrelateFlagOn` — cross-file top-level return
  correlates, canonical bean type published.
- `crossFileQualifiedNestedReturnsCorrelateFlagOn` — the qualified nested
  differential (flag-on `demo.Outer.Inner`, flag-off empty) that the #23
  residual (mis)attributed to per-file isolation.
- The stale per-file-isolation comments corrected (comment-only,
  plan-sanctioned) in `JavacTypeShapeMigrationTest` and
  `JavacSourceSymbolIndex.memberHasErrorType`.

## Acceptance evidence (issue #60)

- Cross-file return correlates flag-on with annotation mirrors available:
  pinned by the C1/C2 cells (the mirror seam's only errorType blockers
  are fed by `memberHasErrorType`, which does not fire for resolved
  cross-file returns — implementation-review round 1 re-verified).
- Nested-type JVM descriptors render `Lpkg/Outer$Inner;` in BOTH paths:
  N1/N2 (index dialect), N3/N4 (textual dialect, both resolution routes),
  N5 (end-to-end producer parity across flag states).
- Flag-on/flag-off descriptor parity holds for every shape the #23
  acceptance cells pin: the full suite is green (the #23 pinned
  descriptors are top-level — e.g. `(Ljava/util/List;[Ljava/lang/String;)V`
  — unchanged); the nested parity cell is N5.
- Error-type members still abstain when the source set genuinely cannot
  resolve: `genuinelyUnresolvableReturnsStillAbstain` (member correlation
  empty for `missing.Thing`) plus the inherited #23 abstention cells.
- Gates: `make adapter-test` **643 executions** (RED baseline 628, floor
  534), zero failures; `make java-lint` PASS (0 new findings; the
  GodClass finding forced the `MemberTypeShapes` extraction, resolved);
  `make evidence-validate` pass.

## Mutation checks (executed, all red under exactly their own mutation)

1. Index renderer reverted to canonical shapes →
   `indexRendersNestedJvmDescriptorsWithBinaryNames` and
   `indexRendersNestedArrayAndVarargsWithBinaryNames` fail
   (`Ldemo/Probe/Inner;` instead of `Ldemo/Probe$Inner;`).
2. `JvmBinaryNames` wiring removed from the textual `jvmDescriptor` →
   N3, N4, N5 (flag-off arm), B2, B3 fail. Executing this check exposed a
   defect in the parity cell itself (its flag-off arm computed the
   descriptor after setting the flag, so it ran the flag-on path); fixed
   in REVIEW_60 (`a643240`) and re-verified red under the mutation.
3. `MemberTypeShapes.binaryType` reverted to the canonical rendering →
   N1, N2, and N5's flag-on arm fail.
4. Boundary-at-0 abstention removed → `uppercasePackageImportsAbstain
   InsteadOfFabricating` fails (fabricates `LCom/example/Foo;`).
5. File-set top-level rule (b) deleted →
   `fileSetTopLevelTypesInUppercasePackagesRender` fails (descriptor
   absent) — REVIEW_60_2's pin.

## Review record

- **Plan review** (Engram-grounded adversarial subagent): round 1, 3
  addressable findings (1 MEDIUM, 2 LOW) — the boundary-at-0 coverage
  loss was understated (the uppercase-package import shape renders
  correctly today and abstains after; now pinned by B3 instead of claimed
  closed), the stale production comment added to the sanctioned edits,
  B3's default-package arm corrected to the reachable shape; round 2,
  0 addressable. Confidence 0.83 → 0.85.
- **Implementation review** (Engram-grounded adversarial subagent): round
  1, 3 addressable findings — the lowercase-top-level-nesting ambiguity
  (`demo.lower.Inner`, true binary `demo.lower$Inner`) added to the
  residual register and pinned; the file-set top-level rule (b)
  mutation-pinned (was unpinnable through `demo.Cash$Flow` because rule
  c's `$`-passthrough returns the same string); the
  `$`-named-segment-with-further-nesting shape registered; round 2, 1 LOW
  wording fix ("unreachable" → "reachable only through
  convention-hostile fully-qualified spellings"), applied. No
  false-DEFINITE found on any convention-conforming shape.

## Behavior facts and residuals

- Flag-off default output changes ONLY for nested-type JVM descriptors
  whose nesting is PROVEN (file-set declarations, type-qualified
  spellings) — they render `$`; everything else keeps the pre-#60
  rendering (JLS-default package reading). The full pre-existing suite
  is green.
- Fully-qualified nested spellings (`java.util.Map.Entry`) and simple
  spellings of imported nested types keep the package reading: the
  spelling carries no boundary evidence (textually indistinguishable
  from a top-level type in a package named like the outer type). The
  flag-on index resolves the nested reading for correlated members;
  non-correlated members keep the package reading — documented residual.
- `$` in a segment before the last (`pkg.Outer$X.Inner`) abstains
  (ambiguous between a `$`-named identifier with further nesting and a
  package segment); a final-segment `$` (`com.lib.Cash$Flow`) passes
  through (binary == canonical).
- Ambiguous index lookups (two unflagged entries sharing a canonical,
  e.g. nested `p.Outer.Inner` next to a top-level type in a package
  literally named `p.Outer`) abstain.
- Default-package nestings keep the package reading (degenerate shape).
- Unqualified sibling nested spellings (`Inner`) still fail closed to
  textual/abstention — JLS 6.5.5.1 scoping; owner-qualified fallback
  spellings remain a follow-up under #20.
- Source-side descriptors are compared against bytecode-derived
  `calleeSignature` (`JavaAnalysisAdapter.targetDescriptorsMatch`), so
  the binary dialect can only create correct matches going forward.
- `BoundedFrameworkModelTypeParsing` / injection contract matching remain
  textual (#61); index diagnostic visibility is #62; the flag default
  stays off (umbrella #20's final step).

## CodeRabbit round (PR #63)

CR-1 (full review): 3 findings (1 Major, 2 Minor), all fixed red-first
in RED_60/GREEN_60 (`381c4f9`/`470b90e`):

1. Minor — the ambiguous index lookup reached `Stream.reduce` with a
   null-returning accumulator and threw NPE instead of abstaining.
   Reproduced red (`ambiguousIndexLookupsAbstainInsteadOfThrowing`,
   NullPointer at the pre-fix head); fixed with a single size-checked
   lookup.
2. Minor — `pkg.Outer$X.Inner` passed through and named the wrong class.
   Pinned red (`dollarSegmentsBeforeTheLastSegmentAbstain`); fixed with
   the pre-last-segment `$` abstention.
3. Major — capitalization was treated as proof of a type boundary,
   fabricating `com.Acme$Widget` for a top-level type in an uppercase
   non-leading package (JLS 13.1: binary == canonical). Fixed by
   replacing the heuristic with the evidence ladder (see PLAN-60's
   CodeRabbit round section): `$` insertion requires index, declaration,
   or type-qualified-spelling evidence; everything else keeps the
   JLS-default package reading. The earlier uppercase-package-import
   "pinned coverage regression" is thereby eliminated — the shape
   renders correctly again.

CR-2 (re-review of `716beb3`): 3 Minor findings, all fixed red-first in
RED_60/GREEN_60 (`06e56ba`/GREEN below):

1. Containment-only chain matching accepted a grandchild declaration
   (Helper.Inner inside Child) for a canonical naming an INHERITED
   member type — fabricating `demo.Child$Inner`, a binary that does not
   exist. Fixed with `isDirectMember` (no intervening type declaration)
   plus a tri-state file-set rule: once a top-level declaration prefix
   matches, an unproven tail ABSTAINS (inherited or deeper nesting)
   instead of falling through to a weaker rule. Pinned red by
   `inheritedMemberTypesAbstainThroughDirectOwnership`. Residual: a
   LOCAL class directly inside a method body has no intervening TYPE
   declaration, so it still reads as a direct member — local classes
   are not referable by qualified spellings, so the canonical cannot
   denote one; registered.
2. The spelling rule rejected multi-dot type-qualified spellings
   (`Outer.Middle.Inner`) — the second-dot check removed; the tail join
   already replaces every dot with `$`. Pinned red by the direct
   `java.a.B$C$D` probe (the file-set multi-segment cell passes through
   the declaration rule and pins it end to end).
3. The parity cell's flag-on arm could run the TEXTUAL renderer
   (package-private `Inner` in another package made the index
   correlation abstain — probe-confirmed). `Inner` is public and the
   cell asserts the correlated member exists before comparing
   descriptors.

CR-3 (forced re-review after the rate-limit window): 1 Minor finding,
fixed red-first in `cd94ff7`/GREEN:

1. When the index has entries for a canonical but ALL are
   duplicate-flagged (the D3 collision shape — nested `p.Outer.Inner`
   shares binary `p.Outer$Inner` with a top-level `p.Outer$Inner`),
   the eligible filter emptied the list and the code fell through to the
   file-set declaration chain, returning exactly the binary name the
   index rejected. The index lookup now retains the full match list and
   abstains whenever any match exists without exactly one eligible
   entry. Pinned red by
   `duplicateFlaggedIndexMatchesAbstainInsteadOfFallingThrough`
   (`Optional[p.Outer$Inner]` at the pre-fix head).

## Build fix (post-CR-3)
The CR-3 change pushed `JvmBinaryNames` over PMD's GodClass threshold
on CI (WMC=47, JDK 17) while the local JDK 27 gate stayed green on the
identical commit — an environment divergence, empirically reproduced on
helios08 under Temurin 17 (pre-split: GodClass fires; post-split: zero
findings; local JDK 27: no finding either way, fresh reports on both).
The file-set declaration-chain logic moved into a `JvmDeclarationChains`
helper (PMD-forced placement, behavior identical). The divergence itself
— the local lint gate cannot reproduce CI's JDK 17 measurement — is
tracked as **#64**.

CR-4 (incremental review of the post-lint-fix head): 1 Minor finding,
fixed red-first in `fba2400`/GREEN:

1. A canonical whose index lookup mixes a FLAGGED entry (the nested
   `p.Outer.Inner`, binary `p.Outer$Inner` colliding with a top-level
   `p.Outer$Inner`) and an UNFLAGGED entry (a top-level `Inner` in the
   package literally named `p.Outer`, binary `p.Outer.Inner`) left
   exactly one eligible entry after the filter and returned its binary —
   but the flagged sibling is a competing declaration for the same
   canonical, so the binary was not proven. A single eligible entry is
   now accepted only when it is the only indexed match. Pinned red by
   `mixedFlaggedAndUnflaggedIndexMatchesAbstain`
   (`Optional[p.Outer.Inner]` at the pre-fix head).

CR-5 (comment 4688796145, thread 4688692546): 2 ownership gaps, both
fixed red-first in `3e02f76`/GREEN:

1. Local class: a LOCAL class inside a method body has no intervening
   TYPE declaration, so containment-only `isDirectMember` accepted it
   for a canonical naming an INHERITED member — fabricating
   `demo.Child$Inner` (the local class's real binary is
   `demo.Child$1Inner`). `isDirectMember` now also rejects any candidate
   inside a method or constructor body. Pinned red by
   `localClassesInMethodBodiesAreNotDirectMembers`.
2. External qualifier: a type-qualified spelling whose qualifier is
   outside the file set (ext.Child, java.util.Map) asserts a member
   relation whose declared-vs-inherited ownership is textually
   unprovable — the readings carry different binary owners
   (`ext.Child$Inner` vs `ext.Base$Inner`). The type-qualified rule now
   ABSTAINS (never appends the tail, never falls through to the package
   reading, which would contradict the spelling's own assertion). Pinned
   red by `externalQualifierMembersAbstain`,
   `importQualifiedExternalMembersAbstain`, and the rewritten
   multi-segment direct probe. This SUPERSEDES the CR-2 thread's
   external-qualifier `$` rendering (java.a.B$C$D); its file-set
   multi-segment arm survives as the evidence-backed case, and the
   flag-on index still resolves external members definitively for
   correlated members.

Note on the related CR-1 follow-up (comment 4688258782, resolved
thread): its demand to remove the package-reading fallback entirely was
probed empirically and REJECTED — with the fallback fully abstaining,
three pinned adapter tests fail (map-producer DEFINITE→POSSIBLE, lost
provider links) because simple-spelling JDK descriptors
(`()Ljava/util/Map;`) are pinned flag-off behavior; removing them breaks
the issue's flag-on/flag-off parity acceptance. The package reading
stays for spellings that assert no member relation (simple and
fully-qualified spellings); type-qualified spellings — which DO assert
one — abstain (CR-5 above).
