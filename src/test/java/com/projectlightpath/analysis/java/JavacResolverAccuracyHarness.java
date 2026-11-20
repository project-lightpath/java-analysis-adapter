package com.projectlightpath.analysis.java;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Runs the textual baseline and the javac prototype over the same fixture catalog. */
final class JavacResolverAccuracyHarness {
  private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();

  private JavacResolverAccuracyHarness() {}

  static Report run(Path workRoot, List<JavacResolverCell> cells) throws IOException {
    Files.createDirectories(workRoot);
    List<CellReport> reports = new ArrayList<>();
    for (JavacResolverCell cell : cells) {
      Path cellRoot = workRoot.resolve(cell.id());
      Files.createDirectories(cellRoot);
      Measured baseline = measure(() -> baseline(cell, cellRoot));
      boolean blocked = cell.release() > Runtime.version().feature();
      Measured javac = blocked
          ? new Measured(JavacResolverResult.empty(cell.id()), 0, 0)
          : measure(() -> javac(cell, cellRoot));
      JavacResolverCell scored = blocked ? cell : cell.alignedTo(javac.result());
      reports.add(new CellReport(
          scored,
          baseline.result(),
          javac.result(),
          blocked || !cell.baselineAvailable()
              ? new JavacResolverMetrics(0, 0, 0, 0, 0, 0, 0)
              : Evaluation.evaluate(scored, baseline.result()),
          blocked
              ? new JavacResolverMetrics(0, 0, 0, 0, 0, 0, 0)
              : Evaluation.evaluate(scored, javac.result()),
          baseline,
          javac,
          blocked));
    }
    return new Report(reports);
  }

  static JavacResolverResult baseline(JavacResolverCell cell, Path cellRoot) {
    if (!cell.baselineAvailable()) {
      return JavacResolverResult.empty(cell.id());
    }
    Path sourceRoot = cellRoot.resolve("src/main/java");
    try {
      for (Map.Entry<String, String> entry : cell.sources().entrySet()) {
        Path file = sourceRoot.resolve(entry.getKey());
        Files.createDirectories(file.getParent());
        Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
      }
      AnalysisContext context = AnalysisContext.builder()
          .id("javac-resolver-" + cell.id())
          .framework(AnalysisContext.Framework.QUARKUS)
          .frameworkVersion("3.40.1")
          .fixtureId("javac-resolver-spike")
          .javaRelease(cell.release())
          .repositoryRoot(cellRoot)
          .applicationClasses(cellRoot.resolve("classes"))
          .selectedProfile("test")
          .analysisScope(AnalysisContext.Scope.PRODUCTION)
          .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
          .dependencyClasspathRequired(false)
          .provenance(AnalysisContext.Provenance.forTesting())
          .build();
      SourceMetadataScanner scanner = new SourceMetadataScanner(context);
      Optional<SourceMetadataScanner.SourceFile> sourceFile = scanner.sourceFile(cell.owner());
      String evidencePath = sourceFile
          .map(file -> cellRoot.resolve("src/main/java").toAbsolutePath().normalize()
              .relativize(file.path().toAbsolutePath().normalize()).toString())
          .orElse("");
      String topLevel = sourceFile.map(SourceMetadataScanner.SourceFile::typeName).orElse("");
      Optional<ServiceLoaderSourceCache.CachedSource> cached =
          ServiceLoaderSourceCache.source(
              scanner, new MethodSymbol(cell.owner(), cell.method(), "()V"));
      if (cached.isEmpty()) {
        return new JavacResolverResult(cell.id(), List.of(), List.of(), evidencePath, topLevel);
      }
      List<JavacResolverFact> facts = new ArrayList<>();
      for (ServiceLoaderCallSite site : cached.get().sites()) {
        String serviceType = sourceFile
            .flatMap(file -> scanner.resolveReferenceType(site.serviceType(), file, site.offset()))
            .orElse(site.fallbackServiceType());
        facts.add(new JavacResolverFact(
            cell.id(),
            evidencePath.isEmpty() ? cell.siteFile() : evidencePath,
            topLevel.isEmpty() ? cell.owner() : topLevel,
            site.line(),
            0,
            site.offset(),
            serviceType,
            "java.util.ServiceLoader",
            "load",
            "TEXTUAL_SITE",
            "DEFINITE",
            "",
            List.of()));
      }
      return new JavacResolverResult(cell.id(), facts, List.of(), evidencePath, topLevel);
    } catch (IOException error) {
      throw new IllegalStateException("cannot build baseline for " + cell.id(), error);
    }
  }

  static JavacResolverResult javac(JavacResolverCell cell, Path cellRoot) {
    try {
      List<Path> roots = new ArrayList<>();
      Path mainRoot = cellRoot.resolve("unit-0");
      writeSources(mainRoot, cell.sources());
      roots.add(mainRoot);
      for (int index = 0; index < cell.extraUnits().size(); index++) {
        Path extra = cellRoot.resolve("unit-" + (index + 1));
        writeSources(extra, cell.extraUnits().get(index));
        roots.add(extra);
      }
      Path classes = Files.createDirectories(cellRoot.resolve("javac-classes"));
      return JavacSourceSymbolResolver.resolve(cell, roots, classes);
    } catch (IOException error) {
      throw new IllegalStateException("cannot run javac for " + cell.id(), error);
    }
  }

  private static void writeSources(Path root, Map<String, String> sources) throws IOException {
    for (Map.Entry<String, String> entry : sources.entrySet()) {
      Path file = root.resolve(entry.getKey());
      Files.createDirectories(file.getParent());
      Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
    }
  }

  private static Measured measure(ThrowingSupplier<JavacResolverResult> action) {
    long start = System.nanoTime();
    java.util.concurrent.atomic.AtomicLong peak = new java.util.concurrent.atomic.AtomicLong(usedHeap());
    java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
    Thread sampler = new Thread(() -> {
      while (running.get()) {
        peak.accumulateAndGet(usedHeap(), Math::max);
        try {
          Thread.sleep(5L);
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }, "javac-resolver-heap-sampler");
    sampler.setDaemon(true);
    sampler.start();
    try {
      JavacResolverResult result = action.get();
      peak.accumulateAndGet(usedHeap(), Math::max);
      return new Measured(result, (System.nanoTime() - start) / 1_000_000L, peak.get());
    } catch (Exception error) {
      throw new IllegalStateException(error);
    } finally {
      running.set(false);
      sampler.interrupt();
    }
  }

  private static long usedHeap() {
    return MEMORY.getHeapMemoryUsage().getUsed();
  }

  @FunctionalInterface
  interface ThrowingSupplier<T> {
    T get() throws Exception;
  }

  record Measured(JavacResolverResult result, long wallMillis, long peakHeapBytes) {}

  record CellReport(
      JavacResolverCell cell,
      JavacResolverResult baseline,
      JavacResolverResult javac,
      JavacResolverMetrics baselineMetrics,
      JavacResolverMetrics javacMetrics,
      Measured baselinePhase,
      Measured javacPhase,
      boolean blocked) {}

  record Report(List<CellReport> cells) {
    JavacResolverMetrics baselineTotals() {
      return JavacResolverMetrics.sum(cells.stream().map(CellReport::baselineMetrics).toList());
    }

    JavacResolverMetrics javacTotals() {
      return JavacResolverMetrics.sum(cells.stream().map(CellReport::javacMetrics).toList());
    }

    String canonical() {
      StringBuilder value = new StringBuilder();
      for (CellReport cell : cells) {
        value.append(cell.cell().id()).append('|')
            .append(cell.baseline()).append('|')
            .append(cell.javac()).append('|')
            .append(cell.baselineMetrics()).append('|')
            .append(cell.javacMetrics()).append('\n');
      }
      return value.toString();
    }

    void write(Path reportPath) throws IOException {
      write(reportPath, true);
    }

    void write(Path reportPath, boolean deterministic) throws IOException {
      Map<String, Object> root = new LinkedHashMap<>();
      root.put("schema", "javac-symbol-resolver-observation.v1");
      root.put("javaVersion", Runtime.version().toString());
      root.put("deterministic", deterministic);
      List<Map<String, Object>> cellItems = new ArrayList<>();
      for (CellReport report : cells) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", report.cell().id());
        item.put("family", report.cell().family().name());
        item.put("release", report.cell().release());
        item.put("blocked", report.blocked());
        item.put("expected", report.cell().expectedSites().stream()
            .map(site -> {
              Map<String, Object> expected = new LinkedHashMap<>();
              expected.put("expectation", site.expectation().name());
              expected.put("serviceType", site.serviceType());
              expected.put("offset", site.offset());
              return expected;
            })
            .toList());
        item.put("baseline", pathItem(report.baseline(), report.baselineMetrics(), report.baselinePhase()));
        item.put("javac", pathItem(report.javac(), report.javacMetrics(), report.javacPhase()));
        cellItems.add(item);
      }
      root.put("cells", cellItems);
      root.put("baselineTotals", baselineTotals().asMap());
      root.put("javacTotals", javacTotals().asMap());
      ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
      Path parent = reportPath.toAbsolutePath().getParent();
      Files.createDirectories(parent);
      mapper.writeValue(reportPath.toFile(), root);
    }

    private static Map<String, Object> pathItem(
        JavacResolverResult result, JavacResolverMetrics metrics, Measured phase) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("wallMillis", phase.wallMillis());
      item.put("peakHeapBytes", phase.peakHeapBytes());
      item.put("selectedPath", result.selectedPath());
      item.put("selectedTopLevel", result.selectedTopLevel());
      item.put("metrics", metrics.asMap());
      List<Map<String, Object>> facts = new ArrayList<>();
      for (JavacResolverFact fact : result.facts()) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("path", fact.path());
        value.put("topLevel", fact.topLevel());
        value.put("line", fact.line());
        value.put("column", fact.column());
        value.put("offset", fact.offset());
        value.put("serviceType", fact.serviceType());
        value.put("owner", fact.owner());
        value.put("member", fact.member());
        value.put("accessibility", fact.accessibility());
        value.put("certainty", fact.certainty());
        value.put("promotion", fact.promotion());
        value.put("diagnostics", fact.diagnostics().stream()
            .map(Report::diagnostic)
            .toList());
        facts.add(value);
      }
      item.put("facts", facts);
      item.put("abstentions", result.abstentions().stream()
          .map(abstention -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("path", abstention.path());
            value.put("line", abstention.line());
            value.put("column", abstention.column());
            value.put("offset", abstention.offset());
            value.put("reason", abstention.reason());
            value.put("diagnostics", abstention.diagnostics().stream()
                .map(Report::diagnostic)
                .toList());
            return value;
          })
          .toList());
      return item;
    }

    private static Map<String, Object> diagnostic(JavacResolverDiagnostic diagnostic) {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("code", diagnostic.code());
      value.put("message", diagnostic.message());
      value.put("path", diagnostic.path());
      value.put("line", diagnostic.line());
      value.put("column", diagnostic.column());
      return value;
    }
  }
}
