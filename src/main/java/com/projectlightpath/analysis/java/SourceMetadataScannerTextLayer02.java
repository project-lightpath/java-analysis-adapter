package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerTextLayer02 extends SourceMetadataScannerTextLayer01 {

  SourceMetadataScannerTextLayer02(AnalysisContext context) {
    super(context);
  }



static boolean startsKeyword(String source, int start, String keyword) {
    return start >= 0
        && start + keyword.length() <= source.length()
        && source.startsWith(keyword, start)
        && (start == 0 || !Character.isJavaIdentifierPart(source.charAt(start - 1)))
        && (start + keyword.length() == source.length()
            || !Character.isJavaIdentifierPart(source.charAt(start + keyword.length())));
  }

static int methodBodyOpening(String source, int ownerStart) {
    int parentheses = 0;
    int brackets = 0;
    for (int index = ownerStart; index < source.length(); index++) {
      switch (source.charAt(index)) {
        case '(' -> parentheses++;
        case ')' -> parentheses = Math.max(0, parentheses - 1);
        case '[' -> brackets++;
        case ']' -> brackets = Math.max(0, brackets - 1);
        case '{' -> {
          if (parentheses == 0 && brackets == 0) {
            return index;
          }
        }
        default -> { }
      }
    }
    return -1;
  }

static int enclosingBlockOpening(String source, int methodOpening, int target) {
    List<Integer> blocks = new ArrayList<>();
    blocks.add(methodOpening);
    for (int index = methodOpening + 1; index < Math.min(target, source.length()); index++) {
      char character = source.charAt(index);
      if (character == '{') {
        blocks.add(index);
      } else if (character == '}' && blocks.size() > 1) {
        blocks.remove(blocks.size() - 1);
      }
    }
    return blocks.get(blocks.size() - 1);
  }

static int quotedArgumentEnd(String source, int start, int closing) {
    boolean escaped = false;
    for (int index = start; index < closing; index++) {
      char character = source.charAt(index);
      if (character == '"' && !escaped) {
        return index;
      }
      escaped = character == '\\' && !escaped;
      if (character != '\\') {
        escaped = false;
      }
    }
    return -1;
  }

static String parameterType(String parameter) {
    String normalized = parameter.trim()
        .replaceAll("@[A-Za-z_][\\w.]*", "")
        .replace("final ", "")
        .trim();
    int nameSeparator = normalized.lastIndexOf(' ');
    if (nameSeparator > 0) {
      normalized = normalized.substring(0, nameSeparator).trim();
    }
    return normalized.replace("...", "[]").replaceAll("\\s+", "");
  }

  /** Reads one source file while preserving the scanner's unchecked failure contract. */
static String readSource(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException error) {
      throw new IllegalStateException("cannot read Java source " + path, error);
    }
  }

  /** Skips closing braces captured by METHOD before resolving declaration ownership. */
static int declarationStart(SourceFile file, int start) {
    String source = file.structuralSource();
    int position = Math.max(0, start);
    while (position < source.length()) {
      char current = source.charAt(position);
      if (current != '}' && !Character.isWhitespace(current)) {
        break;
      }
      position++;
    }
    return position;
  }

static boolean isControlFlowKeyword(String name) {
    return switch (name) {
      case "if", "for", "while", "switch", "catch", "try", "synchronized" -> true;
      default -> false;
    };
  }

static String memberHeader(String source, int opening) {
    int start = opening - 1;
    while (start >= 0 && ";{}".indexOf(source.charAt(start)) < 0) {
      start--;
    }
    return source.substring(start + 1, opening).trim();
  }

static int braceDepthAt(String source, int index) {
    int depth = 0;
    for (int position = 0; position < Math.min(index, source.length()); position++) {
      if (source.charAt(position) == '{') {
        depth++;
      } else if (source.charAt(position) == '}') {
        depth = Math.max(0, depth - 1);
      }
    }
    return depth;
  }
}
