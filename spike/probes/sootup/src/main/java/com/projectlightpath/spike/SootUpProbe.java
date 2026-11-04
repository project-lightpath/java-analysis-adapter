package com.projectlightpath.spike;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import sootup.callgraph.CallGraph;
import sootup.callgraph.CallGraphAlgorithm;
import sootup.callgraph.ClassHierarchyAnalysisAlgorithm;
import sootup.callgraph.RapidTypeAnalysisAlgorithm;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.model.SootClass;
import sootup.core.model.SootMethod;
import sootup.core.model.SourceType;
import sootup.core.signatures.MethodSignature;
import sootup.java.bytecode.frontend.inputlocation.DefaultRuntimeAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.views.JavaView;

/** Minimal, provenance-friendly SootUp CHA/RTA probe for FLPATH-5000. */
public final class SootUpProbe {
  private static final String SOOTUP_VERSION = "3.0.1";

  private SootUpProbe() {}

  public static void main(String[] args) throws Exception {
    Config config = Config.parse(args);
    long setupStart = System.nanoTime();
    JavaView view = createView(config);
    Set<String> applicationClasses = discoverApplicationClasses(config.applicationRoot);
    List<MethodSignature> entryPoints = applicationMethods(view, applicationClasses);
    long setupMillis = elapsedMillis(setupStart);

    List<AnalysisResult> analyses = new ArrayList<>();
    for (String algorithmName : List.of("cha", "rta")) {
      long graphStart = System.nanoTime();
      CallGraphAlgorithm algorithm = createAlgorithm(algorithmName, view);
      CallGraph graph = algorithm.initialize(entryPoints);
      long graphMillis = elapsedMillis(graphStart);
      analyses.add(
          new AnalysisResult(
              algorithmName,
              graphMillis,
              graph,
              config.targets,
              config.sourceRoots,
              applicationClasses));
    }

    writeJson(config, view, applicationClasses, entryPoints, setupMillis, analyses);
  }

  private static JavaView createView(Config config) {
    List<AnalysisInputLocation> locations = new ArrayList<>();
    locations.add(
        new JavaClassPathAnalysisInputLocation(
            config.applicationRoot.toString(), SourceType.Application));
    if (!config.libraryClasspath.isBlank()) {
      locations.add(
          new JavaClassPathAnalysisInputLocation(config.libraryClasspath, SourceType.Library));
    }
    locations.add(new DefaultRuntimeAnalysisInputLocation(SourceType.Library));
    return new JavaView(locations);
  }

  private static CallGraphAlgorithm createAlgorithm(String name, JavaView view) {
    return switch (name) {
      case "cha" -> new ClassHierarchyAnalysisAlgorithm(view);
      case "rta" -> new RapidTypeAnalysisAlgorithm(view);
      default -> throw new IllegalArgumentException("unsupported algorithm: " + name);
    };
  }

  private static Set<String> discoverApplicationClasses(Path applicationRoot) throws IOException {
    if (!Files.isDirectory(applicationRoot)) {
      throw new IllegalArgumentException(
          "application root must be a compiled classes directory: " + applicationRoot);
    }
    try (var paths = Files.walk(applicationRoot)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(path -> path.toString().endsWith(".class"))
          .map(applicationRoot::relativize)
          .map(Path::toString)
          .map(path -> path.substring(0, path.length() - ".class".length()))
          .map(path -> path.replace(applicationRoot.getFileSystem().getSeparator(), "."))
          .collect(Collectors.toCollection(HashSet::new));
    }
  }

  private static List<MethodSignature> applicationMethods(
      JavaView view, Set<String> applicationClasses) {
    return applicationClasses.stream()
        .sorted()
        .flatMap(
            className ->
                view.getClass(view.getIdentifierFactory().getClassType(className)).stream())
        .flatMap(sootClass -> sootClass.getMethods().stream())
        .filter(SootMethod::hasBody)
        .map(SootMethod::getSignature)
        .sorted(Comparator.comparing(MethodSignature::toString))
        .toList();
  }

  private static void writeJson(
      Config config,
      JavaView view,
      Set<String> applicationClasses,
      List<MethodSignature> entryPoints,
      long setupMillis,
      List<AnalysisResult> analyses)
      throws IOException {
    StringBuilder json = new StringBuilder();
    json.append("{\n");
    field(json, 1, "schemaVersion", "0.1.0").append(",\n");
    field(json, 1, "issue", "FLPATH-5000").append(",\n");
    field(json, 1, "adr", "ADR-001").append(",\n");
    json.append("  \"provider\": {\n");
    field(json, 2, "name", "SootUp").append(",\n");
    field(json, 2, "version", SOOTUP_VERSION).append(",\n");
    field(json, 2, "algorithms", List.of("cha", "rta"));
    json.append("\n  },\n");
    json.append("  \"fixture\": {\n");
    field(json, 2, "id", config.fixtureId).append(",\n");
    field(json, 2, "profile", config.profile).append(",\n");
    field(json, 2, "javaRelease", config.javaRelease).append(",\n");
    field(json, 2, "applicationClasses", applicationClasses.size()).append(",\n");
    field(json, 2, "applicationMethods", entryPoints.size()).append(",\n");
    field(json, 2, "sourceRoots", config.sourceRoots);
    json.append("\n  },\n");
    json.append("  \"configuration\": {\n");
    field(json, 2, "applicationRoot", config.applicationRoot.toString()).append(",\n");
    field(json, 2, "libraryClasspathEntries", splitClasspath(config.libraryClasspath).size()).append(",\n");
    field(json, 2, "runtime", "DefaultRuntimeAnalysisInputLocation").append(",\n");
    field(json, 2, "entryPointPolicy", "all concrete application methods");
    json.append("\n  },\n");
    json.append("  \"timing\": {\n");
    field(json, 2, "setupMillis", setupMillis).append(",\n");
    field(json, 2, "graphMillis", analyses.stream().mapToLong(a -> a.graphMillis).sum());
    json.append("\n  },\n");
    json.append("  \"targets\": ");
    array(json, config.targets.stream().map(Target::toJson).toList(), 1);
    json.append(",\n");
    json.append("  \"analyses\": ");
    array(json, analyses.stream().map(AnalysisResult::toJson).toList(), 1);
    json.append("\n}\n");
    Files.createDirectories(config.output.getParent());
    Files.writeString(config.output, json);
  }

  private static List<String> splitClasspath(String classpath) {
    if (classpath.isBlank()) {
      return List.of();
    }
    return Arrays.asList(classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)));
  }

  private static long elapsedMillis(long start) {
    return (System.nanoTime() - start) / 1_000_000;
  }

  private static StringBuilder field(StringBuilder json, int indent, String name, Object value) {
    json.append("  ".repeat(indent)).append(quote(name)).append(":");
    if (value instanceof String) {
      json.append(quote((String) value));
    } else if (value instanceof Number || value instanceof Boolean) {
      json.append(value);
    } else if (value instanceof List<?> list) {
      array(json, list.stream().map(item -> quote(String.valueOf(item))).toList(), indent);
    } else {
      json.append("null");
    }
    return json;
  }

  private static void array(StringBuilder json, List<String> values, int indent) {
    json.append("[");
    if (!values.isEmpty()) {
      json.append("\n");
      for (int i = 0; i < values.size(); i++) {
        json.append("  ".repeat(indent + 1)).append(values.get(i));
        if (i + 1 < values.size()) {
          json.append(",");
        }
        json.append("\n");
      }
      json.append("  ".repeat(indent));
    }
    json.append("]");
  }

  private static String quote(String value) {
    StringBuilder escaped = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      char character = value.charAt(i);
      switch (character) {
        case '\\' -> escaped.append("\\\\");
        case '"' -> escaped.append("\\\"");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> escaped.append(character);
      }
    }
    return escaped.append('"').toString();
  }

  private record Config(
      String fixtureId,
      String profile,
      int javaRelease,
      Path applicationRoot,
      String libraryClasspath,
      List<String> sourceRoots,
      List<Target> targets,
      Path output) {
    static Config parse(String[] args) {
      Map<String, List<String>> values = new HashMap<>();
      for (String arg : args) {
        if (!arg.startsWith("--") || !arg.contains("=")) {
          throw new IllegalArgumentException("arguments must use --name=value: " + arg);
        }
        int separator = arg.indexOf('=');
        values.computeIfAbsent(arg.substring(2, separator), ignored -> new ArrayList<>())
            .add(arg.substring(separator + 1));
      }
      return new Config(
          required(values, "fixture-id"),
          required(values, "profile"),
          Integer.parseInt(required(values, "java-release")),
          Path.of(required(values, "application-root")).toAbsolutePath().normalize(),
          values.getOrDefault("library-classpath", List.of()).stream().findFirst().orElse(""),
          splitPathValues(values.getOrDefault("source-root", List.of())),
          values.getOrDefault("target", List.of()).stream().map(Target::parse).toList(),
          Path.of(required(values, "output")).toAbsolutePath().normalize());
    }

    private static String required(Map<String, List<String>> values, String name) {
      List<String> found = values.get(name);
      if (found == null || found.size() != 1 || found.get(0).isBlank()) {
        throw new IllegalArgumentException("missing required argument --" + name + "=...");
      }
      return found.get(0);
    }

    private static List<String> splitPathValues(List<String> values) {
      return values.stream()
          .flatMap(value -> Arrays.stream(value.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))))
          .filter(value -> !value.isBlank())
          .toList();
    }
  }

  private record Target(String id, String signatureText, MethodSignature signature) {
    static Target parse(String value) {
      int separator = value.indexOf('=');
      if (separator <= 0 || separator == value.length() - 1) {
        throw new IllegalArgumentException("target must be id=soot-signature: " + value);
      }
      return new Target(
          value.substring(0, separator),
          value.substring(separator + 1),
          sootup.java.core.JavaIdentifierFactory.getInstance()
              .parseMethodSignature(value.substring(separator + 1)));
    }

    String toJson() {
      return "{\"id\":"
          + quote(id)
          + ",\"requested\":"
          + quote(signatureText)
          + "}";
    }
  }

  private static final class AnalysisResult {
    private final String algorithm;
    private final long graphMillis;
    private final CallGraph graph;
    private final List<Target> targets;
    private final List<String> sourceRoots;
    private final Set<String> applicationClasses;

    private AnalysisResult(
        String algorithm,
        long graphMillis,
        CallGraph graph,
        List<Target> targets,
        List<String> sourceRoots,
        Set<String> applicationClasses) {
      this.algorithm = algorithm;
      this.graphMillis = graphMillis;
      this.graph = graph;
      this.targets = targets;
      this.sourceRoots = sourceRoots;
      this.applicationClasses = applicationClasses;
    }

    String toJson() {
      StringBuilder json = new StringBuilder("{\n");
      field(json, 2, "algorithm", algorithm).append(",\n");
      field(json, 2, "graphMillis", graphMillis).append(",\n");
      field(json, 2, "methodCount", graph.getMethodSignatures().size()).append(",\n");
      field(json, 2, "callCount", graph.callCount()).append(",\n");
      json.append("    \"targetResults\": ");
      array(json, targets.stream().map(this::targetResultJson).toList(), 2);
      json.append(",\n");
      json.append("    \"applicationCalls\": ");
      List<CallGraph.Call> calls =
          graph.getCalls().stream()
              .filter(call -> applicationClasses.contains(className(call.sourceMethodSignature())))
              .sorted(Comparator.comparing(call -> call.sourceMethodSignature().toString()
                  + "->" + call.targetMethodSignature()))
              .toList();
      array(json, calls.stream().map(this::callJson).toList(), 2);
      json.append("\n  }");
      return json.toString();
    }

    private String targetResultJson(Target target) {
      Set<CallGraph.Call> calls =
          graph.containsMethod(target.signature) ? graph.callsTo(target.signature) : Set.of();
      StringBuilder json = new StringBuilder("{\n");
      field(json, 3, "id", target.id).append(",\n");
      field(json, 3, "requested", target.signatureText).append(",\n");
      field(json, 3, "presentInView", graph.containsMethod(target.signature)).append(",\n");
      json.append("      \"incomingCalls\": ");
      array(
          json,
          calls.stream()
              .sorted(Comparator.comparing(call -> call.sourceMethodSignature().toString()))
              .map(this::callJson)
              .toList(),
          3);
      json.append("\n    }");
      return json.toString();
    }

    private String callJson(CallGraph.Call call) {
      StringBuilder json = new StringBuilder("{\"caller\":");
      json.append(quote(call.sourceMethodSignature().toString()));
      json.append(",\"callee\":").append(quote(call.targetMethodSignature().toString()));
      json.append(",\"line\":").append(call.getLineNumber());
      json.append(",\"sourcePath\":").append(quote(sourcePath(call.sourceMethodSignature())));
      return json.append('}').toString();
    }

    private String sourcePath(MethodSignature signature) {
      String className = className(signature);
      String topLevelClass = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
      String relative = topLevelClass.replace('.', '/') + ".java";
      for (String root : sourceRoots) {
        if (Files.isRegularFile(Path.of(root).resolve(relative))) {
          return Path.of(root).resolve(relative).normalize().toString();
        }
      }
      return "unknown";
    }
  }

  private static String className(MethodSignature signature) {
    return signature.getDeclClassType().getFullyQualifiedName();
  }
}
