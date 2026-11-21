# Can rgctl generate a deterministic call graph for a Java application that uses DI/IoC?

Date: 2026-11-21. Raw artifacts under [`artifacts/`](artifacts/), fixture under
[`fixture/`](fixture/). Scope: rgctl only, content of its graph only — output
format is irrelevant.

## Verdict

**rgctl is deterministic, but its Java call graph is unsound under DI/IoC and
dynamic execution: it misses executed edges on 8 of 10 scenarios (recall
failures), reports provably-executed methods as having zero callers and zero
impact (false "unreachable / not affected"), and exposes no uncertainty status
for any of it.** It passes the two control scenarios and one dynamic scenario
(event-bus method reference), and with Spring annotations present it does emit
`injects`/`exposesroute` framework-extraction edges — but those edges stop at
the constructor-parameter interface (or the injected field's type), are
excluded from blast-radius traversal by implementation, and never resolve the
injected implementation. Crucially, **this is a documented, by-design
boundary, not an incidental bug**: rgctl's own design docs at the pinned
commit state that blast-radius follows static Calls edges only, that
reflection and runtime-configured dispatch "are not connected", that "no full
points-to, no reflection" honesty limits apply to Tier-1 Java, and that
DI-heavy frameworks "still produce sparse `Calls` edges for interface-typed
receivers" (issue #75) — quotes with file:line in the section below. So the
claim is refined, not absolute: rgctl is a fast, deterministic, *syntactic +
framework-heuristic* Java graph that behaves exactly as documented; it is not
a call graph of what executes, and its empty answers cannot be trusted as
"not affected" under DI/IoC.

## Provenance

| Item | Value |
|---|---|
| rgctl | v0.4.30, official release binary (`curl -fsSL https://rgctl.io/install.sh \| sh`, aarch64-apple-darwin), binary sha256 `f0879aad6d6fb7330ad5d73bb8851ea3c391c06dcd2952d346f0c43e2eeecea9`, `rgctl --version` → `rgctl v0.4.30`. Source pinned at commit `b5a530072462a7cd497847b0185852babb0403ac` (2026-11-20), which is exactly tag `v0.4.30` — the tested binary and the cited docs are the same release. Doc-quote verification with file:line: [`artifacts/rgctl-docs-b5a5300-verification.txt`](artifacts/rgctl-docs-b5a5300-verification.txt) |
| Fixture | [`fixture/src/`](fixture/src/), 21 Java files, `javac --release 21` on OpenJDK 27.0.1 (no JDK 21 runtime on this host; bytecode level pinned to 21). Framework-neutral DI: `@Binding` qualifier + constructor injection + a mini container — the same runtime resolution step as Spring `@Qualifier` / Quarkus `@Named` |
| Ground truth | Hand instrumentation: every method that matters starts with `Trace.log("scenario:Class.method")`; truth is the runtime trace only ([`artifacts/ground-truth/`](artifacts/ground-truth/)), never derived from rgctl |
| Spring-annotation probe | Separate index-only source ([`artifacts/spring-probe/src/`](artifacts/spring-probe/src/)) with `@Service`/`@Autowired`/`@Qualifier`/`@RestController`/`@GetMapping`, to answer "which DI edges does rgctl capture, and how" |

Exact commands (from a copy of `fixture/src`):

```sh
rgctl discover                                      # 0.60 s (run1) / 1.12 s (run2), 236 nodes, 546 edges
rgctl -f json callees run --class ScenarioX --depth 4     # per scenario entry point
rgctl -f json callees raise --class AlertService --depth 3  # intermediate dispatch sites
rgctl -f json blast-radius 'EmailNotifier::send'    # per leaf method
rgctl -f json relations --edge injects              # Spring probe: 2 edges
rgctl -f json relations --edge exposesroute         # Spring probe: 1 edge
rgctl -r <repo> -f json blast-radius 'EmailNotifier::send' --classify-boundary --boundary REST_ENDPOINT
```

Raw JSON retained in [`artifacts/rgctl/`](artifacts/rgctl/),
[`artifacts/counterclaim/`](artifacts/counterclaim/),
[`artifacts/determinism/`](artifacts/determinism/). Per-query wall time was
sub-100 ms; indexing dominated.

## Fixture and ground truth

| # | Scenario | Executed edge sequence (trace-confirmed) | Never executes (control for invented edges) |
|---|---|---|---|
| 1 | direct static call (**control**) | `run → StaticHelper.directCall → helper` | — |
| 2 | single-impl interface (**control**) | `run → GreeterClient.render → SimpleGreeter.greet` | — |
| 3 | DI-selected impl (2 impls, qualifier `email`, constructor injection) | `run → MiniContainer.resolve`; `run → AlertService.raise → EmailNotifier.send` | `SmsNotifier.send` |
| 4 | ServiceLoader (`META-INF/services`) | `run → AuditPlugin.run` | — |
| 5 | reflection (`Class.forName`/`getMethod`/`invoke`) | `run → SecretHandler.handle` | — |
| 6 | dynamic proxy (`Proxy` + `InvocationHandler`) | `run → LoggingHandler.invoke → RealEcho.echo` | — |
| 7 | lambda / method reference (invokedynamic) | `run → $lambda$0 → ScenarioLambda.lambdaBody` | — |
| 8 | event bus (listener registration) | `run → AuditListener.register → EventBus.subscribe`; `run → EventBus.publish → AuditListener.onDeployFailed` | `MetricsListener.onDeploySucceeded` |
| 9 | abstract template-method hook | `run → AbstractJob.run → before/NightlyJob.doWork/after` | — |
| 10 | handler registry (static-init map) | `run → CommandRegistry.dispatch → AuditCommand.execute` | `CleanupCommand.execute` |

## Per-scenario matrix (truth vs rgctl v0.4.30)

rgctl result = `callees` from the scenario entry point (depth 4) plus
`blast-radius` of each leaf. "Missing" = executed edge absent; "invented" =
emitted edge that never executes.

| # | Scenario | Executed edges present | Missing dynamic edges | Invented edges | Result |
|---|---|---|---|---|---|
| 1 | direct (control) | `run→directCall`, `directCall→helper` | none | none | **PASS** |
| 2 | single impl (control) | none | `run→render`, `render→greet` | none | FAIL |
| 3 | DI select | `run→raise`, `run→resolve` | `raise→EmailNotifier.send` — rgctl emits the abstract `Notifier.send` node, not the executing impl | none | FAIL |
| 4 | ServiceLoader | none | `run→AuditPlugin.run` | none | FAIL |
| 5 | reflection | none | `run→SecretHandler.handle` | none | FAIL |
| 6 | proxy | interface node `Echo.echo` only | `→LoggingHandler.invoke`, `invoke→RealEcho.echo` | none | FAIL |
| 7 | lambda | synthetic `$lambda$0` node | `$lambda$0→lambdaBody` | none | PARTIAL |
| 8 | event bus | `run→register`, `run→publish`, `publish→onDeployFailed`, `register→subscribe` | none | none (`MetricsListener` correctly absent) | **PASS** |
| 9 | template method | from `AbstractJob.run`: `→before/doWork/after` (abstract nodes) | `run→AbstractJob.run`; `doWork→NightlyJob.doWork` | none | FAIL |
| 10 | registry | `run→dispatch` | `dispatch→AuditCommand.execute` (abstract `Command.execute` node instead) | none | FAIL |

No invented edges were observed in any scenario (precision holds on what it
does emit; the failure mode is recall + false unreachability, not
false-positive edges).

Every one of these **provably executed** methods was reported by
`blast-radius` as `direct_callers_count: 0, impact_zone_size: 0`
([`artifacts/rgctl/br-*.json`](artifacts/rgctl/)):

```
EmailNotifier::send, SimpleGreeter::greet, AuditPlugin::run, SecretHandler::handle,
RealEcho::echo, LoggingHandler::invoke, NightlyJob::doWork, AuditCommand::execute,
AbstractJob::run   <- invoked by plain static syntax: new NightlyJob().run()
```

`AuditListener::onDeployFailed` was the only dynamic leaf with callers
(1, via the method reference passed to `EventBus.subscribe`).

### Exposed uncertainty

None. Every `callees` payload is `{schema_version, callees, total}` with plain
symbol records; every `blast-radius` payload is `{target, metrics, topology,
gatekeeping}`. Searching all outputs for `possible`, `ambiguous`,
`unresolved`, `uncertain`, `unknown`, `candidate` finds zero occurrences. An
empty `topology.direct_callers` carries no abstention marker, so nothing
blocks the false "unreachable / not affected" reading. rgctl's uncertainty
model exists only at the CVE-verdict level (`vuln analyze` → `unknown`), not
in the call-graph queries.

## Determinism

Two independent tests, same rgctl v0.4.30, same source snapshot:

1. **Same directory, `.rgctl` deleted, fresh `discover`:** all 13 re-run
   queries (10 `callees`, 3 `blast-radius`, `status`) byte-identical — 13/13
   sha256 matches.
2. **Two fresh copies of the source in different directories:** `relations
   --edge calls` returns 69 edges in each; the canonical edge sets (source and
   target as qualified names, byte ranges, occurrences, hops — node UUIDs
   dropped) are **identical**. The UUIDs themselves differ because rgctl
   derives node IDs from absolute paths; the graph content does not change.

**Determinism: PASS.** Same snapshot + same version → identical graph content
across runs. Determinism is not the failing property; soundness under dynamic
dispatch is.

## rgctl's own documentation discloses these limits (verified at b5a5300)

The measured behavior is not an incidental bug; it is a **documented,
by-design boundary**. All quotes below verified verbatim at the pinned commit
`b5a5300` (= tag `v0.4.30`, the tested binary):

1. `docs/design/blast-radius-design.md:179` (§6 Known Limitations &
   By-Design Boundaries): "**Static Call Graph Scope:** Reachability follows
   static `Calls` edges (plus `Overrides` and optional `--scip` overlay
   edges). Dynamic reflection (`Method.invoke`) or runtime-configured message
   bus topics without static annotations are not connected."
   → This directly bounds the counterclaim: blast-radius traverses static
   Calls/Overrides/SCIP, not DI containers or reflection.
2. `docs/tier-1-language-support.md:35`: "Honesty limits still apply (no full
   points-to, no reflection, dynamic languages may emit more `Unresolved`
   receivers)". Java is Tier-1 (line 33: "Java is the reference
   implementation"), so this applies to it.
3. `docs/releases/v0.4.10.md:45`: "DI-heavy frameworks (Magento/Symfony) still
   produce sparse `Calls` edges for interface-typed receivers — tracked for
   future work in [#75](https://github.com/rgctl/rgctl/issues/75)."
   → The maintainers acknowledge DI/interface-receiver sparsity as a known
   gap, in writing.
4. `docs/design/tier1-callable-identity.md:212-214` (§6): "Source resolution
   remains conservative. Type aliases, implicit conversions, dynamic dispatch,
   arbitrary expressions, and inferred external types can require evidence
   unavailable to the extractor. This design does not ingest dependencies."
5. `docs/design/hybrid-cpg-plan.md:180-181` (§6): receiver types "are
   recovered … without running a full compiler type-checker. Dynamic or
   unannotated receivers are placed in the `Unresolved` bucket"; "Reflective
   field mutations … are outside static tree-sitter CPG visibility."

Also framing-accurate from the README: "compact **deterministic** JSON"
advertises output stability, not runtime soundness; and the framework
extraction (`Injects`, `ExposesRoute`) is annotation-based, not container
resolution — confirmed by measurement (§"What rgctl does capture").

### Implementation matches the docs (verified at b5a5300)

- **Blast-radius does not traverse `Injects` edges — by code, not just by
  doc.** `crates/rgctl-graph/src/schema.rs:324-328`:
  `is_call_traversal(self) -> matches!(self, EdgeType::Calls)`. `Injects`
  (declared at `schema.rs:315-316`) is excluded from call-graph traversal.
  Measured: in the Spring probe, `AuditFacade -injects-> ReportSink` is
  emitted for a `@Autowired` field injection where no method ever calls the
  sink, and `blast-radius 'ReportSink::emit'` — a node whose *only* graph
  connection is that Injects edge — returns
  `direct_callers_count: 0, impact_zone_size: 0`. Docs and behavior agree.
  (Minor doc/code nuance: the design doc says traversal follows Calls "plus
  `Overrides`", while `is_call_traversal` matches `Calls` only; either way
  `Injects` is excluded.)
- **DI-dispatched callees are not marked unresolved anywhere the call-graph
  queries can see.** The `Unresolved` bucket exists, but only for field-write
  receiver types and only on `cpg mutations`: the `--include-unresolved` flag
  is wired for the `mutations` subcommand (`src/cli/cpg.rs:135-146`, doc
  example `docs/design/hybrid-cpg-plan.md:108`) and rejected by `cpg calls`;
  `cpg calls checkout` returns bare symbol names/IDs with no resolution
  status, and `callees`/`blast-radius` payloads have no status fields at all
  (§"Exposed uncertainty"). So the honesty limit of quote 2/5 is real in the
  implementation, but its visibility does not reach the commands a consumer
  would use to answer "is this callee used".

## The blast-radius / boundary claim

**Claim:** `rgctl -r <repo> -f json blast-radius '<FQN>' --classify-boundary
--boundary REST_ENDPOINT` already answers entry-point usage.

**Reproduced** ([`artifacts/counterclaim/`](artifacts/counterclaim/)):
the flags exist and the command succeeds (exit 0), and for the DI-dispatched
callee `EmailNotifier::send` — a method that provably executed in the
`diSelect` trace — it returns:

```json
{"metrics": {"score": 0.0, "direct_callers_count": 0, "impact_zone_size": 0},
 "topology": {"direct_callers": [], "impact_zone": []}}
```

What `--classify-boundary` does: per `rgctl blast-radius --help`, it "label[s]
impact nodes using boundary catalogs (REST / messaging / …)", and
`--boundary` restricts the boundary kinds. It is a **tagging of the impact
zone's nodes** with entry-point classifications. It does not traverse edges
that don't exist in the graph, and it cannot resolve dispatch: when the impact
zone is empty because the DI edge was never extracted, there is nothing to
label and the command returns a clean, unqualified zero. (Also note: symbol
matching is `Class::method`, not a package FQN —
`com.lightpath.fixture.EmailNotifier::send` returns
`NOT_FOUND: No symbol matched criteria`.)

So the command establishes: "no extracted call edge points at this symbol,
and here are the boundary tags of anything upstream that does." It does not
establish "this method is not used at runtime." Two independent confirmations
that this limit is real and known:

- In this experiment, 9 of 10 provably-executed leaf methods got the empty
  zone, including `AbstractJob::run`, which is called by ordinary static
  syntax.
- rgctl's own production consumer, the dymurray/mta-cve bridge
  (`bridge/README.md`, "Reachability and OpenVEX"), treats rgctl's
  not-affected answer as insufficient on its own: `unreachable` requires
  published method-level data; Go is **always unknown** ("rgctl 0.4.21
  doesn't record calls into external Go packages"); Python is unknown; and
  rgctl's `not_affected` is deliberately downgraded to `under_investigation`
  when method data is missing, with a hand-maintained `catalog.yaml`
  compensating for advisories lacking method data.

**The claim is rejected.** Boundary classification is entry-point tagging on
top of reverse reachability over extracted edges; under DI/IoC the extracted
edges are missing, and the command reports a confident zero.

## What rgctl *does* capture (refinement, not absolutism)

On the Spring-annotation probe ([`artifacts/spring-probe/`](artifacts/spring-probe/),
index-only, 26 nodes / 70 edges):

- `relations -e injects` → 3 edges: `OrderController -injects-> OrderService`
  and `OrderService -injects-> PaymentGateway` (constructor-parameter types),
  and — after adding a `@Autowired` field with no calls —
  `AuditFacade -injects-> ReportSink` (field injection). This is
  annotation-driven extraction of dependency *types*, not container
  resolution.
- `relations -e exposesroute` → 1 edge: `OrderController.orders
  -exposesroute-> route::GET /orders`.
- Yet `callees checkout --class OrderService` still emits only the abstract
  `PaymentGateway.charge`, `blast-radius 'StripeGateway::charge'
  --classify-boundary --boundary REST_ENDPOINT` still returns zero callers —
  the `@Qualifier("stripeGateway")` selection is invisible — and
  `blast-radius 'ReportSink::emit'` returns zero even though an Injects edge
  is its only graph connection, matching `is_call_traversal()` in
  `crates/rgctl-graph/src/schema.rs:324-328` (Calls only).

So rgctl's framework extraction records *that* a dependency and *that* a route
exist, but not *which implementation* is bound, which is exactly the fact a
DI-era call graph needs. On the framework-neutral fixture (no Spring
annotations), `injects`/`exposesroute` return 0 edges.

## Conclusion (content only)

- rgctl v0.4.30 **is deterministic**: same snapshot + same version →
  identical graph content across fresh indexes (13/13 byte-identical queries;
  69/69 identical canonical call edges across two source copies).
- rgctl **is not a sound Java call graph under DI/IoC**: 8/10 scenarios miss
  executed edges; all 9 dynamic leaf methods that executed were reported as
  zero-caller / zero-impact; the two controls pass and one dynamic scenario
  (event-bus method reference) passes; no invented edges were observed.
- **This is a documented by-design boundary, not a bug.** rgctl's own docs at
  the pinned commit b5a5300 (= v0.4.30) disclose it: static-Calls-only
  traversal (`blast-radius-design.md:179`), "no full points-to, no
  reflection" for Tier-1 (`tier-1-language-support.md:35`), DI/interface
  sparsity tracked as issue #75 (`releases/v0.4.10.md:45`), conservative
  source resolution (`tier1-callable-identity.md:212-214`), and
  Unresolved-bucket receiver typing without a compiler
  (`hybrid-cpg-plan.md:180-181`). The implementation matches the docs:
  `is_call_traversal()` matches `Calls` only (`schema.rs:324-328`), Injects is
  excluded, and the Unresolved bucket is queryable only via `cpg mutations
  --include-unresolved` (`src/cli/cpg.rs:135-146`).
- **No uncertainty is exposed** at the call-graph level: the empty answers are
  indistinguishable from genuine unreachability, which is what makes the
  output unsafe to consume as "not affected" — rgctl's own downstream
  consumer (mta-cve) wraps it with `unknown`/`under_investigation` safeguards
  for exactly this reason.
- The blast-radius/boundary claim **does not hold**: boundary classification
  tags impact-zone nodes; it neither traverses Injects edges nor flags the
  absence of DI dispatch edges.
- Refined claim: rgctl is a fast, deterministic syntactic + framework-heuristic
  Java graph that performs as documented, useful for static impact triage; it
  cannot be used as a deterministic **and faithful** call graph of what
  executes in a DI/IoC application. What would change this: `injects` edges
  resolving to the qualifier-selected implementation (or participating in
  traversal), per-edge possible/ambiguous/unresolved status surfaced in
  `callees`/`blast-radius`, a points-to/CHA/RTA backend, or non-empty
  marked-uncertain callers for dynamic leaves.

## Limitations

- The DI container is hand-rolled rather than Spring Boot at runtime; the
  Spring probe covers rgctl's annotation-driven extraction, but a compiled
  Spring Boot app with runtime truth was not run (no Maven/JDK 21 on this
  host). The dispatch-resolution result is expected to be identical since the
  executed edge is still decided at runtime, but that run remains a follow-up.
- `discover` at default flags (no `--with-cfg`/`--with-taint`); CFG/PDG add
  slicing, not type resolution, and mta-cve's own pipeline with those flags
  still yields the `unknown` verdicts cited above.
- Compiled `--release 21` on JDK 27.0.1; scenario semantics are
  bytecode-level identical.
