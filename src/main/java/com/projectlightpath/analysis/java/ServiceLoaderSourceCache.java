package com.projectlightpath.analysis.java;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;

/** Caches source text and parsed ServiceLoader sites for one analysis scanner. */
final class ServiceLoaderSourceCache {
  private static final Map<SourceMetadataScanner, ServiceLoaderSourceCache> CACHES =
      new WeakHashMap<>();

  private final Map<Path, CachedSource> sources = new LinkedHashMap<>();

  private ServiceLoaderSourceCache() {}

  /** Returns one cached source model for a scanner and method owner. */
  static Optional<CachedSource> source(SourceMetadataScanner scanner, MethodSymbol symbol) {
    Objects.requireNonNull(scanner, "source scanner");
    Objects.requireNonNull(symbol, "method symbol");
    ServiceLoaderSourceCache cache = cacheFor(scanner);
    synchronized (cache) {
      return scanner.sourceFile(symbol.owner()).map(file ->
          cache.sources.computeIfAbsent(file.path(), ignored -> create(file)));
    }
  }

  private static ServiceLoaderSourceCache cacheFor(SourceMetadataScanner scanner) {
    synchronized (CACHES) {
      return CACHES.computeIfAbsent(scanner, ignored -> new ServiceLoaderSourceCache());
    }
  }

  private static CachedSource create(SourceMetadataScanner.SourceFile file) {
    String source = file.source();
    return new CachedSource(source, ServiceLoaderCallSiteParser.parse(source));
  }

  record CachedSource(String sourceText, List<ServiceLoaderCallSite> sites) {
    CachedSource {
      sites = List.copyOf(sites);
    }
  }
}
