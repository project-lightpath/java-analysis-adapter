# Independent adversarial review verdict

**INDEPENDENT_REVIEW_PASSED — 96% confidence**

The two proposed prototype fixes are correct on the reviewed paths and are fit to proceed toward a separate production-integration review. This verdict does not authorize production integration.

## Scope and evidence

- Reviewed the local fixed `SeekProbe.java`, `Spill.java`, `verify.py`, fixtures, and prior reproduction sources.
- Diffed fixed `SeekProbe.java` against `../pr7-seekable-revision-prjUvQCc/SeekProbe.java`; the diff contains only the two claimed hunks. `Spill.java` is byte-identical.
- Used JDK `21.0.13+9`, with `javac --release 17`, and bounded child JVMs at `-Xmx32m` with the harness's 90-second process deadline.
- Kept the review directory unchanged during test runs by running disposable sibling copies. Only this verdict file was written here.

## Independent reproductions and reruns

### Defect 1: DEFLATE exception-path accounting

1. Re-ran the standalone prior probe after compiling it with `--release 17`:

   ```text
   threw=java.util.zip.DataFormatException: invalid block type bytesWritten=4096 bytesRead=4102
   ```

   This independently confirms that the malformed stored-block fixture emits 4096 bytes before `Inflater.inflate()` throws.

2. Ran `python3 verify.py gate-before` in a disposable copy with the original `SeekProbe.java`.

   - Exit: `1`
   - Suite result: `==== 8 checks failed ====`
   - The original charged `expanded=0` for the 4096 emitted bytes.
   - Repeated malformed archives all reported `invalid_deflate` instead of the first `invalid_deflate` followed by four `archive_decompression_limit` failures.
   - The later valid archive escaped the exhausted budget and emitted a descriptor; `expanded=21`.

3. The fixed source's `body()` at `SeekProbe.java:222-241` snapshots `getBytesWritten()` around every `inflate()` call, uses the delta only on `DataFormatException`, charges it once, and rethrows `invalid_deflate`. The successful path still charges the return value once. The stored path at `SeekProbe.java:210-215` remains unchanged and charges each stored read once.

### Defect 2: classic 65535-entry ZIP boundary

The fixed suite generated a classic ZIP with 65534 ignored entries plus one service entry using Python `zipfile.ZipFile(..., allowZip64=False)`, producing exactly 65535 classic entries. The original rejected it as `missing_zip64_locator`; the fixed source accepted it and matched the JDK reference descriptor.

The original replay also independently reproduced:

- classic claimed count 65535 with only 65534 actual entries: `missing_zip64_locator` instead of `cen_count_mismatch`;
- the genuine size-sentinel negative case remained rejected.

The fixed `directory()` change at `SeekProbe.java:80-96` removes only `expected==65535` from the sentinel predicate. Size and offset sentinels, locator recognition, ZIP64 record bounds, disk validation, ZIP64 count/field matching, central-directory bounds, and `walk()`'s actual `count==expected` check remain present.

### Full fixed-suite reruns

- `python3 verify.py gate` in a disposable copy: exit `0`, `==== 0 checks failed ====`, no child timeout, crash, OOM, or residual-temp failure.
- Final fixed replay after the original-source replay: `python3 verify.py gate-after-final`: exit `0`, 317 passing check calls, 0 failed.
- No child JVM timed out. All child runs used the harness's 32 MiB heap and 90-second deadline.

### Fresh negative ZIP64 probes

Freshly compiled fixed sources were run against newly patched copies:

| Probe | Observed diagnostic |
|---|---|
| EOCD offset sentinel without locator | `missing_zip64_locator` |
| ZIP64 locator with disk number 1 | `unsupported_multidisk` |
| ZIP64 EOCD record length 43 | `zip64_record_bounds` |

All exited successfully with no stderr or residual temporary files.

## Findings

### No correctness finding in either production-relevant hunk

- **Informational — `SeekProbe.java:222-241`:** The exact-once accounting is structurally sound. Success uses `n`; the exceptional path uses `getBytesWritten() - written`; no outer `DataFormatException` catch remains to double-charge. `charge()` still consumes the shared state, preserves the one-sentinel-byte boundary, and causes rollback through the existing `scan()` transaction on failure.
- **Informational — `SeekProbe.java:80-96` and `:163-185`:** Removing the count-only sentinel does not weaken genuine ZIP64 validation. The fresh sentinel, locator-disk, and record-bound probes above confirmed the negative paths, and the 70003-entry genuine ZIP64 fixture remained accepted by the fixed suite and matched the JDK reference.

### Harness and evidence weaknesses

- **Low — stale recorded check totals, `FIX-NOTES.md:59,78`:** The recorded notes say 311 and 315 checks for earlier runs; the current `verify.py` execution performs 317 check calls. The current result is internally clear (`0` failures), but the recorded totals are not a stable check-count contract.
- **Low — reference is only partially independent, `SeekProbe.java:275-301`:** The JDK `ZipFile` reference independently enumerates central-directory metadata, but it reads payload bytes through production `ServiceLoaderDescriptorArchiveSource.readEntry()` and shares production provider parsing. Therefore, differential equality is not a fully independent end-to-end oracle. The targeted defect assertions do not rely solely on that comparison: they pin diagnostics, budget totals, no partial results, and the known 65535-entry rule.
- **Low — build provenance, `verify.py:18-24`:** The harness compiles selected production sources against prebuilt `target/classes` and `target/safety-isolated-classpath.txt`; it does not perform a clean production rebuild or prove those class files were freshly built from the exact HEAD. I separately verified the production Git checkout and source state.
- **Low — sanity output handling, `verify.py:42-79`:** Residual temp paths are deleted before the harness records a failure, so the test environment is cleaned even when cleanup evidence fails. The residual count is captured and asserted, so this is not vacuous for the current runs.

## Integrity and preservation checks

- Production checkout: exact HEAD `a2f36294abc0d4ecd0e92b4657d5a4f79ea7c30b`; `git status --porcelain --untracked-files=all` empty; `git diff --exit-code` returned 0; zero non-ignored untracked files.
- Original `SeekProbe.java`: SHA-256 `92920a67916c6076acbbea64dfd009ab156f2732b7945c9f350bad12359b49f2`, matching the source manifest.
- Original and fixed-directory `Spill.java`: SHA-256 `87860be1f9f3418cc3a8f593a8bcccfb05ef59808ac0b5213a0a01e02451c638`, matching the source manifest.
- All disposable sibling review-run directories were removed; no review-run artifacts remain beside this directory.

## Engram/context limitations

- Live Engram MCP `recall` was called for the prior PR-7 review and the specific FLPATH-5078/DEFLATE/ZIP64 topics; each targeted query returned no results.
- The live Engram gateway exposed no code-search tool in this session. The zvec-grep MCP search was used read-only with `autoUpdate=false` against the exact production checkout; it returned `freshness: possibly_stale` but surfaced `ServiceLoaderDescriptorArchiveSource.readEntry()` at lines 173-203 and `ScanState.consumeDecompressed()` at lines 286-295. Local source inspection, not the index, was treated as authoritative.
- A search against the configured `java-analysis-adapter` clone failed with `ZG.ENGINE.NOT_FOUND` because no workspace manifest was present. This did not block the review because the exact production checkout was available locally and was inspected directly.
- Java 17 runtime parity was not verified: execution used JDK 21.0.13+9 after compilation with `--release 17`.
- One broad, non-test recursive grep over `~/.engram` hit the 120-second tool deadline. It involved no child JVM and changed no files.

## Proceed decision

The external prototype work is fit to proceed toward production integration review. A separate integration review must still establish the production implementation change, Java 17 runtime behavior, and any product-level policy decisions; none were authorized or performed here.
