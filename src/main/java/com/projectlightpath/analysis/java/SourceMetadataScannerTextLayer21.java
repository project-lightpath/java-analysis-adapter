package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerTextLayer21 extends SourceMetadataScannerTextLayer20 {

  SourceMetadataScannerTextLayer21(AnalysisContext context) {
    super(context);
  }



  /** Resolves a source type with lexical nested-type precedence at one source offset. */
  Optional<String> resolveReferenceType(String type, SourceFile sourceFile, int siteOffset) {
    String normalized = stripBalancedGenericArguments(type == null ? "" : type).trim();
    if (normalized.endsWith("...")) {
      normalized = normalized.substring(0, normalized.length() - 3) + "[]";
    }
    if (normalized.endsWith("[]")) {
      return resolveReferenceType(normalized.substring(0, normalized.length() - 2), sourceFile, siteOffset)
          .map(value -> value + "[]");
    }
    String reference = normalized;
    return resolveOwnerType(reference, sourceFile, siteOffset)
        .or(() -> resolveReferenceType(reference, sourceFile));
  }

  /** Resolves a contract while ignoring annotation imports that share its simple name. */
  Optional<String> resolveContractType(String type, SourceFile sourceFile) {
    String normalized = stripBalancedGenericArguments(type == null ? "" : type).trim();
    if (normalized.isBlank()) {
      return Optional.empty();
    }
    List<String> importedSourceTypes = importedTypes(normalized, sourceFile).stream()
        .filter(imported -> files().stream().anyMatch(file -> file.typeName().equals(imported)))
        .distinct()
        .toList();
    if (importedSourceTypes.size() == 1) {
      return Optional.of(importedSourceTypes.get(0));
    }
    if (importedSourceTypes.size() > 1) {
      return Optional.empty();
    }
    List<String> samePackage = files().stream()
        .filter(file -> file.packageName().equals(sourceFile.packageName()))
        .filter(file -> file.simpleTypeName().equals(normalized))
        .map(SourceFile::typeName)
        .distinct()
        .toList();
    if (samePackage.size() == 1) {
      return Optional.of(samePackage.get(0));
    }
    return resolveReferenceType(normalized, sourceFile);
  }

  Optional<String> resolveOwnerType(String spelling, SourceFile sourceFile, int offset) {
    String normalized = stripBalancedGenericArguments(spelling == null ? "" : spelling).trim();
    if (normalized.isBlank()) {
      return Optional.empty();
    }
    if (!normalized.contains(".")) {
      List<String> lexical = lexicalTypeCandidates(normalized, sourceFile, offset);
      if (!lexical.isEmpty()) {
        return lexical.size() == 1 ? Optional.of(lexical.get(0)) : Optional.empty();
      }
    }
    List<String> imported = importedTypes(normalized, sourceFile);
    if (imported.size() == 1) {
      return Optional.of(normalizeDeclaredTypeName(imported.get(0), sourceFile));
    }
    List<String> declared = declaredOwnerCandidates(normalized, sourceFile, offset);
    if (declared.size() == 1) {
      return Optional.of(declared.get(0));
    }
    if (!declared.isEmpty()) {
      return Optional.empty();
    }
    List<String> onDemand = new ArrayList<>(onDemandImportedTypes(normalized, sourceFile));
    onDemand.addAll(onDemandImportedJdkTypes(normalized, sourceFile));
    return uniqueOnDemandOwner(onDemand, normalized, sourceFile)
        .or(() -> knownOwnerType(normalized, sourceFile));
  }

  List<String> resolveParentTypes(TypeReference reference) {
    String source = maskStringLiterals(reference.file().structuralSource());
    int opening = typeBodyOpening(source, reference.type().start());
    if (opening < 0) {
      return List.of();
    }
    String header = source.substring(reference.type().start(), opening);
    return parentSpellings(reference.type(), header).stream()
        .map(parent -> resolveParentType(parent, reference))
        .filter(Objects::nonNull)
        .distinct()
        .toList();
  }

  String resolveParentType(String spelling, TypeReference reference) {
    List<String> declared = declaredTypeCandidates(
        spelling, reference.file(), reference.type().start());
    if (declared.size() == 1 && declared.get(0).contains("$")) {
      return declared.get(0);
    }
    List<String> imported = importedTypes(spelling, reference.file());
    if (imported.size() > 1) {
      return null;
    }
    if (imported.size() == 1) {
      List<String> importedDeclared = declaredTypeCandidates(
          imported.get(0), reference.file(), reference.type().start());
      return importedDeclared.size() == 1
          ? importedDeclared.get(0)
          : normalizeDeclaredTypeName(imported.get(0), reference.file());
    }
    if (declared.size() == 1) {
      return declared.get(0);
    }
    List<String> onDemand = new ArrayList<>(onDemandImportedTypes(spelling, reference.file()));
    onDemand.addAll(onDemandImportedJdkTypes(spelling, reference.file()));
    onDemand = onDemand.stream().distinct().toList();
    if (onDemand.size() == 1) {
      return normalizeDeclaredTypeName(onDemand.get(0), reference.file());
    }
    if (onDemand.size() > 1) {
      return null;
    }
    return resolveContractType(spelling, reference.file()).orElse(null);
  }

  /** Resolves member and inherited types in Java's lexical-owner precedence order. */
  List<String> lexicalTypeCandidates(String simpleName, SourceFile sourceFile, int siteOffset) {
    for (String owner : lexicalOwnerNames(sourceFile, siteOffset)) {
      List<String> local = localTypeCandidatesForOwner(simpleName, owner, sourceFile, siteOffset);
      if (!local.isEmpty()) {
        return local;
      }
      List<String> direct = directMemberTypeCandidates(owner, simpleName);
      if (!direct.isEmpty()) {
        return direct;
      }
      List<String> inherited = inheritedMemberTypeCandidates(owner, simpleName, sourceFile);
      if (!inherited.isEmpty()) {
        return inherited;
      }
    }
    return List.of();
  }

  List<String> inheritedMemberTypeCandidates(
      String owner, String simpleName, SourceFile siteFile) {
    TypeReference reference = findTypeReference(owner);
    if (reference == null) {
      return List.of();
    }
    List<String> frontier = resolveParentTypes(reference);
    Set<String> visited = new HashSet<>();
    while (!frontier.isEmpty()) {
      List<String> matches = new ArrayList<>();
      List<String> next = new ArrayList<>();
      for (String parent : frontier) {
        if (!visited.add(parent)) {
          continue;
        }
        String candidate = parent + "$" + simpleName;
        boolean declaresMemberType = directMemberTypeCandidates(parent, simpleName).contains(candidate);
        if (declaresMemberType) {
          if (isAccessibleMemberType(candidate, siteFile)) {
            matches.add(candidate);
          }
          continue;
        }
        TypeReference parentReference = findTypeReference(parent);
        if (parentReference != null) {
          next.addAll(resolveParentTypes(parentReference));
        }
      }
      if (!matches.isEmpty()) {
        return matches.stream().distinct().toList();
      }
      frontier = next;
    }
    return List.of();
  }
}
