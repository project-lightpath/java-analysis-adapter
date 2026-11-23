package com.projectlightpath.analysis.java;

import static com.projectlightpath.analysis.java.EvidenceCollections.elements;
import static com.projectlightpath.analysis.java.EvidenceCollections.idSet;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validates the checked-in FLPATH-5000 spike contracts without third-party tools (#45 step
 * 2, ported from spike/scripts/validate_spike.py).
 *
 * <p>A regression gate over checked-in files, so it lives in the test tree and runs as
 * part of the suite; {@code make evidence-validate} runs the same cells focused. Loads
 * the eighteen checked-in contract, capability, and observation documents and enforces
 * every recorded invariant through three rule groups: {@link
 * EvidenceDocumentChecks} (matrix, framework model, AnalysisContext, acceptance,
 * baseline, cases, advisory), {@link EvidenceFactChecks} (expected call facts,
 * enrichment, semantic facts), and {@link EvidenceObservationChecks} (observation
 * coverage, analyzer results, javac resolver GO/NO-GO). Python-behavior helpers live in
 * {@link EvidenceSemantics}.
 *
 * <p>Deliberate divergences from the Python original, all preserving exit code 1 (the
 * Python gate exited 1 through an uncaught traceback; this port fails with a stable
 * diagnostic instead):
 *
 * <ul>
 *   <li>a missing, malformed, or wrong-type document fails with {@code cannot read
 *       <path>} naming the repository-relative path, instead of a Python traceback;
 *   <li>a missing or wrong-type field inside a loaded document fails the enclosing
 *       requirement's message (Python raised KeyError/AttributeError), and set-valued
 *       comparisons skip non-textual elements;
 *   <li>the repository root is resolved by walking up from the working directory until a
 *       directory holding {@code pom.xml} and {@code spike/} is found (Python resolved it
 *       from the script file's location), so the gate works from any nested directory;
 *   <li>Python's {@code in} operator is shape-polymorphic (substring on strings, key
 *       membership on dicts, element equality on lists); the port implements the shapes
 *       the checked-in documents use — substring on strings, key membership on objects —
 *       and skips other shapes, so a JSON array containing the exact string
 *       {@code "caller"} (SootUp evidence) fails here where Python passed;
 *   <li>two checks are deliberately stricter than Python in the reject direction: a
 *       JSON-null {@code testProfile} passes Python's {@code None != "prod"} comparison
 *       but fails this gate, and a mirrored extra metric key (present in
 *       {@code javacTotals} and every cell with matching sums) passes Python's
 *       document-keyed recompute but fails this gate's fixed metric inventory. Both
 *       reject what Python accepted; neither accepts what Python rejected. The same
 *       applies to the CodeQL fact counts: Python's float sum accepted balanced
 *       fractional counts ({@code 51.5 + 12.5 == 64}), which this gate rejects by
 *       requiring whole non-negative counts compared with exact arithmetic, and to
 *       set sentinels: Python collapses {@code True} with {@code 1} and {@code 17} with
 *       {@code 17.0}, where the port's value-text sentinels stay distinct.
 * </ul>
 *
 * <p>The final summary line, every requirement message, and both exit codes are otherwise
 * character-for-character the Python gate's (14/14 parity receipts: the real tree plus
 * eleven mutated trees and two malformed-shape trees, identical stdout and exit codes).
 */
public final class EvidenceContractValidator {
  private static final ObjectMapper MAPPER = new ObjectMapper()
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  static final Set<String> CERTAINTY_STATES =
      Set.of("definite", "possible", "unresolved");

  static final String PINNED_BASELINE_COMMIT =
      "01a18a1b40e151ffcc1d7bef86bf335f87999750";

  static final Set<String> REQUIRED_EDGE_TYPES = Set.of("framework_entrypoint",
      "dependency_injection", "proxy_dispatch", "lifecycle_callback",
      "generated_implementation");

  static final Set<String> REQUIRED_EVIDENCE = Set.of(
      "framework_model_contract", "analysis_context_contract",
      "synthetic_framework_fact_evidence");

  static final Set<String> MANDATORY_CRITERIA =
      Set.of("AC-01", "AC-02", "AC-04", "AC-06", "AC-08", "AC-10");

  static final Set<String> REQUIRED_CASES = Set.of(
      "direct-call", "interface-dispatch", "cdi-injection", "framework-entrypoint",
      "callback-lambda", "service-loader", "reflection", "dynamic-proxy",
      "generated-source", "build-profile");

  static final Set<String> REQUIRED_FSI_CASES = Set.of(
      "spring-mvc-entrypoint", "transfer-controller-to-service",
      "transaction-service-to-audit", "transaction-service-to-jpa",
      "spring-bean-callback", "startup-callback");

  static final Set<String> ROUTE_SELECTION_FIELDS = Set.of(
      "httpMethod", "pathTemplate", "consumes", "produces", "headers", "parameters",
      "activeAnalysisContext");

  static final List<String> JAVAC_METRICS = List.of("wrongDefinite",
      "wrongServiceType", "lost", "downgraded", "added", "unexpectedFact",
      "abstentionMismatch");

  static final Set<String> REQUIRED_JAVAC_CELLS = Set.of((
      "N1 N2 N3 N4 N5 N6 N7 N8 N9 N10 N11 N12 N13 N14 N15 N16 N17 N18 N19 "
      + "N20 N21 N22 N23 N24a N24b N25 N26 N27 N28 N29 N30 N31 N32 N33 N34 N35 N36 "
      + "A1 A2 A3 A4 A5 A6 A7 A8 A9 A10 A11 A12 A13 A14 A15 A16 A16b A17 A20 A21 "
      + "D1 D2 D3 D4 D5 D6 D7 D8 D9 I1 I2 I3 I4 I5").split(" "));

  static final List<String> MISSING_INPUT_CODES = List.of("doesnt.exist",
      "cant.resolve", "duplicate.class", "not.def.public");

  /** Repository-relative paths of every document the gate loads, in load order. */
  static final List<String> DOCUMENT_PATHS = List.of(
      "spike/capability-matrix.json",
      "spike/acceptance-criteria.json",
      "spike/candidate-matrix.json",
      "spike/baselines/fsi-spring-boot.json",
      "spike/contracts/synthetic-advisory.json",
      "spike/contracts/expected-call-facts.json",
      "spike/contracts/framework-model.json",
      "spike/contracts/framework-binding-metadata.json",
      "spike/contracts/analysis-context.json",
      "spike/results/framework-enrichment-observation.json",
      "spike/results/semantic-call-facts-spike-observation.json",
      "spike/results/jdtls-1.63.0-java21-observation.json",
      "spike/results/jdtls-1.63.0-fsi-spring-observation.json",
      "spike/results/sootup-3.0.1-java21-quarkus-observation.json",
      "spike/results/sootup-3.0.1-fsi-spring-observation.json",
      "spike/results/codeql-2.27.4-java21-quarkus-observation.json",
      "spike/results/codeql-2.27.4-fsi-spring-observation.json",
      "spike/results/javac-symbol-resolver-observation.json");

  /** Repository-relative script paths whose presence the gate checks. */
  static final List<String> SCRIPT_PATHS = List.of(
      "spike/scripts/run_static_analysis.py",
      "spike/scripts/normalize_static_analysis.py");

  /** Thrown for any failed requirement; the run boundary renders it as exit code 1. */
  public static final class ValidationError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ValidationError(String message) {
      super(message);
    }

    ValidationError(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private EvidenceContractValidator() {}

  /**
   * Runs the gate against a repository root, printing the summary or the failure
   * diagnostic.
   *
   * @param args ignored, matching the Python original which read no arguments
   * @param root repository root holding the {@code spike/} tree
   * @return 0 when every contract validates, 1 otherwise
   */
  public static int run(String[] args, Path root) {
    try {
      System.out.println(validate(root));
      return 0;
    } catch (ValidationError error) {
      System.err.println("contract validation failed: " + error.getMessage());
      return 1;
    }
  }

  /**
   * Validates every checked-in contract, capability case, and observation.
   *
   * @param root repository root holding the {@code spike/} tree
   * @return the summary line, identical to the Python gate's
   */
  public static String validate(Path root) {
    Documents documents = Documents.load(root);
    new EvidenceDocumentChecks(documents).checkAll();
    new EvidenceFactChecks(documents).checkAll();
    new EvidenceObservationChecks(documents, root).checkAll();
    return summaryLine(documents);
  }

  /** The eighteen documents the gate reads, loaded once in the Python gate's order. */
  record Documents(
      JsonNode matrix,
      JsonNode acceptance,
      JsonNode candidates,
      JsonNode baseline,
      JsonNode advisory,
      JsonNode facts,
      JsonNode frameworkModel,
      JsonNode bindingMetadata,
      JsonNode analysisContext,
      JsonNode enrichment,
      JsonNode semanticFacts,
      JsonNode observation,
      JsonNode baselineObservation,
      JsonNode sootupQuarkus,
      JsonNode sootupFsi,
      JsonNode codeqlQuarkus,
      JsonNode codeqlFsi,
      JsonNode javacResolver) {

    static Documents load(Path root) {
      List<JsonNode> nodes = new ArrayList<>();
      for (String path : DOCUMENT_PATHS) {
        nodes.add(loadNode(root, path));
      }
      return new Documents(
          nodes.get(0), nodes.get(1), nodes.get(2), nodes.get(3), nodes.get(4),
          nodes.get(5), nodes.get(6), nodes.get(7), nodes.get(8), nodes.get(9),
          nodes.get(10), nodes.get(11), nodes.get(12), nodes.get(13), nodes.get(14),
          nodes.get(15), nodes.get(16), nodes.get(17));
    }
  }

  /** Loads one repository-relative JSON document, failing loudly and naming the path. */
  static JsonNode loadNode(Path root, String relative) {
    Path file = root.resolve(relative);
    if (!Files.isRegularFile(file)) {
      throw new ValidationError("cannot read " + relative + ": not a regular file");
    }
    try {
      JsonNode node = MAPPER.readTree(file.toFile());
      if (node == null || !node.isObject()) {
        throw new ValidationError("cannot read " + relative
            + ": expected a single JSON object");
      }
      return node;
    } catch (IOException error) {
      throw new ValidationError("cannot parse " + relative + ": " + error.getMessage(),
          error);
    }
  }

  /**
   * Resolves the repository root by walking up from the start directory until a directory
   * holding pom.xml and spike/ is found.
   */
  static Path resolveRepositoryRoot(Path start) {
    Path candidate = start.toAbsolutePath().normalize();
    while (candidate != null) {
      if (Files.isRegularFile(candidate.resolve("pom.xml"))
          && Files.isDirectory(candidate.resolve("spike"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new ValidationError("cannot resolve repository root above "
        + start.toAbsolutePath().normalize());
  }

  /** The summary line, identical to the Python gate's final print. */
  private static String summaryLine(Documents documents) {
    int criteriaIds = idSet(elements(documents.acceptance().path("criteria")), "id")
        .size();
    int candidateIds = idSet(elements(documents.candidates().path("candidateSet")), "id")
        .size();
    int cases = elements(documents.matrix().path("cases")).size();
    int relationships = elements(documents.facts().path("relationships")).size()
        + elements(documents.facts().path("frameworkRelationships")).size();
    int observedIds = idSet(elements(documents.observation().path("observations")),
        "caseId").size();
    return "validated " + criteriaIds + " acceptance criteria, " + candidateIds
        + " candidates, " + cases + " capability cases, " + relationships
        + " expected relationships, and " + observedIds + " observed results";
  }
}
