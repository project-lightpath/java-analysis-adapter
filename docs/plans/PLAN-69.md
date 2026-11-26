# PLAN-69 — Resource lane: explicit selection overrides root-scope exclusion, labeled

Issue: project-lightpath/java-analysis-adapter#69 (filed from PLAN-68's
round-2 retraction; the lane's three-arm inventory is in #69's
amendment comment). Follow-up #71 (advisory visibility for source
symbols) is explicitly NOT this issue.
Branch: `issue-69-resource-lane-scope` from `origin/main` @ `64f6b5d`
(the PR #70 merge — #68's walker exclusion is on main).

## Decision (the owner's answer to #69's question)

**Explicit selection WINS over root-scope exclusion — and everything
seen carries its provenance/scope label.** The owner's rationale: when
a CVE lands in a test library, the analysis must be able to answer
"is this reachable from production code?" — which requires SEEING the
test code, not blindness. The principle, recorded for #71 too:

> Exclusion governs what we PUBLISH as production facts (#68's walker
> exclusion stands). Explicit selection governs what we can SEE for
> investigation — and everything seen must carry its
> provenance/scope label.

The current code ALREADY behaves this way by accident
(`authorizedPath`'s `explicitlySelected` fallback re-authorizes
OBSERVED metadata paths inside excluded/out-of-scope roots). This
issue makes the behavior a DECISION: documented, labeled, and pinned —
so the override can never silently include anything (the label is the
safety valve), and no scan-global diagnostic is added (nothing is
silently included; the blast-radius statement in AGENTS is satisfied
by not touching `scan.diagnostics()` semantics).

## Preflight (traced on this main; probe at RED)

- **The fallback arm works today.** `authorizedPath` (Authorization:
  81-98): applicationClasses → included roots → dependency entries →
  `explicitlySelected` (Provenance:44-47, `sameRealPath` self-match
  against `selectedLocations` built from the same OBSERVED
  `service_loader_descriptor` declaredPaths). A metadata FILE inside
  an excluded nested root is therefore scanned today — unlabeled.
- **The directory arm works today.** A metadata DIRECTORY under an
  included root passes the included-roots loop (no fallback needed);
  `scanDirectory` (Reader:189-211) walks the whole tree including
  nested excluded/out-of-scope roots' descriptor files — unlabeled.
  (`classpathBacked` Provenance:55-69 already treats
  under-a-selected-location as backed, so these descriptors can earn
  certainty — another reason the label matters.)
- **The sweep arm is geometrically unreachable** for nested roots
  (the per-root `META-INF/services` directory is a SIBLING of the
  root path) — not a manifestation; unchanged.
- **The label surface.** `Descriptor` (Scanner:276-282) carries
  `serviceType/providers/path/fingerprint/kind/malformedEntries`;
  `descriptorEvidence` (Scanner:186-208) builds the fact evidence
  context-free. Three construction sites hold the absolute path and
  the admission context: `ServiceLoaderDescriptorSource.addServiceFile`
  (the services files — sweep, selected metadata, selected entries),
  `ServiceLoaderDescriptorArchiveSource.addDescriptor` (classpath
  entries/archives), `ServiceLoaderDescriptorSource.addModuleBytes`
  (module-info). The `#26`-style additive-component pattern
  (`MemberInfo.publicMember`, #25) keeps every reader compiling.

## Scope (binding)

Production sources in `src/main`, one new test class. Zero gating
changes; zero new diagnostics; zero edits to existing tests.

1. **Document the decision.** Javadoc on
   `ServiceLoaderDescriptorAuthorization.authorizedPath`,
   `ServiceLoaderDescriptorProvenance.explicitlySelected`, and
   `selectedLocations`: explicit OBSERVED selection overrides
   root-scope exclusion BY DESIGN (the owner's CVE-reachability
   rationale; #68 excluded test code from the DEFAULT fact scope, not
   from explicit investigation), and the override is safe because
   every descriptor it admits is provenance-labeled (item 2).
2. **Provenance labels on every descriptor.** `Descriptor` gains an
   additive `String provenance` component (hand-written accessor via
   a compact constructor default of `"unclassified"` so all existing
   construction paths compile unchanged), computed at the three
   construction sites through one new helper:
   `ServiceLoaderDescriptorProvenance.describe(context, absolutePath,
   dependencyEntries)` returning the ADMISSION MECHANISM, mirroring
   `authorizedPath`'s ladder order exactly:
   `application_classes` | `dependency_entry` | `included_root` |
   `selected_metadata` | `unclassified` (defense; unreachable while
   the ladder is walked in order).
   `descriptorEvidence` emits two additive attributes:
   - `descriptorProvenance` — the mechanism;
   - `descriptorRootScope` — `<scope>/<included|excluded>` of the
     MOST-SPECIFIC declared root containing the path (#68's
     `declaredRootsBySpecificity` order, labeling role only), absent
     when the path is under no declared root (dependency entries,
     application classes outside roots).
   The EvidenceCache key (`descriptor, selection`) already
   differentiates by Descriptor; the additive component changes
   equality — safe: the cache is per-scan and the label is a function
   of the same descriptor's path.
3. **Test class `ServiceLoaderDescriptorProvenanceTest`** (plain
   `@Test`; no flag writes — the resource lane is flag-independent):
   - **P1 (label RED; scan GREEN today): the fallback arm.** OBSERVED
     metadata FILE inside a nested EXCLUDED generated root: the
     descriptor IS scanned (the override, pinned green), and its
     evidence carries `descriptorProvenance=selected_metadata` and
     `descriptorRootScope=production/excluded` (RED — the attributes
     do not exist).
   - **P2 (label RED; scan GREEN today): the directory arm.** OBSERVED
     metadata DIRECTORY under an included root whose tree contains a
     nested TEST-scope root: the nested root's descriptor IS scanned,
     labeled `descriptorProvenance=selected_metadata` (inside a
     selected location) and `descriptorRootScope=test/included`.
   - **P3 (label RED): the sweep control.** The per-root
     `META-INF/services` sweep under an included root (the
     `nestedServiceTypesResolveAtCallSite` fixture shape):
     `descriptorProvenance=included_root`,
     `descriptorRootScope=production/included`.
   - **P4 (label RED): the entry control.** A dependency-entry (or
     applicationClasses) descriptor: `descriptorProvenance=
     dependency_entry` (or `application_classes`), no
     `descriptorRootScope` attribute.
   - **P5 (label RED): end-to-end.** The P1 fixture through the full
     scan → resolution path: the provider fact's evidence carries
     both attributes (the label reaches the consumer, not just the
     descriptor record).
   - Pairwise (AGENTS): mechanism × root-scope (P1 excluded vs P2
     test vs P3 included), mechanism × path kind (file P1 vs
     directory P2), explicit-vs-sweep (P1/P2 vs P3), entry (P4).
4. **Makefile floor raise** to the observed post-fix count
   (717 + new cells).

## Non-goals

- Any gating change — the override IS the decision; only labels are
  added.
- #71 (advisory visibility for source symbols / the javac index) —
  the principle's extension beyond the resource lane.
- The sweep arm (geometrically unreachable for nested roots).
- `classpathBacked`'s certainty semantics (a selected-location
  descriptor earning certainty is the existing, now-labeled, behavior;
  changing it is out of scope).

## Certainty model

- Descriptor admission is UNCHANGED (all three arms keep today's
  reachability); the only behavior delta is two additive evidence
  attributes.
- An explicitly-selected descriptor inside an excluded/out-of-scope
  root is always identifiable downstream by its
  `descriptorRootScope` — silent inclusion is impossible by
  construction (the attribute is emitted from the descriptor's own
  path, not from a caller-supplied claim).
- Zero new diagnostics, zero scan-global blast radius (stated per
  AGENTS: `scan.diagnostics()` untouched).

## TDD sequence

- **PLAN_69** (this file): committed first.
- **Plan review**: Engram-grounded adversarial subagent, CodeRabbit
  style; loop until no new addressable findings.
- **RED_69**: `ServiceLoaderDescriptorProvenanceTest` (P1–P5).
  Expected-RED: the five label assertions (the scan assertions in
  P1/P2 are green today — the override is the current behavior, and
  pinning it green is part of this issue's decision record). Full
  gates at RED.
- **GREEN_69**: the javadoc decision record (item 1), the additive
  `Descriptor.provenance` component + `describe` helper + the two
  evidence attributes (item 2). Full gates; helios08 JDK 17 lint if
  any doubt (#64).
- **Implementation review**: a NEW Engram-grounded adversarial
  subagent; findings red-first; loop until clean.
- **EVIDENCE_69**: `docs/resolutions/RESOLUTION-69.md` — the decision
  record, probe receipts (fallback + directory arms scanned today,
  unlabeled), gate counts, mutation checks (remove the label emission
  ⇒ P1–P5 red; mislabel by skipping the ladder order ⇒ P3/P4 red),
  residuals.

Gates after every item: `make adapter-test` (count never below 717;
floor raised to the GREEN count), `make java-lint` (zero new
findings), `make evidence-validate`.

## Residuals / risks

- `describe` walks `authorizedPath`'s ladder a second time (once to
  gate, once to label); a divergence between the two walks would
  mislabel, not mis-admit — mitigated by sharing one helper that both
  gate and label consult where practical, and pinned by P1–P4's
  mechanism assertions.
- The `Descriptor` equality change (additive component) affects the
  EvidenceCache and any Descriptor-set semantics; the cache is
  per-scan and label-inclusive keys are strictly finer — audited at
  GREEN.
- `unclassified` is a defensive value; if reachable it indicates a
  construction site missed by the ladder — visible in the attribute
  (never silent), recorded as a residual if observed.

Confidence score: **0.85** — the decision is recorded and the current
behavior traced at both reachable arms; the change is labeling-only
(no gating semantics), which bounds the risk to mislabeling; the
ladder-sharing mitigation and P1–P4's mechanism matrix are the main
guards. Residual risk: the Descriptor equality change's cache
interaction is reasoned, not yet measured, and the three construction
sites' context availability (absolute path + dependency entries at
`addModuleBytes`) must be verified at RED.
