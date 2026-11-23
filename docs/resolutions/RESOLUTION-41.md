# RESOLUTION-41 — Reverse call graph through Spring Data repository methods

Issue: project-lightpath/java-analysis-adapter#41
Branch: `flpath-5079-repository-reverse-edges`
Plan: `docs/plans/PLAN-41.md` (v10 + implementation amendments)

## Status

- Item 0 (scanner substrate): DONE — RED_00 (cf6e585), GREEN_00 (2b4969f,
  5d122e6). Gates: adapter-test 560 ≥ 534, java-lint 0 new, evidence-validate
  pass.
- Item 1 (Edge 1, declared_invokes): DONE — RED_01 (b063cd9 + amendment),
  GREEN_01 (281ceac + PMD placement refactor). Gates: adapter-test 560 ≥ 534,
  java-lint 0 new, evidence-validate pass.
- Item 2 (Edge 2, delegation facts): in progress.
- Item 3 (promotion ladder + SemanticCallFacts.diagnostics carrier): pending.

## Evidence

### Item 0 — scanner substrate

- Per-contract, per-Boot-version guaranteed-method table replaces the
  `findById`-only injection (single source of guaranteed-ness;
  version-unparsable ⇒ nothing guaranteed, mirroring `.orElse(false)`).
- Reactive contracts classify as `repository_interface` (spring-data-commons
  2.7.18 contracts pinned from the jars).
- Fragment composition and canonical `@EnableJpaRepositories
  repositoryBaseClass` reading verified by
  `SourceMetadataScannerRepositoryContractTest` (6 cells).
- Plan-sanctioned characterization extensions: fact counts 9→39
  (`springRepositoryFactsRequireCanonicalContractsAndKnownMethods`) and
  2→38 (`fixtureSpecificFactIdsRequireUnambiguousFullIdentity`); all other
  assertions unmodified.

### Item 1 — declared-target provider edge

- `SootUpRepositoryDeclaredInvokes.edges` body-scans application methods for
  `JInterfaceInvokeExpr` whose declared receiver is an app-declared
  `repository_interface` candidate (SPRING_BOOT only); emits POSSIBLE
  `declared_invokes` edges, `TargetContext.application()`,
  `StmtPositionInfo` first-line numbers.
- Spike evidence (probes outside the tree): CHA emits **zero** provider facts
  for repository invokes with no implementor in view; `invokeinterface`
  constant-pool names the receiver's static type, including inherited
  methods (`javap -c` on the compiled fixture).
- ID stability: `declared_invokes` edges count and ID in a dedicated
  `sootup-declared-` namespace, never entering `applicationBaseIdCounts`;
  at a shared site (repository default method) the pre-existing
  `interface_dispatch` edge keeps its legacy `sootup-call-<hex>` ID
  (asserted byte-stable in
  `repositoryDefaultMethodKeepsInterfaceDispatchAndLegacyIds`).
- Implementation findings recorded in the plan: (a) the no-source-roots skip
  cell is structurally unreachable (`AnalysisContextInputModeValidator`
  requires a required production source root) — the
  `spring_data_repository_sources_unavailable` diagnostic is dropped;
  (b) nested repository interfaces are not classified (top-level-type-based
  candidates) — documented scanner residual, asserted as a bounded cell.
- Gates: `make adapter-test` 560 executions (baseline 534), `make java-lint`
  0 new findings (PMD placement refactor documented in GREEN_01), `make
  evidence-validate` pass.
