package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerReflectionLayer04 extends SourceMetadataScannerReflectionLayer03 {

  SourceMetadataScannerReflectionLayer04(AnalysisContext context) {
    super(context);
  }



  boolean isJdkClassFieldReceiver(SourceFile file, String receiver, int offset) {
    Matcher thisField = THIS_FIELD_RECEIVER.matcher(receiver);
    if (thisField.matches()) {
      return hasVisibleField(file, "Class", thisField.group(1), offset + thisField.start(1));
    }
    Matcher qualifiedThis = QUALIFIED_THIS_FIELD_RECEIVER.matcher(receiver);
    if (!qualifiedThis.matches()) {
      return false;
    }
    String selectedOwner = qualifiedThisOwner(file, qualifiedThis.group(1), offset + qualifiedThis.start(1));
    return selectedOwner != null
        && hasVisibleField(
            file, "Class", qualifiedThis.group(2), offset + qualifiedThis.start(2), selectedOwner);
  }

  boolean isThisClassForNameReceiver(
      SourceFile file, String source, ChainedReceiver receiver) {
    Matcher matcher = THIS_CLASS_FOR_NAME.matcher(source.substring(receiver.start()));
    return isBareReceiver(source, receiver.start())
        && matcher.find()
        && matcher.start() == 0
        && hasVisibleField(
            file, "Class", matcher.group(1), receiver.start() + matcher.start(1));
  }

  boolean isQualifiedThisClassForNameReceiver(
      SourceFile file, String source, ChainedReceiver receiver) {
    Matcher matcher = QUALIFIED_THIS_CLASS_FOR_NAME.matcher(source.substring(receiver.start()));
    if (!matcher.find() || matcher.start() != 0) {
      return false;
    }
    String selectedOwner = qualifiedThisOwner(
        file, matcher.group(1), receiver.start() + matcher.start(1));
    return selectedOwner != null
        && hasVisibleField(
            file, "Class", matcher.group(2), receiver.start() + matcher.start(2), selectedOwner);
  }

  void addThisReceiverSite(
      List<DynamicSite> result,
      String source,
      SourceFile file,
      String field,
      String method,
      int fieldOffset,
      String selectedOwner) {
    String type = switch (method) {
      case "loadClass" -> "ClassLoader";
      case "getDeclaredMethod", "getMethod", "getDeclaredConstructor", "getConstructor" -> "Class";
      case "invoke" -> "Method";
      case "newInstance" -> null;
      default -> null;
    };
    boolean matches = type != null && hasVisibleField(file, type, field, fieldOffset, selectedOwner);
    if (method.equals("newInstance")) {
      boolean constructorField = hasVisibleField(
          file, "Constructor", field, fieldOffset, selectedOwner);
      boolean classField = hasVisibleField(file, "Class", field, fieldOffset, selectedOwner);
      matches = constructorField || classField;
      type = constructorField ? "Constructor" : classField ? "Class" : null;
    }
    if (matches) {
      addSite(result, source, fieldOffset, dynamicSymbol(method, type));
    }
  }
}
