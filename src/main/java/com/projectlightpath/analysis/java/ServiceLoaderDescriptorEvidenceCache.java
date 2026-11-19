package com.projectlightpath.analysis.java;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Per-run cache of edge-independent ServiceLoader evidence.
 *
 * <p>Descriptor and diagnostic evidence depend only on the descriptor (and, for
 * descriptors, on the unique/ambiguous selection), so rebuilding them per edge
 * duplicates provider text in every produced fact. The cache is thread-confined
 * to one {@code ProviderFactNormalizer.normalize} call and is never stored on
 * the immutable {@code Scan}.
 */
final class ServiceLoaderDescriptorEvidenceCache {
  private final Map<ServiceLoaderDescriptorScanner.Descriptor,
      Map<String, SemanticCallFacts.Evidence>> descriptors = new IdentityHashMap<>();

  private final Map<ServiceLoaderDescriptorScanner.Diagnostic,
      SemanticCallFacts.Evidence> diagnostics = new HashMap<>();

  SemanticCallFacts.Evidence descriptor(
      ServiceLoaderDescriptorScanner.Descriptor descriptor, String selection) {
    return descriptors
        .computeIfAbsent(descriptor, ignored -> new HashMap<>())
        .computeIfAbsent(selection, key ->
            ServiceLoaderDescriptorScanner.descriptorEvidence(descriptor, key));
  }

  SemanticCallFacts.Evidence diagnostic(ServiceLoaderDescriptorScanner.Diagnostic diagnostic) {
    return diagnostics.computeIfAbsent(diagnostic,
        ServiceLoaderDescriptorScanner::diagnosticEvidence);
  }
}
