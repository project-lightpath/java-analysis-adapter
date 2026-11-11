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

The unit tests for the baseline normalizer can be run independently with
`make java-lint-test`.

## What runs

The gate first writes Maven's effective project configuration to
`target/java-lint-effective-pom.xml`, then runs Maven PMD Plugin `3.28.0`,
pinned to PMD `7.17.0`, against `src/main/java` only. Tests, generated fixture
sources, and dependencies are outside this production baseline. CPD runs
separately with a 100-token minimum; the validator reads that threshold from
the effective PMD plugin configuration even when the CPD baseline is empty.
The baseline validator is a Java Maven plugin in
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
to infer authorship. New findings fail the gate; existing baseline findings
remain visible, reviewed, and justified.

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

## Baseline and enforcement

[`config/java-lint-baseline.json`](../config/java-lint-baseline.json) is the
reviewed baseline. It records the PMD/CPD versions, selected ruleset, minimum
priority, production source scope, CPD token threshold, normalized finding
details, classification, and rationale. The validator compares those PMD
settings with Maven's effective POM so a changed ruleset, priority, or test
scope cannot make findings disappear silently.

The normalizer intentionally excludes source line numbers:

- PMD fingerprints use rule, repository-relative path, class, method, variable,
  and a normalized message.
- CPD fingerprints use token count, sorted paths, and a normalized fragment
  hash.

The gate compares fingerprints as multisets. It fails when a current finding is
outside the baseline, when the PMD version/ruleset/CPD configuration changes,
or when either XML report contains a processing/configuration error. Resolved
baseline findings are reported but do not fail the build. This makes line
shifts and duplicate findings deterministic without silently accepting new
quality regressions or partial reports.

### Baseline captured for this change

The current reviewed inventory contains:

| Category | Count | Meaning |
| --- | ---: | --- |
| Maintainability | 67 | Parser/model complexity retained as an explicit reviewed backlog. |
| Intentional | 1 | The CLI's process-boundary `RuntimeException` catch. |
| High-confidence defect | 0 | Fixable dead code and duplicated parser mechanics were removed before capture. |
| Configuration noise | 0 | Documentation/parser false positives were removed from the production ruleset. |
| PMD findings | 68 | All listed with stable fingerprints in the JSON baseline. |
| CPD duplications | 0 | No duplication remains at the 100-token threshold. |

The PMD distribution is 25 `CognitiveComplexity`, 25
`CyclomaticComplexity`, 13 `NPathComplexity`, 3 `GodClass`, 1 `NcssCount`,
and 1 intentional `AvoidCatchingGenericException`. The largest reviewed
clusters are the source parser, bounded framework model, context loader, and
provider normalizer.

Before capture, the change fixed unused locals/private code, isolated scanner
I/O error handling, consolidated repeated parser splitting and import
resolution, introduced named framework-fact inputs, and removed documentation
rules that reported valid record/annotation forms. No blanket suppression or
source exclusion was added.

## Review policy

For each finding:

1. Fix it when it is a high-confidence defect or low-risk duplication.
2. Mark it `intentional` only when the behavior is required at a clear boundary
   and the rationale explains the constraint.
3. Mark parser/model complexity `maintainability` when decomposition would risk
   behavior without focused tests; keep it visible in the baseline.
4. Mark `config-noise` only for a documented tool/configuration false positive;
   adjust the narrow ruleset when the signal is not useful.

To refresh the baseline intentionally, regenerate the reports, inspect every
new or changed fingerprint, update the classification/rationale entries, and
run `make java-lint`. Do not replace the baseline with an empty file or add a
broad exclusion merely to make the count zero.

## CI and limitations

The CI workflow keeps adapter tests, spike validation, and Java lint in
separate jobs. The lint job uploads PMD/CPD reports even when the enforced gate
fails, so a failure remains inspectable.

The gate does not prove semantic API compatibility, runtime behavior, security
correctness, or absence of reflective/source-text usage. Public API evolution
should eventually be checked with a dedicated japicmp or Revapi comparison.
CPD and PMD metrics are evidence for review, not proof that code is defective
or machine-generated.
