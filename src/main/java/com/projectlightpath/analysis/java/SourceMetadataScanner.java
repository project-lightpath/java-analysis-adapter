package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Small, source-backed metadata reader for the bounded framework contract.
 *
 * <p>This is intentionally not a Java compiler or a general-purpose parser.
 * It only recognizes the annotation and descriptor shapes needed by the
 * checked-in Quarkus and Spring contracts. Anything outside those shapes is
 * left for the unresolved path rather than guessed.
 */
final class SourceMetadataScanner {
  private static final Pattern PACKAGE = Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;");
  private static final Pattern TYPE = Pattern.compile("\\b(?:class|interface|record|enum)\\s+(\\w+)");
  private static final Pattern TYPE_DECLARATION = Pattern.compile(
      "\\b(class|interface|record|enum)\\s+(\\w+)");
  private static final Pattern NEW_EXPRESSION = Pattern.compile("\\bnew\\b");
  private static final Pattern METHOD = Pattern.compile(
      "(?m)(?:^|[;{}\\n])\\s*(?:(?:@[A-Za-z_][\\w.]*(?:\\([^)]*\\))?\\s*)|(?:(?:public|protected|private|static|final|synchronized|abstract|native|default|strictfp)\\s+))*(?:<[^>{}]+>\\s+)?[\\w.?$<>\\[\\], ]+\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws [^{;]+)?(?:\\{|;)");
  private static final Pattern QUOTED = Pattern.compile("\\\"([^\\\"]*)\\\"");
  private static final Pattern CLASS_FOR_NAME = Pattern.compile(
      "(?<![\\w$.])\\b([A-Za-z_$][\\w$]*|java\\.lang\\.Class)"
          + "\\s*\\.\\s*forName\\s*\\(");
  private static final Pattern THIS_CLASS_FOR_NAME = Pattern.compile(
      "(?<![\\w$.])\\bthis\\s*\\.\\s*([A-Za-z_$][\\w$]*)"
          + "\\s*\\.\\s*forName\\s*\\(");
  private static final Pattern QUALIFIED_THIS_CLASS_FOR_NAME = Pattern.compile(
      "(?<![\\w$.])([A-Za-z_$][\\w$.]*)\\s*\\.\\s*this\\s*\\."
          + "\\s*([A-Za-z_$][\\w$]*)\\s*\\.\\s*forName\\s*\\(");
  private static final Pattern THIS_FIELD_RECEIVER = Pattern.compile(
      "^this\\s*\\.\\s*([A-Za-z_$][\\w$]*)$");
  private static final Pattern QUALIFIED_THIS_FIELD_RECEIVER = Pattern.compile(
      "^([A-Za-z_$][\\w$.]*)\\s*\\.\\s*this\\s*\\.\\s*([A-Za-z_$][\\w$]*)$");
  private static final Pattern CHAINED_THIS_FIELD_RECEIVER = Pattern.compile(
      "(?<![\\w$.])\\bthis\\s*\\.\\s*([A-Za-z_$][\\w$]*)");
  private static final Pattern CHAINED_QUALIFIED_THIS_FIELD_RECEIVER = Pattern.compile(
      "(?<![\\w$.])([A-Za-z_$][\\w$.]*)\\s*\\.\\s*this\\s*\\.\\s*"
          + "([A-Za-z_$][\\w$]*)");
  private static final Pattern STATIC_FOR_NAME_IMPORT = Pattern.compile(
      "(?m)^\\s*import\\s+static\\s+java\\.lang\\.Class\\.(?:forName|\\*)\\s*;");
  private static final Pattern STATIC_FOR_NAME = Pattern.compile("\\bforName\\s*\\(");
  private static final Pattern RECEIVER_LOAD_CLASS = Pattern.compile(
      "\\b([A-Za-z_$][\\w$]*)\\s*\\.\\s*(loadClass)\\s*\\(");
  private static final Pattern RECEIVER_METHOD_LOOKUP = Pattern.compile(
      "\\b([A-Za-z_$][\\w$]*)\\s*\\.\\s*(getDeclaredMethod|getMethod)\\s*\\(");
  private static final Pattern RECEIVER_CONSTRUCTOR_LOOKUP = Pattern.compile(
      "\\b([A-Za-z_$][\\w$]*)\\s*\\.\\s*(getDeclaredConstructor|getConstructor)\\s*\\(");
  private static final Pattern CONSTRUCTOR_NEW_INSTANCE = Pattern.compile(
      "\\b(Constructor|java\\.lang\\.reflect\\.Constructor)\\s*\\.\\s*newInstance\\s*\\(");
  private static final Pattern CLASS_NEW_INSTANCE = Pattern.compile(
      "\\b(Class|java\\.lang\\.Class)\\s*\\.\\s*newInstance\\s*\\(");
  private static final Pattern METHOD_INVOKE = Pattern.compile(
      "\\b([A-Za-z_$][\\w$]*)\\s*\\.\\s*invoke\\s*\\(");
  private static final Pattern METHOD_TYPE_INVOKE = Pattern.compile(
      "\\b(Method|java\\.lang\\.reflect\\.Method)\\s*\\.\\s*invoke\\s*\\(");
  private static final Pattern CHAINED_CLASS_FOR_NAME_RECEIVER = Pattern.compile(
      "(?<![\\w$.])([A-Za-z_$][\\w$]*|java\\.lang\\.Class)"
          + "\\s*\\.\\s*forName\\s*\\(");
  private static final Pattern CHAINED_GET_CLASS_RECEIVER = Pattern.compile(
      "\\bgetClass\\s*\\(\\s*\\)");
  private static final Pattern CHAINED_CLASS_LITERAL_RECEIVER = Pattern.compile(
      "\\b[A-Za-z_$][\\w$.]*\\s*\\.\\s*class");
  private static final Pattern CHAINED_SIMPLE_RECEIVER = Pattern.compile(
      "(?<![\\w$.])([A-Za-z_$][\\w$]*)\\s*\\.\\s*"
          + "(?:getDeclaredMethod|getMethod|getDeclaredConstructor|getConstructor)\\s*\\(");
  private static final Pattern CLASS_FOR_NAME_PREFIX = Pattern.compile(
      "^([A-Za-z_$][\\w$]*|java\\.lang\\.Class)\\s*\\.\\s*forName\\s*\\(");
  private static final Pattern THIS_RECEIVER_CALL = Pattern.compile(
      "\\bthis\\s*\\.\\s*([A-Za-z_$][\\w$]*)\\s*\\.\\s*"
          + "(loadClass|getDeclaredMethod|getMethod|getDeclaredConstructor|getConstructor|newInstance|invoke)\\s*\\(");
  private static final Pattern QUALIFIED_THIS_RECEIVER_CALL = Pattern.compile(
      "(?<![\\w$.])([A-Za-z_$][\\w$.]*)\\s*\\.\\s*this\\s*\\."
          + "\\s*([A-Za-z_$][\\w$]*)\\s*\\.\\s*"
          + "(loadClass|getDeclaredMethod|getMethod|getDeclaredConstructor|getConstructor|newInstance|invoke)"
          + "\\s*\\(");
  private static final Pattern PROXY_INTERFACES = Pattern.compile(
      "new\\s+Class\\s*<[^>]*>\\s*\\[\\s*\\]\\s*\\{([^}]*)\\}");
  private static final Pattern CLASS_LITERAL = Pattern.compile("([A-Za-z_][\\w.]*)\\.class");
  private static final Pattern PROXY_CALL = Pattern.compile(
      "(?<![\\w$.])\\b([A-Za-z_$][\\w$]*|java\\.lang\\.reflect\\.Proxy)"
          + "\\s*\\.\\s*newProxyInstance\\s*\\(");
  private static final Pattern THIS_PROXY_CALL = Pattern.compile(
      "(?<![\\w$.])\\bthis\\s*\\.\\s*([A-Za-z_$][\\w$]*)"
          + "\\s*\\.\\s*newProxyInstance\\s*\\(");
  private static final Pattern QUALIFIED_THIS_PROXY_CALL = Pattern.compile(
      "(?<![\\w$.])([A-Za-z_$][\\w$.]*)\\s*\\.\\s*this\\s*\\."
          + "\\s*([A-Za-z_$][\\w$]*)\\s*\\.\\s*newProxyInstance\\s*\\(");
  private static final Pattern UNQUALIFIED_PROXY_CALL = Pattern.compile(
      "\\bnewProxyInstance\\s*\\(");
  private static final Pattern STATIC_PROXY_IMPORT = Pattern.compile(
      "(?m)^\\s*import\\s+static\\s+java\\.lang\\.reflect\\.Proxy\\.(?:newProxyInstance|\\*)\\s*;");
  private static final Pattern INJECT_FIELD = Pattern.compile(
      "(?ms)((?:(?:@[A-Za-z_][\\w.]*(?:\\s*\\([^;{}]*\\))?\\s*)+)"
          + "(?:(?:public|protected|private|static|final|transient|volatile)\\s+)*"
          + ")([\\w.$<>?,\\[\\] ]+)\\s+(\\w+)\\s*(?:=[^;]*)?;");
  private static final Set<String> NON_QUALIFIER_ANNOTATIONS = Set.of(
      "Inject", "Autowired", "Resource", "Value", "Bean", "Produces", "Observes",
      "Service", "Component", "Repository", "Controller", "RestController", "Configuration",
      "ApplicationScoped", "RequestScoped", "SessionScoped", "ConversationScoped", "Dependent",
      "Singleton", "Alternative", "Priority", "Primary", "Profile", "Conditional",
      "ConditionalOnBean", "ConditionalOnMissingBean", "ConditionalOnClass",
      "ConditionalOnMissingClass", "ConditionalOnProperty", "ConditionalOnExpression",
      "Transactional", "Async", "Cacheable", "CachePut", "CacheEvict", "PreAuthorize",
      "PostAuthorize", "Secured", "RolesAllowed", "Retryable", "CircuitBreaker", "Validated",
      "Lazy",
      "Path", "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "RequestMapping",
      "GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping", "RequestMethod",
      "Consumes", "Override", "SuppressWarnings");
  private static final Set<String> CDI_SCOPES = Set.of(
      "ApplicationScoped", "RequestScoped", "SessionScoped", "ConversationScoped", "Dependent",
      "Singleton");
  private static final Set<String> CDI_NORMAL_SCOPES = Set.of(
      "ApplicationScoped", "RequestScoped", "SessionScoped", "ConversationScoped");
  private static final Set<String> JAVA_LANG_TYPES = Set.of(
      "Appendable", "AutoCloseable", "Boolean", "Byte", "Character", "CharSequence",
      "Class", "Cloneable", "Comparable", "Double", "Enum", "Exception", "Float",
      "ClassLoader", "Integer", "Iterable", "Long", "Math", "Number", "Object", "Record", "Runnable",
      "RuntimeException", "Short", "StrictMath", "String", "Thread", "Throwable", "Void");
  private static final Map<String, String> KNOWN_CLASSLOADER_SUPERTYPES = Map.of(
      "java.net.URLClassLoader", "java.security.SecureClassLoader",
      "java.security.SecureClassLoader", "java.lang.ClassLoader",
      "jdk.internal.loader.BuiltinClassLoader", "java.security.SecureClassLoader",
      "jdk.internal.loader.ClassLoaders$AppClassLoader", "jdk.internal.loader.BuiltinClassLoader",
      "jdk.internal.loader.ClassLoaders$PlatformClassLoader", "jdk.internal.loader.BuiltinClassLoader");
  private static final Set<String> KNOWN_JDK_TYPE_NAMES = Set.of(
      "java.lang.Class",
      "java.lang.ClassLoader",
      "java.lang.reflect.Method",
      "java.lang.reflect.Constructor",
      "java.lang.reflect.InvocationHandler",
      "java.lang.reflect.Proxy",
      "java.net.URLClassLoader",
      "java.security.SecureClassLoader");
  private static final Set<String> JAVA_PRIMITIVE_TYPES = Set.of(
      "boolean", "byte", "char", "short", "int", "long", "float", "double", "void");
  private static final Set<String> CDI_PRODUCER_ANNOTATIONS = Set.of(
      "jakarta.enterprise.inject.Produces", "javax.enterprise.inject.Produces");
  private static final Map<String, List<String>> SPRING_ROUTE_ANNOTATION_TYPES = Map.of(
      "RequestMapping", List.of("org.springframework.web.bind.annotation.RequestMapping"),
      "GetMapping", List.of("org.springframework.web.bind.annotation.GetMapping"),
      "PostMapping", List.of("org.springframework.web.bind.annotation.PostMapping"),
      "PutMapping", List.of("org.springframework.web.bind.annotation.PutMapping"),
      "PatchMapping", List.of("org.springframework.web.bind.annotation.PatchMapping"),
      "DeleteMapping", List.of("org.springframework.web.bind.annotation.DeleteMapping"),
      "RestController", List.of("org.springframework.web.bind.annotation.RestController"));
  private static final Map<String, List<String>> SPRING_PROXY_ANNOTATION_TYPES = Map.ofEntries(
      Map.entry("Transactional", List.of(
          "org.springframework.transaction.annotation.Transactional",
          "jakarta.transaction.Transactional",
          "javax.transaction.Transactional")),
      Map.entry("Async", List.of("org.springframework.scheduling.annotation.Async")),
      Map.entry("Cacheable", List.of("org.springframework.cache.annotation.Cacheable")),
      Map.entry("CachePut", List.of("org.springframework.cache.annotation.CachePut")),
      Map.entry("CacheEvict", List.of("org.springframework.cache.annotation.CacheEvict")),
      Map.entry("PreAuthorize", List.of("org.springframework.security.access.prepost.PreAuthorize")),
      Map.entry("PostAuthorize", List.of("org.springframework.security.access.prepost.PostAuthorize")),
      Map.entry("Secured", List.of("org.springframework.security.access.annotation.Secured")),
      Map.entry("RolesAllowed", List.of(
          "jakarta.annotation.security.RolesAllowed",
          "javax.annotation.security.RolesAllowed")),
      Map.entry("Retryable", List.of("org.springframework.retry.annotation.Retryable")),
      Map.entry("CircuitBreaker", List.of(
          "io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker")),
      Map.entry("Validated", List.of("org.springframework.validation.annotation.Validated")),
      Map.entry("TransactionalEventListener", List.of(
          "org.springframework.transaction.event.TransactionalEventListener")));
  private static final Set<String> SPRING_PROXY_ANNOTATIONS =
      Set.copyOf(SPRING_PROXY_ANNOTATION_TYPES.keySet());
  private static final String SPRING_COMMAND_LINE_RUNNER =
      "org.springframework.boot.CommandLineRunner";
  private static final Map<String, String> SPRING_DATA_REPOSITORY_CONTRACTS = Map.of(
      "org.springframework.data.repository.Repository", "Repository",
      "org.springframework.data.repository.CrudRepository", "CrudRepository",
      "org.springframework.data.jpa.repository.JpaRepository", "JpaRepository",
      "org.springframework.data.repository.PagingAndSortingRepository", "PagingAndSortingRepository",
      "org.springframework.data.repository.ListCrudRepository", "ListCrudRepository",
      "org.springframework.data.repository.ListPagingAndSortingRepository", "ListPagingAndSortingRepository");
  private static final Set<String> SPRING_DATA_FIND_BY_ID_CONTRACTS = Set.of(
      "CrudRepository", "JpaRepository", "PagingAndSortingRepository", "ListCrudRepository",
      "ListPagingAndSortingRepository");
  private static final List<String> SUPPORTED_HTTP_METHODS =
      List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

  private final AnalysisContext context;
  private List<SourceFile> files;
  private Set<String> declaredTypeNames;

  /** Creates a scanner over the selected analysis source roots. */
  SourceMetadataScanner(AnalysisContext context) {
    this.context = Objects.requireNonNull(context, "analysis context");
  }

  /** Loads and caches Java source files from included roots. */
  List<SourceFile> files() {
    if (files != null) {
      return files;
    }
    Map<Path, AnalysisContext.SourceRoot> selectedRoots = new LinkedHashMap<>();
    for (AnalysisContext.SourceRoot root :
        AnalysisContext.rootsBySpecificity(context.includedSourceRoots())) {
      if (!Files.isDirectory(root.path())) {
        continue;
      }
      try (Stream<Path> paths = Files.walk(root.path())) {
        paths.filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java"))
            .sorted()
            .forEach(path -> selectedRoots.putIfAbsent(path.toAbsolutePath().normalize(), root));
      } catch (IOException error) {
        throw new IllegalStateException("cannot scan source root " + root.path(), error);
      }
    }
    List<SourceFile> result = new ArrayList<>(selectedRoots.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> read(entry.getKey(), entry.getValue()))
        .toList());
    result.sort(Comparator.comparing(SourceFile::displayPath));
    files = List.copyOf(result);
    return files;
  }

  /** Returns fully qualified source owners, including nested declarations. */
  Set<String> declaredTypeNames() {
    if (declaredTypeNames != null) {
      return declaredTypeNames;
    }
    Set<String> result = new LinkedHashSet<>();
    for (SourceFile file : files()) {
      for (TypeInfo type : typeDeclarations(file)) {
        result.add(typeContaining(file, type.start()));
      }
    }
    declaredTypeNames = Set.copyOf(result);
    return declaredTypeNames;
  }

  /** Returns whether a source declaration is an interface in the selected roots. */
  boolean isInterfaceType(String typeName) {
    return declaredType(typeName)
        .map(type -> type.kind().equals("interface"))
        .orElse(false);
  }

  /** Returns whether a source class declares an interface implementation contract. */
  boolean implementsInterface(String typeName) {
    for (SourceFile file : files()) {
      for (TypeInfo type : typeDeclarations(file)) {
        if (!typeContaining(file, type.start()).equals(typeName) || !type.kind().equals("class")) {
          continue;
        }
        String source = maskStringLiterals(file.structuralSource());
        int opening = typeBodyOpening(source, type.start());
        if (opening >= 0 && Pattern.compile("\\bimplements\\b")
            .matcher(source.substring(type.start(), opening)).find()) {
          return true;
        }
      }
    }
    return false;
  }

  /** Returns the source body containing one provider call site. */
  Optional<String> methodSource(MethodSymbol symbol, int line) {
    SourceFile file = sourceFile(symbol.owner()).orElse(null);
    if (file == null) {
      return Optional.empty();
    }
    int lineOffset = line > 0 ? lineStartOffset(file.source(), line) : -1;
    List<MethodInfo> candidates = executableMethods(file).stream()
        .filter(method -> method.name().equals(symbol.name()))
        .filter(method -> typeContaining(file, method.start()).equals(symbol.owner()))
        .toList();
    MethodInfo selected = candidates.stream()
        .filter(method -> lineOffset >= 0 && methodContains(file, method, lineOffset))
        .findFirst()
        .orElseGet(() -> candidates.stream()
            .filter(method -> descriptorParameterCount(symbol.descriptor()) < 0
                || parameters(method.parameters()).size() == descriptorParameterCount(symbol.descriptor()))
            .findFirst()
            .orElse(null));
    if (selected == null && lineOffset >= 0) {
      MethodInfo enclosing = methodContaining(file, lineOffset);
      if (!enclosing.name().equals("<unknown>")) {
        selected = enclosing;
      }
    }
    if (selected == null) {
      return Optional.empty();
    }
    int bodyEnd = selected.end();
    String source = file.structuralSource();
    if (bodyEnd > 0 && bodyEnd <= source.length() && source.charAt(bodyEnd - 1) == '{') {
      int closing = matchingDelimiter(source, bodyEnd - 1, '{', '}');
      if (closing >= 0) {
        bodyEnd = closing + 1;
      }
    }
    return Optional.of(source.substring(selected.start(), Math.min(bodyEnd, source.length())));
  }

  /** Checks a token on the source line attributed to a provider call. */
  boolean sourceLineContains(MethodSymbol symbol, int line, String token) {
    if (line <= 0) {
      return false;
    }
    return sourceFile(symbol.owner())
        .map(file -> {
          String source = maskStringLiterals(file.structuralSource());
          int start = lineStartOffset(source, line);
          if (start < 0) {
            return false;
          }
          int end = source.indexOf('\n', start);
          String sourceLine = source.substring(start, end < 0 ? source.length() : end);
          return sourceLine.contains(token);
        })
        .orElse(false);
  }

  private Optional<TypeInfo> declaredType(String typeName) {
    return sourceFile(typeName).flatMap(file -> typeDeclarations(file).stream()
        .filter(type -> typeContaining(file, type.start()).equals(typeName))
        .findFirst());
  }

  private Optional<SourceFile> sourceFile(String owner) {
    String topLevel = owner.contains("$") ? owner.substring(0, owner.indexOf('$')) : owner;
    return files().stream().filter(file -> file.typeName().equals(topLevel)).findFirst();
  }

  private static boolean methodContains(SourceFile file, MethodInfo method, int offset) {
    String source = file.structuralSource();
    if (method.start() > offset || method.end() <= 0 || method.end() > source.length()
        || source.charAt(method.end() - 1) != '{') {
      return false;
    }
    int closing = matchingDelimiter(source, method.end() - 1, '{', '}');
    return closing >= offset;
  }

  private static int lineStartOffset(String source, int line) {
    if (line <= 0) {
      return -1;
    }
    int currentLine = 1;
    for (int index = 0; index < source.length(); index++) {
      if (currentLine == line) {
        return index;
      }
      if (source.charAt(index) == '\n') {
        currentLine++;
      }
    }
    return currentLine == line ? source.length() : -1;
  }

  private static int descriptorParameterCount(String descriptor) {
    if (descriptor == null || descriptor.isBlank() || descriptor.charAt(0) != '(') {
      return -1;
    }
    int close = descriptor.indexOf(')');
    if (close < 0) {
      return -1;
    }
    int count = 0;
    for (int index = 1; index < close;) {
      while (index < close && descriptor.charAt(index) == '[') {
        index++;
      }
      if (index >= close) {
        return -1;
      }
      if (descriptor.charAt(index) == 'L') {
        int end = descriptor.indexOf(';', index);
        if (end < 0 || end > close) {
          return -1;
        }
        index = end + 1;
      } else {
        index++;
      }
      count++;
    }
    return count;
  }

  /** Resolves a source type using explicit imports, the declaring package, or java.lang. */
  Optional<String> resolveReferenceType(String type, SourceFile sourceFile) {
    String normalized = stripBalancedGenericArguments(type == null ? "" : type).trim();
    if (normalized.endsWith("...")) {
      normalized = normalized.substring(0, normalized.length() - 3) + "[]";
    }
    if (normalized.endsWith("[]")) {
      return resolveReferenceType(normalized.substring(0, normalized.length() - 2), sourceFile)
          .map(value -> value + "[]");
    }
    if (JAVA_PRIMITIVE_TYPES.contains(normalized)) {
      return Optional.of(normalized);
    }
    String referenceName = normalized;
    List<String> imported = importedTypes(normalized, sourceFile);
    if (imported.size() > 1) {
      return Optional.empty();
    }
    if (imported.size() == 1) {
      return Optional.of(imported.get(0));
    }
    if (normalized.contains(".") && Character.isLowerCase(normalized.charAt(0))) {
      return Optional.of(normalized);
    }
    List<String> samePackage = files().stream()
        .filter(file -> file.packageName().equals(sourceFile.packageName()))
        .filter(file -> file.simpleTypeName().equals(referenceName))
        .map(SourceFile::typeName)
        .distinct()
        .toList();
    if (samePackage.size() > 1) {
      return Optional.empty();
    }
    if (samePackage.size() == 1) {
      return Optional.of(samePackage.get(0));
    }
    List<String> onDemandImported = onDemandImportedTypes(normalized, sourceFile);
    if (onDemandImported.size() > 1) {
      return Optional.empty();
    }
    if (onDemandImported.size() == 1) {
      return Optional.of(onDemandImported.get(0));
    }
    return JAVA_LANG_TYPES.contains(normalized)
        ? Optional.of("java.lang." + normalized)
        : Optional.empty();
  }

  /** Resolves a contract while ignoring annotation imports that share its simple name. */
  Optional<String> resolveContractType(String type, SourceFile sourceFile) {
    String normalized = stripBalancedGenericArguments(type == null ? "" : type).trim();
    if (normalized.isBlank()) {
      return Optional.empty();
    }
    List<String> importedSourceTypes = importedTypes(normalized, sourceFile).stream()
        .filter(imported -> files().stream().anyMatch(file -> file.typeName().equals(imported)))
        .distinct()
        .toList();
    if (importedSourceTypes.size() == 1) {
      return Optional.of(importedSourceTypes.get(0));
    }
    if (importedSourceTypes.size() > 1) {
      return Optional.empty();
    }
    List<String> samePackage = files().stream()
        .filter(file -> file.packageName().equals(sourceFile.packageName()))
        .filter(file -> file.simpleTypeName().equals(normalized))
        .map(SourceFile::typeName)
        .distinct()
        .toList();
    if (samePackage.size() == 1) {
      return Optional.of(samePackage.get(0));
    }
    return resolveReferenceType(normalized, sourceFile);
  }

  /** Resolves explicit imports that can qualify a source type spelling. */
  private static List<String> importedTypes(String type, SourceFile sourceFile) {
    String simpleType = type;
    int nestedSeparator = type.indexOf('.');
    if (nestedSeparator >= 0) {
      simpleType = type.substring(0, nestedSeparator);
    }
    Matcher matcher = Pattern.compile("(?m)^\\s*import\\s+([A-Za-z_][\\w.]*)\\s*;")
        .matcher(maskStringLiterals(sourceFile.structuralSource()));
    List<String> result = new ArrayList<>();
    while (matcher.find()) {
      String imported = matcher.group(1);
      if (imported.endsWith("." + simpleType)) {
        result.add(imported + type.substring(simpleType.length()));
      }
    }
    return result.stream().distinct().toList();
  }

  /** Resolves on-demand imports only against unambiguous scanned source types. */
  private List<String> onDemandImportedTypes(String type, SourceFile sourceFile) {
    return onDemandImportedTypeNames(
        type,
        sourceFile,
        files().stream().map(SourceFile::typeName));
  }

  /** Resolves on-demand imports against the bounded set of known JDK types. */
  private static List<String> onDemandImportedJdkTypes(String type, SourceFile sourceFile) {
    return onDemandImportedTypeNames(type, sourceFile, KNOWN_JDK_TYPE_NAMES.stream());
  }

  /** Applies one on-demand import lookup to either source or JDK type candidates. */
  private static List<String> onDemandImportedTypeNames(
      String type, SourceFile sourceFile, Stream<String> candidates) {
    String simpleType = type;
    int nestedSeparator = type.indexOf('.');
    if (nestedSeparator >= 0) {
      simpleType = type.substring(0, nestedSeparator);
    }
    Set<String> packages = onDemandImportPackages(sourceFile);
    String suffix = type.substring(simpleType.length());
    String requestedSimpleType = simpleType;
    return candidates
        .filter(candidate -> packages.contains(packageName(candidate)))
        .filter(candidate -> qualifiedSimpleName(candidate).equals(requestedSimpleType))
        .map(candidate -> candidate + suffix)
        .distinct()
        .toList();
  }

  private static Set<String> onDemandImportPackages(SourceFile sourceFile) {
    Matcher matcher = Pattern.compile("(?m)^\\s*import\\s+([A-Za-z_][\\w.]*)\\.\\*\\s*;")
        .matcher(maskStringLiterals(sourceFile.structuralSource()));
    Set<String> packages = new LinkedHashSet<>();
    while (matcher.find()) {
      packages.add(matcher.group(1));
    }
    return packages;
  }

  private static String packageName(String qualifiedName) {
    int separator = qualifiedName.lastIndexOf('.');
    return separator < 0 ? "" : qualifiedName.substring(0, separator);
  }

  private static String qualifiedSimpleName(String qualifiedName) {
    return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
  }

  /** Scans bounded Quarkus and Spring route annotations. */
  List<Route> routes(AnalysisContext.Framework framework) {
    List<Route> result = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (SourceFile file : files()) {
      String classBlock = file.classAnnotationBlock();
      boolean quarkusClassPath = hasAnnotation(classBlock, "Path");
      boolean springController = framework == AnalysisContext.Framework.SPRING_BOOT
          && canonicalSpringAnnotation(file, classBlock, "RestController");
      if (framework == AnalysisContext.Framework.SPRING_BOOT && !springController) {
        continue;
      }
      List<String> classPaths = framework == AnalysisContext.Framework.QUARKUS
          ? pathValues(classBlock, "Path")
          : mappingPaths(file, classBlock, "RequestMapping");
      if (framework == AnalysisContext.Framework.QUARKUS && quarkusClassPath && classPaths.isEmpty()) {
        continue;
      }
      if (framework == AnalysisContext.Framework.SPRING_BOOT
          && canonicalSpringAnnotation(file, classBlock, "RequestMapping")
          && classPaths.isEmpty()) {
        continue;
      }
      if (classPaths.isEmpty()) {
        classPaths = List.of("");
      }
      List<String> classHttpMethods = framework == AnalysisContext.Framework.SPRING_BOOT
          ? httpMethods(file, classBlock, "RequestMapping")
          : SUPPORTED_HTTP_METHODS;
      for (MethodInfo method : methods(file)) {
        for (String annotationName : routeAnnotations(file, method.annotationBlock(), framework)) {
          List<String> httpMethods = intersectHttpMethods(
              httpMethods(file, method.annotationBlock(), annotationName), classHttpMethods);
          if (httpMethods.isEmpty()) {
            continue;
          }
          boolean methodPathAnnotation = framework == AnalysisContext.Framework.QUARKUS
              && hasAnnotation(method.annotationBlock(), "Path");
          List<String> methodPaths = framework == AnalysisContext.Framework.QUARKUS
              ? pathValues(method.annotationBlock(), "Path")
              : mappingPaths(file, method.annotationBlock(), annotationName);
          if (framework == AnalysisContext.Framework.QUARKUS && methodPathAnnotation && methodPaths.isEmpty()) {
            continue;
          }
          if (framework == AnalysisContext.Framework.SPRING_BOOT
              && methodPaths.isEmpty()
              && hasExplicitMappingPath(file, method.annotationBlock(), annotationName)) {
            continue;
          }
          if (methodPaths.isEmpty()) {
            methodPaths = List.of("");
          }
          if (framework == AnalysisContext.Framework.QUARKUS && !quarkusClassPath) {
            continue;
          }
          List<String> consumes = overrideValues(
              framework == AnalysisContext.Framework.QUARKUS
                  ? mediaValues(classBlock, "Consumes", null)
                  : mediaValues(file, classBlock, "RequestMapping", "consumes"),
              framework == AnalysisContext.Framework.QUARKUS
                  ? mediaValues(method.annotationBlock(), "Consumes", null)
                  : mediaValues(file, method.annotationBlock(), annotationName, "consumes"));
          List<String> produces = overrideValues(
              framework == AnalysisContext.Framework.QUARKUS
                  ? mediaValues(classBlock, "Produces", null)
                  : mediaValues(file, classBlock, "RequestMapping", "produces"),
              framework == AnalysisContext.Framework.QUARKUS
                  ? mediaValues(method.annotationBlock(), "Produces", null)
                  : mediaValues(file, method.annotationBlock(), annotationName, "produces"));
          List<String> headers = framework == AnalysisContext.Framework.SPRING_BOOT
              ? mergeValues(
                  values(file, classBlock, "RequestMapping", "headers"),
                  values(file, method.annotationBlock(), annotationName, "headers"))
              : List.of();
          List<String> parameters = framework == AnalysisContext.Framework.SPRING_BOOT
              ? mergeValues(
                  values(file, classBlock, "RequestMapping", "params"),
                  values(file, method.annotationBlock(), annotationName, "params"))
              : List.of();
          for (String httpMethod : httpMethods) {
            for (String classPath : classPaths) {
              for (String methodPath : methodPaths) {
                String path = joinPath(classPath, methodPath);
                String key = routeKey(file, method, httpMethod, path, consumes, produces, headers, parameters);
                if (!seen.add(key)) {
                  continue;
                }
                result.add(new Route(
                    file,
                    method,
                    httpMethod,
                    path,
                    consumes,
                    produces,
                    headers,
                    parameters,
                    routeSymbols(file, classBlock, method.annotationBlock(), framework, annotationName)));
              }
            }
          }
        }
      }
    }
    return List.copyOf(result);
  }

  /** Scans constructor, field, and method injection points. */
  List<InjectionPoint> injectionPoints(AnalysisContext.Framework framework) {
    List<InjectionPoint> result = new ArrayList<>();
    for (SourceFile file : files()) {
      boolean component = framework == AnalysisContext.Framework.QUARKUS
          ? cdiBean(file.classAnnotationBlock())
          : springComponent(file);
      Pattern constructor = Pattern.compile(
          "(?s)((?:(?:@[A-Za-z_][\\w.]*(?:\\([^)]*\\))?\\s*)*))"
              + "(?:public|protected|private)?\\s*"
              + Pattern.quote(file.simpleTypeName())
              + "\\s*\\(([^)]*)\\)\\s*\\{");
      String maskedSource = maskAnnotationArguments(file.structuralSource());
      Matcher matcher = constructor.matcher(maskedSource);
      List<ConstructorInfo> constructors = new ArrayList<>();
      while (matcher.find()) {
        constructors.add(new ConstructorInfo(
            file.source().substring(matcher.start(1), matcher.end(1)),
            file.source().substring(matcher.start(2), matcher.end(2)),
            matcher.start()));
      }
      boolean springExplicitConstructor = constructors.stream()
          .anyMatch(item -> hasInjectionAnnotation(item.annotations()));
      for (ConstructorInfo constructorInfo : constructors) {
        boolean annotated = hasInjectionAnnotation(constructorInfo.annotations());
        boolean selected = framework == AnalysisContext.Framework.QUARKUS
            ? annotated
            : component && (annotated || (!springExplicitConstructor && constructors.size() == 1));
        if (!selected) {
          continue;
        }
        List<Parameter> parsedParameters = parameters(constructorInfo.parameters());
        if (!parsedParameters.isEmpty()) {
          result.add(new InjectionPoint(
              file,
              parsedParameters,
              file.lineAt(constructorInfo.start()),
              constructorInfo.start(),
              typeContaining(file, constructorInfo.start()),
              "<init>",
              "constructor",
              qualifierNames(constructorInfo.annotations())));
        }
      }

      if (component || (framework == AnalysisContext.Framework.QUARKUS
          && hasAnnotation(file.classAnnotationBlock(), "Path"))) {
        Matcher fieldMatcher = INJECT_FIELD.matcher(file.structuralSource());
        while (fieldMatcher.find()) {
          String annotations = fieldMatcher.group(1);
          if (!hasInjectionAnnotation(annotations)) {
            continue;
          }
          List<String> fieldQualifiers = injectionQualifiers(
              annotations, fieldMatcher.group(3), framework == AnalysisContext.Framework.QUARKUS);
          Parameter parameter = new Parameter(
              fieldMatcher.group(2), fieldMatcher.group(3), fieldQualifiers);
          result.add(new InjectionPoint(
              file,
              List.of(parameter),
              file.lineAt(fieldMatcher.start()),
              fieldMatcher.start(),
              typeContaining(file, fieldMatcher.start()),
              fieldMatcher.group(3),
              "field",
              fieldQualifiers));
        }
        for (MethodInfo method : methods(file)) {
          if (method.name().equals(file.simpleTypeName())
              || !hasInjectionAnnotation(method.annotationBlock())) {
            continue;
          }
          List<Parameter> parsedParameters = parameters(method.parameters());
          if (!parsedParameters.isEmpty()) {
            List<String> methodQualifiers = injectionQualifiers(
                method.annotationBlock(), method.name(), framework == AnalysisContext.Framework.QUARKUS);
            result.add(new InjectionPoint(
                file,
                parsedParameters,
                method.line(),
                method.start(),
                typeContaining(file, method.start()),
                method.name(),
                "method",
                methodQualifiers));
          }
        }
      }
    }
    return List.copyOf(result);
  }

  /** Scans component, repository, and producer bean candidates. */
  List<BeanCandidate> beanCandidates(AnalysisContext.Framework framework) {
    List<BeanCandidate> result = new ArrayList<>();
    for (SourceFile file : files()) {
      String block = file.classAnnotationBlock();
      boolean repositoryInterface = framework == AnalysisContext.Framework.SPRING_BOOT
          && springRepositoryContract(file).isPresent();
      boolean bean = framework == AnalysisContext.Framework.QUARKUS
          ? cdiBean(block)
          : springComponent(file) || repositoryInterface;
      if (bean) {
        Set<String> contracts = contracts(file);
        result.add(new BeanCandidate(
            file,
            contracts,
            repositoryInterface ? "repository_interface" : beanKind(block, framework),
            file.typeName(),
            file.typeName(),
            qualifierNames(block),
            profileValues(block),
            conditionAnnotations(block),
            hasAnnotation(block, "Alternative"),
            hasAnnotation(block, "Primary"),
            null,
            null,
            null));
      }

      for (MethodInfo method : methods(file)) {
        boolean producer = framework == AnalysisContext.Framework.QUARKUS
            ? cdiProducerAnnotation(file, method.annotationBlock())
            : springBeanAnnotation(file, method.annotationBlock());
        if (!producer || method.returnType().isBlank()) {
          continue;
        }
        String candidateId = file.typeName() + "#" + method.name() + "("
            + method.parameters().replaceAll("\\s+", " ").trim() + ")";
        String producerType = resolveReferenceType(method.returnType(), file).orElse("");
        result.add(new BeanCandidate(
            file,
            Set.of(),
            "producer",
            candidateId,
            producerType,
            mergeValues(qualifierNames(block), qualifierNames(method.annotationBlock())),
            mergeValues(profileValues(block), profileValues(method.annotationBlock())),
            mergeValues(conditionAnnotations(block), conditionAnnotations(method.annotationBlock())),
            hasAnnotation(block, "Alternative") || hasAnnotation(method.annotationBlock(), "Alternative"),
            hasAnnotation(block, "Primary") || hasAnnotation(method.annotationBlock(), "Primary"),
            method.name(),
            method.returnType(),
            method.parameters()));
      }
    }
    return List.copyOf(result);
  }

  /** Finds bounded Spring Data repository interfaces, including annotated repositories. */
  Optional<String> springRepositoryContract(SourceFile file) {
    String header = maskStringLiterals(typeHeader(file));
    if (!header.matches("(?s).*\\binterface\\s+\\w+\\b.*")) {
      return Optional.empty();
    }
    int extendsIndex = topLevelKeywordIndex(header, "extends");
    if (extendsIndex < 0) {
      return Optional.empty();
    }
    int permitsIndex = topLevelKeywordIndex(header, "permits");
    String extended = header.substring(
        extendsIndex + "extends".length(), permitsIndex >= 0 ? permitsIndex : header.length());
    return typeNames(extended).stream()
        .map(type -> resolveKnownFrameworkType(type, file, SPRING_DATA_REPOSITORY_CONTRACTS.keySet()))
        .flatMap(Optional::stream)
        .map(SPRING_DATA_REPOSITORY_CONTRACTS::get)
        .findFirst();
  }

  /** Returns repository methods guaranteed by the contract or declared by the source interface. */
  List<RepositoryMethod> springRepositoryMethods(SourceFile file) {
    Optional<String> contract = springRepositoryContract(file);
    if (contract.isEmpty()) {
      return List.of();
    }
    Map<String, RepositoryMethod> result = new LinkedHashMap<>();
    if (SPRING_DATA_FIND_BY_ID_CONTRACTS.contains(contract.get())) {
      result.put("findById", new RepositoryMethod("findById", "findById", true));
    }
    for (MethodInfo method : methods(file)) {
      if (typeContaining(file, method.start()).equals(file.typeName())
          && abstractInstanceRepositoryMethod(method)
          && !redeclaresInheritedFindById(file, method)) {
        RepositoryMethod repositoryMethod = new RepositoryMethod(
            method.name(), repositoryMethodSignature(method), false);
        result.putIfAbsent(repositoryMethod.identity(), repositoryMethod);
      }
    }
    return List.copyOf(result.values());
  }

  /** Detects a source declaration that exactly redeclares the repository ID contract. */
  private boolean redeclaresInheritedFindById(SourceFile file, MethodInfo method) {
    if (!method.name().equals("findById")) {
      return false;
    }
    Optional<String> repositoryIdType = springRepositoryIdType(file);
    List<Parameter> methodParameters = parameters(method.parameters());
    if (repositoryIdType.isEmpty() || methodParameters.size() != 1) {
      return false;
    }
    return repositoryTypeToken(methodParameters.get(0).type(), file)
        .filter(repositoryIdType.get()::equals)
        .isPresent();
  }

  /** Resolves the ID type from the first supported Spring Data contract declaration. */
  private Optional<String> springRepositoryIdType(SourceFile file) {
    String header = maskStringLiterals(typeHeader(file));
    int extendsIndex = topLevelKeywordIndex(header, "extends");
    if (extendsIndex < 0) {
      return Optional.empty();
    }
    int permitsIndex = topLevelKeywordIndex(header, "permits");
    String extended = header.substring(
        extendsIndex + "extends".length(), permitsIndex >= 0 ? permitsIndex : header.length());
    for (String type : splitArguments(extended)) {
      if (resolveKnownFrameworkType(type, file, SPRING_DATA_REPOSITORY_CONTRACTS.keySet()).isEmpty()) {
        continue;
      }
      String arguments = genericArguments(type);
      List<String> parameters = splitArguments(arguments);
      if (parameters.size() >= 2) {
        Optional<String> resolved = repositoryTypeToken(parameters.get(1), file);
        if (resolved.isPresent()) {
          return resolved;
        }
      }
    }
    return Optional.empty();
  }

  /** Resolves a repository type argument, retaining declared type variables for identity checks. */
  private Optional<String> repositoryTypeToken(String type, SourceFile file) {
    String normalized = stripBalancedGenericArguments(type == null ? "" : type).trim();
    Optional<String> resolved = resolveReferenceType(normalized, file);
    if (resolved.isPresent()) {
      return resolved;
    }
    if (normalized.matches("[A-Za-z_$][\\w$]*")
        && typeVariableBounds(file, normalized, file.typeDeclarationStart()).isPresent()) {
      return Optional.of(normalized);
    }
    return Optional.empty();
  }

  /** Keeps only abstract instance methods that Spring Data can implement from an interface. */
  private static boolean abstractInstanceRepositoryMethod(MethodInfo method) {
    String declaration = maskAnnotationArguments(method.annotationBlock());
    return !Pattern.compile("(?<![\\w$])(?:default|static|private)(?![\\w$])")
        .matcher(declaration)
        .find();
  }

  /** Creates a stable source-level signature without parameter names for overload identity. */
  private static String repositoryMethodSignature(MethodInfo method) {
    List<String> parameterTypes = parameters(method.parameters()).stream()
        .map(Parameter::type)
        .map(type -> type.replaceAll("\\s+", " ").trim())
        .toList();
    return method.name() + "(" + String.join(",", parameterTypes) + ")";
  }

  /** Resolves only known framework types, abstaining on ambiguous or unknown imports. */
  private Optional<String> resolveKnownFrameworkType(
      String type, SourceFile sourceFile, Set<String> canonicalTypes) {
    String normalized = stripBalancedGenericArguments(type == null ? "" : type).trim();
    if (canonicalTypes.contains(normalized)) {
      return Optional.of(normalized);
    }
    List<String> imported = importedTypes(normalized, sourceFile);
    if (imported.size() > 1) {
      return Optional.empty();
    }
    if (imported.size() == 1 && canonicalTypes.contains(imported.get(0))) {
      return Optional.of(imported.get(0));
    }
    return resolveContractType(normalized, sourceFile).filter(canonicalTypes::contains);
  }

  /** Scans Spring @Bean and bounded CommandLineRunner lifecycle callbacks. */
  List<LifecycleMethod> springLifecycleMethods() {
    List<LifecycleMethod> result = new ArrayList<>();
    for (SourceFile file : files()) {
      boolean springBean = springComponent(file);
      boolean commandLineRunner = springBean
          && implementsNamedInterface(file, SPRING_COMMAND_LINE_RUNNER);
      for (MethodInfo method : methods(file)) {
        if (!typeContaining(file, method.start()).equals(file.typeName())) {
          continue;
        }
        if (springBeanAnnotation(file, method.annotationBlock())) {
          if (!springBean) {
            continue;
          }
          result.add(new LifecycleMethod(
              file,
              method,
              "bean",
              lifecycleAnnotationSymbols(file, method)));
        }
        if (commandLineRunner
            && method.name().equals("run")
            && commandLineRunnerParameters(method.parameters())) {
          result.add(new LifecycleMethod(
              file,
              method,
              "runner",
              lifecycleAnnotationSymbols(file, method)));
        }
      }
    }
    return List.copyOf(result);
  }

  /** Returns whether a class header implements the named interface. */
  private boolean implementsNamedInterface(SourceFile file, String interfaceName) {
    String header = maskStringLiterals(typeHeader(file));
    int implementsIndex = keywordIndex(header, "implements");
    if (implementsIndex < 0) {
      return false;
    }
    int end = keywordIndexAfter(header, "permits", implementsIndex + "implements".length());
    String implemented = header.substring(
        implementsIndex + "implements".length(), end >= 0 ? end : header.length());
    return typeNames(implemented).stream()
        .map(type -> resolveKnownFrameworkType(type, file, Set.of(interfaceName)))
        .flatMap(Optional::stream)
        .anyMatch(interfaceName::equals);
  }

  /** Accepts both String... and the equivalent String[] CommandLineRunner signature. */
  private static boolean commandLineRunnerParameters(String source) {
    List<Parameter> parameters = parameters(source);
    if (parameters.size() != 1) {
      return false;
    }
    String type = parameters.get(0).type().replaceAll("\\s+", "");
    return type.equals("String[]") || type.equals("java.lang.String[]");
  }

  /** Preserves the exact source annotations used to establish a lifecycle candidate. */
  private static List<String> lifecycleAnnotationSymbols(SourceFile file, MethodInfo method) {
    List<String> result = new ArrayList<>();
    result.addAll(annotationSources(file.classAnnotationBlock(), List.of(
        "Component", "Service", "Repository", "Configuration", "Controller",
        "RestController", "Named")));
    result.addAll(annotationSources(method.annotationBlock(), List.of("Bean")));
    return List.copyOf(new LinkedHashSet<>(result));
  }

  /** Recognizes only the canonical Spring {@code @Bean} annotation. */
  private static boolean springBeanAnnotation(SourceFile file, String block) {
    return canonicalAnnotation(file, block, "Bean", "org.springframework.context.annotation.Bean");
  }

  /** Scans framework proxy targets and records CDI scope eligibility. */
  List<ProxyTarget> proxyTargets(AnalysisContext.Framework framework) {
    List<ProxyTarget> result = new ArrayList<>();
    for (SourceFile file : files()) {
      String classBlock = file.classAnnotationBlock();
      boolean bean = framework == AnalysisContext.Framework.QUARKUS
          ? cdiBean(classBlock)
          : springComponent(file);
      if (!bean) {
        continue;
      }
      boolean normalScoped = CDI_NORMAL_SCOPES.stream().anyMatch(name -> hasAnnotation(classBlock, name));
      for (MethodInfo method : methods(file)) {
        boolean selected = framework == AnalysisContext.Framework.QUARKUS
            ? !method.name().equals(file.simpleTypeName())
            : SPRING_PROXY_ANNOTATIONS.stream()
                .anyMatch(name -> canonicalSpringAnnotation(file, method.annotationBlock(), name))
                || SPRING_PROXY_ANNOTATIONS.stream()
                    .anyMatch(name -> canonicalSpringAnnotation(file, classBlock, name));
        if (selected && springProxyMethod(file, method)) {
          result.add(new ProxyTarget(
              file,
              method,
              file.typeName(),
              framework.id(),
              framework != AnalysisContext.Framework.QUARKUS || normalScoped));
        }
      }
    }
    return List.copyOf(result);
  }

  /** Scans methods in generated source roots. */
  List<GeneratedMethod> generatedMethods() {
    List<GeneratedMethod> result = new ArrayList<>();
    for (SourceFile file : files()) {
      if (!file.generated()) {
        continue;
      }
      for (MethodInfo method : methods(file)) {
        result.add(new GeneratedMethod(file, method));
      }
    }
    return List.copyOf(result);
  }

  /** Scans bounded reflection and dynamic-proxy construction features. */
  List<DynamicFeature> dynamicFeatures() {
    List<DynamicFeature> result = new ArrayList<>();
    for (SourceFile file : files()) {
      for (DynamicSite site : reflectionSites(file)) {
        MethodInfo caller = methodContaining(file, site.offset());
        result.add(new DynamicFeature(
            DynamicFeature.Kind.REFLECTION,
            new MethodSymbol(typeContaining(file, site.offset()), caller.name(), ""),
            site.target(),
            file.displayPath(),
            List.of(site.symbol()),
            "Open-world reflection APIs do not identify a unique runtime target.",
            file.lineAt(site.offset()),
            site.offset()));
      }
      String dynamicSource = maskStringLiterals(file.structuralSource());
      for (int proxyOffset : proxyCallOffsets(file, dynamicSource)) {
        List<String> interfaceNames = proxyInterfaceNames(file.structuralSource(), proxyOffset);
        MethodInfo caller = methodContaining(file, proxyOffset);
        if (interfaceNames.isEmpty()) {
          result.add(dynamicProxyFeature(file, caller, "unknown#unknown", "", proxyOffset));
        } else {
          for (String interfaceName : interfaceNames) {
            String qualified = resolveContractType(interfaceName, file)
                .orElseGet(() -> qualify(interfaceName, file.packageName()));
            Optional<SourceFile> interfaceFile = files().stream()
                .filter(candidate -> candidate.typeName().equals(qualified))
                .findFirst();
            List<MethodInfo> interfaceMethods = interfaceFile.map(SourceMetadataScanner::methods).orElse(List.of());
            if (interfaceMethods.isEmpty()) {
              result.add(dynamicProxyFeature(file, caller, qualified + "#unknown", "", proxyOffset));
            } else {
              for (MethodInfo method : interfaceMethods) {
                result.add(dynamicProxyFeature(
                    file,
                    caller,
                    qualified + "#" + method.name(),
                    methodDescriptor(method),
                    proxyOffset));
              }
            }
          }
        }
      }
    }
    return List.copyOf(result);
  }

  /** Finds qualified proxy calls and explicitly statically imported unqualified calls. */
  private List<Integer> proxyCallOffsets(SourceFile file, String source) {
    List<Integer> result = new ArrayList<>();
    Matcher qualified = PROXY_CALL.matcher(source);
    while (qualified.find()) {
      if (isBareReceiver(source, qualified.start(1))
          && (isJdkApiOwner(file, qualified.group(1), qualified.start(1), "java.lang.reflect.Proxy")
          || isDeclaredVariableBefore(file, "Proxy", qualified.group(1), qualified.start(1)))) {
        result.add(qualified.start());
      }
    }
    Matcher thisQualified = THIS_PROXY_CALL.matcher(source);
    while (thisQualified.find()) {
      if (isBareReceiver(source, thisQualified.start())
          && hasVisibleField(file, "Proxy", thisQualified.group(1), thisQualified.start(1))) {
        result.add(thisQualified.start());
      }
    }
    Matcher qualifiedThis = QUALIFIED_THIS_PROXY_CALL.matcher(source);
    while (qualifiedThis.find()) {
      String owner = qualifiedThisOwner(file, qualifiedThis.group(1), qualifiedThis.start(1));
      if (owner != null
          && hasVisibleField(file, "Proxy", qualifiedThis.group(2), qualifiedThis.start(2), owner)) {
        result.add(qualifiedThis.start());
      }
    }
    if (STATIC_PROXY_IMPORT.matcher(source).find()) {
      Matcher unqualified = UNQUALIFIED_PROXY_CALL.matcher(source);
      while (unqualified.find()) {
        int prefix = unqualified.start() - 1;
        while (prefix >= 0 && Character.isWhitespace(source.charAt(prefix))) {
          prefix--;
        }
        if ((prefix < 0 || source.charAt(prefix) != '.')
            && !hasVisibleMethodNamed(file, unqualified.start(), "newProxyInstance")) {
          result.add(unqualified.start());
        }
      }
    }
    return result.stream().distinct().sorted().toList();
  }

  /** Finds the interface array associated with one proxy construction site. */
  private static List<String> proxyInterfaceNames(String source, int proxyOffset) {
    int opening = source.indexOf('(', proxyOffset);
    if (opening < 0) {
      return List.of();
    }
    int closing = matchingDelimiter(source, opening, '(', ')');
    if (closing < 0) {
      return List.of();
    }
    List<String> arguments = splitTopLevelArguments(source.substring(opening + 1, closing));
    if (arguments.size() < 2) {
      return List.of();
    }
    Matcher interfaces = PROXY_INTERFACES.matcher(arguments.get(1));
    if (!interfaces.find()) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    Matcher literal = CLASS_LITERAL.matcher(interfaces.group(1));
    while (literal.find()) {
      result.add(literal.group(1));
    }
    return List.copyOf(result);
  }

  /** Splits one call's arguments without crossing nested expressions. */
  private static List<String> splitTopLevelArguments(String arguments) {
    return splitBalanced(arguments, ',', false);
  }

  /** Splits text at one delimiter while preserving nested Java expression boundaries. */
  private static List<String> splitBalanced(
      String source, char separator, boolean inferGenericStarts) {
    List<String> result = new ArrayList<>();
    int start = 0;
    int parentheses = 0;
    int brackets = 0;
    int braces = 0;
    int angleBrackets = 0;
    boolean quoted = false;
    boolean escaped = false;
    for (int index = 0; index < source.length(); index++) {
      char character = source.charAt(index);
      if (character == '"' && !escaped) {
        quoted = !quoted;
      }
      if (quoted) {
        escaped = character == '\\' && !escaped;
        if (character != '\\') {
          escaped = false;
        }
        continue;
      }
      escaped = false;
      switch (character) {
        case '(' -> parentheses++;
        case ')' -> parentheses--;
        case '[' -> brackets++;
        case ']' -> brackets--;
        case '{' -> braces++;
        case '}' -> braces--;
        case '<' -> {
          boolean atTopLevel = parentheses == 0 && brackets == 0 && braces == 0;
          if (inferGenericStarts
              ? looksLikeGenericStart(source, index, angleBrackets)
              : atTopLevel) {
            angleBrackets++;
          }
        }
        case '>' -> {
          if (inferGenericStarts
              || (parentheses == 0 && brackets == 0 && braces == 0)) {
            angleBrackets = Math.max(0, angleBrackets - 1);
          }
        }
        default -> {
          if (character == separator
              && parentheses == 0
              && brackets == 0
              && braces == 0
              && angleBrackets == 0) {
            result.add(source.substring(start, index).trim());
            start = index + 1;
          }
        }
      }
    }
    result.add(source.substring(start).trim());
    return result;
  }

  /** Finds each executable reflection marker without treating strings or comments as code. */
  private List<DynamicSite> reflectionSites(SourceFile file) {
    String source = file.structuralSource();
    String dynamicSource = maskStringLiterals(source);
    List<DynamicSite> result = new ArrayList<>();
    addClassForNameSites(result, dynamicSource, source, file);
    if (STATIC_FOR_NAME_IMPORT.matcher(dynamicSource).find()) {
      addStaticForNameSites(result, dynamicSource, source, file);
    }
    addReceiverCallSites(result, dynamicSource, source, file, RECEIVER_LOAD_CLASS, "ClassLoader");
    addReceiverCallSites(result, dynamicSource, source, file, RECEIVER_METHOD_LOOKUP, "Class");
    addReceiverCallSites(result, dynamicSource, source, file, RECEIVER_CONSTRUCTOR_LOOKUP, "Class");
    List<ChainedReceiver> supportedChainedReceivers = chainedReceivers(dynamicSource).stream()
        .filter(receiver -> isSupportedChainedReceiver(file, dynamicSource, receiver))
        .toList();
    addChainedLookupSites(
        result, dynamicSource, source, supportedChainedReceivers,
        "getDeclaredMethod", "getMethod", "getDeclaredConstructor", "getConstructor");
    addJdkOwnerSites(result, dynamicSource, source, file, CONSTRUCTOR_NEW_INSTANCE,
        "Constructor.newInstance", "java.lang.reflect.Constructor");
    addJdkOwnerSites(result, dynamicSource, source, file, CLASS_NEW_INSTANCE,
        "Class.newInstance", "java.lang.Class");

    addReceiverSites(result, dynamicSource, source, file, "newInstance", "Constructor", "Constructor.newInstance");
    addReceiverSites(result, dynamicSource, source, file, "newInstance", "Class", "Class.newInstance");

    Matcher invoke = METHOD_INVOKE.matcher(dynamicSource);
    while (invoke.find()) {
      if (isBareReceiver(dynamicSource, invoke.start(1))
          && (isDeclaredVariableBefore(file, "Method", invoke.group(1), invoke.start())
              || isInvocationHandlerParameterBefore(file, invoke.group(1), invoke.start()))) {
        addSite(result, source, invoke.start(), "Method.invoke");
      }
    }
    addChainedMethodInvokeSites(result, dynamicSource, source, supportedChainedReceivers);
    addChainedConstructorSites(result, dynamicSource, source, supportedChainedReceivers);
    addJdkOwnerSites(result, dynamicSource, source, file, METHOD_TYPE_INVOKE,
        "Method.invoke", "java.lang.reflect.Method");
    addThisReceiverSites(result, dynamicSource, source, file);
    addInlineProxyInvocationSites(result, dynamicSource, source, file);
    result.sort(Comparator.comparingInt(DynamicSite::offset).thenComparing(DynamicSite::symbol));
    return result;
  }

  private void addJdkOwnerSites(
      List<DynamicSite> result,
      String dynamicSource,
      String source,
      SourceFile file,
      Pattern pattern,
      String symbol,
      String expectedType) {
    Matcher matcher = pattern.matcher(dynamicSource);
    while (matcher.find()) {
      if (isBareReceiver(dynamicSource, matcher.start(1))
          && isJdkApiOwner(file, matcher.group(1), matcher.start(1), expectedType)) {
        addSite(result, source, matcher.start(), symbol);
      }
    }
  }

  private void addClassForNameSites(
      List<DynamicSite> result,
      String dynamicSource,
      String source,
      SourceFile file) {
    Matcher matcher = CLASS_FOR_NAME.matcher(dynamicSource);
    while (matcher.find()) {
      if (isBareReceiver(dynamicSource, matcher.start(1))
          && (isJdkApiOwner(file, matcher.group(1), matcher.start(1), "java.lang.Class")
          || isDeclaredVariableBefore(file, "Class", matcher.group(1), matcher.start(1)))) {
        addSite(result, source, matcher.start(), "Class.forName");
      }
    }
    Matcher thisQualified = THIS_CLASS_FOR_NAME.matcher(dynamicSource);
    while (thisQualified.find()) {
      if (isBareReceiver(dynamicSource, thisQualified.start())
          && hasVisibleField(file, "Class", thisQualified.group(1), thisQualified.start(1))) {
        addSite(result, source, thisQualified.start(), "Class.forName");
      }
    }
    Matcher qualifiedThis = QUALIFIED_THIS_CLASS_FOR_NAME.matcher(dynamicSource);
    while (qualifiedThis.find()) {
      String owner = qualifiedThisOwner(file, qualifiedThis.group(1), qualifiedThis.start(1));
      if (owner != null
          && hasVisibleField(file, "Class", qualifiedThis.group(2), qualifiedThis.start(2), owner)) {
        addSite(result, source, qualifiedThis.start(), "Class.forName");
      }
    }
  }

  private void addStaticForNameSites(
      List<DynamicSite> result, String dynamicSource, String source, SourceFile file) {
    Matcher matcher = STATIC_FOR_NAME.matcher(dynamicSource);
    while (matcher.find()) {
      int previous = matcher.start() - 1;
      while (previous >= 0 && Character.isWhitespace(dynamicSource.charAt(previous))) {
        previous--;
      }
      if ((previous < 0 || dynamicSource.charAt(previous) != '.')
          && !hasVisibleMethodNamed(file, matcher.start(), "forName")) {
        addSite(result, source, matcher.start(), "Class.forName");
      }
    }
  }

  private boolean hasVisibleMethodNamed(SourceFile file, int offset, String name) {
    String siteType = typeContaining(file, offset);
    Set<String> visibleOwners = visibleFieldOwners(file, siteType).keySet();
    return files().stream()
        .flatMap(candidate -> methods(candidate).stream()
            .filter(method -> method.name().equals(name))
            .filter(method -> !isDeclarationKeyword(method.returnType()))
            .filter(method -> isAccessibleMethod(candidate, file, method, siteType))
            .map(method -> typeContaining(candidate, method.start())))
        .anyMatch(visibleOwners::contains);
  }

  private static boolean isAccessibleMethod(
      SourceFile declaringFile, SourceFile siteFile, MethodInfo method, String siteType) {
    String declaration = declaringFile.structuralSource()
        .substring(method.start(), Math.min(method.end(), declaringFile.structuralSource().length()));
    String declaringType = typeContaining(declaringFile, method.start());
    boolean privateMethod = Pattern.compile("\\bprivate\\b").matcher(declaration).find();
    boolean lexicalAccess = siteType.equals(declaringType)
        || siteType.startsWith(declaringType + "$");
    if (privateMethod) {
      return lexicalAccess;
    }
    if (isInterfaceType(declaringFile, declaringType)
        && !lexicalAccess
        && Pattern.compile("\\bstatic\\b").matcher(declaration).find()) {
      return false;
    }
    return declaringFile.packageName().equals(siteFile.packageName())
        || Pattern.compile("\\b(?:public|protected)\\b").matcher(declaration).find()
        || isInterfaceType(declaringFile, declaringType);
  }

  private static boolean isInterfaceType(SourceFile file, String owner) {
    return typeDeclarations(file).stream()
        .filter(type -> typeContaining(file, type.start()).equals(owner))
        .anyMatch(type -> type.kind().equals("interface"));
  }

  private boolean startsWithJdkClassForName(SourceFile file, String source, int offset) {
    Matcher owner = CLASS_FOR_NAME_PREFIX.matcher(source.substring(offset));
    return !owner.find()
        || isJdkApiOwner(file, owner.group(1), offset, "java.lang.Class");
  }

  /** Confirms that a source owner resolves to the JDK API rather than an application type. */
  private boolean isJdkApiOwner(
      SourceFile file, String spelling, int offset, String expectedType) {
    if (spelling != null
        && spelling.matches("[A-Za-z_$][\\w$]*")
        && hasVisibleValueNamed(file, spelling, offset)) {
      String expectedSimpleName = jdkSimpleName(expectedType);
      return expectedSimpleName != null
          && isDeclaredVariableBefore(file, expectedSimpleName, spelling, offset);
    }
    return resolveOwnerType(spelling, file, offset)
        .map(expectedType::equals)
        .orElse(false);
  }

  private static String jdkSimpleName(String expectedType) {
    return switch (expectedType) {
      case "java.lang.Class" -> "Class";
      case "java.lang.ClassLoader" -> "ClassLoader";
      case "java.lang.reflect.Method" -> "Method";
      case "java.lang.reflect.Constructor" -> "Constructor";
      case "java.lang.reflect.InvocationHandler" -> "InvocationHandler";
      case "java.lang.reflect.Proxy" -> "Proxy";
      default -> null;
    };
  }

  private boolean hasVisibleValueNamed(SourceFile file, String variable, int offset) {
    MethodInfo owner = methodContaining(file, offset);
    return (!owner.name().equals("<unknown>") && hasParameter(owner, variable))
        || (!owner.name().equals("<unknown>")
            && activeLocalDeclaration(file, owner, variable, offset).isPresent())
        || visibleField(file, variable, offset) != null;
  }

  private Optional<String> resolveOwnerType(String spelling, SourceFile sourceFile, int offset) {
    String normalized = stripBalancedGenericArguments(spelling == null ? "" : spelling).trim();
    if (normalized.isBlank()) {
      return Optional.empty();
    }
    if (!normalized.contains(".")) {
      List<String> lexical = lexicalTypeCandidates(normalized, sourceFile, offset);
      if (!lexical.isEmpty()) {
        return lexical.size() == 1 ? Optional.of(lexical.get(0)) : Optional.empty();
      }
    }
    List<String> imported = importedTypes(normalized, sourceFile);
    if (imported.size() == 1) {
      return Optional.of(normalizeDeclaredTypeName(imported.get(0), sourceFile));
    }
    List<String> declared = normalized.contains(".")
        ? declaredTypeCandidates(normalized, sourceFile, offset)
        : samePackageTypeCandidates(normalized, sourceFile);
    if (declared.size() == 1) {
      return Optional.of(declared.get(0));
    }
    if (!declared.isEmpty()) {
      return Optional.empty();
    }
    List<String> onDemand = new ArrayList<>(onDemandImportedTypes(normalized, sourceFile));
    onDemand.addAll(onDemandImportedJdkTypes(normalized, sourceFile));
    onDemand = onDemand.stream().distinct().toList();
    if (onDemand.size() == 1) {
      return Optional.of(normalizeDeclaredTypeName(onDemand.get(0), sourceFile));
    }
    if (onDemand.size() > 1) {
      return Optional.empty();
    }
    if (normalized.equals("java.lang.Class") || normalized.equals("java.lang.reflect.Proxy")) {
      return Optional.of(normalized);
    }
    if (normalized.equals("java.lang.ClassLoader")
        || normalized.equals("java.lang.reflect.Method")
        || normalized.equals("java.lang.reflect.Constructor")
        || normalized.equals("java.lang.reflect.InvocationHandler")) {
      return Optional.of(normalized);
    }
    if (normalized.equals("Class")) {
      return Optional.of("java.lang.Class");
    }
    if (normalized.equals("Proxy") && hasOnDemandImport(sourceFile, "java.lang.reflect")) {
      return Optional.of("java.lang.reflect.Proxy");
    }
    if (normalized.equals("Method")) {
      return Optional.of("java.lang.reflect.Method");
    }
    if (normalized.equals("Constructor")) {
      return Optional.of("java.lang.reflect.Constructor");
    }
    if (normalized.equals("ClassLoader")) {
      return Optional.of("java.lang.ClassLoader");
    }
    if (normalized.equals("InvocationHandler")) {
      return Optional.of("java.lang.reflect.InvocationHandler");
    }
    return Optional.empty();
  }

  private boolean isJdkDeclaredType(
      SourceFile file, String spelling, int offset, String expectedSimpleName) {
    return isJdkDeclaredType(file, spelling, offset, expectedSimpleName, new HashSet<>());
  }

  private boolean isJdkDeclaredType(
      SourceFile file,
      String spelling,
      int offset,
      String expectedSimpleName,
      Set<String> typeVariables) {
    String normalized = stripBalancedGenericArguments(spelling == null ? "" : spelling).trim();
    Optional<TypeVariableBounds> bounds = typeVariableBounds(file, normalized, offset);
    if (bounds.isPresent()) {
      if (!typeVariables.add(normalized)) {
        return false;
      }
      return bounds.get().bounds().stream()
          .anyMatch(bound -> isJdkDeclaredType(
              file,
              bound,
              bounds.get().declarationOffset(),
              expectedSimpleName,
              new HashSet<>(typeVariables)));
    }
    String expectedType = resolveJdkTypeName(expectedSimpleName);
    return resolveOwnerType(normalized, file, offset)
        .map(resolved -> isJdkTypeOrSubclass(resolved, expectedType, new HashSet<>()))
        .orElse(false);
  }

  private Optional<TypeVariableBounds> typeVariableBounds(
      SourceFile file, String variable, int offset) {
    if (!variable.matches("[A-Za-z_$][\\w$]*")) {
      return Optional.empty();
    }
    MethodInfo method = methodContaining(file, offset);
    if (!method.name().equals("<unknown>")) {
      Optional<List<String>> methodBounds = typeVariableBoundsInMethod(method, variable);
      if (methodBounds.isPresent()) {
        return methodBounds.map(bounds -> new TypeVariableBounds(bounds, method.start()));
      }
    }
    List<TypeInfo> containingTypes = typeDeclarations(file).stream()
        .filter(type -> type.start() <= offset && type.end() >= offset)
        .sorted(Comparator.comparingInt(TypeInfo::start).reversed())
        .toList();
    for (TypeInfo type : containingTypes) {
      Optional<List<String>> typeBounds = typeVariableBoundsInType(file, type, variable);
      if (typeBounds.isPresent()) {
        return typeBounds.map(bounds -> new TypeVariableBounds(bounds, type.start()));
      }
    }
    return Optional.empty();
  }

  private static Optional<List<String>> typeVariableBoundsInMethod(
      MethodInfo method, String variable) {
    String header = method.annotationBlock().replaceFirst("^[;{}\\s]+", "");
    Matcher opening = Pattern.compile("<(?![=])").matcher(header);
    while (opening.find()) {
      String prefix = header.substring(0, opening.start()).trim();
      if (!isMethodTypeParameterPrefix(prefix)) {
        continue;
      }
      int closing = matchingGenericDelimiter(header, opening.start());
      if (closing < 0) {
        continue;
      }
      return typeVariableBoundsInParameters(header.substring(opening.end(), closing), variable);
    }
    return Optional.empty();
  }

  private static boolean isMethodTypeParameterPrefix(String prefix) {
    Set<String> modifiers = Set.of(
        "public", "protected", "private", "static", "final", "synchronized",
        "abstract", "native", "default", "strictfp");
    int index = 0;
    while (index < prefix.length()) {
      while (index < prefix.length() && Character.isWhitespace(prefix.charAt(index))) {
        index++;
      }
      if (index >= prefix.length()) {
        return true;
      }
      if (prefix.charAt(index) == '@') {
        index++;
        while (index < prefix.length()
            && (Character.isJavaIdentifierPart(prefix.charAt(index))
                || prefix.charAt(index) == '.')) {
          index++;
        }
        while (index < prefix.length() && Character.isWhitespace(prefix.charAt(index))) {
          index++;
        }
        if (index < prefix.length() && prefix.charAt(index) == '(') {
          int closing = matchingDelimiter(prefix, index, '(', ')');
          if (closing < 0) {
            return false;
          }
          index = closing + 1;
        }
        continue;
      }
      int tokenEnd = index;
      while (tokenEnd < prefix.length() && Character.isJavaIdentifierPart(prefix.charAt(tokenEnd))) {
        tokenEnd++;
      }
      if (!modifiers.contains(prefix.substring(index, tokenEnd))) {
        return false;
      }
      index = tokenEnd;
    }
    return true;
  }

  private static Optional<List<String>> typeVariableBoundsInType(
      SourceFile file, TypeInfo type, String variable) {
    String source = maskStringLiterals(file.structuralSource());
    int openingBrace = typeBodyOpening(source, type.start());
    if (openingBrace < 0) {
      return Optional.empty();
    }
    String header = source.substring(type.start(), openingBrace);
    Matcher declaration = Pattern.compile(
            "\\b" + Pattern.quote(type.kind()) + "\\s+" + Pattern.quote(type.name())
                + "\\s*<")
        .matcher(header);
    if (!declaration.find()) {
      return Optional.empty();
    }
    int opening = header.indexOf('<', declaration.end() - 1);
    int closing = matchingGenericDelimiter(header, opening);
    if (closing < 0) {
      return Optional.empty();
    }
    return typeVariableBoundsInParameters(header.substring(opening + 1, closing), variable);
  }

  private static Optional<List<String>> typeVariableBoundsInParameters(
      String parameters, String variable) {
    for (String parameter : splitTopLevelArguments(parameters)) {
      String normalizedParameter = stripLeadingTypeParameterAnnotations(parameter);
      Matcher declaration = Pattern.compile(
              "^\\s*" + Pattern.quote(variable) + "(?:\\s+extends\\s+(.+))?\\s*$")
          .matcher(normalizedParameter);
      if (!declaration.matches()) {
        continue;
      }
      if (declaration.group(1) == null) {
        return Optional.of(List.of());
      }
      return Optional.of(splitTypeIntersectionBounds(declaration.group(1)).stream()
          .map(SourceMetadataScanner::stripLeadingTypeParameterAnnotations)
          .filter(bound -> !bound.isBlank())
          .toList());
    }
    return Optional.empty();
  }

  private static String stripLeadingTypeParameterAnnotations(String parameter) {
    int index = 0;
    while (index < parameter.length()) {
      while (index < parameter.length() && Character.isWhitespace(parameter.charAt(index))) {
        index++;
      }
      if (index >= parameter.length() || parameter.charAt(index) != '@') {
        break;
      }
      index++;
      while (index < parameter.length()
          && (Character.isJavaIdentifierPart(parameter.charAt(index))
              || parameter.charAt(index) == '.')) {
        index++;
      }
      while (index < parameter.length() && Character.isWhitespace(parameter.charAt(index))) {
        index++;
      }
      if (index < parameter.length() && parameter.charAt(index) == '(') {
        int closing = matchingDelimiter(parameter, index, '(', ')');
        if (closing < 0) {
          return parameter;
        }
        index = closing + 1;
      }
    }
    return parameter.substring(index).trim();
  }

  private static List<String> splitTypeIntersectionBounds(String expression) {
    return splitBalanced(expression, '&', false);
  }

  private boolean isJdkTypeOrSubclass(
      String candidate, String expectedType, Set<String> visited) {
    if (candidate.equals(expectedType)) {
      return true;
    }
    if (!visited.add(candidate) || !expectedType.equals("java.lang.ClassLoader")) {
      return false;
    }
    TypeReference reference = findTypeReference(candidate);
    if (reference != null
        && resolveParentTypes(reference).stream()
            .anyMatch(parent -> isJdkTypeOrSubclass(parent, expectedType, visited))) {
      return true;
    }
    return Optional.ofNullable(KNOWN_CLASSLOADER_SUPERTYPES.get(candidate))
        .map(parent -> isJdkTypeOrSubclass(parent, expectedType, visited))
        .orElse(false);
  }

  private static String resolveJdkTypeName(String simpleName) {
    return switch (simpleName) {
      case "Class" -> "java.lang.Class";
      case "ClassLoader" -> "java.lang.ClassLoader";
      case "Method" -> "java.lang.reflect.Method";
      case "Constructor" -> "java.lang.reflect.Constructor";
      case "InvocationHandler" -> "java.lang.reflect.InvocationHandler";
      case "Proxy" -> "java.lang.reflect.Proxy";
      default -> simpleName;
    };
  }

  private static boolean hasOnDemandImport(SourceFile sourceFile, String packageName) {
    return Pattern.compile("(?m)^\\s*import\\s+" + Pattern.quote(packageName) + "\\.\\*\\s*;")
        .matcher(maskStringLiterals(sourceFile.structuralSource()))
        .find();
  }

  /** Reports chained Method.invoke only when the lookup receiver is a JDK Class API. */
  private void addChainedMethodInvokeSites(
      List<DynamicSite> result,
      String dynamicSource,
      String source,
      List<ChainedReceiver> receivers) {
    addChainedInvocationSites(
        result, dynamicSource, source, receivers,
        List.of("getDeclaredMethod", "getMethod"), "invoke", "Method.invoke");
  }

  /** Reports chained Constructor.newInstance only when the lookup receiver is a JDK Class API. */
  private void addChainedConstructorSites(
      List<DynamicSite> result,
      String dynamicSource,
      String source,
      List<ChainedReceiver> receivers) {
    addChainedInvocationSites(
        result, dynamicSource, source, receivers,
        List.of("getDeclaredConstructor", "getConstructor"), "newInstance",
        "Constructor.newInstance");
  }

  private void addChainedInvocationSites(
      List<DynamicSite> result,
      String dynamicSource,
      String source,
      List<ChainedReceiver> receivers,
      List<String> lookupMethods,
      String terminalMethod,
      String symbol) {
    for (ChainedReceiver receiver : receivers) {
      for (String lookupMethod : lookupMethods) {
        ChainedLookup lookup = chainedLookup(dynamicSource, receiver, lookupMethod);
        if (lookup == null) {
          continue;
        }
        int terminalStart = chainedTerminalStart(dynamicSource, lookup, terminalMethod);
        if (terminalStart >= 0) {
          addSite(result, source, terminalStart, symbol);
        }
      }
    }
  }

  private boolean isJdkClassForNameExpression(SourceFile file, String expression, int offset) {
    Matcher owner = CLASS_FOR_NAME_PREFIX.matcher(expression);
    if (owner.find()) {
      int closing = matchingDelimiter(expression, owner.end() - 1, '(', ')');
      if (closing == expression.length() - 1
          && isJdkApiOwner(file, owner.group(1), offset, "java.lang.Class")) {
        return true;
      }
    }
    Matcher thisQualified = THIS_CLASS_FOR_NAME.matcher(expression);
    if (thisQualified.find() && thisQualified.start() == 0) {
      int closing = matchingDelimiter(expression, thisQualified.end() - 1, '(', ')');
      if (closing == expression.length() - 1
          && hasVisibleField(
              file, "Class", thisQualified.group(1), offset + thisQualified.start(1))) {
        return true;
      }
    }
    Matcher qualifiedThis = QUALIFIED_THIS_CLASS_FOR_NAME.matcher(expression);
    if (!qualifiedThis.find() || qualifiedThis.start() != 0) {
      return false;
    }
    int closing = matchingDelimiter(expression, qualifiedThis.end() - 1, '(', ')');
    if (closing != expression.length() - 1) {
      return false;
    }
    String selectedOwner = qualifiedThisOwner(file, qualifiedThis.group(1), offset + qualifiedThis.start(1));
    return selectedOwner != null
        && hasVisibleField(
            file, "Class", qualifiedThis.group(2), offset + qualifiedThis.start(2), selectedOwner);
  }

  private boolean isJdkClassFieldReceiver(SourceFile file, String receiver, int offset) {
    Matcher thisField = THIS_FIELD_RECEIVER.matcher(receiver);
    if (thisField.matches()) {
      return hasVisibleField(file, "Class", thisField.group(1), offset + thisField.start(1));
    }
    Matcher qualifiedThis = QUALIFIED_THIS_FIELD_RECEIVER.matcher(receiver);
    if (!qualifiedThis.matches()) {
      return false;
    }
    String selectedOwner = qualifiedThisOwner(file, qualifiedThis.group(1), offset + qualifiedThis.start(1));
    return selectedOwner != null
        && hasVisibleField(
            file, "Class", qualifiedThis.group(2), offset + qualifiedThis.start(2), selectedOwner);
  }

  private void addReceiverCallSites(
      List<DynamicSite> result,
      String dynamicSource,
      String source,
      SourceFile file,
      Pattern pattern,
      String receiverType) {
    Matcher matcher = pattern.matcher(dynamicSource);
    while (matcher.find()) {
      if (!isBareReceiver(dynamicSource, matcher.start(1))) {
        continue;
      }
      String receiver = matcher.group(1);
      boolean apiReceiver = receiver.equals(receiverType)
          && isJdkApiOwner(file, receiver, matcher.start(1), receiverType.equals("Class")
              ? "java.lang.Class" : "java.lang.ClassLoader");
      if (apiReceiver || isDeclaredVariableBefore(file, receiverType, receiver, matcher.start())) {
        addSite(result, source, matcher.start(), matcher.group(2).equals("loadClass")
            ? "ClassLoader.loadClass" : matcher.group(2));
      }
    }
  }

  private void addChainedLookupSites(
      List<DynamicSite> result,
      String dynamicSource,
      String source,
      List<ChainedReceiver> receivers,
      String... lookupMethods) {
    for (ChainedReceiver receiver : receivers) {
      // Simple Class receivers are already emitted by addReceiverCallSites. Keep
      // them in the shared receiver list for terminal invoke/newInstance chains,
      // but do not emit their lookup a second time at a different offset.
      if (receiver.kind() == ChainedReceiverKind.SIMPLE
          || receiver.kind() == ChainedReceiverKind.THIS_FIELD
          || receiver.kind() == ChainedReceiverKind.QUALIFIED_THIS_FIELD) {
        continue;
      }
      for (String lookupMethod : lookupMethods) {
        ChainedLookup lookup = chainedLookup(dynamicSource, receiver, lookupMethod);
        if (lookup != null) {
          addSite(result, source, lookup.methodStart(), lookupMethod);
        }
      }
    }
  }

  private static List<ChainedReceiver> chainedReceivers(String source) {
    List<ChainedReceiver> result = new ArrayList<>();
    addChainedForNameReceivers(
        result, source, CHAINED_CLASS_FOR_NAME_RECEIVER, ChainedReceiverKind.CLASS_FOR_NAME);
    addChainedForNameReceivers(
        result, source, THIS_CLASS_FOR_NAME, ChainedReceiverKind.THIS_CLASS_FOR_NAME);
    addChainedForNameReceivers(
        result, source, QUALIFIED_THIS_CLASS_FOR_NAME,
        ChainedReceiverKind.QUALIFIED_THIS_CLASS_FOR_NAME);
    addChainedFieldReceivers(
        result, source, CHAINED_THIS_FIELD_RECEIVER, ChainedReceiverKind.THIS_FIELD);
    addChainedFieldReceivers(
        result, source, CHAINED_QUALIFIED_THIS_FIELD_RECEIVER,
        ChainedReceiverKind.QUALIFIED_THIS_FIELD);
    Matcher getClass = CHAINED_GET_CLASS_RECEIVER.matcher(source);
    while (getClass.find()) {
      result.add(new ChainedReceiver(
          getClass.start(), getClass.end(), getClass.group(), ChainedReceiverKind.GET_CLASS));
    }
    Matcher classLiteral = CHAINED_CLASS_LITERAL_RECEIVER.matcher(source);
    while (classLiteral.find()) {
      result.add(new ChainedReceiver(
          classLiteral.start(), classLiteral.end(), classLiteral.group(),
          ChainedReceiverKind.CLASS_LITERAL));
    }
    Matcher simple = CHAINED_SIMPLE_RECEIVER.matcher(source);
    while (simple.find()) {
      result.add(new ChainedReceiver(
          simple.start(1), simple.end(1), simple.group(1), ChainedReceiverKind.SIMPLE));
    }
    return result;
  }

  private static void addChainedForNameReceivers(
      List<ChainedReceiver> result,
      String source,
      Pattern pattern,
      ChainedReceiverKind kind) {
    Matcher matcher = pattern.matcher(source);
    while (matcher.find()) {
      int opening = matcher.end() - 1;
      int closing = matchingDelimiter(source, opening, '(', ')');
      if (closing >= 0) {
        result.add(new ChainedReceiver(
            matcher.start(), closing + 1,
            source.substring(matcher.start(), closing + 1), kind));
      }
    }
  }

  private static void addChainedFieldReceivers(
      List<ChainedReceiver> result,
      String source,
      Pattern pattern,
      ChainedReceiverKind kind) {
    Matcher matcher = pattern.matcher(source);
    while (matcher.find()) {
      result.add(new ChainedReceiver(
          matcher.start(), matcher.end(), source.substring(matcher.start(), matcher.end()), kind));
    }
  }

  private boolean isSupportedChainedReceiver(
      SourceFile file, String source, ChainedReceiver receiver) {
    return switch (receiver.kind()) {
      case CLASS_FOR_NAME -> isBareReceiver(source, receiver.start())
          && startsWithJdkClassForName(file, source, receiver.start());
      case THIS_CLASS_FOR_NAME -> isThisClassForNameReceiver(file, source, receiver);
      case QUALIFIED_THIS_CLASS_FOR_NAME -> isQualifiedThisClassForNameReceiver(
          file, source, receiver);
      case THIS_FIELD -> isBareReceiver(source, receiver.start())
          && isJdkClassFieldReceiver(file, receiver.expression(), receiver.start());
      case QUALIFIED_THIS_FIELD -> isJdkClassFieldReceiver(
          file, receiver.expression(), receiver.start());
      case GET_CLASS, CLASS_LITERAL -> true;
      case SIMPLE -> isBareReceiver(source, receiver.start())
          && isDeclaredVariableBefore(file, "Class", receiver.expression(), receiver.start());
    };
  }

  private boolean isThisClassForNameReceiver(
      SourceFile file, String source, ChainedReceiver receiver) {
    Matcher matcher = THIS_CLASS_FOR_NAME.matcher(source.substring(receiver.start()));
    return isBareReceiver(source, receiver.start())
        && matcher.find()
        && matcher.start() == 0
        && hasVisibleField(
            file, "Class", matcher.group(1), receiver.start() + matcher.start(1));
  }

  private boolean isQualifiedThisClassForNameReceiver(
      SourceFile file, String source, ChainedReceiver receiver) {
    Matcher matcher = QUALIFIED_THIS_CLASS_FOR_NAME.matcher(source.substring(receiver.start()));
    if (!matcher.find() || matcher.start() != 0) {
      return false;
    }
    String selectedOwner = qualifiedThisOwner(
        file, matcher.group(1), receiver.start() + matcher.start(1));
    return selectedOwner != null
        && hasVisibleField(
            file, "Class", matcher.group(2), receiver.start() + matcher.start(2), selectedOwner);
  }

  private static ChainedLookup chainedLookup(
      String source, ChainedReceiver receiver, String lookupMethod) {
    int dot = skipWhitespace(source, receiver.end());
    if (dot >= source.length() || source.charAt(dot) != '.') {
      return null;
    }
    int methodStart = skipWhitespace(source, dot + 1);
    if (!tokenAt(source, methodStart, lookupMethod)) {
      return null;
    }
    int opening = skipWhitespace(source, methodStart + lookupMethod.length());
    if (opening >= source.length() || source.charAt(opening) != '(') {
      return null;
    }
    int closing = matchingDelimiter(source, opening, '(', ')');
    return closing < 0 ? null : new ChainedLookup(methodStart, closing);
  }

  private static int chainedTerminalStart(
      String source, ChainedLookup lookup, String terminalMethod) {
    int dot = skipWhitespace(source, lookup.closing() + 1);
    if (dot >= source.length() || source.charAt(dot) != '.') {
      return -1;
    }
    int terminalStart = skipWhitespace(source, dot + 1);
    if (!tokenAt(source, terminalStart, terminalMethod)) {
      return -1;
    }
    int opening = skipWhitespace(source, terminalStart + terminalMethod.length());
    return opening < source.length() && source.charAt(opening) == '(' ? terminalStart : -1;
  }

  private static boolean tokenAt(String source, int start, String token) {
    int end = start + token.length();
    return start >= 0
        && end <= source.length()
        && source.startsWith(token, start)
        && (end == source.length() || !Character.isJavaIdentifierPart(source.charAt(end)));
  }

  private void addThisReceiverSites(
      List<DynamicSite> result, String dynamicSource, String source, SourceFile file) {
    Matcher matcher = THIS_RECEIVER_CALL.matcher(dynamicSource);
    while (matcher.find()) {
      if (isBareReceiver(dynamicSource, matcher.start())) {
        addThisReceiverSite(
            result, source, file, matcher.group(1), matcher.group(2),
            matcher.start(1), null);
      }
    }
    Matcher qualified = QUALIFIED_THIS_RECEIVER_CALL.matcher(dynamicSource);
    while (qualified.find()) {
      String owner = qualifiedThisOwner(file, qualified.group(1), qualified.start(1));
      if (owner != null) {
        addThisReceiverSite(
            result, source, file, qualified.group(2), qualified.group(3),
            qualified.start(2), owner);
      }
    }
  }

  private void addThisReceiverSite(
      List<DynamicSite> result,
      String source,
      SourceFile file,
      String field,
      String method,
      int fieldOffset,
      String selectedOwner) {
    String type = switch (method) {
      case "loadClass" -> "ClassLoader";
      case "getDeclaredMethod", "getMethod", "getDeclaredConstructor", "getConstructor" -> "Class";
      case "invoke" -> "Method";
      case "newInstance" -> null;
      default -> null;
    };
    boolean matches = type != null && hasVisibleField(file, type, field, fieldOffset, selectedOwner);
    if (method.equals("newInstance")) {
      boolean constructorField = hasVisibleField(
          file, "Constructor", field, fieldOffset, selectedOwner);
      boolean classField = hasVisibleField(file, "Class", field, fieldOffset, selectedOwner);
      matches = constructorField || classField;
      type = constructorField ? "Constructor" : classField ? "Class" : null;
    }
    if (matches) {
      addSite(result, source, fieldOffset, dynamicSymbol(method, type));
    }
  }

  private static String dynamicSymbol(String method, String type) {
    if (method.equals("loadClass")) {
      return "ClassLoader.loadClass";
    }
    if (method.equals("invoke")) {
      return "Method.invoke";
    }
    if (method.equals("newInstance")) {
      return "Constructor".equals(type) ? "Constructor.newInstance" : "Class.newInstance";
    }
    return method;
  }

  private void addInlineProxyInvocationSites(
      List<DynamicSite> result, String dynamicSource, String source, SourceFile file) {
    for (int proxyOffset : proxyCallOffsets(file, dynamicSource)) {
      int opening = dynamicSource.indexOf('(', proxyOffset);
      if (opening < 0) {
        continue;
      }
      int closing = matchingDelimiter(dynamicSource, opening, '(', ')');
      if (closing < 0) {
        continue;
      }
      String arguments = dynamicSource.substring(opening + 1, closing);
      List<String> callArguments = splitTopLevelArguments(arguments);
      if (callArguments.size() < 3) {
        continue;
      }
      String handler = callArguments.get(2);
      Matcher lambda = Pattern.compile("\\(([^)]*)\\)\\s*->").matcher(handler);
      if (!lambda.find()) {
        continue;
      }
      List<String> parameters = splitTopLevelArguments(lambda.group(1));
      if (parameters.size() != 3) {
        continue;
      }
      String methodParameter = lambdaParameterName(parameters.get(1));
      if (methodParameter.isBlank()) {
        continue;
      }
      int bodyStart = lambda.end();
      while (bodyStart < handler.length() && Character.isWhitespace(handler.charAt(bodyStart))) {
        bodyStart++;
      }
      int bodyEnd = handler.length();
      if (bodyStart < handler.length() && handler.charAt(bodyStart) == '{') {
        int closingBody = matchingDelimiter(handler, bodyStart, '{', '}');
        if (closingBody < 0) {
          continue;
        }
        bodyEnd = closingBody + 1;
      }
      Matcher invoke = Pattern.compile(
              "\\b" + Pattern.quote(methodParameter) + "\\s*\\.\\s*invoke\\s*\\(")
          .matcher(handler);
      invoke.region(lambda.end(), bodyEnd);
      int handlerOffset = arguments.indexOf(handler);
      while (invoke.find()) {
        addSite(result, source, opening + 1 + handlerOffset + invoke.start(), "Method.invoke");
      }
    }
  }

  private static String lambdaParameterName(String parameter) {
    Matcher name = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*$").matcher(parameter.trim());
    return name.find() ? name.group(1) : "";
  }

  private void addReceiverSites(
      List<DynamicSite> result,
      String dynamicSource,
      String source,
      SourceFile file,
      String method,
      String receiverType,
      String symbol) {
    Matcher matcher = Pattern.compile(
        "\\b([A-Za-z_$][\\w$]*)\\s*\\.\\s*" + method + "\\s*\\(").matcher(dynamicSource);
    while (matcher.find()) {
      if (isBareReceiver(dynamicSource, matcher.start(1))
          && isDeclaredVariableBefore(file, receiverType, matcher.group(1), matcher.start())) {
        addSite(result, source, matcher.start(), symbol);
      }
    }
  }

  private boolean isInvocationHandlerParameterBefore(SourceFile file, String variable, int offset) {
    String source = file.structuralSource();
    MethodInfo owner = methodContaining(file, offset);
    Pattern lambda = Pattern.compile(
        "\\b((?:InvocationHandler|java\\.lang\\.reflect\\.InvocationHandler))\\s+"
            + "[A-Za-z_$][\\w$]*\\s*=\\s*\\(([^)]*)\\)\\s*->");
    Matcher matcher = lambda.matcher(source);
    matcher.region(owner.name().equals("<unknown>") ? 0 : owner.start(),
        Math.min(offset, source.length()));
    while (matcher.find()) {
      if (!isJdkDeclaredType(file, matcher.group(1), matcher.start(1), "InvocationHandler")) {
        continue;
      }
      List<String> parameters = splitTopLevelArguments(matcher.group(2));
      if (parameters.size() >= 2
          && lambdaParameterName(parameters.get(1)).equals(variable)
          && lambdaBodyContains(source, matcher.end(), offset)) {
        return true;
      }
    }
    return false;
  }

  private static boolean lambdaBodyContains(String source, int lambdaEnd, int offset) {
    int bodyStart = lambdaEnd;
    while (bodyStart < source.length() && Character.isWhitespace(source.charAt(bodyStart))) {
      bodyStart++;
    }
    if (bodyStart >= source.length()) {
      return false;
    }
    if (source.charAt(bodyStart) == '{') {
      return matchingDelimiter(source, bodyStart, '{', '}') >= offset;
    }
    int boundary = source.indexOf(';', bodyStart);
    return offset >= bodyStart && (boundary < 0 || offset < boundary);
  }

  private static void addSite(List<DynamicSite> result, String source, int offset, String symbol) {
    if (result.stream().anyMatch(site -> site.offset() == offset && site.symbol().equals(symbol))) {
      return;
    }
    result.add(new DynamicSite(offset, symbol, dynamicTarget(source, offset, symbol)));
  }

  private static boolean isBareReceiver(String source, int receiverStart) {
    int previous = receiverStart - 1;
    while (previous >= 0 && Character.isWhitespace(source.charAt(previous))) {
      previous--;
    }
    return previous < 0 || source.charAt(previous) != '.';
  }

  private boolean isDeclaredVariableBefore(
      SourceFile file, String type, String variable, int offset) {
    MethodInfo owner = methodContaining(file, offset);
    if (owner.name().equals("<unknown>")) {
      return false;
    }
    if (hasParameter(owner, variable)) {
      return parameterMatchesType(file, owner, type, variable, offset);
    }
    Optional<LocalDeclaration> local = activeLocalDeclaration(file, owner, variable, offset);
    if (local.isPresent()) {
      LocalDeclaration declaration = local.get();
      if (declaration.type().equals("var")) {
        return Optional.ofNullable(inferredReflectionType(
                file, declaration.initializer(), declaration.initializerOffset()))
            .map(typeName -> typeName.equals(type))
            .orElse(false);
      }
      return isJdkDeclaredType(file, declaration.type(), declaration.start(), type);
    }
    return hasVisibleField(file, type, variable, offset);
  }

  private String inferredReflectionType(SourceFile file, String initializer, int offset) {
    String expression = stripOuterParentheses(initializer.trim());
    if (isRecognizedClassLookup(file, expression, offset)
        && Pattern.compile("(?:getDeclaredMethod|getMethod)\\s*\\([^;{}]*\\)$")
            .matcher(expression).find()) {
      return "Method";
    }
    if (isRecognizedClassLookup(file, expression, offset)
        && Pattern.compile("(?:getDeclaredConstructor|getConstructor)\\s*\\([^;{}]*\\)$")
            .matcher(expression).find()) {
      return "Constructor";
    }
    if (isStandaloneClassForNameExpression(file, expression, offset)) {
      return "Class";
    }
    if (expression.matches("^[A-Za-z_$][\\w$.]*\\s*\\.\\s*class$")) {
      return "Class";
    }
    if (isClassLoaderResultExpression(file, expression, offset)) {
      return "ClassLoader";
    }
    if (Pattern.compile("^(?:getClass\\s*\\(\\s*\\)|[A-Za-z_$][\\w$]*\\s*\\.\\s*"
            + "getClass\\s*\\(\\s*\\))$")
        .matcher(expression).find()) {
      return "Class";
    }
    if (isStandaloneLoadClassExpression(file, expression, offset)) {
      return "Class";
    }
    return null;
  }

  private boolean isStandaloneClassForNameExpression(
      SourceFile file, String expression, int offset) {
    return isJdkClassForNameExpression(file, expression, offset);
  }

  private boolean isClassLoaderResultExpression(
      SourceFile file, String expression, int offset) {
    Matcher loader = Pattern.compile("^(.+)\\s*\\.\\s*getClassLoader\\s*\\(\\s*\\)$")
        .matcher(expression);
    if (!loader.find()) {
      return false;
    }
    String receiver = stripOuterParentheses(loader.group(1).trim());
    return isStandaloneClassForNameExpression(file, receiver, offset)
        || isStandaloneLoadClassExpression(file, receiver, offset)
        || receiver.matches("^[A-Za-z_$][\\w$.]*\\s*\\.\\s*class$")
        || isDeclaredVariableBefore(file, "Class", receiver, offset)
        || receiver.matches("^(?:getClass\\s*\\(\\s*\\)|[A-Za-z_$][\\w$]*\\s*\\.\\s*"
            + "getClass\\s*\\(\\s*\\))$");
  }

  private static String stripOuterParentheses(String expression) {
    String result = expression;
    while (result.startsWith("(")
        && matchingDelimiter(result, 0, '(', ')') == result.length() - 1) {
      result = result.substring(1, result.length() - 1).trim();
    }
    return result;
  }

  private boolean isRecognizedClassLoaderLookup(SourceFile file, String initializer, int offset) {
    Matcher lookup = Pattern.compile(
            "^(ClassLoader|java\\.lang\\.ClassLoader|[A-Za-z_$][\\w$]*)"
                + "\\s*\\.\\s*loadClass\\s*\\(")
        .matcher(initializer);
    if (!lookup.find()) {
      return false;
    }
    String receiver = lookup.group(1);
    if (receiver.equals("ClassLoader") || receiver.equals("java.lang.ClassLoader")) {
      return isJdkApiOwner(file, receiver, offset + lookup.start(1), "java.lang.ClassLoader");
    }
    return isDeclaredVariableBefore(file, "ClassLoader", receiver, offset + lookup.start(1));
  }

  private boolean isStandaloneLoadClassExpression(SourceFile file, String expression, int offset) {
    Matcher lookup = Pattern.compile(
            "^(ClassLoader|java\\.lang\\.ClassLoader|[A-Za-z_$][\\w$]*)"
                + "\\s*\\.\\s*loadClass\\s*\\(")
        .matcher(expression);
    if (!lookup.find()) {
      return false;
    }
    int closing = matchingDelimiter(expression, lookup.end() - 1, '(', ')');
    return closing == expression.length() - 1
        && isRecognizedClassLoaderLookup(file, expression, offset);
  }

  private boolean isRecognizedClassLookup(SourceFile file, String initializer, int offset) {
    Matcher lookup = Pattern.compile(
            "^((?:[A-Za-z_$][\\w$]*|java\\.lang\\.Class)\\s*\\.\\s*forName\\s*\\([^;{}]*\\)"
                + "|(?:this\\s*\\.\\s*[A-Za-z_$][\\w$]*"
                + "|[A-Za-z_$][\\w$.]*\\s*\\.\\s*this\\s*\\.\\s*[A-Za-z_$][\\w$]*)"
                + "\\s*\\.\\s*forName\\s*\\([^;{}]*\\)"
                + "|(?:this\\s*\\.\\s*[A-Za-z_$][\\w$]*"
                + "|[A-Za-z_$][\\w$.]*\\s*\\.\\s*this\\s*\\.\\s*[A-Za-z_$][\\w$]*)"
                + "|getClass\\s*\\(\\s*\\)"
                + "|[A-Za-z_$][\\w$]*\\s*\\.\\s*getClass\\s*\\(\\s*\\)"
                + "|[A-Za-z_$][\\w$]*\\.class"
                + "|[A-Za-z_$][\\w$]*)\\s*\\.\\s*"
                + "(?:getDeclaredMethod|getMethod|getDeclaredConstructor|getConstructor)"
                + "\\s*\\([^;{}]*\\)$")
        .matcher(initializer);
    if (!lookup.find()) {
      return false;
    }
    String receiver = lookup.group(1).trim();
    String normalizedReceiver = receiver.replaceAll("\\s+", "");
    return normalizedReceiver.equals("getClass()")
        || normalizedReceiver.endsWith(".getClass()")
        || normalizedReceiver.endsWith(".class")
        || isJdkClassForNameExpression(file, receiver, offset + lookup.start(1))
        || isJdkClassFieldReceiver(file, receiver, offset + lookup.start(1))
        || isDeclaredVariableBefore(file, "Class", receiver, offset + lookup.start(1));
  }

  private static boolean hasParameter(MethodInfo owner, String variable) {
    return parameters(owner.parameters()).stream().anyMatch(parameter -> parameter.name().equals(variable));
  }

  private boolean parameterMatchesType(
      SourceFile file, MethodInfo owner, String type, String variable, int offset) {
    return parameters(owner.parameters()).stream()
        .filter(parameter -> parameter.name().equals(variable))
        .map(Parameter::type)
        .map(parameterType -> stripBalancedGenericArguments(parameterType).trim())
        .anyMatch(parameterType -> isJdkDeclaredType(file, parameterType, offset, type));
  }

  private Optional<LocalDeclaration> activeLocalDeclaration(
      SourceFile file, MethodInfo owner, String variable, int offset) {
    String source = maskStringLiterals(file.structuralSource());
    Pattern declaration = Pattern.compile(
        "\\b([A-Za-z_$][\\w$.]*(?:\\s*<[^;=(){}]+>)?(?:\\s*\\[\\])*)\\s+"
            + Pattern.quote(variable)
            + "\\s*(?:=\\s*([^;{}]+))?(?=\\s*[;,:)])");
    Matcher matcher = declaration.matcher(source);
    matcher.region(owner.start(), Math.min(offset, source.length()));
    LocalDeclaration selected = null;
    while (matcher.find()) {
      String declaredType = matcher.group(1).trim();
      if (isDeclarationKeyword(declaredType)) {
        continue;
      }
      if (declarationIsActive(source, matcher.start(1), offset, owner.start())) {
        selected = new LocalDeclaration(
            declaredType,
            matcher.group(2) == null ? "" : matcher.group(2).trim(),
            matcher.start(1),
            matcher.start(2));
      }
    }
    Pattern sharedDeclaration = Pattern.compile(
        "(?m)(?:^|[;{}:])\\s*"
            + "(?:(?:final|volatile|transient)\\s+"
            + "|@[A-Za-z_][\\w.]*(?:\\s*\\([^;]*\\))?\\s*)*"
            + "([A-Za-z_$][\\w$.]*(?:\\s*<[^;=(){}]+>)?(?:\\s*\\[\\])*)\\s+");
    Pattern basicForSharedDeclaration = Pattern.compile(
        "(?m)\\bfor\\s*\\(\\s*"
            + "(?:(?:final|volatile|transient)\\s+"
            + "|@[A-Za-z_][\\w.]*(?:\\s*\\([^;]*\\))?\\s*)*"
            + "([A-Za-z_$][\\w$.]*(?:\\s*<[^;=(){}]+>)?(?:\\s*\\[\\])*)\\s+");
    for (Pattern sharedPattern : List.of(sharedDeclaration, basicForSharedDeclaration)) {
      Matcher sharedMatcher = sharedPattern.matcher(source);
      sharedMatcher.region(owner.start(), Math.min(offset, source.length()));
      while (sharedMatcher.find()) {
        int declarationEnd = fieldDeclarationEnd(source, sharedMatcher.end(1));
        if (declarationEnd < 0) {
          continue;
        }
        String declarators = source.substring(sharedMatcher.end(1), declarationEnd);
        if (splitFieldDeclarators(declarators).size() < 2) {
          continue;
        }
        Optional<String> declaredType = declaredFieldType(
            sharedMatcher.group(1), declarators, variable);
        int variableOffset = declaratorVariableOffset(declarators, variable);
        if (declaredType.isEmpty() || variableOffset < 0) {
          continue;
        }
        int absoluteVariableOffset = sharedMatcher.end(1) + variableOffset;
        if (absoluteVariableOffset >= offset) {
          continue;
        }
        if (!isDeclarationKeyword(declaredType.get())
            && declarationIsActive(source, absoluteVariableOffset, offset, owner.start())) {
          selected = new LocalDeclaration(declaredType.get(), "", absoluteVariableOffset, -1);
        }
      }
    }
    return Optional.ofNullable(selected);
  }

  private boolean hasVisibleField(SourceFile file, String type, String variable, int siteOffset) {
    return hasVisibleField(file, type, variable, siteOffset, null);
  }

  private boolean hasVisibleField(
      SourceFile file, String type, String variable, int siteOffset, String selectedOwner) {
    FieldReference visibleField = visibleField(file, variable, siteOffset, selectedOwner);
    return visibleField != null
        && isJdkDeclaredType(
            visibleField.file(), visibleField.type(), visibleField.offset(), type);
  }

  private FieldReference visibleField(SourceFile file, String variable, int siteOffset) {
    return visibleField(file, variable, siteOffset, null);
  }

  private FieldReference visibleField(
      SourceFile file, String variable, int siteOffset, String selectedOwner) {
    Pattern fieldStart = Pattern.compile(
        "(?m)(?:^|[;{}])\\s*(?:@[A-Za-z_][\\w.]*(?:\\s*\\([^;]*\\))?\\s*)*"
            + "(?:(?:public|protected|private|static|final|transient|volatile|strictfp)\\s+)*"
            + "([A-Za-z_$][\\w$.]*(?:\\s*<[^;=(){}]+>)?(?:\\s*\\[\\])*)\\s+");
    String siteType = typeContaining(file, siteOffset);
    Map<String, Integer> visibleOwners = selectedOwner == null
        ? visibleFieldOwners(file, siteType)
        : selectedOwnerFieldOwners(selectedOwner);
    FieldReference selected = null;
    int selectedDistance = Integer.MAX_VALUE;
    for (SourceFile candidate : files()) {
      String source = maskStringLiterals(candidate.structuralSource());
      Matcher matcher = fieldStart.matcher(source);
      while (matcher.find()) {
        int declarationEnd = fieldDeclarationEnd(source, matcher.end(1));
        if (declarationEnd < 0
            || !isTypeMemberScope(candidate, source, declarationScopeIndex(source, matcher.start()))) {
          continue;
        }
        int fieldOffset = matcher.start(1);
        Optional<String> fieldType = declaredFieldType(
            matcher.group(1), source.substring(matcher.end(1), declarationEnd), variable);
        if (fieldType.isEmpty()) {
          continue;
        }
        String fieldOwner = typeContaining(candidate, fieldOffset);
        String declaration = source.substring(matcher.start(), declarationEnd + 1);
        if (!isAccessibleField(candidate, file, fieldOwner, siteType, declaration)) {
          continue;
        }
        if (!methodContaining(candidate, fieldOffset).name().equals("<unknown>")) {
          continue;
        }
        Integer distance = visibleOwners.get(fieldOwner);
        if (distance == null || distance >= selectedDistance) {
          continue;
        }
        selectedDistance = distance;
        selected = new FieldReference(fieldType.get(), candidate, fieldOffset);
      }
    }
    return selected;
  }

  private Map<String, Integer> selectedOwnerFieldOwners(String owner) {
    Map<String, Integer> result = new LinkedHashMap<>();
    addVisibleOwnerHierarchy(owner, 0, result, new HashSet<>());
    return result;
  }

  private static Optional<String> declaredFieldType(
      String sharedType, String declarators, String variable) {
    for (String raw : splitFieldDeclarators(declarators)) {
      String declaration = raw.trim();
      int equals = declaration.indexOf('=');
      if (equals >= 0) {
        declaration = declaration.substring(0, equals).trim();
      }
      Matcher name = Pattern.compile("^([A-Za-z_$][\\w$]*)").matcher(declaration);
      if (!name.find() || !name.group(1).equals(variable)) {
        continue;
      }
      String suffix = declaration.substring(name.end()).replaceAll("\\s+", "");
      if (!suffix.matches("(?:\\[\\])*$")) {
        return Optional.empty();
      }
      return Optional.of(sharedType.trim() + suffix);
    }
    return Optional.empty();
  }

  private static int declaratorVariableOffset(String declarators, String variable) {
    int search = 0;
    for (String raw : splitFieldDeclarators(declarators)) {
      int rawStart = declarators.indexOf(raw, search);
      if (rawStart < 0) {
        continue;
      }
      Matcher name = Pattern.compile("^([A-Za-z_$][\\w$]*)").matcher(raw);
      if (name.find() && name.group(1).equals(variable)) {
        return rawStart + name.start(1);
      }
      search = rawStart + raw.length();
    }
    return -1;
  }

  private static List<String> splitFieldDeclarators(String source) {
    return splitBalanced(source, ',', true);
  }

  private static boolean looksLikeGenericStart(String source, int opening, int currentDepth) {
    if (currentDepth > 0) {
      return true;
    }
    int previous = previousNonWhitespace(source, opening - 1);
    if (previous < 0) {
      return false;
    }
    if (source.charAt(previous) == '.'
        || source.charAt(previous) == ':' && previous > 0
            && source.charAt(previous - 1) == ':') {
      return true;
    }
    int wordStart = identifierStart(source, previous);
    if (wordStart < 0) {
      return false;
    }
    return "new".equals(source.substring(wordStart, previous + 1))
        || "instanceof".equals(source.substring(wordStart, previous + 1))
        || precededByQualifiedTypeKeyword(source, wordStart, "new")
        || precededByQualifiedTypeKeyword(source, wordStart, "instanceof")
        || castTypeArgument(source, opening, wordStart);
  }

  private static boolean precededByQualifiedTypeKeyword(
      String source, int typeStart, String keyword) {
    int position = previousNonWhitespace(source, typeStart - 1);
    while (position >= 0 && source.charAt(position) == '.') {
      position = previousNonWhitespace(source, position - 1);
      int qualifierStart = identifierStart(source, position);
      if (qualifierStart < 0) {
        return false;
      }
      position = previousNonWhitespace(source, qualifierStart - 1);
    }
    int keywordStart = identifierStart(source, position);
    return keywordStart >= 0
        && keyword.equals(source.substring(keywordStart, position + 1));
  }

  private static boolean castTypeArgument(String source, int opening, int typeStart) {
    int beforeType = previousNonWhitespace(source, typeStart - 1);
    if (beforeType < 0 || source.charAt(beforeType) != '(') {
      return false;
    }
    int closing = matchingGenericDelimiter(source, opening);
    return closing >= 0 && previousNonWhitespace(source, closing + 1) >= 0
        && source.charAt(previousNonWhitespace(source, closing + 1)) == ')';
  }

  private static int previousNonWhitespace(String source, int start) {
    int position = start;
    while (position >= 0 && Character.isWhitespace(source.charAt(position))) {
      position--;
    }
    return position;
  }

  private static int identifierStart(String source, int end) {
    if (end < 0 || !Character.isJavaIdentifierPart(source.charAt(end))) {
      return -1;
    }
    int position = end;
    while (position >= 0 && Character.isJavaIdentifierPart(source.charAt(position))) {
      position--;
    }
    return position + 1;
  }

  private static int fieldDeclarationEnd(String source, int start) {
    return fieldInitializerEnd(source, start);
  }

  private static boolean isAccessibleField(
      SourceFile declaringFile,
      SourceFile siteFile,
      String declaringType,
      String siteType,
      String declaration) {
    boolean lexicalAccess = siteType.equals(declaringType)
        || siteType.startsWith(declaringType + "$");
    if (Pattern.compile("\\bprivate\\b").matcher(declaration).find()) {
      return lexicalAccess;
    }
    if (isInterfaceType(declaringFile, declaringType)) {
      return true;
    }
    return declaringFile.packageName().equals(siteFile.packageName())
        || Pattern.compile("\\b(?:public|protected)\\b").matcher(declaration).find();
  }

  private static int declarationScopeIndex(String source, int matchStart) {
    return matchStart < source.length() && ";{}:".indexOf(source.charAt(matchStart)) >= 0
        ? matchStart + 1
        : matchStart;
  }

  /** Returns lexical and inherited owners visible from one dynamic call site. */
  private Map<String, Integer> visibleFieldOwners(SourceFile file, String siteType) {
    Map<String, Integer> result = new LinkedHashMap<>();
    String lexical = siteType;
    int distance = 0;
    Set<String> visited = new HashSet<>();
    while (!lexical.isBlank()) {
      distance = addVisibleOwnerHierarchy(lexical, distance, result, visited) + 1;
      int separator = lexical.lastIndexOf('$');
      if (separator < 0) {
        break;
      }
      lexical = lexical.substring(0, separator);
    }
    return result;
  }

  private int addVisibleOwnerHierarchy(
      String owner, int distance, Map<String, Integer> result, Set<String> visited) {
    if (owner.isBlank() || !visited.add(owner)) {
      return distance;
    }
    result.putIfAbsent(owner, distance);
    TypeReference reference = findTypeReference(owner);
    if (reference == null) {
      return distance;
    }
    int deepest = distance;
    for (String parent : resolveParentTypes(reference)) {
      deepest = Math.max(deepest, addVisibleOwnerHierarchy(parent, distance + 1, result, visited));
    }
    return deepest;
  }

  private List<String> resolveParentTypes(TypeReference reference) {
    String source = maskStringLiterals(reference.file().structuralSource());
    int opening = typeBodyOpening(source, reference.type().start());
    if (opening < 0) {
      return List.of();
    }
    String header = source.substring(reference.type().start(), opening);
    int extendsIndex = topLevelKeywordIndex(header, "extends");
    int implementsIndex = topLevelKeywordIndex(header, "implements");
    int permitsIndex = topLevelKeywordIndex(header, "permits");
    List<String> parentSpellings = new ArrayList<>();
    if (reference.type().kind().equals("interface")) {
      if (extendsIndex >= 0) {
        int end = permitsIndex >= 0 ? permitsIndex : header.length();
        parentSpellings.addAll(typeNames(header.substring(
            extendsIndex + "extends".length(), end)));
      }
    } else {
      if (extendsIndex >= 0) {
        int end = implementsIndex >= 0
            ? implementsIndex
            : permitsIndex >= 0 ? permitsIndex : header.length();
        parentSpellings.addAll(typeNames(header.substring(
            extendsIndex + "extends".length(), end)));
        if (!parentSpellings.isEmpty()) {
          parentSpellings = new ArrayList<>(parentSpellings.subList(0, 1));
        }
      }
      if (implementsIndex >= 0) {
        int end = permitsIndex >= 0 ? permitsIndex : header.length();
        parentSpellings.addAll(typeNames(header.substring(
            implementsIndex + "implements".length(), end)));
      }
    }
    return parentSpellings.stream()
        .map(parent -> resolveParentType(parent, reference))
        .filter(Objects::nonNull)
        .distinct()
        .toList();
  }

  private String resolveParentType(String spelling, TypeReference reference) {
    List<String> declared = declaredTypeCandidates(
        spelling, reference.file(), reference.type().start());
    if (declared.size() == 1 && declared.get(0).contains("$")) {
      return declared.get(0);
    }
    List<String> imported = importedTypes(spelling, reference.file());
    if (imported.size() > 1) {
      return null;
    }
    if (imported.size() == 1) {
      List<String> importedDeclared = declaredTypeCandidates(
          imported.get(0), reference.file(), reference.type().start());
      return importedDeclared.size() == 1
          ? importedDeclared.get(0)
          : normalizeDeclaredTypeName(imported.get(0), reference.file());
    }
    if (declared.size() == 1) {
      return declared.get(0);
    }
    List<String> onDemand = new ArrayList<>(onDemandImportedTypes(spelling, reference.file()));
    onDemand.addAll(onDemandImportedJdkTypes(spelling, reference.file()));
    onDemand = onDemand.stream().distinct().toList();
    if (onDemand.size() == 1) {
      return normalizeDeclaredTypeName(onDemand.get(0), reference.file());
    }
    if (onDemand.size() > 1) {
      return null;
    }
    return resolveContractType(spelling, reference.file()).orElse(null);
  }

  private TypeReference findTypeReference(String owner) {
    for (SourceFile candidate : files()) {
      for (TypeInfo type : typeDeclarations(candidate)) {
        if (typeContaining(candidate, type.start()).equals(owner)) {
          return new TypeReference(candidate, type);
        }
      }
    }
    return null;
  }

  /** Resolves top-level and nested source types to the binary names used by field lookup. */
  private List<String> declaredTypeCandidates(String spelling, SourceFile sourceFile, int siteOffset) {
    String normalized = spelling == null ? "" : spelling.trim();
    if (normalized.isBlank()) {
      return List.of();
    }
    if (!normalized.contains(".")) {
      List<String> local = localTypeCandidates(normalized, sourceFile, siteOffset);
      if (!local.isEmpty()) {
        return local;
      }
    }
    List<String> declared = files().stream()
        .flatMap(candidate -> typeDeclarations(candidate).stream()
            .map(type -> typeContaining(candidate, type.start())))
        .distinct()
        .toList();
    if (normalized.contains(".")) {
      List<String> direct = declared.stream()
          .filter(candidate -> candidate.equals(normalized)
              || candidate.replace('$', '.').equals(normalized))
          .toList();
      if (!direct.isEmpty()) {
        return direct;
      }
      String qualified = normalized;
      if (!qualified.startsWith(sourceFile.packageName() + ".")
          && !sourceFile.packageName().isBlank()) {
        qualified = sourceFile.packageName() + "." + qualified;
      }
      final String resolvedQualified = qualified;
      final String dotted = resolvedQualified.replace('$', '.');
      return declared.stream()
          .filter(candidate -> candidate.equals(resolvedQualified)
              || candidate.replace('$', '.').equals(dotted))
          .toList();
    }

    String siteType = typeContaining(sourceFile, siteOffset);
    List<String> enclosing = new ArrayList<>();
    String owner = siteType;
    while (!owner.isBlank()) {
      String nested = owner + "$" + normalized;
      if (declared.contains(nested)) {
        enclosing.add(nested);
      }
      int separator = owner.lastIndexOf('$');
      if (separator < 0) {
        break;
      }
      owner = owner.substring(0, separator);
    }
    if (!enclosing.isEmpty()) {
      return List.copyOf(new LinkedHashSet<>(enclosing));
    }
    return declared.stream()
        .filter(candidate -> candidate.startsWith(sourceFile.packageName() + ".")
            || sourceFile.packageName().isBlank() && !candidate.contains("."))
        .filter(candidate -> !candidate.contains("$"))
        .filter(candidate -> {
          int separator = Math.max(candidate.lastIndexOf('$'), candidate.lastIndexOf('.'));
          return candidate.substring(separator + 1).equals(normalized);
        })
        .toList();
  }

  /** Resolves member and inherited types in Java's lexical-owner precedence order. */
  private List<String> lexicalTypeCandidates(String simpleName, SourceFile sourceFile, int siteOffset) {
    for (String owner : lexicalOwnerNames(sourceFile, siteOffset)) {
      List<String> local = localTypeCandidatesForOwner(simpleName, owner, sourceFile, siteOffset);
      if (!local.isEmpty()) {
        return local;
      }
      List<String> direct = directMemberTypeCandidates(owner, simpleName);
      if (!direct.isEmpty()) {
        return direct;
      }
      List<String> inherited = inheritedMemberTypeCandidates(owner, simpleName, sourceFile);
      if (!inherited.isEmpty()) {
        return inherited;
      }
    }
    return List.of();
  }

  private List<String> lexicalOwnerNames(SourceFile sourceFile, int siteOffset) {
    List<TypeInfo> containing = typeDeclarations(sourceFile).stream()
        .filter(type -> type.start() <= siteOffset && type.end() >= siteOffset)
        .sorted(Comparator.comparingInt(TypeInfo::start))
        .toList();
    List<String> result = new ArrayList<>();
    for (int index = containing.size() - 1; index >= 0; index--) {
      String owner = typeContaining(sourceFile, containing.get(index).start());
      if (!owner.equals("unknown") && !result.contains(owner)) {
        result.add(owner);
      }
    }
    return result;
  }

  private String qualifiedThisOwner(SourceFile file, String qualifier, int siteOffset) {
    String dottedQualifier = qualifier.replace('$', '.');
    List<TypeInfo> containing = typeDeclarations(file).stream()
        .filter(type -> type.start() <= siteOffset && type.end() >= siteOffset)
        .sorted(Comparator.comparingInt(TypeInfo::start).reversed())
        .toList();
    for (TypeInfo type : containing) {
      String sourceName = sourceTypeName(file, type);
      if (sourceName.equals(dottedQualifier)
          || sourceName.endsWith("." + dottedQualifier)
          || type.name().equals(qualifier)) {
        return typeContaining(file, type.start());
      }
    }
    return lexicalOwnerNames(file, siteOffset).stream()
        .filter(owner -> {
          String dottedOwner = owner.replace('$', '.');
          return dottedOwner.equals(dottedQualifier)
              || dottedOwner.endsWith("." + dottedQualifier);
        })
        .findFirst()
        .orElse(null);
  }

  private static String sourceTypeName(SourceFile file, TypeInfo target) {
    return typeDeclarations(file).stream()
        .filter(type -> type.start() <= target.start() && type.end() >= target.start())
        .sorted(Comparator.comparingInt(TypeInfo::start))
        .map(TypeInfo::name)
        .collect(java.util.stream.Collectors.joining("."));
  }

  private List<String> localTypeCandidatesForOwner(
      String simpleName, String owner, SourceFile sourceFile, int siteOffset) {
    TypeReference ownerReference = findTypeReference(owner);
    if (ownerReference == null) {
      return List.of();
    }
    return typeDeclarations(sourceFile).stream()
        .filter(type -> type.name().equals(simpleName))
        .filter(type -> isLocalType(sourceFile, type))
        .filter(type -> {
          TypeInfo enclosing = enclosingType(sourceFile, type);
          return enclosing != null && enclosing.start() == ownerReference.type().start();
        })
        .filter(type -> type.start() <= siteOffset)
        .filter(type -> localTypeScopeEnd(sourceFile, type) >= siteOffset)
        .sorted(Comparator.comparingInt(TypeInfo::start).reversed())
        .map(type -> typeContaining(sourceFile, type.start()))
        .distinct()
        .limit(1)
        .toList();
  }

  private List<String> directMemberTypeCandidates(String owner, String simpleName) {
    String candidate = owner + "$" + simpleName;
    return files().stream()
        .flatMap(file -> typeDeclarations(file).stream()
            .map(type -> typeContaining(file, type.start())))
        .anyMatch(candidate::equals)
        ? List.of(candidate)
        : List.of();
  }

  private List<String> samePackageTypeCandidates(String requestedName, SourceFile sourceFile) {
    return files().stream()
        .flatMap(file -> typeDeclarations(file).stream()
            .map(type -> typeContaining(file, type.start())))
        .distinct()
        .filter(candidate -> !candidate.contains("$"))
        .filter(candidate -> sourceFile.packageName().isBlank()
            ? !candidate.contains(".")
            : candidate.startsWith(sourceFile.packageName() + "."))
        .filter(candidate -> simpleName(candidate).equals(requestedName))
        .toList();
  }

  private List<String> localTypeCandidates(String simpleName, SourceFile sourceFile, int siteOffset) {
    return typeDeclarations(sourceFile).stream()
        .filter(type -> type.name().equals(simpleName))
        .filter(type -> isLocalType(sourceFile, type))
        .filter(type -> type.start() <= siteOffset)
        .filter(type -> localTypeScopeEnd(sourceFile, type) >= siteOffset)
        .sorted(Comparator.comparingInt(TypeInfo::start).reversed())
        .map(type -> typeContaining(sourceFile, type.start()))
        .distinct()
        .limit(1)
        .toList();
  }

  private List<String> inheritedMemberTypeCandidates(
      String owner, String simpleName, SourceFile siteFile) {
    TypeReference reference = findTypeReference(owner);
    if (reference == null) {
      return List.of();
    }
    List<String> frontier = resolveParentTypes(reference);
    Set<String> visited = new HashSet<>();
    while (!frontier.isEmpty()) {
      List<String> matches = new ArrayList<>();
      List<String> next = new ArrayList<>();
      for (String parent : frontier) {
        if (!visited.add(parent)) {
          continue;
        }
        String candidate = parent + "$" + simpleName;
        boolean declaresMemberType = directMemberTypeCandidates(parent, simpleName).contains(candidate);
        if (declaresMemberType) {
          if (isAccessibleMemberType(candidate, siteFile)) {
            matches.add(candidate);
          }
          continue;
        }
        TypeReference parentReference = findTypeReference(parent);
        if (parentReference != null) {
          next.addAll(resolveParentTypes(parentReference));
        }
      }
      if (!matches.isEmpty()) {
        return matches.stream().distinct().toList();
      }
      frontier = next;
    }
    return List.of();
  }

  private boolean isAccessibleMemberType(String candidate, SourceFile siteFile) {
    TypeReference reference = findTypeReference(candidate);
    if (reference == null) {
      return false;
    }
    String source = maskStringLiterals(reference.file().structuralSource());
    int opening = typeBodyOpening(source, reference.type().start());
    if (opening < 0) {
      return false;
    }
    String declaration = memberHeader(source, opening);
    if (Pattern.compile("\\bprivate\\b").matcher(declaration).find()) {
      return false;
    }
    TypeInfo enclosing = enclosingType(reference.file(), reference.type());
    if (enclosing != null && enclosing.kind().equals("interface")) {
      return true;
    }
    return reference.file().packageName().equals(siteFile.packageName())
        || Pattern.compile("\\b(?:public|protected)\\b").matcher(declaration).find();
  }

  private static int localTypeScopeEnd(SourceFile sourceFile, TypeInfo type) {
    MethodInfo owner = methodContaining(sourceFile, Math.max(0, type.start() - 1));
    if (owner.name().equals("<unknown>")) {
      return type.end();
    }
    String source = sourceFile.structuralSource();
    int methodOpening = methodBodyOpening(source, owner.start());
    if (methodOpening < 0) {
      return type.end();
    }
    int blockOpening = enclosingBlockOpening(source, methodOpening, type.start());
    int blockEnd = blockOpening < 0 ? -1 : matchingDelimiter(source, blockOpening, '{', '}');
    return blockEnd >= 0 ? blockEnd : type.end();
  }

  private static String normalizeDeclaredTypeName(String spelling, SourceFile sourceFile) {
    String normalized = spelling == null ? "" : spelling.trim();
    if (normalized.contains("$")) {
      return normalized;
    }
    int packageSeparator = normalized.indexOf('.');
    if (packageSeparator >= 0 && !sourceFile.packageName().isBlank()) {
      String packagePrefix = sourceFile.packageName() + ".";
      if (normalized.startsWith(packagePrefix)) {
        String remainder = normalized.substring(packagePrefix.length());
        return packagePrefix + remainder.replace('.', '$');
      }
    }
    return normalized;
  }

  private static boolean isDeclarationKeyword(String value) {
    return Set.of("return", "throw", "new", "if", "for", "while", "switch", "case", "catch", "assert")
        .contains(value);
  }

  private static boolean declarationIsActive(
      String source, int declaration, int offset, int ownerStart) {
    DeclarationScope declarationScope = catchOrResourceScope(source, declaration);
    if (declarationScope != null) {
      return offset >= declarationScope.start() && offset <= declarationScope.end();
    }
    ForScope forScope = forInitializerScope(source, declaration);
    if (forScope != null) {
      if (offset > forScope.end()
          || forScope.enhanced() && offset < forScope.bodyStart()) {
        return false;
      }
    }
    int methodOpening = methodBodyOpening(source, ownerStart);
    if (methodOpening < 0 || offset < methodOpening) {
      return false;
    }
    int opening = enclosingBlockOpening(source, methodOpening, declaration);
    return opening >= 0 && matchingDelimiter(source, opening, '{', '}') >= offset;
  }

  private static DeclarationScope catchOrResourceScope(String source, int declaration) {
    Matcher catchMatcher = Pattern.compile("\\bcatch\\s*\\(").matcher(source);
    while (catchMatcher.find()) {
      int opening = catchMatcher.end() - 1;
      int closing = matchingDelimiter(source, opening, '(', ')');
      if (closing < 0 || declaration <= opening || declaration >= closing) {
        continue;
      }
      int bodyStart = skipWhitespace(source, closing + 1);
      if (bodyStart < source.length() && source.charAt(bodyStart) == '{') {
        int bodyEnd = matchingDelimiter(source, bodyStart, '{', '}');
        return bodyEnd < 0 ? null : new DeclarationScope(bodyStart, bodyEnd);
      }
    }
    Matcher resourceMatcher = Pattern.compile("\\btry\\s*\\(").matcher(source);
    while (resourceMatcher.find()) {
      int opening = resourceMatcher.end() - 1;
      int closing = matchingDelimiter(source, opening, '(', ')');
      if (closing < 0 || declaration <= opening || declaration >= closing) {
        continue;
      }
      if (!topLevelResourceDeclaration(source, opening, declaration)) {
        continue;
      }
      int bodyStart = skipWhitespace(source, closing + 1);
      if (bodyStart < source.length() && source.charAt(bodyStart) == '{') {
        int bodyEnd = matchingDelimiter(source, bodyStart, '{', '}');
        return bodyEnd < 0 ? null : new DeclarationScope(declaration, bodyEnd);
      }
    }
    return null;
  }

  private static boolean topLevelResourceDeclaration(String source, int opening, int declaration) {
    int parentheses = 0;
    int brackets = 0;
    int braces = 0;
    for (int index = opening + 1; index < declaration; index++) {
      switch (source.charAt(index)) {
        case '(' -> parentheses++;
        case ')' -> parentheses = Math.max(0, parentheses - 1);
        case '[' -> brackets++;
        case ']' -> brackets = Math.max(0, brackets - 1);
        case '{' -> braces++;
        case '}' -> braces = Math.max(0, braces - 1);
        default -> { }
      }
    }
    return parentheses == 0 && brackets == 0 && braces == 0;
  }

  private static ForScope forInitializerScope(String source, int declaration) {
    Matcher matcher = Pattern.compile("\\bfor\\s*\\(").matcher(source);
    ForScope selected = null;
    while (matcher.find()) {
      int opening = matcher.end() - 1;
      int closing = matchingDelimiter(source, opening, '(', ')');
      if (closing < 0 || declaration <= opening || declaration >= closing) {
        continue;
      }
      int semicolon = topLevelHeaderDelimiter(source, opening + 1, closing, ';');
      int colon = semicolon < 0
          ? topLevelHeaderDelimiter(source, opening + 1, closing, ':')
          : -1;
      boolean enhanced = semicolon < 0 && colon >= 0;
      if ((semicolon < 0 || declaration >= semicolon)
          && (colon < 0 || declaration >= colon)) {
        continue;
      }
      int bodyStart = closing + 1;
      while (bodyStart < source.length() && Character.isWhitespace(source.charAt(bodyStart))) {
        bodyStart++;
      }
      int bodyEnd = bodyStart < source.length() && source.charAt(bodyStart) == '{'
          ? matchingDelimiter(source, bodyStart, '{', '}')
          : statementEnd(source, bodyStart);
      if (bodyEnd >= 0 && (selected == null || opening > selected.opening())) {
        selected = new ForScope(opening, bodyStart, bodyEnd, enhanced);
      }
    }
    return selected;
  }

  private static int topLevelHeaderDelimiter(String source, int start, int end, char delimiter) {
    int parentheses = 0;
    int brackets = 0;
    int braces = 0;
    for (int index = start; index < end; index++) {
      switch (source.charAt(index)) {
        case '(' -> parentheses++;
        case ')' -> parentheses = Math.max(0, parentheses - 1);
        case '[' -> brackets++;
        case ']' -> brackets = Math.max(0, brackets - 1);
        case '{' -> braces++;
        case '}' -> braces = Math.max(0, braces - 1);
        default -> { }
      }
      if (source.charAt(index) == delimiter
          && parentheses == 0 && brackets == 0 && braces == 0) {
        return index;
      }
    }
    return -1;
  }

  private static int statementEnd(String source, int start) {
    int position = skipWhitespace(source, start);
    if (position >= source.length()) {
      return source.length() - 1;
    }
    if (startsKeyword(source, position, "if")) {
      return conditionalStatementEnd(source, position, "if");
    }
    if (startsKeyword(source, position, "for")
        || startsKeyword(source, position, "while")
        || startsKeyword(source, position, "synchronized")
        || startsKeyword(source, position, "switch")) {
      int opening = source.indexOf('(', position);
      int closing = opening < 0 ? -1 : matchingDelimiter(source, opening, '(', ')');
      return closing < 0 ? simpleStatementEnd(source, position) : statementEnd(source, closing + 1);
    }
    if (startsKeyword(source, position, "do")) {
      int bodyEnd = statementEnd(source, position + 2);
      int whileStart = skipWhitespace(source, bodyEnd + 1);
      if (startsKeyword(source, whileStart, "while")) {
        int opening = source.indexOf('(', whileStart);
        int closing = opening < 0 ? -1 : matchingDelimiter(source, opening, '(', ')');
        return closing < 0 ? simpleStatementEnd(source, whileStart) : statementEnd(source, closing + 1);
      }
      return bodyEnd;
    }
    if (startsKeyword(source, position, "try")) {
      return tryStatementEnd(source, position);
    }
    int labelEnd = labelEnd(source, position);
    if (labelEnd >= 0) {
      return statementEnd(source, labelEnd);
    }
    if (source.charAt(position) == '{') {
      return matchingDelimiter(source, position, '{', '}');
    }
    return simpleStatementEnd(source, position);
  }

  private static int conditionalStatementEnd(String source, int start, String keyword) {
    int opening = source.indexOf('(', start + keyword.length());
    int closing = opening < 0 ? -1 : matchingDelimiter(source, opening, '(', ')');
    if (closing < 0) {
      return simpleStatementEnd(source, start);
    }
    int thenEnd = statementEnd(source, closing + 1);
    int elseStart = skipWhitespace(source, thenEnd + 1);
    if (startsKeyword(source, elseStart, "else")) {
      return statementEnd(source, elseStart + "else".length());
    }
    return thenEnd;
  }

  private static int tryStatementEnd(String source, int start) {
    int bodyStart = skipWhitespace(source, start + "try".length());
    if (bodyStart < source.length() && source.charAt(bodyStart) == '(') {
      int resourcesEnd = matchingDelimiter(source, bodyStart, '(', ')');
      if (resourcesEnd < 0) {
        return simpleStatementEnd(source, start);
      }
      bodyStart = skipWhitespace(source, resourcesEnd + 1);
    }
    int end = statementEnd(source, bodyStart);
    int next = skipWhitespace(source, end + 1);
    while (startsKeyword(source, next, "catch")) {
      int opening = source.indexOf('(', next + "catch".length());
      int closing = opening < 0 ? -1 : matchingDelimiter(source, opening, '(', ')');
      if (closing < 0) {
        return end;
      }
      end = statementEnd(source, closing + 1);
      next = skipWhitespace(source, end + 1);
    }
    if (startsKeyword(source, next, "finally")) {
      end = statementEnd(source, next + "finally".length());
    }
    return end;
  }

  private static int labelEnd(String source, int start) {
    int position = start;
    if (position >= source.length() || !Character.isJavaIdentifierStart(source.charAt(position))) {
      return -1;
    }
    position++;
    while (position < source.length() && Character.isJavaIdentifierPart(source.charAt(position))) {
      position++;
    }
    position = skipWhitespace(source, position);
    return position < source.length() && source.charAt(position) == ':' ? position + 1 : -1;
  }

  private static int simpleStatementEnd(String source, int start) {
    int parentheses = 0;
    int brackets = 0;
    int braces = 0;
    for (int index = start; index < source.length(); index++) {
      switch (source.charAt(index)) {
        case '(' -> parentheses++;
        case ')' -> parentheses = Math.max(0, parentheses - 1);
        case '[' -> brackets++;
        case ']' -> brackets = Math.max(0, brackets - 1);
        case '{' -> braces++;
        case '}' -> {
          if (braces > 0) {
            braces--;
          }
        }
        case ';' -> {
          if (parentheses == 0 && brackets == 0 && braces == 0) {
            return index;
          }
        }
        default -> { }
      }
    }
    return source.length() - 1;
  }

  private static boolean startsKeyword(String source, int start, String keyword) {
    return start >= 0
        && start + keyword.length() <= source.length()
        && source.startsWith(keyword, start)
        && (start == 0 || !Character.isJavaIdentifierPart(source.charAt(start - 1)))
        && (start + keyword.length() == source.length()
            || !Character.isJavaIdentifierPart(source.charAt(start + keyword.length())));
  }

  private static int methodBodyOpening(String source, int ownerStart) {
    int parentheses = 0;
    int brackets = 0;
    for (int index = ownerStart; index < source.length(); index++) {
      switch (source.charAt(index)) {
        case '(' -> parentheses++;
        case ')' -> parentheses = Math.max(0, parentheses - 1);
        case '[' -> brackets++;
        case ']' -> brackets = Math.max(0, brackets - 1);
        case '{' -> {
          if (parentheses == 0 && brackets == 0) {
            return index;
          }
        }
        default -> { }
      }
    }
    return -1;
  }

  private static int enclosingBlockOpening(String source, int methodOpening, int target) {
    List<Integer> blocks = new ArrayList<>();
    blocks.add(methodOpening);
    for (int index = methodOpening + 1; index < Math.min(target, source.length()); index++) {
      char character = source.charAt(index);
      if (character == '{') {
        blocks.add(index);
      } else if (character == '}' && blocks.size() > 1) {
        blocks.remove(blocks.size() - 1);
      }
    }
    return blocks.get(blocks.size() - 1);
  }

  private static String dynamicTarget(String source, int offset, String symbol) {
    String literal = firstQuotedArgument(source, offset);
    if (symbol.equals("Class.forName") || symbol.equals("ClassLoader.loadClass")) {
      return (literal == null ? "unknown" : literal) + "#unknown";
    }
    if (symbol.equals("getDeclaredMethod") || symbol.equals("getMethod")) {
      return "unknown#" + (literal == null ? "unknown" : literal);
    }
    return "unknown#unknown";
  }

  private static String firstQuotedArgument(String source, int offset) {
    int opening = source.indexOf('(', offset);
    if (opening < 0) {
      return null;
    }
    int closing = matchingDelimiter(source, opening, '(', ')');
    if (closing < 0) {
      return null;
    }
    int first = opening + 1;
    while (first < closing && Character.isWhitespace(source.charAt(first))) {
      first++;
    }
    if (first >= closing || source.charAt(first) != '"') {
      return null;
    }
    boolean escaped = false;
    int end = first + 1;
    for (; end < closing; end++) {
      char character = source.charAt(end);
      if (character == '"' && !escaped) {
        break;
      }
      escaped = character == '\\' && !escaped;
      if (character != '\\') {
        escaped = false;
      }
    }
    if (end >= closing) {
      return null;
    }
    int remainder = end + 1;
    while (remainder < closing && Character.isWhitespace(source.charAt(remainder))) {
      remainder++;
    }
    if (remainder < closing && source.charAt(remainder) != ',') {
      return null;
    }
    return source.substring(first + 1, end);
  }

  /** Builds a stable overload discriminator without changing the public callee identity. */
  private static String methodDescriptor(MethodInfo method) {
    String parameters = method.parameters().trim();
    if (parameters.isEmpty()) {
      return "()";
    }
    return "(" + String.join(",", splitTopLevelArguments(parameters).stream()
        .map(SourceMetadataScanner::parameterType)
        .toList()) + ")";
  }

  private static String parameterType(String parameter) {
    String normalized = parameter.trim()
        .replaceAll("@[A-Za-z_][\\w.]*", "")
        .replace("final ", "")
        .trim();
    int nameSeparator = normalized.lastIndexOf(' ');
    if (nameSeparator > 0) {
      normalized = normalized.substring(0, nameSeparator).trim();
    }
    return normalized.replace("...", "[]").replaceAll("\\s+", "");
  }

  /** Builds unresolved evidence for one dynamic proxy construction site. */
  private DynamicFeature dynamicProxyFeature(
      SourceFile file, MethodInfo caller, String target, String targetDescriptor, int offset) {
    List<String> symbols = new ArrayList<>();
    symbols.add("Proxy.newProxyInstance");
    if (file.structuralSource().contains("InvocationHandler")) {
      symbols.add("InvocationHandler");
    }
    return new DynamicFeature(
        DynamicFeature.Kind.DYNAMIC_PROXY,
        new MethodSymbol(typeContaining(file, offset), caller.name(), ""),
        target,
        file.displayPath(),
        symbols,
        "The proxy handler target is not promoted without explicit registration evidence.",
        file.lineAt(offset),
        offset,
        targetDescriptor);
  }

  /** Reads source text and derives its package and top-level type identity. */
  private SourceFile read(Path path, AnalysisContext.SourceRoot root) {
    String source = readSource(path);
    String structuralSource = stripComments(source);
    String maskedStructuralSource = maskStringLiterals(structuralSource);
    Matcher packageMatcher = PACKAGE.matcher(maskedStructuralSource);
    String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
    Matcher typeMatcher = TYPE.matcher(maskedStructuralSource);
    if (!typeMatcher.find()) {
      throw new IllegalStateException("Java source has no top-level type: " + path);
    }
    String simpleName = typeMatcher.group(1);
    String typeName = packageName.isBlank() ? simpleName : packageName + "." + simpleName;
    return new SourceFile(
        path,
        context.displayPath(path),
        source,
        structuralSource,
        packageName,
        typeName,
        simpleName,
        typeMatcher.start(),
        root.generated());
  }

  /** Reads one source file while preserving the scanner's unchecked failure contract. */
  private static String readSource(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException error) {
      throw new IllegalStateException("cannot read Java source " + path, error);
    }
  }

  /** Extracts bounded method declarations and their annotation blocks. */
  static List<MethodInfo> methods(SourceFile file) {
    List<MethodInfo> result = new ArrayList<>();
    String maskedSource = maskAnnotationArguments(file.structuralSource());
    Matcher matcher = METHOD.matcher(maskedSource);
    while (matcher.find()) {
      if (isControlFlowKeyword(matcher.group(1))) {
        continue;
      }
      int start = matcher.start();
      String annotationBlock = file.source().substring(start, matcher.start(1));
      int annotationOffset = annotationBlock.indexOf('@');
      int lineOffset = annotationOffset < 0 ? matcher.start(1) : start + annotationOffset;
      result.add(new MethodInfo(
          matcher.group(1),
          file.source().substring(matcher.start(2), matcher.end(2)),
          annotationBlock,
          start,
          matcher.end(),
          file.lineAt(lineOffset),
          returnType(annotationBlock)));
    }
    return List.copyOf(result);
  }

  private static boolean isControlFlowKeyword(String name) {
    return switch (name) {
      case "if", "for", "while", "switch", "catch", "try", "synchronized" -> true;
      default -> false;
    };
  }

  /** Extracts constructor bodies so dynamic sites retain an explicit initializer caller. */
  private static List<MethodInfo> constructors(SourceFile file) {
    String maskedSource = maskAnnotationArguments(file.structuralSource());
    List<MethodInfo> result = new ArrayList<>();
    for (TypeInfo type : typeDeclarations(file)) {
      Pattern constructor = Pattern.compile(
          "(?m)(?:^|[;{}\\n])\\s*"
              + "(?:(?:@[A-Za-z_][\\w.]*(?:\\([^)]*\\))?\\s*)|"
              + "(?:(?:public|protected|private|static|final|synchronized|strictfp)\\s+))*"
              + Pattern.quote(type.name())
              + "\\s*\\(([^)]*)\\)\\s*(?:throws [^{;]+)?\\{");
      Matcher matcher = constructor.matcher(maskedSource);
      while (matcher.find()) {
        int start = matcher.start();
        String annotationBlock = file.source().substring(start, matcher.start(1));
        int annotationOffset = annotationBlock.indexOf('@');
        int lineOffset = annotationOffset < 0 ? matcher.start(1) : start + annotationOffset;
        result.add(new MethodInfo(
            "<init>",
            file.source().substring(matcher.start(1), matcher.end(1)),
            annotationBlock,
            start,
            matcher.end(),
            file.lineAt(lineOffset),
            ""));
      }
    }
    return result.stream()
        .distinct()
        .sorted(Comparator.comparingInt(MethodInfo::start))
        .toList();
  }

  private static List<MethodInfo> executableMethods(SourceFile file) {
    List<MethodInfo> result = new ArrayList<>(methods(file));
    result.addAll(constructors(file));
    return List.copyOf(result);
  }

  /** Reports whether a proxy target method name has multiple declared overloads. */
  boolean proxyMethodOverloaded(ProxyTarget target) {
    return methods(target.file()).stream()
        .filter(method -> method.name().equals(target.method().name()))
        .count() > 1;
  }

  /** Masks annotation arguments so method declaration scanning remains bounded. */
  private static String maskAnnotationArguments(String source) {
    char[] masked = source.toCharArray();
    Matcher matcher = Pattern.compile("@[A-Za-z_][\\w.]*").matcher(source);
    while (matcher.find()) {
      int opening = matcher.end();
      while (opening < source.length() && Character.isWhitespace(source.charAt(opening))) {
        opening++;
      }
      if (opening >= source.length() || source.charAt(opening) != '(') {
        continue;
      }
      int closing = matchingDelimiter(source, opening, '(', ')');
      if (closing < 0) {
        continue;
      }
      masked[opening] = ' ';
      masked[closing] = ' ';
      for (int index = opening + 1; index < closing; index++) {
        if (source.charAt(index) != '\n' && source.charAt(index) != '\r') {
          masked[index] = ' ';
        }
      }
    }
    return new String(masked);
  }

  private static MethodInfo methodContaining(SourceFile file, int index) {
    List<MethodInfo> constructors = constructors(file);
    String siteType = typeContaining(file, index);
    return executableMethods(file).stream()
        .filter(method -> typeContaining(file, method.start()).equals(siteType))
        .filter(method -> constructors.stream().noneMatch(constructor ->
            constructor.start() == method.start() && !method.name().equals("<init>")))
        .filter(method -> method.start() <= index)
        .filter(method -> method.end() > 0
            && method.end() <= file.structuralSource().length()
            && file.structuralSource().charAt(method.end() - 1) == '{')
         .filter(method -> matchingDelimiter(
             file.structuralSource(), method.end() - 1, '{', '}') >= index)
         .max(Comparator.comparingInt(MethodInfo::start))
         .orElseGet(() -> initializerContaining(file, index)
             .orElseGet(() -> new MethodInfo(
                 "<unknown>", "", "", index, index, file.lineAt(index), "")));
  }

  /** Attributes a dynamic site in a field initializer to the instance or static initializer. */
  private static Optional<MethodInfo> initializerContaining(SourceFile file, int index) {
    String source = maskStringLiterals(file.structuralSource());
    Pattern field = Pattern.compile(
        "(?m)(?:^|[;{}])\\s*"
            + "(?:(?:@[A-Za-z_][\\w.]*(?:\\([^;{}]*\\))?\\s*)|"
            + "(?:(?:public|protected|private|static|final|transient|volatile|strictfp)\\s+))*"
            + "[A-Za-z_$][\\w$.<>?,\\[\\] ]*\\s+[A-Za-z_$][\\w$]*\\s*=");
    Matcher matcher = field.matcher(source);
    MethodInfo selected = initializerBlockContaining(file, source, index).orElse(null);
    while (matcher.find()) {
      int declarationStart = matcher.start();
      while (declarationStart < matcher.end()
          && (Character.isWhitespace(source.charAt(declarationStart))
              || ";{}".indexOf(source.charAt(declarationStart)) >= 0)) {
        declarationStart++;
      }
      if (!isTypeMemberScope(file, source, declarationStart)) {
        continue;
      }
      int equals = source.lastIndexOf('=', matcher.end() - 1);
      if (equals >= index) {
        continue;
      }
      int end = fieldInitializerEnd(source, equals + 1);
      if (end <= index) {
        continue;
      }
      String declaration = source.substring(matcher.start(), equals);
      TypeInfo owner = innermostType(file, declarationStart);
      String name = owner != null && owner.kind().equals("interface")
          || Pattern.compile("\\bstatic\\b").matcher(declaration).find()
          ? "<clinit>" : "<init>";
      selected = new MethodInfo(
          name,
          "",
          declaration,
          matcher.start(),
          end + 1,
          file.lineAt(matcher.start()),
          "");
    }
    return Optional.ofNullable(selected);
  }

  /** Finds a dynamic site inside a type-level instance or static initializer block. */
  private static Optional<MethodInfo> initializerBlockContaining(
      SourceFile file, String source, int index) {
    TypeInfo owner = innermostType(file, index);
    if (owner == null) {
      return Optional.empty();
    }
    int typeOpening = typeBodyOpening(source, owner.start());
    if (typeOpening < 0 || typeOpening >= owner.end()) {
      return Optional.empty();
    }
    int typeBodyDepth = braceDepthAt(source, typeOpening + 1);
    MethodInfo selected = null;
    for (int opening = typeOpening + 1; opening < index; opening++) {
      if (source.charAt(opening) != '{'
          || braceDepthAt(source, opening) != typeBodyDepth
          || !isInitializerBlockOpening(file, source, opening)) {
        continue;
      }
      int closing = matchingDelimiter(source, opening, '{', '}');
      if (closing < index) {
        continue;
      }
      String header = memberHeader(source, opening);
      String name = header.matches("(?s).*\\bstatic\\b.*")
          || owner.kind().equals("interface") ? "<clinit>" : "<init>";
      selected = new MethodInfo(
          name,
          "",
          header,
          opening,
          closing + 1,
          file.lineAt(opening),
          "");
    }
    return Optional.ofNullable(selected);
  }

  private static boolean isInitializerBlockOpening(SourceFile file, String source, int opening) {
    for (TypeInfo type : typeDeclarations(file)) {
      int typeOpening = typeBodyOpening(source, type.start());
      if (typeOpening == opening) {
        return false;
      }
    }
    String header = memberHeader(source, opening);
    return header.isBlank() || header.matches("(?s)\\s*static\\s*");
  }

  private static String memberHeader(String source, int opening) {
    int start = opening - 1;
    while (start >= 0 && ";{}".indexOf(source.charAt(start)) < 0) {
      start--;
    }
    return source.substring(start + 1, opening).trim();
  }

  /** Keeps field-initializer ownership out of method, block, and lambda locals. */
  private static boolean isTypeMemberScope(SourceFile file, String source, int index) {
    TypeInfo owner = innermostType(file, index);
    if (owner == null) {
      return false;
    }
    int opening = typeBodyOpening(source, owner.start());
    if (opening < 0 || opening >= owner.end()) {
      return false;
    }
    return braceDepthAt(source, index) == braceDepthAt(source, opening + 1);
  }

  private static TypeInfo innermostType(SourceFile file, int index) {
    return typeDeclarations(file).stream()
        .filter(type -> type.start() <= index && type.end() >= index)
        .max(Comparator.comparingInt(TypeInfo::start))
        .orElse(null);
  }

  private static int braceDepthAt(String source, int index) {
    int depth = 0;
    for (int position = 0; position < Math.min(index, source.length()); position++) {
      if (source.charAt(position) == '{') {
        depth++;
      } else if (source.charAt(position) == '}') {
        depth = Math.max(0, depth - 1);
      }
    }
    return depth;
  }

  private static int fieldInitializerEnd(String source, int start) {
    int parentheses = 0;
    int brackets = 0;
    int braces = 0;
    for (int index = start; index < source.length(); index++) {
      switch (source.charAt(index)) {
        case '(' -> parentheses++;
        case ')' -> parentheses = Math.max(0, parentheses - 1);
        case '[' -> brackets++;
        case ']' -> brackets = Math.max(0, brackets - 1);
        case '{' -> braces++;
        case '}' -> braces = Math.max(0, braces - 1);
        case ';' -> {
          if (parentheses == 0 && brackets == 0 && braces == 0) {
            return index;
          }
        }
        default -> { }
      }
    }
    return -1;
  }

  /** Returns the deepest declared type that contains a source offset. */
  private static String typeContaining(SourceFile file, int index) {
    if (anonymousTypeContaining(file, index) != null) {
      return "unknown";
    }
    List<TypeInfo> containing = typeDeclarations(file).stream()
        .filter(type -> type.start() <= index && type.end() >= index)
        .sorted(Comparator.comparingInt(TypeInfo::start))
        .toList();
    if (containing.isEmpty()) {
      return file.typeName();
    }
    StringBuilder result = new StringBuilder();
    if (!file.packageName().isBlank()) {
      result.append(file.packageName()).append('.');
    }
    result.append(containing.get(0).name());
    for (int position = 1; position < containing.size(); position++) {
      TypeInfo type = containing.get(position);
      result.append('$');
      if (isLocalType(file, type)) {
        result.append(localTypeOrdinal(file, type));
      }
      result.append(type.name());
    }
    return result.toString();
  }

  private static boolean isLocalType(SourceFile file, TypeInfo type) {
    TypeInfo enclosing = enclosingType(file, type);
    if (enclosing == null) {
      return false;
    }
    String source = maskStringLiterals(file.structuralSource());
    int opening = typeBodyOpening(source, enclosing.start());
    return opening >= 0
        && opening < enclosing.end()
        && braceDepthAt(source, type.start()) > braceDepthAt(source, opening + 1);
  }

  private static int localTypeOrdinal(SourceFile file, TypeInfo type) {
    TypeInfo enclosing = enclosingType(file, type);
    if (enclosing == null) {
      return 1;
    }
    return (int) typeDeclarations(file).stream()
        .filter(candidate -> candidate.start() < type.start())
        .filter(candidate -> candidate.name().equals(type.name()))
        .filter(candidate -> isLocalType(file, candidate))
        .filter(candidate -> {
          TypeInfo candidateEnclosing = enclosingType(file, candidate);
          return candidateEnclosing != null
              && candidateEnclosing.start() == enclosing.start();
        })
        .count() + 1;
  }

  private static TypeInfo enclosingType(SourceFile file, TypeInfo type) {
    return typeDeclarations(file).stream()
        .filter(candidate -> candidate.start() < type.start() && candidate.end() >= type.start())
        .max(Comparator.comparingInt(TypeInfo::start))
        .orElse(null);
  }

  /** Returns an anonymous-class scope without inventing a source-only binary owner. */
  private static AnonymousType anonymousTypeContaining(SourceFile file, int index) {
    String source = maskStringLiterals(file.structuralSource());
    Matcher matcher = NEW_EXPRESSION.matcher(source);
    AnonymousType selected = null;
    while (matcher.find()) {
      int constructorOpening = anonymousConstructorOpening(source, matcher.end());
      if (constructorOpening < 0) {
        continue;
      }
      int constructorClosing = matchingDelimiter(source, constructorOpening, '(', ')');
      if (constructorClosing < 0) {
        continue;
      }
      int opening = constructorClosing + 1;
      while (opening < source.length() && Character.isWhitespace(source.charAt(opening))) {
        opening++;
      }
      if (opening >= source.length() || source.charAt(opening) != '{') {
        continue;
      }
      int closing = matchingDelimiter(source, opening, '{', '}');
      if (opening <= index
          && closing >= index
          && (selected == null || opening > selected.start())) {
        selected = new AnonymousType(opening, closing);
      }
    }
    return selected;
  }

  /** Locates the constructor parenthesis after explicit type arguments and type-use annotations. */
  private static int anonymousConstructorOpening(String source, int start) {
    int position = skipWhitespace(source, start);
    if (position < source.length() && source.charAt(position) == '<') {
      position = matchingGenericDelimiter(source, position) + 1;
      if (position == 0) {
        return -1;
      }
      position = skipWhitespace(source, position);
    }
    while (position < source.length() && source.charAt(position) == '@') {
      position++;
      position = skipWhitespace(source, position);
      position = qualifiedIdentifierEnd(source, position);
      if (position < 0) {
        return -1;
      }
      position = skipWhitespace(source, position);
      if (position < source.length() && source.charAt(position) == '(') {
        int annotationClosing = matchingDelimiter(source, position, '(', ')');
        if (annotationClosing < 0) {
          return -1;
        }
        position = skipWhitespace(source, annotationClosing + 1);
      }
    }
    position = qualifiedIdentifierEnd(source, position);
    if (position < 0) {
      return -1;
    }
    position = skipWhitespace(source, position);
    if (position < source.length() && source.charAt(position) == '<') {
      int genericClosing = matchingGenericDelimiter(source, position);
      if (genericClosing < 0) {
        return -1;
      }
      position = skipWhitespace(source, genericClosing + 1);
    }
    return position < source.length() && source.charAt(position) == '(' ? position : -1;
  }

  private static int skipWhitespace(String source, int start) {
    int position = start;
    while (position < source.length() && Character.isWhitespace(source.charAt(position))) {
      position++;
    }
    return position;
  }

  private static int qualifiedIdentifierEnd(String source, int start) {
    int position = start;
    if (position >= source.length() || !Character.isJavaIdentifierStart(source.charAt(position))) {
      return -1;
    }
    position++;
    while (position < source.length() && Character.isJavaIdentifierPart(source.charAt(position))) {
      position++;
    }
    while (true) {
      int dot = skipWhitespace(source, position);
      if (dot >= source.length() || source.charAt(dot) != '.') {
        return position;
      }
      int segmentStart = skipWhitespace(source, dot + 1);
      if (segmentStart >= source.length()
          || !Character.isJavaIdentifierStart(source.charAt(segmentStart))) {
        return -1;
      }
      position = segmentStart + 1;
      while (position < source.length() && Character.isJavaIdentifierPart(source.charAt(position))) {
        position++;
      }
    }
  }

  private static int matchingGenericDelimiter(String source, int opening) {
    int depth = 0;
    int parentheses = 0;
    int brackets = 0;
    int braces = 0;
    for (int index = opening; index < source.length(); index++) {
      char character = source.charAt(index);
      switch (character) {
        case '(' -> parentheses++;
        case ')' -> parentheses = Math.max(0, parentheses - 1);
        case '[' -> brackets++;
        case ']' -> brackets = Math.max(0, brackets - 1);
        case '{' -> braces++;
        case '}' -> braces = Math.max(0, braces - 1);
        case '<' -> {
          if (parentheses == 0 && brackets == 0 && braces == 0) {
            depth++;
          }
        }
        case '>' -> {
          if (parentheses == 0 && brackets == 0 && braces == 0 && depth > 0) {
            depth--;
            if (depth == 0) {
              return index;
            }
          }
        }
        default -> { }
      }
    }
    return -1;
  }

  /** Finds a type body brace without mistaking annotation arrays for the body. */
  private static int typeBodyOpening(String source, int start) {
    int parentheses = 0;
    int brackets = 0;
    int braces = 0;
    int angleBrackets = 0;
    for (int index = Math.max(0, start); index < source.length(); index++) {
      switch (source.charAt(index)) {
        case '(' -> parentheses++;
        case ')' -> parentheses = Math.max(0, parentheses - 1);
        case '[' -> brackets++;
        case ']' -> brackets = Math.max(0, brackets - 1);
        case '<' -> {
          if (parentheses == 0 && brackets == 0 && braces == 0) {
            angleBrackets++;
          }
        }
        case '>' -> {
          if (parentheses == 0 && brackets == 0 && braces == 0) {
            angleBrackets = Math.max(0, angleBrackets - 1);
          }
        }
        case '{' -> {
          if (parentheses == 0 && brackets == 0 && braces == 0 && angleBrackets == 0) {
            return index;
          }
          braces++;
        }
        case '}' -> braces = Math.max(0, braces - 1);
        default -> { }
      }
    }
    return -1;
  }

  /** Finds declared top-level and nested Java types with bounded body ranges. */
  private static List<TypeInfo> typeDeclarations(SourceFile file) {
    String source = maskStringLiterals(file.structuralSource());
    Matcher matcher = TYPE_DECLARATION.matcher(source);
    List<TypeInfo> result = new ArrayList<>();
    while (matcher.find()) {
      int opening = typeBodyOpening(source, matcher.end());
      if (opening < 0) {
        continue;
      }
      int closing = matchingDelimiter(source, opening, '{', '}');
      if (closing < 0) {
        continue;
      }
      result.add(new TypeInfo(matcher.group(1), matcher.group(2), matcher.start(), closing));
    }
    return List.copyOf(result);
  }

  private static List<String> routeAnnotations(
      SourceFile file, String block, AnalysisContext.Framework framework) {
    List<String> candidates = framework == AnalysisContext.Framework.QUARKUS
        ? List.of("POST", "GET", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
        : List.of("PostMapping", "GetMapping", "PutMapping", "PatchMapping", "DeleteMapping", "RequestMapping");
    List<String> result = new ArrayList<>();
    for (String name : candidates) {
      if (framework == AnalysisContext.Framework.QUARKUS
          ? hasAnnotation(block, name)
          : canonicalSpringAnnotation(file, block, name)) {
        result.add(name);
      }
    }
    return List.copyOf(result);
  }

  /** Expands supported HTTP method annotation values. */
  private static List<String> httpMethods(SourceFile file, String block, String annotationName) {
    List<String> result = new ArrayList<>();
    switch (annotationName) {
      case "POST", "PostMapping" -> result.add("POST");
      case "GET", "GetMapping" -> result.add("GET");
      case "PUT", "PutMapping" -> result.add("PUT");
      case "PATCH", "PatchMapping" -> result.add("PATCH");
      case "DELETE", "DeleteMapping" -> result.add("DELETE");
      case "HEAD" -> result.add("HEAD");
      case "OPTIONS" -> result.add("OPTIONS");
      case "RequestMapping" -> {
        List<String> arguments = annotationArguments(file, block, annotationName);
        boolean wildcard = arguments.isEmpty();
        for (String args : arguments) {
          String selected = attributeValue(args, "method");
          if (selected == null) {
            wildcard = true;
            continue;
          }
          Matcher matcher = Pattern.compile("RequestMethod\\.(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)")
              .matcher(selected);
          while (matcher.find()) {
            result.add(matcher.group(1));
          }
        }
        if (wildcard) {
          result.addAll(SUPPORTED_HTTP_METHODS);
        }
      }
      default -> {
        // The bounded route subset intentionally ignores custom HTTP annotations.
      }
    }
    return List.copyOf(new LinkedHashSet<>(result));
  }

  private static List<String> intersectHttpMethods(
      List<String> methodMethods, List<String> classMethods) {
    Set<String> classMethodSet = new LinkedHashSet<>(classMethods);
    return methodMethods.stream()
        .filter(classMethodSet::contains)
        .toList();
  }

  /** Extracts literal route path values from an annotation. */
  private static List<String> pathValues(String block, String annotation) {
    List<String> result = new ArrayList<>();
    for (String args : annotationArguments(block, annotation)) {
      String selected = selectedValue(args, "value");
      Optional<List<String>> literals = literalPathValues(selected);
      if (literals.isEmpty()) {
        return List.of();
      }
      result.addAll(literals.get());
    }
    return List.copyOf(new LinkedHashSet<>(result));
  }

  /** Extracts literal Spring mapping paths from value and path attributes. */
  private static List<String> mappingPaths(SourceFile file, String block, String annotation) {
    List<String> arguments = annotationArguments(file, block, annotation);
    if (arguments.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (String args : arguments) {
      for (String attribute : List.of("value", "path")) {
        String selected = attributeValue(args, attribute);
        if (selected == null) {
          continue;
        }
        Optional<List<String>> literals = literalPathValues(selected);
        if (literals.isEmpty()) {
          return List.of();
        }
        result.addAll(literals.get());
      }
    }
    if (!result.isEmpty()) {
      return List.copyOf(result);
    }
    return hasExplicitMappingPath(file, block, annotation) ? List.of() : List.of("");
  }

  private static boolean hasExplicitMappingPath(SourceFile file, String block, String annotation) {
    return annotationArguments(file, block, annotation).stream()
        .anyMatch(args -> attributeValue(args, "value") != null || attributeValue(args, "path") != null);
  }

  /** Parses a single literal or literal array route expression. */
  private static Optional<List<String>> literalPathValues(String expression) {
    String selected = expression == null ? "" : expression.trim();
    if (selected.startsWith("{") && selected.endsWith("}")
        && matchingDelimiter(selected, 0, '{', '}') == selected.length() - 1) {
      String body = selected.substring(1, selected.length() - 1).trim();
      if (body.isEmpty()) {
        return Optional.of(List.of());
      }
      List<String> result = new ArrayList<>();
      for (String item : splitArguments(body)) {
        Optional<String> literal = literalString(item.trim());
        if (literal.isEmpty()) {
          return Optional.empty();
        }
        result.add(literal.get());
      }
      return Optional.of(List.copyOf(result));
    }
    Optional<String> literal = literalString(selected);
    return literal.map(List::of);
  }

  /** Returns a Java annotation string only when its value is a complete literal. */
  private static Optional<String> literalString(String expression) {
    if (expression.length() < 2 || expression.charAt(0) != '"'
        || expression.charAt(expression.length() - 1) != '"') {
      return Optional.empty();
    }
    boolean escaped = false;
    for (int index = 1; index < expression.length() - 1; index++) {
      char character = expression.charAt(index);
      if (character == '"' && !escaped) {
        return Optional.empty();
      }
      if (character == '\\' && !escaped) {
        escaped = true;
      } else {
        escaped = false;
      }
    }
    return Optional.of(expression.substring(1, expression.length() - 1));
  }

  private static String routeKey(
      SourceFile file,
      MethodInfo method,
      String httpMethod,
      String path,
      List<String> consumes,
      List<String> produces,
      List<String> headers,
      List<String> parameters) {
    return String.join("|",
        file.displayPath(),
        file.typeName(),
        method.name(),
        method.parameters(),
        httpMethod,
        path,
        String.join(",", consumes),
        String.join(",", produces),
        String.join(",", headers),
        String.join(",", parameters));
  }

  private static List<String> routeSymbols(
      SourceFile file,
      String classBlock,
      String methodBlock,
      AnalysisContext.Framework framework,
      String annotationName) {
    List<String> symbols = new ArrayList<>();
    if (framework == AnalysisContext.Framework.QUARKUS) {
      addSymbols(symbols, classBlock, "Path", "Consumes", "Produces");
      addSymbols(symbols, methodBlock, "Path", "Consumes", "Produces");
    } else {
      addCanonicalSymbols(symbols, file, classBlock, "RequestMapping", "RestController");
    }
    if (framework == AnalysisContext.Framework.QUARKUS) {
      addSymbols(symbols, methodBlock, annotationName);
    } else {
      addCanonicalSymbols(symbols, file, methodBlock, annotationName);
    }
    return List.copyOf(symbols);
  }

  /** Adds source annotation spellings to evidence symbols. */
  private static void addSymbols(List<String> symbols, String block, String... names) {
    for (String name : names) {
      for (AnnotationOccurrence occurrence : annotationOccurrences(block, name)) {
        symbols.add(occurrence.source());
      }
    }
  }

  /** Adds only canonically resolved Spring annotation spellings to evidence. */
  private static void addCanonicalSymbols(
      List<String> symbols, SourceFile file, String block, String... names) {
    for (String name : names) {
      for (AnnotationOccurrence occurrence : canonicalSpringAnnotationOccurrences(file, block, name)) {
        symbols.add(occurrence.source());
      }
    }
  }

  /** Recognizes the bounded Spring component stereotype subset. */
  private static boolean springComponent(SourceFile file) {
    String block = file.classAnnotationBlock();
    return canonicalAnnotation(file, block, "Service", "org.springframework.stereotype.Service")
        || canonicalAnnotation(file, block, "Component", "org.springframework.stereotype.Component")
        || canonicalAnnotation(file, block, "Repository", "org.springframework.stereotype.Repository")
        || canonicalAnnotation(
            file, block, "Configuration", "org.springframework.context.annotation.Configuration")
        || canonicalAnnotation(file, block, "Controller", "org.springframework.stereotype.Controller")
        || canonicalAnnotation(
            file, block, "RestController", "org.springframework.web.bind.annotation.RestController")
        || canonicalAnnotation(file, block, "Named", "jakarta.inject.Named", "javax.inject.Named");
  }

  /** Recognizes CDI scopes, stereotypes, and named beans. */
  private static boolean cdiBean(String block) {
    return CDI_SCOPES.stream().anyMatch(name -> hasAnnotation(block, name))
        || hasAnnotation(block, "Stereotype")
        || hasAnnotation(block, "Named");
  }

  /** Recognizes common CDI and Spring injection annotations. */
  private static boolean hasInjectionAnnotation(String block) {
    return hasAnnotation(block, "Inject")
        || hasAnnotation(block, "Autowired")
        || hasAnnotation(block, "Resource");
  }

  /** Extracts resolved implemented interfaces and the direct superclass from one type header. */
  private Set<String> contracts(SourceFile file) {
    Set<String> contracts = new LinkedHashSet<>();
    String header = typeHeader(file);
    String normalized = stripBalancedGenericArguments(header);
    int implementsIndex = keywordIndex(normalized, "implements");
    int extendsIndex = keywordIndex(normalized, "extends");
    if (implementsIndex >= 0) {
      int end = keywordIndexAfter(normalized, "permits", implementsIndex + "implements".length());
      String implemented = normalized.substring(
          implementsIndex + "implements".length(), end >= 0 ? end : normalized.length());
      addResolvedContracts(contracts, implemented, file);
    }
    if (extendsIndex >= 0) {
      int end = implementsIndex >= 0 ? implementsIndex : keywordIndexAfter(
          normalized, "permits", extendsIndex + "extends".length());
      String extended = normalized.substring(
          extendsIndex + "extends".length(), end >= 0 ? end : normalized.length());
      List<String> extendedTypes = typeNames(extended);
      if (normalized.matches("(?s).*\\binterface\\s+\\w+.*")) {
        addResolvedContracts(contracts, String.join(",", extendedTypes), file);
      } else if (!extendedTypes.isEmpty()) {
        resolveContractType(extendedTypes.get(0), file).ifPresent(contracts::add);
      }
    }
    return Set.copyOf(contracts);
  }

  /** Resolves each comma-separated contract against the declaring source file. */
  private void addResolvedContracts(Set<String> contracts, String source, SourceFile file) {
    for (String type : typeNames(source)) {
      resolveContractType(type, file).ifPresent(contracts::add);
    }
  }

  /** Returns the declaration header before its top-level body. */
  private static String typeHeader(SourceFile file) {
    String source = file.structuralSource();
    int angleDepth = 0;
    int parenthesisDepth = 0;
    int bracketDepth = 0;
    for (int index = file.typeDeclarationStart(); index < source.length(); index++) {
      char character = source.charAt(index);
      if (character == '<') {
        angleDepth++;
      } else if (character == '>' && angleDepth > 0) {
        angleDepth--;
      } else if (character == '(') {
        parenthesisDepth++;
      } else if (character == ')' && parenthesisDepth > 0) {
        parenthesisDepth--;
      } else if (character == '[') {
        bracketDepth++;
      } else if (character == ']' && bracketDepth > 0) {
        bracketDepth--;
      } else if (character == '{' && angleDepth == 0 && parenthesisDepth == 0 && bracketDepth == 0) {
        return source.substring(file.typeDeclarationStart(), index);
      }
    }
    return source.substring(file.typeDeclarationStart());
  }

  /** Finds a word boundary keyword in a declaration header. */
  private static int keywordIndex(String source, String keyword) {
    return keywordIndexAfter(source, keyword, 0);
  }

  /** Finds a declaration keyword outside generic, parameter, and array delimiters. */
  private static int topLevelKeywordIndex(String source, String keyword) {
    int angleDepth = 0;
    int parenthesisDepth = 0;
    int bracketDepth = 0;
    for (int index = 0; index <= source.length() - keyword.length(); index++) {
      char character = source.charAt(index);
      if (character == '<') {
        angleDepth++;
      } else if (character == '>') {
        angleDepth = Math.max(0, angleDepth - 1);
      } else if (character == '(') {
        parenthesisDepth++;
      } else if (character == ')') {
        parenthesisDepth = Math.max(0, parenthesisDepth - 1);
      } else if (character == '[') {
        bracketDepth++;
      } else if (character == ']') {
        bracketDepth = Math.max(0, bracketDepth - 1);
      }
      if (angleDepth == 0 && parenthesisDepth == 0 && bracketDepth == 0
          && source.startsWith(keyword, index)
          && (index == 0 || !Character.isJavaIdentifierPart(source.charAt(index - 1)))
          && (index + keyword.length() == source.length()
              || !Character.isJavaIdentifierPart(source.charAt(index + keyword.length())))) {
        return index;
      }
    }
    return -1;
  }

  /** Finds a word boundary keyword after a declaration-header offset. */
  private static int keywordIndexAfter(String source, String keyword, int offset) {
    Matcher matcher = Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b")
        .matcher(source);
    return matcher.find(offset) ? matcher.start() : -1;
  }

  /** Extracts profile values from an annotation block. */
  private static List<String> profileValues(String block) {
    return values(block, "Profile", null);
  }

  /** Extracts supported conditional registration annotations. */
  private static List<String> conditionAnnotations(String block) {
    List<String> result = new ArrayList<>();
    for (String name : List.of(
        "Conditional", "ConditionalOnBean", "ConditionalOnMissingBean", "ConditionalOnClass",
        "ConditionalOnMissingClass", "ConditionalOnProperty", "ConditionalOnExpression")) {
      for (AnnotationOccurrence occurrence : annotationOccurrences(block, name)) {
        result.add(occurrence.source());
      }
    }
    return List.copyOf(result);
  }

  /** Extracts normalized qualifiers after removing comments and ordinary annotations. */
  private static List<String> qualifierNames(String block) {
    String source = stripComments(block == null ? "" : block);
    List<String> result = new ArrayList<>();
    Matcher matcher = Pattern.compile("@([A-Za-z_][\\w.]*)").matcher(source);
    while (matcher.find()) {
      String name = simpleName(matcher.group(1));
      int end = matcher.end();
      while (end < source.length() && Character.isWhitespace(source.charAt(end))) {
        end++;
      }
      String arguments = "";
      if (end < source.length() && source.charAt(end) == '(') {
        int closing = matchingDelimiter(source, end, '(', ')');
        if (closing >= 0) {
          arguments = source.substring(end + 1, closing).trim();
        }
      }
      if (name.equals("Resource")) {
        String value = attributeValue(arguments, "name");
        if (value == null) {
          value = attributeValue(arguments, "value");
        }
        value = quotedValue(value);
        if (!value.isBlank()) {
          result.add("Resource:" + value);
        }
        continue;
      }
      if (Set.of(
          "Service", "Component", "Repository", "Controller", "RestController", "Configuration",
          "Bean").contains(name)) {
        String value = attributeValue(arguments, "value");
        if (value == null) {
          value = attributeValue(arguments, "name");
        }
        value = quotedValue(value);
        if (!value.isBlank()) {
          result.add("BeanName:" + value);
        }
        continue;
      }
      if (NON_QUALIFIER_ANNOTATIONS.contains(name)) {
        continue;
      }
      if (name.equals("Qualifier") || name.equals("Named")) {
        String value = quotedValue(arguments.replaceAll("^value\\s*=\\s*", "").trim());
        result.add(name + ":" + value);
      } else {
        result.add(name);
      }
    }
    return List.copyOf(new LinkedHashSet<>(result));
  }

  /** Adds explicit or implicit resource-name qualifiers for injection points. */
  private static List<String> injectionQualifiers(
      String annotations,
      String defaultName,
      boolean cdi) {
    List<String> result = new ArrayList<>(qualifierNames(annotations));
    if (cdi && hasAnnotation(annotations, "Named")) {
      for (int index = 0; index < result.size(); index++) {
        if (result.get(index).equals("Named:") && !defaultName.isBlank()) {
          result.set(index, "Named:" + defaultName);
        }
      }
    }
    if (hasAnnotation(annotations, "Resource")
        && result.stream().noneMatch(value -> value.startsWith("Resource:"))) {
      String name = defaultName;
      if (name.startsWith("set") && name.length() > 3) {
        name = Character.toLowerCase(name.charAt(3)) + name.substring(4);
      }
      result.add("ResourceDefault:" + name);
    }
    return List.copyOf(result);
  }

  /** Extracts the first quoted annotation value or returns its trimmed expression. */
  private static String quotedValue(String expression) {
    if (expression == null || expression.isBlank()) {
      return "";
    }
    Matcher quoted = QUOTED.matcher(expression.trim());
    return quoted.find() ? quoted.group(1) : expression.trim();
  }

  /** Extracts the outer return type, retaining generic arguments for producer matching. */
  private static String returnType(String declaration) {
    String cleaned = maskAnnotationArguments(stripComments(declaration))
        .replaceAll("@[A-Za-z_][\\w.]*", " ")
        .replaceAll("\\b(public|protected|private|static|final|synchronized|abstract|native|default|strictfp)\\b", " ")
        .trim();
    cleaned = compactGenericWhitespace(cleaned);
    if (cleaned.isBlank()) {
      return "";
    }
    String[] parts = cleaned.split("\\s+");
    return parts[parts.length - 1].trim();
  }

  /** Removes whitespace inside nested generic arguments without changing declarations. */
  private static String compactGenericWhitespace(String source) {
    StringBuilder result = new StringBuilder(source.length());
    int depth = 0;
    for (int index = 0; index < source.length(); index++) {
      char character = source.charAt(index);
      if (character == '<') {
        depth++;
        result.append(character);
      } else if (character == '>' && depth > 0) {
        depth--;
        result.append(character);
      } else if (Character.isWhitespace(character) && depth > 0) {
        continue;
      } else {
        result.append(character);
      }
    }
    return result.toString();
  }

  /** Classifies a source bean using the bounded framework stereotype subset. */
  private static String beanKind(String block, AnalysisContext.Framework framework) {
    if (framework == AnalysisContext.Framework.QUARKUS) {
      for (String annotation : List.of(
          "ApplicationScoped", "RequestScoped", "SessionScoped", "ConversationScoped",
          "Dependent", "Singleton", "Named", "Stereotype")) {
        if (hasAnnotation(block, annotation)) {
          return annotation;
        }
      }
    }
    for (String annotation : List.of(
        "Service", "Component", "Repository", "Configuration", "Controller", "RestController", "Named")) {
      if (hasAnnotation(block, annotation)) {
        return annotation;
      }
    }
    return "repository_interface";
  }

  /** Returns source annotation spellings that establish one candidate's registration. */
  List<String> sourceAnnotations(BeanCandidate candidate, AnalysisContext.Framework framework) {
    if (candidate.producer()) {
      String block = methods(candidate.file()).stream()
          .filter(method -> method.name().equals(candidate.producerMethod()))
          .map(MethodInfo::annotationBlock)
          .findFirst()
          .orElse("");
      return annotationSources(block, framework == AnalysisContext.Framework.QUARKUS
          ? List.of("Produces")
          : List.of("Bean"));
    }
    List<String> names = framework == AnalysisContext.Framework.QUARKUS
        ? List.of("ApplicationScoped", "RequestScoped", "SessionScoped", "ConversationScoped",
            "Dependent", "Singleton", "Named", "Stereotype")
        : List.of("Service", "Component", "Repository", "Configuration", "Controller",
            "RestController", "Named");
    return annotationSources(candidate.file().classAnnotationBlock(), names);
  }

  /** Returns source advisor or scope annotations that establish one proxy target. */
  List<String> sourceAnnotations(ProxyTarget target, AnalysisContext.Framework framework) {
    List<String> names = framework == AnalysisContext.Framework.QUARKUS
        ? List.of("ApplicationScoped", "RequestScoped", "SessionScoped", "ConversationScoped",
            "Dependent", "Singleton", "Named", "Stereotype")
        : List.of("Transactional", "Async", "Cacheable", "CachePut", "CacheEvict",
            "PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed", "Retryable",
            "CircuitBreaker", "Validated", "TransactionalEventListener");
    List<String> result = framework == AnalysisContext.Framework.QUARKUS
        ? new ArrayList<>(annotationSources(target.file().classAnnotationBlock(), names))
        : new ArrayList<>(canonicalAnnotationSources(
            target.file(), target.file().classAnnotationBlock(), names));
    if (framework == AnalysisContext.Framework.QUARKUS) {
      result.addAll(annotationSources(target.method().annotationBlock(), names));
    } else {
      result.addAll(canonicalAnnotationSources(
          target.file(), target.method().annotationBlock(), names));
    }
    return List.copyOf(new LinkedHashSet<>(result));
  }

  /** Extracts exact source spellings for a bounded set of annotation names. */
  private static List<String> annotationSources(String block, List<String> names) {
    List<String> result = new ArrayList<>();
    for (String name : names) {
      result.addAll(annotationOccurrences(block, name).stream()
          .map(AnnotationOccurrence::source)
          .toList());
    }
    return List.copyOf(new LinkedHashSet<>(result));
  }

  /** Extracts source spellings for canonically resolved Spring annotations. */
  private static List<String> canonicalAnnotationSources(
      SourceFile file, String block, List<String> names) {
    List<String> result = new ArrayList<>();
    for (String name : names) {
      result.addAll(canonicalSpringAnnotationOccurrences(file, block, name).stream()
          .map(AnnotationOccurrence::source)
          .toList());
    }
    return List.copyOf(new LinkedHashSet<>(result));
  }

  /** Parses method or constructor parameters and their qualifier annotations. */
  static List<Parameter> parameters(String source) {
    List<Parameter> result = new ArrayList<>();
    for (String raw : splitArguments(source)) {
      List<String> qualifiers = qualifierNames(raw);
      String cleaned = raw.replaceAll("@[A-Za-z_][\\w.]*(?:\\s*\\([^)]*\\))?", " ").trim();
      cleaned = cleaned.replaceAll("\\b(final|volatile|transient)\\b", "").trim();
      if (cleaned.isBlank()) {
        continue;
      }
      int separator = cleaned.lastIndexOf(' ');
      if (separator < 0) {
        continue;
      }
      String type = cleaned.substring(0, separator).trim();
      String name = cleaned.substring(separator + 1).trim();
      if (type.endsWith("...")) {
        type = type.substring(0, type.length() - 3) + "[]";
      }
      result.add(new Parameter(type, name, qualifiers));
    }
    return List.copyOf(result);
  }

  private static List<String> typeNames(String source) {
    List<String> result = new ArrayList<>();
    for (String raw : splitArguments(source)) {
      String type = stripBalancedGenericArguments(raw.trim()).trim();
      if (!type.isBlank()) {
        result.add(type);
      }
    }
    return result;
  }

  /** Splits comma-separated Java expressions while respecting nested delimiters. */
  private static List<String> splitArguments(String source) {
    List<String> result = new ArrayList<>();
    int depth = 0;
    int start = 0;
    boolean quoted = false;
    boolean escaped = false;
    for (int index = 0; index < source.length(); index++) {
      char character = source.charAt(index);
      if (character == '"' && !escaped) {
        quoted = !quoted;
      }
      if (quoted) {
        escaped = character == '\\' && !escaped;
        continue;
      }
      escaped = false;
      if (character == '<' || character == '{' || character == '(') {
        depth++;
      } else if (character == '>' || character == '}' || character == ')') {
        depth--;
      } else if (character == ',' && depth == 0) {
        result.add(source.substring(start, index));
        start = index + 1;
      }
    }
    result.add(source.substring(start));
    return result;
  }

  private static boolean hasAnnotation(String block, String name) {
    return !annotationOccurrences(block, name).isEmpty();
  }

  /** Recognizes an annotation only when its source spelling resolves canonically. */
  private static boolean canonicalAnnotation(
      SourceFile file, String block, String simpleName, String... canonicalNames) {
    return !canonicalAnnotationOccurrences(file, block, simpleName, Set.of(canonicalNames)).isEmpty();
  }

  /** Resolves a supported Spring annotation without accepting lookalike or wildcard imports. */
  private static boolean canonicalSpringAnnotation(SourceFile file, String block, String simpleName) {
    return !canonicalSpringAnnotationOccurrences(file, block, simpleName).isEmpty();
  }

  /** Returns only annotation occurrences whose source spelling resolves canonically. */
  private static List<AnnotationOccurrence> canonicalSpringAnnotationOccurrences(
      SourceFile file, String block, String simpleName) {
    return canonicalAnnotationOccurrences(
        file,
        block,
        simpleName,
        Set.copyOf(SPRING_ROUTE_ANNOTATION_TYPES.getOrDefault(
            simpleName,
            SPRING_PROXY_ANNOTATION_TYPES.getOrDefault(simpleName, List.of()))));
  }

  private static List<AnnotationOccurrence> canonicalAnnotationOccurrences(
      SourceFile file, String block, String simpleName, Set<String> canonical) {
    List<String> explicitImports = importedTypes(simpleName, file);
    boolean canonicalImport = explicitImports.stream().anyMatch(canonical::contains);
    return annotationOccurrences(block, simpleName).stream()
        .filter(occurrence -> canonical.contains(annotationType(occurrence.source()))
            || annotationType(occurrence.source()).equals(simpleName) && canonicalImport)
        .toList();
  }

  /** Checks a method declaration modifier without reading annotation arguments as modifiers. */
  private static boolean hasModifier(String block, String modifier) {
    String structural = maskAnnotationArguments(stripComments(block == null ? "" : block));
    return Pattern.compile("\\b" + Pattern.quote(modifier) + "\\b").matcher(structural).find();
  }

  /** Keeps only real, interceptable Spring method declarations from the bounded method scan. */
  private static boolean springProxyMethod(SourceFile file, MethodInfo method) {
    if (method.name().equals(file.simpleTypeName())
        || hasModifier(method.annotationBlock(), "private")
        || hasModifier(method.annotationBlock(), "static")) {
      return false;
    }
    String prefix = maskAnnotationArguments(stripComments(method.annotationBlock())).trim();
    return !prefix.matches("(?s)^(return|throw|if|for|while|switch|catch|new|assert|case|else|do)\\b.*");
  }

  /** Recognizes only the CDI producer annotation, not JAX-RS response producers. */
  private static boolean cdiProducerAnnotation(SourceFile file, String block) {
    return annotationOccurrences(block, "Produces").stream()
        .map(AnnotationOccurrence::source)
        .map(SourceMetadataScanner::annotationType)
        .anyMatch(annotation -> CDI_PRODUCER_ANNOTATIONS.contains(annotation)
            || annotation.equals("Produces") && importsCdiProducer(file));
  }

  /** Checks whether a simple {@code @Produces} resolves to a CDI import. */
  private static boolean importsCdiProducer(SourceFile file) {
    Matcher matcher = Pattern.compile("(?m)^\\s*import\\s+([A-Za-z_][\\w.]*(?:\\.\\*)?)\\s*;")
        .matcher(file.structuralSource());
    boolean explicitCdi = false;
    boolean explicitOther = false;
    boolean cdiWildcard = false;
    while (matcher.find()) {
      String imported = matcher.group(1);
      if (CDI_PRODUCER_ANNOTATIONS.contains(imported)) {
        explicitCdi = true;
      } else if (imported.endsWith(".Produces")) {
        explicitOther = true;
      }
      if (imported.equals("jakarta.enterprise.inject.*")
          || imported.equals("javax.enterprise.inject.*")) {
        cdiWildcard = true;
      }
    }
    if (explicitOther) {
      return false;
    }
    if (explicitCdi) {
      return true;
    }
    return cdiWildcard;
  }

  /** Extracts the annotation type name from its source spelling. */
  private static String annotationType(String source) {
    String normalized = source == null ? "" : source.trim();
    if (normalized.startsWith("@")) {
      normalized = normalized.substring(1);
    }
    int opening = normalized.indexOf('(');
    return opening < 0 ? normalized : normalized.substring(0, opening).trim();
  }

  private static List<String> values(String block, String annotation, String attribute) {
    return values(annotationArguments(block, annotation), attribute);
  }

  /** Extracts values only from canonically resolved Spring route annotations. */
  private static List<String> values(
      SourceFile file, String block, String annotation, String attribute) {
    return values(annotationArguments(file, block, annotation), attribute);
  }

  private static List<String> values(List<String> arguments, String attribute) {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (String args : arguments) {
      String selected = selectedValue(args, attribute);
      if (selected.isBlank()) {
        continue;
      }
      Matcher quoted = QUOTED.matcher(selected);
      boolean foundQuoted = false;
      while (quoted.find()) {
        foundQuoted = true;
        result.add(quoted.group(1).trim());
      }
      if (foundQuoted) {
        continue;
      }
      for (String raw : splitArguments(selected.replace("{", "").replace("}", ""))) {
        String normalized = raw.trim();
        if (!normalized.isBlank()) {
          result.add(normalized);
        }
      }
    }
    return List.copyOf(result);
  }

  private static List<String> mediaValues(String block, String annotation, String attribute) {
    return mediaValues(values(block, annotation, attribute));
  }

  /** Extracts media values only from canonically resolved Spring route annotations. */
  private static List<String> mediaValues(
      SourceFile file, String block, String annotation, String attribute) {
    return mediaValues(values(file, block, annotation, attribute));
  }

  private static List<String> mediaValues(List<String> values) {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (String value : values) {
      String normalized = mediaType(value);
      if (!normalized.isBlank()) {
        result.add(normalized);
      }
    }
    return List.copyOf(result);
  }

  private static String attributeValue(String args, String attribute) {
    List<String> parts = splitArguments(args);
    for (String part : parts) {
      int equals = part.indexOf('=');
      if (equals >= 0 && part.substring(0, equals).trim().matches("[A-Za-z_][\\w]*")
          && part.substring(0, equals).trim().equals(attribute)) {
        return part.substring(equals + 1).trim();
      }
    }
    return attribute.equals("value") && !args.isBlank() && parts.stream().noneMatch(
        part -> part.indexOf('=') >= 0 && part.substring(0, part.indexOf('=')).trim().matches("[A-Za-z_][\\w]*"))
        ? args.trim()
        : null;
  }

  private static String selectedValue(String args, String attribute) {
    if (attribute == null) {
      String explicit = attributeValue(args, "value");
      return explicit == null ? args.trim() : explicit;
    }
    String selected = attributeValue(args, attribute);
    return selected == null ? "" : selected;
  }

  private static List<String> annotationArguments(String block, String name) {
    return annotationOccurrences(block, name).stream().map(AnnotationOccurrence::arguments).toList();
  }

  /** Extracts arguments only from canonically resolved Spring route annotations. */
  private static List<String> annotationArguments(SourceFile file, String block, String name) {
    return canonicalSpringAnnotationOccurrences(file, block, name).stream()
        .map(AnnotationOccurrence::arguments)
        .toList();
  }

  /** Finds annotation occurrences with balanced argument extraction. */
  private static List<AnnotationOccurrence> annotationOccurrences(String block, String name) {
    List<AnnotationOccurrence> result = new ArrayList<>();
    String source = stripComments(block == null ? "" : block);
    String searchSource = maskStringLiterals(source);
    Matcher matcher = Pattern.compile(
        "@(?:[A-Za-z_][\\w.]*\\.)?" + Pattern.quote(name) + "\\b").matcher(searchSource);
    while (matcher.find()) {
      int end = matcher.end();
      while (end < source.length() && Character.isWhitespace(source.charAt(end))) {
        end++;
      }
      String arguments = "";
      if (end < source.length() && source.charAt(end) == '(') {
        int closing = matchingDelimiter(source, end, '(', ')');
        if (closing < 0) {
          continue;
        }
        arguments = source.substring(end + 1, closing);
        end = closing + 1;
      }
      result.add(new AnnotationOccurrence(
          arguments,
          source.substring(matcher.start(), end).replaceAll("\\s+", " ").trim()));
    }
    return List.copyOf(result);
  }

  /** Replaces comments with whitespace without changing source offsets or literals. */
  private static String stripComments(String source) {
    StringBuilder result = new StringBuilder(source.length());
    boolean lineComment = false;
    boolean blockComment = false;
    boolean stringLiteral = false;
    boolean textBlock = false;
    boolean characterLiteral = false;
    boolean escaped = false;
    for (int index = 0; index < source.length(); index++) {
      char current = source.charAt(index);
      char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
      if (lineComment) {
        if (current == '\n' || current == '\r') {
          lineComment = false;
          result.append(current);
        } else {
          result.append(' ');
        }
        continue;
      }
      if (blockComment) {
        if (current == '*' && next == '/') {
          result.append("  ");
          index++;
          blockComment = false;
        } else if (current == '\n' || current == '\r') {
          result.append(current);
        } else {
          result.append(' ');
        }
        continue;
      }
      if (textBlock) {
        if (source.startsWith("\"\"\"", index) && !escaped) {
          result.append("\"\"\"");
          index += 2;
          textBlock = false;
          continue;
        }
        result.append(current);
        if (current == '\\' && !escaped) {
          escaped = true;
        } else {
          escaped = false;
        }
        continue;
      }
      if (!stringLiteral && !characterLiteral && current == '/' && next == '/') {
        result.append("  ");
        index++;
        lineComment = true;
        continue;
      }
      if (!stringLiteral && !characterLiteral && current == '/' && next == '*') {
        result.append("  ");
        index++;
        blockComment = true;
        continue;
      }
      if (!stringLiteral && !characterLiteral && source.startsWith("\"\"\"", index)) {
        result.append("\"\"\"");
        index += 2;
        textBlock = true;
        escaped = false;
        continue;
      }
      result.append(current);
      if (current == '\\' && (stringLiteral || characterLiteral) && !escaped) {
        escaped = true;
      } else {
        if (current == '"' && !characterLiteral && !escaped) {
          stringLiteral = !stringLiteral;
        } else if (current == '\'' && !stringLiteral && !escaped) {
          characterLiteral = !characterLiteral;
        }
        escaped = false;
      }
    }
    return result.toString();
  }

  /** Masks string and character literal contents while preserving source offsets. */
  private static String maskStringLiterals(String source) {
    char[] masked = source.toCharArray();
    boolean stringLiteral = false;
    boolean textBlock = false;
    boolean characterLiteral = false;
    boolean escaped = false;
    for (int index = 0; index < source.length(); index++) {
      char current = source.charAt(index);
      if (textBlock) {
        if (source.startsWith("\"\"\"", index) && !escaped) {
          masked[index] = ' ';
          masked[index + 1] = ' ';
          masked[index + 2] = ' ';
          index += 2;
          textBlock = false;
          continue;
        }
        if (current != '\n' && current != '\r') {
          masked[index] = ' ';
        }
        if (current == '\\' && !escaped) {
          escaped = true;
        } else {
          escaped = false;
        }
        continue;
      }
      if (!stringLiteral && !characterLiteral && source.startsWith("\"\"\"", index)) {
        masked[index] = ' ';
        masked[index + 1] = ' ';
        masked[index + 2] = ' ';
        index += 2;
        textBlock = true;
        escaped = false;
        continue;
      }
      if (stringLiteral || characterLiteral) {
        if ((stringLiteral && current == '"' || characterLiteral && current == '\'') && !escaped) {
          stringLiteral = false;
          characterLiteral = false;
          continue;
        }
        if (current == '\\' && !escaped) {
          escaped = true;
          masked[index] = ' ';
          continue;
        }
        if (current != '\n' && current != '\r') {
          masked[index] = ' ';
        }
        escaped = false;
        continue;
      }
      if (current == '"') {
        stringLiteral = true;
      } else if (current == '\'') {
        characterLiteral = true;
      }
    }
    return new String(masked);
  }

  /** Removes nested generic arguments from a declaration without leaving trailing brackets. */
  private static String stripBalancedGenericArguments(String source) {
    StringBuilder result = new StringBuilder(source.length());
    int depth = 0;
    for (int index = 0; index < source.length(); index++) {
      char character = source.charAt(index);
      if (character == '<') {
        depth++;
      } else if (character == '>' && depth > 0) {
        depth--;
      } else if (depth == 0) {
        result.append(character);
      }
    }
    return result.toString();
  }

  /** Returns the outermost generic argument text from one declared type. */
  private static String genericArguments(String source) {
    int opening = source.indexOf('<');
    if (opening < 0) {
      return "";
    }
    int closing = matchingDelimiter(source, opening, '<', '>');
    return closing > opening ? source.substring(opening + 1, closing) : "";
  }

  /** Finds the matching delimiter while ignoring quoted strings. */
  private static int matchingDelimiter(String source, int opening, char open, char close) {
    int depth = 0;
    boolean quoted = false;
    boolean textBlock = false;
    boolean characterLiteral = false;
    boolean escaped = false;
    for (int index = opening; index < source.length(); index++) {
      char character = source.charAt(index);
      if (textBlock) {
        if (source.startsWith("\"\"\"", index) && !escaped) {
          index += 2;
          textBlock = false;
          continue;
        }
        if (character == '\\' && !escaped) {
          escaped = true;
        } else {
          escaped = false;
        }
        continue;
      }
      if (!quoted && !characterLiteral && source.startsWith("\"\"\"", index)) {
        index += 2;
        textBlock = true;
        escaped = false;
        continue;
      }
      if (character == '"' && !escaped && !characterLiteral) {
        quoted = !quoted;
      }
      if (character == '\'' && !escaped && !quoted) {
        characterLiteral = !characterLiteral;
      }
      if (quoted || characterLiteral) {
        escaped = character == '\\' && !escaped;
        if (character != '\\') {
          escaped = false;
        }
        continue;
      }
      escaped = false;
      if (character == open) {
        depth++;
      } else if (character == close && --depth == 0) {
        return index;
      }
    }
    return -1;
  }

  /** Normalizes common media type constants and literals. */
  private static String mediaType(String value) {
    String normalized = value.trim();
    String constant = normalized.substring(normalized.lastIndexOf('.') + 1);
    if (constant.endsWith("_VALUE")) {
      constant = constant.substring(0, constant.length() - "_VALUE".length());
    }
    normalized = switch (constant) {
      case "APPLICATION_JSON" -> "application/json";
      case "APPLICATION_XML" -> "application/xml";
      case "APPLICATION_FORM_URLENCODED" -> "application/x-www-form-urlencoded";
      case "MULTIPART_FORM_DATA" -> "multipart/form-data";
      case "TEXT_PLAIN" -> "text/plain";
      case "ALL" -> "*/*";
      default -> normalized;
    };
    if (normalized.endsWith("_VALUE")) {
      normalized = normalized.substring(0, normalized.length() - "_VALUE".length());
    }
    return normalized.toLowerCase(Locale.ROOT);
  }

  /** Merges annotation values without duplicates. */
  private static List<String> mergeValues(List<String> first, List<String> second) {
    LinkedHashSet<String> result = new LinkedHashSet<>(first);
    result.addAll(second);
    return List.copyOf(result);
  }

  private static List<String> overrideValues(List<String> inherited, List<String> declared) {
    return declared.isEmpty() ? inherited : declared;
  }

  /** Joins route path fragments and normalizes their slash boundaries. */
  private static String joinPath(String prefix, String suffix) {
    String left = prefix == null || prefix.isBlank() ? "" : prefix.trim();
    String right = suffix == null || suffix.isBlank() ? "" : suffix.trim();
    if (left.isEmpty() && right.isEmpty()) {
      return "/";
    }
    String joined = (left + "/" + right).replaceAll("/{2,}", "/");
    if (!joined.startsWith("/")) {
      joined = "/" + joined;
    }
    return joined.length() > 1 && joined.endsWith("/") ? joined.substring(0, joined.length() - 1) : joined;
  }

  private static String qualify(String name, String packageName) {
    return name.contains(".") ? name : (packageName.isBlank() ? name : packageName + "." + name);
  }

  private static String simpleName(String value) {
    int dot = value.lastIndexOf('.');
    return dot < 0 ? value : value.substring(dot + 1);
  }

  private record DynamicSite(int offset, String symbol, String target) {}

  private enum ChainedReceiverKind {
    CLASS_FOR_NAME,
    THIS_CLASS_FOR_NAME,
    QUALIFIED_THIS_CLASS_FOR_NAME,
    THIS_FIELD,
    QUALIFIED_THIS_FIELD,
    GET_CLASS,
    CLASS_LITERAL,
    SIMPLE
  }

  private record ChainedReceiver(
      int start, int end, String expression, ChainedReceiverKind kind) {}

  private record ChainedLookup(int methodStart, int closing) {}

  private record TypeInfo(String kind, String name, int start, int end) {}

  private record TypeVariableBounds(List<String> bounds, int declarationOffset) {}

  private record AnonymousType(int start, int end) {}

  private record TypeReference(SourceFile file, TypeInfo type) {}

  private record FieldReference(String type, SourceFile file, int offset) {}

  private record LocalDeclaration(String type, String initializer, int start, int initializerOffset) {}

  private record DeclarationScope(int start, int end) {}

  private record ForScope(int opening, int bodyStart, int end, boolean enhanced) {}

  record SourceFile(
      Path path,
      String displayPath,
      String source,
      String structuralSource,
      String packageName,
      String typeName,
      String simpleTypeName,
      int typeDeclarationStart,
      boolean generated) {
    String classAnnotationBlock() {
      return structuralSource.substring(0, Math.min(typeDeclarationStart, structuralSource.length()));
    }

    int lineAt(int offset) {
      if (offset <= 0) {
        return 1;
      }
      int line = 1;
      for (int index = 0; index < Math.min(offset, source.length()); index++) {
        if (source.charAt(index) == '\n') {
          line++;
        }
      }
      return line;
    }
  }

  record MethodInfo(
      String name,
      String parameters,
      String annotationBlock,
      int start,
      int end,
      int line,
      String returnType) {
    MethodInfo(String name, String parameters, String annotationBlock, int start, int end, int line) {
      this(name, parameters, annotationBlock, start, end, line, "");
    }

    boolean hasAnnotation(String name) {
      return SourceMetadataScanner.hasAnnotation(annotationBlock, name);
    }
  }

  record Route(
      SourceFile file,
      MethodInfo method,
      String httpMethod,
      String path,
      List<String> consumes,
      List<String> produces,
      List<String> headers,
      List<String> parameters,
      List<String> symbols) {}

  record Parameter(String type, String name, List<String> qualifiers) {
    Parameter(String type, String name) {
      this(type, name, List.of());
    }

    Parameter {
      qualifiers = List.copyOf(qualifiers == null ? List.of() : qualifiers);
    }
  }

  record InjectionPoint(
      SourceFile file,
      List<Parameter> parameters,
      int line,
      int sourceOffset,
      String ownerType,
      String member,
      String kind,
      List<String> qualifiers) {
    InjectionPoint(SourceFile file, List<Parameter> parameters, int line) {
      this(file, parameters, line, 0, file.typeName(), "<init>", "constructor", List.of());
    }

    InjectionPoint {
      parameters = List.copyOf(parameters == null ? List.of() : parameters);
      qualifiers = List.copyOf(qualifiers == null ? List.of() : qualifiers);
    }
  }

  record BeanCandidate(
      SourceFile file,
      Set<String> contracts,
      String kind,
      String candidateId,
      String candidateType,
      List<String> qualifiers,
      List<String> profiles,
      List<String> conditions,
      boolean alternative,
      boolean primary,
      String producerMethod,
      String producerSignature,
      String producerParameters) {
    BeanCandidate(SourceFile file, Set<String> contracts, String kind) {
      this(
          file,
          contracts,
          kind,
          file.typeName(),
          file.typeName(),
          List.of(),
          List.of(),
          List.of(),
          false,
          false,
          null,
          null,
          null);
    }

    BeanCandidate {
      contracts = Set.copyOf(contracts == null ? Set.of() : contracts);
      qualifiers = List.copyOf(qualifiers == null ? List.of() : qualifiers);
      profiles = List.copyOf(profiles == null ? List.of() : profiles);
      conditions = List.copyOf(conditions == null ? List.of() : conditions);
    }

    boolean producer() {
      return producerMethod != null && !producerMethod.isBlank();
    }

    String target() {
      return producer()
          ? file.typeName() + "#" + producerMethod
          : file.typeName() + "#<init>";
    }
  }

  record ProxyTarget(
      SourceFile file,
      MethodInfo method,
      String candidateId,
      String proxyKind,
      boolean normalScoped) {
    ProxyTarget(SourceFile file, MethodInfo method) {
      this(file, method, file.typeName(), "source", true);
    }
  }

  private record ConstructorInfo(String annotations, String parameters, int start) {}

  record GeneratedMethod(SourceFile file, MethodInfo method) {}

  record LifecycleMethod(
      SourceFile file,
      MethodInfo method,
      String kind,
      List<String> annotationSymbols) {}

  record RepositoryMethod(String name, String signature, boolean guaranteed) {
    String identity() {
      return guaranteed ? name : signature;
    }
  }

  private record AnnotationOccurrence(String arguments, String source) {}
}
