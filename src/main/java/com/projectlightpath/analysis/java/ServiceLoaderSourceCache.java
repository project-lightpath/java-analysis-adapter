package com.projectlightpath.analysis.java;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
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
    boolean samePackageTypeShadow = scanner.files().stream()
        .anyMatch(candidate -> candidate.packageName().equals(file.packageName())
            && candidate.simpleTypeName().equals("ServiceLoader"));
    List<ServiceLoaderCallSite> sites = ServiceLoaderCallSiteParser.parse(
        source, !samePackageTypeShadow, true).stream()
        .filter(site -> !isShadowedAt(file, structuralSource, site.offset()))
        .toList();
    return new CachedSource(
        source,
        sites);
  }

  private static boolean isShadowedAt(
      SourceMetadataScanner.SourceFile file, String source, int offset) {
    if (source.startsWith("java.util.ServiceLoader", offset)) {
      return false;
    }
    return SourceMetadataScanner.typeDeclarations(file).stream()
        .filter(type -> type.name().equals("ServiceLoader"))
        .anyMatch(type -> declarationInScope(file, source, type, offset));
  }

  private static boolean declarationInScope(
      SourceMetadataScanner.SourceFile file,
      String source,
      SourceMetadataScannerState.TypeInfo type,
      int offset) {
    if (!isLocalDeclaration(file, source, type)) {
      // Member and nested types are in scope throughout their enclosing type,
      // including source locations that precede the declaration.
      return true;
    }
    if (type.start() > offset) {
      return false;
    }
    int enclosingBlock = enclosingBlockStart(source, type.start());
    if (enclosingBlock < 0) {
      return true;
    }
    int closing = JavaSourceText.matchingDelimiter(source, enclosingBlock, '{', '}');
    return closing < 0 || offset < closing;
  }

  private static boolean isLocalDeclaration(
      SourceMetadataScanner.SourceFile file,
      String source,
      SourceMetadataScannerState.TypeInfo type) {
    return SourceMetadataScanner.methods(file).stream()
        .filter(method -> method.start() <= type.start())
        .filter(method -> method.end() > 0 && method.end() <= source.length())
        .filter(method -> source.charAt(method.end() - 1) == '{')
        .anyMatch(method -> JavaSourceText.matchingDelimiter(
            source, method.end() - 1, '{', '}') >= type.start());
  }

  private static int enclosingBlockStart(String source, int offset) {
    Deque<Integer> openings = new ArrayDeque<>();
    for (int index = 0; index < offset; index++) {
      char value = source.charAt(index);
      if (value == '{') {
        openings.push(index);
      } else if (value == '}' && !openings.isEmpty()) {
        openings.pop();
      }
    }
    return openings.isEmpty() ? -1 : openings.peek();
  }

  record CachedSource(String sourceText, List<ServiceLoaderCallSite> sites) {
    CachedSource {
      sites = List.copyOf(sites);
    }
  }
}
