# RESOLUTION-37 — per-parameter annotation API for the javac index

Issue: project-lightpath/java-analysis-adapter#37 (parent #20; consumed by #24)
Branch: `feature/flpath-5142-per-parameter-annotations` (from `origin/main` @ `4292301`)
Plan: ephemeral per the AGENTS document-hygiene rule (2 fresh-session
adversarial review loops: loop 1 fixed the stub semantics and the
expected-red partition, loop 2 returned PLAN-SOUND with one error-type
pin cell folded in). This document and the PR body are the durable record.

## Summary

`MemberInfo` grows `parameterAnnotations()`
(`List<List<AnnotationInfo>>`): one annotation list per parameter,
aligned with `parameterTypes()` by construction (both derive from the
same `ExecutableElement.getParameters()` iteration in
`ElementReader.memberInfo`). Constructors included; the spelling
contract is the existing surface (`AnnotationInfo` canonical names +
#22's derived `simpleName`). No new index accessor, no flag change, no
consumer wiring — #24 consumes.

## Spike results (JDK 27, --release 17)

- Declaration annotations come from
  `VariableElement.getAnnotationMirrors()`.
- Type-use-only annotations (including the varargs position) live in
  the parameter **type's** mirrors — a different position, excluded as
  #23 scope; the position split is pinned by a cell.
- Mixed `@Dec @Use String c` splits cleanly: no double counting.
- The varargs type spelling is unaffected (`canonicalType` erases
  type-use annotations).

## Acceptance receipts

| Issue criterion | Receipt |
|---|---|
| Per-parameter annotations aligned with parameterTypes by index, incl. varargs and zero-parameter methods | `declarationAnnotationsAlignWithParameterTypes`, `varargsPositionIsPresentButTypeUseIsNotADeclarationMirror`, `zeroParameterMethodsYieldAnEmptyList` |
| Duplicate binary names, error types, unindexed sources keep #21 fail-visible semantics | `unresolvedParameterTypesKeepFailVisibleSemantics` (errorType true AND declaration mirrors rendered) |
| Gates pass; floor raised | receipts below, floor 390 → 399 |

## Gate receipts

| Commit | adapter-test | java-lint | evidence-validate |
|---|---|---|---|
| RED_37 | 7 of 9 cells fail exactly as partitioned (annotation-content cells; the aligned stub keeps position cells green); accuracy test green in isolation (known local full-lane flake) | 0 findings | pass |
| GREEN_37 | targeted 50/50 across the three index-test classes; full-suite receipt on CI | 0 findings | pass |

## Residuals

- Type-use annotations on parameters (including the varargs position)
  are not exposed — #23 scope.
- No consumer reads the API yet; #24 wires constructor/method injection
  qualifiers through it (design findings preserved on the #24 comment).
