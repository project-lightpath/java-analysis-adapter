package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    // starts the header. Annotation arguments are consumed with BALANCED
    // parentheses: a flat [^)]* pattern cannot match a nested-parenthesis
    // argument (@A(@B(1))), an unparseable annotation could hide a clause,
    // and an unrecognized clause would let type variables resolve through
    // imports — so an unparseable prefix abstains, fail closed.
    int clauseStart = prefixEnd(header);
    if (clauseStart < 0) {
      return unparsed();
    }
    if (clauseStart >= header.length() || header.charAt(clauseStart) != '<') {
      return none();
    }
    int clauseEnd = clauseEnd(header, clauseStart);
    if (clauseEnd < 0) {
      return unparsed();
    }
    Set<String> names = clauseNames(header.substring(clauseStart + 1, clauseEnd));
    return names == null ? unparsed() : new JavacTypeParameterClause(false, names);
  }

  /**
   * The index of the '>' closing the clause opened at {@code clauseStart},
   * or -1 when the clause is unclosed. Balances the angle brackets (a
   * bound with nested generics is consumed whole, so names after the
   * inner '>' are still recognized) and shields parenthesis contents from
   * the count: a bound annotation argument (@A(1 > 0)) can contain '>' or
   * '<', and an unbalanced argument leaves the clause unclosed, which
   * abstains.
   */
  private static int clauseEnd(String header, int clauseStart) {
    int depth = 0;
    int parenDepth = 0;
    for (int index = clauseStart; index < header.length(); index++) {
      char current = header.charAt(index);
      if (current == '(') {
        parenDepth++;
      } else if (current == ')') {
        parenDepth--;
      } else if (parenDepth == 0 && current == '<') {
        depth++;
      } else if (parenDepth == 0 && current == '>') {
        depth--;
        if (depth == 0) {
          return index;
        }
      }
    }
    return -1;
  }

  /**
   * Every type-parameter name in the clause text, or null when a segment
   * does not start with a recognizable name (leading annotations like
   * {@code @Ann T} are stripped; an argument-carrying annotation that
   * survives the strip makes the segment unparsable, which abstains).
   */
  private static Set<String> clauseNames(String clause) {
    Set<String> names = new HashSet<>();
    for (String segment : splitTopLevel(clause)) {
      String cleaned = segment.replaceAll("@[A-Za-z_][\\w.]*\\s+", "").trim();
      String name = cleaned.split("[\\s<]")[0];
      if (!name.matches("[A-Za-z_$][\\w$]*")) {
        return null;
      }
      names.add(name);
    }
    return names;
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

  /**
   * Splits a type-parameter clause on top-level commas only. Parenthesis
   * contents are shielded from the angle-bracket depth, and commas inside
   * parentheses (a bound annotation argument like @A(f(1, 2))) do not
   * split.
   */
  private static List<String> splitTopLevel(String clause) {
    List<String> segments = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int depth = 0;
    int parenDepth = 0;
    for (char character : clause.toCharArray()) {
      if (character == '(') {
        parenDepth++;
      } else if (character == ')') {
        parenDepth--;
      } else if (parenDepth == 0 && character == '<') {
        depth++;
      } else if (parenDepth == 0 && character == '>') {
        depth--;
      }
      if (character == ',' && depth == 0 && parenDepth == 0) {
        segments.add(current.toString());
        current.setLength(0);
      } else {
        current.append(character);
      }
    }
    segments.add(current.toString());
    return segments;
  }

  /**
   * The index just past the declaration prefix — whitespace, declaration
   * delimiters ({, }, ;), annotations with balanced arguments, and
   * modifiers — which is the position of the type-parameter clause's
   * '{@code <}' when one follows. Returns -1 when an annotation cannot be
   * parsed (malformed name or unbalanced argument): an unrecognizable
   * prefix could hide a clause, so the caller abstains.
   */
  private static int prefixEnd(String header) {
    int index = 0;
    while (index < header.length()) {
      char current = header.charAt(index);
      if (Character.isWhitespace(current)
          || current == '{' || current == '}' || current == ';') {
        index++;
      } else if (current == '@') {
        int annotationEnd = annotationEnd(header, index);
        if (annotationEnd < 0) {
          return -1;
        }
        index = annotationEnd;
      } else {
        int modifierEnd = modifierEnd(header, index);
        if (modifierEnd < 0) {
          break;
        }
        index = modifierEnd;
      }
    }
    return index;
  }

  /**
   * The index just past an annotation at {@code from}: the qualified name
   * and, when present, its argument list with BALANCED parentheses (a
   * flat [^)]* match cannot consume a nested-parenthesis argument like
   * @A(@B(1))). Whitespace between the annotation's tokens is legal Java
   * (@A (@B(1))) and is skipped at both boundaries. Returns -1 when the
   * annotation cannot be parsed — a malformed name or unbalanced argument
   * could hide a clause, so the caller abstains.
   */
  private static int annotationEnd(String header, int from) {
    int nameEnd = qualifiedNameEnd(header, skipWhitespace(header, from + 1));
    if (nameEnd < 0) {
      return -1;
    }
    int argumentList = skipWhitespace(header, nameEnd);
    if (argumentList >= header.length() || header.charAt(argumentList) != '(') {
      return nameEnd;
    }
    int close = closingParen(header, argumentList);
    return close < 0 ? -1 : close + 1;
  }

  /** The first index at or after {@code from} that is not whitespace. */
  private static int skipWhitespace(String header, int from) {
    int index = from;
    while (index < header.length() && Character.isWhitespace(header.charAt(index))) {
      index++;
    }
    return index;
  }

  /** The index just past a qualified annotation name at {@code from}, or -1. */
  private static int qualifiedNameEnd(String header, int from) {
    int index = identifierEnd(header, from);
    if (index < 0) {
      return -1;
    }
    while (index + 1 < header.length()
        && header.charAt(index) == '.'
        && Character.isJavaIdentifierStart(header.charAt(index + 1))) {
      index = identifierEnd(header, index + 1);
    }
    return index;
  }

  /** The index just past an identifier at {@code from}, or -1. */
  private static int identifierEnd(String header, int from) {
    if (from >= header.length()
        || !Character.isJavaIdentifierStart(header.charAt(from))) {
      return -1;
    }
    int index = from + 1;
    while (index < header.length()
        && Character.isJavaIdentifierPart(header.charAt(index))) {
      index++;
    }
    return index;
  }

  /** The index of the parenthesis closing the one at {@code open}, or -1. */
  private static int closingParen(String header, int open) {
    int depth = 0;
    for (int index = open; index < header.length(); index++) {
      char current = header.charAt(index);
      if (current == '(') {
        depth++;
      } else if (current == ')') {
        depth--;
        if (depth == 0) {
          return index;
        }
      }
    }
    return -1;
  }

  private static final List<String> MODIFIERS = List.of(
      "public", "protected", "private", "static", "final", "synchronized",
      "abstract", "default", "strictfp", "native");

  /** The index just past a modifier keyword at {@code from}, or -1. */
  private static int modifierEnd(String header, int from) {
    for (String modifier : MODIFIERS) {
      int end = from + modifier.length();
      if (header.startsWith(modifier, from)
          && (end >= header.length()
              || !Character.isJavaIdentifierPart(header.charAt(end)))) {
        return end;
      }
    }
    return -1;
  }
}
