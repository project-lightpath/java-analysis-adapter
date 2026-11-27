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
      SourceMetadataScannerState.BeanCandidate candidate,
      Optional<String> requestedShape) {
    Boolean directCompatibility = directTypeCompatibility(
        scanner, injectionFile, requestedType, candidate, requestedShape);
    if (directCompatibility == Boolean.TRUE) {
      return true;
    }
    if (directCompatibility == null) {
      return contractMatches(
          context, scanner, injectionFile, requestedType, candidate, requestedShape);
    }
    return directTypesHaveDifferentRawTypes(
            scanner, injectionFile, requestedType, candidate, requestedShape)
        && contractMatches(
            context, scanner, injectionFile, requestedType, candidate, requestedShape);
  }

  /** Returns whether source and metadata establish a type match strongly enough for definiteness. */
  boolean typeMatchCertain(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.SourceFile injectionFile,
      String requestedType,
      SourceMetadataScannerState.BeanCandidate candidate,
      Optional<String> requestedShape) {
    Boolean directCompatibility = directTypeCompatibility(
        scanner, injectionFile, requestedType, candidate, requestedShape);
    if (directCompatibility == Boolean.TRUE) {
      return true;
    }
    if (candidate.producer() && candidate.candidateType().isBlank()) {
      return false;
    }
    return directCompatibility == Boolean.FALSE
        && directTypesHaveDifferentRawTypes(
            scanner, injectionFile, requestedType, candidate, requestedShape)
        && contractMatches(
            context, scanner, injectionFile, requestedType, candidate, requestedShape);
  }

  /** Matches source and metadata contracts without using an ambiguous package suffix. */
  boolean contractMatches(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.SourceFile injectionFile,
      String requestedType,
      SourceMetadataScannerState.BeanCandidate candidate,
      Optional<String> requestedShape) {
    Optional<String> requested = canonicalType(requestedType, injectionFile, scanner);
    // Issue #61: the index contract disjunct — the candidate's direct
    // supertype shapes against the repair-only erased raw of the requested
    // type. Generics-gated (the shapes are erased; a generic requested type
    // keeps the generic-retaining textual semantics, which alone can
    // compare type arguments) and independent of the requested.isEmpty()
    // early return below: the shape arm fires precisely when that return
    // would (the textual vacuum case).
    boolean indexContract = !hasGenericArguments(requestedType)
        && contractShapesMatch(scanner, injectionFile, requestedType,
            requestedShape, candidate);
    if (requested.isEmpty()) {
      return indexContract;
    }
    boolean sourceContract = candidate.contracts().stream()
        .anyMatch(contract -> contractMatchesType(
            requestedType, requested, contract, candidate.file(), scanner));
    boolean metadataContract = matchingCandidateMetadata(context, scanner, candidate).stream()
        .flatMap(row -> row.contracts().stream())
        .anyMatch(contract -> contractMatchesType(
            requestedType, requested, contract, candidate.file(), scanner));
    return sourceContract || metadataContract || indexContract;
  }

  /**
   * The index contract comparison (issue #61): the candidate's direct
   * supertype shapes contain the requested type's repair-only erased raw
   * resolution. Both sides are canonical, erased, and javac-resolved —
   * this answers exactly the case the textual contract resolution cannot
   * (an implements spelling that is a textual vacuum).
   */
  private static boolean contractShapesMatch(
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.SourceFile injectionFile,
      String requestedType,
      Optional<String> requestedShape,
      SourceMetadataScannerState.BeanCandidate candidate) {
    Optional<String> requestedRaw = canonicalType(
        eraseGenericArguments(requestedType), injectionFile, scanner)
        .or(() -> requestedShape);
    return requestedRaw.isPresent()
        && JavacInjectionTypeShapes.of(scanner)
            .contractShapes(candidate)
            .map(supertypes -> supertypes.contains(requestedRaw.get()))
            .orElse(false);
  }

  /** Compares generic contract arguments whenever either side provides them. */
  private static boolean contractMatchesType(
      String requestedType,
      Optional<String> requested,
      String contract,
      SourceMetadataScannerState.SourceFile contractFile,
      SourceMetadataScanner scanner) {
    if (!hasGenericArguments(requestedType)) {
      Optional<String> candidateRaw = canonicalType(
          eraseGenericArguments(contract), contractFile, scanner);
      return candidateRaw.isPresent() && candidateRaw.get().equals(requested.get());
    }
    Optional<String> candidate = canonicalType(contract, contractFile, scanner);
    if (candidate.isEmpty()) {
      return false;
    }
    return hasGenericArguments(contract) && candidate.get().equals(requested.get());
  }
}
