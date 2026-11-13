package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses ServiceLoader source invocations and resolves their service types. */
final class ServiceLoaderCallSiteParser {
  private static final Pattern SERVICE_LOAD = Pattern.compile(
      "\\b((?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*)\\s*\\.\\s*load"
          + "\\s*\\(\\s*([A-Za-z_$][A-Za-z0-9_$.]*)\\s*\\.\\s*class\\b");
  private static final Pattern PACKAGE = Pattern.compile(
      "\\bpackage\\s+([A-Za-z_$][A-Za-z0-9_$.]*)\\s*;");
  private static final Pattern IMPORT = Pattern.compile(
      "\\bimport\\s+(?!static\\s+)([A-Za-z_$][A-Za-z0-9_$.]*)(\\.\\*)?\\s*;");

  private ServiceLoaderCallSiteParser() {}

  static List<ServiceLoaderCallSite> parse(String sourceText) {
    String packageName = match(PACKAGE, sourceText);
    Imports imports = imports(sourceText);
    List<ServiceLoaderCallSite> result = new ArrayList<>();
    Matcher matcher = SERVICE_LOAD.matcher(sourceText);
    while (matcher.find()) {
      if (!isJavaServiceLoader(matcher.group(1), imports)) {
        continue;
      }
      String rawServiceType = matcher.group(2);
      result.add(new ServiceLoaderCallSite(
           matcher.group(1),
           rawServiceType,
           fallbackServiceType(rawServiceType, packageName, imports),
           hasExplicitClassLoader(sourceText, matcher.end()),
           matcher.start(),
           lineAt(sourceText, matcher.start())));
    }
    return List.copyOf(result);
  }

  private static boolean isJavaServiceLoader(String receiver, Imports imports) {
    return receiver.equals("java.util.ServiceLoader")
        || (receiver.equals("ServiceLoader")
            && javaServiceLoaderImport(imports));
  }

  private static boolean javaServiceLoaderImport(Imports imports) {
    String exact = imports.exact().get("ServiceLoader");
    if (exact != null) {
      return exact.equals("java.util.ServiceLoader");
    }
    return imports.wildcards().size() == 1 && imports.wildcards().contains("java.util");
  }

  private static boolean hasExplicitClassLoader(String sourceText, int afterServiceType) {
    int index = afterServiceType;
    while (index < sourceText.length() && Character.isWhitespace(sourceText.charAt(index))) {
      index++;
    }
    return index < sourceText.length() && sourceText.charAt(index) == ',';
  }

  private static int lineAt(String sourceText, int offset) {
    int line = 1;
    for (int index = 0; index < offset; index++) {
      if (sourceText.charAt(index) == '\n') {
        line++;
      }
    }
    return line;
  }

  private static Imports imports(String sourceText) {
    Map<String, String> exact = new HashMap<>();
    Set<String> wildcards = new HashSet<>();
    Matcher importMatcher = IMPORT.matcher(sourceText);
    while (importMatcher.find()) {
      String imported = importMatcher.group(1);
      if (importMatcher.group(2) == null) {
        exact.put(imported.substring(imported.lastIndexOf('.') + 1), imported);
      } else {
        wildcards.add(imported);
      }
    }
    return new Imports(Map.copyOf(exact), Set.copyOf(wildcards));
  }

  private static String fallbackServiceType(
      String raw, String packageName, Imports imports) {
    if (raw.contains(".")) {
      return raw;
    }
    if (imports.exact().containsKey(raw)) {
      return imports.exact().get(raw);
    }
    if (imports.wildcards().stream().anyMatch(item -> !item.equals("java.util"))) {
      return raw;
    }
    return packageName.isBlank() ? raw : packageName + "." + raw;
  }

  private static String match(Pattern pattern, String value) {
    Matcher matcher = pattern.matcher(value);
    return matcher.find() ? matcher.group(1) : "";
  }

  private record Imports(Map<String, String> exact, Set<String> wildcards) {}
}
