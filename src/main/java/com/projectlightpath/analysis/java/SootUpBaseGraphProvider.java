package com.projectlightpath.analysis.java;

import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import sootup.callgraph.CallGraph;
import sootup.callgraph.ClassHierarchyAnalysisAlgorithm;
import sootup.core.signatures.MethodSignature;
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

    SootUpInputLocationFactory.Inputs inputs = SootUpInputLocationFactory.createView(context);
    JavaView view = inputs.view();
    Set<String> applicationClasses = SootUpCallGraphExtractor.discoverApplicationClasses(
        context.applicationClasses());
    List<MethodSignature> entryPoints = SootUpCallGraphExtractor.applicationMethods(
        view, applicationClasses);
    CallGraph graph = new ClassHierarchyAnalysisAlgorithm(view).initialize(entryPoints);
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    List<CallEdge> calls = SootUpCallGraphExtractor.applicationCalls(
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

}
