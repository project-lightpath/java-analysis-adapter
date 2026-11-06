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

  private final AnalysisContext context;
  private List<SourceFile> files;

  SourceMetadataScanner(AnalysisContext context) {
    this.context = Objects.requireNonNull(context, "analysis context");
  }

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
      for (MethodInfo method : methods(file)) {
        for (String annotationName : routeAnnotations(method.annotationBlock(), framework)) {
          List<String> httpMethods = httpMethods(method.annotationBlock(), annotationName);
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

  List<InjectionPoint> injectionPoints(AnalysisContext.Framework framework) {
    List<InjectionPoint> result = new ArrayList<>();
    for (SourceFile file : files()) {
      boolean component = framework == AnalysisContext.Framework.QUARKUS
          ? hasAnnotation(file.classAnnotationBlock(), "ApplicationScoped")
          : springComponent(file.classAnnotationBlock());
      Pattern constructor = Pattern.compile(
          "(?s)(@Inject\\s*)?(?:public|protected|private)?\\s*"
              + Pattern.quote(file.simpleTypeName())
              + "\\s*\\(([^)]*)\\)\\s*\\{");
      Matcher matcher = constructor.matcher(file.source());
      while (matcher.find()) {
        boolean inject = matcher.group(1) != null;
        if (framework == AnalysisContext.Framework.QUARKUS && !inject) {
          continue;
        }
        if (framework == AnalysisContext.Framework.SPRING_BOOT && !component) {
          continue;
        }
        List<Parameter> parameters = parameters(matcher.group(2));
        if (!parameters.isEmpty()) {
          result.add(new InjectionPoint(file, parameters, file.lineAt(matcher.start())));
        }
      }
    }
    return List.copyOf(result);
  }

  List<BeanCandidate> beanCandidates(AnalysisContext.Framework framework) {
    List<BeanCandidate> result = new ArrayList<>();
    for (SourceFile file : files()) {
      String block = file.classAnnotationBlock();
      boolean bean = framework == AnalysisContext.Framework.QUARKUS
          ? hasAnnotation(block, "ApplicationScoped")
          : springComponent(block) || file.source().matches("(?s).*\\binterface\\s+\\w+\\s+extends\\s+[^;]*\\b(?:JpaRepository|Repository)\\b.*");
      if (!bean) {
        continue;
      }
      Set<String> contracts = new LinkedHashSet<>();
      Matcher implementsMatcher = Pattern.compile("\\bimplements\\s+([^\\{]+)").matcher(file.source());
      if (implementsMatcher.find()) {
        contracts.addAll(typeNames(implementsMatcher.group(1)));
      }
      Matcher extendsMatcher = Pattern.compile("\\bextends\\s+([^\\{]+)").matcher(file.source());
      if (extendsMatcher.find()) {
        contracts.addAll(typeNames(extendsMatcher.group(1)));
      }
      result.add(new BeanCandidate(file, contracts, beanKind(block, framework)));
    }
    return List.copyOf(result);
  }

  List<ProxyTarget> proxyTargets(AnalysisContext.Framework framework) {
    List<ProxyTarget> result = new ArrayList<>();
    for (SourceFile file : files()) {
      String classBlock = file.classAnnotationBlock();
      boolean bean = framework == AnalysisContext.Framework.QUARKUS
          ? hasAnnotation(classBlock, "ApplicationScoped")
          : springComponent(classBlock);
      if (!bean) {
        continue;
      }
      for (MethodInfo method : methods(file)) {
        boolean selected = framework == AnalysisContext.Framework.QUARKUS
            ? !method.name().equals(file.simpleTypeName())
            : hasAnnotation(method.annotationBlock(), "Transactional") || hasAnnotation(classBlock, "Transactional");
        if (selected) {
          result.add(new ProxyTarget(file, method));
        }
      }
    }
    return List.copyOf(result);
  }

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

  private DynamicFeature dynamicProxyFeature(SourceFile file, MethodInfo caller, String target) {
    return new DynamicFeature(
        DynamicFeature.Kind.DYNAMIC_PROXY,
        new MethodSymbol(file.typeName(), caller.name(), ""),
        target,
        file.displayPath(),
        List.of("Proxy.newProxyInstance", "InvocationHandler"),
        "The proxy handler target is not promoted without explicit registration evidence.");
  }

  private SourceFile read(Path path, AnalysisContext.SourceRoot root) {
    try {
      String source = Files.readString(path);
      Matcher packageMatcher = PACKAGE.matcher(source);
      String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
      Matcher typeMatcher = TYPE.matcher(source);
      if (!typeMatcher.find()) {
        throw new IllegalStateException("Java source has no top-level type: " + path);
      }
      String simpleName = typeMatcher.group(1);
      String typeName = packageName.isBlank() ? simpleName : packageName + "." + simpleName;
      return new SourceFile(
          path,
          context.displayPath(path),
          source,
          packageName,
          typeName,
          simpleName,
          typeMatcher.start(),
          root.generated());
    } catch (IOException error) {
      throw new IllegalStateException("cannot read Java source " + path, error);
    }
  }

  static List<MethodInfo> methods(SourceFile file) {
    List<MethodInfo> result = new ArrayList<>();
    String maskedSource = maskAnnotationArguments(file.source());
    Matcher matcher = METHOD.matcher(maskedSource);
    while (matcher.find()) {
      int start = matcher.start();
      String annotationBlock = file.source().substring(start, matcher.start(1));
      int annotationOffset = annotationBlock.indexOf('@');
      int lineOffset = annotationOffset < 0 ? matcher.start(1) : start + annotationOffset;
      result.add(new MethodInfo(
          matcher.group(1),
          matcher.group(2),
          annotationBlock,
          start,
          matcher.end(),
          file.lineAt(lineOffset)));
    }
    return List.copyOf(result);
  }

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
        .orElseGet(() -> new MethodInfo("<unknown>", "", "", index, index, file.lineAt(index)));
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
        for (String args : annotationArguments(block, annotationName)) {
          String selected = attributeValue(args, "method");
          if (selected == null) {
            continue;
          }
          Matcher matcher = Pattern.compile("RequestMethod\\.(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)")
              .matcher(selected);
          while (matcher.find()) {
            result.add(matcher.group(1));
          }
        }
      }
      default -> {
        // The bounded route subset intentionally ignores custom HTTP annotations.
      }
    }
    return List.copyOf(new LinkedHashSet<>(result));
  }

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

  private static void addSymbols(List<String> symbols, String block, String... names) {
    for (String name : names) {
      for (AnnotationOccurrence occurrence : annotationOccurrences(block, name)) {
        symbols.add(occurrence.source());
      }
    }
  }

  private static boolean springComponent(String block) {
    return hasAnnotation(block, "Service")
        || hasAnnotation(block, "Component")
        || hasAnnotation(block, "Repository")
        || hasAnnotation(block, "Configuration")
        || hasAnnotation(block, "RestController");
  }

  private static String beanKind(String block, AnalysisContext.Framework framework) {
    if (framework == AnalysisContext.Framework.QUARKUS) {
      return "ApplicationScoped";
    }
    for (String annotation : List.of("Service", "Component", "Repository", "Configuration", "RestController")) {
      if (hasAnnotation(block, annotation)) {
        return annotation;
      }
    }
    return "repository_interface";
  }

  private static List<Parameter> parameters(String source) {
    List<Parameter> result = new ArrayList<>();
    for (String raw : splitArguments(source)) {
      String cleaned = raw.replaceAll("@\\w+(?:\\([^)]*\\))?", "").trim();
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
      result.add(new Parameter(type, name));
    }
    return List.copyOf(result);
  }

  private static List<String> typeNames(String source) {
    List<String> result = new ArrayList<>();
    for (String raw : splitArguments(source)) {
      String type = raw.trim().replaceAll("<.*>", "").trim();
      if (!type.isBlank()) {
        result.add(simpleName(type));
      }
    }
    return result;
  }

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

  private static List<AnnotationOccurrence> annotationOccurrences(String block, String name) {
    List<AnnotationOccurrence> result = new ArrayList<>();
    Matcher matcher = Pattern.compile(
        "@(?:[A-Za-z_][\\w.]*\\.)?" + Pattern.quote(name) + "\\b").matcher(block);
    while (matcher.find()) {
      int end = matcher.end();
      while (end < block.length() && Character.isWhitespace(block.charAt(end))) {
        end++;
      }
      String arguments = "";
      if (end < block.length() && block.charAt(end) == '(') {
        int closing = matchingDelimiter(block, end, '(', ')');
        if (closing < 0) {
          continue;
        }
        arguments = block.substring(end + 1, closing);
        end = closing + 1;
      }
      result.add(new AnnotationOccurrence(
          arguments,
          block.substring(matcher.start(), end).replaceAll("\\s+", " ").trim()));
    }
    return List.copyOf(result);
  }

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

  private static List<String> mergeValues(List<String> first, List<String> second) {
    LinkedHashSet<String> result = new LinkedHashSet<>(first);
    result.addAll(second);
    return List.copyOf(result);
  }

  private static List<String> overrideValues(List<String> inherited, List<String> declared) {
    return declared.isEmpty() ? inherited : declared;
  }

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
      String packageName,
      String typeName,
      String simpleTypeName,
      int typeDeclarationStart,
      boolean generated) {
    String classAnnotationBlock() {
      return source.substring(0, Math.min(typeDeclarationStart, source.length()));
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

  record MethodInfo(String name, String parameters, String annotationBlock, int start, int end, int line) {
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

  record Parameter(String type, String name) {}

  record InjectionPoint(SourceFile file, List<Parameter> parameters, int line) {}

  record BeanCandidate(SourceFile file, Set<String> contracts, String kind) {}

  record ProxyTarget(SourceFile file, MethodInfo method) {}

  record GeneratedMethod(SourceFile file, MethodInfo method) {}

  private record AnnotationOccurrence(String arguments, String source) {}
}
