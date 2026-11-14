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
final class FrameworkInjectionScanning {
  private FrameworkInjectionScanning() {}

    static List<InjectionPoint> injectionPoints(
        SourceMetadataScanner scanner, AnalysisContext.Framework framework) {
      List<InjectionPoint> result = new ArrayList<>();
      for (SourceFile file : scanner.files()) {
        result.addAll(constructorInjectionPoints(scanner, framework, file));
        if (componentOrPath(scanner, framework, file)) {
          result.addAll(fieldInjectionPoints(scanner, framework, file));
          result.addAll(methodInjectionPoints(scanner, framework, file));
        }
      }
      return List.copyOf(result);
    }

    private static List<InjectionPoint> constructorInjectionPoints(
        SourceMetadataScanner scanner, AnalysisContext.Framework framework, SourceFile file) {
      Pattern constructor = Pattern.compile(
          "(?s)((?:(?:@[A-Za-z_][\\w.]*(?:\\([^)]*\\))?\\s*)*))"
              + "(?:public|protected|private)?\\s*"
              + Pattern.quote(file.simpleTypeName())
              + "\\s*\\(([^)]*)\\)\\s*\\{");
      String maskedSource = scanner.maskAnnotationArguments(file.structuralSource());
      Matcher matcher = constructor.matcher(maskedSource);
      List<ConstructorInfo> constructors = new ArrayList<>();
      while (matcher.find()) {
        constructors.add(new ConstructorInfo(
            file.source().substring(matcher.start(1), matcher.end(1)),
            file.source().substring(matcher.start(2), matcher.end(2)),
            matcher.start()));
      }
      boolean explicit = constructors.stream()
          .anyMatch(item -> scanner.hasInjectionAnnotation(item.annotations()));
      List<InjectionPoint> result = new ArrayList<>();
      for (ConstructorInfo constructorInfo : constructors) {
        boolean annotated = scanner.hasInjectionAnnotation(constructorInfo.annotations());
        boolean selected = framework == AnalysisContext.Framework.QUARKUS
            ? annotated
            : componentOrPath(scanner, framework, file)
                && (annotated || (!explicit && constructors.size() == 1));
        if (!selected) {
          continue;
        }
        List<Parameter> parameters = scanner.parameters(
            framework, file, constructorInfo.parameters());
        if (!parameters.isEmpty()) {
          result.add(new InjectionPoint(
              file,
              parameters,
              file.lineAt(constructorInfo.start()),
              constructorInfo.start(),
              scanner.typeContaining(file, constructorInfo.start()),
              "<init>",
              "constructor",
              scanner.qualifierNames(constructorInfo.annotations())));
        }
      }
      return result;
    }

    private static boolean componentOrPath(
        SourceMetadataScanner scanner, AnalysisContext.Framework framework, SourceFile file) {
      return framework == AnalysisContext.Framework.QUARKUS
          ? scanner.cdiBean(file.classAnnotationBlock())
              || scanner.hasAnnotation(file.classAnnotationBlock(), "Path")
          : scanner.springComponent(file);
    }

    private static List<InjectionPoint> fieldInjectionPoints(
        SourceMetadataScanner scanner, AnalysisContext.Framework framework, SourceFile file) {
      List<InjectionPoint> result = new ArrayList<>();
      Matcher matcher = SourceMetadataScanner.INJECT_FIELD.matcher(file.structuralSource());
      while (matcher.find()) {
        String annotations = matcher.group(1);
        if (!scanner.hasInjectionAnnotation(annotations)) {
          continue;
        }
        List<String> qualifiers = scanner.injectionQualifiers(
            annotations, matcher.group(3), framework == AnalysisContext.Framework.QUARKUS, false);
        result.add(new InjectionPoint(
            file,
            List.of(new Parameter(matcher.group(2), matcher.group(3), qualifiers)),
            file.lineAt(matcher.start()),
            matcher.start(),
            scanner.typeContaining(file, matcher.start()),
            matcher.group(3),
            "field",
            qualifiers));
      }
      return result;
    }

    private static List<InjectionPoint> methodInjectionPoints(
        SourceMetadataScanner scanner, AnalysisContext.Framework framework, SourceFile file) {
      List<InjectionPoint> result = new ArrayList<>();
      for (MethodInfo method : SourceMetadataScanner.methods(file)) {
        if (method.name().equals(file.simpleTypeName())
            || !scanner.hasInjectionAnnotation(method.annotationBlock())) {
          continue;
        }
        List<Parameter> parameters = scanner.parameters(framework, file, method.parameters());
        if (!parameters.isEmpty()) {
          result.add(new InjectionPoint(
              file,
              parameters,
              method.line(),
              method.start(),
              scanner.typeContaining(file, method.start()),
               method.name(),
               "method",
               scanner.injectionQualifiers(
                   method.annotationBlock(), method.name(), framework == AnalysisContext.Framework.QUARKUS,
                   true)));
        }
      }
      return result;
    }

    private record ConstructorInfo(String annotations, String parameters, int start) {}

}
