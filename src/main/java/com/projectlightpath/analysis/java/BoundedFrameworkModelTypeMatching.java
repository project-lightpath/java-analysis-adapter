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
abstract class BoundedFrameworkModelTypeMatching extends BoundedFrameworkModelMetadataMatching implements FrameworkModel {

  BoundedFrameworkModelTypeMatching(AnalysisContext.Framework framework) {
    super(framework);
  }


  /** Tests qualified injection types, producer generic arguments, and contracts. */
  boolean matches(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.SourceFile injectionFile,
      String requestedType,
      SourceMetadataScannerState.BeanCandidate candidate) {
    Boolean directCompatibility = directTypeCompatibility(
        scanner, injectionFile, requestedType, candidate);
    if (directCompatibility == Boolean.TRUE) {
      return true;
    }
    if (directCompatibility == null) {
      return contractMatches(context, scanner, injectionFile, requestedType, candidate);
    }
    return directTypesHaveDifferentRawTypes(scanner, injectionFile, requestedType, candidate)
        && contractMatches(context, scanner, injectionFile, requestedType, candidate);
  }

  /** Returns whether source and metadata establish a type match strongly enough for definiteness. */
  boolean typeMatchCertain(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.SourceFile injectionFile,
      String requestedType,
      SourceMetadataScannerState.BeanCandidate candidate) {
    Boolean directCompatibility = directTypeCompatibility(
        scanner, injectionFile, requestedType, candidate);
    if (directCompatibility == Boolean.TRUE) {
      return true;
    }
    if (candidate.producer() && candidate.candidateType().isBlank()) {
      return false;
    }
    return directCompatibility == Boolean.FALSE
        && directTypesHaveDifferentRawTypes(scanner, injectionFile, requestedType, candidate)
        && contractMatches(context, scanner, injectionFile, requestedType, candidate);
  }

  /** Matches source and metadata contracts without using an ambiguous package suffix. */
  boolean contractMatches(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.SourceFile injectionFile,
      String requestedType,
      SourceMetadataScannerState.BeanCandidate candidate) {
    Optional<String> requested = canonicalType(
        eraseGenericArguments(requestedType), injectionFile, scanner);
    if (requested.isEmpty()) {
      return false;
    }
    boolean sourceContract = candidate.contracts().stream()
        .map(contract -> canonicalType(contract, candidate.file(), scanner))
        .anyMatch(value -> value.isPresent() && value.get().equals(requested.get()));
    boolean metadataContract = matchingCandidateMetadata(context, scanner, candidate).stream()
        .flatMap(row -> row.contracts().stream())
        .map(contract -> scanner.resolveContractType(contract, candidate.file()))
        .anyMatch(value -> value.isPresent() && value.get().equals(requested.get()));
    return sourceContract || metadataContract;
  }
}
