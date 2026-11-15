package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerTextLayer18 extends SourceMetadataScannerTextLayer17 {

  SourceMetadataScannerTextLayer18(AnalysisContext context) {
    super(context);
  }



  /** Builds unresolved evidence for one dynamic proxy construction site. */
  DynamicFeature dynamicProxyFeature(
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

static MethodInfo methodContaining(SourceFile file, int index) {
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

static List<String> httpMethods(SourceFile file, String block, String annotationName) {
    Optional<String> fixed = fixedHttpMethod(annotationName);
    if (fixed.isPresent()) {
      return fixed.stream().toList();
    }
    return "RequestMapping".equals(annotationName)
        ? requestMappingHttpMethods(file, block)
        : List.of();
  }

  /** Extracts literal Spring mapping paths from value and path attributes. */
static List<String> mappingPaths(SourceFile file, String block, String annotation) {
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

  /** Finds the declared source type matching one source-level owner name. */
static Optional<TypeInfo> typeDeclaration(SourceFile file, String typeName) {
    return typeDeclarations(file).stream()
        .filter(type -> typeContaining(file, type.start()).equals(typeName))
        .findFirst();
  }

  /** Extracts profile values from an annotation block. */
static List<String> profileValues(String block) {
    return values(block, "Profile", null);
  }

  /** Extracts profile values only from the canonical Spring annotation. */
static List<String> profileValues(SourceFile file, String block) {
    return values(file, block, "Profile", null);
  }

static List<String> qualifierNames(SourceFile file, String block) {
    String source = stripComments(block == null ? "" : block);
    List<String> result = new ArrayList<>();
    Matcher matcher = Pattern.compile("@([A-Za-z_][\\w.]*)")
        .matcher(maskStringLiterals(source));
    while (matcher.find()) {
      result.addAll(annotationQualifiers(file, source, matcher));
    }
    return List.copyOf(new LinkedHashSet<>(result));
  }

static List<String> mediaValues(String block, String annotation, String attribute) {
    return mediaValues(values(block, annotation, attribute));
  }

  /** Extracts media values only from canonically resolved Spring route annotations. */
static List<String> mediaValues(
      SourceFile file, String block, String annotation, String attribute) {
    return mediaValues(values(file, block, annotation, attribute));
  }

  /** Returns whether a source declaration is an interface in the selected roots. */
  boolean isInterfaceType(String typeName) {
    return declaredType(typeName)
        .map(type -> type.kind().equals("interface"))
        .orElse(false);
  }

  Optional<TypeVariableBounds> typeVariableBounds(
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

  String qualifiedThisOwner(SourceFile file, String qualifier, int siteOffset) {
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

  boolean isAccessibleMemberType(String candidate, SourceFile siteFile) {
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
}
