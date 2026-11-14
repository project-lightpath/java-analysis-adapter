package com.projectlightpath.analysis.java;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Resolves ServiceLoader source calls to source-level service identities. */
final class ServiceLoaderCallSiteReader {
  private ServiceLoaderCallSiteReader() {}

  static ServiceLoadHints read(
      AnalysisContext context,
      CallEdge edge,
      ServiceLoaderDescriptorScanner.Scan descriptorScan,
      SourceMetadataScanner scanner) {
    Objects.requireNonNull(context, "analysis context");
    Optional<SourceMetadataScanner.SourceFile> sourceFile = scanner.sourceFile(edge.caller().owner());
    if (sourceFile.isEmpty()) {
      return ServiceLoadHints.empty();
    }
    List<ServiceLoaderCallSite> sites = ServiceLoaderCallSiteParser.parse(sourceFile.get().source());
    List<ServiceLoaderCallSite> selected = sites.stream()
        .filter(site -> site.line() <= edge.line())
        .toList();
    if (selected.isEmpty()) {
      return ServiceLoadHints.empty();
    }
    List<String> resolved = selected.stream()
        .map(site -> scanner.resolveReferenceType(site.serviceType(), sourceFile.get(), site.offset())
            .orElse(site.fallbackServiceType()))
        .toList();
    boolean bound = selected.size() == 1;
    return new ServiceLoadHints(
        Set.copyOf(resolved),
        selected.stream().anyMatch(ServiceLoaderCallSite::explicitClassLoader),
        bound);
  }

  record ServiceLoadHints(Set<String> serviceTypes, boolean explicitClassLoader, boolean callSiteBound) {
    ServiceLoadHints {
      serviceTypes = Set.copyOf(serviceTypes);
    }

    static ServiceLoadHints empty() {
      return new ServiceLoadHints(Set.of(), false, false);
    }
  }
}
