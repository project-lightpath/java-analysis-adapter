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
abstract class AnalysisContextLoaderLayer02 extends AnalysisContextLoaderLayer01 {



  /** Chooses the first nonblank compatibility field. */
  static String firstNonBlank(String first, String second) {
    return first != null && !first.isBlank() ? first : second;
  }

  static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  /** Converts absent JSON arrays to immutable empty lists. */
  static <T> List<T> nonNull(List<T> values) {
    return values == null ? List.of() : values;
  }

  /** Rejects absent required JSON objects. */
  static void require(Object value, String label) {
    if (value == null) {
      throw new IllegalArgumentException(label + " must not be null");
    }
  }

  static AnalysisContext.Framework parseFramework(String value) {
    String frameworkId = required(value, "framework").toLowerCase(Locale.ROOT);
    return switch (frameworkId) {
      case "quarkus" -> AnalysisContext.Framework.QUARKUS;
      case "spring-boot" -> AnalysisContext.Framework.SPRING_BOOT;
      default -> throw new IllegalArgumentException("unsupported Java framework: " + frameworkId);
    };
  }

  /** Converts candidate, resolved-binding, and proxy metadata rows. */
  static AnalysisContext.FrameworkBindingMetadata bindingMetadata(ContextDocument document) {
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
  static AnalysisContext.BindingCandidateMetadata bindingCandidate(
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
  static AnalysisContext.ResolvedBindingMetadata resolvedBinding(
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
  static AnalysisContext.ProxyTargetMetadata proxyTarget(
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

  static String profileValue(ProfileDocument profile, ProfileField field) {
    if (profile == null) {
      return "";
    }
    return switch (field) {
      case ID -> nullToEmpty(profile.id());
      case VERSION -> nullToEmpty(profile.version());
      case DIGEST -> nullToEmpty(profile.digest());
    };
  }
}