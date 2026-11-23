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
- Item 2 (Edge 2, delegation facts): DONE — RED_02 (b063cd9 line), GREEN_02.
  Gates: adapter-test 565 ≥ 534, java-lint 0 new, evidence-validate pass.
- Item 3 (ladder + diagnostics carrier): DONE — RED_03, GREEN_03 (+ PMD
  selection-split refactor). Gates: adapter-test 569 ≥ 534, java-lint 0
  new, evidence-validate pass.

All items complete; post-implementation review loop next.

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

### Item 2 — framework-model delegation ladder (default tier)

- `delegated_implementation` facts (`spring-data-delegation` rule, evidence
  kind `spring_data_backing_selection`): guaranteed CRUD →
  `SimpleJpaRepository#<m>`; derived queries → `PartTreeJpaQuery#execute`;
  fragment methods → the app-declared `Impl` class (bare-name caller
  identity, joining the `declared_invokes` displayName grammar); reactive
  contracts abstain UNRESOLVED targeting the stable `spring-data-proxy`
  identity (no reactive backing exists on a JPA classpath — module-family
  specific, pinned by Spike 5).
- Implementation finding: fragment methods inherited from the fragment
  interface are not enumerated by `springRepositoryMethods`; their
  delegation facts are emitted from the fragment composition list instead,
  and repository-declared fragment methods are routed there too (no
  double emission).
- Gates: adapter-test 565 executions, java-lint 0 new, evidence-validate
  pass.

### Item 3 — promotion ladder and diagnostics surface

- FLPATH-5075 ladder on `delegated_implementation` facts: guaranteed CRUD
  is DEFINITE when `spring-data-repository-contract` metadata is observed
  and no override mechanism is present; an in-scope `repositoryBaseClass`
  backs CRUD as POSSIBLE (target = the configured class); an out-of-scope
  `repositoryBaseClass` abstains UNRESOLVED targeting the configured name.
  Derived queries stay POSSIBLE (query-lookup strategy not proven);
  fragments and reactive contracts unchanged from Item 2.
- `SemanticCallFacts.diagnostics` (new public surface, `Diagnostic(code,
  path, detail)`, sorted, serialized as an additive `diagnostics` key under
  schemaVersion 0.1.0, threaded through the SOURCE_ARCHIVE re-wrap):
  computed once in `enrich` from the derived context
  (`springDataDiagnostics`); `spring_data_backing_selection_out_of_scope`
  is the first code.
- Overload-preserving 4-arg `ProviderFactNormalizer.normalize` appends the
  diagnostics to the ServiceLoader `Scan` (`withAdditionalDiagnostics`), so
  `isUnique`'s DEFINITE block is scan-global while service_loader edges
  exist. Stated blast radius: while the diagnostic is present, ServiceLoader
  DEFINITE promotion is blocked for the whole scan — asserted by the
  differential control in `diagnosticsBlockServiceLoaderDefinitePromotion`.
- Gates: adapter-test 569 executions, java-lint 0 new, evidence-validate
  pass.

## Reverse chain delivered (issue #41 acceptance)

`SimpleJpaRepository#save` (dependency public symbol)
←(definite, delegated_implementation) `AccountRepository#save`
←(possible, declared_invokes) `AccountService#create`
←(definite, invokes) `AccountController#create`
←(framework entrypoint) HTTP route — repository symbols are no longer
dead ends; the attestation-manifest start node (class+method+descriptor,
ADR-0057 follow-up) can join this grammar without redesign.

