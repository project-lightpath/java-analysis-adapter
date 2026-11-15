package com.projectlightpath.analysis.java;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes the normalized SemanticCallFacts handoff used by manual validation. */
public final class SemanticCallFactsJsonWriter {
  private static final ObjectMapper MAPPER = new ObjectMapper()
      .enable(SerializationFeature.INDENT_OUTPUT)
      .setSerializationInclusion(JsonInclude.Include.NON_NULL);

  private SemanticCallFactsJsonWriter() {}

  public static void write(SemanticCallFacts facts, Path output) throws IOException {
    String json = MAPPER.writeValueAsString(toDocument(facts)) + "\n";
    if ("-".equals(output.toString())) {
      System.out.print(json);
      return;
    }
    Path parent = output.toAbsolutePath().normalize().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(output, json);
  }

  private static Map<String, Object> toDocument(SemanticCallFacts facts) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schemaVersion", "0.1.0");
    result.put("artifact", "SemanticCallFacts");
    result.put("contextId", facts.contextId());
    result.put("framework", facts.framework().id());
    result.put("runMetadata", runMetadata(facts.runMetadata()));
    result.put("providerFacts", facts.providerFacts().stream().map(SemanticCallFactsJsonWriter::fact).toList());
    result.put("frameworkFacts", facts.frameworkFacts().stream().map(SemanticCallFactsJsonWriter::fact).toList());
    result.put("links", facts.links().stream().map(SemanticCallFactsJsonWriter::link).toList());
    result.put("mergePolicy", mergePolicy(facts));
    result.put("coverage", coverage(facts));
    result.put("limitations", facts.runMetadata().handoff().limitations());
    return result;
  }

  private static Map<String, Object> runMetadata(SemanticCallFacts.RunMetadata metadata) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("contextId", metadata.contextId());
    result.put("framework", metadata.framework());
    result.put("frameworkVersion", metadata.frameworkVersion());
    result.put("fixtureId", metadata.fixtureId());
    result.put("selectedProfile", metadata.selectedProfile());
    result.put("analysisScope", metadata.analysisScope());
    result.put("javaRelease", metadata.javaRelease());
    result.put("providerName", metadata.providerName());
    result.put("providerVersion", metadata.providerVersion());
    result.put("graphMethodCount", metadata.graphMethodCount());
    result.put("graphCallCount", metadata.graphCallCount());
    result.put("provenance", provenance(metadata.provenance()));
    result.put("handoff", handoff(metadata.handoff()));
    if (metadata.dependencyCoordinateManifest().present()) {
      result.put(
          "dependencyCoordinateManifest",
          dependencyCoordinateManifest(
              metadata.dependencyCoordinateManifest(), metadata.repositoryRoot()));
    }
    result.put("sourceRoots", metadata.sourceRoots().stream()
        .map(SemanticCallFactsJsonWriter::sourceRoot)
        .toList());
    result.put("generatedSourceRoots", metadata.generatedSourceRoots().stream()
        .map(SemanticCallFactsJsonWriter::sourceRoot)
        .toList());
    return result;
  }

  private static Map<String, Object> sourceRoot(AnalysisContext.SourceRoot root) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("declaredPath", root.declaredPath());
    result.put("path", root.path().toString());
    result.put("scope", root.scope().id());
    result.put("generated", root.generated());
    result.put("included", root.included());
    result.put("required", root.required());
    result.put("fingerprint", root.fingerprint());
    return result;
  }

  private static Map<String, Object> provenance(AnalysisContext.Provenance provenance) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("sourceRepository", provenance.sourceRepository());
    result.put("sourceReference", provenance.sourceReference());
    result.put("sourceRevision", provenance.sourceRevision());
    result.put("sourceFingerprint", provenance.sourceFingerprint());
    result.put("generatedSourceFingerprint", provenance.generatedSourceFingerprint());
    result.put("dependencyFingerprint", provenance.dependencyFingerprint());
    putIfPresent(
        result,
        "dependencyClasspathOrderFingerprint",
        provenance.dependencyClasspathOrderFingerprint());
    putIfPresent(
        result,
        "dependencyCoordinateFingerprint",
        provenance.dependencyCoordinateFingerprint());
    result.put("buildFingerprint", provenance.buildFingerprint());
    result.put("toolchainFingerprint", provenance.toolchainFingerprint());
    result.put("analyzerFingerprint", provenance.analyzerFingerprint());
    result.put("gitRevision", provenance.gitRevision());
    result.put("providerRuntime", provenance.providerRuntime());
    result.put("profileDigest", provenance.profileDigest());
    result.put("preparedAt", provenance.preparedAt());
    result.put("freshnessMaxAgeSeconds", provenance.freshnessMaxAgeSeconds());
    return result;
  }

  private static Map<String, Object> handoff(AnalysisContext.Handoff handoff) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schemaVersion", handoff.schemaVersion());
    result.put("profileId", handoff.profileId());
    result.put("profileVersion", handoff.profileVersion());
    result.put("profileDigest", handoff.profileDigest());
    result.put("buildTool", handoff.buildTool());
    result.put("buildCommand", handoff.buildCommand());
    result.put("buildArtifact", handoff.buildArtifact());
    result.put("buildFingerprint", handoff.buildFingerprint());
    result.put("limitations", handoff.limitations());
    if (handoff.activeMavenProfiles() != null) {
      result.put("activeMavenProfiles", handoff.activeMavenProfiles());
    }
    if (handoff.mavenProfileActivationSources() != null) {
      result.put(
          "mavenProfileActivationSources",
          new java.util.TreeMap<>(handoff.mavenProfileActivationSources()));
    }
    putIfPresent(result, "mavenInvocationFingerprint", handoff.mavenInvocationFingerprint());
    putIfPresent(result, "effectivePomFingerprint", handoff.effectivePomFingerprint());
    return result;
  }

  private static Map<String, Object> dependencyCoordinateManifest(
      AnalysisContext.DependencyCoordinateManifest manifest, Path repositoryRoot) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schemaVersion", manifest.schemaVersion());
    result.put("owner", manifest.owner());
    result.put("entries", manifest.entries().stream()
        .sorted((left, right) -> compareEntries(left, right, repositoryRoot))
        .map(SemanticCallFactsJsonWriter::dependencyCoordinateEntry)
        .toList());
    putIfPresent(result, "fingerprint", manifest.fingerprint());
    return result;
  }

  private static Map<String, Object> dependencyCoordinateEntry(
      AnalysisContext.DependencyCoordinateEntry entry) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("classpathEntry", entry.classpathEntry().toString());
    result.put("groupId", entry.groupId());
    result.put("artifactId", entry.artifactId());
    result.put("version", entry.version());
    putIfPresent(result, "fingerprint", entry.fingerprint());
    putIfPresent(result, "classifier", entry.classifier());
    return result;
  }

  private static int compareEntries(
      AnalysisContext.DependencyCoordinateEntry left,
      AnalysisContext.DependencyCoordinateEntry right,
      Path repositoryRoot) {
    String leftLabel = canonicalLabel(left.classpathEntry(), repositoryRoot);
    String rightLabel = canonicalLabel(right.classpathEntry(), repositoryRoot);
    int labels = compareUtf8(leftLabel, rightLabel);
    return labels != 0
        ? labels
        : compareUtf8(left.classpathEntry().toString(), right.classpathEntry().toString());
  }

  private static String canonicalLabel(Path path, Path repositoryRoot) {
    Path normalized = path.toAbsolutePath().normalize();
    Path root = repositoryRoot.toAbsolutePath().normalize();
    if (normalized.startsWith(root)) {
      return root.relativize(normalized).toString().replace(
          normalized.getFileSystem().getSeparator(), "/");
    }
    return "external:" + normalized.toString().replace(
        normalized.getFileSystem().getSeparator(), "/");
  }

  private static int compareUtf8(String left, String right) {
    byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
    byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
    int length = Math.min(leftBytes.length, rightBytes.length);
    for (int index = 0; index < length; index++) {
      int comparison = Byte.toUnsignedInt(leftBytes[index])
          - Byte.toUnsignedInt(rightBytes[index]);
      if (comparison != 0) {
        return comparison;
      }
    }
    return Integer.compare(leftBytes.length, rightBytes.length);
  }

  private static void putIfPresent(Map<String, Object> result, String key, String value) {
    if (value != null) {
      result.put(key, value);
    }
  }

  private static Map<String, Object> fact(SemanticCallFacts.Fact fact) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", fact.id());
    result.put("framework", fact.framework());
    result.put("contextId", fact.contextId());
    result.put("caller", fact.caller());
    result.put("callee", fact.callee());
    result.put("relationshipType", fact.relationshipType());
    result.put("certainty", fact.certainty().id());
    result.put("status", fact.status().id());
    result.put("factSet", fact.factSet().id());
    result.put("evidenceOrigin", fact.evidenceOrigin().id());
    result.put("modelRule", fact.modelRule());
    result.put("claim", fact.claim());
    result.put("runtimeSelection", fact.runtimeSelection());
    result.put("providerCallerAbsence", fact.providerCallerAbsence());
    if (fact.selectionCriteria() != null) {
      result.put("selectionCriteria", selectionCriteria(fact.selectionCriteria()));
    }
    result.put("derivedFrom", fact.derivedFrom());
    result.put("evidence", fact.evidence().stream().map(SemanticCallFactsJsonWriter::evidence).toList());
    result.put("limitation", fact.limitation());
    return result;
  }

  private static Map<String, Object> selectionCriteria(SemanticCallFacts.RouteSelectionCriteria criteria) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("httpMethod", criteria.httpMethod());
    result.put("pathTemplate", criteria.pathTemplate());
    result.put("consumes", criteria.consumes());
    result.put("produces", criteria.produces());
    result.put("headers", criteria.headers());
    result.put("parameters", criteria.parameters());
    result.put("activeAnalysisContext", criteria.activeAnalysisContext());
    return result;
  }

  private static Map<String, Object> evidence(SemanticCallFacts.Evidence evidence) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("kind", evidence.kind());
    result.put("path", evidence.path());
    result.put("symbols", evidence.symbols());
    result.put("line", evidence.line());
    result.put("attributes", evidence.attributes());
    return result;
  }

  private static Map<String, Object> link(SemanticCallFacts.Link link) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("frameworkFactId", link.frameworkFactId());
    result.put("providerFactId", link.providerFactId());
    result.put("relation", link.relation());
    result.put("certaintyPreserved", link.certaintyPreserved());
    return result;
  }

  private static Map<String, Object> mergePolicy(SemanticCallFacts facts) {
    SemanticCallFacts.MergePolicy policy = facts.mergePolicy();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("providerAndFrameworkFactsRemainDistinguishable", policy.providerAndFrameworkFactsRemainDistinguishable());
    result.put("frameworkFactsNeverReplaceProviderFacts", policy.frameworkFactsNeverReplaceProviderFacts());
    result.put("providerCallerAbsenceDoesNotDisproveRoute", policy.providerCallerAbsenceDoesNotDisproveRoute());
    result.put("unresolvedDynamicBehaviorRemainsUnresolved", policy.unresolvedDynamicBehaviorRemainsUnresolved());
    result.put("certaintyStates", facts.certaintyStates().stream()
        .map(SemanticCallFacts.Certainty::id)
        .sorted()
        .toList());
    return result;
  }

  private static Map<String, Object> coverage(SemanticCallFacts facts) {
    List<SemanticCallFacts.Fact> allFacts = new ArrayList<>(facts.allFacts());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("providerFactCount", facts.providerFacts().size());
    result.put("frameworkFactCount", facts.frameworkFacts().size());
    result.put("factCount", allFacts.size());
    result.put("emittedFactCount", allFacts.stream()
        .filter(fact -> fact.status() == SemanticCallFacts.Status.EMITTED)
        .count());
    result.put("abstainedFactCount", allFacts.stream()
        .filter(fact -> fact.status() == SemanticCallFacts.Status.ABSTAINED)
        .count());
    result.put("linkCount", facts.links().size());
    result.put("relationshipTypes", allFacts.stream()
        .map(SemanticCallFacts.Fact::relationshipType)
        .distinct()
        .sorted(Comparator.naturalOrder())
        .toList());
    return result;
  }
}
