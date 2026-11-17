# FLPATH-5078 production-integration review

## Verdict

**CONDITIONAL-GO — 94% confidence.**

It is reasonable to draft a separate production integration PR after the
preconditions in this report are satisfied. It is not reasonable to merge or
release the prototype as-is. The prototype remains an alternative bounded-
memory parser, not a production implementation.

This review is read-only with respect to production. No production source,
commit, push, GitHub state, or production configuration was changed.

## Scope and evidence

- Production checkout reviewed: `../flpath-5078-fix` at the requested
  `a2f36294abc0d4ecd0e92b4657d5a4f79ea7c30b`.
- Fixed prototype and gate evidence reviewed in this directory:
  `SeekProbe.java`, `Spill.java`, `verify.py`, `FIX-NOTES.md`, and
  `review-verdict.md`.
- The byte-preserved original prototype and its design/readiness notes were
  used only for integration limitations and policy context:
  `../pr7-seekable-revision-prjUvQCc/README.md`.
- The fixed harness was compiled with `javac --release 17` but executed on
  Temurin 21.0.13+9; Java 17 runtime parity remains unverified
  (`FIX-NOTES.md:6-10`, `review-verdict.md:86-92`).

## 1. Production reality and defect-presence map

### Production call path

The production ServiceLoader metadata path is:

1. `JavaAnalysisAdapter.analyze` resolves dependencies, builds the graph, and
   calls enrichment (`../flpath-5078-fix/src/main/java/com/projectlightpath/analysis/java/JavaAnalysisAdapter.java:32-39`).
2. `ProviderFactNormalizer.normalize` scans ServiceLoader descriptors only when
   the graph contains a `service_loader` edge, passing the dependency
   resolution into `ServiceLoaderDescriptorScanner`
   (`../flpath-5078-fix/src/main/java/com/projectlightpath/analysis/java/ProviderFactNormalizer.java:29-40`).
3. `ServiceLoaderDescriptorReader.scan` creates one shared `ScanState`, scans
   application classes, source roots, observed metadata, and dependency entries
   (`../flpath-5078-fix/src/main/java/com/projectlightpath/analysis/java/ServiceLoaderDescriptorReader.java:24-40`,
   `:42-80`).
4. `ServiceLoaderDescriptorSource.scanClasspathEntry` sends regular archive
   files to `ServiceLoaderDescriptorArchiveSource.scan`
   (`../flpath-5078-fix/src/main/java/com/projectlightpath/analysis/java/ServiceLoaderDescriptorSource.java:36-50`).
5. `ServiceLoaderDescriptorArchiveSource.scan` opens the file with
   `java.util.zip.ZipFile`, enumerates it, then reads selected service entries
   and `module-info.class`
   (`../flpath-5078-fix/src/main/java/com/projectlightpath/analysis/java/ServiceLoaderDescriptorArchiveSource.java:3-14`,
   `:19-34`).

The graph/classpath path is separate: dependency files are also handed to
SootUp as `PathBasedAnalysisInputLocation`s and placed in a `JavaView`
(`../flpath-5078-fix/src/main/java/com/projectlightpath/analysis/java/SootUpInputLocationFactory.java:69-84`,
`:30-56`). A seekable metadata parser would not replace SootUp's own archive
class-loading path.

### What production owns versus what the JDK owns

| Behavior | Current owner | Evidence and consequence |
|---|---|---|
| Open archive and parse central-directory/ZIP64 structure | JDK `ZipFile` | Production calls only `new ZipFile(...)` (`ServiceLoaderDescriptorArchiveSource.java:24`); there is no production EOCD, ZIP64-locator, or central-directory parser in the reviewed `ServiceLoaderDescriptor*.java` path. |
| Enumerate `ZipEntry` metadata | JDK `ZipFile` plus production policy | Production calls `zip.entries()` and obtains `ZipEntry`s (`ServiceLoaderDescriptorArchiveSource.java:37-45`). Its own `tryEnumerate` policy is applied after the JDK archive has opened/indexed. |
| DEFLATE and stored-entry decompression | JDK stream returned by `ZipFile` | Production calls `zip.getInputStream(entry)` (`ServiceLoaderDescriptorArchiveSource.java:135-136`, `:164-165`) and does not call `Inflater.inflate()`. |
| Shared output accounting, one-byte sentinel, diagnostics | Production | `readEntry` bounds each read by remaining budget, charges returned bytes, writes only after charging, and performs the sentinel read (`ServiceLoaderDescriptorArchiveSource.java:173-203`). |
| Service-name/provider parsing and module parsing | Production | Provider bytes are decoded and matched in `ServiceLoaderDescriptorSource.providers` (`ServiceLoaderDescriptorSource.java:159-173`); module bytes go through `ModuleDescriptor.read` (`:176-196`). |
| Seekable EOCD/CEN validation, direct `Inflater` accounting, spill sorting, rollback | Prototype only | `SeekProbe` contains the custom directory parser (`SeekProbe.java:72-100`), direct inflater loop (`:202-241`), and archive/result transaction flow (`:243-268`, `:357-373`). `Spill` is explicitly lab-only (`Spill.java:8-19`). |

The independent prototype reference engine also deliberately uses
`ZipFile` and says it is not the implementation because `ZipFile` eagerly
indexes the central directory (`SeekProbe.java:273-301`; the original design
notes make the same distinction at `../pr7-seekable-revision-prjUvQCc/README.md:83-84`).

### Defect (a): exceptional DEFLATE output escaping the budget

**Status in current production: ABSENT as the prototype defect.**

- The production archive path has `ZipEntry`, `ZipException`, and `ZipFile`
  imports, but no `Inflater` import or direct `inflate()` call
  (`../flpath-5078-fix/src/main/java/com/projectlightpath/analysis/java/ServiceLoaderDescriptorArchiveSource.java:7-14`).
- Bytes are charged only after `InputStream.read` returns a positive count,
  before those bytes are copied into the result
  (`../flpath-5078-fix/src/main/java/com/projectlightpath/analysis/java/ServiceLoaderDescriptorArchiveSource.java:181-194`).
  A stream exception is handled by the surrounding archive-entry diagnostic
  path (`:149-152`, `:164-169`); there is no custom success-only
  `Inflater.inflate()` accounting site where `getBytesWritten()` output can
  bypass the charge.
- The prototype defect is specifically the direct `Inflater.inflate()` path:
  the fixed prototype now snapshots `getBytesWritten()` around the call and
  charges the exceptional delta once (`SeekProbe.java:216-241`; the defect
  description is recorded at `FIX-NOTES.md:17-27`).

This is a source-level production map, not a claim that all JDK versions have
identical malformed-DEFLATE internals. The required Java 17 runtime probe is a
release precondition.

### Defect (b): count-only ZIP64 sentinel rejecting classic 65,535 entries

**Status in current production: ABSENT as the ZIP64-locator defect, but a
separate 1,024-entry policy can still make such an archive incomplete.**

- Production does not inspect EOCD count/size/offset fields or a ZIP64 locator;
  it delegates that format decision to `ZipFile` at
  `ServiceLoaderDescriptorArchiveSource.java:24`.
- The validated gate evidence records that a classic ZIP with exactly 65,535
  entries is accepted by the JDK `ZipFile` reference and that the fixed seek
  parser matches it (`FIX-NOTES.md:29-36`, `:59-66`; `review-verdict.md:36-45`).
  This is the JDK behavior production receives, subject to runtime-version
  validation.
- Production has an independent shared enumeration control:
  `MAX_ENUMERATED_ENTRIES` is 1,024
  (`../flpath-5078-fix/src/main/java/com/projectlightpath/analysis/java/ServiceLoaderDescriptorSource.java:24-32`).
  `ArchiveSource.enumerate` calls `tryEnumerate` for every `ZipEntry` before
  filtering directories or selecting service metadata
  (`../flpath-5078-fix/src/main/java/com/projectlightpath/analysis/java/ServiceLoaderDescriptorArchiveSource.java:37-64`).
  Once the cap is hit, it returns no archive candidates
  (`:65-68`), and `ScanState.tryEnumerate` emits
  `descriptor_enumeration_limit` (`../flpath-5078-fix/src/main/java/com/projectlightpath/analysis/java/ServiceLoaderDescriptorSource.java:269-284`).
- The existing production test deliberately verifies that an archive with more
  than 1,024 entries yields no descriptors and a limit diagnostic
  (`../flpath-5078-fix/src/test/java/com/projectlightpath/analysis/java/ServiceLoaderCoverageTest.java:432-465`).

Therefore, a 65,535-entry archive is not currently a successful full metadata
scan under the production policy, but it is not rejected for a false
`missing_zip64_locator` rule. Removing or changing the 1,024 policy is a
product decision, not an automatic consequence of the prototype ZIP64 fix.

### Production resource semantics that matter to integration

- The default shared decompressed budget is 16 MiB
  (`../flpath-5078-fix/src/main/java/com/projectlightpath/analysis/java/ServiceLoaderDescriptorSource.java:28-29`,
  `:225-241`).
- `readEntry` uses an 8 KiB heap buffer and a shared remaining budget
  (`ServiceLoaderDescriptorArchiveSource.java:177-183`); it does not have the
  prototype's separate per-entry `spike.entryMax` control.
- `ScanState` is shared across the whole reader invocation
  (`ServiceLoaderDescriptorReader.java:30-40`), so decompression and entry
  enumeration are cross-source policies, not independent per-archive budgets.
- `ArchiveSource` stores selected `ZipEntry` references in a bounded
  `TreeMap`, but the JDK `ZipFile` has already opened/indexed the archive before
  that map and the production enumeration cap are applied
  (`ServiceLoaderDescriptorArchiveSource.java:24`, `:37-68`).

## 2. Integration surface

The prototype is not wired into any production call site. The harness compiles
the six production descriptor helpers plus `SeekProbe.java` and `Spill.java`
as an external class set (`verify.py:18-24`), and the prototype itself is
labelled experiment-only (`SeekProbe.java:12-16`).

### Code and call-site changes an integration PR would have to design

| Surface | Current production seam | Required integration decision |
|---|---|---|
| Archive acquisition and enumeration | `ServiceLoaderDescriptorArchiveSource.scan/enumerate` opens `ZipFile`, calls `entries`, selects service entries/module info, and emits WAR nested-metadata diagnostics (`ServiceLoaderDescriptorArchiveSource.java:19-68`, `:53-55`) | Replace or feature-select the archive reader while preserving source ordering, `ZipEntry`-equivalent metadata, WAR diagnostics, incomplete-scan diagnostics, and selected-entry behavior. |
| Shared budget/state | `ServiceLoaderDescriptorSource.ScanState` owns decompressed bytes, enumeration count, diagnostics, and exhausted state (`ServiceLoaderDescriptorSource.java:225-295`) | Define production-owned policy objects/configuration for directory I/O, selected expansion, per-entry materialization, spill quota, CPU/deadline, and cancellation. Do not expose `spike.*` as product policy. Decide whether the current shared 1,024 limit remains. |
| Entry materialization | `ArchiveSource.readEntry` reads `ZipFile` streams for descriptors and module info (`ServiceLoaderDescriptorArchiveSource.java:84-120`, `:123-152`) | Decide whether the parser returns byte arrays, a bounded stream, or a disk-backed handle. Preserve existing provider/module parsing semantics or explicitly version the change. |
| Reader scope and source ordering | `ServiceLoaderDescriptorReader.scan` uses one state across application classes, source roots, observed metadata, and dependencies (`ServiceLoaderDescriptorReader.java:30-80`) | Define the lifetime of a parser/spill context and whether rollback is per archive, per source, or whole analysis. Keep path authorization and no-follow/symlink rules in `ServiceLoaderDescriptorAuthorization` (`:14-33`, `:58-121`). |
| Result model and certainty | `ProviderFactNormalizer` calls the scanner (`ProviderFactNormalizer.java:32-40`); `ServiceLoaderDescriptorScanner.Scan` copies its descriptor list (`ServiceLoaderDescriptorScanner.java:183-191`); resolution consumes it as a list (`:41-47`) | A `Spill.Descriptors` result is not a drop-in bounded-memory replacement: the prototype explicitly notes that production `Scan(List)` copies descriptors (`Spill.java:119-121`). Either retain a bounded materialization contract or change the scanner/resolver/result API and prove certainty behavior at scale. |
| SootUp graph path | `SootUpInputLocationFactory` still creates SootUp locations for dependencies (`SootUpInputLocationFactory.java:69-84`), and `SootUpBaseGraphProvider` still builds the `JavaView` (`SootUpBaseGraphProvider.java:30-39`) | Keep this separate from ServiceLoader metadata parsing. A PR must state whether SootUp archive behavior is intentionally unchanged and test any disagreement between class loading and descriptor scanning. |
| Configuration/operations | Current `ScanState()` takes only the hard-coded default budget (`ServiceLoaderDescriptorSource.java:232-241`); prototype controls are JVM properties such as `spike.rawMax`, `spike.directoryMax`, and `spike.entryMax` (`SeekProbe.java:20-21`, `:252-264`; `Spill.java:14-19`) | Add an explicit production configuration boundary, ownership, defaults, observability, tenant/concurrency limits, temp-root permissions, cleanup, and cancellation behavior. No current production config can be assumed to be the correct home without a policy decision. |

### Tests that must be touched or added in an integration PR

Existing tests already cover the production budget/enumeration contract and
must remain green:

- Archive invalid-entry, shared decompression budget, and exact-boundary
  behavior: `ServiceLoaderCoverageTest.java:237-267`.
- Directory/archive enumeration limits and shared-limit diagnostics:
  `ServiceLoaderCoverageTest.java:432-489`.
- WAR nested metadata exclusion and diagnostic cap:
  `ServiceLoaderWarDescriptorTest.java:19-74`, `:127-149`.
- Module-budget and module-info resolution behavior:
  `JavaAnalysisAdapterTest.java:1874-1933`, `:1935-2020`.

The prototype gate cases must be ported as production tests, not merely kept
in the external harness:

1. Malformed DEFLATE that emits bytes before failure; assert exact budget
   accounting, exhausted-state preservation, no later-archive evidence, and
   rollback.
2. Classic exactly-65,535-entry ZIP; claimed-count mismatch; size/offset
   sentinel without locator; genuine ZIP64 with locator; assert diagnostics and
   reference metadata.
3. JDK differential corpus, including UTF-8 comments, valid/invalid ZIP64
   extras, SFX/prefixed archives, malformed local/CEN relationships, overlaps,
   duplicate selected entries, and unsupported methods/flags.
4. Spill write/read failure, cancellation/interruption, temp cleanup, output
   transaction failure, concurrent archive mutation, and multiple-archive
   result semantics.
5. Java 17 runtime execution of the same cases; `--release 17` compilation
   alone is insufficient (`pom.xml:12-14`; `FIX-NOTES.md:9-10`).

## 3. Risk register

| Severity | Risk | Evidence | Required treatment |
|---|---|---|---|
| High | **JDK central-directory memory is outside the production budget.** `ZipFile` is opened before the 1,024 policy can stop enumeration, and the prototype explicitly avoids it because it eagerly indexes CEN (`ServiceLoaderDescriptorArchiveSource.java:24`, `:37-45`; `SeekProbe.java:273-275`). | A large CEN can consume heap before production's selected-entry map or decompressed budget helps. | Benchmark realistic large-entry and 65,535-entry archives under production heap/concurrency limits; compare JDK and spill RSS, CPU, temp I/O, and latency. |
| High | **Lab controls could be mistaken for product defaults.** The prototype uses 64 MiB raw/directory controls, 128 MiB live spill, 1 MiB selected-entry materialization, and 1,024-character/line validation guards (`SeekProbe.java:20-21`, `:252-264`; `Spill.java:14-19`). | The prototype calls these experiment controls, not production policy (`SeekProbe.java:12-16`; `FIX-NOTES.md:81-86`). Production currently has a separate hard-coded 16 MiB shared expansion and 1,024-entry enumeration policy (`ServiceLoaderDescriptorSource.java:28-29`). | Obtain product/operations decisions for each budget, scope (archive/analysis/tenant), exhaustion behavior, observability, and defaults before coding. |
| High | **ZIP compatibility changes can be silent correctness regressions.** The prototype rejects prefixed ZIP64 and some malformed structures, while it does not validate ignored payload CRC/decompression (`SeekProbe.java:87-96`; `../pr7-seekable-revision-prjUvQCc/README.md:133-140`; `FIX-NOTES.md:85-86`). | `ZipFile` and the custom parser need not accept/reject the same corpus. | Establish an explicit compatibility matrix and product policy for prefixed ZIP64, malformed-but-tolerated archives, ignored payload integrity, encrypted/multidisk inputs, and unsupported methods. |
| High | **Spill lifetime, cancellation, concurrency, and transport durability are unresolved.** `Spill` requires an explicit temp root and owns files (`Spill.java:19-50`); prototype output replay is transactional but its transport is not an atomic OS write (`SeekProbe.java:357-374`; `../pr7-seekable-revision-prjUvQCc/README.md:146-150`). | An open archive is not a content snapshot; interruption or concurrent replacement can produce inconsistent observations. | Design cancellation checkpoints, archive snapshot/lifetime rules, per-analysis/tenant temp quotas, crash cleanup, concurrent mutation handling, and complete-result publication before integration. |
| High | **The existing helper regex/object-allocation hazard remains.** Production decodes the complete selected byte array and validates every provider value with `BINARY_CLASS_NAME` (`ServiceLoaderDescriptorSource.java:159-173`; `:28-32`). | The prototype deliberately bounds selected names and provider lines to avoid the known helper regex recursion/object-allocation failure; the production helper stack-overflow issue is explicitly not fixed (`SeekProbe.java:257-264`; `../pr7-seekable-revision-prjUvQCc/README.md:119-124`, `:141-145`). | Either fix/replace the helper validation safely or make its bounds a reviewed production policy. Test deeply segmented names, long lines, many lines, Unicode, and malformed input. |
| High | **Disk-backed prototype results are not a drop-in replacement for production lists.** The scanner copies lists and certainty resolution streams them (`ServiceLoaderDescriptorScanner.java:41-47`, `:183-191`; `Spill.java:119-121`). | A parser can be bounded while later result materialization still recreates the memory problem or changes partial-result semantics. | Choose a bounded end-to-end result contract and assert no partial definite evidence on archive, spill, resolver, or output failures. |
| Medium | **Java 17 runtime behavior is unverified.** Production compiles for release 17 (`pom.xml:12-14`), but the fixed gate ran on JDK 21 (`FIX-NOTES.md:6-10`; `review-verdict.md:86-92`). | `ZipFile` ZIP64 and malformed-DEFLATE behavior are runtime-library behavior, not supplied by the production source. | Run the fixed corpus on the supported Java 17 runtime/vendor and compare diagnostics, exact metadata, resource/cleanup behavior, then repeat on the supported Java 21 runtime if both are deployed. |
| Medium | **Changing the current global 1,024-entry policy changes certainty and workload behavior.** The cap counts every archive entry, including ignored entries, and is shared across sources (`ServiceLoaderDescriptorArchiveSource.java:42-50`; `ServiceLoaderDescriptorSource.java:275-284`; `ServiceLoaderDescriptorReader.java:30-40`). | Removing it to make a 65,535-entry archive scannable may increase work and alter existing `POSSIBLE`/diagnostic outcomes; retaining it means many valid large archives remain incomplete. | Decide explicitly whether the cap is per archive, per analysis, candidate-only, or replaced by bounded full enumeration; preserve diagnostics and test certainty transitions. |

## 4. Preconditions for any production integration PR

- [ ] **Java runtime gate:** run the malformed-DEFLATE, classic-65,535, genuine
  ZIP64, malformed-corpus, differential, fault-injection, and cleanup cases on
  an actual supported Java 17 runtime. Record vendor/version and compare with
  the JDK 21 evidence.
- [ ] **Product policy gate:** approve expansion, directory-read, per-entry,
  enumeration, spill, CPU/deadline, concurrency, cancellation, and tenant
  quotas. Explicitly decide whether the current shared 16 MiB/1,024-entry
  behavior remains and do not promote `spike.*` values by default.
- [ ] **Compatibility gate:** document decisions for prefixed ZIP64, ignored
  payload integrity, malformed-but-tolerated ZIPs, encrypted/multidisk input,
  method/flag support, UTF-8/comment behavior, and SootUp's separate archive
  path.
- [ ] **End-to-end boundedness gate:** prove that CEN memory, selected entry
  bytes, provider parsing, descriptor/result storage, spill files, and output
  publication are bounded together. Include cancellation, concurrent mutation,
  process interruption, and temp cleanup.
- [ ] **Test gate:** port the prototype's two targeted regressions and its
  negative/differential/fault corpus into production tests, retain all current
  ServiceLoader/WAR/module/safety tests, and assert diagnostics, certainty,
  rollback, exact metadata, and no partial evidence.
- [ ] **Review gate:** obtain a fresh adversarial/security review of the actual
  production diff. This report authorizes no production code change and is not
  a substitute for that review.

## Final integrity statement

No production changes were made. The production checkout must remain clean at
`a2f36294abc0d4ecd0e92b4657d5a4f79ea7c30b`; this was verified after the report
was written with:

```text
git rev-parse HEAD
a2f36294abc0d4ecd0e92b4657d5a4f79ea7c30b

git status --porcelain --untracked-files=all
(empty)
```

The only review deliverable written by this task is
`prod-integration-review.md` in the review directory. No production repository
or GitHub state was mutated.
