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
abstract class BoundedFrameworkModelSelectionFacts extends BoundedFrameworkModelRouteFacts implements FrameworkModel {

  BoundedFrameworkModelSelectionFacts(AnalysisContext.Framework framework) {
    super(framework);
  }



  /** Returns a provider identity only when all active matching metadata agrees. */
  String unambiguousInjectionProviderFactId(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.BeanCandidate candidate,
      List<AnalysisContext.ResolvedBindingMetadata> bindingRows) {
    List<AnalysisContext.BindingCandidateMetadata> metadataRows = matchingCandidateMetadata(
        context, scanner, candidate).stream()
        .filter(row -> row.active() != Boolean.FALSE)
        .toList();
    Set<String> providerIds = new LinkedHashSet<>();
    int identitySources = 0;
    for (AnalysisContext.ResolvedBindingMetadata row : bindingRows.stream()
        .filter(row -> row.active() != Boolean.FALSE)
        .filter(row -> row.selected() != Boolean.FALSE)
        .filter(row -> row.candidateId().equals(candidate.candidateId())
            || metadataRows.stream().anyMatch(metadata ->
                row.candidateId().equals(metadata.candidateId())))
        .toList()) {
      identitySources++;
      if (row.providerFactId() == null || row.providerFactId().isBlank()) {
        return null;
      }
      providerIds.add(row.providerFactId());
    }
    for (AnalysisContext.BindingCandidateMetadata row : metadataRows) {
      identitySources++;
      if (row.providerFactId() == null || row.providerFactId().isBlank()) {
        return null;
      }
      providerIds.add(row.providerFactId());
    }
    return identitySources > 0 && providerIds.size() == 1
        ? providerIds.iterator().next()
        : null;
  }


  /** Returns a proxy provider identity only when all active matching rows agree. */
  static String unambiguousProxyProviderFactId(
      List<AnalysisContext.ProxyTargetMetadata> activeRows,
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.ProxyTarget target) {
    if (activeRows.isEmpty()
        || activeRows.stream().anyMatch(row -> descriptorlessProxyRowAmbiguous(scanner, target, row))
        || activeRows.stream().anyMatch(row -> row.providerFactId() == null
            || row.providerFactId().isBlank())) {
      return null;
    }
    Set<String> providerIds = activeRows.stream()
        .map(AnalysisContext.ProxyTargetMetadata::providerFactId)
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    return providerIds.size() == 1 ? providerIds.iterator().next() : null;
  }

  /** Rejects descriptor-less proxy metadata when the source method is overloaded. */
  static boolean descriptorlessProxyRowAmbiguous(
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.ProxyTarget target,
      AnalysisContext.ProxyTargetMetadata row) {
    return (row.methodDescriptor() == null || row.methodDescriptor().isBlank())
        && scanner.proxyMethodOverloaded(target);
  }
}
