# PLAN-60 — Index shape fidelity: binary names in JVM descriptors, both dialects

Issue: project-lightpath/java-analysis-adapter#60 (parent #20).
Follow-up from #23 (PR #59), CodeRabbit thread
[#discussion_r4675771826](https://github.com/project-lightpath/java-analysis-adapter/pull/59#discussion_r4675771826).
Branch: `issue-60-index-shape-fidelity` from `origin/main` @ `f6756d1`.

## Preflight (probe-verified on this main, 2026-11-24, JDK 27, --release 17)

Scratch probe (same-package `IndexProbe` over `target/classes`, temp sources,
not committed) built a two-file context and inspected
`JavacSourceSymbolIndex.members("demo.Producer", ...)`:

- `public Bean topLevel()` with `demo.Bean` declared in ANOTHER selected file:
  `returnType=demo.Bean`, `errorType=false`. **Cross-file references already
  resolve.**
- `public Outer.Inner qualifiedNested()` with `Outer` declared in another
  selected file: `returnType=demo.Outer.Inner`, `errorType=false`.
- `public Inner unqualifiedNested()`: `errorType=true`, javac diagnostic
  `cannot find symbol: class Inner, location: class demo.Producer`. This is
  JLS 6.5.5.1 scoping — a sibling class's nested type is not in scope by
  package membership — NOT per-file isolation. No sourcepath or joint
  compilation can resolve that spelling.
- `type("demo.Outer.Inner").binaryName` is `demo.Outer$Inner` (the index
  already carries binary names at TYPE level).

**Facet 1 of the issue (per-file isolation limiting flag-on coverage) does
not hold at `f6756d1`.** PLAN-21 designed the index to submit EVERY selected
unit to ONE `JavacTask` (empty `-sourcepath` only prevents implicit
compilation of files OUTSIDE the recorded roots); RESOLUTION-23's "the index
compiles per-file" behavior fact was a misdiagnosis of the #23 fixture's
unqualified `Inner` (a scoping error, probe-confirmed above). The issue's
expected flip of `producerReturnTypeAbstainsOnUnprovableIndexShapes` is
therefore impossible for that fixture in ANY compilation shape: the correct
behavior for an out-of-scope spelling is the existing abstention, which stays.
What #60 adds for facet 1 is positive characterization pins (cross-file
top-level and qualified-nested returns correlate flag-on) — the production
code already satisfies the acceptance; the pins make it non-vacuous and
regression-guarded.

**Facet 2 is real and is the production change.** Both JVM-descriptor
dialects render canonical dots as slashes:

- flag-on index path: `JavacShapeDescriptors.canonicalJvmDescriptor`
  (default arm `"L" + canonical.replace('.', '/') + ";"`);
- textual path (flag-off AND flag-on fallback):
  `BoundedFrameworkModelCandidateMetadata.jvmDescriptor` (default arm
  `"L" + resolved.replace('.', '/') + ";"`).

`demo.Outer.Inner` renders `Ldemo/Outer/Inner;` where the JVM descriptor
requires the binary name `Ldemo/Outer$Inner;`.

Consumer reality (why the switch is a fidelity fix, not a dialect shuffle):
source-side `targetDescriptor` evidence is compared against provider facts'
`calleeSignature`, which is bytecode-derived and carries REAL binary
descriptors (`JavaAnalysisAdapter.targetDescriptorsMatch`; SootUp tests pin
`(Ljava/lang/String;)Ldemo/Account;`; binding-candidate rows carry real JVM
descriptors, e.g. `()Ldemo/Token;` in `JavaAnalysisAdapterTest`). The slash
dialect therefore never matches a nested-type consumer — switching to binary
names can only create correct matches, and abstaining on unprovable names
loses no matching power that exists today.

No existing test pins slash-nested descriptors (exhaustive grep over
`src/test` for nested JVM descriptor spellings: zero matches; the pinned
descriptors are top-level only, e.g. `(Ljava/util/List;[Ljava/lang/String;)V`).

## Scope (binding)

Production sources in `src/main` only, plus the new test class. No changes
to: the comma-canonical dialects (`JavacAnnotationAccess.methodDescriptor`,
`repositoryMethodSignature` identities — canonical dots stay), canonical-name
comparisons (`parameterMatchesType`, `correlatedFirstParameterIs`, producer
bean types, the bijection correlation vectors), error-type abstentions,
`memberHasErrorType`, the compilation setup (already joint — see preflight),
or diagnostics (**zero new diagnostics, zero scan-global blast radius**;
stated per AGENTS).

1. **Index binary member shapes.** `MemberInfo` folds its type shapes into a
   nested `MemberTypes(List<String> parameterTypes, String returnType,
   List<String> parameterBinaryTypes, String returnBinaryType)` record so the
   outer record stays at 6 components (PMD `ExcessiveParameterList` fires at
   8, `config/pmd-production.xml`). Hand-written accessors
   `parameterTypes()`, `returnType()`, `parameterBinaryTypes()`,
   `returnBinaryType()` delegate to the fold, so EVERY existing caller and
   test compiles unchanged (verified: `MemberInfo` is constructed in exactly
   one place, `ElementReader.memberInfo`; all readers use the accessor
   methods). Binary spelling rules in `ElementReader`, mirroring
   `canonicalType`: erasure first; `ARRAY` → component binary + `"[]"`;
   `DECLARED` → `elements.getBinaryName(typeElement)`; `ERROR` → `""` (blank;
   unreachable in practice because `memberHasErrorType` blocks the member,
   kept for defensive parity with the canonical blank rule); primitives →
   same spelling; constructors `"void"`, field-like members `""`.
2. **Index-rendered descriptor dialect.** `JavacShapeDescriptors
   .jvmDescriptorFromMember` renders from the BINARY shapes; the default arm
   becomes `"L" + binary.replace('.', '/') + ";"` → `Ldemo/Outer$Inner;`.
   The blank-component abstention and the primitive/array switch are
   unchanged. The existing no-dot guard (default-package spellings abstain)
   is kept against the binary spelling.
3. **Textual descriptor dialect.** `BoundedFrameworkModelCandidateMetadata
   .jvmDescriptor` maps the resolved canonical through a new canonical→binary
   seam before rendering (`"L" + binary.replace('.', '/') + ";"`), in BOTH
   flag states (flag-off, and flag-on textual fallback for non-correlated
   members). New package-private final class `JvmBinaryNames`:
   `static Optional<String> binaryName(SourceMetadataScanner scanner,
   String canonical)`:
   a. **Index lookup (flag-on, DEFINITE):** `JavacSourceSymbolIndex.of(
      scanner.context)` → `type(canonical)`; exactly one entry and not
      duplicate-flagged → its `binaryName`. Otherwise fall through.
   b. **File-set top-level (DEFINITE):** exactly one scanned file whose
      `typeName()` equals the canonical → a top-level type's binary equals
      its canonical (covers `$`-in-simple-name top-level types such as
      `demo.Cash$Flow`, whose binary the segment rule could not reconstruct).
   c. **Segment boundary rule (convention-grounded, fail-closed guarded):**
      split the canonical on `'.'`; if any segment is EMPTY → abstain; if any
      segment CONTAINS `'$'` → the canonical unchanged (a `$` in a
      source-derived canonical spelling denotes a `$`-named simple
      identifier — source references to nested types use dots, so the
      spelling is a top-level reference and binary == canonical; this is
      also today's accidental-but-correct rendering for that shape);
      otherwise find the FIRST segment whose first character is uppercase —
      the type chain starts there: binary = package prefix (segments before,
      joined with `'.'`) + `"."` + chain (joined with `'$'`). If the boundary
      lands at segment 0 (first segment uppercase: default package or an
      UPPERCASE PACKAGE — textually indistinguishable) → abstain (kills the
      `Com.example.Foo` false-DEFINITE window and only loses degenerate
      default-package nestings). If NO segment starts uppercase → the
      canonical unchanged (preserves today's rendering for all-lowercase
      verbatim pass-throughs such as `a.b`).
      Examples: `java.util.Map.Entry` → `java.util.Map$Entry`;
      `demo.Outer.Inner` → `demo.Outer$Inner`; `demo.Outer.lower` (lowercase
      nested member after an uppercase outer) → `demo.Outer$lower`;
      `java.lang.String` → unchanged; `demo.Bean` → unchanged.
      The rule reuses the codebase's existing textual convention
      (`resolveReferenceType`'s lowercase-first-segment verbatim rule;
      FrameworkLayer00's uppercase-first-segment type-qualified rule) — it is
      not a new convention, and every input it renders wrongly requires a
      package segment starting uppercase or a type segment starting
      lowercase, both outside the convention the existing resolver already
      assumes.
4. **No facet-1 production change.** The compilation setup stays as PLAN-21
   specified (joint task, empty sourcepath). Facet-1 acceptance is pinned by
   characterization cells instead.

## Certainty model

- Unchanged abstention ladder: blank/unprovable components abstain
  (`Optional.empty`), matching falls back rather than fabricating. No member
  that correlates today stops correlating; no descriptor that matches a real
  consumer today changes (nested slash descriptors never matched anything
  real — preflight).
- Flag-off default output changes ONLY for nested-type JVM descriptors
  (slashes → `$`, or absent when the boundary rule abstains) — exactly the
  issue's acceptance ("both dialects switch in the SAME change"). All other
  flag-off output is byte-identical.
- Diagnostics: none added; `scan.diagnostics()` and
  `ServiceLoaderDescriptorScanner.isUnique` untouched (zero blast radius).

## Test matrix (`JavacBinaryNameMigrationTest`, new file)

`@Isolated` + `SAME_THREAD` (writes the flag), fixture helpers copied from
`JavacTypeShapeMigrationTest`'s pattern. RED = failing at HEAD.

Derived from the canonical-name shape classes (AGENTS: derive, don't
enumerate) — the equivalence classes of a canonical name under the
canonical→binary map, plus the two dialects and both flag states:

- **N1 (RED): index dialect, nested.** Method `(Outer.Inner p)` returning
  `Outer.Inner` (same file) → `jvmDescriptorFromMember` ==
  `(Ldemo/Outer$Inner;)Ldemo/Outer$Inner;` (RED: slashes today).
- **N2 (RED): index dialect, nested array + varargs.** `Outer.Inner[]` and
  `Outer.Inner...` parameters → `[Ldemo/Outer$Inner;`.
- **N3 (RED): textual dialect flag-off, type-qualified spelling.**
  `methodDescriptor(scanner, file, "java.util.Map.Entry key",
  "java.util.Map.Entry")` → `(Ljava/util/Map$Entry;)Ljava/util/Map$Entry;`
  (RED: slashes today; resolves via the verbatim lowercase-qualified rule).
- **N4 (RED): textual dialect flag-off, import-qualified spelling.**
  Parameter/return spelled `Map.Entry` with `import java.util.Map` → same
  `$` descriptor (resolves via `importedTypes`, probe-confirmed reachable).
- **N5 (RED): dialect parity, end to end.** A producer
  `@Produces public java.util.Map.Entry entry()` (plus a same-file
  correlation-safe shape): flag-on (index binary via correlation) and
  flag-off (textual derivation) `producerDescriptor` are EQUAL and carry
  `$` (both arms RED today: flag-on slashes, flag-off slashes).
- **B1: `$`-simple-name top-level, file-set (GREEN characterization,
  mutation guard).** File `demo/Cash$Flow.java` declaring top-level
  `Cash$Flow`; a member returning it renders `Ldemo/Cash$Flow;` in BOTH
  dialects (today's accidental-correct rendering; pins rule b so the new
  seam cannot regress it).
- **B2 (RED→ by N3/N4 semantics): lowercase nested member, fully-qualified
  spelling.** `demo.Outer.lower` (nested `class lower` inside `Outer`)
  spelled fully qualified → `Ldemo/Outer$lower;` (boundary rule chain
  join; RED: `Ldemo/Outer/lower;` today).
- **B3: boundary-at-0 abstention.** A canonical whose first segment is
  uppercase (default-package shape `Outer.Inner`, or an uppercase-package
  import `Com.example.Foo`) → descriptor ABSENT in the textual dialect
  (abstain, fail closed). Constructed via direct `JvmBinaryNames` call +
  one end-to-end miss cell.
- **B4: `$`-containing non-file-set canonical.** Verbatim spelling
  `com.lib.Cash$Flow` → binary unchanged → `Lcom/lib/Cash$Flow;`
  (characterization of the rule-c `$` passthrough; flag-on the index is
  absent for it, so both flags agree through rule c).
- **B5: empty-segment abstention.** Direct `JvmBinaryNames` probe:
  `a..B` → empty (defensive boundary cell, never reachable through
  resolution).
- **C1 (GREEN characterization, facet 1): cross-file top-level return.**
  Producer in one file returning a top-level type declared in another file:
  flag-on correlated member present, `returnType` canonical, bean type
  canonical; flag-off bean type empty or textual (differential pin of the
  existing correct behavior).
- **C2 (GREEN characterization, facet 1): cross-file qualified-nested
  return.** Producer returning `Outer.Inner` (qualified) with `Outer` in
  another file: flag-on correlates (probe-verified), flag-off textual
  abstains — the differential that the misdiagnosed #23 residual claimed
  was missing.
- **C3 (GREEN guard): genuinely unresolvable still abstains.** Method
  returning `missing.Thing` (resolves nowhere): flag-on correlation empty
  (errorType blocks), textual absent — the issue's third test cell.
- **Sanctioned test edit (comment only, no assertion change):**
  `JavacTypeShapeMigrationTest.producerReturnTypeAbstainsOnUnprovableIndex
  Shapes`'s flag-on comment says "the index is per-file" — factually wrong
  at HEAD (preflight). Corrected to the JLS scoping rationale. Zero edits to
  any other existing test; no existing assertion changes anywhere.

Pairwise/interaction coverage (AGENTS): nesting × array/varargs (N2),
nesting × flag state (N5), nesting × resolution route (verbatim N3 vs
import N4 vs file-set B1), nesting × `$`-collision shape (B1/B4 vs N1 — the
D3 collision renders one descriptor for both, asserted in B1's twin).

## Boundary probes (pre-GREEN, scratch, not committed)

Empirical probe of the segment arithmetic before GREEN, per AGENTS: empty
segment, single-character segment, boundary at 0, boundary at last segment
(single uppercase segment), `$` in first/middle/last segment, exact-length
names, and the B2 lowercase-chain join — asserted against the compiled
`JvmBinaryNames` (the probe doubles as the B5/B3 cell fixtures).

## TDD sequence

- **PLAN_60** (this file): committed first.
- **RED_60**: `JavacBinaryNameMigrationTest` with the matrix above. Expected
  RED: N1–N5, B2, B3 (end-to-end arm). Expected GREEN at RED: B1, B4, B5
  (B5 needs `JvmBinaryNames`, which does not exist at RED — B5 moves to
  GREEN-expected-red-then-green via a behavior-neutral stub: RED commits the
  test plus a stub `JvmBinaryNames` returning `Optional.of(canonical)`
  unchanged, documented in the commit message; B5 then fails until GREEN).
  C1–C3 are green characterization pins at RED (documented). Run
  `make adapter-test` at RED to confirm the expected-red cells fail and the
  rest of the suite stays green; `make java-lint`; `make evidence-validate`.
- **GREEN_60**: minimal implementation — `MemberTypes` fold + binary shapes
  in `ElementReader`; `JavacShapeDescriptors` binary rendering; `JvmBinaryNames`
  (rules a/b/c); textual wiring in `BoundedFrameworkModelCandidateMetadata
  .jvmDescriptor`; the sanctioned comment correction. Full gates.
- **EVIDENCE_60**: `docs/resolutions/RESOLUTION-60.md` — probe receipts
  (facet-1 disposition with the misdiagnosis correction), gate counts,
  mutation checks (revert the binary arm of `JavacShapeDescriptors` → N1/N2
  fail; revert `JvmBinaryNames` wiring → N3–N5 fail; revert the
  `MemberTypes` binary population → N1/N2/N5 flag-on arms fail; revert the
  boundary-at-0 abstention → B3 fails).

Gates after every item: `make adapter-test` (execution count must not
decrease from the current ~628; Makefile floor 534), `make java-lint`
(zero new findings), `make evidence-validate`.

## Non-goals

- Flipping the flag default, deleting the textual path (umbrella #20).
- Owner-qualified textual FALLBACK spellings for unqualified nested
  references (`Inner` → `Outer.Inner`): the scoping abstention stays; a
  follow-up would need inherited-member-type analysis (JLS 6.5.5.1), out of
  scope here and recorded in Residuals.
- The comma-canonical dialects and canonical-name comparisons (unchanged).
- Descriptor provenance / dependency policy (#61/#62 territory).

## Residuals / risks

- The segment boundary rule (c) is convention-grounded for NON-file-set
  types: a dependency type whose nested member starts lowercase AND whose
  outer type is reached only through a fully-qualified spelling is rendered
  correctly by the chain-join, but a package segment starting uppercase
  cannot be distinguished from a default-package type — that shape abstains
  (B3). A lowercase-nested dep type referenced via import-qualified spelling
  (`Map.Entry`-style) renders correctly. Remaining corruption window: a
  dependency whose PACKAGE starts uppercase and is imported explicitly —
  that canonical never reaches rule c through the verbatim route (it
  requires a lowercase first segment) and rule c abstains at boundary 0, so
  the window is closed by construction (B3 pins it).
- Default-package nestings (`Outer.Inner` with no package) now abstain in
  both dialects (previously rendered with slashes — never matched a real
  consumer). Degenerate shape, documented.
- `BoundedFrameworkModelTypeParsing.canonicalType` / injection contract
  matching remain textual (#61).
- The index diagnostics visibility follow-up (#62) is unaffected.

Confidence score: **0.83**. The production change is small and pinned on
both sides by red-first cells; the main uncertainty is the segment rule's
interaction with exotic name shapes, contained by the abstention guards and
the boundary cells, and the possibility that a reviewer surfaces a consumer
of the slash dialect that the preflight grep missed (mitigated: the consumer
enumeration followed every reader of both renderers).
