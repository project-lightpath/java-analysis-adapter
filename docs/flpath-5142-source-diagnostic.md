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

## Gates

- `make adapter-test`: **342** default + **1** large-archive = **343**, 0
  failures (gate >= 338; one new regression test).
- `make java-lint`: PASS, 0 PMD/CPD findings.
- `make evidence-validate`: PASS.

## Residuals

- The diagnostic detail is intentionally generic; the compiler message is not
  copied into evidence.
- A global javac failure (compiler unavailable, global error, or contained
  parser stack exhaustion) still fails closed with an empty site map and no
  per-file diagnostic. Follow-up candidate: a `javac_source_error` diagnostic
  with an empty path for the global case.
- The scan-global downgrade is coarse: an unrelated bad file hides valid
  ServiceLoader DEFINITEs. This is intentional under the existing certainty
  policy; per-declaration error scoping is the alternative.
