package com.projectlightpath.analysis.java;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * VPE tree rules for the contract-fixture validator (#45 step 4): manifest reading, entry
 * inspection (existence, file type, permission bits, git blob), manifest ownership of the
 * fixture, and the cross-repository fixture byte equality. Ported from the corresponding
 * blocks of spike/scripts/validate_contract_fixtures.py.
 */
final class VpeTreeRules {
  /** The java-side fixture path, repository-relative. */
  static final String JAVA_FIXTURE =
      "spike/contracts/dependency-coordinate-manifest.json";

  /** The VPE-side fixture path, tree-relative. */
  static final String VPE_FIXTURE =
      "analysiscontext/testdata/dependency-coordinate-manifest.json";

  private VpeTreeRules() {}

  /** The manifest's tracked entries keyed by path; rejects empty or unkeyed entries. */
  static Map<String, JsonNode> manifestEntries(JsonNode document) {
    JsonNode entries = document.path("entries");
    if (!entries.isMissingNode() && !entries.isArray()) {
      throw new ContractFixtureValidator.ValidationError(
          "VPE tree manifest entries must be an array");
    }
    Map<String, JsonNode> expected = new LinkedHashMap<>();
    for (JsonNode entry : entries) {
      if (!entry.isObject()) {
        throw new ContractFixtureValidator.ValidationError(
            "VPE tree manifest entries must be objects");
      }
      expected.put(entry.path("path").asText(null), entry);
    }
    if (expected.isEmpty()) {
      throw new ContractFixtureValidator.ValidationError(
          "VPE tree manifest contains no tracked entries");
    }
    if (expected.containsKey(null)) {
      throw new ContractFixtureValidator.ValidationError(
          "VPE tree manifest entry is missing its path");
    }
    return expected;
  }

  /** One manifest entry: existence, file type, permission bits, git blob. */
  static void checkEntry(Path vpeRoot, String path, JsonNode entry) {
    Path candidate = vpeRoot.resolve(path);
    if (!Files.exists(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      throw new ContractFixtureValidator.ValidationError("VPE tree file is missing: "
          + path);
    }
    long expectedMode = parseMode(entry.path("mode"), path);
    if (GitTreeFiles.fileType(candidate) != (expectedMode & 0170000L)) {
      throw new ContractFixtureValidator.ValidationError(
          "VPE tree file type mismatch: " + path);
    }
    if (expectedMode != 0120000L) {
      if (GitTreeFiles.permissionBits(candidate, path) != (expectedMode & 07777L)) {
        throw new ContractFixtureValidator.ValidationError("VPE tree mode mismatch: "
            + path);
      }
    }
    if (!GitTreeFiles.gitBlob(candidate).equals(entry.path("blob").asText(null))) {
      throw new ContractFixtureValidator.ValidationError("VPE tree blob mismatch: "
          + path);
    }
  }

  /** The fixture checks: manifest ownership, blob match, and byte equality. */
  static ContractFixtureValidator.Result checkFixture(Path javaRoot, Path vpeRoot,
      String vpeSha, Map<String, JsonNode> expected) {
    JsonNode fixtureEntry = expected.get(VPE_FIXTURE);
    if (fixtureEntry == null) {
      throw new ContractFixtureValidator.ValidationError(
          "VPE fixture is not listed in the tree manifest: " + VPE_FIXTURE);
    }
    Path vpeFixture = vpeRoot.resolve(VPE_FIXTURE);
    if (!GitTreeFiles.gitBlob(vpeFixture)
        .equals(fixtureEntry.path("blob").asText(null))) {
      throw new ContractFixtureValidator.ValidationError(
          "VPE fixture blob does not match the tree manifest: " + VPE_FIXTURE);
    }
    byte[] javaBytes = GitTreeFiles.readBytes(javaRoot.resolve(JAVA_FIXTURE));
    byte[] vpeBytes = GitTreeFiles.readBytes(vpeFixture);
    if (!Arrays.equals(javaBytes, vpeBytes)) {
      throw new ContractFixtureValidator.ValidationError(
          "Java and VPE dependency-coordinate fixtures differ");
    }
    return new ContractFixtureValidator.Result(vpeSha, expected.size(),
        GitTreeFiles.sha256(javaBytes), true);
  }

  private static long parseMode(JsonNode modeNode, String path) {
    if (modeNode == null || !modeNode.isTextual()) {
      throw new ContractFixtureValidator.ValidationError(
          "VPE tree manifest entry is missing its textual mode: " + path);
    }
    // Python int(s, 8): surrounding whitespace and underscores between digits allowed.
    String mode = modeNode.asText().strip();
    if (!mode.matches("[+-]?[0-7]+(_[0-7]+)*")) {
      throw new ContractFixtureValidator.ValidationError(
          "VPE tree manifest entry has a non-octal mode: " + path + ": " + mode);
    }
    return Long.parseLong(mode.replace("_", ""), 8);
  }
}
