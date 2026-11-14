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
abstract class AnalysisContextLoaderLayer05 extends AnalysisContextLoaderLayer04 {



  /** Adds one serialized source root to the context builder. */
  static void addRoot(
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

  static AnalysisContext.DependencyCoordinateEntry manifestEntry(
      JsonNode entryNode, Path repositoryRoot) {
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
    return new AnalysisContext.DependencyCoordinateEntry(
        normalized,
        optionalText(entryNode, "fingerprint", "dependency coordinate entry fingerprint"),
        requiredText(entryNode, "groupId", "dependency coordinate groupId"),
        requiredText(entryNode, "artifactId", "dependency coordinate artifactId"),
        requiredText(entryNode, "version", "dependency coordinate version"),
        optionalText(entryNode, "classifier", "dependency coordinate classifier"));
  }

  static Path dependencyPath(
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

  static void addRoots(
      AnalysisContext.Builder builder,
      List<ResolvedRootDocument> roots,
      boolean generated,
      Path repositoryRoot) {
    for (ResolvedRootDocument root : roots) {
      addRoot(builder, root, generated, repositoryRoot);
    }
  }

  static void addDependencyClasspath(
      AnalysisContext.Builder builder,
      ContextDocument document,
      JsonNode documentNode,
      ContextInputs inputs) {
    if (documentNode.has("dependencyClasspath") && documentNode.get("dependencyClasspath").isNull()) {
      throw new IllegalArgumentException("dependencyClasspath must not be null");
    }
    List<Path> dependencyClasspath = nonNull(document.dependencyClasspath()).stream()
        .map(value -> dependencyPath(value, inputs.repositoryRoot(), inputs.manifest().present()))
        .toList();
    if (inputs.dependencyClasspathRequired() && dependencyClasspath.isEmpty()) {
      throw new IllegalArgumentException("required dependency classpath is empty");
    }
    builder.dependencyClasspath(dependencyClasspath);
  }

  static List<AnalysisContext.DependencyCoordinateEntry> manifestEntries(
      JsonNode entriesNode, Path repositoryRoot) {
    List<AnalysisContext.DependencyCoordinateEntry> entries = new java.util.ArrayList<>();
    for (JsonNode entryNode : entriesNode) {
      entries.add(manifestEntry(entryNode, repositoryRoot));
    }
    return entries;
  }

  static void addRoots(
      AnalysisContext.Builder builder, ContextDocument document, Path repositoryRoot) {
    addRoots(builder, nonNull(document.sourceRoots()), false, repositoryRoot);
    addRoots(builder, nonNull(document.generatedSourceRoots()), true, repositoryRoot);
  }
}