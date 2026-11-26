package com.projectlightpath.analysis.java;

import java.io.IOException;
import com.projectlightpath.analysis.java.SourceMetadataScannerState.BeanCandidate;
import com.projectlightpath.analysis.java.SourceMetadataScannerState.InjectionPoint;
import com.projectlightpath.analysis.java.SourceMetadataScannerState.MethodInfo;
import com.projectlightpath.analysis.java.SourceMetadataScannerState.Parameter;
import com.projectlightpath.analysis.java.SourceMetadataScannerState.Route;
import com.projectlightpath.analysis.java.SourceMetadataScannerState.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for bounded framework source scanning. */
final class FrameworkBeanScanning {
  private static final List<String> CONDITION_ANNOTATION_NAMES = List.of(
      "Conditional", "ConditionalOnBean", "ConditionalOnMissingBean", "ConditionalOnClass",
      "ConditionalOnMissingClass", "ConditionalOnProperty", "ConditionalOnExpression");

  private FrameworkBeanScanning() {}

    static List<BeanCandidate> beanCandidates(
        SourceMetadataScanner scanner, AnalysisContext.Framework framework) {
      JavacFrameworkAnnotations javac = JavacFrameworkAnnotations.of(scanner);
      List<BeanCandidate> result = new ArrayList<>();
      for (SourceFile file : scanner.files()) {
        addTypeCandidate(result, scanner, framework, file, javac);
        addProducerCandidates(result, scanner, framework, file, javac);
      }
      return List.copyOf(result);
    }

    private static void addTypeCandidate(
        List<BeanCandidate> result,
        SourceMetadataScanner scanner,
        AnalysisContext.Framework framework,
        SourceFile file,
        JavacFrameworkAnnotations javac) {
      String block = file.classAnnotationBlock();
      boolean repository = framework == AnalysisContext.Framework.SPRING_BOOT
          && scanner.springRepositoryContract(file).isPresent();
      boolean bean = framework == AnalysisContext.Framework.QUARKUS
          ? scanner.cdiBean(block)
          : scanner.springComponent(file) || repository;
      if (!bean) {
        return;
      }
      Optional<List<JavacSourceSymbolIndex.AnnotationInfo>> mirrors =
          javac.typeAnnotations(file);
      result.add(new BeanCandidate(
          file,
          scanner.contracts(file),
          repository ? "repository_interface" : scanner.beanKind(file, block, framework),
          file.typeName(),
          file.typeName(),
          beanValues(scanner, javac, framework, file, block, mirrors, "qualifier"),
          beanValues(scanner, javac, framework, file, block, mirrors, "profile"),
          beanValues(scanner, javac, framework, file, block, mirrors, "condition"),
          scanner.hasAnnotation(block, "Alternative"),
          primary(scanner, framework, file, block),
          null,
          null,
          null,
          JavacAbstentionEvidence.NONE,
          JavacAbstentionEvidence.NONE));
    }

    /** Extracts one bean annotation-value family, index first. */
    private static List<String> beanValues(
        SourceMetadataScanner scanner,
        JavacFrameworkAnnotations javac,
        AnalysisContext.Framework framework,
        SourceFile file,
        String block,
        Optional<List<JavacSourceSymbolIndex.AnnotationInfo>> mirrors,
        String kind) {
      boolean quarkus = framework == AnalysisContext.Framework.QUARKUS;
      return switch (kind) {
        case "qualifier" -> JavacFrameworkQualifierFamilies.qualifiers(file, block, mirrors.orElse(List.of()), !quarkus)
            .orElseGet(() -> quarkus
                ? scanner.qualifierNames(block)
                : scanner.qualifierNames(file, block));
        case "profile" -> JavacFrameworkAnnotationValues.values(mirrors.orElse(List.of()), file, block,
                "Profile", null, !quarkus)
            .orElseGet(() -> quarkus
                ? scanner.profileValues(block)
                : scanner.profileValues(file, block));
        default -> JavacFrameworkAnnotationValues.conditionAnnotations(mirrors.orElse(List.of()), file, block,
                CONDITION_ANNOTATION_NAMES, !quarkus)
            .orElseGet(() -> quarkus
                ? scanner.conditionAnnotations(block)
                : scanner.conditionAnnotations(file, block));
      };
    }

    private static boolean primary(
        SourceMetadataScanner scanner, AnalysisContext.Framework framework,
        SourceFile file, String block) {
      return framework == AnalysisContext.Framework.QUARKUS
          ? scanner.hasAnnotation(block, "Primary")
          : scanner.canonicalSpringAnnotation(file, block, "Primary");
    }

    private static void addProducerCandidates(
        List<BeanCandidate> result,
        SourceMetadataScanner scanner,
        AnalysisContext.Framework framework,
        SourceFile file,
        JavacFrameworkAnnotations javac) {
      String block = file.classAnnotationBlock();
      for (MethodInfo method : SourceMetadataScanner.methods(file)) {
        if (isProducer(scanner, framework, file, method) && !method.returnType().isBlank()) {
          result.add(producerCandidate(scanner, framework, file, block, method, javac));
        }
      }
    }

    private static boolean isProducer(
        SourceMetadataScanner scanner, AnalysisContext.Framework framework,
        SourceFile file, MethodInfo method) {
      return framework == AnalysisContext.Framework.QUARKUS
          ? scanner.cdiProducerAnnotation(file, method.annotationBlock())
          : scanner.springBeanAnnotation(file, method.annotationBlock());
    }

    private static BeanCandidate producerCandidate(
        SourceMetadataScanner scanner,
        AnalysisContext.Framework framework,
        SourceFile file,
        String block,
        MethodInfo method,
        JavacFrameworkAnnotations javac) {
      String candidateId = file.typeName() + "#" + method.name() + "("
          + method.parameters().replaceAll("\\s+", " ").trim() + ")";
      String producerType = scanner.resolveReferenceType(method.returnType(), file).orElse("");
      JavacAbstentionEvidence producerTypeAbstention = JavacAbstentionEvidence.NONE;
      if (JavacSourceSymbolIndex.enabled()) {
        // Flag-on: the correlated member's canonical return type is the
        // producer bean type; a non-correlating declaration keeps textual
        // — and #62 records WHY on the candidate (never scan.diagnostics()).
        // The owner keeps this path's historical derivation (the raw
        // extraction start): the shared ownerTypeFor helper is the
        // classification/re-query pair's normalization, not this seam's.
        JavacCorrelation correlation = JavacMemberCorrelation.of(scanner)
            .correlatedMethod(file, scanner.typeContaining(file, method.start()), method);
        producerType = correlation.memberOptional()
            .map(member -> member.returnType())
            .filter(returnType -> !returnType.isBlank() && !returnType.equals("void"))
            .orElse(producerType);
        producerTypeAbstention =
            correlation.abstentionOptional().orElse(JavacAbstentionEvidence.NONE);
      }
      JavacFrameworkAnnotations.MirrorLookup classMirrors =
          javac.typeAnnotationsLookup(file);
      JavacFrameworkAnnotations.MemberLookup methodMirrors = javac.memberLookup(
          scanner.typeContaining(file, method.start()), method.name(), "method",
          JavacFrameworkAnnotations.parameterArity(method.parameters()));
      JavacAbstentionEvidence mirrorAbstention = methodMirrors.abstention()
          .or(() -> classMirrors.abstention())
          .orElse(JavacAbstentionEvidence.NONE);
      ProducerSite site = new ProducerSite(
          file, block, method, classMirrors.mirrors(),
          methodMirrors.member()
              .map(member -> Optional.of(member.annotations()))
              .orElse(Optional.empty()));
      return new BeanCandidate(
          file,
          Set.of(),
          "producer",
          candidateId,
          producerType,
          producerQualifiers(scanner, javac, framework, site),
          mergeAnnotationValues(scanner, javac, framework, site, "profile"),
          mergeAnnotationValues(scanner, javac, framework, site, "condition"),
          scanner.hasAnnotation(block, "Alternative")
              || scanner.hasAnnotation(method.annotationBlock(), "Alternative"),
          producerPrimary(scanner, framework, file, block, method),
          method.name(),
          method.returnType(),
          method.parameters(),
          producerTypeAbstention,
          mirrorAbstention);
    }

    /** One producer-method site and its class- and method-level mirrors. */
    private record ProducerSite(
        SourceFile file,
        String classBlock,
        MethodInfo method,
        Optional<List<JavacSourceSymbolIndex.AnnotationInfo>> classMirrors,
        Optional<List<JavacSourceSymbolIndex.AnnotationInfo>> methodMirrors) {}

    /** Keeps a configuration class's bean name separate from its produced bean. */
    private static List<String> producerQualifiers(
        SourceMetadataScanner scanner,
        JavacFrameworkAnnotations javac,
        AnalysisContext.Framework framework,
        ProducerSite site) {
      List<String> factoryQualifiers = beanValues(
          scanner, javac, framework, site.file(), site.classBlock(),
          site.classMirrors(), "qualifier")
          .stream()
          .filter(value -> !value.startsWith("BeanName:"))
          .toList();
      return scanner.mergeValues(
          factoryQualifiers,
          beanValues(scanner, javac, framework, site.file(),
              site.method().annotationBlock(), site.methodMirrors(), "qualifier"));
    }

    private static List<String> mergeAnnotationValues(
        SourceMetadataScanner scanner,
        JavacFrameworkAnnotations javac,
        AnalysisContext.Framework framework,
        ProducerSite site,
        String kind) {
      return scanner.mergeValues(
          beanValues(scanner, javac, framework, site.file(), site.classBlock(),
              site.classMirrors(), kind),
          beanValues(scanner, javac, framework, site.file(),
              site.method().annotationBlock(), site.methodMirrors(), kind));
    }

    private static boolean producerPrimary(
        SourceMetadataScanner scanner,
        AnalysisContext.Framework framework,
        SourceFile file,
        String block,
        MethodInfo method) {
      if (framework == AnalysisContext.Framework.QUARKUS) {
        return scanner.hasAnnotation(block, "Primary")
            || scanner.hasAnnotation(method.annotationBlock(), "Primary");
      }
      return scanner.canonicalSpringAnnotation(file, block, "Primary")
          || scanner.canonicalSpringAnnotation(file, method.annotationBlock(), "Primary");
    }


}
