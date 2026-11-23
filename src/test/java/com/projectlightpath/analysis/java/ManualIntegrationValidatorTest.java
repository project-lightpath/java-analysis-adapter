package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression cells for the manual-integration validator (#45 step 3, ported from
 * spike/scripts/validate_manual_integration.py).
 *
 * <p>Fixtures are built in code as one minimal valid AnalysisContext/SemanticCallFacts
 * pair satisfying every rule; each failure cell mutates one field. The Python-shaped JSON
 * writer is pinned by exact byte comparisons. No test captures System.out or System.err.
 */
class ManualIntegrationValidatorTest {
  @TempDir Path temp;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String REVISION = "01a18a1b40e151ffcc1d7bef86bf335f87999750";

  /** A minimal valid AnalysisContext. */
  private static ObjectNode context() {
    ObjectNode context = MAPPER.createObjectNode();
    context.put("schemaVersion", "0.1.0");
    context.put("id", "fsi-spring-boot-production");
    context.put("sourceRevision", REVISION);
    context.put("analysisScope", "production");
    context.put("framework", "spring-boot");
    context.putArray("applicationClasses").add("com/redhat/lightword/App.class");
    context.putArray("dependencyClasspath").add("spring-boot-2.7.18.jar");
    ObjectNode build = context.putObject("build");
    build.put("artifact", "target/fsi-spring-boot.jar");
    return context;
  }

  /** A minimal valid SemanticCallFacts document covering every capability case. */
  private static ObjectNode facts() {
    String contextId = "fsi-spring-boot-production";
    ObjectNode facts = MAPPER.createObjectNode();
    facts.put("artifact", "SemanticCallFacts");
    facts.put("contextId", contextId);
    facts.put("framework", "spring-boot");
    ObjectNode metadata = facts.putObject("runMetadata");
    metadata.put("contextId", contextId);
    metadata.put("providerName", "SootUp");
    metadata.put("providerVersion", "3.0.1");
    metadata.put("graphMethodCount", 10);
    metadata.put("graphCallCount", 20);
    ObjectNode provenance = metadata.putObject("provenance");
    provenance.put("sourceRepository", "repo");
    provenance.put("sourceRevision", REVISION);
    provenance.put("sourceFingerprint", "sf");
    provenance.put("generatedSourceFingerprint", "gsf");
    provenance.put("dependencyFingerprint", "df");
    provenance.put("buildFingerprint", "bf");
    provenance.put("toolchainFingerprint", "tf");
    provenance.put("analyzerFingerprint", "af");
    provenance.put("gitRevision", REVISION);
    provenance.put("providerRuntime", "java-21");
    provenance.put("profileDigest", "pd");
    provenance.put("preparedAt", "2026-01-01T00:00:00Z");
    ObjectNode handoff = metadata.putObject("handoff");
    handoff.put("buildFingerprint", "bf");
    handoff.put("buildArtifact", "target/fsi-spring-boot.jar");
    handoff.putArray("limitations").add("one limitation");

    ArrayNode providerFacts = facts.putArray("providerFacts");
    providerFacts.add(fact("p-audit", "provider", "invokes", "definite",
        "TransactionService#transfer", "AuditService#log"));
    providerFacts.add(fact("p-other", "provider", "invokes", "possible",
        "Other#run", "Other#stop"));

    ArrayNode frameworkFacts = facts.putArray("frameworkFacts");
    ObjectNode route = fact("f-route", "framework", "framework_entrypoint", "definite",
        "external HTTP POST /api/transfers",
        "com.redhat.lightwell.controller.TransferController#transfer");
    route.put("providerCallerAbsence", "not_evidence_of_absence");
    ObjectNode criteria = route.putObject("selectionCriteria");
    criteria.put("httpMethod", "POST");
    criteria.put("pathTemplate", "/api/transfers");
    criteria.putArray("consumes");
    criteria.putArray("produces");
    criteria.putArray("headers");
    criteria.putArray("parameters");
    criteria.put("activeAnalysisContext", contextId);
    frameworkFacts.add(route);
    frameworkFacts.add(fact("f-di", "framework", "dependency_injection", "definite",
        "TransferController", "TransactionService"));
    frameworkFacts.add(fact("spring-data-generated-repo", "framework",
        "generated_implementation", "possible", "external", "TransferRepository"));
    frameworkFacts.add(fact("f-bean", "framework", "lifecycle_callback", "definite",
        "external", "SecurityConfig#securityFilterChain"));
    frameworkFacts.add(fact("f-startup", "framework", "lifecycle_callback", "definite",
        "external", "DataSeeder#run"));

    ObjectNode link = facts.putArray("links").addObject();
    link.put("frameworkFactId", "f-route");
    link.put("providerFactId", "p-audit");
    link.put("certaintyPreserved", true);
    link.put("relation", "enriches_without_replacing");

    ObjectNode coverage = facts.putObject("coverage");
    coverage.put("providerFactCount", 2);
    coverage.put("frameworkFactCount", 5);
    return facts;
  }

  private static ObjectNode fact(String id, String factSet, String relationshipType,
      String certainty, String caller, String callee) {
    ObjectNode fact = MAPPER.createObjectNode();
    fact.put("id", id);
    fact.put("factSet", factSet);
    fact.put("contextId", "fsi-spring-boot-production");
    fact.put("certainty", certainty);
    fact.put("relationshipType", relationshipType);
    fact.put("caller", caller);
    fact.put("callee", callee);
    fact.putArray("evidence").add("evidence line");
    return fact;
  }

  /** Writes the pair to scratch files and runs the CLI shape of the validator. */
  private int runValidator(ObjectNode context, ObjectNode facts, Path output)
      throws IOException {
    Path contextFile = temp.resolve("context.json");
    Path factsFile = temp.resolve("facts.json");
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(contextFile.toFile(), context);
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(factsFile.toFile(), facts);
    return ManualIntegrationValidator.run(new String[] {"--context",
        contextFile.toString(), "--facts", factsFile.toString(), "--output",
        output.toString()}, temp);
  }

  /** The valid pair validates and the evidence document carries every case. */
  @Test
  void validPairProducesEvidence() throws IOException {
    ObjectNode evidence = ManualIntegrationValidator.validate(context(), facts(),
        REVISION);
    assertEquals("ManualIntegrationCapabilityEvidence", evidence.path("artifact")
        .asText());
    assertEquals("fsi-spring-boot-production", evidence.path("contextId").asText());
    assertEquals(6, evidence.path("capabilityEvidence").size());
    assertEquals("one limitation", evidence.path("limitations").get(0).asText());

    Path output = temp.resolve("out").resolve("evidence.json");
    assertEquals(0, runValidator(context(), facts(), output));
    assertTrue(Files.isRegularFile(output));
    JsonNode written = MAPPER.readTree(output.toFile());
    assertEquals("SootUp", written.path("provider").path("name").asText());
    assertEquals(2, written.path("coverage").path("providerFactCount").asInt());
  }

  /** Python json.dumps(indent=2) byte shapes: nesting, separators, empty containers. */
  @Test
  void writerMatchesPythonJsonDumps() throws Exception {
    assertEquals("{\n  \"a\": 1\n}", ManualIntegrationValidator.pythonJson(
        MAPPER.createObjectNode().put("a", 1)));
    assertEquals("{}",
        ManualIntegrationValidator.pythonJson(MAPPER.createObjectNode()));
    assertEquals("[]", ManualIntegrationValidator.pythonJson(MAPPER.createArrayNode()));
    assertEquals("{\n  \"a\": [\n    1,\n    2\n  ],\n  \"b\": {\n    \"c\": true\n  }\n}",
        ManualIntegrationValidator.pythonJson(MAPPER.readTree(
            "{\"a\": [1, 2], \"b\": {\"c\": true}}")));
    assertEquals("\"quote: \\\" backslash: \\\\ newline: \\n\"",
        ManualIntegrationValidator.pythonJson(MAPPER.createObjectNode()
            .put("v", "quote: \" backslash: \\ newline: \n").path("v")));
    assertEquals("\"unicode: \\u00e9\"",
        ManualIntegrationValidator.pythonJson(MAPPER.createObjectNode()
            .put("v", "unicode: é").path("v")));
    assertEquals("3.14", ManualIntegrationValidator.pythonJson(
        MAPPER.createObjectNode().put("v", 3.14).path("v")));
    assertEquals("1.0", ManualIntegrationValidator.pythonJson(
        MAPPER.createObjectNode().put("v", 1.0).path("v")));
  }

  /** A report wrapper (status field present) is rejected with the Python message. */
  @Test
  void wrapperReportIsRejected() {
    ObjectNode context = context();
    context.put("status", "ok");
    assertMessage(context, facts(),
        "manual context output must be the direct AnalysisContext, not a report wrapper");
  }

  /** Schema version is pinned. */
  @Test
  void schemaMismatchIsRejected() {
    ObjectNode context = context();
    context.put("schemaVersion", "0.2.0");
    assertMessage(context, facts(), "unsupported AnalysisContext schema");
  }

  /** Source revision must be pinned to the expected revision. */
  @Test
  void revisionMismatchIsRejected() {
    ObjectNode context = context();
    context.put("sourceRevision", "deadbeef");
    assertMessage(context, facts(), "AnalysisContext source revision is not pinned");
    ObjectNode facts = facts();
    ((ObjectNode) facts.path("runMetadata").path("provenance"))
        .put("sourceRevision", "deadbeef");
    assertMessage(context(), facts, "facts source revision is not pinned");
  }

  /** Every provenance field must be present and truthy. */
  @Test
  void missingProvenanceFieldIsRejected() {
    ObjectNode facts = facts();
    ((ObjectNode) facts.path("runMetadata").path("provenance")).remove("gitRevision");
    assertMessage(context(), facts, "provenance field is missing: gitRevision");
  }

  /** The handoff must preserve the build fingerprint. */
  @Test
  void fingerprintMismatchIsRejected() {
    ObjectNode facts = facts();
    ((ObjectNode) facts.path("runMetadata").path("handoff"))
        .put("buildFingerprint", "other");
    assertMessage(context(), facts, "build fingerprint was not preserved");
  }

  /** Unknown certainty states are rejected; unresolved facts must be abstained. */
  @Test
  void certaintyRulesAreEnforced() {
    ObjectNode facts = facts();
    ((ObjectNode) facts.path("providerFacts").get(0)).put("certainty", "likely");
    assertMessage(context(), facts, "unknown certainty state was emitted");

    ObjectNode unresolved = facts();
    ((ObjectNode) unresolved.path("frameworkFacts").get(0)).put("certainty",
        "unresolved");
    assertMessage(context(), unresolved, "unresolved fact was emitted: f-route");
  }

  /** Links must point at known facts and carry a literal true certaintyPreserved. */
  @Test
  void linkRulesAreEnforced() {
    ObjectNode facts = facts();
    ((ObjectNode) facts.path("links").get(0)).put("frameworkFactId", "nope");
    assertMessage(context(), facts, "link points to an unknown framework fact");

    ObjectNode notBoolean = facts();
    ((ObjectNode) notBoolean.path("links").get(0)).put("certaintyPreserved", 1);
    assertMessage(context(), notBoolean, "fact link did not preserve certainty");
  }

  /** The Spring route requires the exact caller, callee, and selection criteria. */
  @Test
  void routeCriteriaMustMatchExactly() {
    ObjectNode facts = facts();
    ObjectNode route = (ObjectNode) facts.path("frameworkFacts").get(0);
    ((ObjectNode) route.path("selectionCriteria")).put("pathTemplate", "/other");
    assertMessage(context(), facts,
        "exact Spring MVC POST /api/transfers route evidence is missing");

    ObjectNode missingField = facts();
    ((ObjectNode) missingField.path("frameworkFacts").get(0).path("selectionCriteria"))
        .remove("httpMethod");
    assertMessage(context(), missingField,
        "exact Spring MVC POST /api/transfers route evidence is missing");
  }

  /** Route facts must preserve provider-caller absence semantics. */
  @Test
  void routeAbsenceSemanticsAreEnforced() {
    ObjectNode facts = facts();
    ((ObjectNode) facts.path("frameworkFacts").get(0))
        .put("providerCallerAbsence", "evidence_of_absence");
    assertMessage(context(), facts,
        "route provider-caller absence semantics were not preserved");
  }

  /** Synthetic provider framework-entrypoint facts are rejected. */
  @Test
  void syntheticProviderEntrypointsAreRejected() {
    ObjectNode facts = facts();
    ((ObjectNode) facts.path("providerFacts").get(0))
        .put("relationshipType", "framework_entrypoint");
    assertMessage(context(), facts,
        "synthetic provider framework-entrypoint facts were emitted");
  }

  /** Every capability case needs at least one fact. */
  @Test
  void missingCapabilityEvidenceIsRejected() {
    ObjectNode facts = facts();
    ((ObjectNode) facts.path("frameworkFacts").get(4)).put("callee", "Other#run");
    assertMessage(context(), facts, "capability evidence is missing: startup-callback");
  }

  /** Missing required arguments exit 2, like argparse. */
  @Test
  void missingRequiredArgumentExitsTwo() throws IOException {
    assertEquals(2, ManualIntegrationValidator.run(new String[] {"--facts",
        temp.resolve("facts.json").toString()}, temp));
    assertEquals(2, ManualIntegrationValidator.run(new String[] {"--bogus", "x"},
        temp));
  }

  /** A missing input file exits 1 with a diagnostic naming the path. */
  @Test
  void missingInputFileExitsOne() throws IOException {
    Path output = temp.resolve("evidence.json");
    assertEquals(1, ManualIntegrationValidator.run(new String[] {"--context",
        temp.resolve("absent.json").toString(), "--facts",
        temp.resolve("absent2.json").toString(), "--output", output.toString()},
        temp));
    assertFalse(Files.exists(output));
  }

  /** Malformed JSON exits 1. */
  @Test
  void malformedInputExitsOne() throws IOException {
    Path contextFile = temp.resolve("context.json");
    Path factsFile = temp.resolve("facts.json");
    Files.writeString(contextFile, "{ not json");
    Files.writeString(factsFile, "{}");
    assertEquals(1, ManualIntegrationValidator.run(new String[] {"--context",
        contextFile.toString(), "--facts", factsFile.toString(), "--output",
        temp.resolve("evidence.json").toString()}, temp));
  }

  /** The default revision is the pinned FSI baseline commit. */
  @Test
  void defaultRevisionIsThePinnedCommit() {
    assertEquals(REVISION, ManualIntegrationValidator.DEFAULT_SOURCE_REVISION);
  }

  /**
   * The boundary case CPython documents: the correctly rounded 16-digit candidate fails
   * to round-trip and the adjacent decimal is the shortest repr.
   */
  @Test
  void writerUsesTheAdjacentFloatCandidate() {
    assertEquals("5.960464477539063e-08", ManualIntegrationValidator.pythonJson(
        MAPPER.createObjectNode().put("v", 5.960464477539063e-08).path("v")));
    assertEquals("-4.3849174499425896e-221", ManualIntegrationValidator.pythonJson(
        MAPPER.createObjectNode().put("v", -4.3849174499425896e-221).path("v")));
  }

  /** Python float repr shapes in the passthrough writer. */
  @Test
  void writerMatchesPythonFloatRepr() {
    assertEquals("10000000000.0", ManualIntegrationValidator.pythonJson(
        MAPPER.createObjectNode().put("v", 1e10).path("v")));
    assertEquals("10000000.0", ManualIntegrationValidator.pythonJson(
        MAPPER.createObjectNode().put("v", 1e7).path("v")));
    assertEquals("1e+22", ManualIntegrationValidator.pythonJson(
        MAPPER.createObjectNode().put("v", 1e22).path("v")));
    assertEquals("1e+16", ManualIntegrationValidator.pythonJson(
        MAPPER.createObjectNode().put("v", 1e16).path("v")));
    assertEquals("0.0001", ManualIntegrationValidator.pythonJson(
        MAPPER.createObjectNode().put("v", 0.0001).path("v")));
    assertEquals("1e-05", ManualIntegrationValidator.pythonJson(
        MAPPER.createObjectNode().put("v", 1e-5).path("v")));
    assertEquals("1.5e-05", ManualIntegrationValidator.pythonJson(
        MAPPER.createObjectNode().put("v", 1.5e-5).path("v")));
  }

  /** Absent coverage and limitations default to {} and [], never empty strings. */
  @Test
  void absentCoverageAndLimitationsDefaultToEmptyContainers() throws IOException {
    ObjectNode facts = facts();
    facts.remove("coverage");
    ((ObjectNode) facts.path("runMetadata").path("handoff")).remove("limitations");
    Path output = temp.resolve("out").resolve("evidence.json");
    assertEquals(0, runValidator(context(), facts, output));
    String written = Files.readString(output);
    assertTrue(written.contains("\"coverage\": {}"),
        "coverage must render as an empty object: " + written);
    assertTrue(written.contains("\"limitations\": []"),
        "limitations must render as an empty array: " + written);
  }

  /** A missing fact id rejects at the capability-evidence boundary, like Python's KeyError. */
  @Test
  void missingFactIdIsRejected() {
    ObjectNode facts = facts();
    ((ObjectNode) facts.path("frameworkFacts").get(4)).remove("id");
    assertMessage(context(), facts, "capability evidence is missing: startup-callback");
  }

  /** A non-string caller rejects up front, where Python's substring test crashed. */
  @Test
  void nonStringCallerIsRejected() {
    ObjectNode facts = facts();
    ((ObjectNode) facts.path("providerFacts").get(0)).put("caller", 5);
    assertMessage(context(), facts, "fact caller must be a string when present");
  }

  /** A non-object coverage rejects before the output is written. */
  @Test
  void nonObjectCoverageIsRejected() {
    ObjectNode facts = facts();
    facts.put("coverage", "oops");
    assertMessage(context(), facts, "facts coverage must be an object when present");
  }

  /** A container coverage count rejects and leaves no evidence file behind. */
  @Test
  void containerCoverageCountIsRejected() throws IOException {
    ObjectNode facts = facts();
    ((ObjectNode) facts.path("coverage")).set("providerFactCount",
        MAPPER.createObjectNode());
    Path output = temp.resolve("out").resolve("evidence.json");
    assertEquals(1, runValidator(context(), facts, output));
    assertFalse(Files.exists(output), "a failed run must not leave an evidence file");
  }

  /** A trailing option without a value exits 2 with usage, never a bounds crash. */
  @Test
  void trailingOptionWithoutValueExitsTwo() throws IOException {
    Path contextFile = temp.resolve("context.json");
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(contextFile.toFile(), context());
    assertEquals(2, ManualIntegrationValidator.run(new String[] {"--context",
        contextFile.toString(), "--facts"}, temp));
  }

  /** A null coverage count renders as Python's None. */
  @Test
  void nullCoverageCountRendersAsNone() throws IOException {
    ObjectNode facts = facts();
    ((ObjectNode) facts.path("coverage")).putNull("frameworkFactCount");
    Path output = temp.resolve("evidence.json");
    assertEquals(0, runValidator(context(), facts, output));
    assertTrue(Files.readString(output).contains("\"frameworkFactCount\": null"));
  }

  /** A null certainty with definite and possible present rejects with the Python message. */
  @Test
  void missingCertaintyRejectsWithDiagnostic() {
    ObjectNode facts = facts();
    ObjectNode extra = facts.putArray("providerFacts").addObject();
    extra.put("id", "p-null");
    extra.put("factSet", "provider");
    extra.put("contextId", "fsi-spring-boot-production");
    extra.put("relationshipType", "invokes");
    extra.put("caller", "X#a");
    extra.put("callee", "Y#b");
    extra.putArray("evidence").add("edge");
    assertMessage(context(), facts, "unknown certainty state was emitted");
  }

  /** A non-array links field rejects (absent stays valid, like Python's .get default). */
  @Test
  void nonArrayLinksAreRejected() {
    ObjectNode facts = facts();
    facts.put("links", "oops");
    assertMessage(context(), facts, "facts links must be an array");
  }

  /** A missing framework on both documents rejects instead of emitting null. */
  @Test
  void missingFrameworkIsRejected() {
    ObjectNode context = context();
    ObjectNode facts = facts();
    context.remove("framework");
    facts.remove("framework");
    assertMessage(context, facts, "facts/context frameworks differ");
  }

  /** Trailing JSON content after a valid input is rejected. */
  @Test
  void trailingJsonContentIsRejected() throws IOException {
    Path contextFile = temp.resolve("context.json");
    Path factsFile = temp.resolve("facts.json");
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(contextFile.toFile(), context());
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(factsFile.toFile(), facts());
    Files.writeString(factsFile, Files.readString(factsFile) + "\n{\"extra\": 1}\n");
    assertEquals(1, ManualIntegrationValidator.run(new String[] {"--context",
        contextFile.toString(), "--facts", factsFile.toString(), "--output",
        temp.resolve("evidence.json").toString()}, temp));
  }

  /** A directory at an input path exits 1. */
  @Test
  void directoryAtPathExitsOne() throws IOException {
    Path directory = Files.createDirectories(temp.resolve("not-a-file.json"));
    assertEquals(1, ManualIntegrationValidator.run(new String[] {"--context",
        directory.toString(), "--facts", directory.toString(), "--output",
        temp.resolve("evidence.json").toString()}, temp));
  }

  /** An empty input file exits 1. */
  @Test
  void emptyInputFileExitsOne() throws IOException {
    Path contextFile = temp.resolve("empty.json");
    Path factsFile = temp.resolve("facts.json");
    Files.writeString(contextFile, "");
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(factsFile.toFile(), facts());
    assertEquals(1, ManualIntegrationValidator.run(new String[] {"--context",
        contextFile.toString(), "--facts", factsFile.toString(), "--output",
        temp.resolve("evidence.json").toString()}, temp));
  }

  private static void assertMessage(ObjectNode context, ObjectNode facts,
      String fragment) {
    ManualIntegrationValidator.ValidationError error = assertThrows(
        ManualIntegrationValidator.ValidationError.class,
        () -> ManualIntegrationValidator.validate(context, facts, REVISION));
    assertTrue(error.getMessage().contains(fragment),
        "expected message containing <" + fragment + ">, got <" + error.getMessage()
            + ">");
  }
}
