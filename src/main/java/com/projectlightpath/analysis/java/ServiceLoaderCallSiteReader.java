package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import sootup.callgraph.CallGraph;

/** Resolves one exact ServiceLoader source call site for a graph edge. */
final class ServiceLoaderCallSiteReader {
  private ServiceLoaderCallSiteReader() {}

  /** Finds the exact ServiceLoader call site owning a graph edge. */
  static ServiceLoadHints read(
      AnalysisContext context,
      CallEdge edge,
      ServiceLoaderDescriptorScanner.Scan descriptorScan,
      SourceMetadataScanner scanner) {
    Path source = sourcePath(context, edge.sourcePath());
    if (source == null) {
      return ServiceLoadHints.empty();
    }
    String sourceText = SourceMetadataScanner.maskStringLiterals(
        SourceMetadataScanner.stripComments(readSource(source)));
    List<ServiceLoaderCallSite> sites = javaServiceLoaderSites(
        context, edge.caller(), sourceText, scanner);
    ServiceLoaderCallSiteSelector.Selection selection = ServiceLoaderCallSiteSelector.select(
        edge, sourceText, sites, scanner);
    List<ServiceLoaderCallSite> selected = selection.sites();
    if (selected.isEmpty()) {
      return ServiceLoadHints.empty();
    }
    List<ResolvedSite> resolvedSites = selected.stream()
        .map(site -> resolveServiceType(scanner, edge, site))
        .flatMap(Optional::stream)
        .toList();
    if (resolvedSites.isEmpty()) {
      return new ServiceLoadHints(Set.of(), explicitClassLoaderOnSites(selected), false);
    }
    List<ResolvedSite> correlated = resolvedSites.stream()
        .filter(site -> descriptorScan.descriptors().stream()
            .filter(descriptor -> descriptor.serviceType().equals(site.serviceType()))
            .anyMatch(descriptor -> descriptor.providers().contains(edge.callee().owner())))
        .toList();
    boolean targetCorrelated = selection.statementScoped() && correlated.size() == 1;
    List<ResolvedSite> candidates = targetCorrelated ? correlated : resolvedSites;
    boolean callSiteBound = targetCorrelated
        || (selection.uniquelyBound() && candidates.size() == 1);
    boolean explicitClassLoader = explicitClassLoader(candidates);
    if (candidates.size() != 1) {
      return new ServiceLoadHints(
          candidates.stream()
              .map(ResolvedSite::serviceType)
              .collect(Collectors.toSet()),
          explicitClassLoader,
          false);
    }
    ResolvedSite site = candidates.get(0);
    return new ServiceLoadHints(Set.of(site.serviceType()), explicitClassLoader, callSiteBound);
  }

  /** Detects a validated JDK ServiceLoader call associated with one bytecode line. */
  static boolean hasJavaServiceLoaderCall(
      AnalysisContext context,
      MethodSymbol caller,
      CallGraph.Call call,
      SourceMetadataScanner scanner) {
    int line = call.getLineNumber();
    if (line <= 0) {
      return false;
    }
    Path source = sourcePathForOwner(context, caller.owner());
    if (source == null) {
      return false;
    }
    String sourceText = SourceMetadataScanner.maskStringLiterals(
        SourceMetadataScanner.stripComments(readSource(source)));
    Optional<String> invokedType = call.invokableStmt().getInvokeExpr()
        .map(expression -> expression.getMethodSignature().getDeclClassType()
            .getFullyQualifiedName());
    if (invokedType.isEmpty()) {
      return false;
    }
    return ServiceLoaderCallSiteSelector.hasCallOnStatement(
        sourceText,
        line,
        MethodSymbol.fromSootSignature(call.targetMethodSignature().toString()).name(),
        javaServiceLoaderSites(context, caller, sourceText, scanner),
        site -> resolveServiceType(scanner, caller, site).filter(invokedType.get()::equals).isPresent());
  }

  private static List<ServiceLoaderCallSite> javaServiceLoaderSites(
      AnalysisContext context,
      MethodSymbol caller,
      String sourceText,
      SourceMetadataScanner scanner) {
    return ServiceLoaderCallSiteParser.parse(sourceText).stream()
        .filter(site -> isJavaServiceLoader(scanner, caller, site))
        .toList();
  }

  private static Optional<ResolvedSite> resolveServiceType(
      SourceMetadataScanner scanner, CallEdge edge, ServiceLoaderCallSite site) {
    return resolveServiceType(scanner, edge.caller(), site)
        .map(type -> new ResolvedSite(site, type));
  }

  private static Optional<String> resolveServiceType(
      SourceMetadataScanner scanner, MethodSymbol caller, ServiceLoaderCallSite site) {
    return scanner.sourceFile(caller.owner())
        .flatMap(file -> scanner.resolveReferenceType(site.serviceType(), file, site.offset()))
        .or(() -> resolveLexicalNestedType(scanner, caller, site))
        .or(() -> Optional.of(site.fallbackServiceType()));
  }

  private static Optional<String> resolveLexicalNestedType(
      SourceMetadataScanner scanner, MethodSymbol caller, ServiceLoaderCallSite site) {
    String spelling = site.serviceType();
    if (!spelling.contains(".")) {
      return Optional.empty();
    }
    String nestedSpelling = spelling.replace('.', '$');
    String owner = caller.owner();
    while (!owner.isBlank()) {
      String candidate = owner + "$" + nestedSpelling;
      if (scanner.files().stream().anyMatch(file -> file.typeName().equals(candidate))) {
        return Optional.of(candidate);
      }
      int separator = owner.lastIndexOf('$');
      if (separator < 0) {
        break;
      }
      owner = owner.substring(0, separator);
    }
    return Optional.empty();
  }

  private static boolean explicitClassLoaderOnSites(List<ServiceLoaderCallSite> sites) {
    return sites.stream().anyMatch(ServiceLoaderCallSite::explicitClassLoader);
  }

  private static boolean explicitClassLoader(List<ResolvedSite> sites) {
    return sites.stream().anyMatch(site -> site.site().explicitClassLoader());
  }

  private static boolean isJavaServiceLoader(
      SourceMetadataScanner scanner, MethodSymbol symbol, ServiceLoaderCallSite site) {
    if (site.receiver().equals("java.util.ServiceLoader")) {
      return true;
    }
    if (site.receiver().equals("ServiceLoader")
        && scanner.sourceFile(symbol.owner())
            .map(file -> scanner.hasVisibleValueNamed(file, site.receiver(), site.offset()))
            .orElse(false)) {
      return false;
    }
    return scanner.sourceFile(symbol.owner())
        .flatMap(file -> scanner.resolveReferenceType(site.receiver(), file, site.offset()))
        .filter("java.util.ServiceLoader"::equals)
        .isPresent();
  }

  private static Path sourcePath(AnalysisContext context, String displayPath) {
    Path direct = context.repositoryRoot().resolve(displayPath).normalize();
    if (Files.isRegularFile(direct) && direct.startsWith(context.repositoryRoot())) {
      return direct;
    }
    for (AnalysisContext.SourceRoot root : context.includedSourceRoots()) {
      String prefix = root.declaredPath() + "/";
      if (displayPath.startsWith(prefix)) {
        Path candidate = root.path().resolve(displayPath.substring(prefix.length())).normalize();
        if (Files.isRegularFile(candidate) && candidate.startsWith(root.path())) {
          return candidate;
        }
      }
    }
    return null;
  }

  private static Path sourcePathForOwner(AnalysisContext context, String owner) {
    String topLevel = owner.contains("$") ? owner.substring(0, owner.indexOf('$')) : owner;
    Path relative = Path.of(topLevel.replace('.', '/') + ".java");
    for (AnalysisContext.SourceRoot root : context.includedSourceRoots()) {
      Path candidate = root.path().resolve(relative).normalize();
      if (Files.isRegularFile(candidate) && candidate.startsWith(root.path())) {
        return candidate;
      }
    }
    Path direct = context.repositoryRoot().resolve(relative).normalize();
    return Files.isRegularFile(direct) && direct.startsWith(context.repositoryRoot()) ? direct : null;
  }

  private static String readSource(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException error) {
      throw new IllegalStateException("cannot read ServiceLoader source " + path, error);
    }
  }

  record ServiceLoadHints(Set<String> serviceTypes, boolean explicitClassLoader, boolean callSiteBound) {
    ServiceLoadHints {
      serviceTypes = Set.copyOf(serviceTypes);
    }

    static ServiceLoadHints empty() {
      return new ServiceLoadHints(Set.of(), false, false);
    }
  }

  private record ResolvedSite(ServiceLoaderCallSite site, String serviceType) {}

}
