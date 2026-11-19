package com.projectlightpath.analysis.java;

import java.nio.file.Path;
import java.util.List;

/** Subprocess entry so the spike can run under a fixed heap ceiling. */
public final class JavacResolverHarnessMain {
  private JavacResolverHarnessMain() {}

  public static void main(String[] args) throws Exception {
    Path report = null;
    Path work = null;
    for (int index = 0; index < args.length; index++) {
      if ("--report".equals(args[index]) && index + 1 < args.length) {
        report = Path.of(args[++index]);
      } else if ("--work".equals(args[index]) && index + 1 < args.length) {
        work = Path.of(args[++index]);
      }
    }
    if (report == null || work == null) {
      throw new IllegalArgumentException("usage: --work <dir> --report <file>");
    }
    List<JavacResolverCell> cells = JavacResolverFixtureCatalog.cells();
    JavacResolverAccuracyHarness.Report result = JavacResolverAccuracyHarness.run(work, cells);
    result.write(report);
    System.out.println("wrote " + report);
  }
}
