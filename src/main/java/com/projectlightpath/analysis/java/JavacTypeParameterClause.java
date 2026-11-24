package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A method's own {@code <...>} type-parameter clause, parsed from the
 * declaration header: no clause, every parsed name, or an abstention when
 * the clause cannot be parsed completely (an incomplete name set would let
 * a type variable resolve through imports instead of abstaining, reopening
 * the mirrored-overload derangement window).
 */
record JavacTypeParameterClause(boolean abstain, Set<String> names) {

  static JavacTypeParameterClause parse(
      SourceMetadataScannerState.SourceFile file,
      SourceMetadataScannerState.MethodInfo declaration) {
    String source = file.source();
    int parametersAt = source.indexOf(declaration.parameters(), declaration.start());
    if (declaration.parameters().isEmpty() || parametersAt < 0) {
      return none();
    }
    String header = source.substring(declaration.start(), parametersAt);
    // The clause must be the FIRST thing after modifiers, annotations, and
    // any preceding declaration delimiter ({, }, ;) that the extraction
    // start includes — a generic RETURN type (List<String> pick) also
    // contains angle brackets but never starts the header.
    Matcher opening = HEADER_START.matcher(header);
    if (!opening.find()) {
      return none();
    }
    // Balance the angle brackets: a bound with nested generics must be
    // consumed whole, so names after the inner '>' are still recognized.
    int depth = 0;
    int clauseEnd = -1;
    for (int index = opening.end() - 1; index < header.length(); index++) {
      char current = header.charAt(index);
      if (current == '<') {
        depth++;
      } else if (current == '>') {
        depth--;
        if (depth == 0) {
          clauseEnd = index;
          break;
        }
      }
    }
    if (clauseEnd < 0) {
      return unparsed();
    }
    String clause = header.substring(opening.end(), clauseEnd);
    Set<String> names = new HashSet<>();
    for (String segment : splitTopLevel(clause)) {
      // Strip leading annotations (@Ann T) and take the first identifier.
      String cleaned = segment.replaceAll("@[A-Za-z_][\\w.]*\\s+", "").trim();
      String name = cleaned.split("[\\s<]")[0];
      if (!name.matches("[A-Za-z_$][\\w$]*")) {
        return unparsed();
      }
      names.add(name);
    }
    return new JavacTypeParameterClause(false, names);
  }

  static JavacTypeParameterClause none() {
    return new JavacTypeParameterClause(false, Set.of());
  }

  static JavacTypeParameterClause unparsed() {
    return new JavacTypeParameterClause(true, Set.of());
  }

  /** Splits a type-parameter clause on top-level commas only. */
  private static List<String> splitTopLevel(String clause) {
    List<String> segments = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int depth = 0;
    for (char character : clause.toCharArray()) {
      if (character == '<') {
        depth++;
      } else if (character == '>') {
        depth--;
      }
      if (character == ',' && depth == 0) {
        segments.add(current.toString());
        current.setLength(0);
      } else {
        current.append(character);
      }
    }
    segments.add(current.toString());
    return segments;
  }

  private static final Pattern HEADER_START = Pattern.compile(
      "^(?:\\s|[{};]|@[A-Za-z_][\\w.]*(?:\\([^)]*\\))?|public|protected|private|"
          + "static|final|synchronized|abstract|default|strictfp|native)*"
          + "\\s*<");
}
