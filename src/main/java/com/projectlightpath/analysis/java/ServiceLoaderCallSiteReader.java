package com.projectlightpath.analysis.java;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import sootup.callgraph.CallGraph;

/** Resolves ServiceLoader source calls to source-level service identities. */
final class ServiceLoaderCallSiteReader {
  private ServiceLoaderCallSiteReader() {}

  /** Three-state classification of the load sites on a call's statement. */
  enum LoadStatus { ABSENT, BOUND, UNBOUND }

  /** Detects a bounded ServiceLoader expression on the statement owning a bytecode call. */
  static boolean hasJavaServiceLoaderCall(
      AnalysisContext context,
      MethodSymbol caller,
      CallGraph.Call call,
      SourceMetadataScanner scanner) {
    return loadStatus(context, caller, call, scanner) == LoadStatus.BOUND;
  }

  /**
   * Classifies the statement that owns a bytecode call: no load site (ABSENT),
   * exactly one bound site (BOUND), or a site present but not bound (UNBOUND).
   * A lineless edge carries no statement evidence, so it stays ABSENT.
   */
  static LoadStatus loadStatus(
      AnalysisContext context,
      MethodSymbol caller,
      CallGraph.Call call,
      SourceMetadataScanner scanner) {
    if (call.getLineNumber() <= 0) {
      return LoadStatus.ABSENT;
    }
    Optional<ServiceLoaderSourceCache.CachedSource> cachedSource =
        ServiceLoaderSourceCache.source(scanner, caller);
    if (cachedSource.isEmpty()) {
      return LoadStatus.ABSENT;
    }
    String source = cachedSource.get().sourceText();
    List<ServiceLoaderCallSite> sites = cachedSource.get().sites();
    boolean anyInStatement = sites.stream()
        .anyMatch(site -> site.line() <= call.getLineNumber()
            && ServiceLoaderCallSiteSource.sameStatement(source, site, call.getLineNumber()));
    if (!anyInStatement) {
      return LoadStatus.ABSENT;
    }
    String targetMethod = MethodSymbol.fromSootSignature(call.targetMethodSignature().toString()).name();
    String targetReceiver = call.invokableStmt().getInvokeExpr()
        .filter(sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr.class::isInstance)
        .map(sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr.class::cast)
        .map(expression -> expression.getBase().getName())
        .orElse(null);
    return selectedSites(source, sites, call.getLineNumber(), targetMethod, targetReceiver).size() == 1
        ? LoadStatus.BOUND : LoadStatus.UNBOUND;
  }

  static ServiceLoadHints read(
      AnalysisContext context,
      CallEdge edge,
      ServiceLoaderDescriptorScanner.Scan descriptorScan,
      SourceMetadataScanner scanner) {
    Objects.requireNonNull(context, "analysis context");
    Optional<ServiceLoaderSourceCache.CachedSource> cachedSource =
        ServiceLoaderSourceCache.source(scanner, edge.caller());
    if (cachedSource.isEmpty()) {
      return ServiceLoadHints.empty();
    }
    String source = cachedSource.get().sourceText();
    List<ServiceLoaderCallSite> sites = cachedSource.get().sites();
    List<ServiceLoaderCallSite> selected = selectedSites(
        source, sites, edge.line(), edge.callee().name(), edge.invocationReceiver());
    if (selected.isEmpty()) {
      return ServiceLoadHints.empty();
    }
    List<String> resolved = selected.stream()
        .map(ServiceLoaderCallSite::serviceType)
        .toList();
    // A lineless edge (line <= 0) keeps the sole-site service-type hints for
    // POSSIBLE resolution but must never count as a bound call site: the
    // file-wide fallback cannot verify the caller or receiver.
    boolean bound = edge.line() > 0 && selected.size() == 1;
    return new ServiceLoadHints(
        Set.copyOf(resolved),
        selected.stream().anyMatch(ServiceLoaderCallSite::explicitClassLoader),
        bound);
  }

  private static List<ServiceLoaderCallSite> selectedSites(
      String source,
      List<ServiceLoaderCallSite> sites,
      int line,
      String targetMethod,
      String targetReceiver) {
    if (line <= 0) {
      return sites.size() == 1 ? sites : List.of();
    }
    return sites.stream()
        .filter(site -> site.line() <= line)
        .filter(site -> ServiceLoaderCallSiteSource.sameStatement(source, site, line))
        .filter(site -> ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
            source, site, targetMethod, line, targetReceiver))
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
