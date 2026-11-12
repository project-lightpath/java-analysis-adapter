package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Manual command-line entry point for the VPE-to-Java adapter handoff. */
public final class ManualAnalysisMain {
  private ManualAnalysisMain() {}

  public static void main(String[] args) {
    try {
      Map<String, String> options = options(args);
      String contextPath = required(options, "context");
      String outputPath = required(options, "output");
      AnalysisContext context = AnalysisContextLoader.load(Path.of(contextPath));
      SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(context);
      SemanticCallFactsJsonWriter.write(facts, Path.of(outputPath));
      System.err.printf(
          "manual Java analysis passed: context=%s providerFacts=%d frameworkFacts=%d output=%s%n",
          facts.contextId(),
          facts.providerFacts().size(),
          facts.frameworkFacts().size(),
          outputPath);
    } catch (IOException | RuntimeException error) {
      System.err.println("manual Java analysis failed: " + error.getMessage());
      System.exit(1);
    }
  }

  private static Map<String, String> options(String[] args) {
    Map<String, String> result = new HashMap<>();
    for (String argument : args) {
      if (!argument.startsWith("--") || !argument.contains("=")) {
        throw new IllegalArgumentException("arguments must use --name=value form: " + argument);
      }
      int separator = argument.indexOf('=');
      String name = argument.substring(2, separator).trim();
      String value = argument.substring(separator + 1).trim();
      if (name.isEmpty() || value.isEmpty()) {
        throw new IllegalArgumentException("argument name and value must not be empty");
      }
      result.put(name, value);
    }
    return result;
  }

  private static String required(Map<String, String> options, String name) {
    String value = options.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("--" + name + " is required");
    }
    return value;
  }
}
