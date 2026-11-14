package com.projectlightpath.analysis.java;

/** Masks Java comments and literals while preserving source offsets and line numbers. */
final class ServiceLoaderSourceMasker {
  private ServiceLoaderSourceMasker() {}

  static String mask(String source) {
    char[] masked = source.toCharArray();
    MaskState state = new MaskState();
    for (int index = 0; index < source.length(); index++) {
      if (state.lineComment) {
        index = maskLineComment(source, masked, index, state);
        continue;
      }
      if (state.blockComment) {
        index = maskBlockComment(source, masked, index, state);
        continue;
      }
      if (state.textBlock) {
        index = maskTextBlock(source, masked, index, state);
        continue;
      }
      if (state.stringLiteral || state.characterLiteral) {
        index = maskQuotedLiteral(source, masked, index, state);
        continue;
      }
      index = maskCode(source, masked, index, state);
    }
    return new String(masked);
  }

  private static int maskLineComment(
      String source, char[] masked, int index, MaskState state) {
    char current = source.charAt(index);
    if (current == '\n' || current == '\r') {
      state.lineComment = false;
    } else {
      masked[index] = ' ';
    }
    return index;
  }

  private static int maskBlockComment(
      String source, char[] masked, int index, MaskState state) {
    char current = source.charAt(index);
    char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
    if (current == '*' && next == '/') {
      masked[index] = ' ';
      masked[index + 1] = ' ';
      state.blockComment = false;
      return index + 1;
    }
    if (current != '\n' && current != '\r') {
      masked[index] = ' ';
    }
    return index;
  }

  private static int maskTextBlock(
      String source, char[] masked, int index, MaskState state) {
    char current = source.charAt(index);
    if (source.startsWith("\"\"\"", index) && !state.escaped) {
      maskCharacters(masked, index, 3);
      state.textBlock = false;
      return index + 2;
    }
    if (current != '\n' && current != '\r') {
      masked[index] = ' ';
    }
    state.escaped = current == '\\' && !state.escaped;
    return index;
  }

  private static int maskQuotedLiteral(
      String source, char[] masked, int index, MaskState state) {
    char current = source.charAt(index);
    boolean closing = (state.stringLiteral && current == '"')
        || (state.characterLiteral && current == '\'');
    if (closing && !state.escaped) {
      masked[index] = ' ';
      state.stringLiteral = false;
      state.characterLiteral = false;
      return index;
    }
    if (current == '\\' && !state.escaped) {
      masked[index] = ' ';
      state.escaped = true;
      return index;
    }
    if (current != '\n' && current != '\r') {
      masked[index] = ' ';
    }
    state.escaped = false;
    return index;
  }

  private static int maskCode(
      String source, char[] masked, int index, MaskState state) {
    char current = source.charAt(index);
    char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
    if (current == '/' && next == '/') {
      masked[index] = ' ';
      masked[index + 1] = ' ';
      state.lineComment = true;
      return index + 1;
    }
    if (current == '/' && next == '*') {
      masked[index] = ' ';
      masked[index + 1] = ' ';
      state.blockComment = true;
      return index + 1;
    }
    if (source.startsWith("\"\"\"", index)) {
      maskCharacters(masked, index, 3);
      state.textBlock = true;
      state.escaped = false;
      return index + 2;
    }
    if (current == '"') {
      masked[index] = ' ';
      state.stringLiteral = true;
    } else if (current == '\'') {
      masked[index] = ' ';
      state.characterLiteral = true;
    }
    return index;
  }

  private static void maskCharacters(char[] masked, int start, int count) {
    for (int index = start; index < start + count; index++) {
      masked[index] = ' ';
    }
  }

  private static final class MaskState {
    private boolean lineComment;
    private boolean blockComment;
    private boolean stringLiteral;
    private boolean textBlock;
    private boolean characterLiteral;
    private boolean escaped;
  }
}
