package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import sootup.callgraph.CallGraph;
import sootup.callgraph.ClassHierarchyAnalysisAlgorithm;
import sootup.core.frontend.SootClassSource;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.jimple.common.expr.JInterfaceInvokeExpr;
import sootup.core.model.SootClass;
import sootup.core.model.SootMethod;
import sootup.core.model.SourceType;
import sootup.core.signatures.MethodSignature;
import sootup.core.util.PathUtils;
import sootup.java.bytecode.frontend.inputlocation.DefaultRuntimeAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation;
import sootup.interceptors.BytecodeBodyInterceptors;
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

    ProviderInputs inputs = createView(context);
    JavaView view = inputs.view();
    Set<String> applicationClasses = discoverApplicationClasses(context.applicationClasses());
    List<MethodSignature> entryPoints = applicationMethods(view, applicationClasses);
    CallGraph graph = new ClassHierarchyAnalysisAlgorithm(view).initialize(entryPoints);
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    List<CallEdge> calls = applicationCalls(
        context, view, graph, applicationClasses, scanner, inputs.dependencyEntries());
    return new AnalysisGraph(
        calls,
        scanner.dynamicFeatures(),
        applicationClasses,
        "SootUp",
        VERSION,
        graph.getMethodSignatures().size(),
        graph.callCount());
  }

  private static ProviderInputs createView(AnalysisContext context) {
    List<AnalysisInputLocation> locations = new ArrayList<>();
    locations.add(new JavaClassPathAnalysisInputLocation(
        context.applicationClasses().toString(), SourceType.Application));
    Map<AnalysisInputLocation, Path> dependencyEntries = new IdentityHashMap<>();
    Set<Path> dependencyPaths = new LinkedHashSet<>();
    for (Path dependency : context.dependencyClasspath()) {
      for (Path expanded : expandDependencyPath(dependency)) {
        Path normalized = expanded.toAbsolutePath().normalize();
        if (!isValidInputLocation(normalized) || !dependencyPaths.add(normalized)) {
          continue;
        }
        AnalysisInputLocation location = PathBasedAnalysisInputLocation.create(
            normalized,
            SourceType.Library,
            BytecodeBodyInterceptors.Default.getBodyInterceptors());
        locations.add(location);
        dependencyEntries.put(location, normalized);
      }
    }
    if (!context.dependencyClasspath().isEmpty() && dependencyEntries.isEmpty()) {
      throw new IllegalArgumentException(
          "dependency classpath contains no valid entries for AnalysisContext " + context.id());
    }
    locations.add(new DefaultRuntimeAnalysisInputLocation(SourceType.Library));
    return new ProviderInputs(new JavaView(locations), dependencyEntries);
  }

  private static boolean isValidInputLocation(Path path) {
    return Files.exists(path) && (Files.isDirectory(path) || PathUtils.isArchive(path));
  }

  /** Expands SootUp-compatible trailing wildcards into concrete JAR entries. */
  private static List<Path> expandDependencyPath(Path dependency) {
    Path normalized = dependency.toAbsolutePath().normalize();
    String value = normalized.toString();
    if (!value.endsWith("*")) {
      return List.of(normalized);
    }

    int wildcardIndex = value.indexOf('*');
    Path directory = normalized.getFileSystem().getPath(value.substring(0, wildcardIndex));
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    List<Path> entries = new ArrayList<>();
    try (var paths = Files.newDirectoryStream(directory)) {
      for (Path path : paths) {
        String fileName = path.getFileName().toString();
        if (Files.isRegularFile(path)
            && (fileName.endsWith(".jar") || fileName.endsWith(".JAR"))) {
          entries.add(path.toAbsolutePath().normalize());
        }
      }
    } catch (IOException error) {
      throw new IllegalArgumentException(
          "cannot expand dependency classpath wildcard: " + dependency, error);
    }
    entries.sort(Comparator.comparing(Path::toString));
    return List.copyOf(entries);
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
      JavaView view,
      CallGraph graph,
      Set<String> applicationClasses,
      SourceMetadataScanner scanner,
      Map<AnalysisInputLocation, Path> dependencyEntries) {
    List<CallEdge> result = new ArrayList<>();
    for (CallGraph.Call call : graph.getCalls()) {
      if (view.getMethod(call.targetMethodSignature()).isEmpty()) {
        continue;
      }
      MethodSymbol caller = MethodSymbol.fromSootSignature(call.sourceMethodSignature().toString());
      MethodSymbol callee = MethodSymbol.fromSootSignature(call.targetMethodSignature().toString());
      if (!applicationClasses.contains(caller.owner())) {
        continue;
      }
      CallEdge.TargetContext targetContext = applicationClasses.contains(callee.owner())
          ? CallEdge.TargetContext.application()
          : dependencyTarget(view, callee, dependencyEntries);
      if (targetContext == null) {
        continue;
      }
      String sourcePath = sourcePath(context, caller.owner());
      boolean serviceLoaderCall = call.getLineNumber() > 0
          ? scanner.sourceLineContains(caller, call.getLineNumber(), "ServiceLoader.load")
          : scanner.methodSource(caller, call.getLineNumber())
              .map(source -> source.contains("ServiceLoader.load"))
              .orElse(false);
      boolean interfaceDispatch = call.invokableStmt().getInvokeExpr()
          .map(expression -> expression instanceof JInterfaceInvokeExpr)
          .orElseGet(() -> scanner.isInterfaceType(callee.owner()));
      String relationshipType = serviceLoaderCall
          ? "service_loader"
          : interfaceDispatch
              ? "interface_dispatch"
              : caller.name().startsWith("lambda$") ? "callback" : "invokes";
      SemanticCallFacts.Certainty certainty = relationshipType.equals("invokes")
          ? SemanticCallFacts.Certainty.DEFINITE
          : SemanticCallFacts.Certainty.POSSIBLE;
      result.add(new CallEdge(
          caller,
          callee,
          call.getLineNumber(),
          sourcePath,
          certainty,
          relationshipType,
          targetContext));
    }
    result.sort(Comparator.comparing((CallEdge edge) -> edge.caller().displayName())
        .thenComparing(edge -> edge.caller().descriptor())
        .thenComparing(edge -> edge.callee().displayName())
        .thenComparing(edge -> edge.callee().descriptor())
        .thenComparingInt(CallEdge::line)
        .thenComparing(CallEdge::relationshipType));
    return List.copyOf(result);
  }

  private static CallEdge.TargetContext dependencyTarget(
      JavaView view,
      MethodSymbol callee,
      Map<AnalysisInputLocation, Path> dependencyEntries) {
    Optional<? extends SootClass> targetClass = view.getClass(
        view.getIdentifierFactory().getClassType(callee.owner()));
    if (targetClass.isEmpty()) {
      return null;
    }
    SootClassSource source = targetClass.get().getClassSource();
    Path dependency = dependencyEntries.get(source.getAnalysisInputLocation());
    if (dependency == null) {
      return null;
    }
    String bytecodePath = source.getSourcePath() == null ? "" : source.getSourcePath().toString();
    String sourcePath = bytecodePath.endsWith(".java") ? bytecodePath : "";
    return new CallEdge.TargetContext(
        CallEdge.TargetOrigin.DEPENDENCY,
        dependency.toString(),
        bytecodePath,
        sourcePath);
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

  private record ProviderInputs(
      JavaView view,
      Map<AnalysisInputLocation, Path> dependencyEntries) {}

}
