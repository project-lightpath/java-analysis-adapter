package com.projectlightpath.analysis.java;

import java.util.List;

/**
 * Owner-side proof for static-import-on-demand ambiguity (JLS 7.5.4).
 *
 * <p>An owner outside the selected files, an {@code implements} clause, a
 * second superinterface, or a superclass that itself inherits leaves the
 * member set unproven and abstains.
 */
final class StaticImportOwnerResolver {
  private StaticImportOwnerResolver() {}

  /**
   * True when the owner declares a ServiceLoader member, inherits one through
   * its direct superclass, or has any unresolved/multi-branch inheritance that
   * leaves the member set unproven.
   */
  static boolean mayDeclareServiceLoader(
      SourceMetadataScanner scanner,
      SourceMetadataScanner.SourceFile candidate,
      String sitePackage) {
    if (ServiceLoaderMemberTypes.declaresInheritableServiceLoaderType(
        candidate, sitePackage)) {
      return true;
    }
    String source = ServiceLoaderSourceMasker.mask(candidate.structuralSource());
    String masked = SourceMetadataScanner.maskAnnotationArguments(source);
    for (SourceMetadataScannerState.TypeInfo type
        : SourceMetadataScanner.typeDeclarations(candidate)) {
      if (!type.name().equals(candidate.simpleTypeName())) {
        continue;
      }
      String header = ServiceLoaderInheritance.typeHeader(masked, type);
      if (SourceMetadataScanner.topLevelKeywordIndex(header, "implements") >= 0
          || hasSecondSupertype(header)) {
        return true;
      }
      String reference = ServiceLoaderInheritance.extendsReference(masked, type);
      if (reference == null) {
        continue;
      }
      List<SourceMetadataScanner.SourceFile> superclasses =
          resolvedSuperclasses(scanner, candidate, masked, reference);
      if (superclasses.isEmpty()) {
        return true;
      }
      if (ServiceLoaderInheritance.superclassDeclaresServiceLoader(
          scanner, candidate, masked, type, sitePackage)) {
        return true;
      }
      if (superclasses.stream().anyMatch(StaticImportOwnerResolver::hasUnprovenInheritance)) {
        return true;
      }
    }
    return false;
  }

  /**
   * True when the resolved superclass itself has any extends/implements
   * clause. Its inherited member set is then beyond the one-hop proof and the
   * scan abstains.
   */
  private static boolean hasUnprovenInheritance(
      SourceMetadataScanner.SourceFile file) {
    String source = ServiceLoaderSourceMasker.mask(file.structuralSource());
    String masked = SourceMetadataScanner.maskAnnotationArguments(source);
    for (SourceMetadataScannerState.TypeInfo type
        : SourceMetadataScanner.typeDeclarations(file)) {
      if (!type.name().equals(file.simpleTypeName())) {
        continue;
      }
      String header = ServiceLoaderInheritance.typeHeader(masked, type);
      return SourceMetadataScanner.topLevelKeywordIndex(header, "implements") >= 0
          || hasSecondSupertype(header)
          || ServiceLoaderInheritance.extendsReference(masked, type) != null;
    }
    return false;
  }

  /** True when a top-level comma adds a second extends/superinterface name. */
  private static boolean hasSecondSupertype(String header) {
    int extendsIndex = SourceMetadataScanner.topLevelKeywordIndex(header, "extends");
    if (extendsIndex < 0) {
      return false;
    }
    int depth = 0;
    for (int index = extendsIndex; index < header.length(); index++) {
      char value = header.charAt(index);
      if (value == '<' || value == '(' || value == '[') {
        depth++;
      } else if ((value == '>' || value == ')' || value == ']') && depth > 0) {
        depth--;
      } else if (value == ',' && depth == 0) {
        return true;
      }
    }
    return false;
  }

  /** Resolves the extends reference to every selected source file. */
  private static List<SourceMetadataScanner.SourceFile> resolvedSuperclasses(
      SourceMetadataScanner scanner,
      SourceMetadataScanner.SourceFile file,
      String source,
      String reference) {
    int qualifierEnd = reference.lastIndexOf('.');
    if (qualifierEnd >= 0) {
      String qualifier = reference.substring(0, qualifierEnd);
      String simpleName = reference.substring(qualifierEnd + 1);
      return scanner.files().stream()
          .filter(candidate -> candidate.packageName().equals(qualifier)
              && candidate.simpleTypeName().equals(simpleName))
          .toList();
    }
    return ServiceLoaderInheritance.resolveSimpleType(scanner, file, source, reference);
  }
}
