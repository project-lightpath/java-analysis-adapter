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

`AnalysisContext` can carry the versioned `frameworkBindingMetadata` payload
defined in `spike/contracts/framework-binding-metadata.json`. It records CDI and
Spring candidates, resolved injection rows, and proxy targets. The scanner
supports common constructor, field, and method injection; qualifiers,
alternatives/primary, producer methods, profiles, common conditions, and common
proxy/advisor annotations. An exact unique active metadata row is required for
`definite`; source-bounded candidates remain `possible` and unresolved profile,
condition, or proxy selection remains `unresolved` when the relevant metadata is
missing; an observed but non-unique or unevaluated proxy selection remains
`possible`.

Framework facts link to provider facts only through an exact `providerFactId` or
a unique non-abstained provider target identity. Links use
`enriches_without_replacing`, preserving separate provider and framework fact
sets.

## Dependency Maven coordinate handoff

`AnalysisContext` optionally accepts a versioned
`dependencyCoordinateManifest`. The adapter consumes this upstream-owned
metadata; it never derives coordinates from a filename, local Maven repository
path, archive contents, or `META-INF/pom.properties`.

The supported `0.1.0` shape is:

```json
{
  "dependencyCoordinateManifest": {
    "schemaVersion": "0.1.0",
    "owner": "vulnerability-prioritization-engine/analysiscontext.Coordinator.Prepare",
    "fingerprint": "same-value-as-provenance.dependencyCoordinateFingerprint",
    "entries": [
      {
        "classpathEntry": "/absolute/normalized/dependency.jar",
        "fingerprint": "optional-entry-fingerprint",
        "groupId": "com.example",
        "artifactId": "dependency",
        "version": "1.2.3",
        "classifier": "tests"
      }
    ]
  }
}
```

The manifest is optional for backward compatibility. When present with entries,
its fingerprint must equal `provenance.dependencyCoordinateFingerprint`, every entry path
is normalized and must match exactly one distinct normalized
`dependencyClasspath` path, and duplicate paths or missing coordinate values
are rejected. `classifier` and entry `fingerprint` may be absent. A
dependency fact keeps the existing exact `dependencyClasspathEntry` and adds
only supplied values as `dependencyGroupId`, `dependencyArtifactId`,
`dependencyVersion`, `dependencyClassifier`, and
`dependencyClasspathEntryFingerprint`; absent metadata produces no coordinate
attributes.

## Java API versioning

The Maven module is currently `0.1.0-SNAPSHOT` and has no production release.
The public Java API, including `SemanticCallFacts`, `Fact.FrameworkInput`, and
`RunMetadata`, is therefore pre-release and has no source or binary backwards-
compatibility guarantee. Breaking API changes are allowed before the first
production release when the adapter tests and handoff evidence are updated in
the same change.

This policy covers the intentional FLPATH-5076 API change:

- positional `Fact.framework(...)` arguments were replaced by the named
  `Fact.FrameworkInput` value;
- public positional `RunMetadata` constructors were replaced by
  `RunMetadata.from(AnalysisContext, AnalysisGraph)`; and
- run metadata now derives immutable source-root information from the analysis
  context and provider graph.

No japicmp or Revapi comparison is configured because there is no released
artifact to use as a compatibility baseline. Before the first production
release, publish a stable version and add a dedicated compatibility policy and
check if downstream Java consumers require source or binary compatibility.

Route scanning expands literal class/method path arrays, HTTP method arrays, and
Spring header/parameter/media conditions. Quarkus routes require a class-level
`@Path`; method-level `@Path` narrows that root and an unregistered method-only
path is ignored. Source annotations alone produce a `possible` route candidate;
observed route registration metadata is recorded but must be matched to the
candidate before it can support `definite` certainty. Unsupported path
expressions are ignored rather than treated as literal paths. Method-less Spring
`@RequestMapping` expands to the supported HTTP methods, intersected with any
class-level method restriction.
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
`AnalysisContextLoader`. The manual command preserves the selected profile,
production/test scope, generated-root declarations, source/build/provenance
fingerprints, and handoff limitations. It emits normalized facts with separate
provider and framework sets:

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
