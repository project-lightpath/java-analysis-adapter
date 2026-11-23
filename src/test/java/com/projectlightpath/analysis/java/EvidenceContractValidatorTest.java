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
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression cells for the spike-contract validation gate (#45 step 2, ported from
 * spike/scripts/validate_spike.py).
 *
 * <p>Two tiers: helper cells pin the Python semantics the port must preserve (truthiness,
 * numeric equality, host-path predicates, javac cell inventory), and mutated-tree cells copy
 * the real checked-in spike tree, break one field, and pin the exact diagnostic the Python
 * gate printed for the same mutation. No test captures System.out or System.err; output is
 * asserted on returned values, per the parallel-suite constraint.
 */
class EvidenceContractValidatorTest {
  @TempDir Path temp;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The repository root, resolved the way the validator resolves it at run time. */
  private static Path repoRoot() {
    return EvidenceContractValidator.resolveRepositoryRoot(Path.of("").toAbsolutePath());
  }

  /**
   * Copies exactly the inputs the gate reads — the eighteen documents and the two
   * presence-checked scripts — into a scratch root so one field can be mutated without
   * touching the repository. No tree walk, so disposable analyzer output under
   * spike/runs or spike/.cache is never traversed or copied.
   */
  private Path copiedSpikeTree() throws IOException {
    Path root = temp.resolve("root");
    Files.createDirectories(root);
    Path repository = repoRoot();
    java.util.List<String> inputs = new java.util.ArrayList<>(
        EvidenceContractValidator.DOCUMENT_PATHS);
    inputs.addAll(EvidenceContractValidator.SCRIPT_PATHS);
    for (String relative : inputs) {
      Path target = root.resolve(relative);
      Files.createDirectories(target.getParent());
      Files.copy(repository.resolve(relative), target);
    }
    return root;
  }

  private static ObjectNode read(Path root, String relative) throws IOException {
    return (ObjectNode) MAPPER.readTree(root.resolve(relative).toFile());
  }

  private static void write(Path root, String relative, ObjectNode document)
      throws IOException {
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(root.resolve(relative).toFile(),
        document);
  }

  /** Mutates one document through a callback and returns the mutated scratch root. */
  private interface Mutation {
    void apply(ObjectNode document);
  }

  private Path mutatedSpikeTree(String relative, Mutation mutation) throws IOException {
    Path root = copiedSpikeTree();
    ObjectNode document = read(root, relative);
    mutation.apply(document);
    write(root, relative, document);
    return root;
  }

  /** Asserts validation of the mutated tree fails with a message containing the fragment. */
  private static void assertFailsWith(Path root, String fragment) {
    EvidenceContractValidator.ValidationError error = assertThrows(
        EvidenceContractValidator.ValidationError.class,
        () -> EvidenceContractValidator.validate(root));
    assertTrue(error.getMessage().contains(fragment),
        "expected message containing <" + fragment + ">, got <" + error.getMessage() + ">");
  }

  /** The real checked-in tree passes and prints the exact summary line Python printed. */
  @Test
  void realTreeValidatesWithExactSummaryLine() throws IOException {
    assertEquals(
        "validated 10 acceptance criteria, 3 candidates, 10 capability cases,"
            + " 21 expected relationships, and 10 observed results",
        EvidenceContractValidator.validate(repoRoot()));
    assertEquals(0, EvidenceContractValidator.run(new String[] {}, repoRoot()));
  }

  /** Python ignored argv entirely; the port keeps that behavior for parity. */
  @Test
  void runIgnoresExtraArgumentsForPythonParity() throws IOException {
    assertEquals(0, EvidenceContractValidator.run(new String[] {"stray", "args"}, repoRoot()));
  }

  /** A missing document fails loudly, naming the file. */
  @Test
  void missingDocumentFailsNamingTheFile() throws IOException {
    Path root = copiedSpikeTree();
    Files.delete(root.resolve("spike/capability-matrix.json"));

    assertEquals(1, EvidenceContractValidator.run(new String[] {}, root));
    assertFailsWith(root, "spike/capability-matrix.json");
  }

  /** A malformed document fails loudly, naming the file. */
  @Test
  void malformedDocumentFailsNamingTheFile() throws IOException {
    Path root = copiedSpikeTree();
    Files.writeString(root.resolve("spike/acceptance-criteria.json"), "{ not json");

    assertEquals(1, EvidenceContractValidator.run(new String[] {}, root));
    assertFailsWith(root, "spike/acceptance-criteria.json");
  }

  /** A directory where a document belongs fails loudly, naming the path. */
  @Test
  void directoryAtDocumentPathFailsNamingThePath() throws IOException {
    Path root = copiedSpikeTree();
    Files.delete(root.resolve("spike/candidate-matrix.json"));
    Files.createDirectories(root.resolve("spike/candidate-matrix.json"));

    assertFailsWith(root, "spike/candidate-matrix.json");
  }

  /** Python message parity: the matrix must cite ADR-001. */
  @Test
  void matrixAdrMutationKeepsThePythonMessage() throws IOException {
    assertFailsWith(mutatedSpikeTree("spike/capability-matrix.json",
        document -> document.put("adr", "ADR-999")), "matrix must cite ADR-001");
  }

  /**
   * Pins Python numeric equality: the JSON string "21" does not equal the number 21, so a
   * stringified java release must fail the Java-21 requirement.
   */
  @Test
  void stringJavaReleaseFailsNumericEquality() throws IOException {
    assertFailsWith(mutatedSpikeTree("spike/capability-matrix.json",
        document -> ((ObjectNode) document.path("fixture")).put("javaRelease", "21")),
        "fixture must target Java 21");
  }

  /** Python message parity: production and test profiles must differ. */
  @Test
  void equalProfilesAreRejected() throws IOException {
    assertFailsWith(mutatedSpikeTree("spike/capability-matrix.json", document -> {
      ObjectNode fixture = (ObjectNode) document.path("fixture");
      fixture.put("testProfile", fixture.path("productionProfile").asText());
    }), "profiles must differ");
  }

  /**
   * A deleted test profile must fail loudly (Python raised KeyError, exit 1; the port
   * must not silently accept an absent field as "different").
   */
  @Test
  void deletedTestProfileIsRejected() throws IOException {
    assertFailsWith(mutatedSpikeTree("spike/capability-matrix.json",
        document -> ((ObjectNode) document.path("fixture")).remove("testProfile")),
        "profiles must differ");
  }

  /**
   * An extra key in the javac totals block must fail the totals check (Python keyed the
   * recomputed map off the document and failed the equality; the port rejects unknown
   * metric keys explicitly).
   */
  @Test
  void extraJavacTotalsKeyIsRejected() throws IOException {
    assertFailsWith(mutatedSpikeTree(
        "spike/results/javac-symbol-resolver-observation.json",
        document -> ((ObjectNode) document.path("javacTotals")).put("bogusMetric", 0)),
        "javac resolver totals must equal the per-cell sum");
  }

  /** Python message parity: required framework-enrichment evidence is a subset check. */
  @Test
  void missingRequiredEvidenceIsRejected() throws IOException {
    assertFailsWith(mutatedSpikeTree("spike/capability-matrix.json", document -> {
      ArrayNode required = (ArrayNode) document.withArray("requiredEvidence");
      for (int i = required.size() - 1; i >= 0; i--) {
        if ("framework_model_contract".equals(required.get(i).asText())) {
          required.remove(i);
        }
      }
    }), "framework enrichment evidence requirements are missing");
  }

  /**
   * Pins Python truthiness: a false promotion guard is falsy, so the requirement fails even
   * though the field is present.
   */
  @Test
  void falsePromotionGuardIsFalsy() throws IOException {
    assertFailsWith(mutatedSpikeTree("spike/contracts/framework-model.json",
        document -> ((ObjectNode) document.path("certaintyPolicy")).put("promotionGuard",
            false)),
        "framework model promotion guard is missing");
  }

  /** Python message parity: the certainty state set is exact on both sides. */
  @Test
  void changedCertaintyStatesAreRejected() throws IOException {
    assertFailsWith(mutatedSpikeTree("spike/contracts/framework-model.json", document -> {
      ArrayNode states = (ArrayNode) ((ObjectNode) document.path("certaintyPolicy"))
          .withArray("states");
      for (int i = states.size() - 1; i >= 0; i--) {
        if ("unresolved".equals(states.get(i).asText())) {
          states.remove(i);
        }
      }
    }), "framework model certainty states changed");
  }

  /** Python message parity: abstentions must keep behavior "unresolved". */
  @Test
  void nonUnresolvedAbstentionIsRejected() throws IOException {
    assertFailsWith(mutatedSpikeTree("spike/contracts/framework-model.json",
        document -> ((ObjectNode) document.path("frameworks").get(0).path("abstentions")
            .get(0)).put("behavior", "emitted")),
        "abstentions must remain unresolved");
  }

  /** Python message parity: route facts need the full runtime selection field set. */
  @Test
  void routeFactWithoutHttpMethodIsRejected() throws IOException {
    assertFailsWith(mutatedSpikeTree("spike/contracts/expected-call-facts.json", document -> {
      for (JsonNode relationship : document.path("frameworkRelationships")) {
        if ("framework_entrypoint".equals(relationship.path("relationshipType").asText())) {
          ((ObjectNode) relationship.path("selectionCriteria")).remove("httpMethod");
          return;
        }
      }
      throw new IllegalStateException("no route fact found to mutate");
    }), "is missing runtime selection criteria");
  }

  /** Python message parity: a fact's model rule must be known to its framework. */
  @Test
  void unknownModelRuleIsRejected() throws IOException {
    assertFailsWith(mutatedSpikeTree("spike/contracts/expected-call-facts.json", document -> {
      ObjectNode relationship = (ObjectNode) document.path("frameworkRelationships").get(0);
      ((ObjectNode) relationship.path("provenance")).put("modelRule", "bogus-rule");
    }), "unknown model rule bogus-rule");
  }

  /** Python message parity: host paths leak a normalized analyzer result. */
  @Test
  void analyzerHostPathIsRejected() throws IOException {
    assertFailsWith(mutatedSpikeTree(
        "spike/results/sootup-3.0.1-java21-quarkus-observation.json",
        document -> ((ObjectNode) document.path("observations").get(0))
            .set("evidence", MAPPER.createArrayNode().add("/Users/leak/source.java"))),
        "SootUp result contains a host path");
  }

  /** Python message parity: the javac observation rejects Windows-shaped host paths too. */
  @Test
  void javacObservationHostPathIsRejected() throws IOException {
    assertFailsWith(mutatedSpikeTree(
        "spike/results/javac-symbol-resolver-observation.json",
        document -> ((ObjectNode) document.path("cells").get(0).path("javac"))
            .put("leakedPath", "\\\\Users\\\\leak\\\\cell.java")),
        "javac resolver observation contains a host path");
  }

  /** Python message parity: javac facts must name the ServiceLoader owner. */
  @Test
  void javacFactOwnerMismatchIsRejected() throws IOException {
    assertFailsWith(mutatedSpikeTree(
        "spike/results/javac-symbol-resolver-observation.json",
        document -> ((ObjectNode) document.path("cells").get(0).path("javac")
            .withArray("facts").get(0)).put("owner", "com.example.OtherLoader")),
        "owner mismatch");
  }

  /** Python message parity: GO metrics in the totals block must be zero. */
  @Test
  void nonZeroJavacGoMetricIsRejected() throws IOException {
    assertFailsWith(mutatedSpikeTree(
        "spike/results/javac-symbol-resolver-observation.json",
        document -> ((ObjectNode) document.path("javacTotals")).put("wrongDefinite", 1)),
        "javac resolver GO metric wrongDefinite must be zero");
  }

  /**
   * Pins the truthiness helper against Python semantics: false for missing, null, false,
   * zero, empty string, empty array; true for true, non-zero, non-empty string, non-empty
   * array.
   */
  @Test
  void truthinessMirrorsPython() {
    assertFalse(EvidenceSemantics.truthy(MAPPER.missingNode()));
    assertFalse(EvidenceSemantics.truthy(MAPPER.nullNode()));
    assertFalse(EvidenceSemantics.truthy(MAPPER_BOOLEAN(false)));
    assertFalse(EvidenceSemantics.truthy(
        MAPPER.createObjectNode().put("v", 0).path("v")));
    assertFalse(EvidenceSemantics.truthy(
        MAPPER.createObjectNode().put("v", "").path("v")));
    assertFalse(EvidenceSemantics.truthy(MAPPER.createArrayNode()));
    assertTrue(EvidenceSemantics.truthy(MAPPER_BOOLEAN(true)));
    assertTrue(EvidenceSemantics.truthy(
        MAPPER.createObjectNode().put("v", 1).path("v")));
    assertTrue(EvidenceSemantics.truthy(
        MAPPER.createObjectNode().put("v", "x").path("v")));
    assertTrue(EvidenceSemantics.truthy(MAPPER.createArrayNode().add(1)));
  }

  private static JsonNode MAPPER_BOOLEAN(boolean value) {
    return MAPPER.createObjectNode().put("v", value).path("v");
  }

  /**
   * Pins the analyzer host-path predicates: absolute POSIX paths and the macOS private
   * prefixes are rejected; relative paths are not.
   */
  @Test
  void analyzerHostPathWalkRejectsPosixShapes() {
    assertTrue(EvidenceJavacRules.containsHostPath(
        textNode("/absolute/path.java")));
    assertTrue(EvidenceJavacRules.containsHostPath(
        textNode("nested/Users/leak")));
    assertTrue(EvidenceJavacRules.containsHostPath(
        textNode("nested/private/var/folders")));
    assertFalse(EvidenceJavacRules.containsHostPath(
        textNode("relative/path.java")));
    assertTrue(EvidenceJavacRules.containsHostPath(
        MAPPER.createArrayNode().add(MAPPER.createObjectNode().put("note", "/Users/x"))));
  }

  /**
   * Pins the javac host-path predicates: UNC prefixes, drive letters, /tmp, and the Windows
   * Users prefix are all rejected; relative paths and plain names are not.
   */
  @Test
  void javacHostPathWalkRejectsWindowsShapes() {
    assertTrue(EvidenceJavacRules.javacContainsHostPath(
        textNode("\\\\server\\share\\cell.java")));
    assertTrue(EvidenceJavacRules.javacContainsHostPath(
        textNode("C:\\Users\\leak\\cell.java")));
    assertTrue(EvidenceJavacRules.javacContainsHostPath(
        textNode("D:/repos/cell.java")));
    assertTrue(EvidenceJavacRules.javacContainsHostPath(
        textNode("prefix/tmp/scratch")));
    assertTrue(EvidenceJavacRules.javacContainsHostPath(
        textNode("a\\\\Users\\\\leak")));
    assertFalse(EvidenceJavacRules.javacContainsHostPath(
        textNode("relative/cell.java")));
    assertFalse(EvidenceJavacRules.javacContainsHostPath(
        textNode("N24a.java")));
  }

  /** The required javac cell inventory parses to exactly 71 ids with the compound labels. */
  @Test
  void requiredJavacCellsParseToExactSet() {
    Set<String> cells = EvidenceJavacRules.requiredJavacCells();
    assertEquals(71, cells.size());
    assertTrue(cells.contains("N24a"));
    assertTrue(cells.contains("N24b"));
    assertTrue(cells.contains("A16b"));
    assertTrue(cells.contains("I5"));
    assertFalse(cells.contains("A18"));
  }

  /**
   * The recomputed javac totals equal the per-cell sum on the real observation (the sum
   * check the Python gate ran after the zero checks).
   */
  @Test
  void javacTotalsRecomputeToPerCellSumOnRealObservation() throws IOException {
    JsonNode observation = MAPPER.readTree(
        repoRoot().resolve("spike/results/javac-symbol-resolver-observation.json").toFile());
    assertEquals(EvidenceJavacRules.recomputeTotals(observation),
        EvidenceJavacRules.totalsAsLongMap(observation.path("javacTotals")));
  }

  /** The repository root is found by walking up from a nested directory. */
  @Test
  void repositoryRootResolvesFromNestedDirectory() {
    assertEquals(repoRoot(),
        EvidenceContractValidator.resolveRepositoryRoot(repoRoot().resolve("spike/contracts")));
  }

  /** A tree with no pom.xml and spike directory above it cannot resolve a root: loud failure. */
  @Test
  void unresolvableRootFailsLoudly() {
    EvidenceContractValidator.ValidationError error = assertThrows(
        EvidenceContractValidator.ValidationError.class,
        () -> EvidenceContractValidator.resolveRepositoryRoot(temp));
    assertTrue(error.getMessage().contains("repository root"));
  }

  /** Boundary: an empty cells array must fail the cell-set rule. */
  @Test
  void emptyCellsArrayIsRejectedByTheCellSetRule() throws IOException {
    assertFailsWith(mutatedSpikeTree(
        "spike/results/javac-symbol-resolver-observation.json",
        document -> document.withArray("cells").removeAll()),
        "cell set is incomplete");
  }

  /** Boundary: a duplicate cell id keeps the id set at 71 but fails the count rule. */
  @Test
  void duplicateJavacCellFailsTheCountRule() throws IOException {
    assertFailsWith(mutatedSpikeTree(
        "spike/results/javac-symbol-resolver-observation.json",
        document -> {
          ArrayNode cells = document.withArray("cells");
          cells.add(cells.get(0).deepCopy());
        }), "must have 71 cells");
  }

  /** A non-textual element in a set-valued field breaks equality, like Python's set. */
  @Test
  void nonTextualCertaintyStateIsRejected() throws IOException {
    assertFailsWith(mutatedSpikeTree("spike/contracts/framework-model.json", document -> {
      ArrayNode states = (ArrayNode) ((ObjectNode) document.path("certaintyPolicy"))
          .withArray("states");
      states.add(17);
    }), "framework model certainty states changed");
  }

  /** Trailing JSON content after a document is rejected (Python json.load rejects it). */
  @Test
  void trailingJsonContentIsRejected() throws IOException {
    Path root = copiedSpikeTree();
    String content = Files.readString(root.resolve("spike/capability-matrix.json"));
    Files.writeString(root.resolve("spike/capability-matrix.json"),
        content + "\n{\"extra\": 1}\n");

    assertFailsWith(root, "spike/capability-matrix.json");
  }

  /** A non-object document is rejected at the load boundary, naming the path. */
  @Test
  void nonObjectDocumentIsRejectedAtLoad() throws IOException {
    Path root = copiedSpikeTree();
    Files.writeString(root.resolve("spike/acceptance-criteria.json"), "[]");

    assertFailsWith(root, "expected a single JSON object");
  }

  /** A missing semantic links array is rejected (Python read the key directly). */
  @Test
  void missingSemanticLinksAreRejected() throws IOException {
    assertFailsWith(mutatedSpikeTree(
        "spike/results/semantic-call-facts-spike-observation.json",
        document -> document.remove("links")),
        "semantic facts links are missing");
  }

  /** A missing certainty on one analyzer observation is rejected. */
  @Test
  void missingAnalyzerCertaintyIsRejected() throws IOException {
    assertFailsWith(mutatedSpikeTree(
        "spike/results/sootup-3.0.1-java21-quarkus-observation.json",
        document -> ((ObjectNode) document.path("observations").get(0)).remove(
            "certainty")),
        "observation certainty is missing");
  }

  /** A container certainty rejects (Python's set found it unhashable). */
  @Test
  void containerAnalyzerCertaintyIsRejected() throws IOException {
    assertFailsWith(mutatedSpikeTree(
        "spike/results/sootup-3.0.1-java21-quarkus-observation.json",
        document -> ((ObjectNode) document.path("observations").get(0)).set("certainty",
            MAPPER.createArrayNode())),
        "observation certainty must be a scalar");
  }

  /** A scalar non-textual certainty stays accepted, like Python's hashable set member. */
  @Test
  void numericAnalyzerCertaintyIsAccepted() throws IOException {
    Path root = copiedSpikeTree();
    ObjectNode document = read(root,
        "spike/results/sootup-3.0.1-java21-quarkus-observation.json");
    ((ObjectNode) document.path("observations").get(0)).put("certainty", 17);
    write(root, "spike/results/sootup-3.0.1-java21-quarkus-observation.json", document);
    assertEquals(0, EvidenceContractValidator.run(new String[] {}, root));
  }

  /** Distinct non-textual values stay distinct in sets, like Python's set of 17 and 18. */
  @Test
  void distinctNonTextualValuesStayDistinctInSets() {
    assertEquals(2, EvidenceCollections.idSet(java.util.List.of(
        MAPPER.createObjectNode().put("id", 17),
        MAPPER.createObjectNode().put("id", 18)), "id").size());
    assertEquals(1, EvidenceCollections.idSet(java.util.List.of(
        MAPPER.createObjectNode().put("id", 17),
        MAPPER.createObjectNode().put("id", 17)), "id").size());
    // A textual "17" and a numeric 17 stay distinct too (Python: "17" != 17).
    assertEquals(2, EvidenceCollections.idSet(java.util.List.of(
        MAPPER.createObjectNode().put("id", "\u0000" + "17"),
        MAPPER.createObjectNode().put("id", 17)), "id").size());
    // A textual value carrying the sentinel's NUL prefix cannot collide with the
    // sentinel for a numeric value (CodeRabbit's set-parity residual): the escape
    // doubles the prefix, so the two entries stay distinct like Python's set.
    assertEquals(2, EvidenceCollections.idSet(java.util.List.of(
        MAPPER.createObjectNode().put("id", "17"),
        MAPPER.createObjectNode().put("id", 17)), "id").size());
  }

  /** A missing certainty on a framework rule reports the requirement, not an NPE. */
  @Test
  void missingRuleCertaintyIsRejected() throws IOException {
    assertFailsWith(mutatedSpikeTree("spike/contracts/framework-model.json",
        document -> ((ObjectNode) document.path("frameworks").get(0)
            .path("resolutionRules").get(0)).remove("certainty")),
        "invalid framework certainty in");
  }

  /** A missing certainty on a framework relationship reports a requirement, not an NPE. */
  @Test
  void missingRelationshipCertaintyIsRejected() throws IOException {
    assertFailsWith(mutatedSpikeTree("spike/contracts/expected-call-facts.json",
        document -> ((ObjectNode) document.path("frameworkRelationships").get(0))
            .remove("certainty")),
        "missing certainty in a list entry");
  }

  /** Balanced fractional CodeQL counts reject (Python's float sum accepted 51.5 + 12.5). */
  @Test
  void balancedFractionalCodeqlCountsAreRejected() throws IOException {
    assertFailsWith(mutatedSpikeTree(
        "spike/results/codeql-2.27.4-java21-quarkus-observation.json",
        document -> {
          ObjectNode coverage = (ObjectNode) document.path("coverage");
          coverage.put("productionFacts", 51.5);
          coverage.put("testFactsFiltered", 12.5);
        }), "CodeQL fact scope counts must balance");
  }

  /** Oversized integer counts cannot wrap onto a passing sum. */
  @Test
  void oversizedCodeqlCountsCannotWrap() throws IOException {
    assertFailsWith(mutatedSpikeTree(
        "spike/results/codeql-2.27.4-java21-quarkus-observation.json",
        document -> {
          ObjectNode coverage = (ObjectNode) document.path("coverage");
          coverage.put("productionFacts", new java.math.BigInteger(
              "-9007199254740993"));
          coverage.put("testFactsFiltered", new java.math.BigInteger(
              "9007199254741056"));
        }), "CodeQL fact scope counts must balance");
  }

  /** Whole-number offsets: 64.0 maps to 64 like Python's dict key, 64.5 and oversized reject. */
  @Test
  void wholeNumberOffsetsMatchPythonDictKeySemantics() {
    assertEquals(64L, EvidenceSemantics.wholeNumberOrNull(
        MAPPER.createObjectNode().put("v", 64.0).path("v")));
    assertEquals(64L, EvidenceSemantics.wholeNumberOrNull(
        MAPPER.createObjectNode().put("v", 64).path("v")));
    assertEquals(null, EvidenceSemantics.wholeNumberOrNull(
        MAPPER.createObjectNode().put("v", 64.5).path("v")));
    assertEquals(null, EvidenceSemantics.wholeNumberOrNull(
        MAPPER.createObjectNode().put("v", new java.math.BigInteger(
            "18446744073709551680")).path("v")));
  }

  /** A container set member rejects, like Python's unhashable-list TypeError. */
  @Test
  void containerSetMemberIsRejected() throws IOException {
    assertFailsWith(mutatedSpikeTree("spike/contracts/framework-model.json",
        document -> document.withArray("edgeTypes").addObject()
            .set("id", MAPPER.createArrayNode())),
        "set member must be a scalar");
  }

  /** Python numeric set equality: 17 and 17.0 (and true and 1) are the same member. */
  @Test
  void numericSetEqualityCollapsesDuplicates() throws IOException {
    assertEquals(1, EvidenceCollections.idSet(java.util.List.of(
        MAPPER.createObjectNode().put("id", 17),
        MAPPER.createObjectNode().put("id", 17.0)), "id").size());
    assertEquals(1, EvidenceCollections.idSet(java.util.List.of(
        MAPPER.createObjectNode().put("id", true),
        MAPPER.createObjectNode().put("id", 1)), "id").size());
    // Distinct values stay distinct, and a textual "17" stays distinct from numeric 17.
    assertEquals(2, EvidenceCollections.idSet(java.util.List.of(
        MAPPER.createObjectNode().put("id", 17),
        MAPPER.createObjectNode().put("id", 18)), "id").size());
    // End to end: two relationships whose ids are 17 and 17.0 are duplicates.
    assertFailsWith(mutatedSpikeTree("spike/contracts/expected-call-facts.json",
        document -> {
          ArrayNode relationships = document.withArray("relationships");
          ((ObjectNode) relationships.get(0)).put("id", 17);
          ((ObjectNode) relationships.get(1)).put("id", 17.0);
        }), "expected relationship ids must be unique");
  }

  /** A context without a textual id rejects, matching Python's KeyError. */
  @Test
  void contextWithoutIdIsRejected() throws IOException {
    assertFailsWith(mutatedSpikeTree("spike/contracts/analysis-context.json",
        document -> document.withArray("contexts").addObject()),
        "missing id in a list entry");
  }

  /** A missing expected serviceType fails its requirement, never a null dereference. */
  @Test
  void missingExpectedServiceTypeFailsWithDiagnostic() throws IOException {
    assertFailsWith(mutatedSpikeTree(
        "spike/results/javac-symbol-resolver-observation.json", document -> {
          for (JsonNode javacCell : document.path("cells")) {
            for (JsonNode site : javacCell.path("expected")) {
              if ("DEFINITE".equals(site.path("expectation").asText())) {
                ((ObjectNode) site).remove("serviceType");
                return;
              }
            }
          }
          throw new IllegalStateException("no DEFINITE site found to mutate");
        }), "service type mismatch at");
  }

  private static JsonNode textNode(String value) {
    return MAPPER.createObjectNode().put("v", value).path("v");
  }
}
