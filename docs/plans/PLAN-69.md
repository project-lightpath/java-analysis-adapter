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
- **The label surface (corrected in plan-review round 1).** The
  mechanism CANNOT be derived by re-walking `authorizedPath`'s ladder
  on the file path: only `scanObservedMetadata` runs that gate; the
  per-root sweep scans a SIBLING subtree of the included root (not
  under it); archive descriptors carry SYNTHETIC
  `archive!/entry` paths that defeat `toRealPath`. The admission
  mechanism is known only at the ADMISSION SITE — the arms of
  `ServiceLoaderDescriptorReader.scan` (applicationClasses at :51,
  the per-root sweep at :57-62, dependency entries at :74-79, the
  observed-metadata ladder at :107) — and must be THREADED down
  through `scanSelectedEntry`/`scanDirectory`/`addFile`/
  `addModuleBytes`/`addDescriptor` as a parameter. The root-scope
  label is computed from the real path at the construction sites
  (synthetic archive paths get no root-scope label). The `#26`-style
  additive-component pattern (`MemberInfo.publicMember`, #25) keeps
  every reader compiling via an auxiliary 6-arg constructor delegating
  with `"unclassified"` (records cannot default arguments; four
  existing 6-arg construction sites — Source:161, Source:237,
  ArchiveSource:343, and JavacServiceLoaderSiteResolverTest:195 —
  keep compiling; the test descriptor then carries
  `descriptorProvenance=unclassified`, verified harmless at RED).

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
2. **Provenance labels on every descriptor (mechanism threaded from
   the admission site).** A package-private mechanism constant (String
   or enum: `application_classes`, `dependency_entry`,
   `included_root`, `selected_metadata`) is threaded from each arm of
   `ServiceLoaderDescriptorReader.scan` through
   `scanSelectedEntry`/`scanDirectory`/`addFile`/`addModuleBytes`/
   `addDescriptor` into the construction sites:
   - `applicationClasses` arm (Reader:51);
   - the per-root `META-INF/services` sweep (Reader:57-62) —
     `included_root` (the sweep subtree is a sibling of the root;
     the arm itself is the only truth);
   - dependency entries (Reader:74-79) — `dependency_entry` (covers
     archive descriptors with synthetic paths);
   - the observed-metadata arm (Reader:104-143) —
     `selected_metadata`, for BOTH the declared file and every file
     inside a declared directory tree (the arm admitted them; being
     additionally under an included root does not change who scanned
     them — the plan-review round-1 decision on the directory arm).
   `Descriptor` gains an additive `String provenance` component with
   an auxiliary 6-arg constructor delegating with `"unclassified"`
   (defensive; visible in the attribute, never silent).
   `descriptorEvidence` emits two additive attributes:
   - `descriptorProvenance` — the threaded mechanism;
   - `descriptorRootScope` — `<scope>/<included|excluded>` of the
     MOST-SPECIFIC declared root containing the descriptor's REAL
     path (`ServiceLoaderDescriptorProvenance.rootScopeLabel(context,
     absolutePath)`, #68's `declaredRootsBySpecificity` order,
     labeling role only), absent for synthetic archive paths and
     paths under no declared root.
   The EvidenceCache is an `IdentityHashMap` (per-instance, not
   equality-keyed) — unaffected by the additive component; the
   equality consumer is `DescriptorIdentity` (Scanner:307-319), which
   already excludes path/kind and will exclude provenance — see item
   3's P6 for the merge consequence.
3. **Test class `ServiceLoaderDescriptorProvenanceTest`** (plain
   `@Test`; no flag writes — the resource lane is flag-independent):
   - **P1 (label RED; scan GREEN today): the fallback arm.** OBSERVED
     metadata FILE inside an EXCLUDED generated root that is NOT
     under any included root, applicationClasses, or dependency entry
     (geometry pinned per plan-review round 1 — only then does the
     `explicitlySelected` fallback fire): the descriptor IS scanned
     (the override, pinned green), and its evidence carries
     `descriptorProvenance=selected_metadata` and
     `descriptorRootScope=production/excluded` (RED — the attributes
     do not exist).
   - **P2 (label RED; scan GREEN today): the directory arm.** OBSERVED
     metadata DIRECTORY under an included root whose tree contains a
     nested TEST-scope root: the nested root's descriptor IS scanned
     (green today — the included-roots arm admits the directory and
     `scanDirectory` sweeps the tree), labeled
     `descriptorProvenance=selected_metadata` (the observed-metadata
     arm scanned it; additionally being under an included root does
     not change who admitted it — the round-1 decision) and
     `descriptorRootScope=test/included`.
   - **P2b (label RED; the round-1 geometry cell).** OBSERVED metadata
     FILE inside an excluded root that IS nested under an included
     root: admitted (the included-roots containment has no exclusion
     check), mechanism still `selected_metadata` (the metadata arm
     scanned it), `descriptorRootScope=production/excluded` — the
     label distinguishes what the admission geometry cannot.
   - **P3 (label RED): the sweep control.** The per-root
     `META-INF/services` sweep under an included root (the
     `nestedServiceTypesResolveAtCallSite` fixture shape):
     `descriptorProvenance=included_root`,
     `descriptorRootScope=production/included`.
   - **P4 (label RED): the entry control.** A dependency-entry (or
     applicationClasses) descriptor: `descriptorProvenance=
     dependency_entry` (or `application_classes`), no
     `descriptorRootScope` attribute (synthetic archive paths get
     none; real entry paths under no declared root get none).
   - **P5 (label RED): end-to-end.** The P1 fixture through the full
     scan → resolution path: the provider fact's evidence carries
     both attributes (the label reaches the consumer, not just the
     descriptor record).
   - **P6 (label RED; the merge consequence).** Two same-content
     descriptors — one from a selected location inside an excluded
     root, one classpath-backed — merge in `selectedDescriptors`
     (keyed by `DescriptorIdentity`, which excludes provenance; the
     existing pinned behavior prefers the classpath-backed twin,
     `ServiceLoaderCoverageTest.identicalSourceAndOutputDescriptors
     AreMergedPreferringClasspath`): the survivor carries the
     classpath twin's `dependency_entry` label. Pinned as DOCUMENTED
     behavior (the merge preference is pre-existing and sanctioned;
     the excluded-scope twin's label does not survive the merge) —
     the residual that the merge can discard a scope label, recorded
     with the cell rather than changed.
   - Pairwise (AGENTS): mechanism × root-scope (P1/P2b excluded vs
     P2 test vs P3 included), mechanism × path kind (file P1 vs
     directory P2), explicit-vs-sweep (P1/P2/P2b vs P3), entry (P4),
     merge × label (P6).
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
- **RED_69**: `ServiceLoaderDescriptorProvenanceTest` (P1–P6).
  Expected-RED: the six label assertions (the scan assertions in
  P1/P2/P2b are green today — the override is the current behavior,
  and pinning it green is part of this issue's decision record). Full
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
- `unclassified` is a defensive value; if reachable it indicates an
  admission site missed by the threading — visible in the attribute
  (never silent), recorded as a residual if observed.
- The `selectedDescriptors` merge (`DescriptorIdentity` excludes
  provenance) can discard an excluded-scope twin's label when
  same-content descriptors merge — P6 pins the documented behavior;
  changing the merge key is out of scope.

## Plan-review record

Round 1 (Engram-grounded adversarial subagent, CodeRabbit style): 6
addressable findings (2 HIGH, 2 MEDIUM, 2 LOW), all incorporated:
1. HIGH — the ladder-mirror labeling was unimplementable for 3 of 5
   mechanisms (only the metadata arm runs authorizedPath; the sweep
   subtree is a sibling of the root; archive paths are synthetic).
   The mechanism is now threaded from the admission sites.
2. HIGH — the directory arm's mechanism decided: `selected_metadata`
   (the arm that scanned the file), regardless of additional
   containment under an included root.
3. MEDIUM — P1's geometry pinned (the excluded root must be beside,
   not under, any included root for the fallback to fire); P2b added
   (excluded root under an included root).
4. MEDIUM — P6 merge cell: DescriptorIdentity excludes provenance, so
   the classpath-preferred merge discards the excluded twin's label —
   pinned as documented behavior.
5. LOW — auxiliary 6-arg constructor specified (records cannot
   default arguments); the direct test construction at
   JavacServiceLoaderSiteResolverTest:195 carries `unclassified`.
6. LOW — the EvidenceCache is an IdentityHashMap; the equality
   consumer is DescriptorIdentity (rationale corrected).

Round 2 (delta-only, performed directly with the Engram MCP tools
after two subagent-dispatch cancellations — method recorded
honestly): 0 new findings. Verified: the threading chain is a closed
caller set with one parameter per hop (Reader arms ->
scanClasspathEntry/scanDirectory -> scanSelectedEntry ->
scanServiceDirectory/readDescriptors/readModule -> addServiceFile/
addDescriptor/addModuleBytes); P6's merge pin exists
(ServiceLoaderCoverageTest:563-618); the direct test Descriptor at
JavacServiceLoaderSiteResolverTest:195 asserts certainty only (no
evidence attributes — `unclassified` harmless); P2b's scan is green
today (Authorization:87-91 is pure containment); P1's geometry is
Builder-constructible.

Confidence score: **0.88** (0.85 → 0.88 across plan review) — the decision is recorded and the current
behavior traced at both reachable arms; the change is labeling-only
(no gating semantics), which bounds the risk to mislabeling; the
ladder-sharing mitigation and P1–P4's mechanism matrix are the main
guards. Residual risk: the Descriptor equality change's cache
interaction is reasoned, not yet measured, and the three construction
sites' context availability (absolute path + dependency entries at
`addModuleBytes`) must be verified at RED.
