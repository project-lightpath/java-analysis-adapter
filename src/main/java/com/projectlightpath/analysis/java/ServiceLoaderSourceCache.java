package com.projectlightpath.analysis.java;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
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
  /** One declared name with its typed and inferred-lambda patterns. */
  private record DeclaredName(
      String value, Pattern typed, Pattern inferred, Pattern parameterList, Pattern declarator) {}

  private static DeclaredName declaredName(String name, boolean arrowTerminator) {
    return new DeclaredName(name, typedValueDeclaration(name, arrowTerminator),
        inferredLambdaParameter(name), parameterListElement(name), declarator(name));
  }

  /** A later declarator in a declaration: {@code Type first, name = ...}. */
  private static Pattern declarator(String name) {
    return Pattern.compile(",\\s*(" + Pattern.quote(name) + ")\\s*(?==)");
  }

  private static Pattern typedValueDeclaration(String name, boolean arrowTerminator) {
    String terminator = arrowTerminator ? "[=;,):\\]]|->" : "[=;,):\\]]";
    return Pattern.compile(
        "(?:^|[^\\p{javaJavaIdentifierPart}$.])"
            + "([\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*"
            + "(?:\\.[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)*"
            + "(?:<[<>,\\p{javaJavaIdentifierPart}$.\\s]*>)?"
            + "(?:\\[\\s*\\]|\\.\\.\\.)*)"
            + "\\s+(" + Pattern.quote(name) + ")(?=\\s*(?:" + terminator + "))");
  }

  private static Pattern inferredLambdaParameter(String name) {
    return Pattern.compile(
        "(?:^|[^\\w$.])(\\()?(" + Pattern.quote(name) + ")(\\)?)(?=\\s*->)");
  }

  /** A name that is an element of a parenthesized parameter list. */
  private static Pattern parameterListElement(String name) {
    return Pattern.compile(
        "(?<=[\\s(,])(" + Pattern.quote(name) + ")\\s*(?=,|\\)\\s*->)");
  }

  private static final DeclaredName SERVICE_LOADER = declaredName("ServiceLoader", true);
  private static final DeclaredName JAVA = declaredName("java", true);

  /** Keywords that may precede the name without declaring it. */
  private static final Set<String> DECLARATION_TYPE_KEYWORDS = Set.of(
      "assert", "break", "case", "catch", "continue", "do", "else", "extends",
      "for", "if", "implements", "import", "instanceof", "new", "package",
      "permits", "return", "super", "switch", "throw", "throws", "try",
      "while", "yield");

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
    Optional<Map<Path, List<ServiceLoaderCallSite>>> compilerSites =
        JavacServiceLoaderSiteResolver.sites(scanner);
    if (compilerSites.isPresent()) {
      return new CachedSource(
          source,
          compilerSites.get().getOrDefault(file.path().toAbsolutePath().normalize(), List.of()));
    }
    String structuralSource = ServiceLoaderSourceMasker.mask(source);
    boolean samePackageTypeShadow = scanner.files().stream()
        .filter(candidate -> candidate.packageName().equals(file.packageName()))
        .anyMatch(candidate -> declaresTopLevelType(candidate, "ServiceLoader"));
    boolean staticOnDemandAmbiguous =
        ServiceLoaderInheritance.staticOnDemandAmbiguous(scanner, structuralSource);
    List<int[]> serviceLoaderScopes = declarationScopes(file, structuralSource, SERVICE_LOADER);
    List<int[]> javaScopes = declarationScopes(file, structuralSource, JAVA);
    List<ServiceLoaderCallSite> sites = ServiceLoaderCallSiteParser.parse(
        source, !samePackageTypeShadow, true, staticOnDemandAmbiguous).stream()
        .filter(site -> !isShadowedAt(file, structuralSource, site.offset()))
        .filter(site -> !ServiceLoaderInheritance.shadowsAt(
            scanner, file, structuralSource, site.offset()))
        .filter(site -> !scopedValueShadowsAt(structuralSource, serviceLoaderScopes, site.offset()))
        .filter(site -> !javaQualifierShadowedAt(
            scanner, file, structuralSource, javaScopes, site))
        .toList();
    return new CachedSource(
        source,
        sites);
  }

  /** True when a file declares a top-level type with this name. */
  private static boolean declaresTopLevelType(
      SourceMetadataScanner.SourceFile candidate, String name) {
    List<SourceMetadataScannerState.TypeInfo> types =
        SourceMetadataScanner.typeDeclarations(candidate);
    return types.stream()
        .filter(type -> type.name().equals(name))
        .anyMatch(type -> types.stream()
            .noneMatch(other -> other.start() < type.start() && type.end() <= other.end()));
  }

  /** True when the site file itself declares ServiceLoader in scope. */
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

  /** Scope ranges of declarations of one name (JLS 6.3). */
  private static List<int[]> declarationScopes(
      SourceMetadataScanner.SourceFile file, String source, DeclaredName name) {
    List<int[]> scopes = new ArrayList<>();
    Matcher typed = name.typed().matcher(source);
    while (typed.find()) {
      if (!DECLARATION_TYPE_KEYWORDS.contains(leadingIdentifier(typed.group(1)))) {
        addValueScope(file, source, typed.start(2), name.value().length(), scopes);
      }
    }
    Matcher inferred = name.inferred().matcher(source);
    while (inferred.find()) {
      // Arrow-form switch labels also match the lambda pattern; the keyword
      // filter excludes them exactly like the typed pattern.
      if (!DECLARATION_TYPE_KEYWORDS.contains(precedingIdentifier(source, inferred.start()))) {
        addValueScope(file, source, inferred.start(2), name.value().length(), scopes);
      }
    }
    Matcher parameterList = name.parameterList().matcher(source);
    while (parameterList.find()) {
      addValueScope(file, source, parameterList.start(1), name.value().length(), scopes);
    }
    Matcher declarator = name.declarator().matcher(source);
    while (declarator.find()) {
      addValueScope(file, source, declarator.start(1), name.value().length(), scopes);
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
      SourceMetadataScanner.SourceFile file,
      String source,
      int nameStart,
      int nameLength,
      List<int[]> scopes) {
    int end = valueScopeEnd(source, nameStart, nameLength);
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
  private static int valueScopeEnd(String source, int nameStart, int nameLength) {
    int afterName = skipWhitespace(source, nameStart + nameLength);
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

  /** True when a non-static single-type import binds this simple name. */
  private static boolean singleTypeImportNamed(String source, String simpleName) {
    for (JavaImports.Import imported : JavaImports.parse(source)) {
      if (!imported.isStatic() && !imported.onDemand()
          && imported.simpleName().equals(simpleName)) {
        return true;
      }
    }
    return false;
  }

  private static boolean scopedValueShadowsAt(
      String source, List<int[]> scopes, int offset) {
    if (source.startsWith("java.util.ServiceLoader", offset)) {
      return false;
    }
    return inScope(scopes, offset);
  }

  /** True when a declaration scope covers the offset. */
  private static boolean inScope(List<int[]> scopes, int offset) {
    for (int[] scope : scopes) {
      if (offset >= scope[0] && offset < scope[1]) {
        return true;
      }
    }
    return false;
  }

  /**
   * A fully qualified {@code java.util.ServiceLoader} is not the JDK class when
   * a value or type named {@code java} is in scope (JLS 6.5.2). The site is
   * dropped; bare ServiceLoader references are unaffected.
   */
  private static boolean javaQualifierShadowedAt(
      SourceMetadataScanner scanner,
      SourceMetadataScanner.SourceFile file,
      String source,
      List<int[]> javaScopes,
      ServiceLoaderCallSite site) {
    if (!source.startsWith("java.util.ServiceLoader", site.offset())) {
      return false;
    }
    if (inScope(javaScopes, site.offset())) {
      return true;
    }
    if (singleTypeImportNamed(source, "java")) {
      return true;
    }
    boolean sameCompilationUnit = SourceMetadataScanner.typeDeclarations(file).stream()
        .filter(type -> type.name().equals("java"))
        .anyMatch(type -> declarationInScope(file, source, type, site.offset()));
    return sameCompilationUnit
        || !ServiceLoaderInheritance.resolveSimpleType(scanner, file, source, "java").isEmpty();
  }

  record CachedSource(String sourceText, List<ServiceLoaderCallSite> sites) {
    CachedSource {
      sites = List.copyOf(sites);
    }
  }
}
