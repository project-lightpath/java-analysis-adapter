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

/** Loads and validates the VPE AnalysisContext JSON handoff. */
public final class AnalysisContextLoader extends AnalysisContextLoaderLayer06 {
  public static final String SUPPORTED_SCHEMA_VERSION =
      AnalysisContextLoaderSupport.SUPPORTED_SCHEMA_VERSION;

  private AnalysisContextLoader() {
    super();
  }



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
}
