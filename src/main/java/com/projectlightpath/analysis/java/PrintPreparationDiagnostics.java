package com.projectlightpath.analysis.java;

import java.nio.file.Path;
import java.util.List;

/** Behavior-neutral compile stub for the RED commit; implementation lands in GREEN. */
public final class PrintPreparationDiagnostics {
  private PrintPreparationDiagnostics() {}

  /** Renders fail-visible diagnostic lines for a preparation report. */
  public static List<String> render(Path reportPath) {
    return List.of();
  }

  /** Prints report diagnostics to stderr; never exits non-zero. */
  public static void main(String[] args) {}
}
