package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerTextLayer07 extends SourceMetadataScannerTextLayer06 {

  SourceMetadataScannerTextLayer07(AnalysisContext context) {
    super(context);
  }



static Optional<DeclarationScope> resourceScope(String source, int declaration) {
    Matcher matcher = Pattern.compile("\\btry\\s*\\(").matcher(source);
    while (matcher.find()) {
      int opening = matcher.end() - 1;
      int closing = matchingDelimiter(source, opening, '(', ')');
      if (closing < 0 || declaration <= opening || declaration >= closing
          || !topLevelResourceDeclaration(source, opening, declaration)) {
        continue;
      }
      int bodyStart = skipWhitespace(source, closing + 1);
      if (bodyStart < source.length() && source.charAt(bodyStart) == '{') {
        int bodyEnd = matchingDelimiter(source, bodyStart, '{', '}');
        return Optional.ofNullable(bodyEnd < 0 ? null : new DeclarationScope(declaration, bodyEnd));
      }
    }
    return Optional.empty();
  }

static int labelEnd(String source, int start) {
    int position = start;
    if (position >= source.length() || !Character.isJavaIdentifierStart(source.charAt(position))) {
      return -1;
    }
    position++;
    while (position < source.length() && Character.isJavaIdentifierPart(source.charAt(position))) {
      position++;
    }
    position = skipWhitespace(source, position);
    return position < source.length() && source.charAt(position) == ':' ? position + 1 : -1;
  }

static boolean quotedArgumentIsFirst(String source, int start, int closing) {
    int remainder = skipWhitespace(source, start);
    return remainder >= closing || source.charAt(remainder) == ',';
  }

  /** Reads source text and derives its package and top-level type identity. */
  SourceFile read(Path path, AnalysisContext.SourceRoot root) {
    String source = readSource(path);
    String structuralSource = stripComments(source);
    String maskedStructuralSource = maskStringLiterals(structuralSource);
    Matcher packageMatcher = PACKAGE.matcher(maskedStructuralSource);
    String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
    Matcher typeMatcher = TYPE.matcher(maskedStructuralSource);
    if (!typeMatcher.find()) {
      throw new IllegalStateException("Java source has no top-level type: " + path);
    }
    String simpleName = typeMatcher.group(1);
    String typeName = packageName.isBlank() ? simpleName : packageName + "." + simpleName;
    return new SourceFile(
        path,
        context.displayPath(path),
        source,
        structuralSource,
        packageName,
        typeName,
        simpleName,
        typeMatcher.start(),
        root.generated());
  }

  /** Masks annotation arguments so method declaration scanning remains bounded. */
static String maskAnnotationArguments(String source) {
    char[] masked = source.toCharArray();
    Matcher matcher = Pattern.compile("@[A-Za-z_][\\w.]*").matcher(source);
    while (matcher.find()) {
      int opening = matcher.end();
      while (opening < source.length() && Character.isWhitespace(source.charAt(opening))) {
        opening++;
      }
      if (opening >= source.length() || source.charAt(opening) != '(') {
        continue;
      }
      int closing = matchingDelimiter(source, opening, '(', ')');
      if (closing < 0) {
        continue;
      }
      masked[opening] = ' ';
      masked[closing] = ' ';
      for (int index = opening + 1; index < closing; index++) {
        if (source.charAt(index) != '\n' && source.charAt(index) != '\r') {
          masked[index] = ' ';
        }
      }
    }
    return new String(masked);
  }

static int skipExplicitTypeArguments(String source, int position) {
    if (position >= source.length() || source.charAt(position) != '<') {
      return position;
    }
    int closing = matchingGenericDelimiter(source, position);
    return closing < 0 ? -1 : skipWhitespace(source, closing + 1);
  }

static int skipConstructorTypeArguments(String source, int position) {
    if (position >= source.length() || source.charAt(position) != '<') {
      return position;
    }
    int closing = matchingGenericDelimiter(source, position);
    return closing < 0 ? -1 : skipWhitespace(source, closing + 1);
  }
}
