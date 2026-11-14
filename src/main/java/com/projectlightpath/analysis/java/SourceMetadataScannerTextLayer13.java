package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerTextLayer13 extends SourceMetadataScannerTextLayer12 {

  SourceMetadataScannerTextLayer13(AnalysisContext context) {
    super(context);
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

  /** Finds a dynamic site inside a type-level instance or static initializer block. */
static Optional<MethodInfo> initializerBlockContaining(
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

  /** Keeps field-initializer ownership out of method, block, and lambda locals. */
static boolean isTypeMemberScope(SourceFile file, String source, int index) {
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

static boolean isLocalType(SourceFile file, TypeInfo type) {
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

static int anonymousConstructorOpening(String source, int start) {
    int position = skipExplicitTypeArguments(source, skipWhitespace(source, start));
    if (position < 0) {
      return -1;
    }
    position = skipTypeUseAnnotations(source, position);
    if (position < 0) {
      return -1;
    }
    position = qualifiedIdentifierEnd(source, position);
    if (position < 0) {
      return -1;
    }
    position = skipWhitespace(source, position);
    position = skipConstructorTypeArguments(source, position);
    return position >= 0 && position < source.length() && source.charAt(position) == '('
        ? position
        : -1;
  }

  /** Recognizes CDI scopes, stereotypes, and named beans. */
static boolean cdiBean(String block) {
    return CDI_SCOPES.stream().anyMatch(name -> hasAnnotation(block, name))
        || hasAnnotation(block, "Stereotype")
        || hasAnnotation(block, "Named");
  }

  /** Recognizes common CDI and Spring injection annotations. */
static boolean hasInjectionAnnotation(String block) {
    return hasAnnotation(block, "Inject")
        || hasAnnotation(block, "Autowired")
        || hasAnnotation(block, "Resource");
  }

  /** Recognizes an annotation only when its source spelling resolves canonically. */
static boolean canonicalAnnotation(
      SourceFile file, String block, String simpleName, String... canonicalNames) {
    return !canonicalAnnotationOccurrences(file, block, simpleName, Set.of(canonicalNames)).isEmpty();
  }

  /** Returns only annotation occurrences whose source spelling resolves canonically. */
static List<AnnotationOccurrence> canonicalSpringAnnotationOccurrences(
      SourceFile file, String block, String simpleName) {
    return canonicalAnnotationOccurrences(
        file,
        block,
        simpleName,
        canonicalSpringAnnotationTypes(simpleName));
  }

  /** Keeps only real, interceptable Spring method declarations from the bounded method scan. */
static boolean springProxyMethod(SourceFile file, MethodInfo method) {
    if (method.name().equals(file.simpleTypeName())
        || hasModifier(method.annotationBlock(), "private")
        || hasModifier(method.annotationBlock(), "static")) {
      return false;
    }
    String prefix = maskAnnotationArguments(stripComments(method.annotationBlock())).trim();
    return !prefix.matches("(?s)^(return|throw|if|for|while|switch|catch|new|assert|case|else|do)\\b.*");
  }
}
