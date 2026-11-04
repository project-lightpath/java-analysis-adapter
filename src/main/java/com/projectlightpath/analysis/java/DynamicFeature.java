package com.projectlightpath.analysis.java;

import java.util.List;

/** Dynamic behavior observed in source but intentionally not resolved by CHA. */
public record DynamicFeature(
    Kind kind,
    MethodSymbol caller,
    String target,
    String sourcePath,
    List<String> symbols,
    String limitation) {
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
  }
}
