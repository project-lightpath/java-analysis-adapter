# FLPATH-5142 — javac source-error diagnostics

Issue: FLPATH-5142 follow-up (R1 from the textual-removal residuals)
Branch: `feature/flpath-5142-javac-source-diagnostic`
Base: `origin/main` @ `bb28633` (PR #31 merged)

## Change

`JavacServiceLoaderSiteResolver` already dropped every ServiceLoader site in a
source file that javac rejected, but the skip was silent. The resolver now keeps
the per-file error list and `ServiceLoaderDescriptorScanner` merges it into the
ServiceLoader scan as:

```
javac_source_error  <relative path>  javac reported an error; ServiceLoader sites in this file are unavailable
```

The diagnostic flows through the existing scan channel: it appears as
`service_loader_scan_diagnostic` evidence, appears in the resolution
`limitation`, and blocks DEFINITE promotion. Valid sibling files keep their
sites; only certainty is downgraded.

## Blast radius

This diagnostic is **scan-global**, like every descriptor diagnostic:
`ServiceLoaderDescriptorScanner.isUnique` blocks DEFINITE when
`scan.diagnostics()` is non-empty. One erroneous source file therefore
downgrades every ServiceLoader edge in the analysis to POSSIBLE. That is the
safe direction and the reason the diagnostic is explicit rather than per-file.

## TDD trail

- `RED_2` — a fixture with an in-file javac error resolves to POSSIBLE but its
  limitation is the generic uniqueness message and no `javac_source_error`
  evidence is emitted. Fails.
- `GREEN_2` — keep the error files from the compile, merge them into the scan
  before resolution, and add the diagnostic path relative to the repository
  root.
- `EVIDENCE_2` — this record.

## Review round 1

Three findings from an independent Engram-grounded review, all addressed:

1. **Global javac failures were still silent.** Compiler-unavailable, global
   error, and contained stack-exhaustion now produce `javac_unavailable`,
   `javac_global_error`, or `javac_source_error` diagnostics with the stable
   path token `selected-sources`.
2. **The original test did not prove causation.** Added a clean-sibling
   counterfactual: the same edge is DEFINITE without an error file and POSSIBLE
   once a sibling file fails javac. This proves the scan-global downgrade.
3. **Outside-root paths could leak absolute paths.** The fallback is now
   `<external>/<file-name>` (or `<external>` when there is no file name).
   Boundary tests cover an in-root file, an outside-root file, a one-character
   name, a sibling path with a shared prefix, and the root itself.

## Gates

- `make adapter-test`: **345** default + **1** large-archive = **346**, 0
  failures (gate >= 338; four new regression tests).
- `make java-lint`: PASS, 0 PMD/CPD findings.
- `make evidence-validate`: PASS.

## Residuals

- The diagnostic detail is intentionally generic; the compiler message is not
  copied into evidence.
- `javac_unavailable` and `javac_global_error` are covered by construction and
  review, not by a fixture (the test JVM always has a compiler and forcing a
  source-less global javac error is not portable).
- The scan-global downgrade is coarse: an unrelated bad file hides valid
  ServiceLoader DEFINITEs. This is intentional under the existing certainty
  policy; per-declaration error scoping is the alternative.
