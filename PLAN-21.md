# PLAN-21 — javac source-symbol index and flag seam

Issue: project-lightpath/java-analysis-adapter#21 (parent #20).
Branch: `feature/flpath-5142-javac-index-seam` from `origin/main` @ `5d4635f`.
Companions: `AGENTS.md`, `PLAN-16.md`, `RESOLUTION-16.md`,
`src/test/java/.../JavacSourceSymbolResolver.java` (spike reference),
`src/main/java/.../JavacServiceLoaderSiteResolver.java` (production seam
reference), `AnalysisContext`/`AnalysisContextAccessors` (read only).

## Goal

One javac-backed source-symbol index per `AnalysisContext`, built from the
source roots recorded in that context, parsed exactly once, cached by source
file / top-level type / member / annotation, and exposed through a small read
API for the later #20 subissues. A system flag gates access so default fact
output stays byte-for-byte unchanged while the flag is off.

## Scope (binding)

- New production-seam class `JavacSourceSymbolIndex` in `src/main`
  (package-private, same package as `JavacServiceLoaderSiteResolver`).
- No consumer migration: annotations, framework scanning, reflection,
  ServiceLoader resolution keep their current paths. No textual resolver is
  deleted. No scan-global diagnostics and no certainty-semantics changes:
  the index records its own diagnostics; it never writes into
  `ServiceLoaderDescriptorScanner`'s scan diagnostics.
- `AnalysisContext` is used read-only (`includedSourceRoots()`,
  `javaRelease()`, `dependencyClasspath()`, `dependencyClasspathRequired()`,
  `applicationClasses()`).

## Flag / test seam

- System property `flpath.java.symbolindex`; default **off**.
- `static boolean enabled()` reads the property once per call (tests toggle
  it; production reads are cheap).
- `static Optional<JavacSourceSymbolIndex> of(AnalysisContext context)`
  returns `Optional.empty()` when the flag is off — no javac task, no file
  walk. With the flag on it returns the per-context index, computed at most
  once per context instance.
- Because no production consumer calls `of(...)`, flag-off default output is
  unchanged by construction; the test asserts the stronger property that
  toggling the flag does not change a representative scan result at all.

## Cache and compiler invocation

- Per-context cache: `WeakHashMap<AnalysisContext, JavacSourceSymbolIndex>`
  in a synchronized map, mirroring the existing
  `JavacServiceLoaderSiteResolver` cache pattern. One `AnalysisContext`
  instance ⇒ at most one javac parse; repeated lookups reuse it. Failed
  builds are cached like successes (a present index carrying its
  diagnostics), mirroring the seam's failure-caching pattern — a repeated
  `of()` after `index_build_error` does not retry and does not move
  `COMPILATIONS`. The
  index must **not** retain a strong reference to the context (it would
  pin the WeakHashMap key forever): all paths are rendered into
  `IndexDiagnostic`/`TypeInfo` at build time, and no accessor stores the
  context.
- Compile-count seam: package-private `static final AtomicLong
  COMPILATIONS`, incremented **by the index build path only, after the
  whole build — javac task and post-analysis extraction pass —
  succeeds**; a build that throws anywhere (routed to
  `index_build_error`) does not increment it, so a cached failed build
  never carries a compile count. The counter is
  index-local: the resolver
  path never touches it, so no concurrently running test class can move
  this counter and `SAME_THREAD` + deltas is sufficient. The regression
  test asserts a second `of(context)` lookup does not increment it (no
  second compiler) and that a fresh context does.
- Compilation units: every `*.java` file under the included source roots,
  discovered with a sorted `Files.walk`, **deduped by normalized absolute
  path across all included roots, most-specific root first** (mirror of
  `SourceMetadataScanner.files()`, which uses
  `putIfAbsent` + `rootsBySpecificity`): a file is never submitted as two
  units, so legal nested/overlapping root shapes do not manufacture
  `duplicate class` errors. The walk keeps regular files only
  (`Files.isRegularFile`), includes `package-info.java` (package
  annotations are a later-subissue input), and excludes
  `module-info.java` (module resolution against an empty sourcepath
  manufactures error diagnostics; divergence from `scanner.files()`
  recorded in Residuals). `duplicate_binary_name` stays reserved for
  genuinely distinct files.
- Compiler options, all explicit:
  - `-proc:none`, `-encoding UTF-8`;
  - `--release min(context.javaRelease(), Runtime.version().feature())`
    (same clamp as `JavacServiceLoaderSiteResolver.options`); when
    clamping occurs the index emits its own `release_clamped` diagnostic
    instead of silently compiling at a lower release than declared.
  - `-classpath` = `dependencyClasspath` + `applicationClasses`, always
    emitted, composed caller-side (seam-local, identical by construction
    to `JavacServiceLoaderSiteResolver.options()`;
    `applicationClasses` is never null; javac tolerates nonexistent
    entries);
  - `-sourcepath` = a fresh empty directory, so javac never implicitly
    compiles files outside the recorded roots — the unit set is exactly the
    enumerated files;
  - `-d` = a fresh temp directory per index build, deleted after
    `parse()` + `analyze()` (class writing never runs; `generate()` is not
    called). Both temp directories (sourcepath and `-d`) are deleted in a
    `finally` block; deletion failure is ignored (best-effort cleanup, no
    diagnostic).
- `parse()` then `analyze()`; `Trees`, `Elements`, `Types` retained for the
  build pass only. The index stores plain immutable records — no live
  compiler objects escape.

## Read API (for later subissues)

```java
static Optional<JavacSourceSymbolIndex> of(AnalysisContext context)
static boolean enabled()                               // flag state
List<TypeInfo> type(String canonicalOrBinary)       // every entry sharing the name
List<TypeInfo> typesIn(Path sourceFile)
List<AnnotationInfo> packageAnnotations(Path sourceFile)  // package-info.java rows
List<MemberInfo> members(String typeName, String memberName)  // overloads kept
List<IndexDiagnostic> diagnostics()                  // index + javac errors
long wallMillis()
long peakHeapBytes()
```

`members(typeName, memberName)` resolves its type name through the same
distinct-entry both-map union as `type()`, returning the union of all
entries sharing the type name, each member's `errorType` derived from its
own entry only;
callers abstain on duplicate-flagged entries via `type()`. Both `type()`
and `members()` return a **distinct-entry** union deduped by entry
identity — a type whose canonical and binary names coincide (every
ordinary top-level type) is returned once, never twice. `typesIn`
normalizes its argument via `toAbsolutePath().normalize()` before the
map lookup, so relative or non-normalized caller paths do not silently
miss; `packageAnnotations(Path)` takes the `package-info.java` file
itself as its argument and returns the annotations declared in it —
empty when the path is not an indexed `package-info` unit — applying
the same `toAbsolutePath().normalize()` normalization (and the same
CWD-relative semantics) as `typesIn` against the same cache-key
spelling, so the
later-subissue package-annotation input is actually reachable through
the API rather than parsed and discarded.

Records (all immutable, no compiler types):

- `TypeInfo(String canonicalName, String binaryName, String nestingKind,
  Path file, List<AnnotationInfo> annotations, List<MemberInfo> members,
  TypeShape shape)` — 7 components (PMD `ExcessiveParameterList` fires
  at 8); `TypeShape` is the record above.
- `TypeShape(String declarationKind, boolean errorType, boolean
  duplicateBinaryName)` — the fold keeps `TypeInfo` at 7 components;
  `declarationKind` ∈ {class, interface, enum, record, annotation_type}.
  `MemberInfo.kind` ∈ {method, field, constructor, enum_constant};
  enum constants are `enum_constant`, record components surface as a
  field-kind entry (the synthesized field) plus method-kind accessors,
  probed by cell 6 rather than improvised by a later subissue.
- `MemberInfo(String name, String kind, List<AnnotationInfo> annotations,
  List<String> parameterTypes, String returnType, boolean errorType)`
  — `kind` ∈ {method, field, constructor, enum_constant};
  `parameterTypes` is empty and
  `returnType` is `""` for fields and enum constants (both field-like),
  `"void"` for constructors (a record's canonical constructor surfaces as
  a `constructor` entry;
  constructors have no declared return type; the binary-name keying of
  the owning type carries the identity). Type-use annotations on a
  member's own type (field type, method return) attach to that member's
  `annotations`. `parameterTypes`/`returnType` are canonical erased names
  (`java.lang.String[]` for `String @$Ann ... args`, per A18/A19); for
  nested types the **canonical dotted spelling wins** (`demo.Outer.Inner`,
  never the binary `demo.Outer$Inner`) — probed by a scratch probe before
  RED, since `TypeMirror.toString()` is not a stable contract.
- `AnnotationInfo(String canonicalName, Map<String,String> values)` —
  values rendered deterministically (class literal → canonical name, array
  → `{a, b}`, string → quoted, enum → `Owner.NAME`, nested annotation →
  `@Name(key=value, ...)` recursively, other → `toString()`).
- `IndexDiagnostic(String code, String message, String path, long line,
  long column)` — source-file rows whose source is an **indexed unit**
  use `context.displayPath`, except that an absolute `declaredPath` on
  the owning root falls back to the repository-relative renderer (the
  `missing_classpath` rule) so no row ever leaks an absolute path
  (loader-reachable: `AnalysisContextLoaderLayer05` validates only the
  resolved path, the declared string passes through verbatim);
  diagnostics whose mappable source is not an
  indexed unit (e.g. a broken class file in a classpath directory) and
  classpath/root-directory rows use the repository-relative-or-file-name
  renderer. That rendering (relativize under `repositoryRoot`, else
  `<external>/<fileName>`) is re-implemented context-side in the index —
  8 lines, far below CPD's 100-token tile — rather than extracting
  `ServiceLoaderDescriptorScanner.diagnosticPath`, which would modify a
  production class outside #21's file list; the extraction is a
  follow-up residual. Never an absolute path.

Cache keys: `Map<Path, List<TypeInfo>>` by source file;
`Map<Path, List<AnnotationInfo>>` keyed by absolute-normalized
`package-info.java` walk path (backing `packageAnnotations`);
`Map<String,
List<TypeInfo>>` by canonical name and by binary name (nested types
included; local/anonymous classes have empty canonical names and are keyed
by binary name only; lists absorb duplicate binary names). The per-file
map's keys and `TypeInfo.file` are **absolute normalized walk paths** —
only `IndexDiagnostic.path` is rendered (displayPath/repository-relative);
a displayPath-keyed implementation would silently miss every in-repo
`typesIn` lookup. Members live
inside `TypeInfo` (declaration order is deterministic per file); the
`members(type, name)` lookup is a filtered read, not a recompile.

## Fail-visible rules

| Condition | Behavior |
|---|---|
| flag off | `of()` empty; no javac, no walk |
| no included source roots, or no units discovered under them | present, empty index + diagnostic `missing_source`; short-circuits like the `missing_source_root` row (no javac task, `COMPILATIONS` delta 0) |
| source root dir absent on disk **or present but not a directory** (a regular file) | diagnostic `missing_source_root` per root (declared path in the row, rendered repository-relative under `repositoryRoot` when the declared path is absolute); remaining roots still parsed; when **no** units remain the build short-circuits like the seam (no javac task, `COMPILATIONS` delta 0) after the diagnostics are recorded, and `missing_source` is **also** emitted (zero units discovered under the included roots) |
| `dependencyClasspathRequired()` and an entry absent on disk, **or** required and the classpath list is empty | diagnostic `missing_classpath` (path rendered repository-relative under `repositoryRoot`, else the entry's file name — never a `../` chain); non-required absence stays silent: an optional classpath is a legal state, and `applicationClasses` is routinely absent in tests |
| two indexed types share a binary name | both marked in `TypeShape`, diagnostic `duplicate_binary_name`; `type(name)` returns every entry, all flagged, so consumers abstain |
| error type (`TypeKind.ERROR`) | `errorType` in `TypeShape`/`MemberInfo` (and parameter/return name falls back to the error name); consumers must treat as POSSIBLE/unresolved |
| javac unavailable | present index, diagnostic `javac_unavailable`, zero compilations; the compiler is checked after the walk, so a null supplier with zero remaining units emits **both** the walk rows and this one |
| walk, temp-directory creation, task `IOException`, `StackOverflow` in `analyze()`, `getTask` `IllegalArgumentException`, **or an exception in the post-analysis extraction pass** | present index, diagnostic `index_build_error` (mirrors the existing seam's `javac_source_error` failure semantics; StackOverflow wrapped like `JavacServiceLoaderSiteResolver.analyze`) |
| `javaRelease()` above the runtime feature | compile clamped to the runtime release + `release_clamped` diagnostic |
| javac ERROR diagnostics with a source file | captured as `IndexDiagnostic` rows with code/message/path/line/column |
| source-less javac ERROR (`getSource() == null`) | index-level diagnostic `javac_global_error` with an empty path (mirrors the seam); classpath-source javac errors render the repository-relative entry path |
| javac ERROR whose source URI is unmappable to the default filesystem (jar/classpath entry, `jar:file:` URIs) | the whole build is demoted to `index_build_error` with the entry rendered by file name recovered from the URI — mirroring the seam's `hasUnaddressableError` fail-closed semantics; never `Path.of` on the raw URI |

No scan-global blast radius: none of these reach
`ServiceLoaderDescriptorScanner.isUnique` in this subissue.

## Determinism and recorded cost

- Sorted file discovery, per-file declaration order, `LinkedHashMap`
  ordering throughout; two fresh contexts over identical sources produce
  equal type/member/annotation content (asserted).
- `wallMillis` = nanoTime around the whole build; `peakHeapBytes` =
  `MemoryMXBean` heap `used` sampled before/after (max), the same indicative
  sampling the #16 harness uses. Both exposed on the index and recorded in
  `RESOLUTION-21.md`.

## Test matrix (`JavacSourceSymbolIndexTest`, new file)

The class runs `@Execution(ExecutionMode.SAME_THREAD)` because it owns the
flag and the `COMPILATIONS` counter; all counter assertions are deltas.
Every cell that touches the system property or the compiler supplier
restores both in `@AfterEach` — a leaked `flpath.java.symbolindex=true`
would become a cross-class hazard once later subissues wire consumers.
Fixture contexts in cells 8, 8b, and 14 set
`dependencyClasspathRequired(false)` so the Builder default (`true`
with an empty classpath list) does not co-fire `missing_classpath`;
cell 9 remains the sole owner of the `missing_classpath` *trigger*
shapes (absent entry, empty list); cell 13 owns only its rendering.
The compiler supplier is package-private injectable; one cell covers
`javac_unavailable` by injecting a supplier that returns `null` (present
index,
diagnostic, zero-compilation delta). `missing_source` is covered by cell
8b (zero-roots or all-out-of-scope context); its present-but-empty-roots
variant is covered by cell 8. `javac_global_error` and
`index_build_error` have no cells in this subissue: the source-less-error
trigger is constructible (the resolver test's 20 000-deep package import)
but adds a slow pathological unit, and the build-error trigger is not
reliably constructible across filesystems; both are recorded as residuals.
Derived from the issue's failure classes plus the JLS name-shape lessons of
PLAN-16 (D-series) — no consumer behavior is migrated, so name-resolution
precedence cells are out of scope here:

1. Flag off: `of()` empty, `COMPILATIONS` delta 0.
2. Flag on, one parse: two `of(context)` lookups ⇒ one compilation delta
   (regression: second lookup does not start another compiler); a fresh
   context over the same sources ⇒ one more.
3. `$`-name boundary cells (AGENTS boundary rule, PLAN-16 D-series):
   top-level `demo.Cash$Flow`, nested `Outer.Inner`, top-level
   `demo.Outer$Inner` with decoy `demo/Outer.java` (prefix/suffix
   overlap), leading `$` (`demo.$Flow`), adjacent `$$`
   (`demo.Cash$$Flow`), single-character `demo.$`, `$` in the package
   (`a$b.Outer`). Note the D3 shape is *inherently* a binary-name
   collision (top-level `demo.Outer$Inner` and nested
   `demo.Outer.Inner` share binary name `demo.Outer$Inner`), so the cell
   asserts per lookup string: `type("demo.Outer.Inner")` → the nested
   entry only; `type("demo.Outer$Inner")` → **both** entries, both
   duplicate-flagged (the correct behavior, not a decoy leak). Other
   entry is returned. An ordinary lookup whose canonical and binary names
   coincide (e.g. `type("demo.Cash$Flow")`) returns exactly **one**
   entry — the distinct-union guard. A scratch probe runs before RED for
   the D3 collision shape (both entries present in the index with usable
   keying); if the second symbol does not survive the enter-phase
   duplicate error with a usable binary name, the cell's `type()`
   assertions weaken to cell 10's `typesIn`-based shape and the
   divergence is recorded. Cell 3b also asserts `typesIn` on an in-repo
   (non-generated) source root resolves via its absolute normalized path,
   **and** that a non-normalized absolute spelling
   (`root/x/../demo/Foo.java`) returns the same entries as the
   normalized lookup — the normalization claim probed, not inspected.
   CWD-relative spellings resolve against the process working directory
   by design (`toAbsolutePath` semantics); root-relative resolution is
   deliberately NOT offered, because suffix matching is a silent-match
   hazard.
3b. Overlapping roots: a generated root nested inside a source root (or
   the same root declared twice) yields each shared file exactly once —
   no `duplicate_binary_name`, no duplicate compilation unit.
3c. Release clamp boundary: `javaRelease() == Runtime.version().feature()`
   (no clamp, no diagnostic) and `javaRelease() == feature + 1` (clamped,
   `release_clamped` diagnostic) — the `Math.min` boundary probed
   empirically, not by inspection.
4. Local/anonymous classes: indexed by binary name only — positive
   assertions `type("demo.Outer$1Local")` and `type("demo.Outer$1")`
   return exactly the local/anonymous entry (a silent drop would fail
   the cell, not pass vacuously); `type("")` and
   `type("demo.")` (empty and truncation boundary shapes) return empty
   lists. The local-class qualified-name spelling is part of the pre-RED
   scratch probe: if `TypeElement.getQualifiedName()` for a local class
   is not the empty string, line 192's "binary name only" claim and this
   cell are amended to the observed behavior.
5. Annotations: declaration and type annotations with values (class-literal
   array, string, int, nested annotation, annotation array) come back with
   canonical names and rendered values — the cell names the member shape
   it asserts (method declaration, field type-use annotation, constructor).
6. Member shapes: parameter and return types for array/generic/type-use-
   annotated parameters resolve to canonical erased names (A18/A19
   shapes), including a nested-type parameter/return asserting the
   canonical dotted spelling (`demo.Outer.Inner`). The cell also probes
   the declaration-kind and overload families: a `record` with an
   annotated component, an `enum` with a constant, an `@interface`, and
   two overloaded methods (`foo(String)` / `foo(String, int)`) whose
   `members(type, "foo")` entries are distinguishable by
   `parameterTypes`; a `package-info.java` with a package annotation is
   read back through `packageAnnotations(Path)`, including a
   non-normalized absolute spelling of the path; a non-`package-info`
   path returns empty (the miss shape).
7. Error type: a member referencing a missing type ⇒ `errorType` + javac
   error diagnostic present.
8. Missing source root: absent root dir ⇒ `missing_source_root` diagnostic;
   when it was the only root, the build short-circuits with a
   `COMPILATIONS` delta of 0. A present-but-empty root (wrong-root-path
   misconfiguration): when every present root yields zero units, a
   `missing_source` diagnostic is emitted — never a silent empty index.
8b. No included source roots in scope: a required root whose scope
   differs from `analysisScope` (e.g. a `TEST`-scope root under
   `analysisScope=PRODUCTION`; a zero-roots Builder context throws at
   `build()` because
   `AnalysisContext` requires at least one required non-generated root):
   present index,
   `missing_source` diagnostic, `COMPILATIONS` delta 0 — the cheapest
   row in the fail-visible table gets a test owner.
9. Missing classpath: required absent entry ⇒ `missing_classpath`
   diagnostic; required empty classpath list ⇒ `missing_classpath`
   (the outside-`repositoryRoot` rendering shape lives in cell 13).
10. Duplicate binary name: same package/class in two roots ⇒ both entries
    retrievable via `typesIn(Path)`, both flagged, `duplicate_binary_name`
    diagnostic present; second unit's member completeness NOT asserted.
11. Determinism: two fresh indexes over the same sources compare equal;
    on a built index `wallMillis() >= 0` and `peakHeapBytes() > 0` (the
    acceptance criterion "record peak heap and wall time" has a test
    owner, not just prose).
12. Flag-off wiring invariant: `ServiceLoaderDescriptorScanner.scan` and a
    ServiceLoader resolution produce equal results with the flag off and
    on (the seam is not accidentally wired into fact output); the flag-on
    arm first calls `of(context)` and asserts a present index, so the
    index build path (and its static surface) actually executes in the
    same JVM before the comparison.
13. Diagnostics never contain absolute paths, including the
    `missing_classpath` row, which is constructed with an entry outside
    `repositoryRoot` and asserts the file-name rendering (no `../`
    chain), and a root declared with an absolute `declaredPath` whose
    indexed-unit rows fall back to the repository-relative renderer.
14. `javac_unavailable`: inject a supplier **returning `null`** (not a
    null supplier reference) ⇒ present index,
    `javac_unavailable` diagnostic, zero-compilation delta; a second
    `of()` returns the same cached index with the delta still 0
    (failure caching pinned).

## TDD sequence

- **RED_21**: `JavacSourceSymbolIndexTest` with the matrix above plus a
  behavior-neutral stub `JavacSourceSymbolIndex` (documented in the
  commit; the stub exists only so RED compiles). The stub's full member
  surface: `static boolean enabled()` (returns false), `static
  Optional<JavacSourceSymbolIndex> of(AnalysisContext)` (always empty),
  `static final AtomicLong COMPILATIONS`, the package-private injectable
  `static final AtomicReference<Supplier<JavaCompiler>> COMPILER`
  defaulting to `ToolProvider::getSystemJavaCompiler`, the nested records
  (`TypeShape`, `TypeInfo`, `MemberInfo`, `AnnotationInfo`,
  `IndexDiagnostic`), and no-op instance read methods (`type`,
  `typesIn`, `packageAnnotations`, `members`, `diagnostics`,
  `wallMillis`, `peakHeapBytes`). Expected-red at
  RED: cells 2–11, 3b, 3c, 8b, 12, 13, 14 (any cell
  requiring a present index or counter movement);
  expected-green: cell 1 only (the
  stub changes nothing observable for it — cell 12's flag-on arm asserts
  a present index and is therefore red at RED). The stub is held to the
  same PMD constraints as the implementation (no `static` on nested
  records, parameter counts), so `make java-lint` passes at RED;
  `make evidence-validate` passes at RED; `make adapter-test` runs at
  RED to confirm the expected-red cells fail and the rest of the suite
  stays green (the full green adapter-test gate per AGENTS.md first
  applies at GREEN_21, since RED intentionally contains failing tests).
- **GREEN_21**: implement the index; targeted test passes; run full
  `make adapter-test`, `make java-lint`, `make evidence-validate`; raise the
  Makefile count floor to 349 + new tests.
- **EVIDENCE_21**: `RESOLUTION-21.md` with wall time, peak heap,
  determinism receipts, gate counts, and residuals; commit gate receipts.

## Adversarial plan review (pre-implementation)

Findings folded back into the plan:

1. **`Optional.empty()` conflates "flag off" with "build failed".** Accepted
   for #21: the flag check is the caller's first question, and a failed
   build still returns a present index carrying diagnostics. Documented in
   the API javadoc.
2. **`WeakHashMap` + `computeIfAbsent`.** `Collections.synchronizedMap`
   synchronizes `computeIfAbsent` under its mutex (JDK 9+; the repo targets
   JDK 17+), and `AnalysisContext` uses identity equality — exactly the
   "per context instance" semantics wanted. The mapping function holds the
   mutex for a whole javac parse, so concurrent `of()` calls serialize
   behind one compile; accepted (same trade as the existing seam). Note:
   that seam is keyed by `SourceMetadataScanner`, so with the flag on one
   context pays two parses (site resolver + index); recorded in
   `RESOLUTION-21.md`'s cost table.
3. **javac unavailable** (`ToolProvider.getSystemJavaCompiler() == null`):
   return a present index with a `javac_unavailable` diagnostic and no
   compilation, instead of an exception. Fail visible, not loud.
4. **Annotation values must not use `AnnotationValue.toString()`** — javac's
   rendering is not a stable contract (and the textual path's `(value=1)`
   spellings are the known-bad pinned in PLAN-16). The index renders values
   itself: class literal → canonical name, `String` → quoted, enum →
   `Owner.NAME`, array → `{a, b}`, **nested annotation →
   `@Name(key=value, ...)` rendered recursively**, other → `toString()`.
5. **Duplicate binary names may break attribution of the second unit**, so
   its member list can be incomplete; acceptable because both entries are
   flagged and consumers must abstain — the safe direction per AGENTS.md.
   The duplicate test asserts the minimum guaranteed contract (both files
   present in `typesIn`, both flagged, diagnostic present) and does NOT
   assert the second unit's member completeness; observed attribution
   behavior is recorded as a residual in `RESOLUTION-21.md`.
6. **Diagnostics must never leak absolute paths.** `context.displayPath`
   returns the absolute path for anything outside the included source
   roots, so it is used **only for source-file diagnostics**. Classpath and
   root-directory diagnostics render repository-relative or declared
   paths (never `displayPath`'s absolute fallback). Asserted by a test
   cell that includes a `missing_classpath` row.
7. **Test-count gate**: the Makefile records 338 while the real baseline is
   349; GREEN raises the floor to 349 + new tests.
8. **`COMPILATIONS` and the flag are shared static state under JUnit
   parallel execution** (methods in the same class run concurrently). The
   test class is annotated `@Execution(ExecutionMode.SAME_THREAD)` — only
   this class reads the flag or the counter — and all counter assertions
   are **deltas** around the observed region, never absolute values.
9. **`Map<String, TypeInfo>` cannot hold two same-binary-name entries.**
   Name lookups are `Map<String, List<TypeInfo>>`; `type(name)` returns
   every entry sharing the name, each flagged. The duplicate test asserts
   both files' entries are retrievable via `typesIn(Path)` and both carry
   the flag.
10. **A required-but-empty classpath is a silently skipped input** (AGENTS
    defect class): `dependencyClasspathRequired() &&
    dependencyClasspath().isEmpty()` also emits `missing_classpath`.
    `applicationClasses` absence stays silent (routinely absent in tests,
    javac tolerates the nonexistent entry).
11. **`-classpath` is always emitted** as `dependencyClasspath +
    applicationClasses`, composed caller-side — seam-local and identical
    by construction to the existing seam's `options()` (the "omit when
    empty" branch is dead — `applicationClasses` is never null).
12. **PMD/CPD gates on main sources**: `ExcessiveParameterList` fires at 8
    parameters, so `TypeInfo` is restructured to at most 7 components —
    the state folds into a `TypeShape(declarationKind, errorType,
    duplicateBinaryName)` record. The build pass is split into helper
    methods to stay under the `CognitiveComplexity` threshold. The javac
    task skeleton stays **index-only**: #21 does not sanction modifying
    `JavacServiceLoaderSiteResolver`, so no shared task-construction
    helper is extracted. The two seams' task construction differs by
    construction (the resolver compiles in-memory `TextSource` units
    with no `-sourcepath`/`-d`; the index compiles from-disk units with
    explicit sourcepath/`-d` dirs and an extraction pass), so the
    duplicated token run stays well below CPD's 100-token tile —
    verified empirically with `make java-lint` before GREEN, not only
    after. If CPD still fires, a shared five-parameter helper
    (compiler, file manager, diagnostics, options, units; callers own
    the manager lifecycle) is the documented follow-up, taken in its
    own change with the resolver tests as characterization. Nested
    records are declared **without** the implicit
    `static` modifier (`UnnecessaryModifier` fires otherwise).
13. **Cell 12 is a wiring invariant, not output equivalence**: with no
    consumer migrated, scan output cannot observe the flag; the test
    proves the seam is not accidentally wired in. `Scan`/`Resolution`/
    `Evidence` are records with content equality, so the comparison is
    deterministic.

## Residuals

- With the flag on, one context pays two javac parses (site resolver +
  index); the seams are keyed differently on purpose. Recorded with wall
  time and peak heap in `RESOLUTION-21.md`.
- On duplicate binary names, the second unit's attribution may be
  incomplete; both entries are flagged so consumers abstain, and the
  observed attribution behavior is recorded in `RESOLUTION-21.md`.
- `missing_source` for zero-roots or all-out-of-scope contexts is
  covered by cell 8b; the `missing_source` variant for
  present-but-empty roots is covered by cell 8. `missing_classpath` is likewise reachable only through
  Builder-constructed contexts: the production loader rejects absent
  required classpath entries and required-empty lists before a context
  exists (`AnalysisContextLoaderLayer04/05`), so the row guards
  programmatic construction, not loader-built contexts.
- The index reads sources from disk at `of()` time while the site resolver
  compiles the scanner's content snapshot; a file modified between the two
  walks yields different javac views. Consumers must build the index after
  source materialization (matters from the consumer-migration subissues
  on).
- `module-info.java` is excluded (module resolution against an empty
  sourcepath manufactures error diagnostics); `package-info.java` is
  included. This diverges from `scanner.files()`, which excludes both.
- `javac_global_error` and `index_build_error` have no test cells (see
  the matrix preamble for the constructibility reasoning); both are
  defensive rows verified only by the shared failure-path code review.
  The jar-entry demotion path (source URI unmappable to the default
  filesystem) likewise has no cell — the resolver test's broken-jar
  fixture pattern is the follow-up candidate.
- `packageAnnotations` cannot distinguish a miss (path is not an indexed
  `package-info` unit, including spelling misses) from an indexed
  `package-info` with zero annotations — both return empty. The later
  package-annotation consumer subissue inherits this stated contract.
- The index re-implements the 8-line repository-relative-or-file-name
  path rendering context-side instead of extracting
  `ServiceLoaderDescriptorScanner.diagnosticPath` (out of #21 scope);
  a later cleanup may unify them.
- `typesIn` normalization is lexical (`toAbsolutePath().normalize()`),
  matching the walk keys' spelling; a caller passing a different symlink
  spelling of the same root can still miss. Symlinked source roots are a
  documented residual (both seams share the behavior).
- `javaRelease` values below the toolchain's minimum supported
  `--release` (a positive integer is the only Builder validation) surface
  as `index_build_error` — same behavior as the existing seam; no cell.
- No consumer reads the index yet (by scope); later #20 subissues migrate
  annotations, framework, and reflection consumers behind the flag.
- `peakHeapBytes` is a before/after `MemoryMXBean` sample, not JFR;
  indicative only.
- The index does not follow inheritance outside the selected roots
  (same residual class as PLAN-16); error types make that visible instead
  of guessed.
- One javac parse per context instance; contexts that share roots but are
  distinct instances re-parse (WeakHashMap is keyed by instance). A
  content-keyed cross-context cache is a follow-up if profiling demands it.
