package com.projectlightpath.analysis.java;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Shared bounded source model for the two first-class framework inputs.
 *
 * <p>It models only evidence that is explicit in source or in the selected
 * context. Container selection, proxy generation, and startup ordering remain
 * possible or unresolved when the corresponding runtime/build metadata is not
 * captured.
 */
abstract class BoundedFrameworkModelUtilities extends BoundedFrameworkModelCore implements FrameworkModel {

  BoundedFrameworkModelUtilities(AnalysisContext.Framework framework) {
    super(framework);
  }


  /** Removes nested generic arguments while retaining the erased outer type. */
  static String eraseGenericArguments(String source) {
    StringBuilder result = new StringBuilder(source.length());
    int depth = 0;
    for (int index = 0; index < source.length(); index++) {
      char character = source.charAt(index);
      if (character == '<') {
        depth++;
      } else if (character == '>' && depth > 0) {
        depth--;
      } else if (depth == 0) {
        result.append(character);
      }
    }
    return result.toString();
  }

  /** Produces the stable digest suffix used by generated fact identities. */
  static String digest(String value) {
    try {
      byte[] bytes = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(bytes.length * 2);
      for (byte item : bytes) {
        result.append(String.format(Locale.ROOT, "%02x", item));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  static String dynamicFeatureIdentity(DynamicFeature feature) {
    return String.join("\u0000",
        feature.kind().name(),
        feature.caller().owner(),
        feature.caller().name(),
        feature.caller().descriptor(),
        feature.target(),
        feature.targetDescriptor(),
        feature.sourcePath(),
        Integer.toString(feature.sourceLine()),
        Integer.toString(feature.sourceOffset()),
        feature.symbols().toString());
  }

  static String dynamicTargetSimpleName(String target) {
    int separator = target.indexOf('#');
    String owner = separator < 0 ? target : target.substring(0, separator);
    return simpleName(owner);
  }

  static String dynamicTargetMethod(String target) {
    int separator = target.indexOf('#');
    return separator < 0 ? "" : target.substring(separator + 1);
  }

  static String uniqueFactId(Set<String> ids, String baseId, String identity) {
    if (ids.add(baseId)) {
      return baseId;
    }
    String suffix = digest(identity).substring(0, 16);
    String candidate = baseId + "-" + suffix;
    int duplicate = 2;
    while (!ids.add(candidate)) {
      candidate = baseId + "-" + suffix + "-" + duplicate++;
    }
    return candidate;
  }

  static Map<String, String> dynamicProxyEvidenceAttributes(DynamicFeature feature) {
    if (feature.targetDescriptor().isBlank()) {
      return Map.of("caller", feature.caller().displayName());
    }
    return Map.of(
        "caller", feature.caller().displayName(),
        "targetDescriptor", feature.targetDescriptor());
  }

  static String simpleName(String value) {
    String normalized = eraseGenericArguments(value == null ? "" : value).trim();
    int dot = normalized.lastIndexOf('.');
    return dot < 0 ? normalized : normalized.substring(dot + 1);
  }

  static String slug(String value) {
    return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
  }
}
