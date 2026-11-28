package com.projectlightpath.analysis.java;

import java.io.IOException;
import com.projectlightpath.analysis.java.SourceMetadataScannerState.BeanCandidate;
import com.projectlightpath.analysis.java.SourceMetadataScannerState.InjectionPoint;
import com.projectlightpath.analysis.java.SourceMetadataScannerState.MethodInfo;
import com.projectlightpath.analysis.java.SourceMetadataScannerState.Parameter;
import com.projectlightpath.analysis.java.SourceMetadataScannerState.Route;
import com.projectlightpath.analysis.java.SourceMetadataScannerState.SourceFile;
import com.projectlightpath.analysis.java.SourceMetadataScannerState.TypeInfo;

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
final class FrameworkRouteScanning {
  private FrameworkRouteScanning() {}


    private record RouteMethodRequest(
        SourceMetadataScanner scanner,
        AnalysisContext.Framework framework,
        SourceFile file,
        String classBlock,
        boolean quarkusClassPath,
        List<String> classPaths,
        List<String> classHttpMethods,
        MethodInfo method,
        Set<String> seen) {}

    private record RouteAnnotationRequest(
        SourceMetadataScanner scanner,
        AnalysisContext.Framework framework,
        SourceFile file,
        String classBlock,
        boolean quarkusClassPath,
        List<String> classPaths,
        List<String> classHttpMethods,
        MethodInfo method,
        String annotationName,
        Set<String> seen,
        List<JavacSourceSymbolIndex.AnnotationInfo> classMirrors,
        List<JavacSourceSymbolIndex.AnnotationInfo> methodMirrors) {}

    private record RouteVariantsRequest(
        SourceMetadataScanner scanner,
        AnalysisContext.Framework framework,
        SourceFile file,
        String classBlock,
        MethodInfo method,
        String annotationName,
        List<String> httpMethods,
        List<String> classPaths,
        List<String> methodPaths,
        List<String> consumes,
        List<String> produces,
        List<String> headers,
        List<String> parameters,
        Set<String> seen) {}

    static List<Route> routes(
        SourceMetadataScanner scanner, AnalysisContext.Framework framework) {
      List<Route> result = new ArrayList<>();
      Set<String> seen = new LinkedHashSet<>();
      for (SourceFile file : scanner.files()) {
        result.addAll(routesForFile(scanner, framework, file, seen));
      }
      return List.copyOf(result);
    }

    private static List<Route> routesForFile(
        SourceMetadataScanner scanner,
        AnalysisContext.Framework framework,
        SourceFile file,
        Set<String> seen) {
      JavacFrameworkAnnotations javac = JavacFrameworkAnnotations.of(scanner);
      String classBlock = file.classAnnotationBlock();
      boolean quarkusClassPath = scanner.hasAnnotation(classBlock, "Path");
      boolean springController = framework == AnalysisContext.Framework.SPRING_BOOT
          && scanner.canonicalSpringAnnotation(file, classBlock, "RestController");
      List<JavacSourceSymbolIndex.AnnotationInfo> classMirrors =
          javac.typeAnnotations(file).orElse(List.of());
      if (!classIsRouteable(
          scanner, javac, framework, file, classBlock, quarkusClassPath, springController,
          classMirrors)) {
        return List.of();
      }
      List<String> classPaths = classPaths(
          scanner, javac, framework, file, classBlock, classMirrors);
      if (classPaths.isEmpty()) {
        classPaths = List.of("");
      }
      List<String> classHttpMethods = framework == AnalysisContext.Framework.SPRING_BOOT
          ? JavacFrameworkAnnotationValues.requestMappingHttpMethods(classMirrors, file, classBlock)
              .orElseGet(() -> scanner.httpMethods(file, classBlock, "RequestMapping"))
          : SourceMetadataScanner.SUPPORTED_HTTP_METHODS;
      List<Route> result = new ArrayList<>();
      TypeInfo topLevelType = scanner.innermostType(file, file.typeDeclarationStart());
      for (MethodInfo method : SourceMetadataScanner.methods(file)) {
        TypeInfo methodType = scanner.innermostType(
            file, SourceMetadataScanner.declarationStart(file, method.start()));
        if (topLevelType == null || methodType == null
            || methodType.start() != topLevelType.start()) {
          continue;
        }
        result.addAll(routesForMethod(new RouteMethodRequest(
            scanner,
            framework,
            file,
            classBlock,
            quarkusClassPath,
            classPaths,
            classHttpMethods,
            method,
            seen)));
      }
      return result;
    }

    private static boolean classIsRouteable(
        SourceMetadataScanner scanner,
        JavacFrameworkAnnotations javac,
        AnalysisContext.Framework framework,
        SourceFile file,
        String classBlock,
        boolean quarkusClassPath,
        boolean springController,
        List<JavacSourceSymbolIndex.AnnotationInfo> classMirrors) {
      if (framework == AnalysisContext.Framework.SPRING_BOOT && !springController) {
        return false;
      }
      List<String> classPaths = classPaths(
          scanner, javac, framework, file, classBlock, classMirrors);
      if (framework == AnalysisContext.Framework.QUARKUS
          && quarkusClassPath && classPaths.isEmpty()) {
        return false;
      }
      return framework != AnalysisContext.Framework.SPRING_BOOT
          || !scanner.canonicalSpringAnnotation(file, classBlock, "RequestMapping")
          || !classPaths.isEmpty();
    }

    private static List<String> classPaths(
        SourceMetadataScanner scanner,
        JavacFrameworkAnnotations javac,
        AnalysisContext.Framework framework,
        SourceFile file,
        String classBlock,
        List<JavacSourceSymbolIndex.AnnotationInfo> classMirrors) {
      return framework == AnalysisContext.Framework.QUARKUS
          ? JavacFrameworkAnnotationValues.pathValues(classMirrors, classBlock, "Path")
              .orElseGet(() -> scanner.pathValues(classBlock, "Path"))
          : JavacFrameworkAnnotationValues.mappingPaths(classMirrors, file, classBlock, "RequestMapping")
              .orElseGet(() -> scanner.mappingPaths(file, classBlock, "RequestMapping"));
    }

    private static List<Route> routesForMethod(RouteMethodRequest request) {
      SourceMetadataScanner scanner = request.scanner();
      AnalysisContext.Framework framework = request.framework();
      SourceFile file = request.file();
      String classBlock = request.classBlock();
      boolean quarkusClassPath = request.quarkusClassPath();
      List<String> classPaths = request.classPaths();
      List<String> classHttpMethods = request.classHttpMethods();
      MethodInfo method = request.method();
      Set<String> seen = request.seen();
      JavacFrameworkAnnotations javac = JavacFrameworkAnnotations.of(scanner);
      List<JavacSourceSymbolIndex.AnnotationInfo> classMirrors =
          javac.typeAnnotations(file).orElse(List.of());
      List<JavacSourceSymbolIndex.AnnotationInfo> methodMirrors =
          javac.memberAnnotations(
              // #90: the mirror lookup shares the declarationStart
              // normalization the routesForFile filter above applies,
              // so the pair cannot diverge for a member declared
              // after a nested type.
              scanner.ownerTypeFor(file, method), method.name(), "method",
              JavacFrameworkAnnotations.parameterArity(method.parameters()))
          .orElse(List.of());
      List<Route> result = new ArrayList<>();
      for (String annotationName : scanner.routeAnnotations(
          file, method.annotationBlock(), framework)) {
        result.addAll(routesForAnnotation(new RouteAnnotationRequest(
            scanner,
            framework,
            file,
            classBlock,
            quarkusClassPath,
            classPaths,
            classHttpMethods,
            method,
            annotationName,
            seen,
            classMirrors,
            methodMirrors)));
      }
      return result;
    }

    private static List<Route> routesForAnnotation(RouteAnnotationRequest request) {
      SourceMetadataScanner scanner = request.scanner();
      AnalysisContext.Framework framework = request.framework();
      SourceFile file = request.file();
      String classBlock = request.classBlock();
      List<String> classPaths = request.classPaths();
      List<String> classHttpMethods = request.classHttpMethods();
      MethodInfo method = request.method();
      String annotationName = request.annotationName();
      Set<String> seen = request.seen();
      List<JavacSourceSymbolIndex.AnnotationInfo> classMirrors = request.classMirrors();
      List<JavacSourceSymbolIndex.AnnotationInfo> methodMirrors = request.methodMirrors();
      List<String> methodHttpMethods = "RequestMapping".equals(annotationName)
          ? JavacFrameworkAnnotationValues.requestMappingHttpMethods(methodMirrors, file, method.annotationBlock())
              .orElseGet(() -> scanner.httpMethods(
                  file, method.annotationBlock(), annotationName))
          : scanner.httpMethods(file, method.annotationBlock(), annotationName);
      List<String> httpMethods = scanner.intersectHttpMethods(methodHttpMethods, classHttpMethods);
      if (httpMethods.isEmpty()) {
        return List.of();
      }
      List<String> methodPaths = methodPaths(scanner, framework, file, method,
          annotationName, methodMirrors);
      if (!methodPathsAllowed(request, methodPaths)) {
        return List.of();
      }
      if (methodPaths.isEmpty()) {
        methodPaths = List.of("");
      }
      List<String> consumes = mediaValues(request, "consumes");
      List<String> produces = mediaValues(request, "produces");
      List<String> headers = framework == AnalysisContext.Framework.SPRING_BOOT
          ? scanner.mergeValues(
              JavacFrameworkAnnotationValues.values(classMirrors, file, classBlock, "RequestMapping", "headers", true)
                  .orElseGet(() -> scanner.values(
                      file, classBlock, "RequestMapping", "headers")),
              JavacFrameworkAnnotationValues.values(methodMirrors, file, method.annotationBlock(),
                  annotationName, "headers", true)
                  .orElseGet(() -> scanner.values(
                      file, method.annotationBlock(), annotationName, "headers")))
          : List.of();
      List<String> parameters = framework == AnalysisContext.Framework.SPRING_BOOT
          ? scanner.mergeValues(
              JavacFrameworkAnnotationValues.values(classMirrors, file, classBlock, "RequestMapping", "params", true)
                  .orElseGet(() -> scanner.values(
                      file, classBlock, "RequestMapping", "params")),
              JavacFrameworkAnnotationValues.values(methodMirrors, file, method.annotationBlock(),
                  annotationName, "params", true)
                  .orElseGet(() -> scanner.values(
                      file, method.annotationBlock(), annotationName, "params")))
          : List.of();
      return routeVariants(new RouteVariantsRequest(
          scanner,
          framework,
          file,
          classBlock,
          method,
          annotationName,
          httpMethods,
          classPaths,
          methodPaths,
          consumes,
          produces,
          headers,
          parameters,
          seen));
    }

    private static List<String> methodPaths(
        SourceMetadataScanner scanner,
        AnalysisContext.Framework framework,
        SourceFile file,
        MethodInfo method,
        String annotationName,
        List<JavacSourceSymbolIndex.AnnotationInfo> methodMirrors) {
      return framework == AnalysisContext.Framework.QUARKUS
          ? JavacFrameworkAnnotationValues.pathValues(methodMirrors, method.annotationBlock(), "Path")
              .orElseGet(() -> scanner.pathValues(method.annotationBlock(), "Path"))
          : JavacFrameworkAnnotationValues.mappingPaths(methodMirrors, file, method.annotationBlock(), annotationName)
              .orElseGet(() -> scanner.mappingPaths(
                  file, method.annotationBlock(), annotationName));
    }

    private static boolean methodPathsAllowed(
        RouteAnnotationRequest request, List<String> methodPaths) {
      SourceMetadataScanner scanner = request.scanner();
      AnalysisContext.Framework framework = request.framework();
      MethodInfo method = request.method();
      String annotationName = request.annotationName();
      boolean quarkusExplicitPath = framework == AnalysisContext.Framework.QUARKUS
          && scanner.hasAnnotation(method.annotationBlock(), "Path");
      if (framework == AnalysisContext.Framework.QUARKUS && quarkusExplicitPath
          && methodPaths.isEmpty()) {
        return false;
      }
      if (framework == AnalysisContext.Framework.SPRING_BOOT && methodPaths.isEmpty()
          && JavacFrameworkAnnotationValues.hasExplicitMappingPath(
              request.methodMirrors(), request.file(), method.annotationBlock(), annotationName)
              .orElseGet(() -> scanner.hasExplicitMappingPath(
                  request.file(), method.annotationBlock(), annotationName))) {
        return false;
      }
      return framework != AnalysisContext.Framework.QUARKUS || request.quarkusClassPath();
    }

    private static List<String> mediaValues(
        RouteAnnotationRequest request, String attribute) {
      SourceMetadataScanner scanner = request.scanner();
      AnalysisContext.Framework framework = request.framework();
      String classBlock = request.classBlock();
      String methodBlock = request.method().annotationBlock();
      String annotationName = request.annotationName();
      String classAnnotation = attribute.equals("consumes") ? "Consumes" : "Produces";
      List<String> classValues = framework == AnalysisContext.Framework.QUARKUS
          ? JavacFrameworkAnnotationValues.values(
              request.classMirrors(), request.file(), classBlock, classAnnotation, null, false)
              .orElseGet(() -> scanner.mediaValues(classBlock, classAnnotation, null))
          : JavacFrameworkAnnotationValues.mediaValues(
              request.classMirrors(), request.file(), classBlock, "RequestMapping", attribute)
              .orElseGet(() -> scanner.mediaValues(
                  request.file(), classBlock, "RequestMapping", attribute));
      List<String> methodValues = framework == AnalysisContext.Framework.QUARKUS
          ? JavacFrameworkAnnotationValues.values(
              request.methodMirrors(), request.file(), methodBlock, classAnnotation, null, false)
              .orElseGet(() -> scanner.mediaValues(methodBlock, classAnnotation, null))
          : JavacFrameworkAnnotationValues.mediaValues(
              request.methodMirrors(), request.file(), methodBlock, annotationName, attribute)
              .orElseGet(() -> scanner.mediaValues(
                  request.file(), methodBlock, annotationName, attribute));
      return scanner.overrideValues(classValues, methodValues);
    }

    private static List<Route> routeVariants(RouteVariantsRequest request) {
      SourceMetadataScanner scanner = request.scanner();
      AnalysisContext.Framework framework = request.framework();
      SourceFile file = request.file();
      String classBlock = request.classBlock();
      MethodInfo method = request.method();
      String annotationName = request.annotationName();
      List<String> httpMethods = request.httpMethods();
      List<String> classPaths = request.classPaths();
      List<String> methodPaths = request.methodPaths();
      List<String> consumes = request.consumes();
      List<String> produces = request.produces();
      List<String> headers = request.headers();
      List<String> parameters = request.parameters();
      Set<String> seen = request.seen();
      List<Route> result = new ArrayList<>();
      for (String httpMethod : httpMethods) {
        for (String classPath : classPaths) {
          for (String methodPath : methodPaths) {
            String path = SourceMetadataScanner.joinPath(classPath, methodPath);
            String key = SourceMetadataScanner.routeKey(
                file, method, httpMethod, path, consumes, produces, headers, parameters);
            if (seen.add(key)) {
              result.add(new Route(
                  file,
                  method,
                  httpMethod,
                  path,
                  consumes,
                  produces,
                  headers,
                  parameters,
                  scanner.routeSymbols(
                      file, classBlock, method.annotationBlock(), framework, annotationName)));
            }
          }
        }
      }
      return result;
    }


}
