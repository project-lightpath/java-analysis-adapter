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
  textual dialect, most definite rule first: (a) flag-on index lookup
  (`Elements.getBinaryName`, DEFINITE, duplicate-flagged collisions fall
  through), (b) file-set top-level `typeName()` match (binary ==
  canonical; preserves `$`-in-simple-name top-level types), (c) segment
  boundary rule — the first uppercase-initial segment starts the
  `$`-joined type chain; `$`-containing canonicals pass through; boundary
  at segment 0 (default package vs uppercase package, textually
  indistinguishable) and empty segments abstain.
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
  (slashes → `$`, or absent when the boundary rule abstains) — exactly the
  issue's acceptance. Everything else is byte-identical; the full
  pre-existing suite is green.
- One documented, pinned coverage regression: the UPPERCASE-PACKAGE
  IMPORT shape (`import Com.example.Foo;` + spelling `Foo`) renders
  `LCom/example/Foo;` today and abstains after (textually
  indistinguishable from a default-package nesting; fail-closed
  direction per AGENTS).
- Textual-dialect residual ambiguities (registered in PLAN-60): a
  lowercase top-level class with nested types (`demo.lower.Inner`) keeps
  the package reading; a `$`-named segment with further nesting
  (`pkg.Outer$X.Inner`) passes through unchanged — both
  convention-hostile, both pinned or registered.
- Default-package nestings abstain in both dialects (previously rendered
  with slashes; never matched a real consumer).
- Unqualified sibling nested spellings (`Inner`) still fail closed to
  textual/abstention — JLS 6.5.5.1 scoping; owner-qualified fallback
  spellings remain a follow-up under #20.
- Source-side descriptors are compared against bytecode-derived
  `calleeSignature` (`JavaAnalysisAdapter.targetDescriptorsMatch`), so
  the binary dialect can only create correct matches going forward.
- `BoundedFrameworkModelTypeParsing` / injection contract matching remain
  textual (#61); index diagnostic visibility is #62; the flag default
  stays off (umbrella #20's final step).
