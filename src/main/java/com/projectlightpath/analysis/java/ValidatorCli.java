package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared command-line parsing for the validator mains (#45 steps 3-4): {@code --flag
 * value} and {@code --flag=value} forms, repeatable flags collecting every occurrence,
 * non-repeatable flags keeping the last value (argparse semantics), and a null result for
 * unknown flags or a missing value (argparse exits 2; the caller prints usage and exits
 * 2). Argparse's unambiguous-prefix abbreviation is not supported — no live caller uses
 * it.
 */
final class ValidatorCli {

  private ValidatorCli() {}

  /**
   * Parses flags into a name-to-values map.
   *
   * @param args command-line arguments
   * @param repeatable flag names (without {@code --}) that collect every occurrence
   * @param known every flag name (without {@code --}) this command accepts; anything
   *     else rejects like argparse's invalid-choice error
   * @return the parsed flags, or null on an unknown flag, a non-flag argument, or a
   *     missing value
   */
  static Map<String, List<String>> parse(String[] args, Set<String> repeatable,
      Set<String> known) {
    Map<String, List<String>> flags = new LinkedHashMap<>();
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (!arg.startsWith("--")) {
        return null;
      }
      String name;
      String value;
      int separator = arg.indexOf('=');
      if (separator > 2) {
        name = arg.substring(2, separator);
        value = arg.substring(separator + 1);
      } else {
        name = arg.substring(2);
        if (name.isEmpty() || i + 1 >= args.length) {
          return null;
        }
        value = args[++i];
        // argparse refuses to consume another option as a value ("expected one
        // argument"); a leading "--" marks an option, while signed short values
        // like -1 remain valid operands.
        if (value.startsWith("--")) {
          return null;
        }
      }
      if (!known.contains(name)) {
        return null;
      }
      if (repeatable.contains(name)) {
        flags.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
      } else {
        flags.put(name, new ArrayList<>(List.of(value)));
      }
    }
    return flags;
  }

  /** The single value of a flag, or null when absent. */
  static String single(Map<String, List<String>> flags, String name) {
    List<String> values = flags.get(name);
    return values == null || values.isEmpty() ? null : values.get(values.size() - 1);
  }
}
