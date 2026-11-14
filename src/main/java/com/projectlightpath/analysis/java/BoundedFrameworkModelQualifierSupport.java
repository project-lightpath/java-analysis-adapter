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
abstract class BoundedFrameworkModelQualifierSupport extends BoundedFrameworkModelTypeSupport implements FrameworkModel {

  BoundedFrameworkModelQualifierSupport(AnalysisContext.Framework framework) {
    super(framework);
  }

  abstract List<AnalysisContext.BindingCandidateMetadata> matchingCandidateMetadata(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.BeanCandidate candidate);


  /** Filters candidates by requested framework qualifier semantics. */
  List<SourceMetadataScannerState.BeanCandidate> qualifierMatches(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.Parameter parameter,
      SourceMetadataScannerState.InjectionPoint injection,
      List<SourceMetadataScannerState.BeanCandidate> candidates) {
    Set<String> requested = new LinkedHashSet<>(mergeQualifiers(
        injection.qualifiers(), parameter.qualifiers()));
    if (requested.isEmpty()) {
      if (framework != AnalysisContext.Framework.QUARKUS) {
        return candidates;
      }
      return candidates.stream()
          .filter(candidate -> candidateQualifiers(context, scanner, candidate).stream()
              .noneMatch(this::explicitQualifier))
          .toList();
    }
    return candidates.stream()
        .filter(candidate -> requested.stream().allMatch(requestedQualifier ->
            matchesRequestedQualifier(context, scanner, requestedQualifier, candidate, candidates)))
        .toList();
  }

  /** Applies framework qualifier rules, including Spring bean-name fallbacks. */
  boolean matchesRequestedQualifier(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      String requestedQualifier,
      SourceMetadataScannerState.BeanCandidate candidate,
      List<SourceMetadataScannerState.BeanCandidate> candidates) {
    if (requestedQualifier.startsWith("ResourceDefault:")) {
      String name = requestedQualifier.substring("ResourceDefault:".length());
      boolean namedCandidateExists = candidates.stream()
          .anyMatch(item -> resourceNameMatches(context, scanner, name, item));
      return !namedCandidateExists || resourceNameMatches(context, scanner, name, candidate);
    }
    if (requestedQualifier.startsWith("Resource:")) {
      return resourceNameMatches(
          context,
          scanner,
          requestedQualifier.substring("Resource:".length()),
          candidate);
    }
    if (framework == AnalysisContext.Framework.SPRING_BOOT
        && requestedQualifier.startsWith("Qualifier:")) {
      String value = requestedQualifier.substring("Qualifier:".length());
      return candidateQualifiers(context, scanner, candidate).stream()
          .anyMatch(candidateQualifier -> qualifierEquals(requestedQualifier, candidateQualifier))
          || candidateBeanNames(context, scanner, candidate).contains(value);
    }
    return candidateQualifiers(context, scanner, candidate).stream()
        .anyMatch(candidateQualifier -> qualifierEquals(requestedQualifier, candidateQualifier));
  }

  /** Combines source and metadata qualifiers for one candidate. */
  List<String> candidateQualifiers(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.BeanCandidate candidate) {
    LinkedHashSet<String> result = new LinkedHashSet<>(candidate.qualifiers());
    matchingCandidateMetadata(context, scanner, candidate).stream()
        .flatMap(row -> row.qualifiers().stream())
        .forEach(result::add);
    return List.copyOf(result);
  }

  /** Matches a Spring resource name against candidate bean names. */
  boolean resourceNameMatches(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      String name,
      SourceMetadataScannerState.BeanCandidate candidate) {
    return candidateBeanNames(context, scanner, candidate).contains(name);
  }

  /** Returns explicit and convention-based names that can identify a bean. */
  Set<String> candidateBeanNames(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.BeanCandidate candidate) {
    Set<String> names = new LinkedHashSet<>();
    String defaultName = candidate.producer()
        ? candidate.producerMethod()
        : decapitalize(candidate.file().simpleTypeName());
    if (defaultName != null && !defaultName.isBlank()) {
      names.add(defaultName);
    }
    candidateQualifiers(context, scanner, candidate).stream()
        .filter(qualifier -> qualifier.startsWith("BeanName:")
            || qualifier.startsWith("Named:")
            || qualifier.startsWith("Qualifier:"))
        .map(qualifier -> qualifier.substring(qualifier.indexOf(':') + 1))
        .forEach(names::add);
    return Set.copyOf(names);
  }

  /** Applies primary or enabled-alternative preference when binding metadata did not select. */
  List<SourceMetadataScannerState.BeanCandidate> bindingPreference(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      List<SourceMetadataScannerState.BeanCandidate> candidates) {
    if (candidates.size() < 2) {
      return candidates;
    }
    if (framework == AnalysisContext.Framework.SPRING_BOOT) {
      List<SourceMetadataScannerState.BeanCandidate> primary = candidates.stream()
          .filter(candidate -> candidate.primary() || matchingCandidateMetadata(context, scanner, candidate).stream()
              .anyMatch(AnalysisContext.BindingCandidateMetadata::primary))
          .toList();
      return primary.size() == 1 ? primary : candidates;
    }
    List<SourceMetadataScannerState.BeanCandidate> alternatives = candidates.stream()
        .filter(candidate -> matchingCandidateMetadata(context, scanner, candidate).stream()
            .anyMatch(row -> row.alternative() && row.active() == Boolean.TRUE))
        .toList();
    return alternatives.isEmpty() ? candidates : alternatives;
  }

  /** Identifies qualifiers that exclude an unqualified CDI injection point. */
  boolean explicitQualifier(String qualifier) {
    String normalized = qualifier.toLowerCase(Locale.ROOT);
    return !normalized.equals("default")
        && !normalized.equals("any")
        && !normalized.startsWith("named:")
        && !normalized.startsWith("beanname:");
  }

  /** Compares normalized qualifier kind and value without suffix matching. */
  static boolean qualifierEquals(String left, String right) {
    return left != null && left.equals(right);
  }

  /** Treats an implicit resource-name marker as its public resource qualifier. */
  static boolean bindingQualifierEquals(String left, String right) {
    if (qualifierEquals(left, right)) {
      return true;
    }
    return ((left.startsWith("ResourceDefault:") && right.startsWith("Resource:"))
        || (left.startsWith("Resource:") && right.startsWith("ResourceDefault:")))
        && left.substring(left.indexOf(':') + 1).equals(right.substring(right.indexOf(':') + 1));
  }

  /** Applies the default bean-name decapitalization used by Spring. */
  static String decapitalize(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    if (value.length() > 1 && Character.isUpperCase(value.charAt(0))
        && Character.isUpperCase(value.charAt(1))) {
      return value;
    }
    return Character.toLowerCase(value.charAt(0)) + value.substring(1);
  }

  /** Merges qualifier lists while preserving their first-seen order. */
  static List<String> mergeQualifiers(List<String> first, List<String> second) {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    if (first != null) {
      result.addAll(first);
    }
    if (second != null) {
      result.addAll(second);
    }
    return List.copyOf(result);
  }

  /** Hides the internal marker used to distinguish an implicit {@code @Resource} name. */
  static List<String> displayQualifiers(List<String> qualifiers) {
    return qualifiers.stream()
        .map(qualifier -> qualifier.startsWith("ResourceDefault:")
            ? "Resource:" + qualifier.substring("ResourceDefault:".length())
            : qualifier)
        .toList();
  }

  /** Returns known source-profile state without treating a Maven profile as a Spring profile. */
  static Boolean profileMatches(List<String> profiles) {
    if (profiles == null || profiles.isEmpty()) {
      return Boolean.TRUE;
    }
    return null;
  }
}
