package com.projectlightpath.analysis.java;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Inherited-member eligibility for ServiceLoader shadowing (JLS 8.5/9.3).
 *
 * <p>A private member type is never inherited; a package-private member type is
 * inherited only inside its own package; every member type of an interface is
 * implicitly public.
 */
final class ServiceLoaderMemberTypes {
  private static final Pattern PRIVATE_MODIFIER = Pattern.compile("\\bprivate\\b");
  private static final Pattern PROTECTED_MODIFIER = Pattern.compile("\\bprotected\\b");
  private static final Pattern PUBLIC_MODIFIER = Pattern.compile("\\bpublic\\b");

  private ServiceLoaderMemberTypes() {}

  /**
   * True when the candidate declares a ServiceLoader type that its top-level
   * type owns: the candidate's own top-level type or one of its direct
   * members. Types nested deeper (a member of a member) and local classes are
   * not inherited by an extending type, so they must not shadow the site.
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
