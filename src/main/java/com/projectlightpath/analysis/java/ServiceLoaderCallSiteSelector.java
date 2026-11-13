package com.projectlightpath.analysis.java;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/** Selects the ServiceLoader source invocation belonging to one graph edge. */
final class ServiceLoaderCallSiteSelector {
  private ServiceLoaderCallSiteSelector() {}

  static boolean hasCallOnStatement(
      String sourceText,
      int line,
      List<ServiceLoaderCallSite> sites,
      Predicate<ServiceLoaderCallSite> sitePredicate) {
    return hasCallOnStatement(sourceText, line, "", sites, sitePredicate);
  }

  static boolean hasCallOnStatement(
      String sourceText,
      int line,
      String targetMethod,
      List<ServiceLoaderCallSite> sites,
      Predicate<ServiceLoaderCallSite> sitePredicate) {
    List<ServiceLoaderCallSite> candidates = sites.stream()
        .filter(site -> site.line() <= line)
        .filter(site -> ServiceLoaderCallSiteSource.sameStatement(sourceText, site, line))
        .filter(sitePredicate)
        .toList();
    return candidates.size() == 1
        && (targetMethod == null || targetMethod.isBlank()
            || ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
                sourceText, candidates.get(0), targetMethod));
  }

  static Selection select(
      AnalysisContext context,
      CallEdge edge,
      String sourceText,
      List<ServiceLoaderCallSite> sites) {
    Optional<String> methodSource = new SourceMetadataScanner(context)
        .methodSource(edge.caller(), edge.line());
    if (methodSource.isEmpty()) {
      return Selection.empty();
    }
    String maskedMethod = SourceMetadataScanner.maskStringLiterals(
        SourceMetadataScanner.stripComments(methodSource.get()));
    int edgeOffset = edge.line() > 0
        ? ServiceLoaderCallSiteSource.lineStartOffset(sourceText, edge.line()) : -1;
    int methodStart = ServiceLoaderCallSiteSource.methodStart(sourceText, maskedMethod, edgeOffset);
    if (methodStart < 0) {
      return Selection.empty();
    }
    int methodEnd = methodStart + maskedMethod.length();
    List<ServiceLoaderCallSite> inMethod = sites.stream()
        .filter(site -> site.offset() >= methodStart && site.offset() < methodEnd)
        .toList();
    if (inMethod.isEmpty()) {
      return Selection.empty();
    }
    if (edge.line() <= 0) {
      return inMethod.size() == 1
          ? new Selection(inMethod, false, false)
          : Selection.empty();
    }
    List<ServiceLoaderCallSite> statementSites = inMethod.stream()
        .filter(site -> site.line() <= edge.line())
        .filter(site -> ServiceLoaderCallSiteSource.sameStatement(sourceText, site, edge.line()))
        .filter(site -> ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
            sourceText, site, edge.callee().name()))
        .sorted(Comparator.comparingInt(ServiceLoaderCallSite::line)
            .thenComparingInt(ServiceLoaderCallSite::offset))
        .toList();
    if (!statementSites.isEmpty()) {
      return new Selection(statementSites, true, statementSites.size() == 1);
    }
    return inMethod.size() == 1
        && ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
            sourceText, inMethod.get(0), edge.callee().name())
        ? new Selection(inMethod, false, false)
        : Selection.empty();
  }
  record Selection(
      List<ServiceLoaderCallSite> sites, boolean statementScoped, boolean uniquelyBound) {
    Selection {
      sites = List.copyOf(sites);
    }

    static Selection empty() {
      return new Selection(List.of(), false, false);
    }
  }
}
