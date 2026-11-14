package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerState {


static final Pattern PACKAGE = Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;");
static final Pattern TYPE = Pattern.compile("\\b(?:class|interface|record|enum)\\s+(\\w+)");
static final Pattern TYPE_DECLARATION = Pattern.compile(
      "\\b(class|interface|record|enum)\\s+(\\w+)");
static final Pattern NEW_EXPRESSION = Pattern.compile("\\bnew\\b");
static final Pattern METHOD = Pattern.compile(
      "(?m)(?:^|[;{}\\n])\\s*(?:(?:@[A-Za-z_][\\w.]*(?:\\([^)]*\\))?\\s*)|(?:(?:public|protected|private|static|final|synchronized|abstract|native|default|strictfp)\\s+))*(?:<[^>{}]+>\\s+)?[\\w.?$<>\\[\\], ]+\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws [^{;]+)?(?:\\{|;)");
static final Pattern METHOD_BODY_STATEMENT = Pattern.compile(
      "^(?:return|throw|if|for|while|switch|catch|try|synchronized|new|assert|case|else|do)\\b.*");
static final Pattern QUOTED = Pattern.compile("\\\"([^\\\"]*)\\\"");
static final Pattern CLASS_FOR_NAME = Pattern.compile(
      "(?<![\\w$.])\\b([A-Za-z_$][\\w$]*|java\\.lang\\.Class)"
          + "\\s*\\.\\s*forName\\s*\\(");
static final Pattern THIS_CLASS_FOR_NAME = Pattern.compile(
      "(?<![\\w$.])\\bthis\\s*\\.\\s*([A-Za-z_$][\\w$]*)"
          + "\\s*\\.\\s*forName\\s*\\(");
static final Pattern QUALIFIED_THIS_CLASS_FOR_NAME = Pattern.compile(
      "(?<![\\w$.])([A-Za-z_$][\\w$.]*)\\s*\\.\\s*this\\s*\\."
          + "\\s*([A-Za-z_$][\\w$]*)\\s*\\.\\s*forName\\s*\\(");
static final Pattern THIS_FIELD_RECEIVER = Pattern.compile(
      "^this\\s*\\.\\s*([A-Za-z_$][\\w$]*)$");
static final Pattern QUALIFIED_THIS_FIELD_RECEIVER = Pattern.compile(
      "^([A-Za-z_$][\\w$.]*)\\s*\\.\\s*this\\s*\\.\\s*([A-Za-z_$][\\w$]*)$");
static final Pattern CHAINED_THIS_FIELD_RECEIVER = Pattern.compile(
      "(?<![\\w$.])\\bthis\\s*\\.\\s*([A-Za-z_$][\\w$]*)");
static final Pattern CHAINED_QUALIFIED_THIS_FIELD_RECEIVER = Pattern.compile(
      "(?<![\\w$.])([A-Za-z_$][\\w$.]*)\\s*\\.\\s*this\\s*\\.\\s*"
          + "([A-Za-z_$][\\w$]*)");
static final Pattern STATIC_FOR_NAME_IMPORT = Pattern.compile(
      "(?m)^\\s*import\\s+static\\s+java\\.lang\\.Class\\.(?:forName|\\*)\\s*;");
static final Pattern STATIC_FOR_NAME = Pattern.compile("\\bforName\\s*\\(");
static final Pattern RECEIVER_LOAD_CLASS = Pattern.compile(
      "\\b([A-Za-z_$][\\w$]*)\\s*\\.\\s*(loadClass)\\s*\\(");
static final Pattern RECEIVER_METHOD_LOOKUP = Pattern.compile(
      "\\b([A-Za-z_$][\\w$]*)\\s*\\.\\s*(getDeclaredMethod|getMethod)\\s*\\(");
static final Pattern RECEIVER_CONSTRUCTOR_LOOKUP = Pattern.compile(
      "\\b([A-Za-z_$][\\w$]*)\\s*\\.\\s*(getDeclaredConstructor|getConstructor)\\s*\\(");
static final Pattern CONSTRUCTOR_NEW_INSTANCE = Pattern.compile(
      "\\b(Constructor|java\\.lang\\.reflect\\.Constructor)\\s*\\.\\s*newInstance\\s*\\(");
static final Pattern CLASS_NEW_INSTANCE = Pattern.compile(
      "\\b(Class|java\\.lang\\.Class)\\s*\\.\\s*newInstance\\s*\\(");
static final Pattern METHOD_INVOKE = Pattern.compile(
      "\\b([A-Za-z_$][\\w$]*)\\s*\\.\\s*invoke\\s*\\(");
static final Pattern METHOD_TYPE_INVOKE = Pattern.compile(
      "\\b(Method|java\\.lang\\.reflect\\.Method)\\s*\\.\\s*invoke\\s*\\(");
static final Pattern CHAINED_CLASS_FOR_NAME_RECEIVER = Pattern.compile(
      "(?<![\\w$.])([A-Za-z_$][\\w$]*|java\\.lang\\.Class)"
          + "\\s*\\.\\s*forName\\s*\\(");
static final Pattern CHAINED_GET_CLASS_RECEIVER = Pattern.compile(
      "\\bgetClass\\s*\\(\\s*\\)");
static final Pattern CHAINED_CLASS_LITERAL_RECEIVER = Pattern.compile(
      "\\b[A-Za-z_$][\\w$.]*\\s*\\.\\s*class");
static final Pattern CHAINED_SIMPLE_RECEIVER = Pattern.compile(
      "(?<![\\w$.])([A-Za-z_$][\\w$]*)\\s*\\.\\s*"
          + "(?:getDeclaredMethod|getMethod|getDeclaredConstructor|getConstructor)\\s*\\(");
static final Pattern CLASS_FOR_NAME_PREFIX = Pattern.compile(
      "^([A-Za-z_$][\\w$]*|java\\.lang\\.Class)\\s*\\.\\s*forName\\s*\\(");
static final Pattern THIS_RECEIVER_CALL = Pattern.compile(
      "\\bthis\\s*\\.\\s*([A-Za-z_$][\\w$]*)\\s*\\.\\s*"
          + "(loadClass|getDeclaredMethod|getMethod|getDeclaredConstructor|getConstructor|newInstance|invoke)\\s*\\(");
static final Pattern QUALIFIED_THIS_RECEIVER_CALL = Pattern.compile(
      "(?<![\\w$.])([A-Za-z_$][\\w$.]*)\\s*\\.\\s*this\\s*\\."
          + "\\s*([A-Za-z_$][\\w$]*)\\s*\\.\\s*"
          + "(loadClass|getDeclaredMethod|getMethod|getDeclaredConstructor|getConstructor|newInstance|invoke)"
          + "\\s*\\(");
static final Pattern PROXY_INTERFACES = Pattern.compile(
      "new\\s+Class\\s*<[^>]*>\\s*\\[\\s*\\]\\s*\\{([^}]*)\\}");
static final Pattern CLASS_LITERAL = Pattern.compile("([A-Za-z_][\\w.]*)\\.class");
static final Pattern PROXY_CALL = Pattern.compile(
      "(?<![\\w$.])\\b([A-Za-z_$][\\w$]*|java\\.lang\\.reflect\\.Proxy)"
          + "\\s*\\.\\s*newProxyInstance\\s*\\(");
static final Pattern THIS_PROXY_CALL = Pattern.compile(
      "(?<![\\w$.])\\bthis\\s*\\.\\s*([A-Za-z_$][\\w$]*)"
          + "\\s*\\.\\s*newProxyInstance\\s*\\(");
static final Pattern QUALIFIED_THIS_PROXY_CALL = Pattern.compile(
      "(?<![\\w$.])([A-Za-z_$][\\w$.]*)\\s*\\.\\s*this\\s*\\."
          + "\\s*([A-Za-z_$][\\w$]*)\\s*\\.\\s*newProxyInstance\\s*\\(");
static final Pattern UNQUALIFIED_PROXY_CALL = Pattern.compile(
      "\\bnewProxyInstance\\s*\\(");
static final Pattern STATIC_PROXY_IMPORT = Pattern.compile(
      "(?m)^\\s*import\\s+static\\s+java\\.lang\\.reflect\\.Proxy\\.(?:newProxyInstance|\\*)\\s*;");
static final Pattern INJECT_FIELD = Pattern.compile(
      "(?ms)((?:(?:@[A-Za-z_][\\w.]*(?:\\s*\\([^;{}]*\\))?\\s*)+)"
          + "(?:(?:public|protected|private|static|final|transient|volatile)\\s+)*"
          + ")([\\w.$<>?,\\[\\] ]+)\\s+(\\w+)\\s*(?:=[^;]*)?;");
static final Set<String> NON_QUALIFIER_ANNOTATIONS = Set.of(
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
static final Set<String> CDI_SCOPES = Set.of(
      "ApplicationScoped", "RequestScoped", "SessionScoped", "ConversationScoped", "Dependent",
      "Singleton");
static final Set<String> CDI_NORMAL_SCOPES = Set.of(
      "ApplicationScoped", "RequestScoped", "SessionScoped", "ConversationScoped");
static final Set<String> JAVA_LANG_TYPES = Set.of(
      "Appendable", "AutoCloseable", "Boolean", "Byte", "Character", "CharSequence",
      "Class", "Cloneable", "Comparable", "Double", "Enum", "Exception", "Float",
      "ClassLoader", "Integer", "Iterable", "Long", "Math", "Number", "Object", "Record", "Runnable",
      "RuntimeException", "Short", "StrictMath", "String", "Thread", "Throwable", "Void");
static final Map<String, String> KNOWN_CLASSLOADER_SUPERTYPES = Map.of(
      "java.net.URLClassLoader", "java.security.SecureClassLoader",
      "java.security.SecureClassLoader", "java.lang.ClassLoader",
      "jdk.internal.loader.BuiltinClassLoader", "java.security.SecureClassLoader",
      "jdk.internal.loader.ClassLoaders$AppClassLoader", "jdk.internal.loader.BuiltinClassLoader",
      "jdk.internal.loader.ClassLoaders$PlatformClassLoader", "jdk.internal.loader.BuiltinClassLoader");
static final Set<String> KNOWN_JDK_TYPE_NAMES = Set.of(
      "java.lang.Class",
      "java.lang.ClassLoader",
      "java.lang.reflect.Method",
      "java.lang.reflect.Constructor",
      "java.lang.reflect.InvocationHandler",
      "java.lang.reflect.Proxy",
      "java.net.URLClassLoader",
      "java.security.SecureClassLoader");
static final Set<String> JAVA_PRIMITIVE_TYPES = Set.of(
      "boolean", "byte", "char", "short", "int", "long", "float", "double", "void");
static final Set<String> CDI_PRODUCER_ANNOTATIONS = Set.of(
      "jakarta.enterprise.inject.Produces", "javax.enterprise.inject.Produces");
static final Map<String, List<String>> SPRING_ROUTE_ANNOTATION_TYPES = Map.of(
      "RequestMapping", List.of("org.springframework.web.bind.annotation.RequestMapping"),
      "GetMapping", List.of("org.springframework.web.bind.annotation.GetMapping"),
      "PostMapping", List.of("org.springframework.web.bind.annotation.PostMapping"),
      "PutMapping", List.of("org.springframework.web.bind.annotation.PutMapping"),
      "PatchMapping", List.of("org.springframework.web.bind.annotation.PatchMapping"),
      "DeleteMapping", List.of("org.springframework.web.bind.annotation.DeleteMapping"),
      "RestController", List.of("org.springframework.web.bind.annotation.RestController"));
static final Map<String, List<String>> SPRING_PROXY_ANNOTATION_TYPES = Map.ofEntries(
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
static final Map<String, List<String>> SPRING_BEAN_ANNOTATION_TYPES = Map.ofEntries(
      Map.entry("Service", List.of("org.springframework.stereotype.Service")),
      Map.entry("Component", List.of("org.springframework.stereotype.Component")),
      Map.entry("Repository", List.of("org.springframework.stereotype.Repository")),
      Map.entry("Configuration", List.of("org.springframework.context.annotation.Configuration")),
      Map.entry("Controller", List.of("org.springframework.stereotype.Controller")),
      Map.entry("RestController", List.of("org.springframework.web.bind.annotation.RestController")),
      Map.entry("Named", List.of("jakarta.inject.Named", "javax.inject.Named")),
      Map.entry("Bean", List.of("org.springframework.context.annotation.Bean")),
      Map.entry("Qualifier", List.of("org.springframework.beans.factory.annotation.Qualifier")),
      Map.entry("Resource", List.of("jakarta.annotation.Resource", "javax.annotation.Resource")),
      Map.entry("Primary", List.of("org.springframework.context.annotation.Primary")),
      Map.entry("Profile", List.of("org.springframework.context.annotation.Profile")),
      Map.entry("Conditional", List.of("org.springframework.context.annotation.Conditional")),
      Map.entry("ConditionalOnBean", List.of(
          "org.springframework.boot.autoconfigure.condition.ConditionalOnBean")),
      Map.entry("ConditionalOnMissingBean", List.of(
          "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean")),
      Map.entry("ConditionalOnClass", List.of(
          "org.springframework.boot.autoconfigure.condition.ConditionalOnClass")),
      Map.entry("ConditionalOnMissingClass", List.of(
          "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass")),
      Map.entry("ConditionalOnProperty", List.of(
          "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty")),
      Map.entry("ConditionalOnExpression", List.of(
          "org.springframework.boot.autoconfigure.condition.ConditionalOnExpression")),
      Map.entry("Lazy", List.of("org.springframework.context.annotation.Lazy")));
static final Set<String> SPRING_PROXY_ANNOTATIONS =
      Set.copyOf(SPRING_PROXY_ANNOTATION_TYPES.keySet());
static final String SPRING_COMMAND_LINE_RUNNER =
      "org.springframework.boot.CommandLineRunner";
static final Map<String, String> SPRING_DATA_REPOSITORY_CONTRACTS = Map.of(
      "org.springframework.data.repository.Repository", "Repository",
      "org.springframework.data.repository.CrudRepository", "CrudRepository",
      "org.springframework.data.jpa.repository.JpaRepository", "JpaRepository",
      "org.springframework.data.repository.PagingAndSortingRepository", "PagingAndSortingRepository",
      "org.springframework.data.repository.ListCrudRepository", "ListCrudRepository",
      "org.springframework.data.repository.ListPagingAndSortingRepository", "ListPagingAndSortingRepository");
static final Set<String> SPRING_DATA_FIND_BY_ID_CONTRACTS = Set.of(
      "CrudRepository", "JpaRepository", "PagingAndSortingRepository", "ListCrudRepository",
      "ListPagingAndSortingRepository");
static final List<String> SUPPORTED_HTTP_METHODS =
      List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

  final AnalysisContext context;
  List<SourceFile> files;
  Set<String> declaredTypeNames;
  SourceMetadataScannerState(AnalysisContext context) {
    this.context = Objects.requireNonNull(context, "analysis context");
  }

  abstract SourceFile read(Path path, AnalysisContext.SourceRoot root);


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

  record DynamicSite(int offset, String symbol, String target) {}

  enum ChainedReceiverKind {
    CLASS_FOR_NAME,
    THIS_CLASS_FOR_NAME,
    QUALIFIED_THIS_CLASS_FOR_NAME,
    THIS_FIELD,
    QUALIFIED_THIS_FIELD,
    GET_CLASS,
    CLASS_LITERAL,
    SIMPLE
  }

  record ChainedReceiver(
      int start, int end, String expression, ChainedReceiverKind kind) {}

  record ChainedLookup(int methodStart, int closing) {}

  record TypeInfo(String kind, String name, int start, int end) {}

  record TypeVariableBounds(List<String> bounds, int declarationOffset) {}

  record AnonymousType(int start, int end) {}

  record TypeReference(SourceFile file, TypeInfo type) {}

  record FieldReference(String type, SourceFile file, int offset) {}

  record VisibleField(FieldReference reference, int distance) {}

  record LocalDeclaration(String type, String initializer, int start, int initializerOffset) {}

  record DeclarationScope(int start, int end) {}

  record ForScope(int opening, int bodyStart, int end, boolean enhanced) {}

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

  record ConstructorInfo(String annotations, String parameters, int start) {}

  record GeneratedMethod(SourceFile file, MethodInfo method) {}

  record LifecycleMethod(
      SourceFile file,
      MethodInfo method,
      String declaringType,
      String kind,
      List<String> annotationSymbols) {}

  record RepositoryMethod(String name, String signature, boolean guaranteed) {
    String identity() {
      return guaranteed ? name : signature;
    }
  }

  record AnnotationOccurrence(String arguments, String source) {}

  record ParsedAnnotation(String name, String arguments, String source) {}
}
