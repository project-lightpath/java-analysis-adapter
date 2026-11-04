package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
  private static final Pattern METHOD = Pattern.compile(
      "(?m)(?:^|[;{}\\n])\\s*(?:(?:@[A-Za-z_][\\w.]*(?:\\([^)]*\\))?\\s*)|(?:(?:public|protected|private|static|final|synchronized|abstract|native|default|strictfp)\\s+))*(?:<[^>{}]+>\\s+)?[\\w.?$<>\\[\\], ]+\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws [^{;]+)?(?:\\{|;)");
  private static final Pattern ANNOTATION = Pattern.compile("@([A-Za-z_][\\w.]*)\\s*(?:\\(([^)]*)\\))?");
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
    for (SourceFile file : files()) {
      String classBlock = file.classAnnotationBlock();
      String classPath = framework == AnalysisContext.Framework.QUARKUS
          ? firstValue(classBlock, "Path", null)
          : mappingPath(classBlock, "RequestMapping");
      boolean controller = framework == AnalysisContext.Framework.QUARKUS
          ? classPath != null
          : hasAnnotation(classBlock, "RestController") && classPath != null;
      if (!controller) {
        continue;
      }
      for (MethodInfo method : methods(file)) {
        String annotationName = routeAnnotation(method.annotationBlock(), framework);
        if (annotationName == null) {
          continue;
        }
        String httpMethod = httpMethod(method.annotationBlock(), annotationName);
        if (httpMethod == null) {
          continue;
        }
        String methodPath = framework == AnalysisContext.Framework.QUARKUS
            ? firstValue(method.annotationBlock(), annotationName, null)
            : mappingPath(method.annotationBlock(), annotationName);
        String path = joinPath(classPath, methodPath);
        List<String> consumes = mergeValues(
            framework == AnalysisContext.Framework.QUARKUS
                ? values(classBlock, "Consumes", null)
                : values(classBlock, "RequestMapping", "consumes"),
            framework == AnalysisContext.Framework.QUARKUS
                ? values(method.annotationBlock(), "Consumes", null)
                : values(method.annotationBlock(), annotationName, "consumes"));
        List<String> produces = mergeValues(
            framework == AnalysisContext.Framework.QUARKUS
                ? values(classBlock, "Produces", null)
                : values(classBlock, "RequestMapping", "produces"),
            framework == AnalysisContext.Framework.QUARKUS
                ? values(method.annotationBlock(), "Produces", null)
                : values(method.annotationBlock(), annotationName, "produces"));
        List<String> headers = values(method.annotationBlock(), annotationName, "headers");
        List<String> parameters = values(method.annotationBlock(), annotationName, "params");
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
          root.generated());
    } catch (IOException error) {
      throw new IllegalStateException("cannot read Java source " + path, error);
    }
  }

  static List<MethodInfo> methods(SourceFile file) {
    List<MethodInfo> result = new ArrayList<>();
    Matcher matcher = METHOD.matcher(file.source());
    while (matcher.find()) {
      int start = matcher.start();
      int blockStart = Math.max(file.source().lastIndexOf('}', start - 1), file.source().lastIndexOf(';', start - 1));
      String annotationBlock = file.source().substring(Math.max(0, blockStart + 1), matcher.end());
      result.add(new MethodInfo(
          matcher.group(1),
          matcher.group(2),
          annotationBlock,
          start,
          matcher.end(),
          file.lineAt(start)));
    }
    return List.copyOf(result);
  }

  private static MethodInfo methodContaining(SourceFile file, int index) {
    return methods(file).stream()
        .filter(method -> method.start() <= index && method.end() >= index)
        .findFirst()
        .orElseGet(() -> new MethodInfo("<unknown>", "", "", index, index, file.lineAt(index)));
  }

  private static String routeAnnotation(String block, AnalysisContext.Framework framework) {
    if (framework == AnalysisContext.Framework.QUARKUS) {
      for (String name : List.of("POST", "GET", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")) {
        if (hasAnnotation(block, name)) {
          return name;
        }
      }
      return null;
    }
    for (String name : List.of("PostMapping", "GetMapping", "PutMapping", "PatchMapping", "DeleteMapping", "RequestMapping")) {
      if (hasAnnotation(block, name)) {
        return name;
      }
    }
    return null;
  }

  private static String httpMethod(String block, String annotationName) {
    return switch (annotationName) {
      case "POST", "PostMapping" -> "POST";
      case "GET", "GetMapping" -> "GET";
      case "PUT", "PutMapping" -> "PUT";
      case "PATCH", "PatchMapping" -> "PATCH";
      case "DELETE", "DeleteMapping" -> "DELETE";
      case "HEAD" -> "HEAD";
      case "OPTIONS" -> "OPTIONS";
      case "RequestMapping" -> {
        String args = annotationArgs(block, annotationName);
        Matcher matcher = Pattern.compile("RequestMethod\\.(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)").matcher(args);
        yield matcher.find() ? matcher.group(1) : null;
      }
      default -> null;
    };
  }

  private static List<String> routeSymbols(
      String classBlock, String methodBlock, AnalysisContext.Framework framework, String annotationName) {
    List<String> symbols = new ArrayList<>();
    if (framework == AnalysisContext.Framework.QUARKUS) {
      addSymbol(symbols, classBlock, "Path");
      addSymbol(symbols, classBlock, "Consumes");
      addSymbol(symbols, classBlock, "Produces");
    } else {
      addSymbol(symbols, classBlock, "RequestMapping");
      addSymbol(symbols, classBlock, "RestController");
    }
    addSymbol(symbols, methodBlock, annotationName);
    return List.copyOf(symbols);
  }

  private static void addSymbol(List<String> symbols, String block, String name) {
    Matcher matcher = Pattern.compile("@" + Pattern.quote(name) + "\\s*(?:\\([^)]*\\))?").matcher(block);
    if (matcher.find()) {
      symbols.add(matcher.group());
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
    for (int index = 0; index < source.length(); index++) {
      char character = source.charAt(index);
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
    return Pattern.compile("@(?:[A-Za-z_][\\w.]*\\.)?" + Pattern.quote(name) + "\\b").matcher(block).find();
  }

  private static String annotationArgs(String block, String name) {
    Matcher matcher = Pattern.compile(
        "@(?:[A-Za-z_][\\w.]*\\.)?" + Pattern.quote(name) + "\\s*(?:\\(([^)]*)\\))?").matcher(block);
    String value = null;
    while (matcher.find()) {
      value = matcher.group(1) == null ? "" : matcher.group(1);
    }
    return value;
  }

  private static String firstValue(String block, String annotation, String attribute) {
    List<String> values = values(block, annotation, attribute);
    return values.isEmpty() ? null : values.get(0);
  }

  private static String mappingPath(String block, String annotation) {
    String value = firstValue(block, annotation, "value");
    return value == null ? firstValue(block, annotation, "path") : value;
  }

  private static List<String> values(String block, String annotation, String attribute) {
    String args = annotationArgs(block, annotation);
    if (args == null || args.isBlank()) {
      return List.of();
    }
    String selected = attribute == null ? args : attributeValue(args, attribute);
    if (selected == null) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    Matcher quoted = QUOTED.matcher(selected);
    while (quoted.find()) {
      result.add(quoted.group(1));
    }
    if (!result.isEmpty()) {
      return List.copyOf(result);
    }
    for (String raw : selected.replaceAll("[{}]", "").split(",")) {
      String normalized = mediaType(raw.trim());
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
      if (equals >= 0 && part.substring(0, equals).trim().equals(attribute)) {
        return part.substring(equals + 1).trim();
      }
    }
    return attribute.equals("value") && !args.contains("=") ? args.trim() : null;
  }

  private static String mediaType(String value) {
    String normalized = value.trim();
    if (normalized.equals("MediaType.APPLICATION_JSON") || normalized.equals("MediaType.APPLICATION_JSON_VALUE")) {
      return "application/json";
    }
    if (normalized.equals("MediaType.APPLICATION_XML") || normalized.equals("MediaType.APPLICATION_XML_VALUE")) {
      return "application/xml";
    }
    if (normalized.endsWith("_VALUE")) {
      normalized = normalized.substring(0, normalized.length() - "_VALUE".length());
    }
    return normalized;
  }

  private static List<String> mergeValues(List<String> first, List<String> second) {
    LinkedHashSet<String> result = new LinkedHashSet<>(first);
    result.addAll(second);
    return List.copyOf(result);
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
      boolean generated) {
    String classAnnotationBlock() {
      int type = source.indexOf(simpleTypeName);
      return type < 0 ? source : source.substring(0, type);
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
}
