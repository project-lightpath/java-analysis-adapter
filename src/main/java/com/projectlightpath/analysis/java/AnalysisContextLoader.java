package com.projectlightpath.analysis.java;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Loads and validates the VPE AnalysisContext JSON handoff. */
public final class AnalysisContextLoader {
  public static final String SUPPORTED_SCHEMA_VERSION = "0.1.0";

  private static final Set<String> MANIFEST_FIELDS = Set.of(
      "schemaVersion", "owner", "entries", "fingerprint");
  private static final Set<String> ENTRY_FIELDS = Set.of(
      "classpathEntry", "groupId", "artifactId", "version", "fingerprint", "classifier");
  private static final Set<String> BUILD_FIELDS = Set.of(
      "tool", "command", "artifact", "fingerprint", "activeMavenProfiles",
      "mavenProfileActivationSources", "mavenInvocationFingerprint", "effectivePomFingerprint");
  private static final ObjectMapper MAPPER = new ObjectMapper(
      JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
      .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);

  private AnalysisContextLoader() {}

  /** Read one immutable context from a VPE-emitted JSON file. */
  public static AnalysisContext load(Path filename) throws IOException {
    Objects.requireNonNull(filename, "context filename");
    JsonNode root = MAPPER.readValue(filename.toFile(), JsonNode.class);
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException("analysis context must be a JSON object");
    }
    rejectManifestAliases(root);
    dependencyClasspathRequired(root);
    return fromDocument(MAPPER.treeToValue(root, ContextDocument.class), root);
  }

  /** Converts the validated JSON handoff document into an immutable context. */
  private static AnalysisContext fromDocument(ContextDocument document, JsonNode documentNode) {
    require(document, "analysis context");
    if (!SUPPORTED_SCHEMA_VERSION.equals(document.schemaVersion())) {
      throw new IllegalArgumentException(
          "unsupported AnalysisContext schemaVersion: " + document.schemaVersion());
    }
    String id = required(document.id(), "context id");
    String frameworkId = required(document.framework(), "framework").toLowerCase(Locale.ROOT);
    AnalysisContext.Framework framework = switch (frameworkId) {
      case "quarkus" -> AnalysisContext.Framework.QUARKUS;
      case "spring-boot" -> AnalysisContext.Framework.SPRING_BOOT;
      default -> throw new IllegalArgumentException("unsupported Java framework: " + frameworkId);
    };
    String fixtureId = firstNonBlank(
        document.fixtureId(),
        document.fixture() == null ? null : document.fixture().id());
    int javaRelease = document.javaRelease() > 0
        ? document.javaRelease()
        : document.fixture() == null ? 0 : document.fixture().javaRelease();
    if (javaRelease <= 0) {
      throw new IllegalArgumentException("analysis context Java release must be positive");
    }

    Path repositoryRoot = requiredDirectory(document.repositoryRoot(), "repository root");
    Path applicationClasses = requiredDirectory(
        document.applicationClasses(), "application classes");
    boolean dependencyClasspathRequired = dependencyClasspathRequired(documentNode);
    AnalysisContext.DependencyCoordinateManifest manifest = dependencyCoordinateManifest(
        documentNode, repositoryRoot);
    AnalysisContext.Provenance provenance = provenance(document.provenance(), documentNode);
    AnalysisContext.Builder builder = AnalysisContext.builder()
        .id(id)
        .framework(framework)
        .frameworkVersion(required(document.frameworkVersion(), "framework version"))
        .fixtureId(required(fixtureId, "fixture id"))
        .javaRelease(javaRelease)
        .repositoryRoot(repositoryRoot)
        .applicationClasses(applicationClasses)
        .selectedProfile(required(document.selectedProfile(), "selected profile"))
        .analysisScope(parseScope(document.analysisScope()))
        .dependencyClasspathRequired(dependencyClasspathRequired)
        .dependencyCoordinateManifest(manifest)
        .provenance(provenance);

    for (ResolvedRootDocument root : nonNull(document.sourceRoots())) {
      addRoot(builder, root, false, repositoryRoot);
    }
    for (ResolvedRootDocument root : nonNull(document.generatedSourceRoots())) {
      addRoot(builder, root, true, repositoryRoot);
    }
    if (documentNode.has("dependencyClasspath") && documentNode.get("dependencyClasspath").isNull()) {
      throw new IllegalArgumentException("dependencyClasspath must not be null");
    }
    List<Path> dependencyClasspath = nonNull(document.dependencyClasspath()).stream()
        .map(value -> dependencyPath(value, repositoryRoot, manifest.present()))
        .toList();
    if (dependencyClasspathRequired && dependencyClasspath.isEmpty()) {
      throw new IllegalArgumentException("required dependency classpath is empty");
    }
    builder.dependencyClasspath(dependencyClasspath);
    for (FrameworkMetadataDocument metadata : nonNull(document.frameworkMetadata())) {
      builder.frameworkMetadata(
          required(metadata.id(), "framework metadata id"),
          required(metadata.kind(), "framework metadata kind"),
          parseMetadataStatus(metadata.status()),
          metadata.path());
    }
    builder.frameworkBindingMetadata(bindingMetadata(document));
    builder.handoff(handoff(document, documentNode, manifest));
    return builder.build();
  }

  /** Converts candidate, resolved-binding, and proxy metadata rows. */
  private static AnalysisContext.FrameworkBindingMetadata bindingMetadata(ContextDocument document) {
    FrameworkBindingMetadataDocument envelope = document.frameworkBindingMetadata();
    List<BindingCandidateDocument> candidates = envelope == null
        ? nonNull(document.bindingCandidates())
        : nonNull(envelope.candidates());
    List<ResolvedBindingDocument> bindings = envelope == null
        ? nonNull(document.resolvedBindings())
        : nonNull(envelope.resolvedBindings());
    List<ProxyTargetDocument> proxyTargets = envelope == null
        ? nonNull(document.proxyTargets())
        : nonNull(envelope.proxyTargets());
    if (envelope == null && candidates.isEmpty() && bindings.isEmpty() && proxyTargets.isEmpty()) {
      return AnalysisContext.FrameworkBindingMetadata.empty();
    }
    String schemaVersion = envelope == null || envelope.schemaVersion() == null
        ? "0.1.0"
        : required(envelope.schemaVersion(), "framework binding metadata schemaVersion");
    if (!"0.1.0".equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "unsupported framework binding metadata schemaVersion: " + schemaVersion);
    }
    return new AnalysisContext.FrameworkBindingMetadata(
        schemaVersion,
        candidates.stream().map(AnalysisContextLoader::bindingCandidate).toList(),
        bindings.stream().map(AnalysisContextLoader::resolvedBinding).toList(),
        proxyTargets.stream().map(AnalysisContextLoader::proxyTarget).toList());
  }

  /** Converts one candidate metadata document. */
  private static AnalysisContext.BindingCandidateMetadata bindingCandidate(
      BindingCandidateDocument document) {
    return new AnalysisContext.BindingCandidateMetadata(
        required(document.id(), "binding candidate id"),
        required(document.type(), "binding candidate type"),
        nonNull(document.contracts()),
        nonNull(document.qualifiers()),
        required(document.kind(), "binding candidate kind"),
        document.declaringType(),
        document.producerMethod(),
        document.producerSignature(),
        nonNull(document.profiles()),
        nonNull(document.conditions()),
        document.active(),
        document.alternative(),
        document.primary(),
        document.path(),
        document.providerFactId());
  }

  /** Converts one resolved binding metadata document. */
  private static AnalysisContext.ResolvedBindingMetadata resolvedBinding(
      ResolvedBindingDocument document) {
    return new AnalysisContext.ResolvedBindingMetadata(
        required(document.id(), "resolved binding id"),
        required(document.injectionOwner(), "resolved binding injectionOwner"),
        nullToEmpty(document.injectionMember()),
        required(document.injectionType(), "resolved binding injectionType"),
        document.parameterIndex(),
        nonNull(document.qualifiers()),
        required(document.candidateId(), "resolved binding candidateId"),
        document.selected(),
        document.active(),
        document.selectionReason(),
        document.path(),
        document.providerFactId());
  }

  /** Converts one proxy target metadata document. */
  private static AnalysisContext.ProxyTargetMetadata proxyTarget(
      ProxyTargetDocument document) {
    return new AnalysisContext.ProxyTargetMetadata(
        required(document.id(), "proxy target id"),
        document.candidateId(),
        required(document.targetType(), "proxy target type"),
        required(document.methodName(), "proxy target methodName"),
        document.methodDescriptor(),
        required(document.proxyKind(), "proxy target proxyKind"),
        document.selected(),
        document.active(),
        document.selectionReason(),
        document.path(),
        document.providerFactId());
  }

  /** Adds one serialized source root to the context builder. */
  private static void addRoot(
      AnalysisContext.Builder builder,
      ResolvedRootDocument root,
      boolean generatedCollection,
      Path repositoryRoot) {
    String declaredPath = required(root.declaredPath(), "source root declaredPath");
    Path resolved = normalizedPath(
        root.path(), "source root " + declaredPath, repositoryRoot, false, false);
    if (!resolved.startsWith(repositoryRoot)) {
      throw new IllegalArgumentException("source root must remain inside repository root: " + declaredPath);
    }
    if (!existsNoFollow(resolved)
        && (!generatedCollection || root.required() || root.included())) {
      throw new IllegalArgumentException("source root is unavailable: " + declaredPath);
    }
    AnalysisContext.Scope scope = parseScope(root.scope());
    boolean generated = generatedCollection || root.generated();
    if (generated) {
      builder.generatedSourceRoot(
          declaredPath, resolved, scope, root.included(), root.required(), root.fingerprint());
    } else {
      if (root.required() && !root.included()) {
        throw new IllegalArgumentException("required source root must be included: " + declaredPath);
      }
      builder.sourceRoot(declaredPath, resolved, scope, root.required(), root.fingerprint());
    }
  }

  /** Converts serialized provenance while preserving freshness fields. */
  private static AnalysisContext.Provenance provenance(
      ProvenanceDocument value, JsonNode root) {
    require(value, "provenance");
    JsonNode provenanceNode = requiredObject(root, "provenance");
    return new AnalysisContext.Provenance(
        required(value.sourceRepository(), "provenance sourceRepository"),
        required(value.sourceReference(), "provenance sourceReference"),
        required(value.sourceRevision(), "provenance sourceRevision"),
        required(value.sourceFingerprint(), "provenance sourceFingerprint"),
        required(value.generatedSourceFingerprint(), "provenance generatedSourceFingerprint"),
        required(value.dependencyFingerprint(), "provenance dependencyFingerprint"),
        optionalFingerprint(provenanceNode, "dependencyClasspathOrderFingerprint"),
        optionalFingerprint(provenanceNode, "dependencyCoordinateFingerprint"),
        required(value.buildFingerprint(), "provenance buildFingerprint"),
        required(value.toolchainFingerprint(), "provenance toolchainFingerprint"),
        required(value.analyzerFingerprint(), "provenance analyzerFingerprint"),
        required(value.gitRevision(), "provenance gitRevision"),
        required(value.providerRuntime(), "provenance providerRuntime"),
        required(value.profileDigest(), "provenance profileDigest"),
        required(value.preparedAt(), "provenance preparedAt"),
        value.freshness() == null ? 0 : value.freshness().maxAgeSeconds());
  }

  /** Converts the serialized build handoff. */
  private static AnalysisContext.Handoff handoff(
      ContextDocument document,
      JsonNode root,
      AnalysisContext.DependencyCoordinateManifest manifest) {
    ProfileDocument profile = document.profile();
    BuildDocument build = document.build();
    JsonNode buildNode = root.get("build");
    boolean replayExpected = manifest.present();
    if (replayExpected && (buildNode == null || buildNode.isNull() || !buildNode.isObject())) {
      throw new IllegalArgumentException("coordinate-enabled context requires a build object");
    }
    if (!replayExpected && buildNode != null && buildNode.isObject()
        && hasAny(buildNode,
            "activeMavenProfiles",
            "mavenProfileActivationSources",
            "mavenInvocationFingerprint",
            "effectivePomFingerprint")) {
      throw new IllegalArgumentException(
          "replay metadata requires a dependency coordinate manifest");
    }
    if (replayExpected) {
      rejectUnknownFields(buildNode, BUILD_FIELDS, "build");
      requireReplayFields(buildNode);
    }
    return new AnalysisContext.Handoff(
        required(document.schemaVersion(), "schemaVersion"),
        profile == null ? "" : nullToEmpty(profile.id()),
        profile == null ? "" : nullToEmpty(profile.version()),
        profile == null ? "" : nullToEmpty(profile.digest()),
        build == null ? "" : nullToEmpty(build.tool()),
        build == null ? List.of() : nonNull(build.command()),
        build == null ? "" : nullToEmpty(build.artifact()),
        build == null ? "" : nullToEmpty(build.fingerprint()),
        nonNull(document.limitations()),
        replayExpected ? List.copyOf(build.activeMavenProfiles()) : null,
        replayExpected ? Map.copyOf(build.mavenProfileActivationSources()) : null,
        replayExpected ? required(build.mavenInvocationFingerprint(), "build mavenInvocationFingerprint") : null,
        replayExpected ? required(build.effectivePomFingerprint(), "build effectivePomFingerprint") : null);
  }

  /** Parses a serialized production or test scope. */
  private static AnalysisContext.Scope parseScope(String value) {
    String normalized = required(value, "analysis scope").toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "PRODUCTION" -> AnalysisContext.Scope.PRODUCTION;
      case "TEST" -> AnalysisContext.Scope.TEST;
      default -> throw new IllegalArgumentException("unsupported analysis scope: " + value);
    };
  }

  /** Decodes the presence-aware dependency-coordinate manifest before model binding can erase it. */
  private static AnalysisContext.DependencyCoordinateManifest dependencyCoordinateManifest(
      JsonNode root, Path repositoryRoot) {
    if (!root.has("dependencyCoordinateManifest")) {
      return AnalysisContext.DependencyCoordinateManifest.absent();
    }
    JsonNode node = root.get("dependencyCoordinateManifest");
    if (node == null || node.isNull() || !node.isObject()) {
      throw new IllegalArgumentException("dependencyCoordinateManifest must be a JSON object");
    }
    rejectUnknownFields(node, MANIFEST_FIELDS, "dependencyCoordinateManifest");
    String schemaVersion = requiredText(node, "schemaVersion", "manifest schemaVersion");
    String owner = requiredText(node, "owner", "manifest owner");
    JsonNode entriesNode = node.get("entries");
    if (entriesNode == null || entriesNode.isNull() || !entriesNode.isArray()) {
      throw new IllegalArgumentException("manifest entries must be a non-null array");
    }
    List<AnalysisContext.DependencyCoordinateEntry> entries = new java.util.ArrayList<>();
    for (JsonNode entryNode : entriesNode) {
      if (entryNode == null || entryNode.isNull() || !entryNode.isObject()) {
        throw new IllegalArgumentException("manifest entry must be a JSON object");
      }
      rejectUnknownFields(entryNode, ENTRY_FIELDS, "dependency coordinate manifest entry");
      String classpathEntry = requiredText(
          entryNode, "classpathEntry", "dependency coordinate classpathEntry");
      Path normalized = normalizedPath(
          classpathEntry,
          "dependency coordinate classpathEntry",
          repositoryRoot,
          true,
          false);
      if (!existsNoFollow(normalized)) {
        throw new IllegalArgumentException(
            "dependency coordinate classpathEntry is unavailable: " + classpathEntry);
      }
      entries.add(new AnalysisContext.DependencyCoordinateEntry(
          normalized,
          optionalText(entryNode, "fingerprint", "dependency coordinate entry fingerprint"),
          requiredText(entryNode, "groupId", "dependency coordinate groupId"),
          requiredText(entryNode, "artifactId", "dependency coordinate artifactId"),
          requiredText(entryNode, "version", "dependency coordinate version"),
          optionalText(entryNode, "classifier", "dependency coordinate classifier")));
    }
    String fingerprint = null;
    if (!entries.isEmpty()) {
      fingerprint = requiredText(node, "fingerprint", "manifest fingerprint");
      validateFingerprint(fingerprint, "manifest fingerprint");
    } else if (node.has("fingerprint")) {
      throw new IllegalArgumentException(
          "present-empty dependency coordinate manifest must omit fingerprint");
    }
    return new AnalysisContext.DependencyCoordinateManifest(
        schemaVersion, owner, fingerprint, entries);
  }

  private static boolean dependencyClasspathRequired(JsonNode root) {
    if (!root.has("dependencyClasspathRequired")) {
      return true;
    }
    JsonNode value = root.get("dependencyClasspathRequired");
    if (value == null || value.isNull() || !value.isBoolean()) {
      throw new IllegalArgumentException("dependencyClasspathRequired must be a boolean");
    }
    return value.booleanValue();
  }

  private static Path dependencyPath(
      String value, Path repositoryRoot, boolean manifestPresent) {
    Path path = normalizedPath(
        value,
        "dependency classpath entry",
        repositoryRoot,
        true,
        !manifestPresent);
    if (isWildcard(path)) {
      return path;
    }
    if (!existsNoFollow(path)) {
      throw new IllegalArgumentException("dependency classpath entry is unavailable: " + value);
    }
    return path;
  }

  private static boolean isWildcard(Path path) {
    return path.toString().contains("*");
  }

  private static void rejectManifestAliases(JsonNode root) {
    for (String alias : List.of(
        "dependencyCoordinates", "dependencyClasspathMetadata", "dependencyMetadata")) {
      if (root.has(alias)) {
        throw new IllegalArgumentException("unsupported dependency coordinate manifest alias: " + alias);
      }
    }
  }

  private static void rejectUnknownFields(JsonNode node, Set<String> allowed, String label) {
    var fields = node.fieldNames();
    while (fields.hasNext()) {
      String field = fields.next();
      if (!allowed.contains(field)) {
        throw new IllegalArgumentException(label + " contains unknown field: " + field);
      }
    }
  }

  private static String requiredText(JsonNode object, String field, String label) {
    JsonNode value = object.get(field);
    if (value == null || value.isNull() || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(label + " must be a nonblank string");
    }
    return value.textValue();
  }

  private static String optionalText(JsonNode object, String field, String label) {
    if (!object.has(field)) {
      return null;
    }
    return requiredText(object, field, label);
  }

  private static String optionalFingerprint(JsonNode object, String field) {
    if (!object.has(field)) {
      return null;
    }
    String value = requiredText(object, field, "provenance " + field);
    validateFingerprint(value, "provenance " + field);
    return value;
  }

  private static void validateFingerprint(String value, String label) {
    if (!value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(label + " must be a lowercase SHA-256 fingerprint");
    }
  }

  private static JsonNode requiredObject(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || value.isNull() || !value.isObject()) {
      throw new IllegalArgumentException(field + " must be a JSON object");
    }
    return value;
  }

  private static boolean hasAny(JsonNode object, String... fields) {
    for (String field : fields) {
      if (object.has(field)) {
        return true;
      }
    }
    return false;
  }

  private static void requireReplayFields(JsonNode buildNode) {
    JsonNode profiles = buildNode.get("activeMavenProfiles");
    JsonNode sources = buildNode.get("mavenProfileActivationSources");
    if (profiles == null || profiles.isNull() || !profiles.isArray()
        || sources == null || sources.isNull() || !sources.isObject()) {
      throw new IllegalArgumentException("coordinate-enabled build requires replay profile metadata");
    }
    String invocation = requiredText(
        buildNode, "mavenInvocationFingerprint", "build mavenInvocationFingerprint");
    String pom = requiredText(
        buildNode, "effectivePomFingerprint", "build effectivePomFingerprint");
    validateFingerprint(invocation, "build mavenInvocationFingerprint");
    validateFingerprint(pom, "build effectivePomFingerprint");
    List<String> profileValues = new java.util.ArrayList<>();
    for (JsonNode profile : profiles) {
      if (profile == null || profile.isNull() || !profile.isTextual() || profile.textValue().isBlank()) {
        throw new IllegalArgumentException("activeMavenProfiles must contain nonblank strings");
      }
      profileValues.add(profile.textValue());
    }
    List<String> sorted = profileValues.stream().sorted().toList();
    if (!profileValues.equals(sorted)
        || new HashSet<>(profileValues).size() != profileValues.size()) {
      throw new IllegalArgumentException("activeMavenProfiles must be sorted and unique");
    }
    if (sources.size() != profileValues.size()) {
      throw new IllegalArgumentException(
          "mavenProfileActivationSources must cover activeMavenProfiles exactly");
    }
    for (String profile : profileValues) {
      JsonNode source = sources.get(profile);
      if (source == null || !source.isTextual() || !"explicit".equals(source.textValue())) {
        throw new IllegalArgumentException(
            "maven profile activation source must be explicit for " + profile);
      }
    }
    var sourceNames = sources.fieldNames();
    while (sourceNames.hasNext()) {
      if (!profileValues.contains(sourceNames.next())) {
        throw new IllegalArgumentException(
            "mavenProfileActivationSources contains an inactive profile");
      }
    }
  }

  /** Parses an observed or not-captured metadata status. */
  private static AnalysisContext.MetadataStatus parseMetadataStatus(String value) {
    String normalized = required(value, "framework metadata status").toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "OBSERVED" -> AnalysisContext.MetadataStatus.OBSERVED;
      case "NOT_CAPTURED" -> AnalysisContext.MetadataStatus.NOT_CAPTURED;
      default -> throw new IllegalArgumentException("unsupported framework metadata status: " + value);
    };
  }

  /** Validates a required existing directory path. */
  private static Path requiredDirectory(String value, String label) {
    String required = required(value, label);
    Path path;
    try {
      path = Path.of(required).toAbsolutePath().normalize();
    } catch (RuntimeException error) {
      throw new IllegalArgumentException(label + " is not a valid path: " + required, error);
    }
    if (!path.isAbsolute() || !existsNoFollow(path)) {
      throw new IllegalArgumentException(label + " is unavailable: " + required);
    }
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException(label + " must be a directory: " + path);
    }
    return path;
  }

  /** Normalizes a serialized path lexically without resolving symlinks. */
  private static Path normalizedPath(
      String value,
      String label,
      Path repositoryRoot,
      boolean allowExternal,
      boolean allowWildcard) {
    String required = required(value, label);
    Path path;
    try {
      path = Path.of(required);
    } catch (RuntimeException error) {
      throw new IllegalArgumentException(label + " is not a valid path: " + required, error);
    }
    if (!path.isAbsolute()) {
      if (repositoryRoot == null) {
        throw new IllegalArgumentException(label + " must be absolute: " + required);
      }
      path = repositoryRoot.resolve(path);
    }
    path = path.toAbsolutePath().normalize();
    boolean wildcard = isWildcard(path);
    if (wildcard) {
      if (!allowWildcard || !validWildcard(path)) {
        throw new IllegalArgumentException(label + " contains an invalid wildcard: " + required);
      }
      Path directory = path.getParent();
      if (directory == null || !existsNoFollow(directory)
          || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalArgumentException(label + " wildcard directory is unavailable: " + required);
      }
    } else if (!hasNoSymlinkComponents(path)) {
      throw new IllegalArgumentException(label + " must not traverse a symlink: " + required);
    }
    if (!allowExternal && repositoryRoot != null && !path.startsWith(repositoryRoot)) {
      throw new IllegalArgumentException(label + " must remain inside repository root: " + required);
    }
    return path;
  }

  private static boolean validWildcard(Path path) {
    String value = path.toString();
    return path.getFileName() != null
        && "*".equals(path.getFileName().toString())
        && value.indexOf('*') == value.lastIndexOf('*');
  }

  private static boolean existsNoFollow(Path path) {
    return Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        && hasNoSymlinkComponents(path);
  }

  private static boolean hasNoSymlinkComponents(Path path) {
    Path normalized = path.toAbsolutePath().normalize();
    Path current = normalized.getRoot();
    if (current == null) {
      return false;
    }
    for (Path component : normalized) {
      current = current.resolve(component);
      if (Files.isSymbolicLink(current) && !isTrustedPrefixAlias(current)) {
        return false;
      }
    }
    return true;
  }

  /** macOS exposes temporary directories through the trusted /var alias. */
  private static boolean isTrustedPrefixAlias(Path path) {
    String value = path.toAbsolutePath().normalize().toString();
    return "/var".equals(value) || "/tmp".equals(value);
  }

  /** Requires a nonblank serialized scalar. */
  private static String required(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value;
  }

  /** Chooses the first nonblank compatibility field. */
  private static String firstNonBlank(String first, String second) {
    return first != null && !first.isBlank() ? first : second;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  /** Converts absent JSON arrays to immutable empty lists. */
  private static <T> List<T> nonNull(List<T> values) {
    return values == null ? List.of() : values;
  }

  /** Rejects absent required JSON objects. */
  private static void require(Object value, String label) {
    if (value == null) {
      throw new IllegalArgumentException(label + " must not be null");
    }
  }

  /** Root context shape is intentionally kept separate from the domain builder. */
  private record ContextDocument(
      String schemaVersion,
      String id,
      String fixtureId,
      FixtureDocument fixture,
      ProfileDocument profile,
      String framework,
      String frameworkVersion,
      int javaRelease,
      String repositoryRoot,
      String sourceRevision,
      String selectedProfile,
      String analysisScope,
      List<ResolvedRootDocument> sourceRoots,
      List<ResolvedRootDocument> generatedSourceRoots,
      List<String> dependencyClasspath,
      Boolean dependencyClasspathRequired,
      String applicationClasses,
      List<FrameworkMetadataDocument> frameworkMetadata,
      @JsonAlias({"bindingMetadata", "frameworkBindings"})
      FrameworkBindingMetadataDocument frameworkBindingMetadata,
      List<BindingCandidateDocument> bindingCandidates,
      List<ResolvedBindingDocument> resolvedBindings,
      List<ProxyTargetDocument> proxyTargets,
      BuildDocument build,
      ProvenanceDocument provenance,
      List<String> limitations) {}

  private record FixtureDocument(String id, int javaRelease) {}

  private record ProfileDocument(String id, String version, String digest) {}

  private record ResolvedRootDocument(
      String declaredPath,
      String path,
      String scope,
      boolean generated,
      boolean included,
      boolean required,
      String fingerprint) {}

  private record FrameworkMetadataDocument(String id, String kind, String status, String path) {}

  private record FrameworkBindingMetadataDocument(
      String schemaVersion,
      @JsonAlias("beanCandidates")
      List<BindingCandidateDocument> candidates,
      @JsonAlias("bindings")
      List<ResolvedBindingDocument> resolvedBindings,
      @JsonAlias("targets")
      List<ProxyTargetDocument> proxyTargets) {}

  private record BindingCandidateDocument(
      @JsonAlias("candidateId")
      String id,
      @JsonAlias({"candidateType", "beanType"}) String type,
      List<String> contracts,
      List<String> qualifiers,
      String kind,
      String declaringType,
      String producerMethod,
      String producerSignature,
      List<String> profiles,
      List<String> conditions,
      Boolean active,
      boolean alternative,
      boolean primary,
      @JsonAlias({"sourcePath", "declaredPath"}) String path,
      String providerFactId) {}

  private record ResolvedBindingDocument(
      @JsonAlias("bindingId")
      String id,
      String injectionOwner,
      String injectionMember,
      String injectionType,
      Integer parameterIndex,
      List<String> qualifiers,
      String candidateId,
      Boolean selected,
      Boolean active,
      String selectionReason,
      @JsonAlias({"sourcePath", "declaredPath"}) String path,
      String providerFactId) {}

  private record ProxyTargetDocument(
      @JsonAlias("proxyTargetId")
      String id,
      String candidateId,
      String targetType,
      String methodName,
      String methodDescriptor,
      String proxyKind,
      Boolean selected,
      Boolean active,
      String selectionReason,
      @JsonAlias({"sourcePath", "declaredPath"}) String path,
      String providerFactId) {}

  private record BuildDocument(
      String tool,
      List<String> command,
      String artifact,
      String fingerprint,
      List<String> activeMavenProfiles,
      Map<String, String> mavenProfileActivationSources,
      String mavenInvocationFingerprint,
      String effectivePomFingerprint) {}

  private record FreshnessDocument(long maxAgeSeconds) {}

  private record ProvenanceDocument(
      String sourceRepository,
      String sourceReference,
      String sourceRevision,
      String sourceFingerprint,
      String generatedSourceFingerprint,
      String dependencyFingerprint,
      String dependencyClasspathOrderFingerprint,
      String dependencyCoordinateFingerprint,
      String buildFingerprint,
      String toolchainFingerprint,
      String analyzerFingerprint,
      String gitRevision,
      String providerRuntime,
      String profileDigest,
      String preparedAt,
      FreshnessDocument freshness) {}
}
