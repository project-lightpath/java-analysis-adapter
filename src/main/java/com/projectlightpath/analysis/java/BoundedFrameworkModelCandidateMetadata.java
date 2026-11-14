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
abstract class BoundedFrameworkModelCandidateMetadata extends BoundedFrameworkModelQualifierSupport implements FrameworkModel {

  BoundedFrameworkModelCandidateMetadata(AnalysisContext.Framework framework) {
    super(framework);
  }


  /** Finds metadata rows belonging to one source candidate. */
  List<AnalysisContext.BindingCandidateMetadata> matchingCandidateMetadata(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.BeanCandidate candidate) {
    return context.bindingCandidates().stream()
        .filter(row -> candidateMetadataMatches(scanner, row, candidate))
        .toList();
  }

  /** Matches candidate identity, canonical type, declaring type, and producer method. */
  static boolean candidateMetadataMatches(
      SourceMetadataScanner scanner,
      AnalysisContext.BindingCandidateMetadata row,
      SourceMetadataScannerState.BeanCandidate candidate) {
    if (row.candidateId().equals(candidate.candidateId())) {
      return true;
    }
    if (candidate.producer() != producerMetadataRow(row)) {
      return false;
    }
    if (!metadataTypeMatches(scanner, row.candidateType(), candidate.candidateType())) {
      return false;
    }
    if (row.declaringType() != null && !row.declaringType().isBlank()
        && !metadataTypeMatches(scanner, row.declaringType(), candidate.file().typeName())) {
      return false;
    }
    if (row.producerMethod() != null && !row.producerMethod().isBlank()) {
      if (!candidate.producer() || !row.producerMethod().equals(candidate.producerMethod())) {
        return false;
      }
      return producerSignatureMatches(scanner, row, candidate);
    }
    if (row.producerSignature() != null && !row.producerSignature().isBlank()) {
      return candidate.producer() && producerSignatureMatches(scanner, row, candidate);
    }
    return !candidate.producer() || producerOverloadCount(candidate) == 1;
  }

  /** Distinguishes producer metadata from ordinary component/class registration rows. */
  static boolean producerMetadataRow(AnalysisContext.BindingCandidateMetadata row) {
    return row.producerMethod() != null && !row.producerMethod().isBlank()
        || row.producerSignature() != null && !row.producerSignature().isBlank()
        || row.kind() != null && row.kind().toLowerCase(Locale.ROOT).contains("producer");
  }

  /** Matches a producer metadata descriptor without conflating overloaded methods. */
  static boolean producerSignatureMatches(
      SourceMetadataScanner scanner,
      AnalysisContext.BindingCandidateMetadata row,
      SourceMetadataScannerState.BeanCandidate candidate) {
    if (row.producerSignature() == null || row.producerSignature().isBlank()) {
      return producerOverloadCount(candidate) == 1;
    }
    return producerDescriptor(scanner, candidate)
        .map(row.producerSignature()::equals)
        .orElse(false);
  }

  /** Counts declarations sharing a producer method name for conservative fallback matching. */
  static long producerOverloadCount(SourceMetadataScannerState.BeanCandidate candidate) {
    return SourceMetadataScanner.methods(candidate.file()).stream()
        .filter(method -> method.name().equals(candidate.producerMethod()))
        .count();
  }

  /** Builds a JVM descriptor only when every reference type can be resolved. */
  static Optional<String> methodDescriptor(
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.ProxyTarget target) {
    return methodDescriptor(scanner, target.file(), target.method().parameters(), target.method().returnType());
  }

  /** Builds a JVM descriptor from one source method signature. */
  static Optional<String> methodDescriptor(
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.SourceFile sourceFile,
      String parameters,
      String returnType) {
    StringBuilder descriptor = new StringBuilder("(");
    for (SourceMetadataScannerState.Parameter parameter : SourceMetadataScanner.parameters(parameters)) {
      Optional<String> parameterDescriptor = jvmDescriptor(parameter.type(), sourceFile, scanner);
      if (parameterDescriptor.isEmpty()) {
        return Optional.empty();
      }
      descriptor.append(parameterDescriptor.get());
    }
    descriptor.append(')');
    Optional<String> returnDescriptor = jvmDescriptor(returnType, sourceFile, scanner);
    return returnDescriptor.map(value -> descriptor.append(value).toString());
  }

  /** Builds a JVM descriptor for a source producer candidate. */
  static Optional<String> producerDescriptor(
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.BeanCandidate candidate) {
    return methodDescriptor(
        scanner,
        candidate.file(),
        candidate.producerParameters(),
        candidate.producerSignature());
  }

  /** Resolves one Java type to a JVM descriptor without guessing unknown references. */
  static Optional<String> jvmDescriptor(
      String type,
      SourceMetadataScannerState.SourceFile sourceFile,
      SourceMetadataScanner scanner) {
    String normalized = eraseGenericArguments(type == null ? "" : type).trim();
    if (normalized.endsWith("...")) {
      normalized = normalized.substring(0, normalized.length() - 3) + "[]";
    }
    if (normalized.endsWith("[]")) {
      return jvmDescriptor(normalized.substring(0, normalized.length() - 2), sourceFile, scanner)
          .map(value -> "[" + value);
    }
    return switch (normalized) {
      case "void" -> Optional.of("V");
      case "boolean" -> Optional.of("Z");
      case "byte" -> Optional.of("B");
      case "char" -> Optional.of("C");
      case "short" -> Optional.of("S");
      case "int" -> Optional.of("I");
      case "long" -> Optional.of("J");
      case "float" -> Optional.of("F");
      case "double" -> Optional.of("D");
      case "" -> Optional.empty();
      default -> scanner.resolveReferenceType(normalized, sourceFile)
          .map(value -> "L" + value.replace('.', '/') + ";");
    };
  }
}
