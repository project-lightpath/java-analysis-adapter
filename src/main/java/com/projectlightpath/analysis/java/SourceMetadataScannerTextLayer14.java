package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerTextLayer14 extends SourceMetadataScannerTextLayer13 {

  SourceMetadataScannerTextLayer14(AnalysisContext context) {
    super(context);
  }



static List<String> values(List<String> arguments, String attribute) {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (String args : arguments) {
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
    List<String> onDemandImported = onDemandImportedTypes(normalized, sourceFile);
    if (onDemandImported.size() > 1) {
      return Optional.empty();
    }
    if (onDemandImported.size() == 1) {
      return Optional.of(onDemandImported.get(0));
    }
    return JAVA_LANG_TYPES.contains(normalized)
        ? Optional.of("java.lang." + normalized)
        : Optional.empty();
  }

static Optional<List<String>> typeVariableBoundsInMethod(
      MethodInfo method, String variable) {
    String header = method.annotationBlock().replaceFirst("^[;{}\\s]+", "");
    Matcher opening = Pattern.compile("<(?![=])").matcher(header);
    while (opening.find()) {
      String prefix = header.substring(0, opening.start()).trim();
      if (!isMethodTypeParameterPrefix(prefix)) {
        continue;
      }
      int closing = matchingGenericDelimiter(header, opening.start());
      if (closing < 0) {
        continue;
      }
      return typeVariableBoundsInParameters(header.substring(opening.end(), closing), variable);
    }
    return Optional.empty();
  }

static Optional<List<String>> typeVariableBoundsInType(
      SourceFile file, TypeInfo type, String variable) {
    String source = maskStringLiterals(file.structuralSource());
    int openingBrace = typeBodyOpening(source, type.start());
    if (openingBrace < 0) {
      return Optional.empty();
    }
    String header = source.substring(type.start(), openingBrace);
    Matcher declaration = Pattern.compile(
            "\\b" + Pattern.quote(type.kind()) + "\\s+" + Pattern.quote(type.name())
                + "\\s*<")
        .matcher(header);
    if (!declaration.find()) {
      return Optional.empty();
    }
    int opening = header.indexOf('<', declaration.end() - 1);
    int closing = matchingGenericDelimiter(header, opening);
    if (closing < 0) {
      return Optional.empty();
    }
    return typeVariableBoundsInParameters(header.substring(opening + 1, closing), variable);
  }

static ForScope forInitializerScope(String source, int declaration) {
    Matcher matcher = Pattern.compile("\\bfor\\s*\\(").matcher(source);
    ForScope selected = null;
    while (matcher.find()) {
      Optional<ForScope> candidate = forScopeAt(source, declaration, matcher.end() - 1);
      if (candidate.isPresent()
          && (selected == null || candidate.get().opening() > selected.opening())) {
        selected = candidate.get();
      }
    }
    return selected;
  }

static List<MethodInfo> executableMethods(SourceFile file) {
    List<MethodInfo> result = new ArrayList<>(methods(file));
    result.addAll(constructors(file));
    return List.copyOf(result);
  }

  /** Reports whether a proxy target method name has multiple declared overloads. */
  boolean proxyMethodOverloaded(ProxyTarget target) {
    TypeInfo owner = innermostType(target.file(), target.method().start());
    return methods(target.file()).stream()
        .filter(method -> {
          TypeInfo containing = innermostType(target.file(), method.start());
          return owner != null && containing != null && owner.start() == containing.start();
        })
        .filter(method -> !proxyMethodBodyStatement(method.annotationBlock()))
        .filter(method -> method.name().equals(target.method().name()))
        .count() > 1;
  }

}
