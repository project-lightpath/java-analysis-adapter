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
  private FrameworkBeanScanning() {}

    static List<BeanCandidate> beanCandidates(
        SourceMetadataScanner scanner, AnalysisContext.Framework framework) {
      List<BeanCandidate> result = new ArrayList<>();
      for (SourceFile file : scanner.files()) {
        addTypeCandidate(result, scanner, framework, file);
        addProducerCandidates(result, scanner, framework, file);
      }
      return List.copyOf(result);
    }

    private static void addTypeCandidate(
        List<BeanCandidate> result,
        SourceMetadataScanner scanner,
        AnalysisContext.Framework framework,
        SourceFile file) {
      String block = file.classAnnotationBlock();
      boolean repository = framework == AnalysisContext.Framework.SPRING_BOOT
          && scanner.springRepositoryContract(file).isPresent();
      boolean bean = framework == AnalysisContext.Framework.QUARKUS
          ? scanner.cdiBean(block)
          : scanner.springComponent(file) || repository;
      if (!bean) {
        return;
      }
      result.add(new BeanCandidate(
          file,
          scanner.contracts(file),
          repository ? "repository_interface" : scanner.beanKind(file, block, framework),
          file.typeName(),
          file.typeName(),
          qualifiers(scanner, framework, file, block),
          profiles(scanner, framework, file, block),
          conditions(scanner, framework, file, block),
          scanner.hasAnnotation(block, "Alternative"),
          primary(scanner, framework, file, block),
          null,
          null,
          null));
    }

    private static List<String> qualifiers(
        SourceMetadataScanner scanner, AnalysisContext.Framework framework,
        SourceFile file, String block) {
      return framework == AnalysisContext.Framework.QUARKUS
          ? scanner.qualifierNames(block) : scanner.qualifierNames(file, block);
    }

    private static List<String> profiles(
        SourceMetadataScanner scanner, AnalysisContext.Framework framework,
        SourceFile file, String block) {
      return framework == AnalysisContext.Framework.QUARKUS
          ? scanner.profileValues(block) : scanner.profileValues(file, block);
    }

    private static List<String> conditions(
        SourceMetadataScanner scanner, AnalysisContext.Framework framework,
        SourceFile file, String block) {
      return framework == AnalysisContext.Framework.QUARKUS
          ? scanner.conditionAnnotations(block) : scanner.conditionAnnotations(file, block);
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
        SourceFile file) {
      String block = file.classAnnotationBlock();
      for (MethodInfo method : SourceMetadataScanner.methods(file)) {
        if (isProducer(scanner, framework, file, method) && !method.returnType().isBlank()) {
          result.add(producerCandidate(scanner, framework, file, block, method));
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
        MethodInfo method) {
      String candidateId = file.typeName() + "#" + method.name() + "("
          + method.parameters().replaceAll("\\s+", " ").trim() + ")";
      String producerType = scanner.resolveReferenceType(method.returnType(), file).orElse("");
      return new BeanCandidate(
          file,
          Set.of(),
          "producer",
          candidateId,
          producerType,
          mergeAnnotationValues(scanner, framework, file, block, method, "qualifier"),
          mergeAnnotationValues(scanner, framework, file, block, method, "profile"),
          mergeAnnotationValues(scanner, framework, file, block, method, "condition"),
          scanner.hasAnnotation(block, "Alternative")
              || scanner.hasAnnotation(method.annotationBlock(), "Alternative"),
          producerPrimary(scanner, framework, file, block, method),
          method.name(),
          method.returnType(),
          method.parameters());
    }

    private static List<String> mergeAnnotationValues(
        SourceMetadataScanner scanner,
        AnalysisContext.Framework framework,
        SourceFile file,
        String classBlock,
        MethodInfo method,
        String kind) {
      return scanner.mergeValues(
          annotationValues(scanner, framework, file, classBlock, kind),
          annotationValues(scanner, framework, file, method.annotationBlock(), kind));
    }

    private static List<String> annotationValues(
        SourceMetadataScanner scanner,
        AnalysisContext.Framework framework,
        SourceFile file,
        String block,
        String kind) {
      if (framework == AnalysisContext.Framework.QUARKUS) {
        return switch (kind) {
          case "qualifier" -> scanner.qualifierNames(block);
          case "profile" -> scanner.profileValues(block);
          default -> scanner.conditionAnnotations(block);
        };
      }
      return switch (kind) {
        case "qualifier" -> scanner.qualifierNames(file, block);
        case "profile" -> scanner.profileValues(file, block);
        default -> scanner.conditionAnnotations(file, block);
      };
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
