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
      JavacFrameworkAnnotations javac = JavacFrameworkAnnotations.of(scanner);
      List<InjectionPoint> result = new ArrayList<>();
      for (SourceFile file : scanner.files()) {
        result.addAll(constructorInjectionPoints(scanner, framework, file, javac));
        if (componentOrPath(scanner, framework, file)) {
          result.addAll(fieldInjectionPoints(scanner, framework, file, javac));
          result.addAll(methodInjectionPoints(scanner, framework, file, javac));
        }
      }
      return List.copyOf(result);
    }

    private static List<InjectionPoint> constructorInjectionPoints(
        SourceMetadataScanner scanner, AnalysisContext.Framework framework, SourceFile file,
        JavacFrameworkAnnotations javac) {
      Pattern constructor = Pattern.compile(
          "(?s)((?:(?:@[A-Za-z_][\\w.]*(?:\\([^)]*\\))?\\s*)*))"
              + "(?:public|protected|private)?\\s*"
              + Pattern.quote(file.simpleTypeName())
              + "\\s*\\(([^)]*)\\)\\s*(?:throws\\s+[^\\{]+)?\\s*\\{");
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
        String owner = scanner.typeContaining(file, constructorInfo.start());
        List<Parameter> parameters = injectionParameters(
            scanner, javac, framework, file, owner, file.simpleTypeName(),
            "constructor", constructorInfo.parameters());
        if (!parameters.isEmpty()) {
          result.add(new InjectionPoint(
              file,
              parameters,
              file.lineAt(constructorInfo.start()),
              constructorInfo.start(),
              owner,
              "<init>",
              "constructor",
              JavacFrameworkQualifierFamilies.qualifiers(
                  file, constructorInfo.annotations(),
                  javac.memberAnnotations(
                      owner, file.simpleTypeName(), "constructor",
                      JavacFrameworkAnnotations.parameterArity(constructorInfo.parameters()))
                      .orElse(List.of()),
                  false)
                  .orElseGet(() -> scanner.qualifierNames(constructorInfo.annotations()))));
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
        SourceMetadataScanner scanner, AnalysisContext.Framework framework, SourceFile file,
        JavacFrameworkAnnotations javac) {
      List<InjectionPoint> result = new ArrayList<>();
      Matcher matcher = SourceMetadataScanner.INJECT_FIELD.matcher(file.structuralSource());
      while (matcher.find()) {
        String annotations = matcher.group(1);
        if (!scanner.hasInjectionAnnotation(annotations)) {
          continue;
        }
        String owner = scanner.typeContaining(file, matcher.start());
        List<String> qualifiers = JavacFrameworkQualifierFamilies.injectionQualifiers(
            file, annotations,
            javac.memberAnnotations(owner, matcher.group(3), "field", -1)
                .orElse(List.of()),
            matcher.group(3), framework == AnalysisContext.Framework.QUARKUS, false)
            .orElseGet(() -> scanner.injectionQualifiers(
                annotations, matcher.group(3),
                framework == AnalysisContext.Framework.QUARKUS, false));
        result.add(new InjectionPoint(
            file,
            List.of(new Parameter(matcher.group(2), matcher.group(3), qualifiers)),
            file.lineAt(matcher.start()),
            matcher.start(),
            owner,
            matcher.group(3),
            "field",
            qualifiers));
      }
      return result;
    }

    private static List<InjectionPoint> methodInjectionPoints(
        SourceMetadataScanner scanner, AnalysisContext.Framework framework, SourceFile file,
        JavacFrameworkAnnotations javac) {
      List<InjectionPoint> result = new ArrayList<>();
      for (MethodInfo method : SourceMetadataScanner.methods(file)) {
        if (method.name().equals(file.simpleTypeName())
            || !scanner.hasInjectionAnnotation(method.annotationBlock())) {
          continue;
        }
        String owner = scanner.typeContaining(file, method.start());
        List<Parameter> parameters = injectionParameters(
            scanner, javac, framework, file, owner, method.name(),
            "method", method.parameters());
        if (!parameters.isEmpty()) {
          result.add(new InjectionPoint(
              file,
              parameters,
              method.line(),
              method.start(),
              owner,
               method.name(),
               "method",
               JavacFrameworkQualifierFamilies.injectionQualifiers(
                   file, method.annotationBlock(),
                   javac.memberAnnotations(
                       owner, method.name(), "method",
                       JavacFrameworkAnnotations.parameterArity(method.parameters()))
                       .orElse(List.of()),
                   method.name(), framework == AnalysisContext.Framework.QUARKUS, true)
                   .orElseGet(() -> scanner.injectionQualifiers(
                       method.annotationBlock(), method.name(),
                       framework == AnalysisContext.Framework.QUARKUS, true))));
        }
      }
      return result;
    }

    /** Parses injection parameters, repairing qualifiers from the index. */
    private static List<Parameter> injectionParameters(
        SourceMetadataScanner scanner,
        JavacFrameworkAnnotations javac,
        AnalysisContext.Framework framework,
        SourceFile file,
        String owner,
        String memberName,
        String kind,
        String rawParameters) {
      List<Parameter> textual = scanner.parameters(framework, file, rawParameters);
      if (textual.isEmpty()) {
        return textual;
      }
      boolean spring = framework == AnalysisContext.Framework.SPRING_BOOT;
      List<String> raws = SourceMetadataScanner.splitArguments(rawParameters.trim());
      Optional<List<List<JavacSourceSymbolIndex.AnnotationInfo>>> perParameter =
          javac.parameterAnnotations(
              owner, memberName, kind,
              JavacFrameworkAnnotations.parameterArity(rawParameters), textual.size());
      if (perParameter.isEmpty() || raws.size() != textual.size()) {
        return textual;
      }
      List<Parameter> result = new ArrayList<>();
      for (int index = 0; index < textual.size(); index++) {
        Parameter parameter = textual.get(index);
        String raw = raws.get(index);
        List<String> qualifiers = JavacFrameworkQualifierFamilies.qualifiers(
            file, raw, perParameter.orElseThrow().get(index), spring)
            .orElseGet(() -> spring
                ? scanner.qualifierNames(file, raw)
                : scanner.qualifierNames(raw));
        result.add(new Parameter(parameter.type(), parameter.name(), qualifiers));
      }
      return List.copyOf(result);
    }

    private record ConstructorInfo(String annotations, String parameters, int start) {}

}
