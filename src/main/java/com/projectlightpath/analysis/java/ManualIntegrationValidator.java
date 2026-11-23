package com.projectlightpath.analysis.java;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Validates the manual VPE AnalysisContext to Java facts handoff (#45 step 3, ported
 * from spike/scripts/validate_manual_integration.py, deleted).
 *
 * <p>Delegates the handoff rules to {@link ManualIntegrationRules}, builds the
 * capability-evidence document (six Spring capability cases), and emits it in Python
 * {@code json.dumps(indent=2)} byte shape via {@link PythonJson}.
 *
 * <p>Deliberate divergences from the Python original, all preserving the exit code:
 *
 * <ul>
 *   <li>a missing or malformed input file fails with a stable {@code manual integration
 *       validation failed: cannot read <path>} diagnostic (Python raised FileNotFoundError
 *       as an uncaught traceback; JSONDecodeError was caught) — exit code 1 either way;
 *   <li>argument errors print a short usage line instead of argparse's usage block — exit
 *       code 2 either way; both {@code --flag value} and {@code --flag=value} forms are
 *       accepted (argparse abbreviation prefixes are not, matching every live caller);
 *   <li>a missing id inside an otherwise-valid fact rejects at the capability-evidence
 *       boundary (Python raised KeyError there) instead of serializing {@code null};
 *   <li>fact caller/callee/id fields must be strings when present, and coverage must be
 *       an object when present — Python crashed on those shapes mid-validation, after or
 *       before different side effects; the port rejects them before writing the output
 *       (same exit code 1, the output file may differ in the crash window);
 *   <li>safe-direction strictness the Python original accepted by accident: non-string
 *       truthy context ids and classpath arrays, boolean graph counts, links pairing
 *       two id-less facts, and container-valued coverage counts all reject here (null
 *       counts render as Python's None; booleans as True/False).
 * </ul>
 */
public final class ManualIntegrationValidator {
  /** The Python original's default: the pinned FSI baseline commit. */
  public static final String DEFAULT_SOURCE_REVISION =
      "01a18a1b40e151ffcc1d7bef86bf335f87999750";

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .enable(com.fasterxml.jackson.databind.DeserializationFeature
          .FAIL_ON_TRAILING_TOKENS);

  /** Thrown for any failed rule; the run boundary renders it as exit code 1. */
  public static final class ValidationError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ValidationError(String message) {
      super(message);
    }

    ValidationError(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private ManualIntegrationValidator() {}

  /**
   * Entry point: validates the handoff and writes the capability-evidence document.
   *
   * @param args {@code --context} and {@code --facts} (required), {@code
   *     --source-revision} (defaults to {@value #DEFAULT_SOURCE_REVISION}), {@code
   *     --output} (defaults to spike/runs/manual-capability-evidence.json under the
   *     working directory)
   * @param cwd working directory for the default output path
   * @return 0 on success, 1 on a validation failure, 2 on an argument error
   */
  public static int run(String[] args, Path cwd) {
    Arguments arguments = Arguments.parse(args);
    if (arguments == null) {
      System.err.println("usage: ManualIntegrationValidator --context CONTEXT_JSON"
          + " --facts FACTS_JSON [--source-revision SHA] [--output EVIDENCE_JSON]");
      return 2;
    }
    try {
      ObjectNode context = readObject(arguments.context());
      ObjectNode facts = readObject(arguments.facts());
      ObjectNode evidence = validate(context, facts, arguments.sourceRevision());
      // The summary is computed before the file is written so a count-shape failure
      // (a container-valued coverage count) cannot leave a complete evidence document
      // behind on a failed run.
      String summary = summaryLine(evidence);
      Path output = arguments.output();
      Path resolved = output.isAbsolute() ? output : cwd.resolve(output);
      if (resolved.getParent() != null) {
        Files.createDirectories(resolved.getParent());
      }
      Files.writeString(resolved, PythonJson.dumps(evidence) + "\n",
          StandardCharsets.UTF_8);
      System.out.println(summary);
      System.out.println(output);
      return 0;
    } catch (ValidationError | IOException error) {
      System.err.println("manual integration validation failed: " + error.getMessage());
      return 1;
    }
  }

  /** Main boundary; the only System.exit. */
  public static void main(String[] args) {
    System.exit(run(args, Path.of("").toAbsolutePath()));
  }

  /**
   * Validates the pair and builds the capability-evidence document.
   *
   * @param context the direct AnalysisContext document
   * @param facts the SemanticCallFacts document
   * @param expectedRevision the pinned source revision
   * @return the evidence document in the Python original's field order
   */
  public static ObjectNode validate(ObjectNode context, ObjectNode facts,
      String expectedRevision) {
    ManualIntegrationRules.Handoff handoff =
        new ManualIntegrationRules(context, facts, expectedRevision).checkAll();
    return buildEvidence(context, facts, handoff, expectedRevision);
  }

  /** Renders a JSON tree exactly as Python {@code json.dumps(node, indent=2)}. */
  static String pythonJson(JsonNode node) {
    return PythonJson.dumps(node);
  }

  // --------------------------------------------------------------- evidence

  private static ObjectNode buildEvidence(ObjectNode context, ObjectNode facts,
      ManualIntegrationRules.Handoff handoff, String expectedRevision) {
    JsonNode metadata = handoff.metadata();
    List<JsonNode> providerFacts = listElements(facts.path("providerFacts"));
    List<JsonNode> frameworkFacts = listElements(facts.path("frameworkFacts"));
    ObjectNode evidence = MAPPER.createObjectNode();
    evidence.put("schemaVersion", "0.1.0");
    evidence.put("artifact", "ManualIntegrationCapabilityEvidence");
    evidence.put("contextId", handoff.contextId());
    evidence.put("framework", text(facts, "framework"));
    evidence.put("sourceRevision", expectedRevision);
    ObjectNode provider = evidence.putObject("provider");
    provider.put("name", text(metadata, "providerName"));
    provider.put("version", text(metadata, "providerVersion"));
    // Python used facts.get("coverage", {}) — an absent field defaults to an empty
    // object, never an empty string. A present non-object crashes Python's summary
    // print after writing the file; the port rejects before writing (same exit code).
    JsonNode coverageField = facts.path("coverage");
    require(coverageField.isMissingNode() || coverageField.isObject(),
        "facts coverage must be an object when present");
    evidence.set("coverage", objectOrDefault(facts, "coverage"));
    ArrayNode capabilityEvidence = evidence.putArray("capabilityEvidence");
    capabilityEvidence.add(capability("spring-mvc-entrypoint", handoff.springRoute(),
        "Spring MVC route annotations produced the exact framework-owned"
            + " POST /api/transfers entrypoint fact."));
    capabilityEvidence.add(capability("transfer-controller-to-service",
        matching(frameworkFacts, fact -> "dependency_injection"
            .equals(text(fact, "relationshipType"))
            && contains(text(fact, "caller"), "TransferController")
            && contains(text(fact, "callee"), "TransactionService")),
        "Spring constructor injection produced a bounded candidate fact."));
    capabilityEvidence.add(capability("transaction-service-to-audit",
        matching(providerFacts, fact -> "invokes"
            .equals(text(fact, "relationshipType"))
            && contains(text(fact, "caller"), "TransactionService")
            && contains(text(fact, "callee"), "AuditService#log")),
        "SootUp provider graph preserved the application service-to-audit call."));
    capabilityEvidence.add(capability("transaction-service-to-jpa",
        matching(frameworkFacts, fact -> "generated_implementation"
            .equals(text(fact, "relationshipType"))
            && contains(text(fact, "callee"), "Repository")
            && text(fact, "id") != null
            && text(fact, "id").startsWith("spring-data-generated-")),
        "Spring Data repository contract produced a generated implementation fact."));
    capabilityEvidence.add(capability("spring-bean-callback",
        matching(frameworkFacts, fact -> "lifecycle_callback"
            .equals(text(fact, "relationshipType"))
            && contains(text(fact, "callee"), "SecurityConfig#securityFilterChain")),
        "Spring @Bean configuration produced a lifecycle callback fact."));
    capabilityEvidence.add(capability("startup-callback",
        matching(frameworkFacts, fact -> "lifecycle_callback"
            .equals(text(fact, "relationshipType"))
            && contains(text(fact, "callee"), "DataSeeder#run")),
        "Spring Boot CommandLineRunner produced a startup callback fact."));
    for (JsonNode item : capabilityEvidence) {
      require(!item.path("factIds").isEmpty(),
          "capability evidence is missing: " + text(item, "caseId"));
    }
    // Python used handoff.get("limitations", []) — an absent field defaults to an empty
    // array, never an empty string.
    JsonNode limitations = metadata.path("handoff").path("limitations");
    evidence.set("limitations", limitations.isMissingNode()
        ? MAPPER.createArrayNode() : limitations.deepCopy());
    return evidence;
  }

  private static ObjectNode capability(String caseId, List<JsonNode> matched,
      String basis) {
    ObjectNode item = MAPPER.createObjectNode();
    item.put("caseId", caseId);
    item.put("status", "observed");
    ArrayNode factIds = item.putArray("factIds");
    for (JsonNode fact : matched) {
      // Python read fact["id"] directly; a missing id is a validation failure, not a
      // null entry in the evidence document.
      require(text(fact, "id") != null,
          "capability evidence is missing: " + caseId);
      factIds.add(text(fact, "id"));
    }
    item.put("basis", basis);
    return item;
  }

  /** The field's value, or an empty object when absent (Python .get default). */
  private static JsonNode objectOrDefault(ObjectNode parent, String field) {
    JsonNode value = parent.path(field);
    return value.isMissingNode() ? MAPPER.createObjectNode() : value.deepCopy();
  }

  private static String summaryLine(ObjectNode evidence) {
    JsonNode coverage = evidence.path("coverage");
    return "validated manual integration: " + text(evidence, "contextId") + ", "
        + countText(coverage.path("providerFactCount")) + " provider facts, "
        + countText(coverage.path("frameworkFactCount")) + " framework facts, "
        + evidence.path("capabilityEvidence").size() + " capability cases";
  }

  /** Python printed the coverage counts via f-string: raw text, 0 when missing. */
  private static String countText(JsonNode node) {
    if (node.isNumber() || node.isTextual()) {
      return node.asText();
    }
    if (node.isBoolean()) {
      return node.asBoolean() ? "True" : "False";
    }
    if (node.isNull()) {
      return "None";
    }
    if (node.isMissingNode()) {
      return "0";
    }
    // A container count cannot be rendered like Python's f-string; reject instead of
    // printing a Java-shaped summary line.
    throw new ValidationError("facts coverage counts must be scalars");
  }

  // -------------------------------------------------------------- helpers

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new ValidationError(message);
    }
  }

  private static String text(JsonNode parent, String field) {
    if (parent == null) {
      return null;
    }
    JsonNode node = parent.path(field);
    return node.isTextual() ? node.asText() : null;
  }

  private static List<JsonNode> listElements(JsonNode array) {
    List<JsonNode> values = new ArrayList<>();
    if (array != null && array.isArray()) {
      array.forEach(values::add);
    }
    return values;
  }

  private static boolean contains(String value, String fragment) {
    return value != null && value.contains(fragment);
  }

  private static List<JsonNode> matching(List<JsonNode> facts,
      Predicate<JsonNode> predicate) {
    List<JsonNode> matched = new ArrayList<>();
    for (JsonNode fact : facts) {
      if (predicate.test(fact)) {
        matched.add(fact);
      }
    }
    return matched;
  }

  private static ObjectNode readObject(Path file) {
    if (!Files.isRegularFile(file)) {
      throw new ValidationError("cannot read " + file + ": not a regular file");
    }
    try {
      JsonNode node = MAPPER.readTree(file.toFile());
      if (node == null || !node.isObject()) {
        throw new ValidationError("cannot parse " + file + ": expected a JSON object");
      }
      return (ObjectNode) node;
    } catch (IOException error) {
      throw new ValidationError("cannot parse " + file + ": " + error.getMessage(),
          error);
    }
  }

  /** Parsed command line. */
  private record Arguments(Path context, Path facts, String sourceRevision, Path output) {

    static Arguments parse(String[] args) {
      Path context = null;
      Path facts = null;
      String sourceRevision = DEFAULT_SOURCE_REVISION;
      String output = "spike/runs/manual-capability-evidence.json";
      for (int i = 0; i < args.length; i++) {
        String arg = args[i];
        String inlineValue = null;
        String name = arg;
        if (arg.startsWith("--") && arg.contains("=")) {
          name = arg.substring(0, arg.indexOf('='));
          inlineValue = arg.substring(arg.indexOf('=') + 1);
        }
        // A trailing option without an inline value must fail here, before the
        // switch reads args[i + 1]: the parse runs outside run()'s try block.
        if (inlineValue == null && i + 1 >= args.length) {
          return null;
        }
        switch (name) {
          case "--context" -> context = Path.of(value(args, i, inlineValue));
          case "--facts" -> facts = Path.of(value(args, i, inlineValue));
          case "--source-revision" -> sourceRevision = value(args, i, inlineValue);
          case "--output" -> output = value(args, i, inlineValue);
          default -> {
            return null;
          }
        }
        if (inlineValue == null) {
          i++;
        }
      }
      if (context == null || facts == null) {
        return null;
      }
      return new Arguments(context, facts, sourceRevision, Path.of(output));
    }

    private static String value(String[] args, int index, String inlineValue) {
      return inlineValue != null ? inlineValue : args[index + 1];
    }
  }
}
