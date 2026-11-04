package com.projectlightpath.analysis.java;

/** One application call edge extracted from the SootUp base graph. */
public record CallEdge(
    MethodSymbol caller,
    MethodSymbol callee,
    int line,
    String sourcePath,
    SemanticCallFacts.Certainty certaintyHint,
    String relationshipType) {
  public CallEdge {
    if (line < -1) {
      throw new IllegalArgumentException("call line must be -1 or non-negative");
    }
    if (sourcePath == null || sourcePath.isBlank()) {
      sourcePath = "unknown";
    }
    certaintyHint = certaintyHint == null ? SemanticCallFacts.Certainty.DEFINITE : certaintyHint;
    relationshipType = relationshipType == null || relationshipType.isBlank() ? "invokes" : relationshipType;
  }

  public static CallEdge definite(MethodSymbol caller, MethodSymbol callee, int line, String sourcePath) {
    return new CallEdge(caller, callee, line, sourcePath, SemanticCallFacts.Certainty.DEFINITE, "invokes");
  }
}
