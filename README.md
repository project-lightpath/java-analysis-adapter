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

The `Adapter tests` CI lane runs `make adapter-test` and `make evidence-validate`
on pull requests and pushes to `main`. The separate `Java lint` CI lane runs
the enforced production-quality gate with `make java-lint` and uploads the
PMD/CPD reports as workflow artifacts, including when the gate fails.

See [`docs/flpath-5002-java-adapter.md`](docs/flpath-5002-java-adapter.md) for
the implementation boundary and pre-release Java API versioning policy.

Run the enforced Java quality gate with:

```sh
make java-lint
```

It runs the pinned PMD/CPD configuration against production Java sources,
requires zero normalized PMD/CPD findings, and fails on findings, changed lint
configuration, or partial PMD/CPD reports. The retired baseline is not
vendored. For exploratory reports without enforcement, use
`make java-lint-report`. See [`docs/java-lint.md`](docs/java-lint.md) for the
rule rationale, limitations, and review process.

## FLPATH-5000 spike and findings

The [`spike/`](spike/) directory contains the Java 21/Quarkus fixture, pinned
Spring Boot baseline manifest, acceptance criteria, candidate/capability
matrices, contracts, analyzer probes, scripts, and portable normalized results.
The readiness report is in
[`docs/flpath-5000-java-readiness.md`](docs/flpath-5000-java-readiness.md).

Validate the checked-in evidence contracts with:

```sh
make evidence-validate
```

The Spring Boot baseline remains external and is not vendored. The ignored
`spike/runs/` and `spike/.cache/` paths contain disposable host-specific output
when reproducing analyzer runs.

## Scope boundary

This repository does not implement the VPE graph engine, assessment controller,
vulnerability policy, runtime validation, or durable `RepositoryAnalysisProfile`
wiring. Those remain integration concerns for the vulnerability-prioritization
engine and FLPATH-5012.
