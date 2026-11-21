# Java lint / quality gate

## Commands

Run the enforced production-quality gate:

```sh
make java-lint
```

For an exploratory report without failing on new findings:

```sh
make java-lint-report
```

The unit tests for the lint normalizer can be run independently with
`make java-lint-test`.

## What runs

The gate first writes Maven's effective project configuration to
`target/java-lint-effective-pom.xml`, then runs Maven PMD Plugin `3.28.0`,
pinned to PMD `7.17.0`, against `src/main/java` only. Tests, generated fixture
sources, and dependencies are outside this production scope. CPD runs
separately with a 100-token minimum; the validator reads that threshold from
the effective PMD plugin configuration.
The lint validator is a Java Maven plugin in
[`tools/java-lint-maven-plugin`](../tools/java-lint-maven-plugin), and its
JUnit tests run before the enforced gate.

The configuration is deliberately narrow and is stored in
[`config/pmd-production.xml`](../config/pmd-production.xml). It covers:

| Signal | Rules | Review intent |
| --- | --- | --- |
| Complexity and size | `CognitiveComplexity`, `CyclomaticComplexity`, `NPathComplexity`, `NcssCount`, `ExcessiveParameterList`, `ExcessivePublicCount`, `GodClass`, `AvoidDeeplyNestedIfStmts` | Find structures that deserve decomposition or API review. Thresholds are high enough to avoid ordinary style noise. |
| Exception safety | `AvoidCatchingGenericException`, `EmptyCatchBlock`, `AvoidThrowingRawExceptionTypes`, `AvoidRethrowingException`, `ExceptionAsFlowControl`, `PreserveStackTrace` | Find swallowed failures, lost causes, and broad exception paths. |
| Dead-looking code | `UnusedAssignment`, `UnusedLocalVariable`, `UnusedPrivateField`, `UnusedPrivateMethod`, `EmptyControlStatement`, `IdenticalCatchBranches` | Find likely cleanup opportunities; source scanning and reflection require human review. |
| Public type documentation | Narrow `CommentRequired` plus empty-constructor/method checks | Review public type intent without requiring mass Javadocs for accessors, overrides, or records. |
| Mechanical cleanup | `UnnecessaryModifier` | Find small, high-confidence declaration mistakes. |
| Copy/paste | PMD CPD, 100 tokens | Identify repeated, padded, or mechanically copied code that deserves review; it does not infer authorship. |

This is a production maintainability gate for preventing low-quality or
AI-slop-like code patterns: unnecessary complexity, dead code, weak exception
handling, accidental API surface, and mechanical duplication. It does not try
to infer authorship. Every PMD/CPD finding fails the enforced gate.

PMD type resolution is disabled because the project targets Java 17 while a
newer host JDK can expose class files that the pinned PMD ASM reader cannot
parse. CI provisions Java 17. On a newer local JDK, PMD may print non-fatal
`Unsupported class file major version` warnings; the XML report and normalized
gate still complete. Type-dependent rules therefore have reduced precision.

Reports are disposable output:

```text
target/pmd.xml
target/cpd.xml
target/java-lint-effective-pom.xml
target/java-lint/
```

## Enforcement

There is no checked-in PMD/CPD findings baseline. The validator treats a
missing baseline as an empty inventory, so every current finding is new and
fails the enforced gate. It also verifies the pinned PMD version, production
source scope, minimum priority, selected ruleset, and CPD token threshold
against Maven's effective POM.

The normalizer intentionally excludes source line numbers:

- PMD fingerprints use rule, repository-relative path, class, method, variable,
  and a normalized message.
- CPD fingerprints use token count, sorted paths, and a normalized fragment
  hash.

The gate still normalizes PMD/CPD fingerprints so line shifts do not affect
diagnostics, and it fails when either XML report contains a
processing/configuration error. No baseline entries or suppressions are used.

## Review policy

For each finding:

1. Fix every finding with a responsibility-based refactor or a narrow code
   change.
2. Keep PMD/CPD thresholds and the production source scope unchanged.
3. Add no suppression, source exclusion, or baseline entry merely to make the
   count zero.

## CI and limitations

The CI workflow keeps adapter tests, captured-evidence validation, and Java lint in
separate jobs. The lint job uploads PMD/CPD reports even when the enforced gate
fails, so a failure remains inspectable.

The gate does not prove semantic API compatibility, runtime behavior, security
correctness, or absence of reflective/source-text usage. This module is still
`0.1.0-SNAPSHOT` with no production release, so its public Java API has no
source or binary backwards-compatibility guarantee; see
[`docs/flpath-5002-java-adapter.md`](flpath-5002-java-adapter.md) for the
intentional API policy and the pre-release `SemanticCallFacts` changes. A
japicmp or Revapi comparison becomes appropriate once a released artifact
exists as a compatibility baseline.
CPD and PMD metrics are evidence for review, not proof that code is defective
or machine-generated.
