package com.projectlightpath.analysis.java;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Caches source text and parsed ServiceLoader sites for one analysis scanner. */
final class ServiceLoaderSourceCache {
  private static final Map<SourceMetadataScanner, ServiceLoaderSourceCache> CACHES =
      new WeakHashMap<>();

  /**
   * Typed value declarations named ServiceLoader. The type side allows
   * qualified names, generic arguments, arrays, and varargs but never ends in
   * a dot, whitespace between type and name is mandatory (so the
   * {@code import java.util.ServiceLoader;} line cannot match), and the name
   * must be followed by a declarator context ({@code = ; , ) : ]}) but never
   * {@code >}, which excludes generic type-argument usages such as
   * {@code List<ServiceLoader>}.
   */
  private static final Pattern TYPED_VALUE_DECLARATION = Pattern.compile(
      "(?:^|[^\\w$.])"
          + "([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*"
          + "(?:<[<>,\\w$.\\s]*>)?"
          + "(?:\\[\\s*\\]|\\.\\.\\.)*)"
          + "\\s+(ServiceLoader)(?=\\s*[=;,):\\]])");

  /** Single-inferred-parameter lambdas that bind the name ServiceLoader. */
  private static final Pattern INFERRED_LAMBDA_PARAMETER = Pattern.compile(
      "(?:^|[^\\w$.])(\\(?)(ServiceLoader)(\\)?)(?=\\s*->)");

  /** Keywords that may precede the name without declaring it. */
  private static final Set<String> DECLARATION_TYPE_KEYWORDS = Set.of(
      "assert", "break", "case", "catch", "continue", "do", "else", "extends",
      "for", "if", "implements", "import", "instanceof", "new", "package",
      "permits", "return", "super", "switch", "throw", "throws", "try",
      "while", "yield");

  /** The name following a top-level extends keyword in a type declaration header. */
  private static final Pattern EXTENDS_TARGET = Pattern.compile(
      "extends\\s+([A-Za-z_$][\\w$]*)(?!\\s*\\.)");

  /** Any single-type or on-demand import in a compilation unit. */
  private static final Pattern ANY_IMPORT = Pattern.compile(
      "(?m)^\\s*import\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;");

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
    List<int[]> valueScopes = valueDeclarationScopes(file, structuralSource);
    List<ServiceLoaderCallSite> sites = ServiceLoaderCallSiteParser.parse(
        source, !samePackageTypeShadow, true).stream()
        .filter(site -> !isShadowedAt(file, structuralSource, site.offset()))
        .filter(site -> !inheritedTypeShadowsAt(
            scanner, file, structuralSource, site.offset()))
        .filter(site -> !valueDeclarationShadowsAt(structuralSource, valueScopes, site.offset()))
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

  /**
   * Inherited member types from a superclass in another file (JLS 6.4.1/6.5).
   * BOUNDED, single level: each type declaration enclosing the site contributes
   * its extends-clause simple name, resolved against the selected files by
   * JLS 6.4.1 precedence (single-type import, then same package, then on-demand
   * import); a resolved superclass file that declares an inheritable
   * ServiceLoader type (its top-level type or a direct member) shadows the
   * site. Multi-level chains, {@code implements} clauses, superinterfaces
   * listed after the first extends name, and qualified or unresolvable
   * superclass names (including nested-type imports) are not followed
   * (documented residuals — behavior for them is unchanged).
   */
  private static boolean inheritedTypeShadowsAt(
      SourceMetadataScanner scanner,
      SourceMetadataScanner.SourceFile file,
      String source,
      int offset) {
    if (source.startsWith("java.util.ServiceLoader", offset)) {
      return false;
    }
    for (SourceMetadataScannerState.TypeInfo type : SourceMetadataScanner.typeDeclarations(file)) {
      if (type.start() <= offset
          && (type.end() < 0 || offset <= type.end())
          && superclassDeclaresServiceLoader(scanner, file, source, type)) {
        return true;
      }
    }
    return false;
  }

  private static boolean superclassDeclaresServiceLoader(
      SourceMetadataScanner scanner,
      SourceMetadataScanner.SourceFile file,
      String source,
      SourceMetadataScannerState.TypeInfo type) {
    String superName = extendsSimpleName(source, type);
    if (superName == null) {
      return false;
    }
    List<SourceMetadataScanner.SourceFile> candidates = scanner.files().stream()
        .filter(candidate -> candidate.simpleTypeName().equals(superName))
        .toList();
    String singleTypeImport = singleTypeImport(source, superName);
    if (singleTypeImport != null) {
      // JLS 6.4.1: a single-type import outranks any package member.
      String importPackage = singleTypeImport.substring(
          0, singleTypeImport.length() - superName.length() - 1);
      return candidates.stream()
          .filter(candidate -> candidate.packageName().equals(importPackage))
          .anyMatch(ServiceLoaderSourceCache::declaresInheritableServiceLoaderType);
    }
    List<SourceMetadataScanner.SourceFile> samePackage = candidates.stream()
        .filter(candidate -> candidate.packageName().equals(file.packageName()))
        .toList();
    if (!samePackage.isEmpty()) {
      // A same-package type shadows every on-demand import (JLS 6.4.1).
      return samePackage.stream()
          .anyMatch(ServiceLoaderSourceCache::declaresInheritableServiceLoaderType);
    }
    Set<String> onDemandPackages = onDemandImports(source);
    return candidates.stream()
        .filter(candidate -> onDemandPackages.contains(candidate.packageName()))
        .anyMatch(ServiceLoaderSourceCache::declaresInheritableServiceLoaderType);
  }

  private static String extendsSimpleName(
      String source, SourceMetadataScannerState.TypeInfo type) {
    int body = source.indexOf('{', type.start());
    int limit = body >= 0 ? body : (type.end() >= 0 ? type.end() + 1 : source.length());
    if (limit <= type.start()) {
      return null;
    }
    String header = source.substring(type.start(), limit);
    int extendsIndex = SourceMetadataScanner.topLevelKeywordIndex(header, "extends");
    if (extendsIndex < 0) {
      return null;
    }
    // lookingAt keeps the match anchored after the extends keyword, so a
    // qualified superclass name is skipped rather than matched on its last
    // segment (documented residual).
    Matcher target = EXTENDS_TARGET.matcher(header);
    target.region(extendsIndex, header.length());
    return target.lookingAt() ? target.group(1) : null;
  }

  /** Returns the single-type import name (pkg.Type) for a simple name, if any. */
  private static String singleTypeImport(String source, String simpleName) {
    for (String imported : imports(source)) {
      if (!imported.endsWith(".*")
          && imported.substring(imported.lastIndexOf('.') + 1).equals(simpleName)) {
        return imported;
      }
    }
    return null;
  }

  private static Set<String> onDemandImports(String source) {
    Set<String> result = new LinkedHashSet<>();
    for (String imported : imports(source)) {
      if (imported.endsWith(".*")) {
        result.add(imported.substring(0, imported.length() - 2));
      }
    }
    return result;
  }

  private static List<String> imports(String source) {
    List<String> result = new ArrayList<>();
    Matcher matcher = ANY_IMPORT.matcher(source);
    while (matcher.find()) {
      result.add(matcher.group(1));
    }
    return result;
  }

  /**
   * True when the candidate declares a ServiceLoader type that its top-level
   * type owns: the candidate's own top-level type or one of its direct
   * members. Types nested deeper (a member of a member) and local classes are
   * not inherited by an extending type, so they must not shadow the site.
   */
  private static boolean declaresInheritableServiceLoaderType(
      SourceMetadataScanner.SourceFile candidate) {
    List<SourceMetadataScannerState.TypeInfo> types =
        SourceMetadataScanner.typeDeclarations(candidate);
    return types.stream()
        .filter(type -> type.name().equals("ServiceLoader"))
        .filter(type -> !SourceMetadataScanner.isLocalType(candidate, type))
        .anyMatch(type -> types.stream()
            .filter(other -> other.start() < type.start() && type.end() <= other.end())
            .allMatch(other -> other.name().equals(candidate.simpleTypeName())));
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
    // Same probe as insideMethodBody (a method body opened at method.end()
    // still spans type.start()): delegate instead of duplicating the scan.
    return insideMethodBody(file, source, type.start());
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

  /** Returns the masked-source scope ranges of value declarations named ServiceLoader. */
  private static List<int[]> valueDeclarationScopes(
      SourceMetadataScanner.SourceFile file, String source) {
    List<int[]> scopes = new ArrayList<>();
    Matcher typed = TYPED_VALUE_DECLARATION.matcher(source);
    while (typed.find()) {
      if (!DECLARATION_TYPE_KEYWORDS.contains(leadingIdentifier(typed.group(1)))) {
        addValueScope(file, source, typed.start(2), scopes);
      }
    }
    Matcher inferred = INFERRED_LAMBDA_PARAMETER.matcher(source);
    while (inferred.find()) {
      // Arrow-form switch labels (case ServiceLoader -> doX();) also match the
      // lambda pattern; exclude them exactly like the typed pattern's keywords.
      if (!DECLARATION_TYPE_KEYWORDS.contains(
          precedingIdentifier(source, inferred.start()))) {
        addValueScope(file, source, inferred.start(2), scopes);
      }
    }
    return scopes;
  }

  private static String precedingIdentifier(String source, int matchStart) {
    int index = matchStart - 1;
    while (index >= 0 && Character.isWhitespace(source.charAt(index))) {
      index--;
    }
    int end = index + 1;
    while (index >= 0 && Character.isJavaIdentifierPart(source.charAt(index))) {
      index--;
    }
    return source.substring(index + 1, end);
  }

  private static String leadingIdentifier(String type) {
    return type.split("[^A-Za-z0-9_$]", 2)[0];
  }

  private static void addValueScope(
      SourceMetadataScanner.SourceFile file, String source, int nameStart, List<int[]> scopes) {
    int end = valueScopeEnd(source, nameStart);
    if (end <= nameStart) {
      return;
    }
    int start = nameStart;
    if (enclosingHeaderClose(source, nameStart) < 0 && !insideMethodBody(file, source, nameStart)) {
      // JLS 6.3: a field is in scope for its entire enclosing type body,
      // including source locations that precede the declaration.
      start = Math.max(enclosingBlockStart(source, nameStart), 0);
    }
    scopes.add(new int[] {start, end});
  }

  /**
   * JLS 6.3 scope end. Header-declared forms (method, catch, try-with-resources,
   * for, and lambda parameters) are governed by the block that follows their
   * enclosing closing parenthesis, so that block is scanned forward; block
   * declarations (locals and instanceof pattern variables) end with their
   * enclosing block. The instanceof flow-scoping is a block-end
   * over-approximation; abstention is the safe direction.
   */
  private static int valueScopeEnd(String source, int nameStart) {
    int afterName = skipWhitespace(source, nameStart + "ServiceLoader".length());
    if (source.startsWith("->", afterName)) {
      // Unparenthesized lambda parameter or arrow-form switch pattern
      // variable: the scope is the lambda body or switch arm only.
      return lambdaBodyEnd(source, afterName + 2, nameStart);
    }
    int headerClose = enclosingHeaderClose(source, nameStart);
    if (headerClose >= 0) {
      int cursor = skipWhitespace(source, headerClose + 1);
      if (source.startsWith("->", cursor)) {
        return lambdaBodyEnd(source, cursor + 2, nameStart);
      }
      if (cursor < source.length() && source.charAt(cursor) == '{') {
        return blockEnd(source, cursor);
      }
    }
    return blockEnd(source, enclosingBlockStart(source, nameStart));
  }

  private static int lambdaBodyEnd(String source, int afterArrow, int nameStart) {
    int body = skipWhitespace(source, afterArrow);
    if (body < source.length() && source.charAt(body) == '{') {
      return blockEnd(source, body);
    }
    // Expression-bodied lambda or arrow switch arm: the scope ends with the
    // enclosing statement.
    return statementEnd(source, afterArrow, nameStart);
  }

  private static int statementEnd(String source, int after, int nameStart) {
    int semicolon = source.indexOf(';', after);
    int end = blockEnd(source, enclosingBlockStart(source, nameStart));
    if (end >= 0 && (semicolon < 0 || semicolon > end)) {
      return end;
    }
    return semicolon < 0 ? source.length() : semicolon;
  }

  private static int blockEnd(String source, int blockStart) {
    if (blockStart < 0) {
      return -1;
    }
    int closing = JavaSourceText.matchingDelimiter(source, blockStart, '{', '}');
    return closing < 0 ? source.length() : closing;
  }

  private static int enclosingHeaderClose(String source, int nameStart) {
    int depth = 0;
    for (int index = nameStart - 1; index >= 0; index--) {
      char value = source.charAt(index);
      if (value == ')') {
        depth++;
      } else if (value == '(') {
        if (depth == 0) {
          return JavaSourceText.matchingDelimiter(source, index, '(', ')');
        }
        depth--;
      }
    }
    return -1;
  }

  private static int skipWhitespace(String source, int index) {
    while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
      index++;
    }
    return index;
  }

  private static boolean insideMethodBody(
      SourceMetadataScanner.SourceFile file, String source, int offset) {
    return SourceMetadataScanner.methods(file).stream()
        .filter(method -> method.start() <= offset)
        .filter(method -> method.end() > 0 && method.end() <= source.length())
        .filter(method -> source.charAt(method.end() - 1) == '{')
        .anyMatch(method -> JavaSourceText.matchingDelimiter(
            source, method.end() - 1, '{', '}') >= offset);
  }

  private static boolean valueDeclarationShadowsAt(
      String source, List<int[]> scopes, int offset) {
    if (source.startsWith("java.util.ServiceLoader", offset)) {
      return false;
    }
    for (int[] scope : scopes) {
      if (offset >= scope[0] && offset < scope[1]) {
        return true;
      }
    }
    return false;
  }

  record CachedSource(String sourceText, List<ServiceLoaderCallSite> sites) {
    CachedSource {
      sites = List.copyOf(sites);
    }
  }
}
