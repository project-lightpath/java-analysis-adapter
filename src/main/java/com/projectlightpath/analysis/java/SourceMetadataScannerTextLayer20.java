package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerTextLayer20 extends SourceMetadataScannerTextLayer19 {

  SourceMetadataScannerTextLayer20(AnalysisContext context) {
    super(context);
  }



  /** Parses method or constructor parameters and their qualifier annotations. */
  static List<Parameter> parameters(String source) {
    return parameters(null, source);
  }

  /** Parses injection parameters with framework-specific qualifier resolution. */
static List<Parameter> parameters(
      AnalysisContext.Framework framework, SourceFile file, String source) {
    return framework == AnalysisContext.Framework.SPRING_BOOT
        ? parameters(file, source)
        : parameters(source);
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

  List<String> declaredOwnerCandidates(
      String normalized, SourceFile sourceFile, int offset) {
    return normalized.contains(".")
        ? declaredTypeCandidates(normalized, sourceFile, offset)
        : samePackageTypeCandidates(normalized, sourceFile);
  }
}
