package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerTextLayer16 extends SourceMetadataScannerTextLayer15 {

  SourceMetadataScannerTextLayer16(AnalysisContext context) {
    super(context);
  }



  /** Extracts arguments only from canonically resolved Spring route annotations. */
static List<String> annotationArguments(SourceFile file, String block, String name) {
    return canonicalSpringAnnotationOccurrences(file, block, name).stream()
        .map(AnnotationOccurrence::arguments)
        .toList();
  }

static boolean declarationIsActive(
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

  /** Returns the deepest declared type that contains a source offset. */
static String typeContaining(SourceFile file, int index) {
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

static List<String> routeAnnotations(
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

static List<String> requestMappingHttpMethods(SourceFile file, String block) {
    List<String> result = new ArrayList<>();
    List<String> argumentsList = annotationArguments(file, block, "RequestMapping");
    boolean wildcard = argumentsList.isEmpty();
    for (String arguments : argumentsList) {
      String selected = attributeValue(arguments, "method");
      if (selected == null) {
        wildcard = true;
        continue;
      }
      Matcher matcher = Pattern.compile(
          "RequestMethod\\.(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)").matcher(selected);
      while (matcher.find()) {
        result.add(matcher.group(1));
      }
    }
    if (wildcard) {
      result.addAll(SUPPORTED_HTTP_METHODS);
    }
    return List.copyOf(new LinkedHashSet<>(result));
  }

  /** Extracts literal route path values from an annotation. */
static List<String> pathValues(String block, String annotation) {
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

static boolean hasExplicitMappingPath(SourceFile file, String block, String annotation) {
    return annotationArguments(file, block, annotation).stream()
        .anyMatch(args -> attributeValue(args, "value") != null || attributeValue(args, "path") != null);
  }

static List<String> routeSymbols(
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

  /** Recognizes the bounded Spring component stereotype subset. */
static boolean springComponent(SourceFile file) {
    return springComponentAnnotations(file, file.classAnnotationBlock());
  }

  /** Extracts conditions only from canonical Spring registration annotations. */
static List<String> conditionAnnotations(SourceFile file, String block) {
    return canonicalAnnotationSources(file, block, List.of(
        "Conditional", "ConditionalOnBean", "ConditionalOnMissingBean", "ConditionalOnClass",
        "ConditionalOnMissingClass", "ConditionalOnProperty", "ConditionalOnExpression"));
  }

static List<String> annotationQualifiers(
      SourceFile file, String source, Matcher matcher) {
    ParsedAnnotation annotation = parseAnnotation(source, matcher);
    if (file != null && SPRING_BEAN_ANNOTATION_TYPES.containsKey(annotation.name())
        && !canonicalSpringAnnotation(
            file, annotation.source(), annotation.name())) {
      return List.of();
    }
    if (annotation.name().equals("Resource")) {
      return resourceQualifier(annotation.arguments());
    }
    if (isBeanNameAnnotation(annotation.name())) {
      return beanNameQualifier(annotation.arguments());
    }
    if (NON_QUALIFIER_ANNOTATIONS.contains(annotation.name())) {
      return List.of();
    }
    if (annotation.name().equals("Qualifier") || annotation.name().equals("Named")) {
      String value = quotedValue(
          annotation.arguments().replaceFirst("^value\\s*=\\s*", "").trim());
      return List.of(annotation.name() + ":" + value);
    }
    return List.of(annotation.name());
  }
}
