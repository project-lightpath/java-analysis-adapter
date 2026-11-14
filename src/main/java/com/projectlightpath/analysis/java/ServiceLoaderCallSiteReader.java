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
        .anyMatch(site -> statementContainsMethod(source, site, targetMethod, call.getLineNumber()));
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
    if (lineStart < 0 || site.line() > line) {
      return false;
    }
    DelimiterDepth depth = new DelimiterDepth();
    for (int index = site.offset(); index < lineStart; index++) {
      char value = source.charAt(index);
      if (depth.atTopLevel()
          && (value == ',' || value == ';' || value == ')' || value == ']'
              || value == '}' || value == '{')) {
        return false;
      }
      depth.accept(value);
    }
    return true;
  }

  private static boolean statementContainsMethod(
      String source, ServiceLoaderCallSite site, String targetMethod, int line) {
    if (targetMethod == null || targetMethod.isBlank() || targetMethod.equals("<init>")) {
      return false;
    }
    int loadEnd = source.indexOf(')', site.offset());
    if (loadEnd < 0) {
      return false;
    }
    int statementEnd = statementEnd(source, loadEnd + 1);
    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
        "\\.\\s*" + java.util.regex.Pattern.quote(targetMethod) + "\\s*\\(")
        .matcher(source);
    matcher.region(loadEnd + 1, statementEnd);
    if (!matcher.find()) {
      return false;
    }
    return lineAt(source, matcher.start()) == line;
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

  private static int lineAt(String source, int offset) {
    int line = 1;
    for (int index = 0; index < offset; index++) {
      if (source.charAt(index) == '\n') {
        line++;
      }
    }
    return line;
  }

  private static int statementEnd(String source, int start) {
    DelimiterDepth depth = new DelimiterDepth();
    for (int index = start; index < source.length(); index++) {
      char value = source.charAt(index);
      if (value == ';' && depth.atTopLevel()) {
        return index;
      }
      depth.accept(value);
    }
    return source.length();
  }

  private static final class DelimiterDepth {
    private int parentheses;
    private int brackets;
    private int braces;

    boolean atTopLevel() {
      return parentheses == 0 && brackets == 0 && braces == 0;
    }

    void accept(char value) {
      switch (value) {
        case '(' -> parentheses++;
        case ')' -> parentheses = decrement(parentheses);
        case '[' -> brackets++;
        case ']' -> brackets = decrement(brackets);
        case '{' -> braces++;
        case '}' -> braces = decrement(braces);
        default -> { }
      }
    }

    private static int decrement(int depth) {
      return depth == 0 ? 0 : depth - 1;
    }
  }
}
