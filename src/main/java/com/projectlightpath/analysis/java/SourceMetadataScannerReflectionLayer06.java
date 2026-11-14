package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerReflectionLayer06 extends SourceMetadataScannerReflectionLayer05 {

  SourceMetadataScannerReflectionLayer06(AnalysisContext context) {
    super(context);
  }



  void addThisReceiverSites(
      List<DynamicSite> result, String dynamicSource, String source, SourceFile file) {
    Matcher matcher = THIS_RECEIVER_CALL.matcher(dynamicSource);
    while (matcher.find()) {
      if (isBareReceiver(dynamicSource, matcher.start())) {
        addThisReceiverSite(
            result, source, file, matcher.group(1), matcher.group(2),
            matcher.start(1), null);
      }
    }
    Matcher qualified = QUALIFIED_THIS_RECEIVER_CALL.matcher(dynamicSource);
    while (qualified.find()) {
      String owner = qualifiedThisOwner(file, qualified.group(1), qualified.start(1));
      if (owner != null) {
        addThisReceiverSite(
            result, source, file, qualified.group(2), qualified.group(3),
            qualified.start(2), owner);
      }
    }
  }



  void addJdkOwnerSites(
      List<DynamicSite> result,
      String dynamicSource,
      String source,
      SourceFile file,
      Pattern pattern,
      String symbol,
      String expectedType) {
    Matcher matcher = pattern.matcher(dynamicSource);
    while (matcher.find()) {
      if (isBareReceiver(dynamicSource, matcher.start(1))
          && isJdkApiOwner(file, matcher.group(1), matcher.start(1), expectedType)) {
        addSite(result, source, matcher.start(), symbol);
      }
    }
  }

  void addClassForNameSites(
      List<DynamicSite> result,
      String dynamicSource,
      String source,
      SourceFile file) {
    Matcher matcher = CLASS_FOR_NAME.matcher(dynamicSource);
    while (matcher.find()) {
      if (isBareReceiver(dynamicSource, matcher.start(1))
          && (isJdkApiOwner(file, matcher.group(1), matcher.start(1), "java.lang.Class")
          || isDeclaredVariableBefore(file, "Class", matcher.group(1), matcher.start(1)))) {
        addSite(result, source, matcher.start(), "Class.forName");
      }
    }
    Matcher thisQualified = THIS_CLASS_FOR_NAME.matcher(dynamicSource);
    while (thisQualified.find()) {
      if (isBareReceiver(dynamicSource, thisQualified.start())
          && hasVisibleField(file, "Class", thisQualified.group(1), thisQualified.start(1))) {
        addSite(result, source, thisQualified.start(), "Class.forName");
      }
    }
    Matcher qualifiedThis = QUALIFIED_THIS_CLASS_FOR_NAME.matcher(dynamicSource);
    while (qualifiedThis.find()) {
      String owner = qualifiedThisOwner(file, qualifiedThis.group(1), qualifiedThis.start(1));
      if (owner != null
          && hasVisibleField(file, "Class", qualifiedThis.group(2), qualifiedThis.start(2), owner)) {
        addSite(result, source, qualifiedThis.start(), "Class.forName");
      }
    }
  }

  boolean startsWithJdkClassForName(SourceFile file, String source, int offset) {
    Matcher owner = CLASS_FOR_NAME_PREFIX.matcher(source.substring(offset));
    return !owner.find()
        || isJdkApiOwner(file, owner.group(1), offset, "java.lang.Class");
  }

  void addReceiverCallSites(
      List<DynamicSite> result,
      String dynamicSource,
      String source,
      SourceFile file,
      Pattern pattern,
      String receiverType) {
    Matcher matcher = pattern.matcher(dynamicSource);
    while (matcher.find()) {
      if (!isBareReceiver(dynamicSource, matcher.start(1))) {
        continue;
      }
      String receiver = matcher.group(1);
      boolean apiReceiver = receiver.equals(receiverType)
          && isJdkApiOwner(file, receiver, matcher.start(1), receiverType.equals("Class")
              ? "java.lang.Class" : "java.lang.ClassLoader");
      if (apiReceiver || isDeclaredVariableBefore(file, receiverType, receiver, matcher.start())) {
        addSite(result, source, matcher.start(), matcher.group(2).equals("loadClass")
            ? "ClassLoader.loadClass" : matcher.group(2));
      }
    }
  }

  void addReceiverSites(
      List<DynamicSite> result,
      String dynamicSource,
      String source,
      SourceFile file,
      String method,
      String receiverType,
      String symbol) {
    Matcher matcher = Pattern.compile(
        "\\b([A-Za-z_$][\\w$]*)\\s*\\.\\s*" + method + "\\s*\\(").matcher(dynamicSource);
    while (matcher.find()) {
      if (isBareReceiver(dynamicSource, matcher.start(1))
          && isDeclaredVariableBefore(file, receiverType, matcher.group(1), matcher.start())) {
        addSite(result, source, matcher.start(), symbol);
      }
    }
  }

  boolean isSupportedChainedReceiver(
      SourceFile file, String source, ChainedReceiver receiver) {
    return switch (receiver.kind()) {
      case CLASS_FOR_NAME -> isBareReceiver(source, receiver.start())
          && startsWithJdkClassForName(file, source, receiver.start());
      case THIS_CLASS_FOR_NAME -> isThisClassForNameReceiver(file, source, receiver);
      case QUALIFIED_THIS_CLASS_FOR_NAME -> isQualifiedThisClassForNameReceiver(
          file, source, receiver);
      case THIS_FIELD -> isBareReceiver(source, receiver.start())
          && isJdkClassFieldReceiver(file, receiver.expression(), receiver.start());
      case QUALIFIED_THIS_FIELD -> isJdkClassFieldReceiver(
          file, receiver.expression(), receiver.start());
      case GET_CLASS, CLASS_LITERAL -> true;
      case SIMPLE -> isBareReceiver(source, receiver.start())
          && isDeclaredVariableBefore(file, "Class", receiver.expression(), receiver.start());
    };
  }
}
