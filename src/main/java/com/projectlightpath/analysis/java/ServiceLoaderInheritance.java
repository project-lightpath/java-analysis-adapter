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
      "(?U)extends\\s+(?:@[\\p{javaJavaIdentifierStart}]"
          + "[\\p{javaJavaIdentifierPart}]*(?:\\s*\\.\\s*"
          + "[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)*\\s+)*"
          + "([\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*"
          + "(?:\\s*\\.\\s*[\\p{javaJavaIdentifierStart}]"
          + "[\\p{javaJavaIdentifierPart}]*)*)");

  private ServiceLoaderInheritance() {}

  /**
   * True when a static on-demand import names a type whose own or inherited
   * members include a ServiceLoader: that member and java.util.ServiceLoader
   * are then ambiguous, so the wildcard cannot own the simple name. An owner
   * outside the selected files, or one whose superclass cannot be resolved,
   * leaves the member set unproven and abstains.
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
        // The named owner is outside the selected files: whether it declares
        // (or inherits) the member cannot be proven, so abstain.
        return true;
      }
      if (owners.stream()
          .anyMatch(candidate -> StaticImportOwnerResolver.mayDeclareServiceLoader(
              scanner, candidate, file.packageName()))) {
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
          && superclassDeclaresServiceLoader(
              scanner, file, source, type, file.packageName())) {
        return true;
      }
    }
    return false;
  }

  static boolean superclassDeclaresServiceLoader(
      SourceMetadataScanner scanner,
      SourceMetadataScanner.SourceFile file,
      String source,
      SourceMetadataScannerState.TypeInfo type,
      String sitePackage) {
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
          .anyMatch(candidate -> ServiceLoaderMemberTypes
              .declaresInheritableServiceLoaderType(candidate, sitePackage));
    }
    return resolveSimpleType(scanner, file, source, superReference).stream()
        .anyMatch(candidate -> ServiceLoaderMemberTypes
            .declaresInheritableServiceLoaderType(candidate, sitePackage));
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

  /**
   * Returns the extends-clause reference. The source must already be
   * annotation-argument masked (offsets are preserved by masking).
   */
  static String extendsReference(
      String source, SourceMetadataScannerState.TypeInfo type) {
    String header = typeHeader(source, type);
    if (header.isEmpty()) {
      return null;
    }
    int extendsIndex = SourceMetadataScanner.topLevelKeywordIndex(header, "extends");
    if (extendsIndex < 0) {
      return null;
    }
    Matcher target = EXTENDS_REFERENCE.matcher(header);
    target.region(extendsIndex, header.length());
    return target.lookingAt() ? target.group(1).replaceAll("\\s+", "") : null;
  }

  /**
   * The type declaration header, up to its body.
   *
   * <p>The source must already be annotation-argument masked: a brace inside
   * an array-valued annotation argument would otherwise be mistaken for the
   * body opening.
   */
  static String typeHeader(
      String source, SourceMetadataScannerState.TypeInfo type) {
    int body = source.indexOf('{', type.start());
    int limit = body >= 0 ? body : (type.end() >= 0 ? type.end() + 1 : source.length());
    return limit <= type.start() ? "" : source.substring(type.start(), limit);
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

}
