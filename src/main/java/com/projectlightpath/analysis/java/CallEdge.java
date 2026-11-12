package com.projectlightpath.analysis.java;

/** One application call edge extracted from the SootUp base graph. */
public record CallEdge(
    MethodSymbol caller,
    MethodSymbol callee,
    int line,
    String sourcePath,
    SemanticCallFacts.Certainty certaintyHint,
    String relationshipType,
    TargetContext targetContext) {
  public enum TargetOrigin {
    APPLICATION("application"),
    DEPENDENCY("dependency");

    private final String id;

    TargetOrigin(String id) {
      this.id = id;
    }

    public String id() {
      return id;
    }
  }

  /** Describes the resolved input that supplied a call target. */
  public record TargetContext(
      TargetOrigin origin,
      String dependencyClasspathEntry,
      String targetBytecodePath,
      String targetSourcePath) {
    public TargetContext {
      origin = origin == null ? TargetOrigin.APPLICATION : origin;
      dependencyClasspathEntry = blankIfNull(dependencyClasspathEntry);
      targetBytecodePath = blankIfNull(targetBytecodePath);
      targetSourcePath = blankIfNull(targetSourcePath);
      if (origin == TargetOrigin.APPLICATION) {
        dependencyClasspathEntry = "";
      }
      if (origin == TargetOrigin.DEPENDENCY && dependencyClasspathEntry.isBlank()) {
        throw new IllegalArgumentException("dependency target context requires a classpath entry");
      }
    }

    public static TargetContext application() {
      return new TargetContext(TargetOrigin.APPLICATION, "", "", "");
    }

    private static String blankIfNull(String value) {
      return value == null ? "" : value;
    }
  }

  /** Compatibility constructor for existing provider graph fixtures. */
  public CallEdge(
      MethodSymbol caller,
      MethodSymbol callee,
      int line,
      String sourcePath,
      SemanticCallFacts.Certainty certaintyHint,
      String relationshipType) {
    this(caller, callee, line, sourcePath, certaintyHint, relationshipType, TargetContext.application());
  }

  public CallEdge {
    if (line < -1) {
      throw new IllegalArgumentException("call line must be -1 or non-negative");
    }
    if (sourcePath == null || sourcePath.isBlank()) {
      sourcePath = "unknown";
    }
    certaintyHint = certaintyHint == null ? SemanticCallFacts.Certainty.DEFINITE : certaintyHint;
    relationshipType = relationshipType == null || relationshipType.isBlank() ? "invokes" : relationshipType;
    targetContext = targetContext == null ? TargetContext.application() : targetContext;
  }

  public static CallEdge definite(MethodSymbol caller, MethodSymbol callee, int line, String sourcePath) {
    return new CallEdge(
        caller,
        callee,
        line,
        sourcePath,
        SemanticCallFacts.Certainty.DEFINITE,
        "invokes",
        TargetContext.application());
  }
}
