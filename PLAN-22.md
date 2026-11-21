# PLAN-22 — annotation names and arguments from javac, not regexes

Issue: project-lightpath/java-analysis-adapter#22 (parent #20).
Branch: `feature/flpath-5142-javac-annotation-names` from `origin/main` @ `1077c09`
(after merges of #33, #35, #36).
Prior art: `PLAN-21.md` / `RESOLUTION-21.md`, `JavacSourceSymbolIndex`
(the #21 seam: one parse per `AnalysisContext` behind
`flpath.java.symbolindex`, default off), PLAN-16/RESOLUTION-16 (the
accuracy GO that motivates the whole tree).

## Goal

Replace the annotation-name and annotation-argument **text** reading with
javac answers from the #21 index, behind the existing
`flpath.java.symbolindex` flag, keeping the textual path as fallback and
default output byte-for-byte unchanged while the flag is off.

## Verified consumer inventory (Engram-searched, `@[A-Za-z_]` family)

In scope (the issue's list):

| Site | What it does today | Known-bad shape |
|---|---|---|
| `SourceMetadataScannerTextLayer02.parameterType(String)` | strips `@[A-Za-z_][\w.]*` + `final ` from one parameter's text | `String @$Ann... args` → `String@$Ann[]`; `@Ann(value=1) String p` → `(value=1)String` (pinned as baseline losses in `JavacResolverAccuracyTest`) |
| `SourceMetadataScannerTextLayer12.returnType(String)` | masks annotation arguments, strips the same annotation regex + modifiers from a declaration header | same class |
| `SourceMetadataScannerTextLayer12.annotationOccurrences` / `annotationArguments` | reads annotation names and argument text from header blocks | `$`/Unicode/qualified names missed |
| `SourceMetadataScannerTextLayer19` (qualifier extraction) | same strip regex before reading qualifiers | same class |
| method/field/constructor/type header helpers that strip annotations to isolate the declared shape | the strip regex copies | same class |

Adjacent but **out of scope** (documented boundary, review to attack):

- `SourceMetadataScannerTextLayer07.maskAnnotationArguments` — the
  argument-masking helper. Widening its name regex would change
  textual-mode output and break the flag-off guarantee; the flag-on path
  must not need it. Its correctness for `$`/Unicode stays a textual-path
  residual until the textual resolvers are deleted.
- Declaration-*discovery* regexes that merely tolerate annotations
  (`State.METHOD`, `State.INJECT_FIELD`, `TextLayer11` constructor
  pattern, `TextLayer15.INITIALIZER_FIELD`,
  `ReflectionLayer02/03`): finding declarations is not reading
  annotation names. `FrameworkInjectionScanning`'s constructor regex and
  reflection-layer shapes are #24/#25 scope.
- Framework route/bean/injection **semantics** (#24), reflection
  runtime strings (#25), parity harness extension (#26).

## Design

### Correlation rule (the load-bearing decision)

The scanner's model is text-derived (`MethodInfo` with header blocks);
the index is symbol-derived (`TypeInfo`/`MemberInfo` by name). The
migration correlates at the points where a consumer asks a
**name-keyed** question:

- Given (file, owning type, member name) →
  `index.members(typeName, memberName)` returns every overload's
  `annotations()`, `parameterTypes()`, `returnType()`.
- Overload disambiguation: the textual `MethodInfo` knows its parameter
  count; when exactly one index overload matches the count, its values
  replace the textual ones. Zero matches (source not indexed, error
  type, local class outside the canonical map) or >1 matches with the
  same count → **keep the textual value** and record nothing (silent
  fallback is safe: the textual value is today's output; the parity
  harness in #26 measures the fallback rate).
- The index gains **no position/offset API** in this subissue — offsets
  would grow #21's contract; name+arity correlation is enough for every
  in-scope consumer. Review loop to attack this.

### Migration points

1. `TextLayer11.methodDescriptor` (the only caller of
   `parameterType`): with the flag on, the descriptor's parameter list
   comes from the index overload's `parameterTypes()` (canonical erased
   names, already the shape `methodDescriptor` joins). Falls back per
   the correlation rule.
2. Header-shape helpers (`returnType`, field/constructor/type header
   stripping): with the flag on, the declared shape comes from the
   index member's `returnType()`/kind, not from regex stripping.
3. Annotation reads (`annotationOccurrences`/`annotationArguments`,
   qualifiers in `TextLayer19`): with the flag on, names and rendered
   values come from `AnnotationInfo` (canonical names + the #21 value
   renderer). Owner/annotation evidence recorded per the issue: each
   parsed annotation carries its owning member's binary name and the
   annotation's canonical name.

A package-private `JavacAnnotationAccess` helper (same package, holds
no state beyond the per-context index from
`JavacSourceSymbolIndex.of(context)`) wraps the lookup + fallback rule
so the three consumer families share one correlation implementation
instead of three copies.

### Flag

Reuse `flpath.java.symbolindex`. Flag off (default): every consumer
path is byte-for-byte today's output — pinned by a scan-equality cell
in both directions, as in #21.

## Test matrix (`JavacAnnotationMigrationTest`, new file; SAME_THREAD)

Derived from the issue's cells plus the JLS boundary rule (`$`,
Unicode, qualified names — the D-series lesson):

1. Flag off: output identical to the textual path (scan equality on a
   fixture with annotated members).
2. `@$Ann` on a parameter, field, method return, and type: names and
   shapes resolve (A18/A19: `java.lang.String[]`, `java.lang.String`).
3. Unicode-initial annotation name (e.g. `@Ωmega`).
4. Fully qualified annotation name `@pkg.Outer.Ann`.
5. `$`-named annotation `@Mar$k` on each member kind.
6. Annotation values: array, class literal, enum constant, nested
   annotation — read back through the migrated consumer with the #21
   renderer's spellings.
7. Varargs and type-use annotations before and after the type.
8. Annotation-like text inside a string or comment is not an
   annotation (index path: it never was; the cell pins that the
   migration does not regress the masking the textual path keeps).
9. Overloads: two same-name methods with equal parameter counts are
   not silently conflated — flag-on falls back to textual values
   rather than guessing (the ambiguity rule), and the parity choice is
   asserted.
10. Error types / unindexed sources: silent textual fallback, no
    diagnostics added (non-goal: scan-global diagnostics).
11. Determinism: two fresh contexts produce equal migrated output.
12. No `@[A-Za-z_]` annotation-name regex remains in the migrated call
    sites (asserted by a source-scan test or gate script — TBD by
    review).

## TDD sequence

- **RED_22**: the matrix above plus a behavior-neutral seam (the
  `JavacAnnotationAccess` helper returning textual values
  unconditionally), expected-red cells enumerated in the commit.
- **GREEN_22**: implement the correlation and the three migration
  points; targeted green; full gates; Makefile floor raised to the new
  strict total.
- **REFACTOR_22**: under green.
- **EVIDENCE_22**: `RESOLUTION-22.md` — parity deltas vs the pinned
  baseline losses (`String@$Ann[]`, `(value=1)String` now correct with
  the flag on), fallback-rate note, wall/heap receipts, residuals.

## Gates

`make adapter-test` (count floor raised in GREEN), `make java-lint`
(zero findings; no new suppressions without inventoried justification),
`make evidence-validate`. Full green adapter-test first applies at
GREEN_22 (RED intentionally contains failing tests, per AGENTS).

## Residuals / risks

- Name+arity correlation cannot distinguish overloads with identical
  arity — those fall back to textual values until the index grows
  positions (follow-up candidate; #26 parity harness will measure how
  often it matters).
- The textual resolvers are kept (per #20); their `$`/Unicode gaps in
  `maskAnnotationArguments` remain until deletion.
- Local/anonymous-class members are binary-keyed in the index; member
  correlation for them needs the binary owner name the scanner does
  not always carry — likely silent fallback; to be measured in #26.
