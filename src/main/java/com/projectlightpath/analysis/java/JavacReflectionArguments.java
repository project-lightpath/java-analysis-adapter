package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps one reflection lookup's class-literal arguments to canonical erased
 * spellings for comparison against the index member shapes (issue #25).
 * Arguments are NORMALIZED, never published: {@code String.class} names a
 * JDK type the index legitimately lacks, and a wrong normalization can
 * only produce a vector mismatch, which fails closed — never a fabricated
 * owner. An argument that is not a class literal, or whose spelling cannot
 * be normalized, makes the whole vector unresolvable.
 */
final class JavacReflectionArguments {

  private static final Set<String> PRIMITIVES = Set.of(
      "boolean", "byte", "char", "short", "int", "long", "float", "double", "void");

  /** One class-literal parameter argument: Spelling.class / Spelling[].class. */
  private static final Pattern CLASS_LITERAL_ARGUMENT = Pattern.compile(
      "^\\s*([A-Za-z_$][\\w$.]*)\\s*((?:\\[\\s*\\])*)\\s*\\.\\s*class\\s*$");

  private final SourceMetadataScanner scanner;
  private final JavacSourceSymbolIndex index;

  JavacReflectionArguments(SourceMetadataScanner scanner, JavacSourceSymbolIndex index) {
    this.scanner = scanner;
    this.index = index;
  }

  /**
   * The canonical erased parameter vector, or null when any argument is
   * not a normalizable class literal (the site stays on the runtime path).
   * The name literal of method lookups is skipped; blank elements —
   * splitTopLevelArguments("") yields one for an empty list — are ignored.
   */
  List<String> vector(
      SourceMetadataScannerState.SourceFile file,
      List<String> arguments,
      boolean constructorLookup,
      int offset) {
    List<String> nonBlank = arguments.stream().filter(argument -> !argument.isBlank()).toList();
    List<String> parameters = constructorLookup
        ? nonBlank
        : nonBlank.size() > 0 ? nonBlank.subList(1, nonBlank.size()) : List.of();
    List<String> vector = new ArrayList<>();
    for (String argument : parameters) {
      Matcher literal = CLASS_LITERAL_ARGUMENT.matcher(argument);
      if (!literal.matches()) {
        return null;
      }
      String canonical = canonicalArgument(file, literal.group(1), offset);
      if (canonical == null) {
        return null;
      }
      vector.add(canonical + literal.group(2).replaceAll("\\s+", ""));
    }
    return List.copyOf(vector);
  }

  private String canonicalArgument(
      SourceMetadataScannerState.SourceFile file, String spelling, int offset) {
    if (PRIMITIVES.contains(spelling)) {
      return spelling;
    }
    return scanner.resolveReferenceType(spelling, file, offset)
        .map(this::canonicalThroughIndex)
        .orElse(null);
  }

  /** Unifies the textual dialect ($-joined vs dotted) through the index
   * entry; a duplicate-flagged entry makes the parameter type ambiguous,
   * so the vector is unresolvable (the site abstains, never matches). */
  private String canonicalThroughIndex(String resolved) {
    List<JavacSourceSymbolIndex.TypeInfo> entries = index.type(resolved);
    if (entries.stream().anyMatch(entry -> entry.shape().duplicateBinaryName())) {
      return null;
    }
    if (entries.size() == 1
        && !entries.get(0).shape().errorType()) {
      return entries.get(0).canonicalName();
    }
    return resolved;
  }
}
