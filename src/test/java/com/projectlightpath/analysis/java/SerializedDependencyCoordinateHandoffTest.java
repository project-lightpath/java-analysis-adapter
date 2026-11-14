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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Business-level contract tests for the serialized producer-to-consumer handoff.
 *
 * <p>These tests intentionally cross the JSON loader, real adapter, SootUp
 * provider, and facts writer. They do not construct adapter implementation
 * objects or inspect implementation-only state.
 */
class SerializedDependencyCoordinateHandoffTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String OWNER =
      "vulnerability-prioritization-engine/analysiscontext.Coordinator.Prepare";
  private static final String ENTRY_FINGERPRINT =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final String MANIFEST_FINGERPRINT =
      "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
  private static final String INVOCATION_FINGERPRINT =
      "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
  private static final String POM_FINGERPRINT =
      "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";

  @TempDir Path temp;

  /**
   * Objective mapping: FedRAMP/NIST 800-53 AU-3 and AU-10 (complete,
   * attributable evidence); SOC 2 CC7.2 and CC8.1 (monitoring and change
   * traceability); OWASP ASVS V7 and V11 (evidence handling and business
   * logic integrity). Business objective: preserve supplied coordinates and
   * prevent dependency evidence from being attributed to application code.
   */
  @Test
  void serializedCoordinatesReachDependencyEvidenceWithoutAttributingApplicationFacts()
      throws IOException {
    Fixture fixture = fixture();
    JsonNode output = analyzeAndWrite(fixture, true);

    JsonNode dependencyEvidence = evidence(output, "app.Application#dependencyCall",
        "vendor.api.VulnerableApi#check");
    JsonNode attributes = dependencyEvidence.path("attributes");
    assertEquals("dependency", attributes.path("targetOrigin").asText());
    assertEquals(fixture.dependencyJar().toAbsolutePath().normalize().toString(),
        attributes.path("dependencyClasspathEntry").asText());
    assertEquals("com.example", attributes.path("dependencyGroupId").asText());
    assertEquals("vulnerable-api", attributes.path("dependencyArtifactId").asText());
    assertEquals("1.2.3", attributes.path("dependencyVersion").asText());
    assertEquals("tests", attributes.path("dependencyClassifier").asText());
    assertEquals(ENTRY_FINGERPRINT,
        attributes.path("dependencyClasspathEntryFingerprint").asText());

    JsonNode applicationEvidence = evidence(output, "app.Application#applicationCall",
        "app.Local#value");
    assertFalse(applicationEvidence.path("attributes").has("dependencyGroupId"));
    assertFalse(applicationEvidence.path("attributes").has("dependencyArtifactId"));
    assertFalse(applicationEvidence.path("attributes").has("dependencyVersion"));
    assertFalse(applicationEvidence.path("attributes").has("dependencyClasspathEntry"));

    JsonNode manifest = output.path("runMetadata").path("dependencyCoordinateManifest");
    assertEquals(OWNER, manifest.path("owner").asText());
    assertEquals(MANIFEST_FINGERPRINT, manifest.path("fingerprint").asText());
  }

  /**
   * Objective mapping: FedRAMP/NIST 800-53 SI-10 (input validation), SOC 2
   * CC7.2 (detect invalid input), and OWASP ASVS V5 (validation and encoding).
   * Business objective: preserve path-only compatibility without inventing
   * Maven coordinates from filenames or repository paths.
   */
  @Test
  void legacySerializedContextKeepsPathOnlyEvidenceAndDoesNotInferCoordinates()
      throws IOException {
    Fixture fixture = fixture();
    JsonNode output = analyzeAndWrite(fixture, false);

    JsonNode dependencyEvidence = evidence(output, "app.Application#dependencyCall",
        "vendor.api.VulnerableApi#check");
    JsonNode attributes = dependencyEvidence.path("attributes");
    assertEquals(fixture.dependencyJar().toAbsolutePath().normalize().toString(),
        attributes.path("dependencyClasspathEntry").asText());
    assertFalse(attributes.has("dependencyGroupId"));
    assertFalse(attributes.has("dependencyArtifactId"));
    assertFalse(attributes.has("dependencyVersion"));
    assertFalse(attributes.has("dependencyClassifier"));
    assertFalse(attributes.has("dependencyClasspathEntryFingerprint"));
    assertFalse(output.path("runMetadata").has("dependencyCoordinateManifest"));
  }

  /**
   * Objective mapping: AU-3, SOC 2 CC7.2, and ASVS V11.
   * Business objective: distinguish an explicit dependency-free run from a
   * legacy context that predates coordinate metadata.
   */
  @Test
  void explicitEmptySerializedManifestIsPreserved() throws IOException {
    Fixture fixture = fixture();
    Path contextFile = writeContext(fixture, true, root -> {
      root.putArray("dependencyClasspath").removeAll();
      root.put("dependencyClasspathRequired", false);
      ObjectNode manifest = (ObjectNode) root.get("dependencyCoordinateManifest");
      ((ArrayNode) manifest.get("entries")).removeAll();
      manifest.remove("fingerprint");
      ((ObjectNode) root.get("provenance")).remove("dependencyCoordinateFingerprint");
    });
    AnalysisContext context = AnalysisContextLoader.load(contextFile);
    Path outputFile = temp.resolve("empty-manifest.json");
    SemanticCallFactsJsonWriter.write(
        new JavaAnalysisAdapter().analyze(context, AnalysisGraph.empty()), outputFile);

    JsonNode manifest = JSON.readTree(Files.readString(outputFile))
        .path("runMetadata").path("dependencyCoordinateManifest");
    assertTrue(manifest.isObject());
    assertEquals(0, manifest.path("entries").size());
    assertFalse(manifest.has("fingerprint"));
  }

  /**
   * Objective mapping: SI-10, SOC 2 CC7.2, and ASVS V5.
   * Business objective: reject untrusted fields.
   */
  @Test
  void unknownSerializedCoordinateFieldsFailClosed() throws IOException {
    Fixture fixture = fixture();
    Path context = writeContext(fixture, true, root ->
        ((ObjectNode) root.get("dependencyCoordinateManifest")).put("unexpected", true));

    assertThrows(IllegalArgumentException.class, () -> AnalysisContextLoader.load(context));
  }

  /**
   * Objective mapping: AU-10, SOC 2 CC8.1, and ASVS V11.
   * Business objective: accept evidence only from the designated producer.
   */
  @Test
  void untrustedSerializedManifestOwnerFailsClosed() throws IOException {
    Fixture fixture = fixture();
    Path context = writeContext(fixture, true, root ->
        ((ObjectNode) root.get("dependencyCoordinateManifest")).put("owner", "untrusted"));

    assertThrows(IllegalArgumentException.class, () -> AnalysisContextLoader.load(context));
  }

  /**
   * Objective mapping: SI-10, SOC 2 CC7.2, and ASVS V5.
   * Business objective: reject an ambiguous or null handoff object.
   */
  @Test
  void nullSerializedCoordinateManifestFailsClosed() throws IOException {
    Fixture fixture = fixture();
    Path context = writeContext(fixture, true, root ->
        root.putNull("dependencyCoordinateManifest"));

    assertThrows(IllegalArgumentException.class, () -> AnalysisContextLoader.load(context));
  }

  /**
   * Objective mapping: SI-7, SOC 2 CC8.1, and ASVS V5/V10.
   * Business objective: reject unverifiable artifact identity.
   */
  @Test
  void invalidSerializedCoordinateFingerprintFailsClosed() throws IOException {
    Fixture fixture = fixture();
    Path context = writeContext(fixture, true, root -> {
      ObjectNode manifest = (ObjectNode) root.get("dependencyCoordinateManifest");
      ((ObjectNode) manifest.path("entries").get(0)).put("fingerprint", "not-a-sha256");
    });

    assertThrows(IllegalArgumentException.class, () -> AnalysisContextLoader.load(context));
  }

  /**
   * Objective mapping: CM-3, SOC 2 CC8.1, and ASVS V14.
   * Business objective: require reproducible build metadata when coordinates
   * are authoritative.
   */
  @Test
  void incompleteSerializedReplayMetadataFailsClosed() throws IOException {
    Fixture fixture = fixture();
    Path context = writeContext(fixture, true, root ->
        ((ObjectNode) root.get("build")).remove("effectivePomFingerprint"));

    assertThrows(IllegalArgumentException.class, () -> AnalysisContextLoader.load(context));
  }

  /**
   * Objective mapping: AU-10, CM-8, SOC 2 CC8.1, and ASVS V5.
   * Business objective: preserve exact path ownership.
   */
  @Test
  void coordinateOwnershipThatDoesNotMatchTheSerializedClasspathFailsClosed()
      throws IOException {
    Fixture fixture = fixture();
    Path context = writeContext(fixture, true, root -> {
      ObjectNode manifest = (ObjectNode) root.get("dependencyCoordinateManifest");
      ((ObjectNode) manifest.path("entries").get(0))
          .put("classpathEntry", fixture.applicationClasses().toString());
    });

    assertThrows(IllegalArgumentException.class, () -> AnalysisContextLoader.load(context));
  }

  /**
   * Objective mapping: AU-10, SOC 2 CC8.1, and ASVS V11.
   * Business objective: reject stale provenance.
   */
  @Test
  void coordinateProvenanceThatDoesNotMatchTheSerializedManifestFailsClosed()
      throws IOException {
    Fixture fixture = fixture();
    Path context = writeContext(fixture, true, root ->
        ((ObjectNode) root.get("provenance")).put("dependencyCoordinateFingerprint",
            "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"));

    assertThrows(IllegalArgumentException.class, () -> AnalysisContextLoader.load(context));
  }

  /**
   * Objective mapping: SI-10, CM-8, SOC 2 CC8.1, and ASVS V5.
   * Business objective: prevent ambiguous ownership.
   */
  @Test
  void duplicateSerializedCoordinateOwnershipFailsClosed() throws IOException {
    Fixture fixture = fixture();
    Path context = writeContext(fixture, true, root -> {
      ArrayNode entries = (ArrayNode) root.path("dependencyCoordinateManifest").path("entries");
      entries.add(entries.get(0).deepCopy());
    });

    assertThrows(IllegalArgumentException.class, () -> AnalysisContextLoader.load(context));
  }

  /**
   * Objective mapping: FedRAMP/NIST 800-53 SI-10, SOC 2 CC7.2, and OWASP ASVS
   * V5. Business objective: reject malformed serialized envelopes before
   * analysis begins.
   */
  @Test
  void malformedSerializedEnvelopeAndClasspathFlagsFailClosed() throws IOException {
    Fixture fixture = fixture();
    Path nonObject = temp.resolve("non-object.json");
    Files.writeString(nonObject, "[]");
    assertThrows(IllegalArgumentException.class, () -> AnalysisContextLoader.load(nonObject));

    Path nullClasspath = writeContext(fixture, false,
        root -> root.putNull("dependencyClasspath"));
    assertThrows(IllegalArgumentException.class, () -> AnalysisContextLoader.load(nullClasspath));

    Path invalidFlag = writeContext(fixture, false,
        root -> root.put("dependencyClasspathRequired", "yes"));
    assertThrows(IllegalArgumentException.class, () -> AnalysisContextLoader.load(invalidFlag));
  }

  /**
   * Objective mapping: FedRAMP/NIST 800-53 SI-10 and CM-8, SOC 2 CC8.1, and
   * OWASP ASVS V5/V10. Business objective: reject malformed or inferred
   * coordinate ownership data.
   */
  @Test
  void malformedSerializedManifestShapesFailClosed() throws IOException {
    Fixture fixture = fixture();

    Path unsupportedSchema = writeContext(fixture, true, root ->
        ((ObjectNode) root.get("dependencyCoordinateManifest")).put("schemaVersion", "9.9.9"));
    assertThrows(IllegalArgumentException.class,
        () -> AnalysisContextLoader.load(unsupportedSchema));

    Path nullEntries = writeContext(fixture, true, root ->
        ((ObjectNode) root.get("dependencyCoordinateManifest")).putNull("entries"));
    assertThrows(IllegalArgumentException.class, () -> AnalysisContextLoader.load(nullEntries));

    Path nullEntry = writeContext(fixture, true, root ->
        ((ArrayNode) root.path("dependencyCoordinateManifest").path("entries")).addNull());
    assertThrows(IllegalArgumentException.class, () -> AnalysisContextLoader.load(nullEntry));

    Path missingManifestFingerprint = writeContext(fixture, true, root ->
        ((ObjectNode) root.get("dependencyCoordinateManifest")).remove("fingerprint"));
    assertThrows(IllegalArgumentException.class,
        () -> AnalysisContextLoader.load(missingManifestFingerprint));

    Path unavailableEntry = writeContext(fixture, true, root ->
        ((ObjectNode) root.path("dependencyCoordinateManifest").path("entries").get(0))
            .put("classpathEntry", fixture.repositoryRoot().resolve("missing.jar").toString()));
    assertThrows(IllegalArgumentException.class,
        () -> AnalysisContextLoader.load(unavailableEntry));

    Path wildcardEntry = writeContext(fixture, true, root ->
        ((ObjectNode) root.path("dependencyCoordinateManifest").path("entries").get(0))
            .put("classpathEntry", fixture.repositoryRoot().resolve("dependency/*.jar").toString()));
    assertThrows(IllegalArgumentException.class,
        () -> AnalysisContextLoader.load(wildcardEntry));

    Path alias = writeContext(fixture, false,
        root -> root.putObject("dependencyMetadata"));
    assertThrows(IllegalArgumentException.class, () -> AnalysisContextLoader.load(alias));
  }

  /**
   * Objective mapping: FedRAMP/NIST 800-53 AU-10 and CM-3, SOC 2 CC8.1, and
   * OWASP ASVS V11/V14. Business objective: require complete, reproducible
   * Maven replay metadata and explicit activation provenance.
   */
  @Test
  void replayMetadataTamperingFailsClosed() throws IOException {
    Fixture fixture = fixture();

    Path missingBuild = writeContext(fixture, true, root -> root.putNull("build"));
    assertThrows(IllegalArgumentException.class, () -> AnalysisContextLoader.load(missingBuild));

    Path replayWithoutManifest = writeContext(fixture, false, root ->
        ((ObjectNode) root.get("build")).putArray("activeMavenProfiles").add("production"));
    assertThrows(IllegalArgumentException.class,
        () -> AnalysisContextLoader.load(replayWithoutManifest));

    Path missingProfiles = writeContext(fixture, true, root ->
        ((ObjectNode) root.get("build")).remove("activeMavenProfiles"));
    assertThrows(IllegalArgumentException.class,
        () -> AnalysisContextLoader.load(missingProfiles));

    Path blankProfile = writeContext(fixture, true, root ->
        ((ArrayNode) root.path("build").path("activeMavenProfiles")).add(""));
    assertThrows(IllegalArgumentException.class, () -> AnalysisContextLoader.load(blankProfile));

    Path duplicateProfile = writeContext(fixture, true, root ->
        ((ArrayNode) root.path("build").path("activeMavenProfiles")).add("production"));
    assertThrows(IllegalArgumentException.class,
        () -> AnalysisContextLoader.load(duplicateProfile));

    Path incompleteSources = writeContext(fixture, true, root ->
        ((ObjectNode) root.path("build").path("mavenProfileActivationSources"))
            .remove("production"));
    assertThrows(IllegalArgumentException.class,
        () -> AnalysisContextLoader.load(incompleteSources));

    Path inferredSource = writeContext(fixture, true, root ->
        ((ObjectNode) root.path("build").path("mavenProfileActivationSources"))
            .put("production", "inferred"));
    assertThrows(IllegalArgumentException.class,
        () -> AnalysisContextLoader.load(inferredSource));
  }

  /**
   * Objective mapping: FedRAMP/NIST 800-53 SC-7 and SI-10, SOC 2 CC6.6, and
   * OWASP ASVS V5. Business objective: keep serialized paths bounded and
   * reject symlink, traversal, and unavailable dependency inputs.
   */
  @Test
  void serializedPathPolicyPreservesWildcardsAndRejectsUnsafeInputs() throws IOException {
    Fixture fixture = fixture();

    Path wildcard = writeContext(fixture, false, root -> {
      root.putArray("dependencyClasspath")
          .add(fixture.repositoryRoot().resolve("dependency/*").toString());
    });
    AnalysisContext wildcardContext = AnalysisContextLoader.load(wildcard);
    assertTrue(wildcardContext.dependencyClasspath().get(0).toString().endsWith("dependency/*"));

    Path unavailable = writeContext(fixture, false, root -> {
      root.putArray("dependencyClasspath")
          .add(fixture.repositoryRoot().resolve("missing.jar").toString());
    });
    assertThrows(IllegalArgumentException.class, () -> AnalysisContextLoader.load(unavailable));

    Path missingWildcardDirectory = writeContext(fixture, false, root -> {
      root.putArray("dependencyClasspath")
          .add(fixture.repositoryRoot().resolve("missing/*").toString());
    });
    assertThrows(IllegalArgumentException.class,
        () -> AnalysisContextLoader.load(missingWildcardDirectory));

    Path outsideRoot = writeContext(fixture, false, root ->
        ((ObjectNode) root.path("sourceRoots").get(0))
            .put("path", fixture.repositoryRoot().getParent().toString()));
    assertThrows(IllegalArgumentException.class, () -> AnalysisContextLoader.load(outsideRoot));

    Path invalidRepositoryRoot = writeContext(fixture, false,
        root -> root.put("repositoryRoot", "\u0000"));
    assertThrows(IllegalArgumentException.class,
        () -> AnalysisContextLoader.load(invalidRepositoryRoot));

    Path invalidSourcePath = writeContext(fixture, false,
        root -> ((ObjectNode) root.path("sourceRoots").get(0)).put("path", "\u0000"));
    assertThrows(IllegalArgumentException.class,
        () -> AnalysisContextLoader.load(invalidSourcePath));

    Path symlink = fixture.repositoryRoot().resolve("source-link");
    Files.createSymbolicLink(symlink, fixture.applicationSource());
    Path symlinkSource = writeContext(fixture, false,
        root -> ((ObjectNode) root.path("sourceRoots").get(0)).put("path", symlink.toString()));
    assertThrows(IllegalArgumentException.class,
        () -> AnalysisContextLoader.load(symlinkSource));
  }

  /**
   * Objective mapping: FedRAMP/NIST 800-53 AU-3, SOC 2 CC7.2, and OWASP ASVS
   * V11. Business objective: preserve optional upstream fields without
   * fabricating absent classifier or artifact fingerprints.
   */
  @Test
  void absentOptionalCoordinateFieldsRemainAbsentInSerializedFacts() throws IOException {
    Fixture fixture = fixture();
    Path contextFile = writeContext(fixture, true, root -> {
      ObjectNode entry = (ObjectNode) root.path("dependencyCoordinateManifest")
          .path("entries").get(0);
      entry.remove("classifier");
      entry.remove("fingerprint");
    });
    AnalysisContext context = AnalysisContextLoader.load(contextFile);
    Path outputFile = temp.resolve("optional-coordinate-fields.json");
    SemanticCallFactsJsonWriter.write(
        new JavaAnalysisAdapter().analyze(context, AnalysisGraph.empty()), outputFile);

    JsonNode entry = JSON.readTree(Files.readString(outputFile))
        .path("runMetadata").path("dependencyCoordinateManifest").path("entries").get(0);
    assertFalse(entry.has("classifier"));
    assertFalse(entry.has("fingerprint"));
  }

  /**
   * Objective mapping: FedRAMP/NIST 800-53 AU-10, SOC 2 CC8.1, and OWASP ASVS
   * V11. Business objective: detect stale supplied identities without
   * treating absent legacy metadata as a mismatch.
   */
  @Test
  void serializedFreshnessIdentitiesDistinguishLegacyAndChangedReplayInputs()
      throws IOException {
    Fixture fixture = fixture();
    AnalysisContext legacy = AnalysisContextLoader.load(writeContext(fixture, false,
        ignored -> {}));
    AnalysisContext coordinate = AnalysisContextLoader.load(writeContext(fixture, true,
        ignored -> {}));
    assertFalse(legacy.freshnessSnapshot().staleAgainst(coordinate.freshnessSnapshot()));
    assertTrue(coordinate.freshnessSnapshot().staleAgainst(legacy.freshnessSnapshot()));

    AnalysisContext changedInvocation = AnalysisContextLoader.load(writeContext(fixture, true,
        root -> ((ObjectNode) root.get("build")).put("mavenInvocationFingerprint",
            "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")));
    assertTrue(coordinate.freshnessSnapshot().staleAgainst(
        changedInvocation.freshnessSnapshot()));

    AnalysisContext changedCoordinates = AnalysisContextLoader.load(writeContext(fixture, true,
        root -> {
          ((ObjectNode) root.get("dependencyCoordinateManifest"))
              .put("fingerprint", "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
          ((ObjectNode) root.get("provenance"))
              .put("dependencyCoordinateFingerprint",
                  "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
        }));
    assertTrue(coordinate.freshnessSnapshot().staleAgainst(
        changedCoordinates.freshnessSnapshot()));
  }

  @Test
  void programmaticHandoffAndManifestPreservePresenceAndReplayInvariants() {
    assertThrows(IllegalArgumentException.class, () ->
        new AnalysisContext.DependencyCoordinateManifest(
            "0.1.0",
            OWNER,
            MANIFEST_FINGERPRINT,
            List.of(),
            AnalysisContext.DependencyCoordinateManifestPresence.PRESENT_EMPTY));

    assertThrows(IllegalArgumentException.class, () ->
        new AnalysisContext.Handoff(
            "0.1.0",
            "profile",
            "1.0.0",
            "profile-digest",
            "maven",
            List.of("mvn"),
            "target/app.jar",
            "build-fingerprint",
            List.of(),
            List.of("production"),
            null,
            INVOCATION_FINGERPRINT,
            POM_FINGERPRINT));

    assertThrows(IllegalArgumentException.class, () ->
        new AnalysisContext.Handoff(
            "0.1.0",
            "profile",
            "1.0.0",
            "profile-digest",
            "maven",
            List.of("mvn"),
            "target/app.jar",
            "build-fingerprint",
            List.of(),
            List.of("production", "default"),
            java.util.Map.of("production", "explicit", "default", "explicit"),
            INVOCATION_FINGERPRINT,
            POM_FINGERPRINT));
  }

  /**
   * Objective mapping: FedRAMP/NIST 800-53 AU-3, SOC 2 CC8.1, and OWASP ASVS
   * V11. Business objective: serialize multiple external coordinates in a
   * deterministic UTF-8 path order.
   */
  @Test
  void multipleSerializedCoordinatesAreWrittenInDeterministicOrder() throws IOException {
    Fixture fixture = fixture();
    Path first = fixture.repositoryRoot().resolve("a.jar");
    Path prefix = fixture.repositoryRoot().resolve("a.jar.extra");
    Path last = fixture.repositoryRoot().resolve("z.jar");
    Files.copy(fixture.dependencyJar(), first);
    Files.copy(fixture.dependencyJar(), prefix);
    Files.copy(fixture.dependencyJar(), last);

    Path contextFile = writeContext(fixture, true, root -> {
      root.put("repositoryRoot", applicationRoot(fixture).toString());
      ArrayNode classpath = (ArrayNode) root.get("dependencyClasspath");
      classpath.set(0, first.toString());
      classpath.add(prefix.toString());
      classpath.add(last.toString());
      ArrayNode entries = (ArrayNode) root.path("dependencyCoordinateManifest").path("entries");
      ((ObjectNode) entries.get(0)).put("classpathEntry", first.toString());
      addCoordinateEntry(entries, prefix, "vulnerable-api-prefix");
      addCoordinateEntry(entries, last, "vulnerable-api-last");
    });
    AnalysisContext context = AnalysisContextLoader.load(contextFile);
    Path outputFile = temp.resolve("ordered-coordinates.json");
    SemanticCallFactsJsonWriter.write(
        new JavaAnalysisAdapter().analyze(context, AnalysisGraph.empty()), outputFile);

    ArrayNode entries = (ArrayNode) JSON.readTree(Files.readString(outputFile))
        .path("runMetadata").path("dependencyCoordinateManifest").path("entries");
    assertEquals(first.toString(), entries.get(0).path("classpathEntry").asText());
    assertEquals(prefix.toString(), entries.get(1).path("classpathEntry").asText());
    assertEquals(last.toString(), entries.get(2).path("classpathEntry").asText());
  }

  private JsonNode analyzeAndWrite(Fixture fixture, boolean coordinates) throws IOException {
    Path contextFile = writeContext(fixture, coordinates, ignored -> {});
    AnalysisContext context = AnalysisContextLoader.load(contextFile);
    Path outputFile = temp.resolve(coordinates ? "with-coordinates.json" : "legacy.json");
    SemanticCallFactsJsonWriter.write(new JavaAnalysisAdapter().analyze(context), outputFile);
    return JSON.readTree(Files.readString(outputFile));
  }

  private Path writeContext(
      Fixture fixture, boolean coordinates, Consumer<ObjectNode> mutation) throws IOException {
    ObjectNode root = JSON.createObjectNode();
    root.put("schemaVersion", "0.1.0");
    root.put("id", "serialized-coordinate-fixture");
    root.put("fixtureId", "serialized-coordinate-fixture");
    root.put("framework", "quarkus");
    root.put("frameworkVersion", "3.40.1");
    root.put("javaRelease", 17);
    root.put("repositoryRoot", fixture.repositoryRoot().toString());
    root.put("selectedProfile", "production");
    root.put("analysisScope", "production");
    root.put("applicationClasses", fixture.applicationClasses().toString());
    root.put("dependencyClasspathRequired", true);
    root.putArray("dependencyClasspath").add(fixture.dependencyJar().toString());

    ObjectNode sourceRoot = root.putArray("sourceRoots").addObject();
    sourceRoot.put("declaredPath", "src/main/java");
    sourceRoot.put("path", fixture.applicationSource().toString());
    sourceRoot.put("scope", "production");
    sourceRoot.put("generated", false);
    sourceRoot.put("included", true);
    sourceRoot.put("required", true);

    ObjectNode build = root.putObject("build");
    build.put("tool", "maven");
    build.putArray("command").add("mvn").add("package");
    build.put("artifact", "target/application.jar");
    build.put("fingerprint", "build-fingerprint");

    ObjectNode provenance = root.putObject("provenance");
    provenance.put("sourceRepository", "https://example.test/application.git");
    provenance.put("sourceReference", "test-revision");
    provenance.put("sourceRevision", "test-revision");
    provenance.put("sourceFingerprint", "source-fingerprint");
    provenance.put("generatedSourceFingerprint", "generated-fingerprint");
    provenance.put("dependencyFingerprint", "dependency-fingerprint");
    provenance.put("buildFingerprint", "build-fingerprint");
    provenance.put("toolchainFingerprint", "toolchain-fingerprint");
    provenance.put("analyzerFingerprint", "analyzer-fingerprint");
    provenance.put("gitRevision", "test-revision");
    provenance.put("providerRuntime", "test-runtime");
    provenance.put("profileDigest", "profile-digest");
    provenance.put("preparedAt", "2026-01-01T00:00:00Z");
    provenance.putObject("freshness").put("maxAgeSeconds", 86400);

    if (coordinates) {
      build.putArray("activeMavenProfiles").add("production");
      build.putObject("mavenProfileActivationSources").put("production", "explicit");
      build.put("mavenInvocationFingerprint", INVOCATION_FINGERPRINT);
      build.put("effectivePomFingerprint", POM_FINGERPRINT);
      provenance.put("dependencyCoordinateFingerprint", MANIFEST_FINGERPRINT);

      ObjectNode manifest = root.putObject("dependencyCoordinateManifest");
      manifest.put("schemaVersion", "0.1.0");
      manifest.put("owner", OWNER);
      manifest.put("fingerprint", MANIFEST_FINGERPRINT);
      ObjectNode entry = manifest.putArray("entries").addObject();
      entry.put("classpathEntry", fixture.dependencyJar().toString());
      entry.put("groupId", "com.example");
      entry.put("artifactId", "vulnerable-api");
      entry.put("version", "1.2.3");
      entry.put("classifier", "tests");
      entry.put("fingerprint", ENTRY_FINGERPRINT);
    }

    root.putArray("limitations");
    mutation.accept(root);
    Path contextFile = temp.resolve("analysis-context-" + (coordinates ? "coordinates" : "legacy")
        + "-" + Math.abs(mutation.hashCode()) + ".json");
    Files.writeString(contextFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    return contextFile;
  }

  private Fixture fixture() throws IOException {
    Path dependencySource = temp.resolve("dependency/src/main/java");
    Path dependencyClasses = temp.resolve("dependency/target/classes");
    write(dependencySource, "vendor/api/VulnerableApi.java", """
        package vendor.api;
        public final class VulnerableApi {
          public static boolean check(String value) { return value != null; }
        }
        """);
    compile(dependencySource, dependencyClasses, List.of());
    Path dependencyJar = jar(
        dependencyClasses, temp.resolve("com.example.vulnerable-api-1.2.3-tests.jar"));

    Path applicationSource = temp.resolve("application/src/main/java");
    Path applicationClasses = temp.resolve("application/target/classes");
    write(applicationSource, "app/Application.java", """
        package app;
        import vendor.api.VulnerableApi;
        public class Application {
          public boolean dependencyCall(String value) {
            return VulnerableApi.check(value);
          }
          public String applicationCall() {
            return new Local().value();
          }
        }
        """);
    write(applicationSource, "app/Local.java", """
        package app;
        public class Local {
          public String value() { return "application"; }
        }
        """);
    compile(applicationSource, applicationClasses, List.of(dependencyJar));
    return new Fixture(temp, applicationSource, applicationClasses, dependencyJar);
  }

  private static JsonNode evidence(JsonNode output, String caller, String callee) {
    for (JsonNode fact : output.path("providerFacts")) {
      if (caller.equals(fact.path("caller").asText())
          && callee.equals(fact.path("callee").asText())) {
        JsonNode evidence = fact.path("evidence").get(0);
        if (evidence != null && !evidence.isMissingNode()) {
          return evidence;
        }
      }
    }
    throw new AssertionError("missing serialized provider fact " + caller + " -> " + callee
        + ": " + output.path("providerFacts"));
  }

  private static void write(Path root, String relative, String source) throws IOException {
    Path file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, source);
  }

  private static Path applicationRoot(Fixture fixture) {
    return fixture.applicationClasses().getParent().getParent();
  }

  private static void addCoordinateEntry(ArrayNode entries, Path classpathEntry, String artifactId) {
    ObjectNode entry = entries.addObject();
    entry.put("classpathEntry", classpathEntry.toString());
    entry.put("groupId", "com.example");
    entry.put("artifactId", artifactId);
    entry.put("version", "1.2.3");
    entry.put("fingerprint", ENTRY_FINGERPRINT);
    entry.put("classifier", "tests");
  }

  private static void compile(Path sourceRoot, Path classes, List<Path> classpath)
      throws IOException {
    Files.createDirectories(classes);
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("a JDK compiler is required for the serialized handoff test");
    }
    try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
         var sourceFiles = Files.walk(sourceRoot)) {
      List<Path> sources = sourceFiles
          .filter(path -> path.toString().endsWith(".java"))
          .sorted()
          .toList();
      List<String> options = new ArrayList<>(List.of(
          "--release", "17", "-g", "-d", classes.toString()));
      if (!classpath.isEmpty()) {
        options.add("-classpath");
        options.add(classpath.stream().map(Path::toString)
            .reduce((left, right) -> left + java.io.File.pathSeparator + right)
            .orElseThrow());
      }
      var units = fileManager.getJavaFileObjectsFromFiles(
          sources.stream().map(Path::toFile).toList());
      assertTrue(compiler.getTask(null, fileManager, null, options, null, units).call(),
          "serialized handoff fixture compilation failed");
    }
  }

  private static Path jar(Path classes, Path jar) throws IOException {
    try (OutputStream stream = Files.newOutputStream(jar);
         JarOutputStream output = new JarOutputStream(stream);
         var files = Files.walk(classes)) {
      files.filter(Files::isRegularFile)
          .sorted(Comparator.comparing(Path::toString))
          .forEach(file -> {
            try {
              String entryName = classes.relativize(file).toString()
                  .replace(file.getFileSystem().getSeparator(), "/");
              output.putNextEntry(new JarEntry(entryName));
              Files.copy(file, output);
              output.closeEntry();
            } catch (IOException error) {
              throw new FixtureIOException(error);
            }
          });
    } catch (FixtureIOException error) {
      throw error.cause;
    }
    return jar;
  }

  private record Fixture(
      Path repositoryRoot, Path applicationSource, Path applicationClasses, Path dependencyJar) {}

  private static final class FixtureIOException extends RuntimeException {
    private final IOException cause;

    private FixtureIOException(IOException cause) {
      this.cause = cause;
    }
  }
}
