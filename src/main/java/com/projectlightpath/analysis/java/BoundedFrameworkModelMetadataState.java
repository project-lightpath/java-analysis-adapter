package com.projectlightpath.analysis.java;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Shared bounded source model for the two first-class framework inputs.
 *
 * <p>It models only evidence that is explicit in source or in the selected
 * context. Container selection, proxy generation, and startup ordering remain
 * possible or unresolved when the corresponding runtime/build metadata is not
 * captured.
 */
abstract class BoundedFrameworkModelMetadataState extends BoundedFrameworkModelCandidateMetadata implements FrameworkModel {

  BoundedFrameworkModelMetadataState(AnalysisContext.Framework framework) {
    super(framework);
  }


  /** Reports source-profile uncertainty when evaluated candidate metadata is absent. */
  boolean profileSelectionUnknown(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.BeanCandidate candidate) {
    List<AnalysisContext.BindingCandidateMetadata> rows = matchingCandidateMetadata(
        context, scanner, candidate);
    if (!candidate.profiles().isEmpty()) {
      return rows.isEmpty() || rows.stream().anyMatch(row -> row.active() == null);
    }
    return rows.stream().anyMatch(row -> !row.profiles().isEmpty() && row.active() == null);
  }

  /** Reports whether source or metadata leaves condition selection to runtime. */
  boolean hasConditions(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.BeanCandidate candidate) {
    return !candidate.conditions().isEmpty()
        || matchingCandidateMetadata(context, scanner, candidate).stream()
            .anyMatch(row -> !row.conditions().isEmpty());
  }

  /** Combines active, inactive, and unknown candidate metadata states. */
  Boolean candidateMetadataActive(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.BeanCandidate candidate) {
    List<AnalysisContext.BindingCandidateMetadata> rows = matchingCandidateMetadata(context, scanner, candidate);
    if (rows.isEmpty()) {
      return null;
    }
    boolean knownActive = false;
    boolean knownInactive = false;
    boolean unknown = false;
    for (AnalysisContext.BindingCandidateMetadata row : rows) {
      if (row.active() == Boolean.FALSE) {
        knownInactive = true;
      } else if (row.active() == Boolean.TRUE) {
        knownActive = true;
      } else {
        unknown = true;
      }
    }
    if (knownActive && !knownInactive && !unknown) {
      return Boolean.TRUE;
    }
    if (knownInactive && !knownActive && !unknown) {
      return Boolean.FALSE;
    }
    return null;
  }

  /** Finds type-compatible metadata candidates that are not represented by scanned source. */
  List<AnalysisContext.BindingCandidateMetadata> metadataOnlyCandidates(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.SourceFile injectionFile,
      String requestedType,
      List<String> requestedQualifiers,
      List<SourceMetadataScannerState.BeanCandidate> scannedCandidates) {
    return context.bindingCandidates().stream()
        .filter(row -> scannedCandidates.stream()
            .noneMatch(candidate -> candidateMetadataMatches(scanner, row, candidate)))
        .filter(row -> metadataCandidateTypeMatches(scanner, injectionFile, requestedType, row))
        .filter(row -> metadataCandidateQualifierMatches(requestedQualifiers, row))
        .toList();
  }

  /** Matches an external metadata candidate by concrete type or declared contract. */
  static boolean metadataCandidateTypeMatches(
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.SourceFile injectionFile,
      String requestedType,
      AnalysisContext.BindingCandidateMetadata row) {
    Optional<String> requested = canonicalType(requestedType, injectionFile, scanner);
    if (requested.isEmpty()) {
      return false;
    }
    if (canonicalType(row.candidateType(), injectionFile, scanner)
        .map(requested.get()::equals)
        .orElse(false)) {
      return true;
    }
    return row.contracts().stream()
        .map(contract -> canonicalType(contract, injectionFile, scanner))
        .anyMatch(value -> value.isPresent() && value.get().equals(requested.get()));
  }

  /** Applies only conservative metadata qualifier filtering for external candidates. */
  boolean metadataCandidateQualifierMatches(
      List<String> requestedQualifiers,
      AnalysisContext.BindingCandidateMetadata row) {
    if (framework != AnalysisContext.Framework.QUARKUS) {
      return true;
    }
    if (requestedQualifiers.isEmpty()) {
      return row.qualifiers().stream().noneMatch(this::explicitQualifier);
    }
    return requestedQualifiers.stream().allMatch(requested -> row.qualifiers().stream()
        .anyMatch(candidate -> bindingQualifierEquals(requested, candidate)));
  }
}
