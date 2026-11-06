package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
  private static final Pattern METHOD = Pattern.compile(
      "(?m)(?:^|[;{}\\n])\\s*(?:(?:@[A-Za-z_][\\w.]*(?:\\([^)]*\\))?\\s*)|(?:(?:public|protected|private|static|final|synchronized|abstract|native|default|strictfp)\\s+))*(?:<[^>{}]+>\\s+)?[\\w.?$<>\\[\\], ]+\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws [^{;]+)?(?:\\{|;)");
  private static final Pattern QUOTED = Pattern.compile("\\\"([^\\\"]*)\\\"");
  private static final Pattern CLASS_FOR_NAME = Pattern.compile("Class\\.forName\\s*\\(\\s*\\\"([^\\\"]+)\\\"");
  private static final Pattern DECLARED_METHOD = Pattern.compile("getDeclaredMethod\\s*\\(\\s*\\\"([^\\\"]+)\\\"");
  private static final Pattern PROXY_INTERFACES = Pattern.compile(
      "new\\s+Class\\s*<[^>]*>\\s*\\[\\s*\\]\\s*\\{([^}]*)\\}");
  private static final Pattern CLASS_LITERAL = Pattern.compile("([A-Za-z_][\\w.]*)\\.class");
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
      "Integer", "Iterable", "Long", "Math", "Number", "Object", "Record", "Runnable",
      "RuntimeException", "Short", "StrictMath", "String", "Thread", "Throwable", "Void");
  private static final Set<String> JAVA_PRIMITIVE_TYPES = Set.of(
      "boolean", "byte", "char", "short", "int", "long", "float", "double", "void");
  private static final Set<String> CDI_PRODUCER_ANNOTATIONS = Set.of(
      "jakarta.enterprise.inject.Produces", "javax.enterprise.inject.Produces");
  private static final Set<String> SPRING_PROXY_ANNOTATIONS = Set.of(
      "Transactional", "Async", "Cacheable", "CachePut", "CacheEvict", "PreAuthorize",
      "PostAuthorize", "Secured", "RolesAllowed", "Retryable", "CircuitBreaker", "Validated",
      "TransactionalEventListener");
  private static final List<String> SUPPORTED_HTTP_METHODS =
      List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

  private final AnalysisContext context;
  private List<SourceFile> files;

  /** Creates a scanner over the selected analysis source roots. */
  SourceMetadataScanner(AnalysisContext context) {
    this.context = Objects.requireNonNull(context, "analysis context");
  }

  /** Loads and caches Java source files from included roots. */
  List<SourceFile> files() {
    if (files != null) {
      return files;
    }
    List<SourceFile> result = new ArrayList<>();
    for (AnalysisContext.SourceRoot root : context.includedSourceRoots()) {
      if (!Files.isDirectory(root.path())) {
        continue;
      }
      try (Stream<Path> paths = Files.walk(root.path())) {
        paths.filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java"))
            .sorted()
            .map(path -> read(path, root))
            .forEach(result::add);
      } catch (IOException error) {
        throw new IllegalStateException("cannot scan source root " + root.path(), error);
      }
    }
    result.sort(Comparator.comparing(SourceFile::displayPath));
    files = List.copyOf(result);
    return files;
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
    return JAVA_LANG_TYPES.contains(normalized)
        ? Optional.of("java.lang." + normalized)
        : Optional.empty();
  }

  /** Resolves explicit imports that can qualify a source type spelling. */
  private static List<String> importedTypes(String type, SourceFile sourceFile) {
    String simpleType = type;
    int nestedSeparator = type.indexOf('.');
    if (nestedSeparator >= 0) {
      simpleType = type.substring(0, nestedSeparator);
    }
    Matcher matcher = Pattern.compile("(?m)^\\s*import\\s+([A-Za-z_][\\w.]*)\\s*;")
        .matcher(sourceFile.structuralSource());
    List<String> result = new ArrayList<>();
    while (matcher.find()) {
      String imported = matcher.group(1);
      if (imported.endsWith("." + simpleType)) {
        result.add(imported + type.substring(simpleType.length()));
      }
    }
    return result.stream().distinct().toList();
  }

  /** Scans bounded Quarkus and Spring route annotations. */
  List<Route> routes(AnalysisContext.Framework framework) {
    List<Route> result = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (SourceFile file : files()) {
      String classBlock = file.classAnnotationBlock();
      boolean quarkusClassPath = hasAnnotation(classBlock, "Path");
      boolean springController = hasAnnotation(classBlock, "RestController");
      if (framework == AnalysisContext.Framework.SPRING_BOOT && !springController) {
        continue;
      }
      List<String> classPaths = framework == AnalysisContext.Framework.QUARKUS
          ? pathValues(classBlock, "Path")
          : mappingPaths(classBlock, "RequestMapping");
      if (framework == AnalysisContext.Framework.QUARKUS && quarkusClassPath && classPaths.isEmpty()) {
        continue;
      }
      if (framework == AnalysisContext.Framework.SPRING_BOOT
          && hasAnnotation(classBlock, "RequestMapping")
          && classPaths.isEmpty()) {
        continue;
      }
      if (classPaths.isEmpty()) {
        classPaths = List.of("");
      }
      List<String> classHttpMethods = framework == AnalysisContext.Framework.SPRING_BOOT
          ? httpMethods(classBlock, "RequestMapping")
          : SUPPORTED_HTTP_METHODS;
      for (MethodInfo method : methods(file)) {
        for (String annotationName : routeAnnotations(method.annotationBlock(), framework)) {
          List<String> httpMethods = intersectHttpMethods(
              httpMethods(method.annotationBlock(), annotationName), classHttpMethods);
          if (httpMethods.isEmpty()) {
            continue;
          }
          boolean methodPathAnnotation = framework == AnalysisContext.Framework.QUARKUS
              && hasAnnotation(method.annotationBlock(), "Path");
          List<String> methodPaths = framework == AnalysisContext.Framework.QUARKUS
              ? pathValues(method.annotationBlock(), "Path")
              : mappingPaths(method.annotationBlock(), annotationName);
          if (framework == AnalysisContext.Framework.QUARKUS && methodPathAnnotation && methodPaths.isEmpty()) {
            continue;
          }
          if (framework == AnalysisContext.Framework.SPRING_BOOT
              && methodPaths.isEmpty()
              && hasExplicitMappingPath(method.annotationBlock(), annotationName)) {
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
                  : mediaValues(classBlock, "RequestMapping", "consumes"),
              framework == AnalysisContext.Framework.QUARKUS
                  ? mediaValues(method.annotationBlock(), "Consumes", null)
                  : mediaValues(method.annotationBlock(), annotationName, "consumes"));
          List<String> produces = overrideValues(
              framework == AnalysisContext.Framework.QUARKUS
                  ? mediaValues(classBlock, "Produces", null)
                  : mediaValues(classBlock, "RequestMapping", "produces"),
              framework == AnalysisContext.Framework.QUARKUS
                  ? mediaValues(method.annotationBlock(), "Produces", null)
                  : mediaValues(method.annotationBlock(), annotationName, "produces"));
          List<String> headers = framework == AnalysisContext.Framework.SPRING_BOOT
              ? mergeValues(
                  values(classBlock, "RequestMapping", "headers"),
                  values(method.annotationBlock(), annotationName, "headers"))
              : List.of();
          List<String> parameters = framework == AnalysisContext.Framework.SPRING_BOOT
              ? mergeValues(
                  values(classBlock, "RequestMapping", "params"),
                  values(method.annotationBlock(), annotationName, "params"))
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
                    routeSymbols(classBlock, method.annotationBlock(), framework, annotationName)));
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
          : springComponent(file.classAnnotationBlock());
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
              "<init>",
              "constructor",
              qualifierNames(constructorInfo.annotations())));
        }
      }

      if (component) {
        Matcher fieldMatcher = INJECT_FIELD.matcher(file.structuralSource());
        while (fieldMatcher.find()) {
          String annotations = fieldMatcher.group(1);
          if (!hasInjectionAnnotation(annotations)) {
            continue;
          }
          List<String> fieldQualifiers = injectionQualifiers(
              annotations, fieldMatcher.group(3));
          Parameter parameter = new Parameter(
              fieldMatcher.group(2), fieldMatcher.group(3), fieldQualifiers);
          result.add(new InjectionPoint(
              file,
              List.of(parameter),
              file.lineAt(fieldMatcher.start()),
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
                method.annotationBlock(), method.name());
            result.add(new InjectionPoint(
                file,
                parsedParameters,
                method.line(),
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
      boolean bean = framework == AnalysisContext.Framework.QUARKUS
          ? cdiBean(block)
          : springComponent(block) || file.structuralSource().matches(
              "(?s).*\\binterface\\s+\\w+\\s+extends\\s+[^;]*\\b(?:JpaRepository|Repository)\\b.*");
      if (bean) {
        Set<String> contracts = contracts(file);
        result.add(new BeanCandidate(
            file,
            contracts,
            beanKind(block, framework),
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
            : method.hasAnnotation("Bean");
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

  /** Scans framework proxy targets and records CDI scope eligibility. */
  List<ProxyTarget> proxyTargets(AnalysisContext.Framework framework) {
    List<ProxyTarget> result = new ArrayList<>();
    for (SourceFile file : files()) {
      String classBlock = file.classAnnotationBlock();
      boolean bean = framework == AnalysisContext.Framework.QUARKUS
          ? cdiBean(classBlock)
          : springComponent(classBlock);
      if (!bean) {
        continue;
      }
      boolean normalScoped = CDI_NORMAL_SCOPES.stream().anyMatch(name -> hasAnnotation(classBlock, name));
      for (MethodInfo method : methods(file)) {
        boolean selected = framework == AnalysisContext.Framework.QUARKUS
            ? !method.name().equals(file.simpleTypeName())
            : SPRING_PROXY_ANNOTATIONS.stream().anyMatch(name -> hasAnnotation(method.annotationBlock(), name))
                || SPRING_PROXY_ANNOTATIONS.stream().anyMatch(name -> hasAnnotation(classBlock, name));
        if (selected && (framework != AnalysisContext.Framework.SPRING_BOOT
            || (!hasModifier(method.annotationBlock(), "private")
                && !hasModifier(method.annotationBlock(), "static")))) {
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
      String source = file.source();
      if (source.contains("Class.forName") || source.contains("getDeclaredMethod") || source.contains("Method.invoke")) {
        Matcher targetClass = CLASS_FOR_NAME.matcher(source);
        Matcher targetMethod = DECLARED_METHOD.matcher(source);
        String target = (targetClass.find() ? targetClass.group(1) : "unknown")
            + "#" + (targetMethod.find() ? targetMethod.group(1) : "unknown");
        MethodInfo caller = methodContaining(file, firstDynamicIndex(source));
        result.add(new DynamicFeature(
            DynamicFeature.Kind.REFLECTION,
            new MethodSymbol(file.typeName(), caller.name(), ""),
            target,
            file.displayPath(),
            List.of("Class.forName", "getDeclaredMethod", "Method.invoke"),
            "Class.forName, Method.getDeclaredMethod, and Method.invoke do not identify a unique runtime target."));
      }
      if (source.contains("Proxy.newProxyInstance") || source.contains("InvocationHandler")) {
        Matcher interfaces = PROXY_INTERFACES.matcher(source);
        List<String> interfaceNames = new ArrayList<>();
        if (interfaces.find()) {
          Matcher literal = CLASS_LITERAL.matcher(interfaces.group(1));
          while (literal.find()) {
            interfaceNames.add(literal.group(1));
          }
        }
        MethodInfo caller = methodContaining(file, firstDynamicIndex(source));
        if (interfaceNames.isEmpty()) {
          result.add(dynamicProxyFeature(file, caller, "unknown#unknown"));
        } else {
          for (String interfaceName : interfaceNames) {
            String qualified = qualify(interfaceName, file.packageName());
            Optional<SourceFile> interfaceFile = files().stream()
                .filter(candidate -> candidate.typeName().equals(qualified)
                    || candidate.simpleTypeName().equals(interfaceName))
                .findFirst();
            List<MethodInfo> interfaceMethods = interfaceFile.map(SourceMetadataScanner::methods).orElse(List.of());
            if (interfaceMethods.isEmpty()) {
              result.add(dynamicProxyFeature(file, caller, qualified + "#unknown"));
            } else {
              for (MethodInfo method : interfaceMethods) {
                result.add(dynamicProxyFeature(file, caller, qualified + "#" + method.name()));
              }
            }
          }
        }
      }
    }
    return List.copyOf(result);
  }

  /** Builds unresolved evidence for one dynamic proxy construction site. */
  private DynamicFeature dynamicProxyFeature(SourceFile file, MethodInfo caller, String target) {
    return new DynamicFeature(
        DynamicFeature.Kind.DYNAMIC_PROXY,
        new MethodSymbol(file.typeName(), caller.name(), ""),
        target,
        file.displayPath(),
        List.of("Proxy.newProxyInstance", "InvocationHandler"),
        "The proxy handler target is not promoted without explicit registration evidence.");
  }

  /** Reads source text and derives its package and top-level type identity. */
  private SourceFile read(Path path, AnalysisContext.SourceRoot root) {
    try {
      String source = Files.readString(path);
      String structuralSource = stripComments(source);
      Matcher packageMatcher = PACKAGE.matcher(structuralSource);
      String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
      Matcher typeMatcher = TYPE.matcher(structuralSource);
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
    return methods(file).stream()
        .filter(method -> method.start() <= index && method.end() >= index)
        .findFirst()
        .orElseGet(() -> new MethodInfo("<unknown>", "", "", index, index, file.lineAt(index), ""));
  }

  private static List<String> routeAnnotations(String block, AnalysisContext.Framework framework) {
    List<String> candidates = framework == AnalysisContext.Framework.QUARKUS
        ? List.of("POST", "GET", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
        : List.of("PostMapping", "GetMapping", "PutMapping", "PatchMapping", "DeleteMapping", "RequestMapping");
    List<String> result = new ArrayList<>();
    for (String name : candidates) {
      if (hasAnnotation(block, name)) {
        result.add(name);
      }
    }
    return List.copyOf(result);
  }

  /** Expands supported HTTP method annotation values. */
  private static List<String> httpMethods(String block, String annotationName) {
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
        boolean wildcard = annotationArguments(block, annotationName).isEmpty();
        for (String args : annotationArguments(block, annotationName)) {
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
  private static List<String> mappingPaths(String block, String annotation) {
    if (!hasAnnotation(block, annotation)) {
      return List.of();
    }
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (String args : annotationArguments(block, annotation)) {
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
    return hasExplicitMappingPath(block, annotation) ? List.of() : List.of("");
  }

  private static boolean hasExplicitMappingPath(String block, String annotation) {
    return annotationArguments(block, annotation).stream()
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
      String classBlock, String methodBlock, AnalysisContext.Framework framework, String annotationName) {
    List<String> symbols = new ArrayList<>();
    if (framework == AnalysisContext.Framework.QUARKUS) {
      addSymbols(symbols, classBlock, "Path", "Consumes", "Produces");
      addSymbols(symbols, methodBlock, "Path", "Consumes", "Produces");
    } else {
      addSymbols(symbols, classBlock, "RequestMapping", "RestController");
    }
    addSymbols(symbols, methodBlock, annotationName);
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

  /** Recognizes the bounded Spring component stereotype subset. */
  private static boolean springComponent(String block) {
    return hasAnnotation(block, "Service")
        || hasAnnotation(block, "Component")
        || hasAnnotation(block, "Repository")
        || hasAnnotation(block, "Configuration")
        || hasAnnotation(block, "Controller")
        || hasAnnotation(block, "RestController")
        || hasAnnotation(block, "Named");
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

  /** Extracts implemented interfaces and the direct superclass from one type header. */
  private static Set<String> contracts(SourceFile file) {
    Set<String> contracts = new LinkedHashSet<>();
    String header = typeHeader(file);
    String normalized = stripBalancedGenericArguments(header);
    int implementsIndex = keywordIndex(normalized, "implements");
    int extendsIndex = keywordIndex(normalized, "extends");
    if (implementsIndex >= 0) {
      int end = keywordIndexAfter(normalized, "permits", implementsIndex + "implements".length());
      String implemented = normalized.substring(
          implementsIndex + "implements".length(), end >= 0 ? end : normalized.length());
      contracts.addAll(typeNames(implemented));
    }
    if (extendsIndex >= 0) {
      int end = implementsIndex >= 0 ? implementsIndex : keywordIndexAfter(
          normalized, "permits", extendsIndex + "extends".length());
      String extended = normalized.substring(
          extendsIndex + "extends".length(), end >= 0 ? end : normalized.length());
      List<String> extendedTypes = typeNames(extended);
      if (normalized.matches("(?s).*\\binterface\\s+\\w+.*")) {
        contracts.addAll(extendedTypes);
      } else if (!extendedTypes.isEmpty()) {
        contracts.add(extendedTypes.get(0));
      }
    }
    return Set.copyOf(contracts);
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
  private static List<String> injectionQualifiers(String annotations, String defaultName) {
    List<String> result = new ArrayList<>(qualifierNames(annotations));
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
      return "ApplicationScoped";
    }
    for (String annotation : List.of(
        "Service", "Component", "Repository", "Configuration", "Controller", "RestController", "Named")) {
      if (hasAnnotation(block, annotation)) {
        return annotation;
      }
    }
    return "repository_interface";
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
        result.add(simpleName(type));
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

  /** Checks a method declaration modifier without reading annotation arguments as modifiers. */
  private static boolean hasModifier(String block, String modifier) {
    String structural = maskAnnotationArguments(stripComments(block == null ? "" : block));
    return Pattern.compile("\\b" + Pattern.quote(modifier) + "\\b").matcher(structural).find();
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
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (String args : annotationArguments(block, annotation)) {
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
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (String value : values(block, annotation, attribute)) {
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

  /** Finds annotation occurrences with balanced argument extraction. */
  private static List<AnnotationOccurrence> annotationOccurrences(String block, String name) {
    List<AnnotationOccurrence> result = new ArrayList<>();
    String source = stripComments(block == null ? "" : block);
    Matcher matcher = Pattern.compile(
        "@(?:[A-Za-z_][\\w.]*\\.)?" + Pattern.quote(name) + "\\b").matcher(source);
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

  /** Finds the matching delimiter while ignoring quoted strings. */
  private static int matchingDelimiter(String source, int opening, char open, char close) {
    int depth = 0;
    boolean quoted = false;
    boolean escaped = false;
    for (int index = opening; index < source.length(); index++) {
      char character = source.charAt(index);
      if (character == '"' && !escaped) {
        quoted = !quoted;
      }
      if (quoted) {
        escaped = character == '\\' && !escaped;
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

  /** Finds the first reflection or dynamic-proxy construct in source text. */
  private static int firstDynamicIndex(String source) {
    int reflection = source.indexOf("Class.forName");
    int proxy = source.indexOf("Proxy.newProxyInstance");
    if (reflection < 0) {
      return proxy < 0 ? 0 : proxy;
    }
    if (proxy < 0) {
      return reflection;
    }
    return Math.min(reflection, proxy);
  }

  private static String qualify(String name, String packageName) {
    return name.contains(".") ? name : (packageName.isBlank() ? name : packageName + "." + name);
  }

  private static String simpleName(String value) {
    int dot = value.lastIndexOf('.');
    return dot < 0 ? value : value.substring(dot + 1);
  }

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
      String member,
      String kind,
      List<String> qualifiers) {
    InjectionPoint(SourceFile file, List<Parameter> parameters, int line) {
      this(file, parameters, line, "<init>", "constructor", List.of());
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

  private record AnnotationOccurrence(String arguments, String source) {}
}
