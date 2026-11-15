package com.projectlightpath.analysis.java;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Responsibility layer for the AnalysisContext JSON handoff. */
abstract class AnalysisContextLoaderLayer06 extends AnalysisContextLoaderLayer05 {



  /** Decodes the presence-aware dependency-coordinate manifest before model binding can erase it. */
  static AnalysisContext.DependencyCoordinateManifest dependencyCoordinateManifest(
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
    List<AnalysisContext.DependencyCoordinateEntry> entries = manifestEntries(entriesNode, repositoryRoot);
    String fingerprint = manifestFingerprint(node, entries);
    return new AnalysisContext.DependencyCoordinateManifest(
        schemaVersion, owner, fingerprint, entries);
  }

  static ContextInputs contextInputs(ContextDocument document, JsonNode documentNode) {
    validateSchema(document);
    String id = required(document.id(), "context id");
    AnalysisContext.Framework framework = parseFramework(document.framework());
    String fixtureId = firstNonBlank(
        document.fixtureId(), document.fixture() == null ? null : document.fixture().id());
    int javaRelease = javaRelease(document);
    Path repositoryRoot = requiredDirectory(document.repositoryRoot(), "repository root");
    Path applicationClasses = requiredDirectory(document.applicationClasses(), "application classes");
    boolean classpathRequired = dependencyClasspathRequired(documentNode);
    AnalysisContext.DependencyCoordinateManifest manifest = dependencyCoordinateManifest(
        documentNode, repositoryRoot);
    return new ContextInputs(
        id,
        framework,
        fixtureId,
        javaRelease,
        repositoryRoot,
        applicationClasses,
        classpathRequired,
        manifest,
        provenance(document.provenance(), documentNode));
  }

  /** Converts the validated JSON handoff document into an immutable context. */
  static AnalysisContext fromDocument(ContextDocument document, JsonNode documentNode) {
    require(document, "analysis context");
    ContextInputs inputs = contextInputs(document, documentNode);
    AnalysisContext.Builder builder = baseBuilder(document, inputs);
    addRoots(builder, document, inputs.repositoryRoot());
    addDependencyClasspath(builder, document, documentNode, inputs);
    addFrameworkMetadata(builder, document);
    builder.frameworkBindingMetadata(bindingMetadata(document));
    builder.handoff(handoff(document, documentNode, inputs.manifest()));
    return builder.build();
  }
}