package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerTextLayer15 extends SourceMetadataScannerTextLayer14 {

  SourceMetadataScannerTextLayer15(AnalysisContext context) {
    super(context);
  }



  /** Attributes a dynamic site in a field initializer to the instance or static initializer. */
static Optional<MethodInfo> initializerContaining(SourceFile file, int index) {
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

static int localTypeOrdinal(SourceFile file, TypeInfo type) {
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

  /** Returns an anonymous-class scope without inventing a source-only binary owner. */
static AnonymousType anonymousTypeContaining(SourceFile file, int index) {
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

  /** Adds only canonically resolved Spring annotation spellings to evidence. */
static void addCanonicalSymbols(
      List<String> symbols, SourceFile file, String block, String... names) {
    for (String name : names) {
      for (AnnotationOccurrence occurrence : canonicalSpringAnnotationOccurrences(file, block, name)) {
        symbols.add(occurrence.source());
      }
    }
  }

  /** Recognizes the bounded Spring component stereotype subset in an annotation block. */
static boolean springComponentAnnotations(SourceFile file, String block) {
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

  /** Extracts source spellings for canonically resolved Spring annotations. */
static List<String> canonicalAnnotationSources(
      SourceFile file, String block, List<String> names) {
    List<String> result = new ArrayList<>();
    for (String name : names) {
      result.addAll(canonicalSpringAnnotationOccurrences(file, block, name).stream()
          .map(AnnotationOccurrence::source)
          .toList());
    }
    return List.copyOf(new LinkedHashSet<>(result));
  }

  /** Resolves a supported Spring annotation without accepting lookalike or wildcard imports. */
static boolean canonicalSpringAnnotation(SourceFile file, String block, String simpleName) {
    return !canonicalSpringAnnotationOccurrences(file, block, simpleName).isEmpty();
  }
}
