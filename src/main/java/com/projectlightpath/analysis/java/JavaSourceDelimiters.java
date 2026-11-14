package com.projectlightpath.analysis.java;

/** Delimiter-aware structural scans for Java source text. */
final class JavaSourceDelimiters {
  private JavaSourceDelimiters() {}

  /** Finds a generic closing delimiter while ignoring nested Java expressions. */
  static int matchingGenericDelimiter(String source, int opening) {
    GenericState state = new GenericState();
    for (int index = opening; index < source.length(); index++) {
      if (state.consume(source.charAt(index))) {
        return index;
      }
    }
    return -1;
  }

  /** Finds a type body brace without mistaking annotation arrays for the body. */
  static int typeBodyOpening(String source, int start) {
    TypeHeaderState state = new TypeHeaderState();
    for (int index = Math.max(0, start); index < source.length(); index++) {
      if (state.consume(source.charAt(index))) {
        return index;
      }
    }
    return -1;
  }

  private static final class GenericState {
    private int depth;
    private int parentheses;
    private int brackets;
    private int braces;

    boolean consume(char character) {
      switch (character) {
        case '(' -> parentheses++;
        case ')' -> parentheses = Math.max(0, parentheses - 1);
        case '[' -> brackets++;
        case ']' -> brackets = Math.max(0, brackets - 1);
        case '{' -> braces++;
        case '}' -> braces = Math.max(0, braces - 1);
        case '<' -> openAngle();
        case '>' -> { return closeAngle(); }
        default -> { }
      }
      return false;
    }

    private void openAngle() {
      if (atTopLevel()) {
        depth++;
      }
    }

    private boolean closeAngle() {
      if (!atTopLevel() || depth == 0) {
        return false;
      }
      depth--;
      return depth == 0;
    }

    private boolean atTopLevel() {
      return parentheses == 0 && brackets == 0 && braces == 0;
    }
  }

  private static final class TypeHeaderState {
    private int parentheses;
    private int brackets;
    private int braces;
    private int angleBrackets;

    boolean consume(char character) {
      if (character == '(') {
        parentheses++;
      } else if (character == ')') {
        parentheses = Math.max(0, parentheses - 1);
      } else if (character == '[') {
        brackets++;
      } else if (character == ']') {
        brackets = Math.max(0, brackets - 1);
      } else if (character == '<') {
        openAngle();
      } else if (character == '>') {
        closeAngle();
      } else if (character == '{') {
        return openBrace();
      } else if (character == '}') {
        braces = Math.max(0, braces - 1);
      }
      return false;
    }

    private void openAngle() {
      if (atTopLevel()) {
        angleBrackets++;
      }
    }

    private void closeAngle() {
      if (atTopLevel()) {
        angleBrackets = Math.max(0, angleBrackets - 1);
      }
    }

    private boolean openBrace() {
      if (atTopLevel() && angleBrackets == 0) {
        return true;
      }
      braces++;
      return false;
    }

    private boolean atTopLevel() {
      return parentheses == 0 && brackets == 0 && braces == 0;
    }
  }
}
