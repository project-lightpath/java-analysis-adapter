package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerTextLayer10 extends SourceMetadataScannerTextLayer09 {

  SourceMetadataScannerTextLayer10(AnalysisContext context) {
    super(context);
  }



static void addClassParents(
      List<String> result,
      String header,
      int extendsIndex,
      int implementsIndex,
      int permitsIndex) {
    if (extendsIndex >= 0) {
      int end = implementsIndex >= 0
          ? implementsIndex
          : permitsIndex >= 0 ? permitsIndex : header.length();
      List<String> superclass = typeNames(header.substring(extendsIndex + "extends".length(), end));
      if (!superclass.isEmpty()) {
        result.add(superclass.get(0));
      }
    }
    if (implementsIndex >= 0) {
      int end = permitsIndex >= 0 ? permitsIndex : header.length();
      result.addAll(typeNames(header.substring(implementsIndex + "implements".length(), end)));
    }
  }

static String sourceTypeName(SourceFile file, TypeInfo target) {
    return typeDeclarations(file).stream()
        .filter(type -> type.start() <= target.start() && type.end() >= target.start())
        .sorted(Comparator.comparingInt(TypeInfo::start))
        .map(TypeInfo::name)
        .collect(java.util.stream.Collectors.joining("."));
  }

static DeclarationScope catchOrResourceScope(String source, int declaration) {
    return catchScope(source, declaration)
        .or(() -> resourceScope(source, declaration))
        .orElse(null);
  }

static int statementEnd(String source, int start) {
    int position = skipWhitespace(source, start);
    if (position >= source.length()) {
      return source.length() - 1;
    }
    if (startsKeyword(source, position, "if")) {
      return conditionalStatementEnd(source, position, "if");
    }
    if (startsKeyword(source, position, "for")
        || startsKeyword(source, position, "while")
        || startsKeyword(source, position, "synchronized")
        || startsKeyword(source, position, "switch")) {
      return parenthesizedStatementEnd(source, position);
    }
    if (startsKeyword(source, position, "do")) {
      return doStatementEnd(source, position);
    }
    if (startsKeyword(source, position, "try")) {
      return tryStatementEnd(source, position);
    }
    int labelEnd = labelEnd(source, position);
    if (labelEnd >= 0) {
      return statementEnd(source, labelEnd);
    }
    return source.charAt(position) == '{'
        ? matchingDelimiter(source, position, '{', '}')
        : simpleStatementEnd(source, position);
  }

static int parenthesizedStatementEnd(String source, int start) {
    int opening = source.indexOf('(', start);
    int closing = opening < 0 ? -1 : matchingDelimiter(source, opening, '(', ')');
    return closing < 0 ? simpleStatementEnd(source, start) : statementEnd(source, closing + 1);
  }

static int doStatementEnd(String source, int start) {
    int bodyEnd = statementEnd(source, start + 2);
    int whileStart = skipWhitespace(source, bodyEnd + 1);
    if (!startsKeyword(source, whileStart, "while")) {
      return bodyEnd;
    }
    int opening = source.indexOf('(', whileStart);
    int closing = opening < 0 ? -1 : matchingDelimiter(source, opening, '(', ')');
    return closing < 0 ? simpleStatementEnd(source, whileStart) : statementEnd(source, closing + 1);
  }

static int conditionalStatementEnd(String source, int start, String keyword) {
    int opening = source.indexOf('(', start + keyword.length());
    int closing = opening < 0 ? -1 : matchingDelimiter(source, opening, '(', ')');
    if (closing < 0) {
      return simpleStatementEnd(source, start);
    }
    int thenEnd = statementEnd(source, closing + 1);
    int elseStart = skipWhitespace(source, thenEnd + 1);
    if (startsKeyword(source, elseStart, "else")) {
      return statementEnd(source, elseStart + "else".length());
    }
    return thenEnd;
  }

static int tryStatementEnd(String source, int start) {
    int bodyStart = skipWhitespace(source, start + "try".length());
    if (bodyStart < source.length() && source.charAt(bodyStart) == '(') {
      int resourcesEnd = matchingDelimiter(source, bodyStart, '(', ')');
      if (resourcesEnd < 0) {
        return simpleStatementEnd(source, start);
      }
      bodyStart = skipWhitespace(source, resourcesEnd + 1);
    }
    int end = statementEnd(source, bodyStart);
    int next = skipWhitespace(source, end + 1);
    while (startsKeyword(source, next, "catch")) {
      int opening = source.indexOf('(', next + "catch".length());
      int closing = opening < 0 ? -1 : matchingDelimiter(source, opening, '(', ')');
      if (closing < 0) {
        return end;
      }
      end = statementEnd(source, closing + 1);
      next = skipWhitespace(source, end + 1);
    }
    if (startsKeyword(source, next, "finally")) {
      end = statementEnd(source, next + "finally".length());
    }
    return end;
  }

static String firstQuotedArgument(String source, int offset) {
    int opening = source.indexOf('(', offset);
    if (opening < 0) {
      return null;
    }
    int closing = matchingDelimiter(source, opening, '(', ')');
    if (closing < 0) {
      return null;
    }
    int first = skipWhitespace(source, opening + 1);
    if (first >= closing || source.charAt(first) != '"') {
      return null;
    }
    int end = quotedArgumentEnd(source, first + 1, closing);
    if (end < 0 || !quotedArgumentIsFirst(source, end + 1, closing)) {
      return null;
    }
    return source.substring(first + 1, end);
  }
}
