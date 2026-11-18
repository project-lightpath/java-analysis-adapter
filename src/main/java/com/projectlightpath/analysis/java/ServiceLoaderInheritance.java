package com.projectlightpath.analysis.java;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cross-file superclass resolution for ServiceLoader shadowing (JLS 6.4.1/6.5).
 *
 * <p>Bounded, single level: an unqualified extends-clause reference is resolved
 * by JLS precedence (single-type import, then same package, then on-demand
 * import); a package-qualified reference is resolved directly. A resolved
 * superclass whose top-level type or a direct member is named
 * {@code ServiceLoader} shadows the site. Multi-level chains, implements
 * clauses, later superinterfaces, nested-type qualifiers, and superclasses
 * outside the selected files are documented residuals.
 */
final class ServiceLoaderInheritance {
  /** The extends-clause reference in a type declaration header (no generics). */
  private static final Pattern EXTENDS_REFERENCE = Pattern.compile(
      "(?U)extends\\s+([\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*"
          + "(?:\\s*\\.\\s*[\\p{javaJavaIdentifierStart}]"
          + "[\\p{javaJavaIdentifierPart}]*)*)");

  private static final Pattern PRIVATE_MODIFIER = Pattern.compile("\\bprivate\\b");

  private ServiceLoaderInheritance() {}

  /**
   * True when a static on-demand import names a selected type that declares a
   * member ServiceLoader: that member and java.util.ServiceLoader are then
   * ambiguous, so the wildcard cannot own the simple name.
   */
  static boolean staticOnDemandAmbiguous(
      SourceMetadataScanner scanner, String source) {
    for (JavaImports.Import imported : JavaImports.parse(source)) {
      if (!imported.isStatic() || !imported.onDemand()) {
        continue;
      }
      String target = imported.simpleName();
      if (scanner.files().stream()
          .filter(candidate -> candidate.simpleTypeName().equals(target))
          .anyMatch(ServiceLoaderInheritance::declaresInheritableServiceLoaderType)) {
        return true;
      }
    }
    return false;
  }

  /** True when an enclosing type inherits a ServiceLoader member type. */
  static boolean shadowsAt(
      SourceMetadataScanner scanner,
      SourceMetadataScanner.SourceFile file,
      String source,
      int offset) {
    if (source.startsWith("java.util.ServiceLoader", offset)) {
      return false;
    }
    for (SourceMetadataScannerState.TypeInfo type : SourceMetadataScanner.typeDeclarations(file)) {
      if (type.start() <= offset
          && (type.end() < 0 || offset <= type.end())
          && superclassDeclaresServiceLoader(scanner, file, source, type)) {
        return true;
      }
    }
    return false;
  }

  private static boolean superclassDeclaresServiceLoader(
      SourceMetadataScanner scanner,
      SourceMetadataScanner.SourceFile file,
      String source,
      SourceMetadataScannerState.TypeInfo type) {
    String superReference = extendsReference(source, type);
    if (superReference == null) {
      return false;
    }
    int qualifierEnd = superReference.lastIndexOf('.');
    if (qualifierEnd >= 0) {
      // Only the package-qualified form (pkg.Base) is resolved; a nested-type
      // qualifier (Outer.Base) and a selection outside the selected files stay
      // documented residuals.
      String qualifier = superReference.substring(0, qualifierEnd);
      String simpleName = superReference.substring(qualifierEnd + 1);
      return scanner.files().stream()
          .filter(candidate -> candidate.packageName().equals(qualifier)
              && candidate.simpleTypeName().equals(simpleName))
          .anyMatch(ServiceLoaderInheritance::declaresInheritableServiceLoaderType);
    }
    List<SourceMetadataScanner.SourceFile> candidates = scanner.files().stream()
        .filter(candidate -> candidate.simpleTypeName().equals(superReference))
        .toList();
    String singleTypeImport = singleTypeImport(source, superReference);
    if (singleTypeImport != null) {
      // JLS 6.4.1: a single-type import outranks any package member.
      String importPackage = singleTypeImport.substring(
          0, singleTypeImport.length() - superReference.length() - 1);
      return candidates.stream()
          .filter(candidate -> candidate.packageName().equals(importPackage))
          .anyMatch(ServiceLoaderInheritance::declaresInheritableServiceLoaderType);
    }
    List<SourceMetadataScanner.SourceFile> samePackage = candidates.stream()
        .filter(candidate -> candidate.packageName().equals(file.packageName()))
        .toList();
    if (!samePackage.isEmpty()) {
      // A same-package type shadows every on-demand import (JLS 6.4.1).
      return samePackage.stream()
          .anyMatch(ServiceLoaderInheritance::declaresInheritableServiceLoaderType);
    }
    Set<String> onDemandPackages = onDemandImports(source);
    return candidates.stream()
        .filter(candidate -> onDemandPackages.contains(candidate.packageName()))
        .anyMatch(ServiceLoaderInheritance::declaresInheritableServiceLoaderType);
  }

  private static String extendsReference(
      String source, SourceMetadataScannerState.TypeInfo type) {
    int body = source.indexOf('{', type.start());
    int limit = body >= 0 ? body : (type.end() >= 0 ? type.end() + 1 : source.length());
    if (limit <= type.start()) {
      return null;
    }
    String header = source.substring(type.start(), limit);
    int extendsIndex = SourceMetadataScanner.topLevelKeywordIndex(header, "extends");
    if (extendsIndex < 0) {
      return null;
    }
    Matcher target = EXTENDS_REFERENCE.matcher(header);
    target.region(extendsIndex, header.length());
    return target.lookingAt() ? target.group(1).replaceAll("\\s+", "") : null;
  }

  /** Returns the single-type import name (pkg.Type) for a simple name, if any. */
  private static String singleTypeImport(String source, String simpleName) {
    for (JavaImports.Import imported : JavaImports.parse(source)) {
      if (!imported.isStatic() && !imported.onDemand()
          && imported.simpleName().equals(simpleName)) {
        return imported.name();
      }
    }
    return null;
  }

  private static Set<String> onDemandImports(String source) {
    Set<String> result = new LinkedHashSet<>();
    for (JavaImports.Import imported : JavaImports.parse(source)) {
      if (!imported.isStatic() && imported.onDemand()) {
        result.add(imported.name());
      }
    }
    return result;
  }

  /**
   * True when the candidate declares a ServiceLoader type that its top-level
   * type owns: the candidate's own top-level type or one of its direct
   * members. Types nested deeper (a member of a member) and local classes are
   * not inherited by an extending type, so they must not shadow the site.
   */
  static boolean declaresInheritableServiceLoaderType(
      SourceMetadataScanner.SourceFile candidate) {
    List<SourceMetadataScannerState.TypeInfo> types =
        SourceMetadataScanner.typeDeclarations(candidate);
    return types.stream()
        .filter(type -> type.name().equals("ServiceLoader"))
        .filter(type -> !SourceMetadataScanner.isLocalType(candidate, type))
        .filter(type -> !isPrivateMemberType(candidate, type))
        .anyMatch(type -> types.stream()
            .filter(other -> other.start() < type.start() && type.end() <= other.end())
            .allMatch(other -> other.name().equals(candidate.simpleTypeName())));
  }

  /** A private member type is not inherited by an extending type. */
  private static boolean isPrivateMemberType(
      SourceMetadataScanner.SourceFile candidate, SourceMetadataScannerState.TypeInfo type) {
    String source = ServiceLoaderSourceMasker.mask(candidate.structuralSource());
    int boundary = type.start() - 1;
    while (boundary >= 0 && ";{}".indexOf(source.charAt(boundary)) < 0) {
      boundary--;
    }
    return PRIVATE_MODIFIER.matcher(source.substring(boundary + 1, type.start())).find();
  }
}
