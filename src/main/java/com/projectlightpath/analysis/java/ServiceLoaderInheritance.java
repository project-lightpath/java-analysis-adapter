package com.projectlightpath.analysis.java;

import java.util.Comparator;
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
  private static final Pattern PROTECTED_MODIFIER = Pattern.compile("\\bprotected\\b");
  private static final Pattern PUBLIC_MODIFIER = Pattern.compile("\\bpublic\\b");

  private ServiceLoaderInheritance() {}

  /**
   * True when a static on-demand import names a selected type that declares a
   * member ServiceLoader: that member and java.util.ServiceLoader are then
   * ambiguous, so the wildcard cannot own the simple name.
   */
  static boolean staticOnDemandAmbiguous(
      SourceMetadataScanner scanner,
      SourceMetadataScanner.SourceFile file,
      String source) {
    for (JavaImports.Import imported : JavaImports.parse(source)) {
      if (!imported.isStatic() || !imported.onDemand()) {
        continue;
      }
      String owner = imported.name();
      List<SourceMetadataScanner.SourceFile> owners = scanner.files().stream()
          .filter(candidate -> candidate.typeName().equals(owner))
          .toList();
      if (owners.isEmpty()) {
        // The named owner is outside the selected files. If a selected type
        // shares its simple name, the owner could still declare the member, so
        // the ambiguity is unprovable and abstention is the safe direction.
        String simpleName = imported.simpleName();
        if (scanner.files().stream()
            .anyMatch(candidate -> candidate.simpleTypeName().equals(simpleName))) {
          return true;
        }
        continue;
      }
      if (owners.stream()
          .anyMatch(candidate -> declaresInheritableServiceLoaderType(
              candidate, file.packageName()))) {
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
          .anyMatch(candidate -> declaresInheritableServiceLoaderType(
              candidate, file.packageName()));
    }
    return resolveSimpleType(scanner, file, source, superReference).stream()
        .anyMatch(candidate -> declaresInheritableServiceLoaderType(
            candidate, file.packageName()));
  }

  /**
   * Resolves a simple type name by JLS 6.4.1 precedence: single-type import,
   * then same-package types, then on-demand imports. Empty when unresolved.
   */
  static List<SourceMetadataScanner.SourceFile> resolveSimpleType(
      SourceMetadataScanner scanner,
      SourceMetadataScanner.SourceFile file,
      String source,
      String simpleName) {
    List<SourceMetadataScanner.SourceFile> candidates = scanner.files().stream()
        .filter(candidate -> candidate.simpleTypeName().equals(simpleName))
        .toList();
    String singleTypeImport = singleTypeImport(source, simpleName);
    if (singleTypeImport != null) {
      // JLS 6.4.1: a single-type import outranks any package member.
      String importPackage = singleTypeImport.substring(
          0, singleTypeImport.length() - simpleName.length() - 1);
      return candidates.stream()
          .filter(candidate -> candidate.packageName().equals(importPackage))
          .toList();
    }
    List<SourceMetadataScanner.SourceFile> samePackage = candidates.stream()
        .filter(candidate -> candidate.packageName().equals(file.packageName()))
        .toList();
    if (!samePackage.isEmpty()) {
      // A same-package type shadows every on-demand import (JLS 6.4.1).
      return samePackage;
    }
    Set<String> onDemandPackages = onDemandImports(source);
    return candidates.stream()
        .filter(candidate -> onDemandPackages.contains(candidate.packageName()))
        .toList();
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
   * A private member is never inherited; a package-private member is inherited
   * only by a subclass in the same package (JLS 8.5).
   */
  static boolean declaresInheritableServiceLoaderType(
      SourceMetadataScanner.SourceFile candidate, String sitePackage) {
    List<SourceMetadataScannerState.TypeInfo> types =
        SourceMetadataScanner.typeDeclarations(candidate);
    List<SourceMetadataScannerState.TypeInfo> named = types.stream()
        .filter(type -> type.name().equals("ServiceLoader"))
        .filter(type -> !SourceMetadataScanner.isLocalType(candidate, type))
        .toList();
    if (named.isEmpty()) {
      return false;
    }
    String source = ServiceLoaderSourceMasker.mask(candidate.structuralSource());
    String masked = SourceMetadataScanner.maskAnnotationArguments(source);
    return named.stream()
        .filter(type -> isInheritableMemberType(
            candidate, masked, type, sitePackage, types))
        .anyMatch(type -> types.stream()
            .filter(other -> other.start() < type.start() && type.end() <= other.end())
            .allMatch(other -> other.name().equals(candidate.simpleTypeName())));
  }

  /**
   * A private member type is never inherited; a package-private member type is
   * inherited only inside its own package (JLS 8.5). Every member type of an
   * interface is implicitly public (JLS 9.3).
   */
  private static boolean isInheritableMemberType(
      SourceMetadataScanner.SourceFile candidate,
      String source,
      SourceMetadataScannerState.TypeInfo type,
      String sitePackage,
      List<SourceMetadataScannerState.TypeInfo> types) {
    String modifiers = declarationModifiers(source, type);
    if (PRIVATE_MODIFIER.matcher(modifiers).find()) {
      return false;
    }
    return candidate.packageName().equals(sitePackage)
        || PUBLIC_MODIFIER.matcher(modifiers).find()
        || PROTECTED_MODIFIER.matcher(modifiers).find()
        || implicitlyPublicInterfaceMember(types, type);
  }

  /** True when the type's innermost enclosing type is an interface. */
  private static boolean implicitlyPublicInterfaceMember(
      List<SourceMetadataScannerState.TypeInfo> types,
      SourceMetadataScannerState.TypeInfo type) {
    return types.stream()
        .filter(other -> other.start() < type.start() && type.end() <= other.end())
        .max(Comparator.comparingInt(SourceMetadataScannerState.TypeInfo::start))
        .map(other -> other.kind().equals("interface"))
        .orElse(false);
  }

  /** The declaration header between the previous member boundary and the type. */
  private static String declarationModifiers(
      String source, SourceMetadataScannerState.TypeInfo type) {
    int boundary = type.start() - 1;
    while (boundary >= 0 && ";{}".indexOf(source.charAt(boundary)) < 0) {
      boundary--;
    }
    return source.substring(boundary + 1, type.start());
  }
}
