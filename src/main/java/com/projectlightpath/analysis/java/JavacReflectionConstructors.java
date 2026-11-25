package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.List;

/**
 * The constructor's REFLECTION-domain parameter vectors (issue #25): javac's
 * source vector omits parameters the runtime constructor still takes — the
 * synthetic enclosing instance of a non-static MEMBER nested owner, and the
 * synthetic name/ordinal pair of an enum constructor — so the synthetic
 * prefix is prepended before comparing against a lookup site's
 * class-literal arguments, and the descriptor renders from the same
 * effective binary vector.
 */
final class JavacReflectionConstructors {

  private JavacReflectionConstructors() {}

  /** LOCAL/ANONYMOUS owners: the constructor's synthetic parameters (if
   * any) cannot be derived from the nesting kind — fail closed. Also
   * guards a binary name that does not end with the owner's simple name
   * (the enclosing derivation would be unprovable). */
  static boolean nestingUnprovable(JavacSourceSymbolIndex.TypeInfo owner) {
    if ("TOP_LEVEL".equals(owner.nestingKind()) || "MEMBER".equals(owner.nestingKind())) {
      return !owner.binaryName().endsWith(simpleName(owner));
    }
    return true;
  }

  /**
   * The member's parameter vector in the REFLECTION domain: the synthetic
   * prefix is prepended to the source vector before comparing.
   */
  static List<String> reflectionParameters(
      JavacSourceSymbolIndex.MemberInfo member,
      JavacSourceSymbolIndex.TypeInfo owner,
      boolean constructorLookup) {
    return prefixed(constructorLookup, syntheticPrefixes(owner, false), member.parameterTypes());
  }

  /** The binary twin of {@link #reflectionParameters}. */
  static List<String> reflectionParameterBinaries(
      JavacSourceSymbolIndex.MemberInfo member,
      JavacSourceSymbolIndex.TypeInfo owner,
      boolean constructorLookup) {
    return prefixed(constructorLookup, syntheticPrefixes(owner, true),
        member.parameterBinaryTypes());
  }

  private static List<String> prefixed(
      boolean constructorLookup, List<String> prefixes, List<String> source) {
    if (!constructorLookup || prefixes.isEmpty()) {
      return source;
    }
    List<String> effective = new ArrayList<>(prefixes);
    effective.addAll(source);
    return List.copyOf(effective);
  }

  /**
   * The synthetic parameters the runtime constructor takes beyond the
   * source declaration: enums always carry {@code (String name, int
   * ordinal)} first (and are never inner, so no enclosing instance); a
   * non-static MEMBER nested owner carries its enclosing instance first
   * (canonical for matching, binary for the descriptor).
   */
  private static List<String> syntheticPrefixes(
      JavacSourceSymbolIndex.TypeInfo owner, boolean binary) {
    if ("enum".equals(owner.shape().declarationKind())) {
      return List.of("java.lang.String", "int");
    }
    if (!owner.shape().staticType() && "MEMBER".equals(owner.nestingKind())) {
      return List.of(binary ? enclosingBinary(owner) : enclosingCanonical(owner));
    }
    return List.of();
  }

  /**
   * The owner's simple name, taken from the CANONICAL spelling: a '$' in
   * the simple name (demo.Outer.Inner$Part) stays part of it, so the
   * enclosing names are derived by removing the exact simple-name suffix —
   * never by splitting at the last '$', which a '$'-named simple name
   * would corrupt (the enclosing of demo.Outer$Inner$Part is demo.Outer,
   * not demo.Outer$Inner).
   */
  private static String simpleName(JavacSourceSymbolIndex.TypeInfo owner) {
    String canonical = owner.canonicalName();
    int dot = canonical.lastIndexOf('.');
    return dot < 0 ? canonical : canonical.substring(dot + 1);
  }

  private static String enclosingCanonical(JavacSourceSymbolIndex.TypeInfo owner) {
    String canonical = owner.canonicalName();
    return canonical.substring(0, canonical.length() - simpleName(owner).length() - 1);
  }

  /** The enclosing's binary name, derived by the same suffix removal. */
  private static String enclosingBinary(JavacSourceSymbolIndex.TypeInfo owner) {
    String binary = owner.binaryName();
    return binary.substring(0, binary.length() - simpleName(owner).length() - 1);
  }
}
