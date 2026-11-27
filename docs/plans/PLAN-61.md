# PLAN-61 — Injection-contract type matching via javac index shapes

Issue: project-lightpath/java-analysis-adapter#61 (part of #20; the last
major TEXTUAL consumer of the javac symbol-index seam).
Branch: `issue-61-injection-javac-shapes` from `origin/main` @ `fb8a664`.
Floor at branch time: **754** executions (Makefile literal, post-#80).

## 1. Binding requirements

- Route the injection-contract type matching through
  `flpath.java.symbolindex` (default ON): correlate the declaring members
  and compare canonical index shapes, with the fail-closed ladder
  (textual fallback, no fabricated DEFINITE).
- Flag-off (`-Dflpath.java.symbolindex=false`) is byte-identical on every
  wired surface — pinned by flag-off goldens, count-form where identity
  strings are involved (#23's CodeRabbit identity-split lesson).
- Same-spelling declarations keep ONE identity across files regardless of
  import style (the #23 repository-signature file-INDEPENDENT rule).
- No fabricated DEFINITE: ambiguous or unresolvable correlations abstain
  to the textual ladder.
- Abstention evidence (if any is surfaced) never enters
  `scan.diagnostics()` (#62/#76 contracts); the opt-out gate lives INSIDE
  the seam; a failed index build keeps lookup results and silences
  evidence (`withSilencedEvidence`).
- Unique-owner guards wherever attribution would be ambiguous.
- Gates after every item: `make adapter-test` (floor raised from 754),
  `make java-lint` (0 new findings; JDK 27 vs helios08 JDK 17 divergence
  checked per #64 when GodClass is in play), `make evidence-validate`.
- Zero edits to existing tests unless this plan sanctions them
  explicitly (each sanction listed in §7).

## 2. Preflight inventory (verified at fb8a664, file:line)

### 2.1 The textual surface and its full consumer set

`BoundedFrameworkModelTypeParsing.canonicalType` (TypeParsing:32) is the
central resolver; it delegates to the TEXTUAL import resolver
`SourceMetadataScannerTextLayer14.resolveReferenceType` (TextLayer14:47:
explicit imports → dotted-lowercase-first-segment → same-package files →
on-demand imports → java.lang list). Every defect class of #26 applies:
on-demand JDK imports do not resolve (probe-verified in #23 CR-2 thread
r4675771839), nested `$` spellings, case-insensitive simple-name
fallbacks, generics handling is textual.

Consumers (weakness-disposition audit — the COMPLETE set, rg-verified):

| # | Consumer | Site | Resolves |
|---|----------|------|----------|
| C1 | `directTypeCompatibility` | TypeSupport:56 | requestedRaw, candidateRaw, requestedCanonical, candidateCanonical |
| C2 | `directTypesHaveDifferentRawTypes` | TypeSupport:96 | requestedRaw, candidateRaw |
| C3 | `injectionTypesMatch` | TypeSupport:119 (matchingBindings, MetadataMatching:42) | metadata, source, metadataRaw, sourceRaw |
| C4 | `contractMatches` / `contractMatchesType` | TypeMatching:71/92 | requested, candidateRaw, candidate (contracts) |
| C5 | `metadataCandidateTypeMatches` | MetadataState:101 (metadataOnlyCandidates:95) | requested, row.candidateType, row contracts |
| C6 | `metadataTypeMatches` | TypeSupport:32 | simple-name fallback, `declaredTypeNames` uniqueness, case-INSENSITIVE |

C6's complete consumer set (weakness-disposition enumeration):
`injectionOwnerMatches` (MetadataMatching:63-68, the resolved-binding owner
filter), the `injectionTypesMatch` fallback (TypeSupport:137), and
`candidateMetadataMatches` (CandidateMetadata:51/55 — candidateType and
declaringType arms).

Call paths into the surface (BoundedFrameworkModel.InjectionScanning):
`matches` at :158 and :255, `typeMatchCertain` at :316 and :328
(certainty gates for DEFINITE), `metadataOnlyCandidates` at :168,
`matchingBindings` at :181. `candidateMetadataMatches`
(CandidateMetadata:51,55) calls C6 for row-vs-candidate identity — used
by `matchingCandidateMetadata` everywhere.

Injection points are extracted textually in `FrameworkInjectionScanning`
in three kinds — constructor (:46, regex with the phantom-site
`countsMatch` guard at :80), field (:118, INJECT_FIELD regex), method
(:150, MethodInfo-based). Parameter TYPE spellings stay textual
(`injectionParameters` repairs only QUALIFIERS); `Parameter.type()`
feeds evidence strings and fact IDs (`owner#member(type)` in
InjectionScanning.injectionFact :460,:478) — those identity strings must
NOT change flag-on (they are display/ID inputs, not matching inputs).

### 2.2 Seam facts (verified)

- `JavacFrameworkMemberLookup.lookup(owner, name, kind, arity)`
  (JavacFrameworkMemberLookup:45): the #24/#62 unique-member seam —
  flag-gated INSIDE, unique usable owner type
  (`shapeIsUsable`), unique member in the name+kind+arity group,
  `mirror_member_error_type`/`_absent`/`_ambiguous` declines,
  `withSilencedEvidence` on failed builds. Handles kind `field`
  (arity −1 matches any). This is the SAME seam the injection extractor
  already uses for qualifier repair — reusing it keeps one member-rule
  per site.
- `JavacMemberCorrelation.producerCorrelation`
  (JavacMemberCorrelation:284): candidate-own-parameter-text overload
  identification → correlated member (seam A, bijection guards).
- Producer candidate types are ALREADY partially index-derived:
  `FrameworkBeanScanning.producerCandidate` (:140-197) resolves the
  producer return textually (:149) and maps the correlated member's
  `returnType()` (:161). The NON-producer candidate type is the textual
  `file.typeName()`.
- `MemberInfo.MemberTypes` (JavacSourceSymbolIndex:1108) carries
  canonical AND binary, ERASED parameter/return spellings. FIELD members
  are indexed (isMember :850) but `memberReturnType` returns `""` for
  fields (:875-883) — field type shapes are a GAP. `memberHasErrorType`
  already covers field error types via `isError(element.asType())`.
- `TypeInfo` (:1066) has NO supertype component — contracts cannot be
  compared as index shapes without an additive extension.
- The index compiles the FULL selected source set in ONE JavacTask
  (RESOLUTION-60 probe; JavacSourceSymbolIndex:294-300) — cross-file
  references among selected files resolve; residual errors are JLS
  6.5.5.1 scoping failures or missing classpath types.
- Parallel work: PR for #77 (producer-anchor normalization) is open —
  different files; all new tests go in a NEW class; rebase and re-measure
  the floor if it merges mid-flight.

### 2.3 Preflight probes to run at RED (receipts in the RED commit)

- P1: nested-type `file.typeName()` spelling (`$` vs `.`) vs the index
  `canonicalName`/`binaryName` for a nested bean candidate — decides
  whether the candidate arm changes identity for nested types.
- P2: `resolveReferenceType` on `java.util.List` under
  `import java.util.*;` returns empty (re-verify at HEAD) — the
  requested-side defect-class fixture.
- P3: full-suite churn measurement with the prototype arms wired
  (scratch checkout, not committed): enumerate every existing golden
  whose flag-on output changes. Each changed fixture is either (a)
  re-derived in the sanctioned-edits list (§7) or (b) the arm is
  narrowed to keep it stable — decided per fixture, documented. The
  sweep MUST include a reflection field-target golden
  (`getField`-style site) to prove the kind filter that keeps
  `JavacReflectionTargets.selectMember` (:221-226, reads
  `returnBinaryType()` of its matched member) method/constructor-scoped
  when field members gain type shapes.
- P4: A5 reachability — run the textual extraction
  (`scanner.parameters` → `FrameworkInjectionScanning.injectionParameters`)
  on the exact TYPE_USE-annotated fixture; if no `Parameter` is produced,
  the site is never created and the cell is replaced with the strongest
  REACHABLE corruption (RESOLUTION-23's annotated-return lesson).
- P5: varargs and type-parameter probes — (a) the index renders a
  varargs parameter as an array (`MemberTypeShapes.canonicalType`
  ARRAY case, JavacSourceSymbolIndex:961-964) while the textual
  `parameter.type()` may retain `...` and `canonicalType` strips only
  `[]` (TypeParsing:41): probe `String... args` constructor and method
  injection empirically; (b) the erased-bound rendering of a
  type-variable parameter (`<T extends Number> void inject(T t)`) —
  A12's flag-on expectation depends on it, so it is probed, not
  inspected (review round 2, finding 6). Never verify by inspection
  (AGENTS boundary rule).

## 3. Design

### 3.1 New seam: `JavacInjectionTypeShapes` (package-private, final)

All methods return `Optional`/empty on EVERY decline; the flag gate and
`withSilencedEvidence`-style failed-build handling live INSIDE (mirroring
`JavacFrameworkMemberLookup`); nothing enters `scan.diagnostics()`;
no method ever returns a shape the textual path could contradict —
every shape is javac's own resolution of an ACTUAL indexed declaration.

- **`requestedShape(injection, parameterIndex)`** — the canonical
  ERASED index shape of one injection parameter:
  - constructor/method: `JavacFrameworkMemberLookup.of(scanner).lookup(
    injection.ownerType(), memberName, kind, parameters.size())`
    where memberName is the injection member (`<init>` maps to the
    owner's simple name, the index's constructor naming) →
    `member.parameterTypes().get(parameterIndex)`.
  - field: same lookup with kind `field`, arity −1 → the member's FIELD
    TYPE SHAPE (§3.2 extension).
  - constructor sites re-apply the extractor's phantom-site guard: the
    seam recomputes the textual constructor site count through a
    package-private helper extracted from
    `FrameworkInjectionScanning.constructorInjectionPoints` (the SAME
    regex over `scanner.maskAnnotationArguments(file.structuralSource())`
    — deterministic and re-derivable at matching time — and quoting
    `file.simpleTypeName()`, the extraction rule) and compares it with
    `javac.memberCount(owner, file.simpleTypeName(), "constructor")`,
    the identical `countsMatch` rule the qualifier repair uses (:80).
    The LOOKUP member name (`<init>` → owner's simple name) is a
    separate concern from the guard's count name (review round 2,
    finding 7). A mismatch declines the whole file's constructor
    shapes. NO `InjectionPoint` record change is needed (review round
    1, finding 8); `equals`/`hashCode` on the record are untouched.
  - Any decline (ambiguous overload, error-typed member, multi-type
    owner, absent member, flag off, failed build) → empty.
- **`candidateShape(candidate)`** — the canonical ERASED identity of the
  candidate's own declaration:
  - non-producer: `typesIn(candidate.file())` filtered to entries whose
    `canonicalName` OR `binaryName` equals `candidate.candidateType()`
    (the MirrorLookup named-filter rule, JavacFrameworkAnnotations:66-70),
    unique usable → `canonicalName`.
  - producer: `JavacMemberCorrelation.producerCorrelation(candidate)` →
    `member.returnType()` (existing seam; verify the candidate-type
    wiring at FrameworkBeanScanning and reuse, do not duplicate).
- **`contractShapes(candidate)`** — `Optional<List<String>>` of the
  candidate's DIRECT supertypes (canonical, erased) from the new
  `TypeInfo.supertypes` component (§3.2); empty when the candidate's
  type does not resolve uniquely or the type is error-shaped.

### 3.2 Index extensions (additive, `MemberPosition`/`publicMember` precedent)

1. **Field type shapes**: FIELD and ENUM_CONSTANT members record their
   declared type in `MemberTypes.returnType` / `returnBinaryType`
   (currently `""`). COMPLETE reader audit (weakness-disposition rule):
   every current `MemberInfo.returnType()`/`returnBinaryType()` reader
   is method/constructor-scoped — `FrameworkBeanScanning:161` (producer
   methods only), `JavacShapeDescriptors` (method descriptors),
   `JavacReflectionTargets.selectMember:221-226` (reads
   `returnBinaryType()`; safe ONLY because its candidate streams are
   kind-filtered to method/constructor at :136-144) — and P3's churn
   sweep includes a reflection FIELD-target golden (`getField` site) to
   prove that filter holds with field shapes populated.
   `memberHasErrorType` already flags error-typed fields via the
   declared mirror.
2. **`TypeInfo.supertypes`**: `List<String>` canonical ERASED spellings
   of the DIRECT superclass (omitting `java.lang.Object`) and directly
   implemented interfaces. An ERROR-kind supertype entry is OMITTED (the
   textual contract ladder still covers that spelling — omitting is the
   conservative direction for an additional match source).

Both extensions change no existing reader's answer (audit at RED with
the full suite; see P3).

### 3.3 Threading

`InjectionScanning.injectionFactsForParameter` computes
`Optional<String> requestedShape` ONCE per parameter via the seam and
passes it down. Signature changes are confined to the
BoundedFrameworkModel family (`matches`, `typeMatchCertain`,
`contractMatches`, `metadataOnlyCandidates`, `matchingBindings` →
`injectionTypesMatch`); the textual parameters remain so the ladders
below stay expressible. No state-record changes (§3.1).

**Abstention evidence decision** (review round 1, finding 9): the seam
returns bare `Optional`s with NO evidence channel — a decline IS the
textual fallback, and the issue requires no new visibility. Pinned by
C3 (`scan.diagnostics()` empty, DEFINITE promotion untouched).

### 3.4 Comparison wiring (the load-bearing ladders)

Two rules govern every arm (review round 1, findings 1–3):

- **Repair-only substitution**: an index shape fills a textual VACUUM —
  `erasedRaw(spelling, file, shape) := canonicalType(eraseGeneric
  Arguments(spelling), file, scanner).or(shape)`. When the textual
  resolution SUCCEEDS with a value, the textual answer wins and the
  shape is unused. The precise invariant (review round 2, finding 1):
  **no non-null textual answer flips** — but a textual ABSTENTION
  (`Optional.empty`) is an answered state too, and filling it changes
  outcomes BY DESIGN (that is the repair): `directTypeCompatibility`
  transitions null→TRUE (javac-proven raw equality → a legitimate new
  match), null→FALSE (javac-proven raw inequality → the contract path
  runs under `directTypesHaveDifferentRawTypes`, and
  `typeMatchCertain` TypeMatching:65-67 can promote to DEFINITE through
  `contractMatches` — legitimate: every input to that promotion is
  javac-proven or runs its own ladder), and `injectionTypesMatch`'s
  erased branch can suppress the C6 case-insensitive fallback
  (a javac-proven correction of an acknowledged over-approximation).
  These **vacuum-fill promotions** are the sanctioned churn class in
  P3: each is enumerated, and each promotion path's no-fabrication
  argument is the one given here. The both-present-divergent case keeps
  the pre-existing textual answer (e.g. a same-compilation-unit type
  that TextLayer14's import-first order misses — a documented residual,
  not a regression).
- **Generics gate**: wherever the textual ladder treats generic
  arguments as load-bearing (`contractMatchesType` TypeMatching:107,
  `metadataCandidateTypeMatches` MetadataState:106-117), the index arm
  is admitted ONLY when `!hasGenericArguments(requestedType)` — the
  index is erased, and an erased comparison against a generic-retaining
  textual semantics fabricates matches (and, through `typeMatchCertain`
  TypeMatching:65-67, fabricated DEFINITE).

- **C1 `directTypeCompatibility`**: `requestedRaw` and `candidateRaw`
  use the repair-only helper (requested shape arm; candidate shape
  arm). The generic tail (producer signatures) stays textual.
- **C2 `directTypesHaveDifferentRawTypes`**: NO shape arms — DISPOSITION
  (review round 2, finding 2): both call sites (TypeMatching:46,:66)
  are guarded by `directCompatibility == Boolean.FALSE`, which (under
  the identical helper) already entails both raws present and
  different, so arms here are constant-true dead code with no
  mutation-receiptable cell. The function stays fully textual and
  unchanged.
- **C3 `injectionTypesMatch`**: in the ERASED branch, `sourceRaw` uses
  the repair-only helper (requested shape arm); the metadata side and
  the bothGeneric branch stay textual (generic binding rows are a
  documented residual).
- **C4 `contractMatches`**: the textual ladder runs unchanged; ADD one
  disjunct — `!hasGenericArguments(requestedType) && requestedRaw.is
  Present() && contractShapes(candidate).contains(requestedRaw.get())`
  where `requestedRaw` is the repair-only helper result (erased).
  PLACEMENT (review round 2, finding 5): the disjunct is evaluated
  independent of the `requested.isEmpty()` early return
  (TypeMatching:77-80) — the shape arm fires precisely when that early
  return would (the A1/B1 defect class), so the early return must not
  preempt it. The contract side's index shapes answer exactly the case
  the textual contract resolution cannot (an unresolvable implements
  spelling); metadata-row contracts stay textual (external spellings,
  residual).
- **C5 `metadataCandidateTypeMatches`**: the textual ladder runs
  unchanged; ADD one disjunct — `!hasGenericArguments(requestedType) &&
  rowErasedRaw.isPresent() && rowErasedRaw.equals(requestedRaw.get())`
  where `requestedRaw` is the repair-only helper result and
  `rowErasedRaw` is the TEXTUAL arm only (metadata-only rows have no
  indexed declaration — there is no row-side shape; review round 2,
  finding 3). Same placement rule as C4: independent of the
  `requested.isEmpty()` early return (MetadataState:106-109).
- **C6 `metadataTypeMatches`**: UNCHANGED, dispositioned — its
  case-insensitive unique-simple-name arm is an over-approximation;
  tightening could lose true answers, and no index arm adds a match the
  case-insensitive arm does not already cover. Documented residual.
- **`proxyTargetTypeMatches`** (MetadataMatching:95): OUT of scope —
  the proxy surface's descriptors already correlate through #23; the
  row targetType ladder is its own follow-up. Documented residual.

### 3.5 No-fabricated-DEFINITE argument

Every index shape is javac's resolution of an actual indexed declaration
(the injection's own member parameter, the candidate's own type, the
candidate's own direct supertypes), and every shape participates only
as a repair of a textual VACUUM or as a generics-gated additional
disjunct (§3.4) — a comparison the textual ladder answered with a
VALUE is never flipped. The vacuum-fill promotion paths and their
no-fabrication arguments: null→TRUE in `directTypeCompatibility`
(javac-proven raw equality); null→FALSE plus
`directTypesHaveDifferentRawTypes` plus `contractMatches` in
`typeMatchCertain` (TypeMatching:65-67 — the FALSE and the raw
difference are javac-proven, and the contract disjunct runs its own
gated ladder); the C3 fallback suppression (javac-proven correction of
the C6 case-insensitive over-approximation). Wrong-attribution guards:
unique usable owner, unique member in the name+kind+arity group, the
extractor's phantom-constructor count guard (recomputed through the
same implementation), the producer's own-parameter-text overload
identification, unique named-type filter for candidates. A decline
leaves the exact pre-change textual behavior (flag-off equivalence by
construction; the seam is empty flag-off).

## 4. Test matrix — `JavacInjectionContractMigrationTest` (NEW class)

`@Isolated @Execution(SAME_THREAD)`, flag written per cell; every cell
has a flag-off twin asserting the textual golden (count-form where fact
identity strings are involved). Cells:

- **A. Requested-side shapes (constructor/method/field × defect class)**
  - A1 on-demand JDK import (`import java.util.*;` inject `List`) —
    flag-on matches the candidate/metadata FQN; flag-off count-form
    golden (unresolved, P2 re-verified).
  - A2 field injection with the same defect (field type shape path).
  - A3 method injection (`@Inject`-style annotated method).
  - A4 import-style independence: two injection files, one explicit
    import, one on-demand import of the same source type — ONE
    identity; both match the same candidate flag-on.
  - A5 annotation-corrupted parameter spelling — REACHABILITY probed
    first (P4); the cell pins the strongest reachable corruption.
  - A6 nested-type identity: candidate nested `Outer.Inner` — the
    candidate arm canonicalizes (P1 decides the exact cell form).
  - A7 same-arity overload ambiguity → seam abstains → textual result
    byte-identical flag-on/flag-off (abstention cell).
  - A8 multi-type owner file → abstention cell.
  - A9 error-typed member (unresolved classpath type in the signature)
    → abstention cell.
  - A10 phantom-constructor guard: void method spelled like the class →
    the whole file's constructor shape arm declines.
  - A11 varargs injection (`String... args`, constructor and method) —
    pinned from the P5 probe, never by inspection.
  - A12 type-parameter parameter (RES-23 CR-1/CR-9b class):
    method-scoped `<T extends Number> void inject(T t)` and an
    enclosing-class-scoped type variable — flag-on erased-bound shape
    vs flag-off textual answer (the `T` spelling textually unresolvable
    or import-resolved).
- **B. Contract shapes**
  - B1 direct supertype match through index shapes where the implements
    spelling is unresolvable textually (on-demand import).
  - B2 transitive contract NOT matched (parity with the textual
    declared-contracts semantics — no new transitive matching).
  - B3 error supertype omitted; textual contract ladder still answers.
  - B4 generics gate (C4): requested `Repo<User>` vs candidate
    `implements Repo<Order>` — same ERASED contract, different type
    argument → NO match flag-on (the disjunct is gated; red under a
    gate-removal mutation).
  - B5 generics gate (C5): requested `Repo<User>` vs metadata-only row
    candidateType `Repo` → NO match flag-on.
- **C. Certainty / blast radius**
  - C1 flag-on DEFINITE promotion through `typeMatchCertain` on an
    index-proven match (the #61 acceptance cell).
  - C2 no fabricated DEFINITE: a shadowing fixture (sibling type with
    the JDK simple name) where javac resolves differently from the
    textual guess — the index arm must not manufacture a match
    (`reflectionParameterMatchingDoesNotMatchShadowedSimpleNames`
    pattern), AND the gated C4 disjunct must not match a same-erased
    contract against the shadowed simple name.
  - C3 an abstaining fixture AND a ServiceLoader DEFINITE site in one
    scan: promotion untouched, `scan.diagnostics()` empty (#62 D1
    pattern) — the seam carries no evidence channel, so diagnostics
    stay empty by construction.
  - C4 full-suite zero-churn measurement (P3 receipt in EVIDENCE):
    every flag-on golden change is enumerated and sanctioned or the arm
    narrowed.
- **D. JLS 6.5/6.4.1 name-resolution matrix (AGENTS rule)** — the full
  precedence ladder for the type-position scope, derived from JLS
  6.5.5/6.4.1, weakest to strongest: on-demand import < same-package
  type in another unit < single-type import < same-compilation-unit
  type and inherited member types. Under the repair-only policy,
  divergence matters only where the textual resolution FAILS, so each
  cell constructs the weaker-scope spelling as the only candidate
  resolution:
  D1 on-demand × same-package; D2 on-demand × single-type import;
  D3 java.lang × on-demand; D4 java.lang × same-package;
  D5 java.lang × single-type import; D6a same-CU type × single-type
  import (textual RESOLVES to the import — pin the pre-existing
  textual answer flag-on as a characterization of the residual);
  D6b same-CU type with no matching import (textual FAILS — the repair
  resolves the CU's own nested type); D7 single-type import of a
  same-package type (legal Java; must resolve — parity cell);
  D8 same-package × same-CU (no import — textual same-package lookup
  misses the CU's own nested type, repair fixes);
  D9 on-demand × same-CU; D10 java.lang × same-CU;
  D11 inherited member type (cross-file, single level — the AGENTS
  scope; the unqualified spelling of a superclass's nested type fails
  the 2-arg textual resolver, javac resolves it flag-on).
  Each cell: flag-on answer where the textual resolver fails is the
  JLS-correct javac shape; flag-off twin pins the textual answer.

Cell count target: 22–26; floor 754 → 754+N (re-derived at GREEN).

## 4a. Plan-review record

- **Round 1** (Engram-grounded adversarial subagent, full plan): 12
  findings — 2 HIGH blocking (the C4/C5 disjuncts were generics-blind:
  erased index comparisons against generic-retaining textual semantics
  fabricate matches and, via `typeMatchCertain`, fabricated DEFINITE),
  1 HIGH blocking (no policy for both-present-divergent and mixed-arm
  substitution), 4 MEDIUM, 2 LOW, 1 NIT. All incorporated in this
  revision: the repair-only substitution policy (index shapes fill
  textual vacuums only — zero flip risk, the same-CU divergence becomes
  a characterized residual), the `!hasGenericArguments` gates on C4/C5
  with B4/B5 divergence cells, the `JavacReflectionTargets.selectMember`
  kind-filter audit + getField churn golden, P4 (A5 reachability) and
  P5 (varargs) probes, A11/A12 cells, the full D1–D7 JLS ladder, the
  no-InjectionPoint-change threading (guard recomputed through one
  shared implementation), the bare-Optional no-evidence-channel
  decision, and the C6 consumer enumeration.
- **Round 2** (delta-only, on the round-1 amendments): 7 findings —
  1 HIGH blocking (the "zero golden-churn" invariant overclaimed:
  vacuum-filling flips null→TRUE/FALSE in `directTypeCompatibility`,
  changing `matches`, `typeMatchCertain`, and certainty — restated as
  "no non-null textual answer flips", with vacuum-fill promotions
  made an explicit sanctioned P3 churn class and each promotion path's
  no-fabrication argument recorded in §3.5); 4 MEDIUM (C2's arms are
  call-site dead code — DISPOSITIONED, the function stays textual;
  C5's row side has no shape — reworded; the D matrix missed the
  inherited-member-type rung and three same-CU pairs — D8-D11 added,
  D6 split; the C4/C5 disjunct placement vs the `requested.isEmpty()`
  early return — specified independent); 1 LOW (A12's erased-bound
  expectation folded into P5's probe receipt); 1 NIT (guard count name
  vs lookup name — stated separately).
## 5. TDD sequence

1. **PLAN_61** (this document) + adversarial plan-review rounds
   (Engram-grounded subagent; loop until no addressable findings).
2. **RED_61**: the new test class, all cells failing (plus P1/P2 probe
   receipts; P3 churn measurement run on a scratch prototype arm).
   Sanctioned-edit list finalized here (§7).
3. **GREEN_61**: minimal implementation — seam, index extensions,
   threading, ladders. Full gates.
4. **EVIDENCE_61**: mutation receipts (each arm's removal makes exactly
   its cells fail), gate receipts, RESOLUTION-61 draft.
5. **REVIEW_61_n**: independent adversarial implementation reviews
   (fresh Engram-grounded subagent each round) until no new addressable
   findings.

## 6. Risks

- **Flag-on golden churn** (default is ON): measured at P3 before GREEN;
  every change is either sanctioned (§7) or the arm is narrowed. The
  no-true-answer-lost and no-fabricated-DEFINITE rules bound both
  directions.
- **PMD GodClass**: `BoundedFrameworkModel*` chain classes are near the
  threshold; the seam work goes in the NEW class; PMD-forced
  extractions keep behavior identical and are documented in the commit
  message (AGENTS rule).
- **JDK 17/27 lint divergence** (#64): the index extension touches
  `JavacSourceSymbolIndex` (a GodClass-measured file) — verify lint on
  helios08 JDK 17 when in doubt.
- **Parallel PR (#77/#81)**: disjoint files; new test class only; rebase
  protocol per §2.2.

## 7. Sanctioned test edits (the complete list; none others)

- NONE at plan time. P3 may add enumerated fixture re-derivations —
  each added edit is recorded HERE with the fixture name and the
  before/after answer before GREEN proceeds.

## 8. Confidence

Post-round-2 confidence: **0.86** — both blocking defect classes are
designed out with precise invariants (no non-null textual answer flips;
generics-gated disjuncts), the dead C2 arms are dispositioned rather
than wired, and the remaining open facts are confined to the P1–P5
probes with decision rules that cannot invalidate the ladder design.
