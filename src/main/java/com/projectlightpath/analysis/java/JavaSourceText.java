package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.List;

/** Offset-preserving lexical operations used by the source-backed metadata scanner. */
final class JavaSourceText {
  private JavaSourceText() {}

  static List<String> splitBalanced(String source, char separator, boolean inferGenericStarts) {
    List<String> result = new ArrayList<>();
    SplitState state = new SplitState();
    int start = 0;
    for (int index = 0; index < source.length(); index++) {
      char character = source.charAt(index);
      if (state.consumeQuoted(character)) {
        continue;
      }
      state.clearEscape();
      state.consumeStructural(source, index, inferGenericStarts);
      if (state.isSeparator(character, separator)) {
        result.add(source.substring(start, index).trim());
        start = index + 1;
      }
    }
    result.add(source.substring(start).trim());
    return result;
  }

  static String stripComments(String source) {
    return new CommentMasker(source).strip();
  }

  static String maskStringLiterals(String source) {
    return new LiteralMasker(source).mask();
  }

  /** Finds the matching delimiter while ignoring quoted strings. */
  static int matchingDelimiter(String source, int opening, char open, char close) {
    DelimiterState state = new DelimiterState();
    for (int index = opening; index < source.length(); index++) {
      char character = source.charAt(index);
      if (state.inTextBlock()) {
        if (source.startsWith("\"\"\"", index) && !state.escaped()) {
          index += 2;
          state.closeTextBlock();
        } else {
          state.updateEscape(character);
        }
        continue;
      }
      if (state.startsTextBlock(source, index)) {
        index += 2;
        state.openTextBlock();
        continue;
      }
      if (state.inQuotedLiteral()) {
        state.consumeLiteral(character, open, close);
        continue;
      }
      if (state.startsLiteral(character)) {
        continue;
      }
      if (character == open) {
        state.openDelimiter();
      } else if (character == close && state.closeDelimiter()) {
        return index;
      }
    }
    return -1;
  }

  static int matchingGenericDelimiter(String source, int opening) {
    return JavaSourceDelimiters.matchingGenericDelimiter(source, opening);
  }

  static int typeBodyOpening(String source, int start) {
    return JavaSourceDelimiters.typeBodyOpening(source, start);
  }

  private static boolean looksLikeGenericStart(String source, int opening, int currentDepth) {
    if (currentDepth > 0) {
      return true;
    }
    int previous = previousNonWhitespace(source, opening - 1);
    if (previous < 0 || source.charAt(previous) == '.') {
      return previous >= 0;
    }
    int wordStart = identifierStart(source, previous);
    if (wordStart < 0) {
      return false;
    }
    String word = source.substring(wordStart, previous + 1);
    return word.equals("new") || word.equals("instanceof")
        || precededByQualifiedTypeKeyword(source, wordStart, "new")
        || precededByQualifiedTypeKeyword(source, wordStart, "instanceof")
        || castTypeArgument(source, opening, wordStart);
  }

  private static boolean precededByQualifiedTypeKeyword(
      String source, int typeStart, String keyword) {
    int position = previousNonWhitespace(source, typeStart - 1);
    while (position >= 0 && source.charAt(position) == '.') {
      position = previousNonWhitespace(source, position - 1);
      int qualifierStart = identifierStart(source, position);
      if (qualifierStart < 0) {
        return false;
      }
      position = previousNonWhitespace(source, qualifierStart - 1);
    }
    int keywordStart = identifierStart(source, position);
    return keywordStart >= 0 && keyword.equals(source.substring(keywordStart, position + 1));
  }

  private static boolean castTypeArgument(String source, int opening, int typeStart) {
    int beforeType = previousNonWhitespace(source, typeStart - 1);
    if (beforeType < 0 || source.charAt(beforeType) != '(') {
      return false;
    }
    int closing = matchingGenericDelimiter(source, opening);
    int after = previousNonWhitespace(source, closing + 1);
    return closing >= 0 && after >= 0 && source.charAt(after) == ')';
  }

  private static int previousNonWhitespace(String source, int start) {
    int position = start;
    while (position >= 0 && Character.isWhitespace(source.charAt(position))) {
      position--;
    }
    return position;
  }

  private static int identifierStart(String source, int end) {
    if (end < 0 || !Character.isJavaIdentifierPart(source.charAt(end))) {
      return -1;
    }
    int position = end;
    while (position >= 0 && Character.isJavaIdentifierPart(source.charAt(position))) {
      position--;
    }
    return position + 1;
  }

  private static void blank(char[] value, int start, int length) {
    for (int index = start; index < Math.min(value.length, start + length); index++) {
      value[index] = ' ';
    }
  }

  private static final class CommentMasker {
    private final String source;
    private final StringBuilder result;
    private Mode mode = Mode.CODE;
    private boolean stringLiteral;
    private boolean characterLiteral;
    private boolean escaped;

    CommentMasker(String source) {
      this.source = source;
      result = new StringBuilder(source.length());
    }

    String strip() {
      for (int index = 0; index < source.length(); index++) {
        index += consume(index);
      }
      return result.toString();
    }

    private int consume(int index) {
      return switch (mode) {
        case LINE -> consumeLine(index);
        case BLOCK -> consumeBlock(index);
        case TEXT_BLOCK -> consumeTextBlock(index);
        case CODE -> consumeCode(index);
      };
    }

    private int consumeLine(int index) {
      char current = source.charAt(index);
      if (current == '\n' || current == '\r') {
        mode = Mode.CODE;
        result.append(current);
      } else {
        result.append(' ');
      }
      return 0;
    }

    private int consumeBlock(int index) {
      char current = source.charAt(index);
      char next = next(index);
      if (current == '*' && next == '/') {
        result.append("  ");
        mode = Mode.CODE;
        return 1;
      }
      result.append(current == '\n' || current == '\r' ? current : ' ');
      return 0;
    }

    private int consumeTextBlock(int index) {
      if (source.startsWith("\"\"\"", index) && !escaped) {
        result.append("\"\"\"");
        mode = Mode.CODE;
        return 2;
      }
      char current = source.charAt(index);
      result.append(current);
      updateEscape(current);
      return 0;
    }

    private int consumeCode(int index) {
      char current = source.charAt(index);
      char next = next(index);
      if (!stringLiteral && !characterLiteral && current == '/' && next == '/') {
        result.append("  ");
        mode = Mode.LINE;
        return 1;
      }
      if (!stringLiteral && !characterLiteral && current == '/' && next == '*') {
        result.append("  ");
        mode = Mode.BLOCK;
        return 1;
      }
      if (!stringLiteral && !characterLiteral && source.startsWith("\"\"\"", index)) {
        result.append("\"\"\"");
        mode = Mode.TEXT_BLOCK;
        escaped = false;
        return 2;
      }
      result.append(current);
      updateLiteral(current);
      return 0;
    }

    private char next(int index) {
      return index + 1 < source.length() ? source.charAt(index + 1) : '\0';
    }

    private void updateLiteral(char current) {
      if (current == '\\' && (stringLiteral || characterLiteral) && !escaped) {
        escaped = true;
        return;
      }
      if (current == '"' && !characterLiteral && !escaped) {
        stringLiteral = !stringLiteral;
      } else if (current == '\'' && !stringLiteral && !escaped) {
        characterLiteral = !characterLiteral;
      }
      escaped = false;
    }

    private void updateEscape(char current) {
      escaped = current == '\\' && !escaped;
      if (current != '\\') {
        escaped = false;
      }
    }

    private enum Mode {
      CODE,
      LINE,
      BLOCK,
      TEXT_BLOCK
    }
  }

  private static final class LiteralMasker {
    private final String source;
    private final char[] masked;
    private boolean stringLiteral;
    private boolean textBlock;
    private boolean characterLiteral;
    private boolean escaped;

    LiteralMasker(String source) {
      this.source = source;
      masked = source.toCharArray();
    }

    String mask() {
      for (int index = 0; index < source.length(); index++) {
        if (textBlock) {
          index += consumeTextBlock(index);
        } else if (startsTextBlock(index)) {
          blank(masked, index, 3);
          index += 2;
          textBlock = true;
          escaped = false;
        } else if (inLiteral()) {
          index += consumeLiteral(index);
        } else {
          startLiteral(source.charAt(index));
        }
      }
      return new String(masked);
    }

    private int consumeTextBlock(int index) {
      char current = source.charAt(index);
      if (source.startsWith("\"\"\"", index) && !escaped) {
        blank(masked, index, 3);
        textBlock = false;
        return 2;
      }
      if (current != '\n' && current != '\r') {
        masked[index] = ' ';
      }
      updateEscape(current);
      return 0;
    }

    private boolean startsTextBlock(int index) {
      return !stringLiteral && !characterLiteral && source.startsWith("\"\"\"", index);
    }

    private boolean inLiteral() {
      return stringLiteral || characterLiteral;
    }

    private int consumeLiteral(int index) {
      char current = source.charAt(index);
      if (literalEnds(current)) {
        stringLiteral = false;
        characterLiteral = false;
        return 0;
      }
      if (current == '\\' && !escaped) {
        escaped = true;
        masked[index] = ' ';
        return 0;
      }
      if (current != '\n' && current != '\r') {
        masked[index] = ' ';
      }
      escaped = false;
      return 0;
    }

    private boolean literalEnds(char current) {
      return !escaped && (stringLiteral && current == '"'
          || characterLiteral && current == '\'');
    }

    private void startLiteral(char current) {
      stringLiteral = current == '"';
      characterLiteral = current == '\'';
    }

    private void updateEscape(char current) {
      escaped = current == '\\' && !escaped;
      if (current != '\\') {
        escaped = false;
      }
    }
  }

  private static final class SplitState {
    private int parentheses;
    private int brackets;
    private int braces;
    private int angleBrackets;
    private boolean quoted;
    private boolean escaped;

    boolean consumeQuoted(char character) {
      if (character == '"' && !escaped) {
        quoted = !quoted;
      }
      if (!quoted) {
        return false;
      }
      escaped = character == '\\' ? !escaped : false;
      return true;
    }

    void clearEscape() {
      escaped = false;
    }

    void consumeStructural(String source, int index, boolean inferGenericStarts) {
      char character = source.charAt(index);
      switch (character) {
        case '(' -> parentheses++;
        case ')' -> parentheses--;
        case '[' -> brackets++;
        case ']' -> brackets--;
        case '{' -> braces++;
        case '}' -> braces--;
        case '<' -> consumeOpeningAngle(source, index, inferGenericStarts);
        case '>' -> consumeClosingAngle(inferGenericStarts);
        default -> { }
      }
    }

    private void consumeOpeningAngle(String source, int index, boolean inferGenericStarts) {
      boolean topLevel = parentheses == 0 && brackets == 0 && braces == 0;
      if (!inferGenericStarts ? topLevel : looksLikeGenericStart(source, index, angleBrackets)) {
        angleBrackets++;
      }
    }

    private void consumeClosingAngle(boolean inferGenericStarts) {
      if (inferGenericStarts || parentheses == 0 && brackets == 0 && braces == 0) {
        angleBrackets = Math.max(0, angleBrackets - 1);
      }
    }

    boolean isSeparator(char character, char separator) {
      return character == separator && parentheses == 0 && brackets == 0
          && braces == 0 && angleBrackets == 0;
    }
  }

  private static final class DelimiterState {
    private int depth;
    private boolean quoted;
    private boolean textBlock;
    private boolean characterLiteral;
    private boolean escaped;

    boolean inTextBlock() {
      return textBlock;
    }

    boolean escaped() {
      return escaped;
    }

    void closeTextBlock() {
      textBlock = false;
    }

    void openTextBlock() {
      textBlock = true;
      escaped = false;
    }

    void updateEscape(char character) {
      escaped = character == '\\' && !escaped;
      if (character != '\\') {
        escaped = false;
      }
    }

    boolean startsTextBlock(String source, int index) {
      return !quoted && !characterLiteral && source.startsWith("\"\"\"", index);
    }

    boolean inQuotedLiteral() {
      return quoted || characterLiteral;
    }

    void consumeLiteral(char character, char open, char close) {
      if (character == '"' && !escaped && !characterLiteral) {
        quoted = !quoted;
      } else if (character == '\'' && !escaped && !quoted) {
        characterLiteral = !characterLiteral;
      }
      updateEscape(character);
    }

    boolean startsLiteral(char character) {
      if (character == '"') {
        quoted = true;
        return true;
      }
      if (character == '\'') {
        characterLiteral = true;
        return true;
      }
      return false;
    }

    void openDelimiter() {
      depth++;
    }

    boolean closeDelimiter() {
      return --depth == 0;
    }
  }
}
