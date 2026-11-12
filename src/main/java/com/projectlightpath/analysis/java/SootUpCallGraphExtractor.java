package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import sootup.callgraph.CallGraph;
import sootup.core.frontend.SootClassSource;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.jimple.common.expr.JInterfaceInvokeExpr;
import sootup.core.jimple.common.expr.JVirtualInvokeExpr;
import sootup.core.model.SootClass;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.views.JavaView;

/** Extracts application-owned SootUp calls and exact dependency target context. */
final class SootUpCallGraphExtractor {
  private SootUpCallGraphExtractor() {}

  static Set<String> discoverApplicationClasses(Path applicationRoot) {
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

  static List<MethodSignature> applicationMethods(
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

  static List<CallEdge> applicationCalls(
      AnalysisContext context,
      JavaView view,
      CallGraph graph,
      Set<String> applicationClasses,
      SourceMetadataScanner scanner,
      Map<AnalysisInputLocation, Path> dependencyEntries) {
    List<CallEdge> result = new ArrayList<>();
    for (CallGraph.Call call : graph.getCalls()) {
      applicationCall(context, view, call, applicationClasses, scanner, dependencyEntries)
          .ifPresent(result::add);
    }
    result.sort(Comparator.comparing((CallEdge edge) -> edge.caller().displayName())
        .thenComparing(edge -> edge.caller().descriptor())
        .thenComparing(edge -> edge.callee().displayName())
        .thenComparing(edge -> edge.callee().descriptor())
        .thenComparingInt(CallEdge::line)
        .thenComparing(CallEdge::relationshipType));
    return List.copyOf(result);
  }

  private static Optional<CallEdge> applicationCall(
      AnalysisContext context,
      JavaView view,
      CallGraph.Call call,
      Set<String> applicationClasses,
      SourceMetadataScanner scanner,
      Map<AnalysisInputLocation, Path> dependencyEntries) {
    if (view.getMethod(call.targetMethodSignature()).isEmpty()) {
      return Optional.empty();
    }
    MethodSymbol caller = MethodSymbol.fromSootSignature(call.sourceMethodSignature().toString());
    MethodSymbol callee = MethodSymbol.fromSootSignature(call.targetMethodSignature().toString());
    if (!applicationClasses.contains(caller.owner())) {
      return Optional.empty();
    }
    CallEdge.TargetContext targetContext = targetContext(
        view, callee, applicationClasses, dependencyEntries);
    if (targetContext == null) {
      return Optional.empty();
    }
    String relationshipType = relationshipType(call, caller, callee, scanner);
    return Optional.of(new CallEdge(
        caller,
        callee,
        call.getLineNumber(),
        sourcePath(context, caller.owner()),
        certainty(call, view, relationshipType, targetContext),
        relationshipType,
        targetContext));
  }

  private static CallEdge.TargetContext targetContext(
      JavaView view,
      MethodSymbol callee,
      Set<String> applicationClasses,
      Map<AnalysisInputLocation, Path> dependencyEntries) {
    return applicationClasses.contains(callee.owner())
        ? CallEdge.TargetContext.application()
        : dependencyTarget(view, callee, dependencyEntries);
  }

  private static String relationshipType(
      CallGraph.Call call, MethodSymbol caller, MethodSymbol callee, SourceMetadataScanner scanner) {
    boolean serviceLoaderCall = serviceLoaderCall(call, caller, scanner);
    boolean interfaceDispatch = interfaceDispatch(call, callee, scanner);
    if (serviceLoaderCall) {
      return "service_loader";
    }
    if (interfaceDispatch) {
      return "interface_dispatch";
    }
    return caller.name().startsWith("lambda$") ? "callback" : "invokes";
  }

  private static boolean serviceLoaderCall(
      CallGraph.Call call, MethodSymbol caller, SourceMetadataScanner scanner) {
    if (call.getLineNumber() > 0) {
      return scanner.sourceLineContains(caller, call.getLineNumber(), "ServiceLoader.load");
    }
    return scanner.methodSource(caller, call.getLineNumber())
        .map(source -> source.contains("ServiceLoader.load"))
        .orElse(false);
  }

  private static boolean interfaceDispatch(
      CallGraph.Call call, MethodSymbol callee, SourceMetadataScanner scanner) {
    return call.invokableStmt().getInvokeExpr()
        .map(expression -> expression instanceof JInterfaceInvokeExpr)
        .orElseGet(() -> scanner.isInterfaceType(callee.owner()));
  }

  private static SemanticCallFacts.Certainty certainty(
      CallGraph.Call call,
      JavaView view,
      String relationshipType,
      CallEdge.TargetContext targetContext) {
    if (!relationshipType.equals("invokes")) {
      return SemanticCallFacts.Certainty.POSSIBLE;
    }
    if (targetContext.origin() == CallEdge.TargetOrigin.DEPENDENCY
        && virtualTargetMayDispatch(view, call)) {
      return SemanticCallFacts.Certainty.POSSIBLE;
    }
    return SemanticCallFacts.Certainty.DEFINITE;
  }

  /** Returns whether CHA may have emitted one of several virtual dependency targets. */
  private static boolean virtualTargetMayDispatch(JavaView view, CallGraph.Call call) {
    boolean virtualInvoke = call.invokableStmt().getInvokeExpr()
        .map(expression -> expression instanceof JVirtualInvokeExpr)
        .orElse(false);
    if (!virtualInvoke) {
      return false;
    }
    MethodSignature declared = call.invokableStmt().getInvokeExpr()
        .map(expression -> expression.getMethodSignature())
        .orElseThrow();
    boolean finalMethod = resolveMethod(view, declared)
        .map(SootMethod::isFinal)
        .orElse(false);
    boolean finalClass = view.getClass(declared.getDeclClassType())
        .map(SootClass::isFinal)
        .orElse(false);
    return !finalMethod && !finalClass;
  }

  private static Optional<? extends SootMethod> resolveMethod(
      JavaView view, MethodSignature signature) {
    MethodSignature current = signature;
    while (true) {
      Optional<? extends SootClass> declaringClass = view.getClass(current.getDeclClassType());
      if (declaringClass.isEmpty()) {
        return Optional.empty();
      }
      Optional<? extends SootMethod> method = declaringClass.get()
          .getMethod(current.getSubSignature());
      if (method.isPresent()) {
        return method;
      }
      var superclass = declaringClass.get().getSuperclass();
      if (superclass.isEmpty()) {
        return Optional.empty();
      }
      current = new MethodSignature(superclass.get(), current.getSubSignature());
    }
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
}
