# PLAN-9 — Issue #9 certainty gaps (static on-demand owner, inherited accessibility, `$` owner names)

Branch: `issue-9/certainty-gaps` (from `origin/main` at `6a46a85`).
Issue: project-lightpath/java-analysis-adapter#9 (scope narrowed by the task brief).
Companion documents: `AGENTS.md`, issue #9 receipt in Engram
(`java-analysis-adapter-issue-9`, `-part1`, `-part2`).

## Scope (binding)

Only the three gaps named in the task brief:

- **(a)** `ServiceLoaderInheritance.staticOnDemandAmbiguous` must match the
  *qualified owner* of a static on-demand import (`import static a.Tools.*`)
  instead of any selected file whose simple type name is `Tools`.
- **(b)** A package-private inherited member type is inherited only inside its
  own package (JLS 8.5 / 6.6.1). Thread the subclass/site package into the
  inheritance check; keep same-package eligibility, private exclusion,
  public/protected inheritance, and ordinary nested fallback intact. This is a
  **precision (over-abstention) fix**: head abstains where the member is not
  inherited, so no false DEFINITE is fabricated.
- **(c)** `SourceMetadataScannerTextLayer00.sourceFile(owner)` must try an
  exact owner match before truncating binary names at `$`, so a top-level
  class named `Cash$Flow` keeps attribution.

Out of scope and explicitly deferred (see Residuals): multi-statement derived
chains (`P p = load(...); p.record();`) and dependency-resolution diagnostics
when no `service_loader` edge exists.

## Spec derivation (not example enumeration)

### JLS 8.5 — inherited member types

For a direct superclass `S` of `C`, `C` inherits the member types of `S`:

| Member type modifiers | Same package as `S` | Different package |
|---|---|---|
| `public` | inherited | inherited |
| `protected` | inherited | inherited |
| package-private (no access modifier) | inherited | **not inherited** |
| `private` | not inherited | not inherited |
| local / anonymous | never a member | never a member |
| nested deeper than a direct member | not inherited | not inherited |

`implements` clauses, later `extends` names, multi-level chains,
nested-type-qualified superclasses and superclasses outside the selected file
set remain the documented residuals from AGENTS.md (FLPATH-5124).

### JLS 7.5.4 + 6.4.1 — static-import-on-demand ambiguity

`import static T.*;` imports the accessible static members of `T`. If `T`
declares a member type named `ServiceLoader` and the compilation unit also has
a type-import-on-demand that supplies `java.util.ServiceLoader`, the simple
name is ambiguous. The ambiguity is a property of the **named owner `T`**, so a
different selected type with the same simple name (`b.Tools`) must not create
it. Accessibility of the imported member is evaluated from the importing
compilation unit's package (shared inheritance predicate below; the
protected-member cross-package case is a safe over-abstention residual because
the predicate is stricter for imports than JLS 6.6.2 requires).

### JLS 13.1 — binary names

Nested types use `$` in binary names, but `$` is also a legal identifier
character in a top-level type name. Therefore a scanner given a binary owner
must first try the owner as an exact `typeName`; only then may it drop the last
`$` segment to find the enclosing top-level file. Repeated truncation is
required because an enclosing top-level type may itself contain `$`
(`demo.Cash$Flow$Inner` → `demo.Cash$Flow`).

## TDD cells

Test class: `src/test/java/com/projectlightpath/analysis/java/ServiceLoaderNameResolutionMatrixTest.java`
(existing matrix; new methods and new helpers only). Zero edits to existing
test methods. The plan sanctions helper changes only: `matrixSites` delegates
to a new package/owner-aware builder so cells can supply a non-`demo` package
and a `$` binary owner; assertions in existing tests do not change. Four new
helpers are explicit:

- `sourceFileOf(id, files, owner)` → `Optional<SourceFile>` for direct `(c)`
  file-resolution probes (C2–C9b);
- `scannerFor(id, files)` → the configured `SourceMetadataScanner` used by
  C10–C12 for direct scanner-method probes;
- `matrixSitesIn(id, bridgePackage, bridgeSource, extraFiles)` → owner is
  `bridgePackage + ".Bridge"`, used by A1–A7 and B4/B5 so a fixture in
  `package a;` is resolved with owner `a.Bridge`;
- `matrixSitesForOwner(id, owner, files)` → `MethodSymbol` owner is passed
  explicitly, used by C1 only (the sole cell that asserts a parsed site).

`matrixSites` delegates to `matrixSitesIn(id, "demo", …)` without assertion
changes.

### (a) Static on-demand owner matching

| Cell | Fixture | Expected | RED/GREEN |
|---|---|---|---|
| A1 | `package demo;` `import java.util.*;` `import static a.Tools.*`; `a.Tools` has no `ServiceLoader`; unrelated `b.Tools` has a public member `ServiceLoader` | site attributed | **RED** (head abstains) |
| A2 | A1 plus `a.Tools` also declares the public member | site abstained | guard (new owner-match-with-lookalike test) |
| A3 | owner-only static import with member `ServiceLoader` | abstained | guard (existing `staticOnDemandLookalikeMakesWildcardAmbiguous`) |
| A4 | `package demo;` `import java.util.*;` `import static a.Tools.*`; `a.Tools` member `ServiceLoader` is package-private | site attributed (member not accessible) | **RED** (head abstains) |
| A5 | A4 with bridge in `package a;` (owner `a.Bridge`) | site abstained (member accessible) | guard |
| A6 | `package demo;` `import java.util.*;` `import static a.Tools.*`; `a.Tools` member `ServiceLoader` is `private` | site attributed | guard (predicate's private exclusion) |
| A7 | `package demo;` `import java.util.*;` `import static a.Tools.*`; `a.Tools` member `ServiceLoader` is `protected` | site abstained | guard pinning the documented over-abstention |

All `(a)` cells include `import java.util.*;` so attribution is not vacuous.

### (b) Inherited member-type accessibility

The predicate is reached through three superclass-resolution branches, so B4
and B5 are each a three-cell row: (i) single-type import `import other.Base;`,
(ii) on-demand import `import other.*;`, (iii) package-qualified
`extends other.Base`. All cells keep `import java.util.*;`.

| Cell | Fixture | Expected | RED/GREEN |
|---|---|---|---|
| B1 | same package, package-private member `ServiceLoader` | abstained | guard (existing `inheritedMemberTypeFromSuperclassInAnotherFileShadows`) |
| B2 | same package, `private` member | attributed | guard (existing `privateNestedServiceLoaderDoesNotShadow`) |
| B3 | cross package, `public` member | abstained | guard (existing three-path fixtures: `singleTypeImportedSuperclassWithMemberTypeShadows`, `onDemandImportSuperclassNameShadows`, `qualifiedSuperclassNameIsResolved`) |
| B4 | cross package, `protected` member (three resolution paths) | abstained | guards (head already abstains; pins the new predicate on every path) |
| B5 | cross package, package-private member (three resolution paths) | attributed | **RED** (head over-abstains; precision gap) |
| B6 | same package, deeper nested type of a member (existing fixture) | attributed | guard (existing `nestedTypeOfMemberTypeIsNotInherited`) |

### (c) `sourceFile` owner matching

C1 uses `matrixSitesForOwner` (owner passed explicitly as the `MethodSymbol`
owner) plus `sourceFileOf`; C2–C9b use `sourceFileOf` only (file-resolution
assertions). C1 asserts `sourceFileOf(id, files, "demo.Bridge").isPresent()` in
the same fixture, so the fixture is not silently owner-blind. C10–C12 use
`scannerFor` and call the scanner methods directly.

The C1 fixture `demo/Cash$Flow.java` is
`package demo; import java.util.*; class Cash$Flow { void audit() { ServiceLoader.load(Object.class); } }`;
C1 asserts both the parsed site and the `demo.Bridge` resolution. C2–C9b only
assert file resolution, so their owner files need no call site.

| Cell | Owner | Expected | RED/GREEN |
|---|---|---|---|
| C1 | `demo.Cash$Flow` (top-level class named `Cash$Flow`) | exact file found, site parsed; `demo.Bridge` still resolves | **RED** (head truncates to `demo.Cash`) |
| C2 | `demo.$Flow` (leading `$`) | exact file found | **RED** (head truncates to `demo.`) |
| C3 | `demo.Outer$Inner` (ordinary nested) | `demo.Outer` file found | guard |
| C4 | `demo.Cash$Flow$Inner` (nested inside `$`-named top-level) | `demo.Cash$Flow` file found | **RED** (head truncates to `demo.Cash`) |
| C5 | `demo.Missing$Inner` | empty, no exception | guard |
| C6 | `demo.Plain` | exact file found | guard |
| C7 | `a$b.Service` (exact, `$` in package) | exact file found | **RED** (head truncates at the first `$` to `a`) |
| C8 | `a$b.Outer$Inner` | `a$b.Outer` file found | **RED** (head gives `a`) |
| C9 | `a$b.Outer` (no `$` in the simple name) | `a$b.Outer` file found; must never resolve a default-package type `a` | **RED** (head gives `a`) |
| C9b | `a$b.Missing$Inner` with a decoy default-package `a.java` selected | empty; never the decoy | **RED** (head gives `a`; a naive last-`$` loop also gives `a`; only a package-dot-bounded loop stays empty) |
| C10 | `declaredType("demo.Cash$Flow")` | returns the top-level type `Cash$Flow` | **RED** (head empty) |
| C11 | `methodSource(MethodSymbol("demo.Cash$Flow", "audit", "()V"), line)` where `line` is the `audit` declaration line in `Cash$Flow.java` | returns the `audit` method body | **RED** (head empty) |
| C12 | `sourceLineContains(MethodSymbol("demo.Cash$Flow", "audit", "()V"), line, "ServiceLoader")` where `line` is the line containing the `ServiceLoader.load(...)` call (not the declaration line) | true | **RED** (head false) |

Boundary probes required by AGENTS.md for `$` arithmetic, mapped to cells:
empty suffix / leading `$` (C2), single-segment name (C6), prefix/suffix
overlap (C4/C9b), `$` inside a package vs default-package decoy (C7–C9/C9b),
and exact-boundary (C1/C7/C9). Index arithmetic is probed by the tests, not by
inspection.

`ServiceLoaderCallSiteReader.read` is covered transitively: it calls both
`ServiceLoaderSourceCache.source` and `scanner.sourceFile` with the same
`edge.caller().owner()` argument that C1 pins, and returns empty only when
either is empty; no separate fixture adds a new owner-format contract.

## RED tests (tests only, new methods only)

1. `ServiceLoaderNameResolutionMatrixTest`:
   - `staticOnDemandUnrelatedOwnerDoesNotAbstain` (A1)
   - `staticOnDemandOwnerMatchWithLookalikeStaysAmbiguous` (A2)
   - `staticOnDemandPackagePrivateMemberCrossPackageDoesNotAbstain` (A4)
   - `staticOnDemandSamePackagePackagePrivateMemberStaysAmbiguous` (A5)
   - `staticOnDemandPrivateMemberDoesNotAbstain` (A6)
   - `staticOnDemandProtectedMemberCrossPackageStaysAmbiguous` (A7)
   - `crossPackagePackagePrivateMemberTypeIsNotInherited` — three tests, one
     per resolution path (B5)
   - `crossPackageProtectedMemberTypeIsInherited` — three tests, one per
     resolution path (B4)
   - `topLevelDollarNameIsResolvedExactly` (C1)
   - `leadingDollarTopLevelNameIsResolved` (C2)
   - `ordinaryNestedOwnerFallsBackToTopLevel` (C3)
   - `nestedInsideDollarNamedTopLevelIsResolved` (C4)
   - `unknownNestedOwnerIsUnresolved` (C5)
   - `plainOwnerIsResolved` (C6)
   - `dollarInPackageNameIsNotTruncated` (C7–C9, three assertions)
   - `missingDollarOwnerNeverResolvesDecoyDefaultPackageType` (C9b)
   - `declaredTypeResolvesDollarNamedTopLevel` (C10)
   - `methodSourceResolvesDollarNamedTopLevel` (C11)
   - `sourceLineContainsResolvesDollarNamedTopLevel` (C12)
2. New helper methods only in the same class: `sourceFileOf`, `scannerFor`,
   `matrixSitesIn`, `matrixSitesForOwner`; `matrixSites` delegates without
   assertion changes.

RED commit contains only these new tests/helpers; no production edits.
Compile fidelity: cross-package `Base`/`Tools` fixtures are `public`; cells
that call `assertCompiles` use a custom `load` method, while cells that rely on
`java.util.ServiceLoader.load` (no such method) never call `assertCompiles`.

## GREEN minimal changes

1. `ServiceLoaderInheritance.staticOnDemandAmbiguous(scanner, file, source)`:
   filter candidates by `candidate.typeName().equals(imported.name())`
   (`Import.name` is the normalized qualified owner without `.*`, per
   `JavaImports.parseQualifiedName`). Pass `file.packageName()` into the
   member-type predicate. The single caller is
   `ServiceLoaderSourceCache.create` (cache:105–106).
2. `ServiceLoaderInheritance.declaresInheritableServiceLoaderType(candidate,
   sitePackage)`:
   - keep local-type, private-member, and direct-member checks;
   - add `public`/`protected` detection on the masked declaration header;
   - a member type is eligible when it is `public`, `protected`, or the
     candidate package equals `sitePackage`.
   - two internal callers in `superclassDeclaresServiceLoader`
     (`ServiceLoaderInheritance.java` qualified branch lines 87–90 and
     simple-name branch lines 92–93) pass `file.packageName()`; this fixes all
     three resolution paths.
3. `SourceMetadataScannerTextLayer00.sourceFile(owner)`:
   - exact `typeName` match first;
   - then drop the trailing `$` segment repeatedly from the right, but never
     cross the last package dot (so `a$b.Outer$Inner` resolves
     `a$b.Outer`, never a default-package `a`);
   - unknown owner still returns empty.
   All five callers change resolution and are covered by guards:
   `ServiceLoaderSourceCache.source`, `ServiceLoaderCallSiteReader.read`,
   `SourceMetadataScannerTextLayer06.sourceLineContains`,
   `SourceMetadataScannerTextLayer17.declaredType`, and
   `SourceMetadataScannerTextLayer20.methodSource`. C10/C11/C12 pin
   `declaredType`/`methodSource`/`sourceLineContains`; C1 pins the cache path;
   `ServiceLoaderCallSiteReader.read` is covered transitively as stated above.
4. No diagnostics are added, so the scan-global `ServiceLoaderScanner.isUnique`
   blast radius does not change.

## Refactor step (EVIDENCE)

- In `declaresInheritableServiceLoaderType`, mask the candidate's structural
  source once per candidate and reuse it for every candidate `ServiceLoader`
  type (currently masked once per type).
- Extract a small modifier-header helper if PMD requires it; behavior and
  offsets stay identical. Document placement in the EVIDENCE commit.
- Characterization: all RED tests plus the existing matrix stay green.

## Review and gates

Plan review: this plan is reviewed adversarially before implementation; the
findings and dispositions are recorded in the `EVIDENCE_09` commit message
(plus the PR body). After pushing, post `@coderabbitai full review`, disposition
every finding, and run an independent change review before merge (AGENTS.md
"Before pushing").

Gates (after RED, after GREEN, after refactor):

- `make adapter-test` — zero failures; execution count must not decrease from
  the baseline captured on `6a46a85`.
- `make java-lint` — zero findings.
- `make evidence-validate` — pass.

## Commit series

- `RED_09`: new failing tests + owner-aware test helpers.
- `GREEN_09`: minimal production fix for (a), (b), (c).
- `EVIDENCE_09`: behavior-neutral refactor, gate receipts, plan-review
  dispositions and residuals.

## Residuals (documented, safe-direction)

- Static import whose owner is a nested type (`import static a.Outer.Tools.*`)
  is still not matched by the top-level `typeName` comparison; the wildcard
  stays attributed (possible under-abstention), not DEFINITE-fabricated.
- Import names are normalized by `JavaImports.appendNormalized` (identifier-
  ignorable code points stripped) while `typeName` is raw source text. An
  owner name containing an ignorable code point cannot match the owner
  comparison; this is not a regression from head and is listed here rather
  than silently assumed equivalent.
- The shared accessibility predicate treats a `protected` static member
  imported cross-package as eligible, so that shape over-abstains (safe; A7
  pins it).
- A non-static member type reached only through `import static T.*` is
  over-counted as ambiguous (safe over-abstention).
- (b) keeps the existing FLPATH-5124 residuals: multi-level inheritance,
  `implements` clauses, superinterfaces after the first `extends` name,
  nested-type-qualified superclasses, superclasses outside the selected files.
- (c) anonymous/local class indices (`demo.Outer$1`) resolve to the enclosing
  top-level file, which is the existing intended fallback.
- A top-level `demo.Cash$Flow` and a nested `Flow` in `demo.Cash` share a
  binary name. If both files are selected, exact-first prefers the top-level
  file; such a pair cannot compile together, but source scanning can reach it.
- Deferred per the task brief: multi-statement derived chains and
  dependency-resolution diagnostics when no `service_loader` edge exists.
