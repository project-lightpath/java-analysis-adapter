package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Red/green gate for the javac resolver spike. */
class JavacResolverAccuracyTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Set<String> BASELINE_WRONG =
      Set.of("N20", "N21", "N22", "N23", "N34", "N36", "A9", "A10", "A11", "A13",
          "A14", "A15", "A16b", "A17", "A20", "I1", "I2", "I3", "I5");
  private static final Set<String> BASELINE_LOST =
      Set.of("N20", "N21", "N22", "N23", "N28", "N32", "A1", "A4", "A7",
          "D1", "D3", "D4", "D6", "D7", "D8", "D9");

  @TempDir Path temp;

  @Test
  void javacPathMeetsGoThresholdsAndTextualBaselineStaysVisible() throws IOException {
    JavacResolverAccuracyHarness.Report report =
        JavacResolverAccuracyHarness.run(temp.resolve("work"), JavacResolverFixtureCatalog.cells());
    Path observation = temp.resolve("observation.json");
    report.write(observation);

    JavacResolverMetrics javac = report.javacTotals();
    assertEquals(0, javac.wrongDefinite());
    assertEquals(0, javac.wrongServiceType());
    assertEquals(0, javac.lost());
    assertEquals(0, javac.downgraded());
    assertEquals(0, javac.added());
    assertEquals(0, javac.unexpectedFact());
    assertEquals(0, javac.abstentionMismatch());

    JavacResolverMetrics baseline = report.baselineTotals();
    assertEquals(19, baseline.wrongDefinite());
    assertEquals(16, baseline.lost());
    assertEquals(12, baseline.added());
    assertEquals(6, baseline.abstentionMismatch());
    assertEquals(BASELINE_WRONG, cellsWith(report, "wrongDefinite", true));
    assertEquals(BASELINE_LOST, cellsWith(report, "lost", true));
    assertEquals(0, report.cells().stream()
        .filter(JavacResolverAccuracyHarness.CellReport::blocked)
        .filter(item -> !List.of("N22", "N23").contains(item.cell().id()))
        .count());
    assertTrue(report.cells().stream()
        .filter(item -> !item.blocked())
        .allMatch(item -> item.cell().release() <= Runtime.version().feature()));
  }

  @Test
  void everyJavacFactCarriesSymbolEvidenceAndIncompleteInputsArePossible() throws IOException {
    JavacResolverAccuracyHarness.Report report =
        JavacResolverAccuracyHarness.run(temp.resolve("evidence-work"), JavacResolverFixtureCatalog.cells());
    for (JavacResolverAccuracyHarness.CellReport cell : report.cells()) {
      for (JavacResolverFact fact : cell.javac().facts()) {
        assertEquals("java.util.ServiceLoader", fact.owner());
        assertEquals("load", fact.member());
        assertEquals("PUBLIC_STATIC", fact.accessibility());
        assertFalse(fact.serviceType().isBlank());
      }
    }
    for (String id : List.of("I1", "I2", "I3", "I4", "I5")) {
      JavacResolverAccuracyHarness.CellReport cell = cell(report, id);
      assertTrue(cell.javac().facts().stream().noneMatch(fact -> fact.certainty().equals("DEFINITE")),
          id + " must not emit DEFINITE: " + cell.javac().facts());
      boolean diagnostic = cell.javac().facts().stream()
          .anyMatch(fact -> !fact.diagnostics().isEmpty())
          || cell.javac().abstentions().stream().anyMatch(item -> !item.diagnostics().isEmpty());
      assertTrue(diagnostic, id + " must carry a diagnostic");
    }
  }

  @Test
  void freshRunsProduceIdenticalFacts() throws IOException {
    Path first = temp.resolve("run-1.json");
    Path second = temp.resolve("run-2.json");
    runHarness(temp.resolve("work-1"), first);
    runHarness(temp.resolve("work-2"), second);
    assertEquals(canonicalFacts(MAPPER.readTree(first.toFile())),
        canonicalFacts(MAPPER.readTree(second.toFile())));
    JsonNode report = MAPPER.readTree(second.toFile());
    assertTrue(report.get("deterministic").asBoolean());
    assertEquals("javac-symbol-resolver-observation.v1", report.get("schema").asText());
    assertEquals(JavacResolverFixtureCatalog.cells().size(), report.get("cells").size());
    assertNoAbsolutePaths(report);
  }

  @Test
  void committedObservationMatchesAFreshRun() throws IOException {
    Path committed = Path.of("spike/results/javac-symbol-resolver-observation.json");
    assertTrue(Files.exists(committed), "committed observation is missing");
    Path fresh = temp.resolve("fresh-observation.json");
    runHarness(temp.resolve("fresh-work"), fresh);
    JsonNode freshTree = MAPPER.readTree(fresh.toFile());
    Set<String> blocked = new java.util.TreeSet<>();
    for (JsonNode cell : freshTree.get("cells")) {
      if (cell.get("blocked").asBoolean()) {
        blocked.add(cell.get("id").asText());
      }
    }
    assertEquals(
        evidenceView(MAPPER.readTree(committed.toFile()), blocked),
        evidenceView(freshTree, blocked));
  }

  @Test
  void tooNewReleaseIsBlockedInsteadOfFailingTheGate() throws IOException {
    JavacResolverCell blocked = new JavacResolverCell(
        "BLOCKED", JavacResolverCell.Family.PRECEDENCE, "demo/Bridge.java", "demo.Bridge",
        "audit", 99,
        Map.of("demo/Bridge.java", """
            package demo;
            import java.util.*;
            class Bridge { void audit() { ServiceLoader.load(Object.class); } }
            """),
        List.of(), false,
        List.of(new JavacResolverCell.ExpectedSite(
            JavacResolverCell.Expectation.DEFINITE, "java.lang.Object")));
    JavacResolverAccuracyHarness.Report report =
        JavacResolverAccuracyHarness.run(temp.resolve("blocked-work"), List.of(blocked));
    assertTrue(report.cells().get(0).blocked());
    assertEquals(0, report.javacTotals().wrongDefinite());
    assertEquals(0, report.javacTotals().lost());
  }

  @Test
  void annotationAndVarargsParameterTypesUseJavacNotTextualHeuristics() throws IOException {
    String source = """
        package demo;
        import java.lang.annotation.*;
        class Probe {
          void varargs(String @$Ann... args) { }
          void annotated(@Ann(value = 1) String value) { }
        }
        @Target(ElementType.TYPE_USE) @interface $Ann { }
        @Target(ElementType.TYPE_USE) @interface Ann { int value(); }
        """;
    assertEquals("java.lang.String[]",
        JavacSourceSymbolResolver.parameterType(source, "demo.Probe", "varargs", 0, temp));
    assertEquals("java.lang.String",
        JavacSourceSymbolResolver.parameterType(source, "demo.Probe", "annotated", 0, temp));
    assertEquals("String@$Ann[]", SourceMetadataScanner.parameterType("String @$Ann... args"));
    assertEquals("(value=1)String", SourceMetadataScanner.parameterType("@Ann(value = 1) String value"));
  }

  private static void runHarness(Path work, Path report) throws IOException {
    String classpath = System.getProperty(
        "surefire.test.class.path", System.getProperty("java.class.path"));
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    List<String> command = new ArrayList<>();
    command.add(java.toString());
    command.add("-Xmx256m");
    command.add("-cp");
    command.add(classpath);
    command.add(JavacResolverHarnessMain.class.getName());
    command.add("--work");
    command.add(work.toString());
    command.add("--report");
    command.add(report.toString());
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exit;
    try {
      exit = process.waitFor();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while running the harness", error);
    }
    assertEquals(0, exit, output);
  }

  private static Set<String> cellsWith(
      JavacResolverAccuracyHarness.Report report, String metric, boolean baseline) {
    Set<String> ids = new TreeSet<>();
    for (JavacResolverAccuracyHarness.CellReport cell : report.cells()) {
      JavacResolverMetrics metrics = baseline ? cell.baselineMetrics() : cell.javacMetrics();
      int value = switch (metric) {
        case "wrongDefinite" -> metrics.wrongDefinite();
        case "lost" -> metrics.lost();
        default -> throw new IllegalArgumentException(metric);
      };
      if (value > 0) {
        ids.add(cell.cell().id());
      }
    }
    return ids;
  }

  private static JavacResolverAccuracyHarness.CellReport cell(
      JavacResolverAccuracyHarness.Report report, String id) {
    return report.cells().stream()
        .filter(item -> item.cell().id().equals(id))
        .findFirst()
        .orElseThrow();
  }

  private static List<String> canonicalFacts(JsonNode report) {
    List<String> facts = new ArrayList<>();
    for (JsonNode cell : report.get("cells")) {
      for (JsonNode fact : cell.get("javac").get("facts")) {
        facts.add(cell.get("id").asText() + "|" + canonical(fact));
      }
    }
    facts.sort(Comparator.naturalOrder());
    return facts;
  }

  private static String canonical(JsonNode node) {
    List<String> fields = new ArrayList<>();
    node.fieldNames().forEachRemaining(fields::add);
    fields.sort(Comparator.naturalOrder());
    StringBuilder value = new StringBuilder();
    for (String field : fields) {
      value.append(field).append('=').append(node.get(field)).append(';');
    }
    return value.toString();
  }

  private static String evidenceView(JsonNode report, Set<String> excludedCells) {
    ObjectNode view = MAPPER.createObjectNode();
    view.put("schema", report.get("schema").asText());
    ArrayNode cells = view.putArray("cells");
    for (JsonNode cell : report.get("cells")) {
      if (excludedCells.contains(cell.get("id").asText())) {
        continue;
      }
      ObjectNode item = cells.addObject();
      item.put("id", cell.get("id").asText());
      item.put("family", cell.get("family").asText());
      item.put("release", cell.get("release").asInt());
      item.set("expected", cell.get("expected"));
      item.set("baselineMetrics", cell.get("baseline").get("metrics"));
      item.set("javacMetrics", cell.get("javac").get("metrics"));
      item.set("baselineFacts", stableSites(cell.get("baseline").get("facts"), true));
      item.set("javacFacts", stableSites(cell.get("javac").get("facts"), true));
      item.set("baselineAbstentions", stableSites(cell.get("baseline").get("abstentions"), false));
      item.set("javacAbstentions", stableSites(cell.get("javac").get("abstentions"), false));
    }
    view.set("baselineTotals", report.get("baselineTotals"));
    view.set("javacTotals", report.get("javacTotals"));
    return view.toString();
  }

  private static ArrayNode stableSites(JsonNode sites, boolean fact) {
    List<JsonNode> sorted = new ArrayList<>();
    sites.forEach(sorted::add);
    sorted.sort(java.util.Comparator.comparingInt(item -> item.get("offset").asInt()));
    ArrayNode result = MAPPER.createArrayNode();
    for (JsonNode site : sorted) {
      ObjectNode item = result.addObject();
      item.put("path", site.get("path").asText());
      item.put("offset", site.get("offset").asInt());
      if (fact) {
        item.put("topLevel", site.get("topLevel").asText());
        item.put("serviceType", site.get("serviceType").asText());
        item.put("owner", site.get("owner").asText());
        item.put("member", site.get("member").asText());
        item.put("accessibility", site.get("accessibility").asText());
        item.put("certainty", site.get("certainty").asText());
        item.put("promotion", site.get("promotion").asText());
      } else {
        item.put("reason", site.get("reason").asText());
      }
      List<String> codes = new ArrayList<>();
      site.get("diagnostics").forEach(diagnostic -> codes.add(diagnostic.get("code").asText()));
      codes.sort(java.util.Comparator.naturalOrder());
      ArrayNode codeNodes = item.putArray("diagnosticCodes");
      codes.forEach(codeNodes::add);
    }
    return result;
  }

  private static void assertNoAbsolutePaths(JsonNode node) {    if (node.isTextual()) {
      assertFalse(node.asText().startsWith("/"), "absolute path in report: " + node.asText());
    } else if (node.isObject()) {
      node.fieldNames().forEachRemaining(name -> assertNoAbsolutePaths(node.get(name)));
    } else if (node.isArray()) {
      node.forEach(JavacResolverAccuracyTest::assertNoAbsolutePaths);
    }
  }
}
