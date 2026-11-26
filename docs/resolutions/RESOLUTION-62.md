# RESOLUTION-62 — Blast-radius-safe visibility for javac index abstentions (per-member evidence channel)

Issue: project-lightpath/java-analysis-adapter#62 (part of #20;
follow-up from #23 PR #59 CodeRabbit thread r4681365963, CR-3 finding 3).
Branch: `issue-62-per-member-evidence` from `origin/main` @ `5c050ec`,
rebased onto `64f6b5d` (#68) before the review rounds.
Commits: PLAN_62 (+ 6 review-round amendments + the javacRepairAbstention
key amendment), RED_62 (×2, calibration), GREEN_62, REVIEW_62_1,
REVIEW_62_2, REVIEW_62_3.

## What changed

A per-member javac abstention evidence channel, generalizing #25's
per-fact `diagnostic` attribute to the other javac consumers: every
decline (correlation guard, parameter repair, framework-annotation
mirror) attaches evidence to the affected fact's evidence attributes —
NEVER `scan.diagnostics()`, so `ServiceLoaderDescriptorScanner.isUnique`
and DEFINITE promotion for unrelated entries are untouched. The channel
is visibility-only: certainty, status, limitation, and fact IDs are
unchanged everywhere (the full suite shows zero golden churn).

- **`JavacAbstentionEvidence`** (new record): decline code, member
  identity (`owner#name(arity)`), and the javac error codes recorded
  for the member's file (deduped, unique-owner-only). Renders the
  `javacAbstention` / `javacCallerAbstention` /
  `javacProducerTypeAbstention` attribute families — one family per
  member role, so co-occurring declines cannot overwrite each other.
- **`JavacCorrelation`** (new record) from `JavacMemberCorrelation`:
  the correlated member OR the abstention. Seam-A codes:
  `comment_corrupted_parameters`, `multi_type_file`, `member_absent`,
  `error_type_member`, `type_parameter_overload_group` (the
  unresolved-bounds class),
  `overload_group_mismatch`. The flag-off gate lives INSIDE the seam.
- **Seam B** (`JavacAnnotationMemberLookup`, extracted from
  `JavacAnnotationAccess` per PMD): `annotation_member_absent` /
  `_ambiguous` / `_error_type`, surfaced as `javacRepairAbstention`
  when the per-parameter repair also declined.
- **Seam C** (`JavacFrameworkAnnotations`): `mirror_type_absent`,
  `mirror_owner_unusable` / `_absent` / `_ambiguous`,
  `mirror_member_absent` / `_ambiguous` / `_error_type`, surfaced as
  `javacMirrorAbstention`.
- **The index diagnostics join**: `errorCodesByFile`, keyed by the
  normalized absolute `Path` held at `addCompilerDiagnostics` render
  time (never the rendered row path), deep-copied at `finish()`. A
  FAILED build (the #25 integrity codes, now ONE shared
  `INTEGRITY_CODES` set) silences the channel through
  `intactIndex()` — a scan-global build failure is not a per-member
  abstention.
- **Wiring**: proxy framework facts (`proxyAttributes`), bean_candidate
  evidence (descriptor + producer-type + mirror abstentions), Spring
  Data delegation facts (declared rows carry the guaranteed-contract
  check's abstention, causally gated), and the dynamic proxy /
  reflection provider facts (descriptor + caller abstentions). The
  caller abstention is a pure re-query of the same correlation the
  classification ran, gated by an extraction-time parameter-arm capture
  at the `isDeclaredVariableBefore` choke point.
- **PMD-forced placements** (behavior identical, per AGENTS):
  `JavacOverloadBijection`, `JavacRepositoryContractCorrelation`,
  `JavacAnnotationMemberLookup` extracted; `DynamicFeature` keeps its
  10-component shape via `JavacFeatureEvidence` grouping (records'
  canonical constructors are PMD-exempt; explicit ones are not).

## Acceptance evidence (issue #62)

- A flag-on abstention is visible in the scan output without entering
  `scan.diagnostics()` and without blocking DEFINITE promotion for
  unrelated entries: C1–C10 (every seam × code, end-to-end) and D1
  (an abstaining fixture AND a ServiceLoader DEFINITE site in one
  scan: promotion untouched, diagnostics empty, the abstention rides
  the fact).
- The #23 acceptance leg "Unresolved types produce POSSIBLE/unresolved
  plus a diagnostic" is satisfied through this channel: C2
  (`error_type_member` + `javacAbstentionDiagnostics` carrying the
  javac error code family for the member's file) and C9.
- Gates: `make adapter-test` **732 executions** (default=618, large=1,
  sootup=113; main's floor 717 + 15 cells), zero failures, zero golden
  churn; `make java-lint` PASS with 0 new findings on BOTH the local
  JDK 27 and helios08 Temurin 17 (scratch checkout, the #64
  discipline — the GodClass measurements moved both ways across JDKs
  and the extractions hold on both); `make evidence-validate` pass.

## Mutation checks (executed, all red under exactly their own mutation)

1. Remove the proxy-fact attribute merge ⇒ C1 fails.
2. Remove the caller-abstention parameter-arm gate ⇒ C10's
   field-receiver twin fails (the twin was STRENGTHENED during the
   evidence pass after the first mutation run passed vacuously — its
   original fixture's correlation succeeded, so the gate was not
   isolated; the twin now abstains and only the gate holds the
   attribute back).
3. Remove the `javacRepairAbstention` render ⇒ C7 fails.
4. Remove the failed-build integrity gate ⇒ D5 fails (the forced
   `javac_unavailable` build would mislabel every decline
   `multi_type_file`).
5. Remove the in-seam flag-off gate ⇒ D2 still passes — absorbed by
   `of()`'s own gate (defense in depth: the flag-off invariant is
   doubly gated, `of()` + seam; both were verified).
6. Add the abstentions to `dynamicFeatureIdentity` ⇒ the existing
   fact-ID goldens still pass, because their fixtures carry NONE
   abstentions — the ID stability is STRUCTURAL (the identity
   functions enumerate fields explicitly and the new component is not
   among them; verified by code inspection in review rounds 1–3) and
   D4 pins byte-identical evidence on rescans.

## Review record

- **Plan review**: 5 rounds (9 findings total across rounds 1–2
  incorporated; rounds 4–5 produced only plan-precision findings).
  Material design changes: the in-seam opt-out gate, the shared
  `ownerTypeFor` helper, `ProviderFactNormalizer.dynamicFact` as the
  reflection-fact wiring point, the `Path`-keyed diagnostics join,
  the two-criterion test-edit sanction, `index_absent` dropped as
  unreachable, seam-B signature stability, the split
  descriptor/caller abstention fields, the choke-point receiver-arm
  capture, the C8/D2 arm distinctions. Confidence 0.78 → 0.91.
- **Implementation review** (fresh Engram-grounded adversarial
  subagents each round): round 1, 8 findings (1 HIGH — the lost
  textual fallback when a correlated member's binary render abstains;
  1 MEDIUM — the producer-type key-family collision), all addressed
  in REVIEW_62_1; round 2, 3 findings + 1 NIT (1 MEDIUM — the failed
  index build mislabeled as per-member causes), all addressed in
  REVIEW_62_2 with the new D5/D6 cells; round 3 verified every
  incorporation and found 1 LOW (the duplicated INTEGRITY_CODES set),
  addressed in REVIEW_62_3 — **loop closed, no open findings**.

## Behavior facts discovered at GREEN (probe-verified)

- A same-ERASED-signature overload pair is illegal Java: javac
  collapses it to one index member, so the bijection's collision arm
  is not constructible end-to-end; `overload_group_mismatch` is
  reachable through sibling-count and reference-resolution mismatches
  (C7's comment-corrupted sibling).
- Consumable annotations (`@Deprecated`) do NOT trigger
  `repairTriggered`; only unconsumable spellings (`@Mar$k`) do.
- A multi-type-file owner BLOCKS the repair path entirely
  (`correlation.blocked`), so seam B is unreachable there.
- The producer seam's historical raw-extraction owner anchor names the
  NESTED type in the member identity for members declared after a
  nested type (`demo.ProducerConfig$Helper#build(1)`) — kept for
  behavior-neutrality and surfaced under its own
  `javacProducerTypeAbstention` family (round-1 finding 2).
- The javac error code for a missing package prefix is
  `compiler.err.doesnt.exist`, not `compiler.err.cannot.find.symbol`;
  C2 pins the `compiler.err.` family, not one code.
- A type declared immediately BEFORE a caller method breaks the
  textual `methodContaining` extraction anchor (the site is not
  extracted at all) — pre-existing textual behavior, out of scope;
  the multi-type fixtures nest the extra type AFTER the method.

- **CodeRabbit CR-1** (PR #72): 5 findings (2 Major, 3 Minor), all
  addressed red-first. Major 4709913078 — the `intactIndex` hard gate
  changed LOOKUP RESULTS for partial indexes (an integrity code with
  usable members): replaced with evidence-only silencing
  (`withSilencedEvidence` keeps the lookup result, suppresses the
  per-member evidence when `buildFailed()`); pinned by D7 (a missing
  second source root: the index-dialect descriptor survives, the
  abstention is silenced), mutation-verified red. Major 4709913104 —
  `T[][]` escaped the type-parameter guard (single-rank strip, a
  pre-existing #23 defect): every trailing `[]` rank is now stripped
  for the base-name test; pinned by D8/D9, mutation-verified red.
  Minor 4709913093 — unknown arity (-1) rendered as arity zero in the
  member identity: explicit `(*)` marker. Minor 4709913124 — D1 now
  requires the ServiceLoader facts to exist. Minor 4709913135 — D2
  gained flag-off twins for the repair, reflection-caller, and
  proxy-caller fixtures. PMD-forced extraction:
  `JavacFrameworkMemberLookup`.
- **CodeRabbit CR-2 through CR-4** (the rank-2 thread's verification
  rounds, each catching a subtler fixture defect in D9 — the guard
  itself was verified present from CR-3 on): D9 was reworked three
  times into its final form — the mirrored fixture uses the QUALIFIED
  crossed-bound shadowing form
  (`Mirror<List extends java.util.Set, Set extends java.util.List>`,
  erasures crossed and distinct; the unqualified bound refers to the
  type parameter per JLS 6.3 and clashes, and fully-crossed unqualified
  bounds cycle — both probe-verified), requires both facts to exist,
  and the Grid control verifies each declaration's correlated member
  both by its fact's descriptor and DIRECTLY through the correlation's
  `parameterTypes()`. The D2 twins sweep every `javac*Abstention*` key
  (CR-2). Final head 15495f8; all six review threads RESOLVED by
  CodeRabbit verification; CI green on every push.

## Residuals

- The index-diagnostics join is file-level (`MemberInfo` carries no
  position) and unique-owner-only; a member-level line join would need
  an additive position component — follow-up candidate.
- The parameter-arm capture records dead entries from the
  type-INFERENCE callers of `isDeclaredVariableBefore`
  (initializer-relative offsets that never match a site offset), and a
  site whose receiver type was transitively inferred from a
  parameter-initialized local gets no caller abstention
  (direct-admitting-arm scope).
- The producer-type seam's raw-extraction anchor (pre-existing) names
  a nested owner in the member identity for post-nested-type members;
  normalizing it would change the producer-type answer in
  anchor-divergence cases — deliberately out of scope (visibility-only
  mandate), a follow-up candidate with its own behavior review.
- `memberCount`-style guards and `JavacReflectionTargets`' own
  abstention ladder (#25 channel) are out of scope by design.

Confidence score: **0.90** — the channel is visibility-only with
zero golden churn on the full suite; every decline path is
mutation-pinned end-to-end; the blast-radius contract (never
`scan.diagnostics()`, never blocking DEFINITE promotion, silent on
failed builds, silent on opt-out) is pinned by D1/D2/D5 and verified
structurally in three review rounds; the open residuals are documented
and none affects an acceptance item.
