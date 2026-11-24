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
      SourceMetadataScannerState.MethodInfo declaration,
      String indexName) {
    String source = file.source();
    if (declaration.parameters().isEmpty()) {
      return none();
    }
    // Mask comments and string literals across the WHOLE file (both
    // offset-preserving): the parameter-list boundary is located on the
    // masked source, because a comment can contain the parameter text
    // verbatim (public /* List p */ <...>) and a raw indexOf would anchor
    // the header inside the comment. A comment can also break the method
    // EXTRACTION pattern, pushing declaration.start() past the clause
    // entirely, so the header window is anchored on the parameter list
    // (the method's own name, then back to the previous declaration
    // delimiter), not on the extraction start. If that boundary cannot be
    // established, the clause scope is unknown: abstain, fail closed.
    String masked = SourceMetadataScanner.maskStringLiterals(
        SourceMetadataScanner.stripComments(source));
    String header = declarationHeader(masked, declaration, indexName);
    if (header == null) {
      return unparsed();
    }
    // The clause must be the FIRST thing after modifiers, annotations, and
    // the preceding declaration delimiter ({, }, ;) — a generic RETURN
    // type (List<String> pick) also contains angle brackets but never
    // starts the header.
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

  /**
   * The declaration header (previous declaration delimiter up to the
   * method name) from the masked source, or null when the parameter-list
   * boundary cannot be established. The parameter text is searched from
   * the extraction start and every occurrence is verified against the
   * method's own name so an identical earlier parameter list cannot
   * misanchor the header.
   */
  private static String declarationHeader(
      String masked, SourceMetadataScannerState.MethodInfo declaration,
      String indexName) {
    int searchFrom = declaration.start();
    int parametersAt;
    while ((parametersAt = masked.indexOf(declaration.parameters(), searchFrom)) >= 0) {
      int nameStart = methodNameStart(masked, parametersAt, indexName);
      if (nameStart >= 0) {
        return masked.substring(declarationBoundary(masked, nameStart), nameStart);
      }
      searchFrom = parametersAt + 1;
    }
    return null;
  }

  /**
   * The start of the declaration name preceding the parameter list, or -1.
   * The name is the INDEX name: the method's own name, or the owner's
   * simple name for constructors (whose textual declarations carry
   * {@code <init>}).
   */
  private static int methodNameStart(String masked, int parametersAt, String indexName) {
    int listStart = parametersAt;
    while (listStart > 0 && Character.isWhitespace(masked.charAt(listStart - 1))) {
      listStart--;
    }
    if (listStart == 0 || masked.charAt(listStart - 1) != '(') {
      return -1;
    }
    int nameEnd = listStart - 1;
    while (nameEnd > 0 && Character.isWhitespace(masked.charAt(nameEnd - 1))) {
      nameEnd--;
    }
    int nameStart = nameEnd;
    while (nameStart > 0
        && Character.isJavaIdentifierPart(masked.charAt(nameStart - 1))) {
      nameStart--;
    }
    return masked.substring(nameStart, nameEnd).equals(indexName) ? nameStart : -1;
  }

  /**
   * The previous declaration delimiter at parenthesis depth zero, scanning
   * backwards from the method name. Depth tracking keeps a delimiter
   * inside an annotation argument (@Ann({1})) from ending the window
   * prematurely.
   */
  private static int declarationBoundary(String masked, int nameStart) {
    int depth = 0;
    int index = nameStart;
    while (index > 0) {
      char current = masked.charAt(index - 1);
      if (current == ')') {
        depth++;
      } else if (current == '(') {
        depth--;
      } else if (depth == 0 && (current == '{' || current == '}' || current == ';')) {
        break;
      }
      index--;
    }
    return index;
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
