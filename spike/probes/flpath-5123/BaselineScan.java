package com.projectlightpath.analysis.java;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Scratch acceptance harness for FLPATH-5123 (not production code). */
public final class BaselineScan {
  private BaselineScan() {}

  public static void main(String[] args) throws Exception {
    Path app = Path.of(args[0]).toAbsolutePath().normalize();
    List<Path> dependencies = new ArrayList<>();
    for (String line : Files.readAllLines(Path.of(args[1]))) {
      for (String part : line.split(File.pathSeparator)) {
        if (part.endsWith(".jar")) {
          dependencies.add(Path.of(part));
        }
      }
    }
    AnalysisContext context = AnalysisContext.builder()
        .id("ssc-demo-baseline")
        .framework(AnalysisContext.Framework.SPRING_BOOT)
        .frameworkVersion("2.7.18")
        .fixtureId("fsi-spring-boot")
        .javaRelease(17)
        .repositoryRoot(app)
        .applicationClasses(app.resolve("target/classes"))
        .selectedProfile("default")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", app.resolve("src/main/java"),
            AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspath(dependencies)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(context);
    long enumeration = scan.diagnostics().stream()
        .filter(item -> item.code().equals("descriptor_enumeration_limit")).count();
    long retention = scan.diagnostics().stream()
        .filter(item -> item.code().equals("descriptor_retention_limit")).count();
    long decompression = scan.diagnostics().stream()
        .filter(item -> item.code().equals("archive_decompression_limit")).count();
    System.out.println("dependencies=" + dependencies.size());
    System.out.println("descriptors=" + scan.descriptors().size());
    System.out.println("diagnostics=" + scan.diagnostics().size());
    System.out.println("enumerationDiagnostics=" + enumeration);
    System.out.println("retentionDiagnostics=" + retention);
    System.out.println("decompressionDiagnostics=" + decompression);
    scan.diagnostics().stream().limit(10)
        .forEach(item -> System.out.println("  " + item.code() + " " + item.path()));
  }
}
