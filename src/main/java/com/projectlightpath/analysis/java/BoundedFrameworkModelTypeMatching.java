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
          context, scanner, injectionFile, requestedType, candidate, requestedShape,
          false);
    }
    return directTypesHaveDifferentRawTypes(
            scanner, injectionFile, requestedType, candidate, requestedShape)
        && contractMatches(
            context, scanner, injectionFile, requestedType, candidate, requestedShape,
            false);
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
            context, scanner, injectionFile, requestedType, candidate, requestedShape,
            true);
  }

  /** Matches source and metadata contracts without using an ambiguous package suffix. */
  boolean contractMatches(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.SourceFile injectionFile,
      String requestedType,
      SourceMetadataScannerState.BeanCandidate candidate,
      Optional<String> requestedShape,
      boolean forCertainty) {
    Optional<String> requested = canonicalType(requestedType, injectionFile, scanner);
    if (requested.isEmpty()) {
      // Issue #61: the index contract disjunct is evaluated BEFORE this
      // early return can preempt it — the shape arm fires precisely in
      // this textual-vacuum case. CodeRabbit 4724438561: the vacuum path
      // must also run the ASSOCIATED-ROW contract arm the textual path
      // runs (a scanned producer whose binding row declares the
      // requested contract stays in the match set, so a competitor is
      // never hidden and an unopposed DEFINITE is never emitted).
      // CodeRabbit 4724642492: forCertainty separates the two roles —
      // the eligibility role keeps the simple-name fallback (a potential
      // competitor is retained), while the CERTAINTY role requires the
      // row contract to RESOLVE to the shape (an unresolved contract
      // must never prove a match).
      return indexContract(scanner, injectionFile, requestedType,
          requestedShape, candidate)
          || associatedContractMatches(
              context, scanner, requestedType, requestedShape, candidate,
              forCertainty);
    }
    boolean sourceContract = candidate.contracts().stream()
        .anyMatch(contract -> contractMatchesType(
            requestedType, requested, contract, candidate.file(), scanner));
    boolean metadataContract = matchingCandidateMetadata(context, scanner, candidate).stream()
        .flatMap(row -> row.contracts().stream())
        .anyMatch(contract -> contractMatchesType(
            requestedType, requested, contract, candidate.file(), scanner));
    // The textual arms run first; the index disjunct is consulted only
    // when they decline (review round 1, finding 1 — no eager seam lookup
    // on the textual-answer path).
    return sourceContract || metadataContract
        || indexContract(scanner, injectionFile, requestedType,
            requestedShape, candidate);
  }

  /**
   * The associated-row contract arm for the vacuum path (CodeRabbit
   * 4724438561): the candidate's binding rows' contract spellings
   * compared against the requested shape through the SAME row-spelling
   * policy as the metadata-only arm — the textual path counts these
   * rows, so the vacuum repair must too.
   */
  private boolean associatedContractMatches(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      String requestedType,
      Optional<String> requestedShape,
      SourceMetadataScannerState.BeanCandidate candidate,
      boolean forCertainty) {
    if (hasGenericArguments(requestedType) || requestedShape.isEmpty()) {
      return false;
    }
    return matchingCandidateMetadata(context, scanner, candidate).stream()
        .flatMap(row -> row.contracts().stream())
        .anyMatch(contract -> BoundedFrameworkModelMetadataState
            .nonGenericRawEquals(scanner, candidate.file(), contract,
                requestedShape.get(), !forCertainty));
  }

  /**
   * The index contract comparison (issue #61): the candidate's direct
   * supertype shapes contain the requested type's repair-only erased raw
   * resolution. Both sides are canonical, erased, and javac-resolved —
   * this answers exactly the case the textual contract resolution cannot
   * (an implements spelling that is a textual vacuum). Generics-gated
   * (the shapes are erased; a generic requested type keeps the
   * generic-retaining textual semantics, which alone can compare type
   * arguments). CodeRabbit 4724039692: when javac's requested shape is
   * KNOWN and diverges from the textual identity (a nested-type shadow
   * over an imported name — the 2-arg resolver has no nested rung), the
   * disjunct declines: no index match is built on a divergent identity.
   */
  private static boolean indexContract(
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.SourceFile injectionFile,
      String requestedType,
      Optional<String> requestedShape,
      SourceMetadataScannerState.BeanCandidate candidate) {
    if (hasGenericArguments(requestedType)) {
      return false;
    }
    Optional<String> requestedRaw = erasedRaw(
        requestedType, injectionFile, scanner, () -> requestedShape);
    if (requestedRaw.isEmpty()) {
      return false;
    }
    // CodeRabbit 4724039692 / 4724438566: the index arm admits a match
    // only when javac's requested shape is KNOWN and agrees with the
    // resolved identity. A divergent shape (a nested-type shadow over an
    // imported name) or an ABSENT shape (a declined lookup — e.g. a
    // same-arity overload ambiguity) leaves the requested identity
    // unverified, and an unverified identity must not build an index
    // match: abstention is the safe result.
    if (requestedShape.isEmpty()
        || !requestedRaw.get().equals(requestedShape.get())) {
      return false;
    }
    return JavacInjectionTypeShapes.of(scanner)
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
