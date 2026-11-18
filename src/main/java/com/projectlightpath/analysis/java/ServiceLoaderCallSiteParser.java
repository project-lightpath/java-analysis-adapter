package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the bounded ServiceLoader.load source shape used by provider evidence. */
final class ServiceLoaderCallSiteParser {
  private static final Pattern LOAD = Pattern.compile(
      "(?<![\\w$.])(?:(java\\.util)\\.)?ServiceLoader\\s*\\.\\s*load\\s*\\(");
  private static final Pattern PACKAGE = Pattern.compile(
      "(?U)\\bpackage\\s+([\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*(?:\\.[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)*)\\s*;");
  /**
   * Non-lambda chained calls that cannot replace the flowing provider value.
   * Enumerated from the green chained call-site tests: every Optional chain
   * uses findFirst; orElseThrow appears only on the receiver==null direct
   * path, which DIRECT_PROVIDER_CHAIN in ServiceLoaderCallSiteSource covers
   * without this whitelist (and a whitelisted orElseThrow before a lambda
   * call such as configure(q -> q.record()) fabricates attribution).
   */
  private static final Set<String> VALUE_PRESERVING_CALLS = Set.of("findFirst");

  private ServiceLoaderCallSiteParser() {}

  static List<ServiceLoaderCallSite> parse(String source) {
    return parse(source, false);
  }

  static List<ServiceLoaderCallSite> parse(String source, boolean wildcardImportOwned) {
    return parse(source, wildcardImportOwned, true);
  }

  /** Parses calls with source-proven ownership for imported ServiceLoader types. */
  static List<ServiceLoaderCallSite> parse(
      String source, boolean wildcardImportOwned, boolean serviceLoaderTypeOwned) {
    return parse(source, wildcardImportOwned, serviceLoaderTypeOwned, false);
  }

  /** Parses calls; {@code staticOnDemandAmbiguous} blocks wildcard ownership. */
  static List<ServiceLoaderCallSite> parse(
      String source,
      boolean wildcardImportOwned,
      boolean serviceLoaderTypeOwned,
      boolean staticOnDemandAmbiguous) {
    List<ServiceLoaderCallSite> result = new ArrayList<>();
    String structuralSource = ServiceLoaderSourceMasker.mask(source == null ? "" : source);
    Matcher matcher = LOAD.matcher(structuralSource);
    while (matcher.find()) {
      if (!identifierBoundary(structuralSource, matcher.start())) {
        continue;
      }
      if (matcher.group(1) == null
          && !isJavaUtilServiceLoader(
              structuralSource, wildcardImportOwned, serviceLoaderTypeOwned,
              staticOnDemandAmbiguous)) {
        continue;
      }
      int closing = matchingParenthesis(structuralSource, matcher.end() - 1);
      if (closing < 0) {
        continue;
      }
      List<String> arguments = splitArguments(structuralSource.substring(matcher.end(), closing));
      if (arguments.isEmpty() || arguments.size() > 2) {
        continue;
      }
      String serviceType = classLiteralType(arguments.get(0));
      if (serviceType == null) {
        continue;
      }
      result.add(new ServiceLoaderCallSite(
           serviceType,
           serviceType,
           arguments.size() == 2,
           matcher.start(),
           lineAt(structuralSource, matcher.start())));
    }
    return List.copyOf(result);
  }

  /**
   * Java identifier-part boundary: the ASCII regex lookbehind already blocks
   * ASCII names, so this adds the Unicode cases the JLS treats as one
   * identifier ({@code 外ServiceLoader}).
   */
  private static boolean identifierBoundary(String source, int start) {
    if (start == 0) {
      return true;
    }
    int previous = source.codePointBefore(start);
    return previous != '.' && !Character.isJavaIdentifierPart(previous);
  }

  private static boolean isJavaUtilServiceLoader(
      String source,
      boolean wildcardImportOwned,
      boolean serviceLoaderTypeOwned,
      boolean staticOnDemandAmbiguous) {
    if (!serviceLoaderTypeOwned) {
      return false;
    }
    ImportOwnership ownership = importOwnership(source, staticOnDemandAmbiguous);
    if (ownership.explicit()) {
      return true;
    }
    if (ownership.wildcard() && wildcardImportOwned && !ownership.foreign()) {
      return true;
    }
    if (ownership.foreign()) {
      return false;
    }
    Matcher packageMatcher = PACKAGE.matcher(source);
    return packageMatcher.find() && packageMatcher.group(1).equals("java.util");
  }

  private record ImportOwnership(boolean explicit, boolean wildcard, boolean foreign) {
    ImportOwnership withExplicit() {
      return new ImportOwnership(true, wildcard, foreign);
    }

    ImportOwnership withWildcard() {
      return new ImportOwnership(explicit, true, foreign);
    }

    ImportOwnership withForeign() {
      return new ImportOwnership(explicit, wildcard, true);
    }
  }

  private static ImportOwnership importOwnership(
      String source, boolean staticOnDemandAmbiguous) {
    ImportOwnership result = new ImportOwnership(false, false, false);
    for (JavaImports.Import imported : JavaImports.parse(source)) {
      result = classify(result, imported, staticOnDemandAmbiguous);
    }
    return result;
  }

  private static ImportOwnership classify(
      ImportOwnership ownership,
      JavaImports.Import imported,
      boolean staticOnDemandAmbiguous) {
    if (imported.onDemand()) {
      // Neither on-demand form shadows anything (JLS 6.4.1); a non-static
      // java.util wildcard owns the name unless a static on-demand import of
      // a type with a member ServiceLoader makes it ambiguous.
      if (imported.isStatic()) {
        return staticOnDemandAmbiguous ? ownership.withForeign() : ownership;
      }
      return imported.name().equals("java.util")
          ? ownership.withWildcard() : ownership;
    }
    if (imported.isStatic()) {
      // A static import cannot import the top-level java.util.ServiceLoader,
      // but a static single member-type import can supply a lookalike.
      boolean lookalike = imported.simpleName().equals("ServiceLoader")
          || imported.name().endsWith(".ServiceLoader");
      return lookalike ? ownership.withForeign() : ownership;
    }
    if (imported.name().equals("java.util.ServiceLoader")) {
      return ownership.withExplicit();
    }
    return imported.simpleName().equals("ServiceLoader") ? ownership.withForeign() : ownership;
  }

  private static String classLiteralType(String argument) {
    String normalized = argument.trim();
    if (!normalized.endsWith(".class")) {
      return null;
    }
    String type = normalized.substring(0, normalized.length() - ".class".length()).trim();
    return type.matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*(?:\\.[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)*") ? type : null;
  }

  private static int matchingParenthesis(String source, int opening) {
    int depth = 0;
    for (int index = opening; index < source.length(); index++) {
      char current = source.charAt(index);
      if (current == '(') {
        depth++;
      } else if (current == ')' && --depth == 0) {
        return index;
      }
    }
    return -1;
  }

  /**
   * Detects a non-lambda method call between the load expression and the owning
   * lambda that can replace the flowing provider value (for example
   * {@code .replacement()}). Calls whose arguments introduce a lambda are
   * governed by the lambda walk in ServiceLoaderCallSiteSource; only
   * value-preserving calls stay attributed.
   */
  static boolean hasInterveningReplacingCall(String sourceText, int start, int lambdaStart) {
    int depth = 0;
    for (int index = start; index < lambdaStart; index++) {
      char value = sourceText.charAt(index);
      if (value == '(') {
        depth++;
      } else if (value == ')') {
        depth--;
      } else if (depth == 0 && value == '.' && replacesFlowingValue(sourceText, index, lambdaStart)) {
        return true;
      }
    }
    return false;
  }

  private static boolean replacesFlowingValue(String sourceText, int dot, int lambdaStart) {
    int nameEnd = dot + 1;
    while (nameEnd < lambdaStart && Character.isJavaIdentifierPart(sourceText.charAt(nameEnd))) {
      nameEnd++;
    }
    int opening = nameEnd;
    while (opening < lambdaStart && Character.isWhitespace(sourceText.charAt(opening))) {
      opening++;
    }
    if (nameEnd == dot + 1 || opening == lambdaStart || sourceText.charAt(opening) != '(') {
      return false;
    }
    String name = sourceText.substring(dot + 1, nameEnd);
    int closing = matchingParenthesis(sourceText, opening);
    int argumentEnd = closing > opening ? closing : lambdaStart;
    return !sourceText.substring(opening, argumentEnd).contains("->")
        && !VALUE_PRESERVING_CALLS.contains(name);
  }

  /** A type declaration heading a brace: annotations, modifiers, keyword. */
  private static final Pattern TYPE_DECLARATION_HEADER = Pattern.compile(
      "^\\s*(?:@[\\w$.]+(?:\\([^{}]*\\))?\\s+)*"
          + "(?:(?:public|private|protected|static|final|abstract|sealed|non-sealed|strictfp)\\s+)*"
          + "(?:class|interface|enum|record)\\b");
  /** An anonymous-class constructor invocation heading a brace. */
  private static final Pattern ANONYMOUS_CLASS_PREFIX = Pattern.compile(
      "\\bnew\\s+[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}$.]*(?:<[^()]*>)?\\s*$");


  /**
   * True when the target sits inside a type body (local, anonymous, or nested
   * declaration) that opens within the owning lambda: the inner same-named
   * parameter is a different variable, so the lambda's ownership must not
   * reach it. Covers both declaration-keyword bodies and anonymous-class
   * bodies (which have no declaration keyword).
   */
  static boolean insideTypeBodyDeclaredInLambda(
      String sourceText, int lambdaEnd, int targetOffset) {
    for (int index = lambdaEnd; index < targetOffset; index++) {
      if (sourceText.charAt(index) != '{' || !isTypeBodyOpening(sourceText, index)) {
        continue;
      }
      int closing = JavaSourceText.matchingDelimiter(sourceText, index, '{', '}');
      if (closing < 0 || closing >= targetOffset) {
        return true;
      }
    }
    return false;
  }

  private static boolean isTypeBodyOpening(String sourceText, int brace) {
    int boundary = Math.max(sourceText.lastIndexOf(';', brace - 1),
        Math.max(sourceText.lastIndexOf('{', brace - 1), sourceText.lastIndexOf('}', brace - 1)));
    String header = sourceText.substring(boundary + 1, brace).trim();
    if (TYPE_DECLARATION_HEADER.matcher(header).find()) {
      return true;
    }
    return isAnonymousClassHeader(header);
  }

  private static boolean isAnonymousClassHeader(String header) {
    if (!header.endsWith(")")) {
      return false;
    }
    int open = matchingParenthesisBackward(header, header.length() - 1);
    return open > 0 && ANONYMOUS_CLASS_PREFIX.matcher(header.substring(0, open).trim()).find();
  }

  private static int matchingParenthesisBackward(String sourceText, int closing) {
    int depth = 0;
    for (int index = closing; index >= 0; index--) {
      char value = sourceText.charAt(index);
      if (value == ')') {
        depth++;
      } else if (value == '(' && --depth == 0) {
        return index;
      }
    }
    return -1;
  }

  private static List<String> splitArguments(String source) {
    List<String> result = new ArrayList<>();
    ArgumentDepth depth = new ArgumentDepth();
    int start = 0;
    for (int index = 0; index < source.length(); index++) {
      if (depth.accept(source, index)) {
        result.add(source.substring(start, index));
        start = index + 1;
      }
    }
    result.add(source.substring(start));
    return result.stream().map(String::trim).filter(item -> !item.isEmpty()).toList();
  }

  private static boolean startsExplicitTypeArguments(String source, int index) {
    int previous = index - 1;
    while (previous >= 0 && Character.isWhitespace(source.charAt(previous))) {
      previous--;
    }
    return previous >= 0 && source.charAt(previous) == '.';
  }

  private static final class ArgumentDepth {
    private int delimiters;
    private int genericTypes;

    boolean accept(String source, int index) {
      char current = source.charAt(index);
      switch (current) {
        case '(', '[', '{' -> delimiters++;
        case ')', ']', '}' -> delimiters--;
        case '<' -> {
          if (startsExplicitTypeArguments(source, index)) {
            genericTypes++;
          }
        }
        case '>' -> {
          if (genericTypes > 0) {
            genericTypes--;
          }
        }
        default -> { }
      }
      return current == ',' && delimiters == 0 && genericTypes == 0;
    }
  }

  private static int lineAt(String source, int offset) {
    return (int) source.substring(0, offset).chars().filter(value -> value == '\n').count() + 1;
  }
}
