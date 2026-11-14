package com.projectlightpath.analysis.java;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

/** Caches source text and parsed ServiceLoader sites for one analysis scanner. */
final class ServiceLoaderSourceCache {
  private static final Map<SourceMetadataScanner, ServiceLoaderSourceCache> CACHES =
      new WeakHashMap<>();

  private final Map<Path, CachedSource> sources = new LinkedHashMap<>();

  private ServiceLoaderSourceCache() {}

  private static final Pattern SERVICE_LOADER_TYPE_DECLARATION = Pattern.compile(
      "\\b(?:class|interface|record|enum)\\s+ServiceLoader\\b");

  /** Returns one cached source model for a scanner and method owner. */
  static Optional<CachedSource> source(SourceMetadataScanner scanner, MethodSymbol symbol) {
    Objects.requireNonNull(scanner, "source scanner");
    Objects.requireNonNull(symbol, "method symbol");
    ServiceLoaderSourceCache cache = cacheFor(scanner);
    synchronized (cache) {
      return scanner.sourceFile(symbol.owner()).map(file ->
          cache.sources.computeIfAbsent(file.path(), ignored -> create(scanner, file)));
    }
  }

  private static ServiceLoaderSourceCache cacheFor(SourceMetadataScanner scanner) {
    synchronized (CACHES) {
      return CACHES.computeIfAbsent(scanner, ignored -> new ServiceLoaderSourceCache());
    }
  }

  private static CachedSource create(
      SourceMetadataScanner scanner, SourceMetadataScanner.SourceFile file) {
    String source = file.source();
    String structuralSource = ServiceLoaderSourceMasker.mask(source);
    boolean sourceTypeOwned = !SERVICE_LOADER_TYPE_DECLARATION.matcher(structuralSource).find()
        && scanner.files().stream()
            .noneMatch(candidate -> candidate.packageName().equals(file.packageName())
                && candidate.simpleTypeName().equals("ServiceLoader"));
    return new CachedSource(
        source,
        ServiceLoaderCallSiteParser.parse(source, sourceTypeOwned, sourceTypeOwned));
  }

  record CachedSource(String sourceText, List<ServiceLoaderCallSite> sites) {
    CachedSource {
      sites = List.copyOf(sites);
    }
  }
}
