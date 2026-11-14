package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerReflectionLayer02 extends SourceMetadataScannerReflectionLayer01 {

  SourceMetadataScannerReflectionLayer02(AnalysisContext context) {
    super(context);
  }



static int declaratorVariableOffset(String declarators, String variable) {
    int search = 0;
    for (String raw : splitFieldDeclarators(declarators)) {
      int rawStart = declarators.indexOf(raw, search);
      if (rawStart < 0) {
        continue;
      }
      Matcher name = Pattern.compile("^([A-Za-z_$][\\w$]*)").matcher(raw);
      if (name.find() && name.group(1).equals(variable)) {
        return rawStart + name.start(1);
      }
      search = rawStart + raw.length();
    }
    return -1;
  }

  /** Returns lexical and inherited owners visible from one dynamic call site. */
  Map<String, Integer> visibleFieldOwners(SourceFile file, String siteType) {
    Map<String, Integer> result = new LinkedHashMap<>();
    String lexical = siteType;
    int distance = 0;
    Set<String> visited = new HashSet<>();
    while (!lexical.isBlank()) {
      distance = addVisibleOwnerHierarchy(lexical, distance, result, visited) + 1;
      int separator = lexical.lastIndexOf('$');
      if (separator < 0) {
        break;
      }
      lexical = lexical.substring(0, separator);
    }
    return result;
  }

  boolean hasVisibleMethodNamed(SourceFile file, int offset, String name) {
    String siteType = typeContaining(file, offset);
    Set<String> visibleOwners = visibleFieldOwners(file, siteType).keySet();
    return files().stream()
        .flatMap(candidate -> methods(candidate).stream()
            .filter(method -> method.name().equals(name))
            .filter(method -> !isDeclarationKeyword(method.returnType()))
            .filter(method -> isAccessibleMethod(candidate, file, method, siteType))
            .map(method -> typeContaining(candidate, method.start())))
        .anyMatch(visibleOwners::contains);
  }

  void addChainedInvocationSites(
      List<DynamicSite> result,
      String dynamicSource,
      String source,
      List<ChainedReceiver> receivers,
      List<String> lookupMethods,
      String terminalMethod,
      String symbol) {
    for (ChainedReceiver receiver : receivers) {
      for (String lookupMethod : lookupMethods) {
        ChainedLookup lookup = chainedLookup(dynamicSource, receiver, lookupMethod);
        if (lookup == null) {
          continue;
        }
        int terminalStart = chainedTerminalStart(dynamicSource, lookup, terminalMethod);
        if (terminalStart >= 0) {
          addSite(result, source, terminalStart, symbol);
        }
      }
    }
  }

  void addChainedLookupSites(
      List<DynamicSite> result,
      String dynamicSource,
      String source,
      List<ChainedReceiver> receivers,
      String... lookupMethods) {
    for (ChainedReceiver receiver : receivers) {
      // Simple Class receivers are already emitted by addReceiverCallSites. Keep
      // them in the shared receiver list for terminal invoke/newInstance chains,
      // but do not emit their lookup a second time at a different offset.
      if (receiver.kind() == ChainedReceiverKind.SIMPLE
          || receiver.kind() == ChainedReceiverKind.THIS_FIELD
          || receiver.kind() == ChainedReceiverKind.QUALIFIED_THIS_FIELD) {
        continue;
      }
      for (String lookupMethod : lookupMethods) {
        ChainedLookup lookup = chainedLookup(dynamicSource, receiver, lookupMethod);
        if (lookup != null) {
          addSite(result, source, lookup.methodStart(), lookupMethod);
        }
      }
    }
  }

static LocalDeclaration sharedDeclarationMatch(
      String source,
      Matcher matcher,
      String variable,
      int offset,
      int ownerStart,
      LocalDeclaration selected) {
    int declarationEnd = fieldDeclarationEnd(source, matcher.end(1));
    if (declarationEnd < 0) {
      return selected;
    }
    String declarators = source.substring(matcher.end(1), declarationEnd);
    if (splitFieldDeclarators(declarators).size() < 2) {
      return selected;
    }
    Optional<String> declaredType = declaredFieldType(
        matcher.group(1), declarators, variable);
    int variableOffset = declaratorVariableOffset(declarators, variable);
    if (declaredType.isEmpty() || variableOffset < 0) {
      return selected;
    }
    int absoluteVariableOffset = matcher.end(1) + variableOffset;
    if (absoluteVariableOffset >= offset
        || isDeclarationKeyword(declaredType.get())
        || !declarationIsActive(source, absoluteVariableOffset, offset, ownerStart)) {
      return selected;
    }
    return new LocalDeclaration(declaredType.get(), "", absoluteVariableOffset, -1);
  }

  Optional<VisibleField> visibleFieldInCandidate(
      SourceFile candidate,
      SourceFile siteFile,
      String variable,
      String siteType,
      Pattern fieldStart,
      Map<String, Integer> visibleOwners) {
    String source = maskStringLiterals(candidate.structuralSource());
    Matcher matcher = fieldStart.matcher(source);
    VisibleField selected = null;
    while (matcher.find()) {
      int declarationEnd = fieldDeclarationEnd(source, matcher.end(1));
      if (declarationEnd < 0
          || !isTypeMemberScope(candidate, source, declarationScopeIndex(source, matcher.start()))) {
        continue;
      }
      int fieldOffset = matcher.start(1);
      Optional<String> fieldType = declaredFieldType(
          matcher.group(1), source.substring(matcher.end(1), declarationEnd), variable);
      if (fieldType.isEmpty()) {
        continue;
      }
      String fieldOwner = typeContaining(candidate, fieldOffset);
      String declaration = source.substring(matcher.start(), declarationEnd + 1);
      if (!isAccessibleField(candidate, siteFile, fieldOwner, siteType, declaration)
          || !methodContaining(candidate, fieldOffset).name().equals("<unknown>")) {
        continue;
      }
      Integer distance = visibleOwners.get(fieldOwner);
      if (distance == null || selected != null && distance >= selected.distance()) {
        continue;
      }
      selected = new VisibleField(new FieldReference(fieldType.get(), candidate, fieldOffset), distance);
    }
    return Optional.ofNullable(selected);
  }

  void addStaticForNameSites(
      List<DynamicSite> result, String dynamicSource, String source, SourceFile file) {
    Matcher matcher = STATIC_FOR_NAME.matcher(dynamicSource);
    while (matcher.find()) {
      int previous = matcher.start() - 1;
      while (previous >= 0 && Character.isWhitespace(dynamicSource.charAt(previous))) {
        previous--;
      }
      if ((previous < 0 || dynamicSource.charAt(previous) != '.')
          && !hasVisibleMethodNamed(file, matcher.start(), "forName")) {
        addSite(result, source, matcher.start(), "Class.forName");
      }
    }
  }

  /** Reports chained Method.invoke only when the lookup receiver is a JDK Class API. */
  void addChainedMethodInvokeSites(
      List<DynamicSite> result,
      String dynamicSource,
      String source,
      List<ChainedReceiver> receivers) {
    addChainedInvocationSites(
        result, dynamicSource, source, receivers,
        List.of("getDeclaredMethod", "getMethod"), "invoke", "Method.invoke");
  }

  /** Reports chained Constructor.newInstance only when the lookup receiver is a JDK Class API. */
  void addChainedConstructorSites(
      List<DynamicSite> result,
      String dynamicSource,
      String source,
      List<ChainedReceiver> receivers) {
    addChainedInvocationSites(
        result, dynamicSource, source, receivers,
        List.of("getDeclaredConstructor", "getConstructor"), "newInstance",
        "Constructor.newInstance");
  }
}
