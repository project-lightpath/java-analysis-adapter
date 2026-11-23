package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression cells for the contract-fixture validator (#45 step 4, ported from
 * spike/scripts/validate_contract_fixtures.py and its cells in test_validators.py).
 *
 * <p>Pins the lstat semantics (symlink type before permissions, NOFOLLOW on every read),
 * the git blob SHA-1 over content or link target, manifest ownership of the VPE fixture,
 * and the cross-repository fixture equality, plus every malformed boundary shape.
 */
class ContractFixtureValidatorTest {
  @TempDir Path temp;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String JAVA_FIXTURE =
      "spike/contracts/dependency-coordinate-manifest.json";
  private static final String VPE_FIXTURE =
      "analysiscontext/testdata/dependency-coordinate-manifest.json";

  /** Builds the java root, the VPE root, and a manifest entry per requested path. */
  private Path[] fixtureRoots(Map<String, String> extraFiles) throws IOException {
    Path root = temp.resolve("roots");
    Path javaRoot = root.resolve("java");
    Path vpeRoot = root.resolve("vpe");
    Files.createDirectories(javaRoot.resolve(JAVA_FIXTURE).getParent());
    Files.createDirectories(vpeRoot.resolve(VPE_FIXTURE).getParent());
    Files.writeString(javaRoot.resolve(JAVA_FIXTURE), "{\"schemaVersion\":\"0.1.0\"}\n");
    Files.writeString(vpeRoot.resolve(VPE_FIXTURE), "{\"schemaVersion\":\"0.1.0\"}\n");
    for (Map.Entry<String, String> entry : extraFiles.entrySet()) {
      Path target = vpeRoot.resolve(entry.getKey());
      Files.createDirectories(target.getParent());
      Files.writeString(target, entry.getValue());
    }
    return new Path[] {javaRoot, vpeRoot};
  }

  /** Writes a manifest covering the fixture pair plus the extra files. */
  private Path manifestFor(Path javaRoot, Path vpeRoot, String... extraPaths)
      throws IOException {
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("commit", "vpe-commit");
    java.util.List<Map<String, String>> entries = new java.util.ArrayList<>();
    entries.add(entryFor(vpeRoot, VPE_FIXTURE));
    for (String path : extraPaths) {
      entries.add(entryFor(vpeRoot, path));
    }
    manifest.put("entries", entries);
    Path file = temp.resolve("manifest.json");
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), manifest);
    return file;
  }

  private static Map<String, String> entryFor(Path vpeRoot, String path) throws IOException {
    Path candidate = vpeRoot.resolve(path);
    String mode = Files.isSymbolicLink(candidate) ? "120000"
        : (Files.getPosixFilePermissions(candidate)
            .contains(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE)
            ? "100755" : "100644");
    Map<String, String> entry = new LinkedHashMap<>();
    entry.put("path", path);
    entry.put("mode", mode);
    entry.put("blob", ContractFixtureValidator.gitBlob(candidate));
    return entry;
  }

  private int run(Path javaRoot, Path vpeRoot, Path manifest, Path evidenceRoot) {
    return ContractFixtureValidator.run(new String[] {"--java-root",
        javaRoot.toString(), "--vpe-root", vpeRoot.toString(), "--vpe-sha", "vpe-commit",
        "--vpe-tree-manifest", manifest.toString(), "--evidence-root",
        evidenceRoot.toString()});
  }

  /** The valid pair validates and writes the evidence receipt. */
  @Test
  void validPairValidatesAndWritesEvidence() throws IOException {
    Path[] roots = fixtureRoots(Map.of("tracked.txt", "tracked"));
    Path manifest = manifestFor(roots[0], roots[1], "tracked.txt");
    Path evidenceRoot = temp.resolve("evidence");
    assertEquals(0, run(roots[0], roots[1], manifest, evidenceRoot));
    String evidence = Files.readString(evidenceRoot.resolve("contract-fixtures.json"));
    assertTrue(evidence.contains("\"fixtureBytesEqual\" : true")
        || evidence.contains("\"fixtureBytesEqual\": true"), evidence);
    assertTrue(evidence.contains("vpe-commit"), evidence);
    assertTrue(evidence.contains("\"trackedEntries\" : 2")
        || evidence.contains("\"trackedEntries\": 2"), evidence);
  }

  /** A tracked symlink validates by type, before permissions (test_validators cell). */
  @Test
  void trackedSymlinkTypeIsValidatedBeforePermissions() throws IOException {
    Path[] roots = fixtureRoots(Map.of());
    Path vpeRoot = roots[1];
    Files.writeString(vpeRoot.resolve("target.txt"), "target");
    Files.createSymbolicLink(vpeRoot.resolve("link.txt"), Path.of("target.txt"));
    Path manifest = manifestFor(roots[0], vpeRoot, "target.txt", "link.txt");
    assertEquals(0, run(roots[0], vpeRoot, manifest, temp.resolve("evidence")));
  }

  /** A symlink pointing at a regular file must NOT satisfy a 100644 entry. */
  @Test
  void symlinkDoesNotSatisfyRegularFileEntry() throws IOException {
    Path[] roots = fixtureRoots(Map.of());
    Path vpeRoot = roots[1];
    Files.writeString(vpeRoot.resolve("target.txt"), "{\"schemaVersion\":\"0.1.0\"}\n");
    Path link = vpeRoot.resolve("fakeregular.json");
    Files.createSymbolicLink(link, Path.of("target.txt"));
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("commit", "vpe-commit");
    Map<String, String> entry = new LinkedHashMap<>();
    entry.put("path", "fakeregular.json");
    entry.put("mode", "100644");
    entry.put("blob", ContractFixtureValidator.gitBlob(link));
    manifest.put("entries", java.util.List.of(entryFor(vpeRoot, VPE_FIXTURE), entry));
    Path file = temp.resolve("manifest.json");
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), manifest);
    ContractFixtureValidator.ValidationError error = assertThrows(
        ContractFixtureValidator.ValidationError.class,
        () -> ContractFixtureValidator.validate(roots[0], vpeRoot, "vpe-commit", file));
    assertTrue(error.getMessage().contains("file type mismatch"), error.getMessage());
  }

  /** An unmanifested VPE fixture is rejected (test_validators cell). */
  @Test
  void unmanifestedVpeFixtureIsRejected() throws IOException {
    Path[] roots = fixtureRoots(Map.of("tracked.txt", "tracked"));
    // A manifest that tracks only the extra file, not the VPE fixture.
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("commit", "vpe-commit");
    manifest.put("entries", java.util.List.of(entryFor(roots[1], "tracked.txt")));
    Path file = temp.resolve("manifest.json");
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), manifest);
    ContractFixtureValidator.ValidationError error = assertThrows(
        ContractFixtureValidator.ValidationError.class,
        () -> ContractFixtureValidator.validate(roots[0], roots[1], "vpe-commit",
            file));
    assertTrue(error.getMessage().contains("not listed"), error.getMessage());
  }

  /** A manifest commit mismatch rejects. */
  @Test
  void commitMismatchIsRejected() throws IOException {
    Path[] roots = fixtureRoots(Map.of());
    Path manifest = manifestFor(roots[0], roots[1]);
    ContractFixtureValidator.ValidationError error = assertThrows(
        ContractFixtureValidator.ValidationError.class,
        () -> ContractFixtureValidator.validate(roots[0], roots[1], "other", manifest));
    assertTrue(error.getMessage().contains("does not match"), error.getMessage());
  }

  /** An empty manifest rejects. */
  @Test
  void emptyManifestIsRejected() throws IOException {
    Path[] roots = fixtureRoots(Map.of());
    Path file = temp.resolve("manifest.json");
    Files.writeString(file, "{\"commit\": \"vpe-commit\", \"entries\": []}");
    ContractFixtureValidator.ValidationError error = assertThrows(
        ContractFixtureValidator.ValidationError.class,
        () -> ContractFixtureValidator.validate(roots[0], roots[1], "vpe-commit", file));
    assertTrue(error.getMessage().contains("no tracked entries"), error.getMessage());
  }

  /** A missing VPE tree file rejects, naming the path. */
  @Test
  void missingVpeTreeFileIsRejected() throws IOException {
    Path[] roots = fixtureRoots(Map.of("gone.txt", "x"));
    Path manifest = manifestFor(roots[0], roots[1], "gone.txt");
    Files.delete(roots[1].resolve("gone.txt"));
    ContractFixtureValidator.ValidationError error = assertThrows(
        ContractFixtureValidator.ValidationError.class,
        () -> ContractFixtureValidator.validate(roots[0], roots[1], "vpe-commit",
            manifest));
    assertTrue(error.getMessage().contains("VPE tree file is missing: gone.txt"),
        error.getMessage());
  }

  /** A mode mismatch (permission bits) rejects. */
  @Test
  void modeMismatchIsRejected() throws IOException {
    Path[] roots = fixtureRoots(Map.of("script.sh", "x"));
    Path script = roots[1].resolve("script.sh");
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr--r--"));
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("commit", "vpe-commit");
    Map<String, String> entry = entryFor(roots[1], VPE_FIXTURE);
    Map<String, String> scriptEntry = new LinkedHashMap<>();
    scriptEntry.put("path", "script.sh");
    scriptEntry.put("mode", "100644");
    scriptEntry.put("blob", ContractFixtureValidator.gitBlob(script));
    manifest.put("entries", java.util.List.of(entry, scriptEntry));
    Path file = temp.resolve("manifest.json");
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), manifest);
    ContractFixtureValidator.ValidationError error = assertThrows(
        ContractFixtureValidator.ValidationError.class,
        () -> ContractFixtureValidator.validate(roots[0], roots[1], "vpe-commit", file));
    assertTrue(error.getMessage().contains("mode mismatch"), error.getMessage());
  }

  /** A blob mismatch rejects. */
  @Test
  void blobMismatchIsRejected() throws IOException {
    Path[] roots = fixtureRoots(Map.of("drifted.txt", "original"));
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("commit", "vpe-commit");
    Map<String, String> drifted = new LinkedHashMap<>();
    drifted.put("path", "drifted.txt");
    drifted.put("mode", "100644");
    drifted.put("blob", "0".repeat(40));
    manifest.put("entries",
        java.util.List.of(entryFor(roots[1], VPE_FIXTURE), drifted));
    Path file = temp.resolve("manifest.json");
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), manifest);
    ContractFixtureValidator.ValidationError error = assertThrows(
        ContractFixtureValidator.ValidationError.class,
        () -> ContractFixtureValidator.validate(roots[0], roots[1], "vpe-commit", file));
    assertTrue(error.getMessage().contains("blob mismatch"), error.getMessage());
  }

  /** Differing Java and VPE fixture bytes reject. */
  @Test
  void differingFixtureBytesAreRejected() throws IOException {
    Path[] roots = fixtureRoots(Map.of());
    Files.writeString(roots[0].resolve(JAVA_FIXTURE), "{\"schemaVersion\":\"0.2.0\"}\n");
    Path manifest = manifestFor(roots[0], roots[1]);
    ContractFixtureValidator.ValidationError error = assertThrows(
        ContractFixtureValidator.ValidationError.class,
        () -> ContractFixtureValidator.validate(roots[0], roots[1], "vpe-commit",
            manifest));
    assertTrue(error.getMessage().contains("fixtures differ"), error.getMessage());
  }

  /** gitBlob hashes the blob header plus content; symlinks hash the link target. */
  @Test
  void gitBlobHashesContentAndLinkTargets() throws IOException {
    Path file = temp.resolve("file.txt");
    Files.writeString(file, "hello");
    assertEquals("b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0",
        ContractFixtureValidator.gitBlob(file));
    Path link = temp.resolve("link.txt");
    Files.createSymbolicLink(link, Path.of("file.txt"));
    assertEquals("4c330738cc959751fb6760a91a50d9e58cfe5cb9",
        ContractFixtureValidator.gitBlob(link));
  }

  /** A non-textual manifest commit rejects (Python compares typed values). */
  @Test
  void numericManifestCommitIsRejected() throws IOException {
    Path[] roots = fixtureRoots(Map.of());
    Path file = temp.resolve("manifest.json");
    Files.writeString(file, "{\"commit\": 123, \"entries\": []}");
    ContractFixtureValidator.ValidationError error = assertThrows(
        ContractFixtureValidator.ValidationError.class,
        () -> ContractFixtureValidator.validate(roots[0], roots[1], "123", file));
    assertTrue(error.getMessage().contains("does not match"), error.getMessage());
  }

  /** A JSON-object entries field rejects (Python's key iteration crashed). */
  @Test
  void objectEntriesAreRejected() throws IOException {
    Path[] roots = fixtureRoots(Map.of());
    Path file = temp.resolve("manifest.json");
    Files.writeString(file, "{\"commit\": \"vpe-commit\", \"entries\": "
        + "{\"a\": {\"path\": \"x\", \"mode\": \"100644\", \"blob\": \"0\"}}}");
    ContractFixtureValidator.ValidationError error = assertThrows(
        ContractFixtureValidator.ValidationError.class,
        () -> ContractFixtureValidator.validate(roots[0], roots[1], "vpe-commit",
            file));
    assertTrue(error.getMessage().contains("must be an array"), error.getMessage());
  }

  /** A numeric mode rejects; Python's int(mode, 8) took strings only. */
  @Test
  void numericModeIsRejected() throws IOException {
    Path[] roots = fixtureRoots(Map.of("drifted.txt", "x"));
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("commit", "vpe-commit");
    Map<String, Object> drifted = new LinkedHashMap<>();
    drifted.put("path", "drifted.txt");
    drifted.put("mode", 100644);
    drifted.put("blob", ContractFixtureValidator.gitBlob(roots[1].resolve("drifted.txt")));
    manifest.put("entries", java.util.List.of(
        entryFor(roots[1], VPE_FIXTURE), drifted));
    Path file = temp.resolve("manifest.json");
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), manifest);
    ContractFixtureValidator.ValidationError error = assertThrows(
        ContractFixtureValidator.ValidationError.class,
        () -> ContractFixtureValidator.validate(roots[0], roots[1], "vpe-commit",
            file));
    assertTrue(error.getMessage().contains("textual mode"), error.getMessage());
  }

  /** Python int(s, 8) accepted underscores between octal digits; so does the port. */
  @Test
  void underscoredOctalModeIsAccepted() throws IOException {
    Path[] roots = fixtureRoots(Map.of("tracked.txt", "tracked"));
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("commit", "vpe-commit");
    Map<String, String> underscored = new LinkedHashMap<>();
    underscored.put("path", "tracked.txt");
    underscored.put("mode", "100_644");
    underscored.put("blob", ContractFixtureValidator.gitBlob(roots[1].resolve(
        "tracked.txt")));
    manifest.put("entries", java.util.List.of(
        entryFor(roots[1], VPE_FIXTURE), underscored));
    Path file = temp.resolve("manifest.json");
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), manifest);
    assertEquals(0, run(roots[0], roots[1], file, temp.resolve("evidence")));
  }

  /** An underscore after the 0o prefix is valid, like Python int(s, 8). */
  @Test
  void underscoreAfterOctalPrefixIsAccepted() throws IOException {
    Path[] roots = fixtureRoots(Map.of("tracked.txt", "tracked"));
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("commit", "vpe-commit");
    Map<String, String> prefixed = new LinkedHashMap<>();
    prefixed.put("path", "tracked.txt");
    prefixed.put("mode", "0o_100644");
    prefixed.put("blob", ContractFixtureValidator.gitBlob(roots[1].resolve(
        "tracked.txt")));
    manifest.put("entries", java.util.List.of(
        entryFor(roots[1], VPE_FIXTURE), prefixed));
    Path file = temp.resolve("manifest.json");
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), manifest);
    assertEquals(0, run(roots[0], roots[1], file, temp.resolve("evidence")));
  }

  /** A special permission bit (setgid) on the staged file rejects, like S_IMODE. */
  @Test
  void setgidBitOnStagedFileIsRejected() throws IOException, InterruptedException {
    Path[] roots = fixtureRoots(Map.of("special.txt", "x"));
    Path special = roots[1].resolve("special.txt");
    new ProcessBuilder("chmod", "2644", special.toString()).start().waitFor();
    Path manifest = manifestFor(roots[0], roots[1], "special.txt");
    ContractFixtureValidator.ValidationError error = assertThrows(
        ContractFixtureValidator.ValidationError.class,
        () -> ContractFixtureValidator.validate(roots[0], roots[1], "vpe-commit",
            manifest));
    assertTrue(error.getMessage().contains("mode mismatch"), error.getMessage());
  }

  /** Boundary: a malformed manifest rejects loudly. */
  @Test
  void malformedManifestRejects() throws IOException {
    Path[] roots = fixtureRoots(Map.of());
    Path file = temp.resolve("manifest.json");
    Files.writeString(file, "{ not json");
    assertEquals(1, run(roots[0], roots[1], file, temp.resolve("evidence")));
  }
}
