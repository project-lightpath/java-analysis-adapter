# PLAN-76 — Member-level line join for the javac abstention diagnostics

Issue: project-lightpath/java-analysis-adapter#76 (follow-up from #62 /
PR #72; scope recorded in `docs/resolutions/RESOLUTION-62.md`
Residuals).
Branch: `issue-76-member-level-diagnostics` from `origin/main` @
`1a2486f` (the PR #72 merge).

## Preflight (verified on this main, 2026-11-27, JDK 27)

- **The current join is file-level.** `JavacAbstentionEvidence`'s
  `indexDiagnostics` carries every `compiler.err.*` code recorded for
  the member's declaring FILE (`errorCodesFor(Path)`, unique-owner-only
  — a file with two error members attributes BOTH codes to EACH
  abstention). Three consumers: `JavacMemberCorrelation.errorCodesFor`
  (`error_type_member`, `member_absent`), `JavacAnnotationMemberLookup.
  uniqueOwnerErrorCodes` (`annotation_member_error_type`),
  `JavacFrameworkMemberLookup` (`mirror_member_error_type`).
- **The rows carry line data.** `IndexDiagnostic` records
  `line`/`column` from javac's own diagnostics; the record-time capture
  (`recordErrorCodesByFile`) currently discards the line, keeping only
  the code, keyed by the raw `Path` (never the rendered row path).
- **Position spike (scratch, outside the tree; the plan's load-bearing
  measurement).** A javac probe over a fixture with an error-typed
  method, an implicit constructor, an enum with synthetic methods, and
  a nested class:
  - `Trees.getTree(member)` is NULL for synthetic enum members
    (`values`, `valueOf`) — no position exists.
  - `getEndPosition` returns `-1` for implicitly-declared constructors
    (`endLine` renders 0) — a point span, not a range.
  - Real members carry exact spans (`broken` lines 3..3, `probe`
    6..6, `inner` 8..8), and the fixture's
    `compiler.err.doesnt.exist` error (line 3) falls INSIDE `broken`'s
    span — the member-level join captures it.
  - Nested TYPE elements also appear in `getEnclosedElements` but are
    excluded by `isMember` (METHOD/CONSTRUCTOR/FIELD/ENUM_CONSTANT
    only) — type positions are irrelevant to `MemberInfo`.
  - `SourcePositions.getStartPosition/getEndPosition` are
    deprecated-for-removal on newer JDKs (warning only; the repo
    compiles `--release 17` and both JDKs in play provide them).
- **The construction site is single.** `ElementReader.memberInfo` is
  the only `MemberInfo` constructor; no test constructs `MemberInfo`
  directly (verified), so the additive component needs no
  compatibility constructor. The `Extractor` already holds `Trees` and
  visits `ClassTree`s with the `CompilationUnitTree` in scope
  (`visitClass`), so the position is recordable at the same site.
- **Parallel work**: issue #78 runs in a separate worktree on the
  scanner capture seams (ReflectionLayer05, feature builders) —
  disjoint from this change; the shared test file
  (`JavacAbstentionEvidenceTest`) gets append-only cells on both
  branches.
- **Gates on this main**: `make adapter-test` floor 735 (observed
  735); `make java-lint`; `make evidence-validate`. JDK 17 divergence
  (#64): the touched classes are not near PMD limits, but lint is
  verified on helios08 with `~/jdk17` and a scratch checkout before
  pushing per the standing rule.

## Design

### The position component (additive, the publicMember/staticType precedent)

```java
record MemberPosition(long startLine, long endLine) {}
```

`MemberInfo` gains `MemberPosition position`. Recorded in
`ElementReader.memberInfo` — which therefore needs the member's tree
and the compilation unit: the `Extractor` passes its `Trees` into
`ElementReader` (constructor) and the unit through
`typeInfo(element, file, unit)` → `memberInfo(element, unit)`. Per the
spike:

- `Tree tree = trees.getTree(element)`; null (synthetic members) →
  position `null` (unknown).
- `end == -1` (implicit constructors) → `(startLine, startLine)` — a
  point span.
- Otherwise `(startLine, endLine)` inclusive.

`MemberInfo.position()` is nullable; every reader treats null as
"unknown position".

### The record-time line capture

`recordErrorCodesByFile` additionally records the LINE with each code:
`Map<Path, List<ErrorCodeRow(long line, String code)>>
errorLinesByFile` (new; `errorCodesByFile` stays for the file-level
API). Keyed by the same raw normalized `Path`, deep-copied at
`finish()`.

### The member-level join

New index accessor:

```java
List<String> errorCodesForMember(Path file, MemberInfo member)
```

- Joins `errorLinesByFile` by the member's file, keeping the codes of
  rows whose line lies within `[startLine, endLine]` inclusive,
  deduped in recording order.
- Unknown position (`member.position() == null`) → the file-level set
  (`errorCodesFor(file)`): the conservative #62 behavior (a synthetic
  member cannot be narrowed; dropping codes would under-report).
- Empty result when the file recorded no errors — same as today.

### The consumers

1. **`JavacAnnotationMemberLookup`** (`annotation_member_error_type`):
   the found member is specific (`matches.get(0)`); the join switches
   to `errorCodesForMember(ownerFile, found)` — the owner entry is the
   unique `index.type(typeName)` entry already in hand.
2. **`JavacFrameworkMemberLookup`** (`mirror_member_error_type`): the
   found member is specific; same switch with the unique owner
   entry's file.
3. **`JavacMemberCorrelation`**:
   - `error_type_member`: the group's error-typed members are joined
     by their owning files — the group construction pairs each member
     with its declaring `TypeInfo` entry, and the abstention carries
     the union of the error-typed members' member-level codes
     (deduped, recording order).
   - `member_absent`: NO member exists, so there is no span — the
     join stays FILE-level (unchanged #62 behavior, documented: a
     missing member's cause is often an error elsewhere in the file,
     e.g. the whole class failed to resolve).

### What deliberately does not change

- The abstention codes, member identities, attribute keys, and the
  visibility-only contract (certainty, status, limitation, fact IDs).
- `scan.diagnostics()` — untouched.
- The file-level `errorCodesFor(Path)` API (still used by
  `member_absent` and the unknown-position fallback).
- The opt-out path (no evidence with the flag off — unchanged gates).

## Test matrix (append-only cells in `JavacAbstentionEvidenceTest`)

- E1 **precision (the issue's core)**: one single-type file with TWO
  error-typed members carrying DIFFERENT javac codes (e.g. two missing
  package prefixes); each member's abstention carries ONLY its own
  code (pre-change both abstentions carry both codes).
- E2 **body-error attribution**: an error INSIDE a method body (an
  unreachable/missing statement type) is attributed to that method's
  span, not the file.
- E3 **implicit-constructor point span**: an error on the implicit
  constructor's declaration line is attributed (end=-1 handled as a
  point span).
- E4 **synthetic-member fallback**: unknown position falls back to the
  file-level set (characterized via an enum whose synthetic member is
  the abstaining one, or — if not constructible end-to-end — via the
  fallback unit path; the spike says synthetic members are not
  error-typed in practice, so this may document as a code-inspection
  pin with a probe receipt rather than a cell; decided at RED).
- E5 **member_absent keeps the file-level join**: unchanged from #62
  (characterization; the codes are the file's, not a span's).
- Controls: C2 (the existing `error_type_member` cell) stays green —
  the missing-`missing.Thing` error sits on `broken()`'s own line, so
  the member-level join yields the same code; flag-off twins for E1;
  no `javacAbstentionDiagnostics` regressions elsewhere (grep: only
  C2 asserts the attribute today).

Sanctioned existing-test edits: none expected (C2's assertion is the
`compiler.err.` family prefix, robust to the narrowing); if a golden
shifts, enumerated at RED with observed values.

## TDD / gates / commits

1. RED_76: the E-cells failing (E1/E2/E3 red against the file-level
   join; E4/E5 as decided at RED).
2. GREEN_76: position component + line capture + member-level joins.
3. REVIEW_76 rounds: fresh Engram-grounded adversarial subagents
   (subagents use the engram MCP tools — plain grep is blocked in
   their environment), findings addressed red-first, loop until clean.
4. EVIDENCE_76: `docs/resolutions/RESOLUTION-76.md` with gate
   receipts, mutation checks (revert the span filter ⇒ E1 fails with
   both codes; drop the end=-1 handling ⇒ E3 fails; drop the null-tree
   fallback ⇒ probe-receipted), the spike receipt, residuals.
5. Gates after every item: `make adapter-test` (floor raised from
   whatever main reports), `make java-lint` (helios08 JDK 17
   verification), `make evidence-validate`.
6. PR base `main`, head `issue-76-member-level-diagnostics`;
   `@coderabbitai full review` after pushing; every finding answered
   red-first; no manual thread resolution; no merge (user decides).

## Residuals (to state in the resolution)

- Class-level errors (reported at the type declaration, outside every
  member span) are visible only through the `member_absent` file-level
  join and the index diagnostics surface, not through member-level
  abstentions — the honest bound of a member-scoped join.
- The deprecated-for-removal `SourcePositions` accessors are the only
  portable position API at `--release 17`; revisit when the repo's
  release floor moves.

Confidence score (pre-plan-review): **0.82** — the design is grounded
in a position spike on the exact boundary classes (null trees,
end=-1), a single verified construction site, and the #62 join's
shape; the open risks are the E4 synthetic-member cell's
constructibility (decided at RED) and the group-union semantics for
`error_type_member` (a plan-review question), each with a named
fallback.
