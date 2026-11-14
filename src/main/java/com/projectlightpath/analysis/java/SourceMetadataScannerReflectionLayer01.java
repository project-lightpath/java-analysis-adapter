package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerReflectionLayer01 extends SourceMetadataScannerReflectionLayer00 {

  SourceMetadataScannerReflectionLayer01(AnalysisContext context) {
    super(context);
  }



static int fieldDeclarationEnd(String source, int start) {
    return fieldInitializerEnd(source, start);
  }

static boolean isAccessibleField(
      SourceFile declaringFile,
      SourceFile siteFile,
      String declaringType,
      String siteType,
      String declaration) {
    boolean lexicalAccess = siteType.equals(declaringType)
        || siteType.startsWith(declaringType + "$");
    if (Pattern.compile("\\bprivate\\b").matcher(declaration).find()) {
      return lexicalAccess;
    }
    if (isInterfaceType(declaringFile, declaringType)) {
      return true;
    }
    return declaringFile.packageName().equals(siteFile.packageName())
        || Pattern.compile("\\b(?:public|protected)\\b").matcher(declaration).find();
  }

static int declarationScopeIndex(String source, int matchStart) {
    return matchStart < source.length() && ";{}:".indexOf(source.charAt(matchStart)) >= 0
        ? matchStart + 1
        : matchStart;
  }

  int addVisibleOwnerHierarchy(
      String owner, int distance, Map<String, Integer> result, Set<String> visited) {
    if (owner.isBlank() || !visited.add(owner)) {
      return distance;
    }
    result.putIfAbsent(owner, distance);
    TypeReference reference = findTypeReference(owner);
    if (reference == null) {
      return distance;
    }
    int deepest = distance;
    for (String parent : resolveParentTypes(reference)) {
      deepest = Math.max(deepest, addVisibleOwnerHierarchy(parent, distance + 1, result, visited));
    }
    return deepest;
  }

static List<ChainedReceiver> chainedReceivers(String source) {
    List<ChainedReceiver> result = new ArrayList<>();
    addChainedForNameReceivers(
        result, source, CHAINED_CLASS_FOR_NAME_RECEIVER, ChainedReceiverKind.CLASS_FOR_NAME);
    addChainedForNameReceivers(
        result, source, THIS_CLASS_FOR_NAME, ChainedReceiverKind.THIS_CLASS_FOR_NAME);
    addChainedForNameReceivers(
        result, source, QUALIFIED_THIS_CLASS_FOR_NAME,
        ChainedReceiverKind.QUALIFIED_THIS_CLASS_FOR_NAME);
    addChainedFieldReceivers(
        result, source, CHAINED_THIS_FIELD_RECEIVER, ChainedReceiverKind.THIS_FIELD);
    addChainedFieldReceivers(
        result, source, CHAINED_QUALIFIED_THIS_FIELD_RECEIVER,
        ChainedReceiverKind.QUALIFIED_THIS_FIELD);
    Matcher getClass = CHAINED_GET_CLASS_RECEIVER.matcher(source);
    while (getClass.find()) {
      result.add(new ChainedReceiver(
          getClass.start(), getClass.end(), getClass.group(), ChainedReceiverKind.GET_CLASS));
    }
    Matcher classLiteral = CHAINED_CLASS_LITERAL_RECEIVER.matcher(source);
    while (classLiteral.find()) {
      result.add(new ChainedReceiver(
          classLiteral.start(), classLiteral.end(), classLiteral.group(),
          ChainedReceiverKind.CLASS_LITERAL));
    }
    Matcher simple = CHAINED_SIMPLE_RECEIVER.matcher(source);
    while (simple.find()) {
      result.add(new ChainedReceiver(
          simple.start(1), simple.end(1), simple.group(1), ChainedReceiverKind.SIMPLE));
    }
    return result;
  }

static ChainedLookup chainedLookup(
      String source, ChainedReceiver receiver, String lookupMethod) {
    int dot = skipWhitespace(source, receiver.end());
    if (dot >= source.length() || source.charAt(dot) != '.') {
      return null;
    }
    int methodStart = skipWhitespace(source, dot + 1);
    if (!tokenAt(source, methodStart, lookupMethod)) {
      return null;
    }
    int opening = skipWhitespace(source, methodStart + lookupMethod.length());
    if (opening >= source.length() || source.charAt(opening) != '(') {
      return null;
    }
    int closing = matchingDelimiter(source, opening, '(', ')');
    return closing < 0 ? null : new ChainedLookup(methodStart, closing);
  }

static int chainedTerminalStart(
      String source, ChainedLookup lookup, String terminalMethod) {
    int dot = skipWhitespace(source, lookup.closing() + 1);
    if (dot >= source.length() || source.charAt(dot) != '.') {
      return -1;
    }
    int terminalStart = skipWhitespace(source, dot + 1);
    if (!tokenAt(source, terminalStart, terminalMethod)) {
      return -1;
    }
    int opening = skipWhitespace(source, terminalStart + terminalMethod.length());
    return opening < source.length() && source.charAt(opening) == '(' ? terminalStart : -1;
  }

  boolean isInvocationHandlerParameterBefore(SourceFile file, String variable, int offset) {
    String source = file.structuralSource();
    MethodInfo owner = methodContaining(file, offset);
    Pattern lambda = Pattern.compile(
        "\\b((?:InvocationHandler|java\\.lang\\.reflect\\.InvocationHandler))\\s+"
            + "[A-Za-z_$][\\w$]*\\s*=\\s*\\(([^)]*)\\)\\s*->");
    Matcher matcher = lambda.matcher(source);
    matcher.region(owner.name().equals("<unknown>") ? 0 : owner.start(),
        Math.min(offset, source.length()));
    while (matcher.find()) {
      if (!isJdkDeclaredType(file, matcher.group(1), matcher.start(1), "InvocationHandler")) {
        continue;
      }
      List<String> parameters = splitTopLevelArguments(matcher.group(2));
      if (parameters.size() >= 2
          && lambdaParameterName(parameters.get(1)).equals(variable)
          && lambdaBodyContains(source, matcher.end(), offset)) {
        return true;
      }
    }
    return false;
  }

  Map<String, Integer> selectedOwnerFieldOwners(String owner) {
    Map<String, Integer> result = new LinkedHashMap<>();
    addVisibleOwnerHierarchy(owner, 0, result, new HashSet<>());
    return result;
  }

static Optional<String> declaredFieldType(
      String sharedType, String declarators, String variable) {
    for (String raw : splitFieldDeclarators(declarators)) {
      String declaration = raw.trim();
      int equals = declaration.indexOf('=');
      if (equals >= 0) {
        declaration = declaration.substring(0, equals).trim();
      }
      Matcher name = Pattern.compile("^([A-Za-z_$][\\w$]*)").matcher(declaration);
      if (!name.find() || !name.group(1).equals(variable)) {
        continue;
      }
      String suffix = declaration.substring(name.end()).replaceAll("\\s+", "");
      if (!suffix.matches("(?:\\[\\])*$")) {
        return Optional.empty();
      }
      return Optional.of(sharedType.trim() + suffix);
    }
    return Optional.empty();
  }
}
