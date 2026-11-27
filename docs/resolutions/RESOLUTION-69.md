# RESOLUTION-69 — Resource lane: explicit selection overrides root-scope exclusion, labeled

Issue: project-lightpath/java-analysis-adapter#69 (filed from PLAN-68's
round-2 retraction; the lane's three-arm inventory is in #69's
amendment comment).
Branch: `issue-69-resource-lane-scope` from `origin/main` @ `64f6b5d`.
Commits: PLAN_69 (+2 plan-review rounds + GREEN-state amendments),
RED_69 `35b871b`, GREEN_69 `44437cd`, REVIEW_69_1 `0b3b08e`,
REVIEW_69_2, EVIDENCE_69.

## The decision (recorded in the code, not just the docs)

**Explicit OBSERVED selection overrides root-scope exclusion BY
DESIGN, and everything seen carries its provenance label.** The
owner's rationale: when a CVE lands in a test library, the analysis
must be able to answer "is this reachable from production code?" —
which requires SEEING the selected location. Exclusion (#68)
governs what is PUBLISHED as production facts; explicit selection
governs what can be SEEN for investigation. The decision record
lives on `authorizedPath`, `selectedLocations`, and
`explicitlySelected` (REVIEW_69_1); the principle extends to #71
(re-scoped to multi-scope analysis — see #71's re-scope comment).

## What changed

- **`DescriptorOrigin(mechanism, rootScope)`** — an additive fold on
  `Descriptor` (7 components; auxiliary 6-arg constructor keeps every
  existing construction site compiling; `DescriptorIdentity` equality
  excludes origin, so the resolve-time merge is unchanged).
- **The admission mechanism is threaded from the `Reader.scan`
  arms**: `application_classes`, `included_root` (the per-root
  resources sweep), `dependency_entry`, `selected_metadata` (the
  observed-metadata arm — including every file inside a declared
  directory tree: the ARM THAT SCANNED the file is the mechanism,
  the plan-review round-1 decision). Back-compat overloads delegate
  with `unclassified` (defensive; unreachable in production —
  verified by the implementation review's caller trace).
- **`rootScopeLabel`**: the most-specific DECLARED root's
  `<scope>/<included|excluded>` for real paths (#68's specificity
  order, labeling role only); absent for paths under no declared
  root. The archive pipeline is mechanism-ONLY by construction
  (synthetic `archive!/entry` paths carry no root scope; the
  pipeline never calls `rootScopeLabel`).
- **`descriptorEvidence`** emits `descriptorProvenance` (always) and
  `descriptorRootScope` (when present).
- **ArchiveSource keeps exactly two scan methods** (delegator +
  config-and-mechanism body): its GodClass finding is baselined with
  the WMC embedded in the fingerprint message, so the class's method
  count — and therefore WMC — is preserved by design; `readModule`
  stays at 8 parameters (under the >8 threshold). The first GREEN
  attempt added two overloads (+2 WMC) and broke the baseline match;
  the fix removed them (the Reader passes the defaults config
  directly) and updated the six explicit-config test call sites in
  `ServiceLoaderSeekableZipTest` (sanctioned mechanical edit,
  assertions untouched).
- New `ServiceLoaderDescriptorProvenanceTest` (8 cells: P1, P2, P2b,
  P3, P4, P4b, P5, P6). Makefile floor 717 → 725.

## GO evidence (gates, this host, JDK 27)

- `make adapter-test`: **725 executions** (default=611, large=1,
  sootup=113), zero failures — 717 baseline + the 8 new cells. RED
  receipt: the 7 original label assertions failed (the scan/merge
  assertions were green — the override was already the behavior;
  pinning it green is part of the decision record); everything else
  green across all three lanes.
- `make java-lint`: PASS — after the GodClass fingerprint
  restoration (method count preserved; the first attempt's +2 WMC
  broke the baseline match, caught by the gate and fixed).
  **JDK 17 verification (#64)**: PASS on helios08 with `~/jdk17`
  (17.0.21.1) over a `git archive` scratch checkout.
- `make evidence-validate`: pass.

## Mutation checks (red-first)

- Remove the label emission from `descriptorEvidence` ⇒ all 8 cells
  fail (8/8 red, verified).
- Thread the wrong mechanism into the metadata arm
  (`selected_metadata` → `included_root`) ⇒ the selected-metadata
  cells fail (1/8 red, verified — the mechanism assertions are
  mutation-sensitive).
- Reverse `rootScopeLabel`'s specificity order (least-specific
  governs) ⇒ the scope-labeled cells fail (2/8 red, verified — P2's
  `test/included` and P1's `production/excluded` both flip under the
  reversed order).

## Pinned behaviors worth naming

- **P3**: the per-root sweep's services tree is a SIBLING of the
  declared source root — `included_root` mechanism, NO root-scope
  label (the plan's original `production/included` expectation was
  wrong; corrected at GREEN).
- **P6**: the resolve-time `DescriptorIdentity` merge can DISCARD a
  scope label — with same-content selected-excluded and classpath
  twins (both classpath-backed), the classpath twin wins (its
  absolute display path sorts first) and the excluded twin's label
  is dropped. Deterministic but not label-cautious; pinned as the
  documented residual (plan-review round 2 finding 4). Making the
  merge label-aware is a follow-up candidate if ever needed.

## Review rounds

- Plan review: 2 rounds (6 findings → 0), plus direct Engram-tool
  verification after two subagent-dispatch cancellations (method
  recorded in the plan). Round 1's HIGH findings forced the
  admission-site threading design (the ladder-mirror was
  unimplementable) and the directory-arm mechanism decision.
- Implementation review: round 1 found 4 addressable findings
  (2 MEDIUM — the missing decision-record javadoc at its named
  surfaces, the unpinned dependency-entry cell; 1 LOW — the P5
  GREEN-state correction unrecorded; 1 NIT), all addressed in
  REVIEW_69_1; round 2 verified all four with one NIT (the
  root-scope when-present wording), folded into REVIEW_69_2.

## Residuals

- `unclassified` is defensive and unreachable in production (the
  implementation review traced every caller); visible in the
  attribute if ever reached, never silent.
- The P6 merge label-discard (above).
- The `#71` follow-up (multi-scope analysis, scope-consistent
  contexts, scope-tagged dependency resolution) is re-scoped and
  deferred by the owner's SBOM-scope reasoning (see #71's re-scope
  comment).
- `rootScopeLabel` re-sorts the declared roots per call (descriptor
  counts are retention-budget-bounded; negligible).

Confidence score: **0.90** — the decision is recorded at its code
surfaces, the current behavior was traced at both reachable arms
before implementation, the change is labeling-only (no gating
semantics), all three mutation checks are red-verified, and the
review loop's two MEDIUM findings (documentation and matrix
coverage) were closed in-round. Residual risk: the P6 merge's
label-discard is pinned but not fixed (deliberate — the merge
preference is pre-existing pinned behavior), and the GodClass
fingerprint constraint makes ArchiveSource sensitive to future
method additions (documented in the plan's design note).
