package com.projectlightpath.analysis.java;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import sootup.callgraph.CallGraph;
import sootup.callgraph.ClassHierarchyAnalysisAlgorithm;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.model.SootMethod;
import sootup.core.model.SourceType;
import sootup.core.signatures.MethodSignature;
import sootup.java.bytecode.frontend.inputlocation.DefaultRuntimeAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.views.JavaView;

/** Builds the provider-owned SootUp CHA graph for a selected AnalysisContext. */
public final class SootUpBaseGraphProvider {
  public static final String VERSION = "3.0.1";

  public AnalysisGraph analyze(AnalysisContext context) {
    if (!Files.isDirectory(context.applicationClasses())) {
      throw new IllegalArgumentException(
          "application classes must be a compiled classes directory: " + context.applicationClasses());
    }
    if (context.dependencyClasspathRequired() && context.dependencyClasspath().isEmpty()) {
      throw new IllegalArgumentException(
          "dependency classpath is required by AnalysisContext " + context.id());
    }

    JavaView view = createView(context);
    Set<String> applicationClasses = discoverApplicationClasses(context.applicationClasses());
    List<MethodSignature> entryPoints = applicationMethods(view, applicationClasses);
    CallGraph graph = new ClassHierarchyAnalysisAlgorithm(view).initialize(entryPoints);
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    List<CallEdge> calls = applicationCalls(context, graph, applicationClasses, scanner);
    return new AnalysisGraph(
        calls,
        scanner.dynamicFeatures(),
        applicationClasses,
        "SootUp",
        VERSION,
        graph.getMethodSignatures().size(),
        graph.callCount());
  }

  private static JavaView createView(AnalysisContext context) {
    List<AnalysisInputLocation> locations = new ArrayList<>();
    locations.add(new JavaClassPathAnalysisInputLocation(
        context.applicationClasses().toString(), SourceType.Application));
    if (!context.dependencyClasspath().isEmpty()) {
      String classpath = context.dependencyClasspath().stream()
          .map(Path::toString)
          .collect(Collectors.joining(File.pathSeparator));
      locations.add(new JavaClassPathAnalysisInputLocation(classpath, SourceType.Library));
    }
    locations.add(new DefaultRuntimeAnalysisInputLocation(SourceType.Library));
    return new JavaView(locations);
  }

  private static Set<String> discoverApplicationClasses(Path applicationRoot) {
    try (var paths = Files.walk(applicationRoot)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(path -> path.toString().endsWith(".class"))
          .map(applicationRoot::relativize)
          .map(Path::toString)
          .map(path -> path.substring(0, path.length() - ".class".length()))
          .map(path -> path.replace(applicationRoot.getFileSystem().getSeparator(), "."))
          .collect(Collectors.toCollection(HashSet::new));
    } catch (IOException error) {
      throw new IllegalStateException("cannot discover application classes in " + applicationRoot, error);
    }
  }

  private static List<MethodSignature> applicationMethods(
      JavaView view, Set<String> applicationClasses) {
    return applicationClasses.stream()
        .sorted()
        .flatMap(className -> view.getClass(view.getIdentifierFactory().getClassType(className)).stream())
        .flatMap(sootClass -> sootClass.getMethods().stream())
        .filter(SootMethod::hasBody)
        .map(SootMethod::getSignature)
        .sorted(Comparator.comparing(MethodSignature::toString))
        .toList();
  }

  private static List<CallEdge> applicationCalls(
      AnalysisContext context,
      CallGraph graph,
      Set<String> applicationClasses,
      SourceMetadataScanner scanner) {
    List<CallEdge> result = new ArrayList<>();
    for (CallGraph.Call call : graph.getCalls()) {
      MethodSymbol caller = MethodSymbol.fromSootSignature(call.sourceMethodSignature().toString());
      MethodSymbol callee = MethodSymbol.fromSootSignature(call.targetMethodSignature().toString());
      if (!applicationClasses.contains(caller.owner()) || !applicationClasses.contains(callee.owner())) {
        continue;
      }
      String sourcePath = sourcePath(context, caller.owner());
      String source = sourceFor(scanner, caller.owner());
      boolean serviceLoaderCall = source.contains("ServiceLoader.load")
          || (caller.owner().endsWith("ServiceLoaderBridge") && caller.name().startsWith("lambda$"));
      String relationshipType = serviceLoaderCall ? "service_loader"
          : caller.name().startsWith("lambda$") ? "callback" : "invokes";
      boolean boundedDispatch = scanner.files().stream()
          .filter(file -> file.typeName().equals(callee.owner()))
          .anyMatch(file -> file.source().matches("(?s).*\\bimplements\\s+[^\\{]+.*"));
      boolean sourceNamesConcreteTarget = source.contains(simpleName(callee.owner()));
      SemanticCallFacts.Certainty certainty = relationshipType.equals("invokes")
              && !(boundedDispatch && !sourceNamesConcreteTarget)
          ? SemanticCallFacts.Certainty.DEFINITE
          : SemanticCallFacts.Certainty.POSSIBLE;
      result.add(new CallEdge(
          caller,
          callee,
          call.getLineNumber(),
          sourcePath,
          certainty,
          relationshipType));
    }
    result.sort(Comparator.comparing(edge -> edge.caller().descriptor() + "->" + edge.callee().descriptor()));
    return List.copyOf(result);
  }

  private static String simpleName(String typeName) {
    int dot = typeName.lastIndexOf('.');
    return dot < 0 ? typeName : typeName.substring(dot + 1);
  }

  private static String sourcePath(AnalysisContext context, String owner) {
    String topLevel = owner.contains("$") ? owner.substring(0, owner.indexOf('$')) : owner;
    String relative = topLevel.replace('.', '/') + ".java";
    for (AnalysisContext.SourceRoot root : context.includedSourceRoots()) {
      Path candidate = root.path().resolve(relative);
      if (Files.isRegularFile(candidate)) {
        return context.displayPath(candidate);
      }
    }
    return "unknown";
  }

  private static String sourceFor(SourceMetadataScanner scanner, String owner) {
    String topLevel = owner.contains("$") ? owner.substring(0, owner.indexOf('$')) : owner;
    return scanner.files().stream()
        .filter(file -> file.typeName().equals(topLevel))
        .map(SourceMetadataScanner.SourceFile::source)
        .findFirst()
        .orElse("");
  }
}
