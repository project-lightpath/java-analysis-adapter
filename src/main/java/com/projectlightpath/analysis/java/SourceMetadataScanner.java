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

/**
 * Small, source-backed metadata reader for the bounded framework contract.
 *
 * <p>This is intentionally not a Java compiler or a general-purpose parser.
 * It only recognizes the annotation and descriptor shapes needed by the
 * checked-in Quarkus and Spring contracts. Anything outside those shapes is
 * left for the unresolved path rather than guessed.
 */
final class SourceMetadataScanner extends SourceMetadataScannerFrameworkSupport {

  SourceMetadataScanner(AnalysisContext context) {
    super(context);
  }


  List<Route> routes(AnalysisContext.Framework framework) {
    return FrameworkScanning.routes(this, framework);
  }

  List<InjectionPoint> injectionPoints(AnalysisContext.Framework framework) {
    return FrameworkScanning.injectionPoints(this, framework);
  }

  List<BeanCandidate> beanCandidates(AnalysisContext.Framework framework) {
    return FrameworkScanning.beanCandidates(this, framework);
  }

  List<DynamicFeature> dynamicFeatures() {
    return new DynamicScanning().dynamicFeatures();
  }

  /** Finds the interface array associated with one proxy construction site. */
static List<String> proxyInterfaceNames(String source, int proxyOffset) {
    int opening = source.indexOf('(', proxyOffset);
    if (opening < 0) {
      return List.of();
    }
    int closing = matchingDelimiter(source, opening, '(', ')');
    if (closing < 0) {
      return List.of();
    }
    List<String> arguments = splitTopLevelArguments(source.substring(opening + 1, closing));
    if (arguments.size() < 2) {
      return List.of();
    }
    Matcher interfaces = PROXY_INTERFACES.matcher(arguments.get(1));
    if (!interfaces.find()) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    Matcher literal = CLASS_LITERAL.matcher(interfaces.group(1));
    while (literal.find()) {
      result.add(literal.group(1));
    }
    return List.copyOf(result);
  }

  /** Finds each executable reflection marker without treating strings or comments as code. */
  List<DynamicSite> reflectionSites(SourceFile file) {
    String source = file.structuralSource();
    String dynamicSource = maskStringLiterals(source);
    List<DynamicSite> result = new ArrayList<>();
    addClassForNameSites(result, dynamicSource, source, file);
    if (STATIC_FOR_NAME_IMPORT.matcher(dynamicSource).find()) {
      addStaticForNameSites(result, dynamicSource, source, file);
    }
    addReceiverCallSites(result, dynamicSource, source, file, RECEIVER_LOAD_CLASS, "ClassLoader");
    addReceiverCallSites(result, dynamicSource, source, file, RECEIVER_METHOD_LOOKUP, "Class");
    addReceiverCallSites(result, dynamicSource, source, file, RECEIVER_CONSTRUCTOR_LOOKUP, "Class");
    List<ChainedReceiver> supportedChainedReceivers = chainedReceivers(dynamicSource).stream()
        .filter(receiver -> isSupportedChainedReceiver(file, dynamicSource, receiver))
        .toList();
    addChainedLookupSites(
        result, dynamicSource, source, supportedChainedReceivers,
        "getDeclaredMethod", "getMethod", "getDeclaredConstructor", "getConstructor");
    addJdkOwnerSites(result, dynamicSource, source, file, CONSTRUCTOR_NEW_INSTANCE,
        "Constructor.newInstance", "java.lang.reflect.Constructor");
    addJdkOwnerSites(result, dynamicSource, source, file, CLASS_NEW_INSTANCE,
        "Class.newInstance", "java.lang.Class");

    addReceiverSites(result, dynamicSource, source, file, "newInstance", "Constructor", "Constructor.newInstance");
    addReceiverSites(result, dynamicSource, source, file, "newInstance", "Class", "Class.newInstance");

    Matcher invoke = METHOD_INVOKE.matcher(dynamicSource);
    while (invoke.find()) {
      if (isBareReceiver(dynamicSource, invoke.start(1))
          && (isDeclaredVariableBefore(file, "Method", invoke.group(1), invoke.start())
              || isInvocationHandlerParameterBefore(file, invoke.group(1), invoke.start()))) {
        addSite(result, source, invoke.start(), "Method.invoke");
      }
    }
    addChainedMethodInvokeSites(result, dynamicSource, source, supportedChainedReceivers);
    addChainedConstructorSites(result, dynamicSource, source, supportedChainedReceivers);
    addJdkOwnerSites(result, dynamicSource, source, file, METHOD_TYPE_INVOKE,
        "Method.invoke", "java.lang.reflect.Method");
    addThisReceiverSites(result, dynamicSource, source, file);
    addInlineProxyInvocationSites(result, dynamicSource, source, file);
    result.sort(Comparator.comparingInt(DynamicSite::offset).thenComparing(DynamicSite::symbol));
    return result;
  }

  void addInlineProxyInvocationSites(
      List<DynamicSite> result, String dynamicSource, String source, SourceFile file) {
    new DynamicScanning().addInlineProxyInvocationSites(result, dynamicSource, source, file);
  }

  /** Reflection and proxy scans kept separate from the source-model facade. */
  final class DynamicScanning {
    private List<DynamicFeature> dynamicFeatures() {
      List<DynamicFeature> result = new ArrayList<>();
      for (SourceFile file : files()) {
        result.addAll(reflectionFeatures(file));
        result.addAll(proxyFeatures(file));
      }
      return List.copyOf(result);
    }

    private List<DynamicFeature> reflectionFeatures(SourceFile file) {
      List<DynamicFeature> result = new ArrayList<>();
      for (DynamicSite site : reflectionSites(file)) {
        MethodInfo caller = methodContaining(file, site.offset());
        result.add(new DynamicFeature(
            DynamicFeature.Kind.REFLECTION,
            new MethodSymbol(typeContaining(file, site.offset()), caller.name(), ""),
            site.target(),
            file.displayPath(),
            List.of(site.symbol()),
            "Open-world reflection APIs do not identify a unique runtime target.",
            file.lineAt(site.offset()),
            site.offset()));
      }
      return result;
    }

    private List<DynamicFeature> proxyFeatures(SourceFile file) {
      List<DynamicFeature> result = new ArrayList<>();
      String source = maskStringLiterals(file.structuralSource());
      for (int proxyOffset : proxyCallOffsets(file, source)) {
        result.addAll(proxyFeaturesAt(file, proxyOffset));
      }
      return result;
    }

    private List<DynamicFeature> proxyFeaturesAt(SourceFile file, int proxyOffset) {
      List<String> interfaceNames = proxyInterfaceNames(file.structuralSource(), proxyOffset);
      MethodInfo caller = methodContaining(file, proxyOffset);
      if (interfaceNames.isEmpty()) {
        return List.of(dynamicProxyFeature(file, caller, "unknown#unknown", "", proxyOffset));
      }
      List<DynamicFeature> result = new ArrayList<>();
      for (String interfaceName : interfaceNames) {
        result.addAll(proxyFeaturesForInterface(file, caller, interfaceName, proxyOffset));
      }
      return result;
    }

    private List<DynamicFeature> proxyFeaturesForInterface(
        SourceFile file, MethodInfo caller, String interfaceName, int proxyOffset) {
      String qualified = resolveContractType(interfaceName, file)
          .orElseGet(() -> qualify(interfaceName, file.packageName()));
      Optional<SourceFile> interfaceFile = files().stream()
          .filter(candidate -> candidate.typeName().equals(qualified))
          .findFirst();
      List<MethodInfo> interfaceMethods = interfaceFile
          .map(SourceMetadataScanner::methods)
          .orElse(List.of());
      if (interfaceMethods.isEmpty()) {
        return List.of(dynamicProxyFeature(file, caller, qualified + "#unknown", "", proxyOffset));
      }
      return interfaceMethods.stream()
          .map(method -> dynamicProxyFeature(
              file,
              caller,
              qualified + "#" + method.name(),
              methodDescriptor(method),
              proxyOffset))
          .toList();
    }

    private List<Integer> proxyCallOffsets(SourceFile file, String source) {
      List<Integer> result = new ArrayList<>();
      addQualifiedProxyOffsets(result, file, source);
      addThisProxyOffsets(result, file, source);
      addQualifiedThisProxyOffsets(result, file, source);
      addStaticProxyOffsets(result, file, source);
      return result.stream().distinct().sorted().toList();
    }

    private void addQualifiedProxyOffsets(List<Integer> result, SourceFile file, String source) {
      Matcher matcher = PROXY_CALL.matcher(source);
      while (matcher.find()) {
        if (isBareReceiver(source, matcher.start(1))
            && (isJdkApiOwner(file, matcher.group(1), matcher.start(1), "java.lang.reflect.Proxy")
                || isDeclaredVariableBefore(file, "Proxy", matcher.group(1), matcher.start(1)))) {
          result.add(matcher.start());
        }
      }
    }

    private void addThisProxyOffsets(List<Integer> result, SourceFile file, String source) {
      Matcher matcher = THIS_PROXY_CALL.matcher(source);
      while (matcher.find()) {
        if (isBareReceiver(source, matcher.start())
            && hasVisibleField(file, "Proxy", matcher.group(1), matcher.start(1))) {
          result.add(matcher.start());
        }
      }
    }

    private void addQualifiedThisProxyOffsets(
        List<Integer> result, SourceFile file, String source) {
      Matcher matcher = QUALIFIED_THIS_PROXY_CALL.matcher(source);
      while (matcher.find()) {
        String owner = qualifiedThisOwner(file, matcher.group(1), matcher.start(1));
        if (owner != null
            && hasVisibleField(file, "Proxy", matcher.group(2), matcher.start(2), owner)) {
          result.add(matcher.start());
        }
      }
    }

    private void addStaticProxyOffsets(List<Integer> result, SourceFile file, String source) {
      if (!STATIC_PROXY_IMPORT.matcher(source).find()) {
        return;
      }
      Matcher matcher = UNQUALIFIED_PROXY_CALL.matcher(source);
      while (matcher.find()) {
        int prefix = matcher.start() - 1;
        while (prefix >= 0 && Character.isWhitespace(source.charAt(prefix))) {
          prefix--;
        }
        if ((prefix < 0 || source.charAt(prefix) != '.')
            && !hasVisibleMethodNamed(file, matcher.start(), "newProxyInstance")) {
          result.add(matcher.start());
        }
      }
    }

    private void addInlineProxyInvocationSites(
        List<DynamicSite> result, String dynamicSource, String source, SourceFile file) {
      for (int proxyOffset : proxyCallOffsets(file, dynamicSource)) {
        addInlineProxyInvocationSite(result, dynamicSource, source, proxyOffset);
      }
    }

    private void addInlineProxyInvocationSite(
        List<DynamicSite> result, String dynamicSource, String source, int proxyOffset) {
      int opening = dynamicSource.indexOf('(', proxyOffset);
      if (opening < 0) {
        return;
      }
      int closing = matchingDelimiter(dynamicSource, opening, '(', ')');
      if (closing < 0) {
        return;
      }
      List<String> arguments = splitTopLevelArguments(dynamicSource.substring(opening + 1, closing));
      if (arguments.size() >= 3) {
        addLambdaInvocationSites(result, dynamicSource, source, opening, arguments.get(2));
      }
    }

    private void addLambdaInvocationSites(
        List<DynamicSite> result,
        String dynamicSource,
        String source,
        int opening,
        String handler) {
      Matcher lambda = Pattern.compile("\\(([^)]*)\\)\\s*->").matcher(handler);
      if (!lambda.find()) {
        return;
      }
      List<String> parameters = splitTopLevelArguments(lambda.group(1));
      if (parameters.size() != 3) {
        return;
      }
      String methodParameter = lambdaParameterName(parameters.get(1));
      if (methodParameter.isBlank()) {
        return;
      }
      int bodyEnd = lambdaBodyEnd(handler, lambda.end());
      if (bodyEnd < 0) {
        return;
      }
      Matcher invoke = Pattern.compile(
              "\\b" + Pattern.quote(methodParameter) + "\\s*\\.\\s*invoke\\s*\\(")
          .matcher(handler);
      invoke.region(lambda.end(), bodyEnd);
      int handlerOffset = dynamicSource.indexOf(handler, opening + 1);
      if (handlerOffset < 0) {
        return;
      }
      while (invoke.find()) {
        addSite(result, source, handlerOffset + invoke.start(), "Method.invoke");
      }
    }

    private int lambdaBodyEnd(String handler, int bodyStart) {
      while (bodyStart < handler.length() && Character.isWhitespace(handler.charAt(bodyStart))) {
        bodyStart++;
      }
      if (bodyStart >= handler.length()) {
        return -1;
      }
      if (handler.charAt(bodyStart) != '{') {
        int boundary = handler.indexOf(';', bodyStart);
        return boundary < 0 ? handler.length() : boundary;
      }
      int closing = matchingDelimiter(handler, bodyStart, '{', '}');
      return closing < 0 ? -1 : closing + 1;
    }
  }
}

final class FrameworkScanning {
  private FrameworkScanning() {}

  static List<Route> routes(
      SourceMetadataScanner scanner, AnalysisContext.Framework framework) {
    return FrameworkRouteScanning.routes(scanner, framework);
  }

  static List<InjectionPoint> injectionPoints(
      SourceMetadataScanner scanner, AnalysisContext.Framework framework) {
    return FrameworkInjectionScanning.injectionPoints(scanner, framework);
  }

  static List<BeanCandidate> beanCandidates(
      SourceMetadataScanner scanner, AnalysisContext.Framework framework) {
    return FrameworkBeanScanning.beanCandidates(scanner, framework);
  }
}
