package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the bounded ServiceLoader.load source shape used by provider evidence. */
final class ServiceLoaderCallSiteParser {
  private static final Pattern LOAD = Pattern.compile(
      "(?<![\\w$.])(?:java\\.util\\.)?ServiceLoader\\s*\\.\\s*load\\s*\\(");

  private ServiceLoaderCallSiteParser() {}

  static List<ServiceLoaderCallSite> parse(String source) {
    List<ServiceLoaderCallSite> result = new ArrayList<>();
    Matcher matcher = LOAD.matcher(source == null ? "" : source);
    while (matcher.find()) {
      int closing = matchingParenthesis(source, matcher.end() - 1);
      if (closing < 0) {
        continue;
      }
      List<String> arguments = splitArguments(source.substring(matcher.end(), closing));
      if (arguments.isEmpty()) {
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
          lineAt(source, matcher.start())));
    }
    return List.copyOf(result);
  }

  private static String classLiteralType(String argument) {
    String normalized = argument.trim();
    if (!normalized.endsWith(".class")) {
      return null;
    }
    String type = normalized.substring(0, normalized.length() - ".class".length()).trim();
    return type.matches("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*") ? type : null;
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

  private static List<String> splitArguments(String source) {
    List<String> result = new ArrayList<>();
    int start = 0;
    int depth = 0;
    for (int index = 0; index < source.length(); index++) {
      char current = source.charAt(index);
      if (current == '(' || current == '[' || current == '{') {
        depth++;
      } else if (current == ')' || current == ']' || current == '}') {
        depth--;
      } else if (current == ',' && depth == 0) {
        result.add(source.substring(start, index));
        start = index + 1;
      }
    }
    result.add(source.substring(start));
    return result.stream().map(String::trim).filter(item -> !item.isEmpty()).toList();
  }

  private static int lineAt(String source, int offset) {
    return (int) source.substring(0, offset).chars().filter(value -> value == '\n').count() + 1;
  }
}
