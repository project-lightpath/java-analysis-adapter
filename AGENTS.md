# AGENTS.md — java-analysis-adapter

Guidance for AI agents reviewing and changing this repository. This is a
living document: when a review cycle reveals a new failure class, add it here.

## What this codebase must not do
- The adapter's purpose is accurate call facts with explicit certainty. A
  false DEFINITE (a fabricated provider/framework fact) is the primary bug
  class. Abstention (POSSIBLE/unresolved) is always the safe direction when
  ownership or coverage cannot be proven.
- Incomplete scans must be visible. Any path that silently skips an input
  (budget, file-type check, dependency-resolution filter) is a defect: emit a
  diagnostic.
- Diagnostics are scan-global. `ServiceLoaderDescriptorScanner.isUnique`
  blocks DEFINITE promotion when `scan.diagnostics()` is non-empty, so adding
  one diagnostic downgrades certainty for the WHOLE scan, not one archive or
  entry. State this blast radius in the commit message when adding a
  diagnostic.

## Review scope: derive equivalence classes from the spec
Do not enumerate test cases from examples. When a change touches name
resolution, call-site classification, or scoping, derive the matrix from the
JLS and test every cell plus pairwise interactions:
- JLS 6.5 name-resolution precedence, weakest to strongest: package member <
  on-demand import < single-type import < same-compilation-unit type <
  enclosing/nested type < local class < in-scope variable (parameter, local,
  field) < lambda parameter < catch parameter < instanceof pattern variable.
- When one review finding exposes an open cell, close the remaining cells in
  the same change rather than one finding per review round.
- Scope rules come from JLS 6.3: parameters span the method body, locals span
  declaration-to-block-end, fields span the type body, catch parameters span
  the catch block only, resources span the try block, for-each variables span
  the for statement, lambda parameters span the lambda body ONLY.

## Boundary conditions
Any substring/indexOf/length arithmetic on names or paths must be probed
empirically for: empty component, single character, prefix/suffix OVERLAP
(e.g. `META-INF/versions/module-info.class` makes the prefix and suffix share
the slash), and exact-boundary lengths. Never verify index arithmetic by
inspection — run a scratch probe before committing.

## Weakness disposition
When a review flags a weakness below full confidence, audit EVERY caller and
path through the weak code, not just the flagged instance. An "accepted
over-approximation" disposition must enumerate all manifestations of the
weakness, not just the one that was reported.

## Interaction tests
Scope features are tested pairwise at minimum: lambda x local/anonymous
class, lambda x switch, catch x nested type, import x declaration. Features
green in isolation do not prove their composition.

## TDD and gates
- RED_nn / GREEN_nn / EVIDENCE_nn commit triples. RED commits contain the
  failing tests (plus, if unavoidable, a behavior-neutral compile stub —
  documented in the message). GREEN commits contain the minimal fix only.
- Gates after every item, mandatory: `make adapter-test` (execution count
  must not decrease), `make java-lint` (zero findings), `make evidence-validate`.
- Zero edits to existing tests unless the plan sanctions one explicitly.
- If PMD forces a helper placement change, keep behavior identical and
  document the placement in the commit message.

## Before pushing
Run `coderabbit review` (or request a review) on the local diff before
pushing. Front-load findings instead of paying one public review cycle per
batch.
