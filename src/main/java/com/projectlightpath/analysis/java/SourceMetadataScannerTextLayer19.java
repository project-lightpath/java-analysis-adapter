package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerTextLayer19 extends SourceMetadataScannerTextLayer18 {

  SourceMetadataScannerTextLayer19(AnalysisContext context) {
    super(context);
  }



static int localTypeScopeEnd(SourceFile sourceFile, TypeInfo type) {
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

  /** Returns the annotation-bearing declaration prefix for one source-level owner name. */
static String typeAnnotationBlock(SourceFile file, String typeName) {
    if (file.typeName().equals(typeName)) {
      return file.classAnnotationBlock();
    }
    return typeDeclaration(file, typeName)
        .map(type -> typeAnnotationBlock(file, type.start()))
        .orElse("");
  }

static String typeHeader(SourceFile file, String typeName) {
    String source = file.structuralSource();
    int declarationStart = file.typeName().equals(typeName)
        ? file.typeDeclarationStart()
        : typeDeclaration(file, typeName).map(TypeInfo::start).orElse(-1);
    if (declarationStart < 0) {
      return "";
    }
    int bodyStart = typeHeaderBodyStart(source, declarationStart);
    return source.substring(declarationStart, bodyStart);
  }

  /** Extracts normalized qualifiers after removing comments and ordinary annotations. */
static List<String> qualifierNames(String block) {
    return qualifierNames(null, block);
  }

static boolean isAccessibleMethod(
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

  List<String> localTypeCandidatesForOwner(
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

  List<String> localTypeCandidates(String simpleName, SourceFile sourceFile, int siteOffset) {
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

  /** Recognizes a canonical Spring component stereotype on one source type. */
static boolean springComponent(SourceFile file, String typeName) {
    if (file.typeName().equals(typeName)) {
      return springComponent(file);
    }
    return typeDeclaration(file, typeName)
        .filter(type -> !isLocalType(file, type))
        .map(type -> typeAnnotationBlock(file, type.start()))
        .map(block -> springComponentAnnotations(file, block))
        .orElse(false);
  }

  /** Returns the declaration header before its top-level body. */
static String typeHeader(SourceFile file) {
    return typeHeader(file, file.typeName());
  }

  /** Adds explicit or implicit resource-name qualifiers for injection points. */
  static List<String> injectionQualifiers(
      String annotations,
      String defaultName,
      boolean cdi,
      boolean setterMethod) {
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
      if (setterMethod && name.startsWith("set") && name.length() > 3) {
        name = decapitalizeBeanProperty(name.substring(3));
      }
      result.add("ResourceDefault:" + name);
    }
    return List.copyOf(result);
  }

  /** Applies JavaBeans decapitalization to an implicit setter property name. */
  private static String decapitalizeBeanProperty(String property) {
    if (property.length() < 2
        || !Character.isUpperCase(property.charAt(0))
        || !Character.isUpperCase(property.charAt(1))) {
      return Character.toLowerCase(property.charAt(0)) + property.substring(1);
    }
    return property;
  }

static List<Parameter> parameters(SourceFile file, String source) {
    List<Parameter> result = new ArrayList<>();
    for (String raw : splitArguments(source)) {
      List<String> qualifiers = qualifierNames(file, raw);
      String cleaned = maskAnnotationArguments(stripComments(raw))
          .replaceAll("@[A-Za-z_][\\w.]*", " ").trim();
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

  List<String> declaredTypeCandidates(String spelling, SourceFile sourceFile, int siteOffset) {
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
    List<String> declared = allDeclaredTypeNames();
    return normalized.contains(".")
        ? qualifiedDeclaredTypeCandidates(normalized, sourceFile, declared)
        : nestedDeclaredTypeCandidates(normalized, sourceFile, siteOffset, declared);
  }
}
