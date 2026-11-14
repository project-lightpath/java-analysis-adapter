package com.projectlightpath.analysis.java;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import sootup.callgraph.CallGraph;

/** Resolves ServiceLoader source calls to source-level service identities. */
final class ServiceLoaderCallSiteReader {
  private ServiceLoaderCallSiteReader() {}

  /** Detects a bounded ServiceLoader expression on the statement owning a bytecode call. */
  static boolean hasJavaServiceLoaderCall(
      AnalysisContext context,
      MethodSymbol caller,
      CallGraph.Call call,
      SourceMetadataScanner scanner) {
    if (call.getLineNumber() <= 0) {
      return false;
    }
    Optional<ServiceLoaderSourceCache.CachedSource> cachedSource =
        ServiceLoaderSourceCache.source(scanner, caller);
    if (cachedSource.isEmpty()) {
      return false;
    }
    String targetMethod = MethodSymbol.fromSootSignature(call.targetMethodSignature().toString()).name();
    return selectedSites(cachedSource.get().sourceText(), cachedSource.get().sites(),
        call.getLineNumber(), targetMethod).size() == 1;
  }

  static ServiceLoadHints read(
      AnalysisContext context,
      CallEdge edge,
      ServiceLoaderDescriptorScanner.Scan descriptorScan,
      SourceMetadataScanner scanner) {
    Objects.requireNonNull(context, "analysis context");
    Optional<ServiceLoaderSourceCache.CachedSource> cachedSource =
        ServiceLoaderSourceCache.source(scanner, edge.caller());
    Optional<SourceMetadataScanner.SourceFile> sourceFile = scanner.sourceFile(edge.caller().owner());
    if (cachedSource.isEmpty() || sourceFile.isEmpty()) {
      return ServiceLoadHints.empty();
    }
    String source = cachedSource.get().sourceText();
    List<ServiceLoaderCallSite> sites = cachedSource.get().sites();
    List<ServiceLoaderCallSite> selected = selectedSites(
        source, sites, edge.line(), edge.callee().name());
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

  private static List<ServiceLoaderCallSite> selectedSites(
      String source,
      List<ServiceLoaderCallSite> sites,
      int line,
      String targetMethod) {
    if (line <= 0) {
      return sites.size() == 1 ? sites : List.of();
    }
    return sites.stream()
        .filter(site -> site.line() <= line)
        .filter(site -> ServiceLoaderCallSiteSource.sameStatement(source, site, line))
        .filter(site -> ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
            source, site, targetMethod, line))
        .toList();
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
