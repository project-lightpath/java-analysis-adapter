package com.projectlightpath.analysis.java;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import sootup.core.jimple.common.expr.JInterfaceInvokeExpr;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.views.JavaView;

/**
 * Body-scanning pass for declared-target provider edges: application invokes
 * on app-declared Spring Data repository interfaces name the interface method
 * in the bytecode, and CHA never targets the abstract interface method itself.
 */
final class SootUpRepositoryDeclaredInvokes {
  private SootUpRepositoryDeclaredInvokes() {}

  /** Emits declared-target edges for invokes on app-declared Spring Data repositories. */
  static List<CallEdge> edges(
      AnalysisContext context,
      JavaView view,
      Set<String> applicationClasses,
      SourceMetadataScanner scanner) {
    if (context.framework() != AnalysisContext.Framework.SPRING_BOOT) {
      return List.of();
    }
    Set<String> repositoryInterfaces = scanner
        .beanCandidates(AnalysisContext.Framework.SPRING_BOOT).stream()
        .filter(candidate -> candidate.kind().equals("repository_interface"))
        .map(candidate -> candidate.file().typeName())
        .collect(Collectors.toCollection(LinkedHashSet::new));
    if (repositoryInterfaces.isEmpty()) {
      return List.of();
    }
    List<CallEdge> result = new java.util.ArrayList<>();
    for (MethodSignature method : SootUpCallGraphExtractor.applicationMethods(view, applicationClasses)) {
      view.getMethod(method).ifPresent(sootMethod -> sootMethod.getBody().getStmts()
          .forEach(stmt -> declaredInvoke(context, method, stmt, repositoryInterfaces)
              .ifPresent(result::add)));
    }
    return result;
  }

  private static Optional<CallEdge> declaredInvoke(
      AnalysisContext context,
      MethodSignature callerSignature,
      sootup.core.jimple.common.stmt.Stmt stmt,
      Set<String> repositoryInterfaces) {
    if (!stmt.isInvokableStmt()) {
      return Optional.empty();
    }
    Optional<JInterfaceInvokeExpr> invoke = stmt.asInvokableStmt().getInvokeExpr()
        .filter(JInterfaceInvokeExpr.class::isInstance)
        .map(JInterfaceInvokeExpr.class::cast);
    if (invoke.isEmpty()) {
      return Optional.empty();
    }
    JInterfaceInvokeExpr expression = invoke.get();
    MethodSymbol caller = MethodSymbol.fromSootSignature(callerSignature.toString());
    MethodSymbol callee = MethodSymbol.fromSootSignature(
        expression.getMethodSignature().toString());
    if (!repositoryInterfaces.contains(callee.owner())) {
      return Optional.empty();
    }
    int line = stmt.getPositionInfo().getStmtPosition().getFirstLine();
    return Optional.of(new CallEdge(
        caller,
        callee,
        line,
        SootUpCallGraphExtractor.sourcePath(context, caller.owner()),
        SemanticCallFacts.Certainty.POSSIBLE,
        "declared_invokes",
        CallEdge.TargetContext.application(),
        expression.getBase().getName()));
  }
}
