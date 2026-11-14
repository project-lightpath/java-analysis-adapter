package com.projectlightpath.analysis.java;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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
    Optional<SourceMetadataScanner.SourceFile> sourceFile = scanner.sourceFile(caller.owner());
    if (sourceFile.isEmpty()) {
      return false;
    }
    String source = sourceFile.get().source();
    String targetMethod = MethodSymbol.fromSootSignature(call.targetMethodSignature().toString()).name();
    return ServiceLoaderCallSiteParser.parse(source).stream()
        .filter(site -> site.line() <= call.getLineNumber())
        .filter(site -> sameStatement(source, site, call.getLineNumber()))
        .anyMatch(site -> statementContainsMethod(source, site, targetMethod));
  }

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

  private static boolean sameStatement(String source, ServiceLoaderCallSite site, int line) {
    int lineStart = lineStart(source, line);
    if (lineStart < 0 || site.offset() >= lineStart) {
      return site.offset() < lineStart || site.line() == line;
    }
    int statementEnd = source.indexOf(';', site.offset());
    return statementEnd < 0 || lineStart <= statementEnd;
  }

  private static boolean statementContainsMethod(
      String source, ServiceLoaderCallSite site, String targetMethod) {
    if (targetMethod == null || targetMethod.isBlank() || targetMethod.equals("<init>")) {
      return false;
    }
    int loadEnd = source.indexOf(')', site.offset());
    if (loadEnd < 0) {
      return false;
    }
    int statementEnd = source.indexOf(';', loadEnd);
    if (statementEnd < 0) {
      statementEnd = source.length();
    }
    String suffix = source.substring(loadEnd + 1, statementEnd);
    return suffix.matches("(?s).*\\.\\s*" + java.util.regex.Pattern.quote(targetMethod)
        + "\\s*\\(.*");
  }

  private static int lineStart(String source, int line) {
    int current = 1;
    for (int index = 0; index < source.length(); index++) {
      if (current == line) {
        return index;
      }
      if (source.charAt(index) == '\n') {
        current++;
      }
    }
    return current == line ? source.length() : -1;
  }
}
