# Java analysis adapter

This repository captures the project-lightpath Java analysis work from
FLPATH-5000 and FLPATH-5002: the reproducible readiness spike, normalized
findings, and bounded production adapter.

## Adapter

The root Maven module uses SootUp 3.0.1 as the provider-owned CHA graph and
composes it with project-owned `AnalysisContext` and `FrameworkModel` inputs to
produce `SemanticCallFacts`.

Provider and framework facts remain separate and preserve `definite`,
`possible`, and `unresolved` certainty, evidence provenance, route-selection
criteria, and explicit enrichment links. Quarkus and Spring Boot are first-class
bounded framework models. Reflection and dynamic-proxy behavior that is not
uniquely identified remains unresolved/abstained.

Run the adapter checks with:

```sh
make test
make package
```

See [`docs/flpath-5002-java-adapter.md`](docs/flpath-5002-java-adapter.md) for
the implementation boundary.

## FLPATH-5000 spike and findings

The [`spike/`](spike/) directory contains the Java 21/Quarkus fixture, pinned
Spring Boot baseline manifest, acceptance criteria, candidate/capability
matrices, contracts, analyzer probes, scripts, and portable normalized results.
The readiness report is in
[`docs/flpath-5000-java-readiness.md`](docs/flpath-5000-java-readiness.md).

Validate the checked-in spike evidence with:

```sh
make spike-validate
```

The Spring Boot baseline remains external and is not vendored. The ignored
`spike/runs/` and `spike/.cache/` paths contain disposable host-specific output
when reproducing analyzer runs.

## Scope boundary

This repository does not implement the VPE graph engine, assessment controller,
vulnerability policy, runtime validation, or durable `RepositoryAnalysisProfile`
wiring. Those remain integration concerns for the vulnerability-prioritization
engine and FLPATH-5012.
