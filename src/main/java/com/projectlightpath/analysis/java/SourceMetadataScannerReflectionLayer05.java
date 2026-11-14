package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerReflectionLayer05 extends SourceMetadataScannerReflectionLayer04 {

  SourceMetadataScannerReflectionLayer05(AnalysisContext context) {
    super(context);
  }



  /** Confirms that a source owner resolves to the JDK API rather than an application type. */
  boolean isJdkApiOwner(
      SourceFile file, String spelling, int offset, String expectedType) {
    if (spelling != null
        && spelling.matches("[A-Za-z_$][\\w$]*")
        && hasVisibleValueNamed(file, spelling, offset)) {
      String expectedSimpleName = jdkSimpleName(expectedType);
      return expectedSimpleName != null
          && isDeclaredVariableBefore(file, expectedSimpleName, spelling, offset);
    }
    return resolveOwnerType(spelling, file, offset)
        .map(expectedType::equals)
        .orElse(false);
  }

  boolean isJdkClassForNameExpression(SourceFile file, String expression, int offset) {
    Matcher owner = CLASS_FOR_NAME_PREFIX.matcher(expression);
    if (owner.find()) {
      int closing = matchingDelimiter(expression, owner.end() - 1, '(', ')');
      if (closing == expression.length() - 1
          && isJdkApiOwner(file, owner.group(1), offset, "java.lang.Class")) {
        return true;
      }
    }
    Matcher thisQualified = THIS_CLASS_FOR_NAME.matcher(expression);
    if (thisQualified.find() && thisQualified.start() == 0) {
      int closing = matchingDelimiter(expression, thisQualified.end() - 1, '(', ')');
      if (closing == expression.length() - 1
          && hasVisibleField(
              file, "Class", thisQualified.group(1), offset + thisQualified.start(1))) {
        return true;
      }
    }
    Matcher qualifiedThis = QUALIFIED_THIS_CLASS_FOR_NAME.matcher(expression);
    if (!qualifiedThis.find() || qualifiedThis.start() != 0) {
      return false;
    }
    int closing = matchingDelimiter(expression, qualifiedThis.end() - 1, '(', ')');
    if (closing != expression.length() - 1) {
      return false;
    }
    String selectedOwner = qualifiedThisOwner(file, qualifiedThis.group(1), offset + qualifiedThis.start(1));
    return selectedOwner != null
        && hasVisibleField(
            file, "Class", qualifiedThis.group(2), offset + qualifiedThis.start(2), selectedOwner);
  }

  boolean isDeclaredVariableBefore(
      SourceFile file, String type, String variable, int offset) {
    MethodInfo owner = methodContaining(file, offset);
    if (owner.name().equals("<unknown>")) {
      return false;
    }
    if (hasParameter(owner, variable)) {
      return parameterMatchesType(file, owner, type, variable, offset);
    }
    Optional<LocalDeclaration> local = activeLocalDeclaration(file, owner, variable, offset);
    if (local.isPresent()) {
      LocalDeclaration declaration = local.get();
      if (declaration.type().equals("var")) {
        return Optional.ofNullable(inferredReflectionType(
                file, declaration.initializer(), declaration.initializerOffset()))
            .map(typeName -> typeName.equals(type))
            .orElse(false);
      }
      return isJdkDeclaredType(file, declaration.type(), declaration.start(), type);
    }
    return hasVisibleField(file, type, variable, offset);
  }

  String inferredReflectionType(SourceFile file, String initializer, int offset) {
    String expression = stripOuterParentheses(initializer.trim());
    if (isRecognizedClassLookup(file, expression, offset)
        && Pattern.compile("(?:getDeclaredMethod|getMethod)\\s*\\([^;{}]*\\)$")
            .matcher(expression).find()) {
      return "Method";
    }
    if (isRecognizedClassLookup(file, expression, offset)
        && Pattern.compile("(?:getDeclaredConstructor|getConstructor)\\s*\\([^;{}]*\\)$")
            .matcher(expression).find()) {
      return "Constructor";
    }
    if (isStandaloneClassForNameExpression(file, expression, offset)) {
      return "Class";
    }
    if (expression.matches("^[A-Za-z_$][\\w$.]*\\s*\\.\\s*class$")) {
      return "Class";
    }
    if (isClassLoaderResultExpression(file, expression, offset)) {
      return "ClassLoader";
    }
    if (Pattern.compile("^(?:getClass\\s*\\(\\s*\\)|[A-Za-z_$][\\w$]*\\s*\\.\\s*"
            + "getClass\\s*\\(\\s*\\))$")
        .matcher(expression).find()) {
      return "Class";
    }
    if (isStandaloneLoadClassExpression(file, expression, offset)) {
      return "Class";
    }
    return null;
  }

  boolean isStandaloneClassForNameExpression(
      SourceFile file, String expression, int offset) {
    return isJdkClassForNameExpression(file, expression, offset);
  }

  boolean isClassLoaderResultExpression(
      SourceFile file, String expression, int offset) {
    Matcher loader = Pattern.compile("^(.+)\\s*\\.\\s*getClassLoader\\s*\\(\\s*\\)$")
        .matcher(expression);
    if (!loader.find()) {
      return false;
    }
    String receiver = stripOuterParentheses(loader.group(1).trim());
    return isStandaloneClassForNameExpression(file, receiver, offset)
        || isStandaloneLoadClassExpression(file, receiver, offset)
        || receiver.matches("^[A-Za-z_$][\\w$.]*\\s*\\.\\s*class$")
        || isDeclaredVariableBefore(file, "Class", receiver, offset)
        || receiver.matches("^(?:getClass\\s*\\(\\s*\\)|[A-Za-z_$][\\w$]*\\s*\\.\\s*"
            + "getClass\\s*\\(\\s*\\))$");
  }

  boolean isRecognizedClassLoaderLookup(SourceFile file, String initializer, int offset) {
    Matcher lookup = Pattern.compile(
            "^(ClassLoader|java\\.lang\\.ClassLoader|[A-Za-z_$][\\w$]*)"
                + "\\s*\\.\\s*loadClass\\s*\\(")
        .matcher(initializer);
    if (!lookup.find()) {
      return false;
    }
    String receiver = lookup.group(1);
    if (receiver.equals("ClassLoader") || receiver.equals("java.lang.ClassLoader")) {
      return isJdkApiOwner(file, receiver, offset + lookup.start(1), "java.lang.ClassLoader");
    }
    return isDeclaredVariableBefore(file, "ClassLoader", receiver, offset + lookup.start(1));
  }

  boolean isStandaloneLoadClassExpression(SourceFile file, String expression, int offset) {
    Matcher lookup = Pattern.compile(
            "^(ClassLoader|java\\.lang\\.ClassLoader|[A-Za-z_$][\\w$]*)"
                + "\\s*\\.\\s*loadClass\\s*\\(")
        .matcher(expression);
    if (!lookup.find()) {
      return false;
    }
    int closing = matchingDelimiter(expression, lookup.end() - 1, '(', ')');
    return closing == expression.length() - 1
        && isRecognizedClassLoaderLookup(file, expression, offset);
  }

  boolean isRecognizedClassLookup(SourceFile file, String initializer, int offset) {
    Matcher lookup = Pattern.compile(
            "^((?:[A-Za-z_$][\\w$]*|java\\.lang\\.Class)\\s*\\.\\s*forName\\s*\\([^;{}]*\\)"
                + "|(?:this\\s*\\.\\s*[A-Za-z_$][\\w$]*"
                + "|[A-Za-z_$][\\w$.]*\\s*\\.\\s*this\\s*\\.\\s*[A-Za-z_$][\\w$]*)"
                + "\\s*\\.\\s*forName\\s*\\([^;{}]*\\)"
                + "|(?:this\\s*\\.\\s*[A-Za-z_$][\\w$]*"
                + "|[A-Za-z_$][\\w$.]*\\s*\\.\\s*this\\s*\\.\\s*[A-Za-z_$][\\w$]*)"
                + "|getClass\\s*\\(\\s*\\)"
                + "|[A-Za-z_$][\\w$]*\\s*\\.\\s*getClass\\s*\\(\\s*\\)"
                + "|[A-Za-z_$][\\w$]*\\.class"
                + "|[A-Za-z_$][\\w$]*)\\s*\\.\\s*"
                + "(?:getDeclaredMethod|getMethod|getDeclaredConstructor|getConstructor)"
                + "\\s*\\(")
        .matcher(initializer);
    if (!lookup.find()) {
      return false;
    }
    int closing = matchingDelimiter(initializer, lookup.end() - 1, '(', ')');
    if (closing != initializer.length() - 1) {
      return false;
    }
    String receiver = lookup.group(1).trim();
    String normalizedReceiver = receiver.replaceAll("\\s+", "");
    return normalizedReceiver.equals("getClass()")
        || normalizedReceiver.endsWith(".getClass()")
        || normalizedReceiver.endsWith(".class")
        || isJdkClassForNameExpression(file, receiver, offset + lookup.start(1))
        || isJdkClassFieldReceiver(file, receiver, offset + lookup.start(1))
        || isDeclaredVariableBefore(file, "Class", receiver, offset + lookup.start(1));
  }
}
