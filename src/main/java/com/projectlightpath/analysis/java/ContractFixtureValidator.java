package com.projectlightpath.analysis.java;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates the cross-repository contract fixture and staged VPE tree (#45 step 4,
 * ported from spike/scripts/validate_contract_fixtures.py, deleted).
 *
 * <p>Every tracked manifest entry must exist with the recorded file type, permission bits,
 * and git blob SHA-1 (symlinks hash their link target, via {@link GitTreeFiles}), and the
 * dependency-coordinate fixture must be byte-identical in both repositories. The receipt
 * is emitted through {@link PythonJson} with keys in sorted order, reproducing {@code
 * json.dumps(indent=2, sort_keys=True)}.
 *
 * <p>Deliberate divergences from the Python original, all preserving the exit code:
 * failures print one stable diagnostic instead of a traceback; argument errors print a
 * short usage line instead of argparse's block (exit code 2 either way); the
 * permission-bit comparison covers the user/group/other rwx bits reported by the POSIX
 * view (Python's S_IMODE also carries setuid/setgid/sticky, which no fixture uses; the
 * port rejects files it cannot read permissions for).
 */
public final class ContractFixtureValidator {
  private static final ObjectMapper MAPPER = new ObjectMapper()
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

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

  /** The validation receipt written to contract-fixtures.json. */
  public record Result(String vpeSha, int trackedEntries, String fixtureSha256,
      boolean fixtureBytesEqual) {}

  private ContractFixtureValidator() {}

  /**
   * Entry point: validates the staged tree and writes the evidence receipt.
   *
   * @param args {@code --java-root --vpe-root --vpe-sha --vpe-tree-manifest
   *     --evidence-root} (all required)
   * @return 0 on success, 1 on a validation failure, 2 on an argument error
   */
  public static int run(String[] args) {
    Arguments arguments = Arguments.parse(args);
    if (arguments == null) {
      System.err.println("usage: ContractFixtureValidator --java-root DIR --vpe-root DIR"
          + " --vpe-sha SHA --vpe-tree-manifest JSON --evidence-root DIR");
      return 2;
    }
    try {
      Result result = validate(arguments.javaRoot(), arguments.vpeRoot(),
          arguments.vpeSha(), arguments.manifest());
      writeReceipt(result, arguments.evidenceRoot());
      System.out.println("contract fixtures equal: " + result.fixtureSha256());
      return 0;
    } catch (ValidationError | IOException error) {
      System.err.println(error.getMessage());
      return 1;
    }
  }

  /** Main boundary; the only System.exit. */
  public static void main(String[] args) {
    System.exit(run(args));
  }

  /**
   * Validates the staged VPE tree against the manifest and the fixture pair.
   *
   * @param javaRoot the java-analysis-adapter repository root
   * @param vpeRoot the staged VPE tree root
   * @param vpeSha the expected VPE commit
   * @param manifest the VPE tree manifest path
   * @return the validation receipt
   */
  public static Result validate(Path javaRoot, Path vpeRoot, String vpeSha,
      Path manifest) {
    JsonNode document = readManifest(manifest);
    if (!vpeSha.equals(document.path("commit").asText(null))) {
      throw new ValidationError("VPE tree manifest commit does not match --vpe-sha");
    }
    Map<String, JsonNode> expected = VpeTreeRules.manifestEntries(document);
    for (Map.Entry<String, JsonNode> entry : expected.entrySet()) {
      VpeTreeRules.checkEntry(vpeRoot, entry.getKey(), entry.getValue());
    }
    return VpeTreeRules.checkFixture(javaRoot, vpeRoot, vpeSha, expected);
  }

  // -------------------------------------------------------------- helpers

  /** The git blob SHA-1, delegating to the shared tree-file inspection. */
  public static String gitBlob(Path path) {
    return GitTreeFiles.gitBlob(path);
  }

  private static JsonNode readManifest(Path manifest) {
    if (!Files.isRegularFile(manifest)) {
      throw new ValidationError("cannot read " + manifest + ": not a regular file");
    }
    try {
      JsonNode document = MAPPER.readTree(manifest.toFile());
      if (document == null || !document.isObject()) {
        throw new ValidationError("cannot parse " + manifest
            + ": expected a single JSON object");
      }
      return document;
    } catch (IOException error) {
      throw new ValidationError("cannot parse " + manifest + ": " + error.getMessage(),
          error);
    }
  }

  private static void writeReceipt(Result result, Path evidenceRoot)
      throws IOException {
    ObjectNode receipt = MAPPER.createObjectNode();
    // Keys inserted in sorted order, reproducing json.dumps(sort_keys=True).
    receipt.put("fixtureBytesEqual", result.fixtureBytesEqual());
    receipt.put("fixtureSha256", result.fixtureSha256());
    receipt.put("trackedEntries", result.trackedEntries());
    receipt.put("vpeSha", result.vpeSha());
    Files.createDirectories(evidenceRoot);
    Files.writeString(evidenceRoot.resolve("contract-fixtures.json"),
        PythonJson.dumps(receipt) + "\n", StandardCharsets.UTF_8);
  }

  /** Parsed command line. */
  private record Arguments(Path javaRoot, Path vpeRoot, String vpeSha, Path manifest,
      Path evidenceRoot) {

    static Arguments parse(String[] args) {
      Map<String, List<String>> flags = ValidatorCli.parse(args, Set.of());
      if (flags == null) {
        return null;
      }
      List<String> required = new ArrayList<>();
      for (String name : List.of("java-root", "vpe-root", "vpe-sha",
          "vpe-tree-manifest", "evidence-root")) {
        String value = ValidatorCli.single(flags, name);
        if (value == null) {
          return null;
        }
        required.add(value);
      }
      return new Arguments(Path.of(required.get(0)), Path.of(required.get(1)),
          required.get(2), Path.of(required.get(3)), Path.of(required.get(4)));
    }
  }
}
