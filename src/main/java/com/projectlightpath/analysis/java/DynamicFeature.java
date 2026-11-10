package com.projectlightpath.analysis.java;

import java.util.List;

/** Dynamic behavior observed in source but intentionally not resolved by CHA. */
public record DynamicFeature(
    Kind kind,
    MethodSymbol caller,
    String target,
    String sourcePath,
    List<String> symbols,
    String limitation,
    int sourceLine,
    int sourceOffset,
    String targetDescriptor) {
  /** Compatibility constructor for callers that provide source-site coordinates only. */
  public DynamicFeature(
      Kind kind,
      MethodSymbol caller,
      String target,
      String sourcePath,
      List<String> symbols,
      String limitation,
      int sourceLine,
      int sourceOffset) {
    this(kind, caller, target, sourcePath, symbols, limitation, sourceLine, sourceOffset, "");
  }

  /** Compatibility constructor for provider callers that do not have source-site coordinates. */
  public DynamicFeature(
      Kind kind,
      MethodSymbol caller,
      String target,
      String sourcePath,
      List<String> symbols,
      String limitation) {
    this(kind, caller, target, sourcePath, symbols, limitation, 0, 0, "");
  }

  public enum Kind {
    REFLECTION,
    DYNAMIC_PROXY
  }

  public DynamicFeature {
    if (kind == null) {
      throw new IllegalArgumentException("dynamic feature kind must not be null");
    }
    if (sourcePath == null || sourcePath.isBlank()) {
      sourcePath = "unknown";
    }
    symbols = List.copyOf(symbols == null ? List.of() : symbols);
    limitation = limitation == null || limitation.isBlank()
        ? "dynamic target is not uniquely identified by the selected context"
        : limitation;
    sourceLine = Math.max(0, sourceLine);
    sourceOffset = Math.max(0, sourceOffset);
    targetDescriptor = targetDescriptor == null ? "" : targetDescriptor;
  }
}
