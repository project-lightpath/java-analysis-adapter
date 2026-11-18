package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.List;

/**
 * Linear parser for import declarations in already-masked Java source.
 *
 * <p>Regex repetition over dotted names recurses per segment and overflows the
 * stack on pathological inputs (a 2,000-segment import); this scanner is
 * O(n) and allocation-bounded. It accepts the JLS 3.6 whitespace freedom
 * around dots, Unicode identifiers, static imports, and same-line import
 * declarations, and it classifies each import for name-resolution callers.
 */
final class JavaImports {
  private JavaImports() {}

  /** One import declaration; {@code name} never contains whitespace. */
  record Import(String name, boolean onDemand, boolean isStatic) {
    String simpleName() {
      int dot = name.lastIndexOf('.');
      return dot < 0 ? name : name.substring(dot + 1);
    }
  }

  /** Parses every import declaration in {@code source}; malformed ones are skipped. */
  static List<Import> parse(String source) {
    List<Import> result = new ArrayList<>();
    int index = 0;
    while (true) {
      int start = keyword(source, index, "import");
      if (start < 0) {
        return result;
      }
      index = start + "import".length();
      if (!declarationBoundary(source, start)) {
        continue;
      }
      ImportHolder parsed = parseDeclaration(source, index);
      if (parsed == null) {
        continue;
      }
      result.add(parsed.imported());
      index = parsed.end();
    }
  }

  private static ImportHolder parseDeclaration(String source, int afterKeyword) {
    int cursor = skipWhitespace(source, afterKeyword);
    boolean isStatic = wordAt(source, cursor, "static");
    if (isStatic) {
      cursor = skipWhitespace(source, cursor + "static".length());
    }
    QualifiedName name = parseQualifiedName(source, cursor);
    if (name == null
        || (!name.onDemand() && name.value().indexOf('.') < 0)) {
      // A qualified name is mandatory for a single-type import; a dotless
      // import is malformed Java and must not resolve like a real import.
      return null;
    }
    int end = skipWhitespace(source, name.end());
    if (end >= source.length() || source.charAt(end) != ';') {
      return null;
    }
    return new ImportHolder(
        new Import(name.value(), name.onDemand(), isStatic), end + 1);
  }

  private record QualifiedName(String value, boolean onDemand, int end) {}

  private static QualifiedName parseQualifiedName(String source, int start) {
    StringBuilder name = new StringBuilder();
    int cursor = start;
    while (true) {
      if (cursor >= source.length()
          || !Character.isJavaIdentifierStart(source.codePointAt(cursor))) {
        return null;
      }
      int segmentStart = cursor;
      while (cursor < source.length()) {
        int codePoint = source.codePointAt(cursor);
        if (!Character.isJavaIdentifierPart(codePoint)) {
          break;
        }
        cursor += Character.charCount(codePoint);
      }
      name.append(source, segmentStart, cursor);
      int afterSegment = skipWhitespace(source, cursor);
      if (afterSegment >= source.length() || source.charAt(afterSegment) != '.') {
        return new QualifiedName(name.toString(), false, afterSegment);
      }
      int afterDot = skipWhitespace(source, afterSegment + 1);
      if (afterDot < source.length() && source.charAt(afterDot) == '*') {
        return new QualifiedName(
            name.toString(), true, skipWhitespace(source, afterDot + 1));
      }
      name.append('.');
      cursor = afterDot;
    }
  }

  private record ImportHolder(Import imported, int end) {}

  private static int keyword(String source, int from, String keyword) {
    int index = source.indexOf(keyword, from);
    while (index >= 0) {
      boolean before = index == 0 || !Character.isJavaIdentifierPart(source.codePointBefore(index));
      int end = index + keyword.length();
      boolean after = end == source.length() || !Character.isJavaIdentifierPart(source.codePointAt(end));
      if (before && after) {
        return index;
      }
      index = source.indexOf(keyword, index + 1);
    }
    return -1;
  }

  private static boolean declarationBoundary(String source, int start) {
    int index = start - 1;
    while (index >= 0 && Character.isWhitespace(source.charAt(index))) {
      index--;
    }
    return index < 0 || ";{}".indexOf(source.charAt(index)) >= 0;
  }

  private static boolean wordAt(String source, int index, String word) {
    return source.startsWith(word, index)
        && (index + word.length() == source.length()
            || !Character.isJavaIdentifierPart(source.codePointAt(index + word.length())));
  }

  private static int skipWhitespace(String source, int index) {
    while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
      index++;
    }
    return index;
  }
}
