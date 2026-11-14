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
abstract class BoundedFrameworkModelTypeSupport extends BoundedFrameworkModelTypeParsing implements FrameworkModel {

  BoundedFrameworkModelTypeSupport(AnalysisContext.Framework framework) {
    super(framework);
  }


  /** Matches full metadata names, falling back to simple names only when unqualified. */
  static boolean metadataTypeMatches(
      SourceMetadataScanner scanner,
      String metadataType,
      String sourceType) {
    if (metadataType == null || sourceType == null) {
      return false;
    }
    String normalizedMetadata = eraseGenericArguments(metadataType).trim();
    String normalizedSource = eraseGenericArguments(sourceType).trim();
    return normalizedMetadata.equals(normalizedSource)
        || (!normalizedMetadata.contains(".")
            && normalizedMetadata.equals(simpleName(normalizedSource))
            && scanner.declaredTypeNames().stream()
                .filter(type -> simpleName(type).equalsIgnoreCase(normalizedMetadata))
                .count() <= 1);
  }

  /** Matches constructor aliases and optional member metadata. */
  static boolean memberMatches(String rowMember, String member) {
    return rowMember == null || rowMember.isBlank() || rowMember.equals(member)
        || (rowMember.equals("constructor") && member.equals("<init>"));
  }

  /** Compares erased raw types before checking known generic arguments. */
  static Boolean directTypeCompatibility(
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.SourceFile injectionFile,
      String requestedType,
      SourceMetadataScannerState.BeanCandidate candidate) {
    if (candidate.producer() && candidate.candidateType().isBlank()) {
      return null;
    }
    String candidateType = candidate.producer()
        ? candidate.producerSignature()
        : candidate.candidateType();
    if (candidateType == null || candidateType.isBlank()) {
      return null;
    }
    Optional<String> requestedRaw = canonicalType(
        eraseGenericArguments(requestedType), injectionFile, scanner);
    Optional<String> candidateRaw = canonicalType(
        eraseGenericArguments(candidateType), candidate.file(), scanner);
    if (requestedRaw.isEmpty() || candidateRaw.isEmpty()) {
      return null;
    }
    if (!requestedRaw.get().equals(candidateRaw.get())) {
      return Boolean.FALSE;
    }
    if (!hasGenericArguments(requestedType)) {
      return Boolean.TRUE;
    }
    if (!candidate.producer() || !hasGenericArguments(candidate.producerSignature())) {
      return null;
    }
    Optional<String> requestedCanonical = canonicalType(requestedType, injectionFile, scanner);
    Optional<String> candidateCanonical = canonicalType(
        candidate.producerSignature(), candidate.file(), scanner);
    if (requestedCanonical.isEmpty() || candidateCanonical.isEmpty()) {
      return null;
    }
    return requestedCanonical.get().equals(candidateCanonical.get());
  }

  /** Checks whether candidate and requested types have different resolved raw types. */
  static boolean directTypesHaveDifferentRawTypes(
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.SourceFile injectionFile,
      String requestedType,
      SourceMetadataScannerState.BeanCandidate candidate) {
    if (candidate.producer() && candidate.candidateType().isBlank()) {
      return false;
    }
    String candidateType = candidate.producer()
        ? candidate.producerSignature()
        : candidate.candidateType();
    if (candidateType == null || candidateType.isBlank()) {
      return false;
    }
    Optional<String> requestedRaw = canonicalType(
        eraseGenericArguments(requestedType), injectionFile, scanner);
    Optional<String> candidateRaw = canonicalType(
        eraseGenericArguments(candidateType), candidate.file(), scanner);
    return requestedRaw.isPresent() && candidateRaw.isPresent()
        && !requestedRaw.get().equals(candidateRaw.get());
  }

  /** Matches resolved-binding injection types while tolerating unqualified metadata values. */
  static boolean injectionTypesMatch(
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.SourceFile sourceFile,
      String metadataType,
      String sourceType) {
    Optional<String> metadata = canonicalType(
        eraseGenericArguments(metadataType), sourceFile, scanner);
    Optional<String> source = canonicalType(
        eraseGenericArguments(sourceType), sourceFile, scanner);
    return metadata.isPresent() && source.isPresent()
        ? metadata.get().equals(source.get())
        : metadataTypeMatches(scanner, metadataType, sourceType);
  }
}
