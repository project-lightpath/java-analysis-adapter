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
abstract class BoundedFrameworkModelMetadataMatching extends BoundedFrameworkModelMetadataState implements FrameworkModel {

  BoundedFrameworkModelMetadataMatching(AnalysisContext.Framework framework) {
    super(framework);
  }


  /** Finds resolved binding rows for one source injection parameter. */
  List<AnalysisContext.ResolvedBindingMetadata> matchingBindings(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.InjectionPoint injection,
      SourceMetadataScannerState.Parameter parameter,
      int parameterIndex,
      List<SourceMetadataScannerState.BeanCandidate> candidates) {
    return context.resolvedBindings().stream()
        .filter(row -> injectionOwnerMatches(scanner, row.injectionOwner(), injection.ownerType()))
        .filter(row -> memberMatches(row.injectionMember(), injection.member()))
        .filter(row -> injectionTypesMatch(scanner, injection.file(), row.injectionType(), parameter.type()))
        .filter(row -> row.parameterIndex() == null || row.parameterIndex() == parameterIndex)
        .filter(row -> resolvedQualifiersCompatible(
            row.qualifiers(), mergeQualifiers(injection.qualifiers(), parameter.qualifiers())))
        .filter(row -> candidates.stream().anyMatch(candidate ->
            row.candidateId().equals(candidate.candidateId())
                || matchingCandidateMetadata(context, scanner, candidate).stream()
                    .anyMatch(metadata -> row.candidateId().equals(metadata.candidateId()))))
        .toList();
  }

  /** Rejects conflicting requested qualifiers while preserving empty-list wildcards. */
  static boolean resolvedQualifiersCompatible(List<String> resolved, List<String> requested) {
    if (resolved.isEmpty() || requested.isEmpty()) {
      return true;
    }
    return requested.stream().allMatch(requestedQualifier -> resolved.stream()
        .anyMatch(candidateQualifier -> bindingQualifierEquals(requestedQualifier, candidateQualifier)));
  }

  /** Matches qualified owners exactly and permits simple names only in metadata. */
  static boolean injectionOwnerMatches(
      SourceMetadataScanner scanner,
      String rowOwner,
      String owner) {
    return metadataTypeMatches(scanner, rowOwner, owner);
  }

  /** Finds proxy rows using target, overload descriptor, and candidate identity. */
  List<AnalysisContext.ProxyTargetMetadata> matchingProxyMetadata(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.ProxyTarget target) {
    Optional<String> descriptor = methodDescriptor(scanner, target);
    return context.proxyTargetMetadata().stream()
        .filter(row -> proxyTargetTypeMatches(scanner, row.targetType(), target))
        .filter(row -> row.methodName().equals(target.method().name()))
        .filter(row -> row.methodDescriptor() == null || row.methodDescriptor().isBlank()
            || descriptor.isPresent() && row.methodDescriptor().equals(descriptor.get()))
        .filter(row -> row.candidateId() == null || row.candidateId().isBlank()
            || row.candidateId().equals(target.candidateId())
            || simpleName(row.candidateId()).equalsIgnoreCase(target.file().simpleTypeName())
                && scanner.files().stream()
                    .filter(file -> file.simpleTypeName().equalsIgnoreCase(target.file().simpleTypeName()))
                    .count() == 1
            || context.bindingCandidates().stream().anyMatch(candidate ->
                candidate.candidateId().equals(row.candidateId())
                    && simpleName(candidate.candidateType())
                        .equalsIgnoreCase(target.file().simpleTypeName())))
        .toList();
  }

  /** Matches qualified proxy types or unique unqualified source types. */
  static boolean proxyTargetTypeMatches(
      SourceMetadataScanner scanner,
      String metadataType,
      SourceMetadataScannerState.ProxyTarget target) {
    if (metadataType.equals(target.file().typeName())) {
      return true;
    }
    if (metadataType.contains(".")
        || !simpleName(metadataType).equalsIgnoreCase(target.file().simpleTypeName())) {
      return false;
    }
    return scanner.files().stream()
        .filter(file -> file.simpleTypeName().equalsIgnoreCase(metadataType))
        .count() == 1;
  }

  static Map<String, String> proxyAttributes(
      String metadataId,
      SourceMetadataScannerState.ProxyTarget target,
      AnalysisContext.ProxyTargetMetadata metadata,
      String targetDescriptor) {
    Map<String, String> attributes = new HashMap<>();
    attributes.put("metadata", metadataId);
    attributes.put("targetType", target.file().typeName());
    attributes.put("method", target.method().name());
    attributes.put("proxyKind", target.proxyKind());
    if (targetDescriptor != null && !targetDescriptor.isBlank()) {
      attributes.put("targetDescriptor", targetDescriptor);
    }
    if (metadata != null) {
      attributes.put("proxyTargetMetadataId", metadata.proxyTargetId());
      attributes.put("proxyTargetSelected", String.valueOf(metadata.selected()));
      attributes.put("proxyTargetActive", String.valueOf(metadata.active()));
      if (metadata.selectionReason() != null) {
        attributes.put("selectionReason", metadata.selectionReason());
      }
    }
    return attributes;
  }
}
