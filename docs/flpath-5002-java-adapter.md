# FLPATH-5002 Java analysis adapter

This repository contains the bounded production integration boundary built from
FLPATH-5000:

```text
SootUp CHA base graph + FrameworkModel + AnalysisContext
    -> SemanticCallFacts(providerFacts, frameworkFacts)
```

`JavaAnalysisAdapter` composes the provider and framework layers. The adapter
uses SootUp 3.0.1 for the provider-owned application CHA graph and keeps
Quarkus/Spring framework enrichment in separate, provenance-bearing fact sets.

The first-class framework models cover bounded REST routes, dependency
injection, generated/client proxies, generated source, Spring Data repositories,
and Spring lifecycle callbacks. Provider and framework facts preserve
`definite`, `possible`, and `unresolved` certainty. Missing provider callers do
not prove route absence; open-world reflection and dynamic proxies remain
`unresolved`/`abstained`.

Route scanning expands literal class/method path arrays, HTTP method arrays, and
Spring header/parameter/media conditions. Quarkus routes require a class-level
`@Path`; method-level `@Path` narrows that root and an unregistered method-only
path is ignored. Source annotations alone produce a `possible` route candidate;
observed route registration metadata is recorded but must be matched to the
candidate before it can support `definite` certainty. Unsupported path
expressions are ignored rather than treated as literal paths.
Route evidence retains the source path and all route annotations, and route IDs
include the fully qualified owner, signature, conditions, and a stable digest so
same-named controllers do not collide.

The adapter does not add synthetic provider facts when SootUp has no incoming
caller for a framework route. The framework fact records
`providerCallerAbsence: not_evidence_of_absence` instead.

The adapter intentionally does not implement the graph engine, vulnerability
policy, controller, runtime validation, or durable repository-profile wiring.

## Manual VPE integration

The VPE `AnalysisContext` JSON handoff is loaded by
`AnalysisContextLoader`. The manual command preserves source/build/provenance
fingerprints and emits normalized facts with separate provider and framework
sets:

```sh
spike/scripts/run-manual-integration.sh
```

The runner uses the pinned `fsi-spring-boot` profile and local immutable Git
archive, then executes:

```text
source archive -> Maven package -> AnalysisContext JSON
    -> JavaAnalysisAdapter -> SemanticCallFacts JSON
    -> capability/provenance validation
```

The manual runner records host toolchain limitations; it does not claim the
outer sandbox guarantees required by a production orchestrator. Tekton can be
considered only after this local chain is passing.
