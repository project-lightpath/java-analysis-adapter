package com.projectlightpath.analysis.java;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * javac-backed ServiceLoader site discovery.
 *
 * <p>javac Trees is the only source-symbol authority. The source cache asks
 * this class for compiler-bound sites; there is no textual fallback.
 */
final class JavacServiceLoaderSiteResolver {
  private static final String SERVICE_LOADER = "java.util.ServiceLoader";
  private static final String LOAD = "load";

  private JavacServiceLoaderSiteResolver() {}

  /** Compiler-bound sites plus the files javac rejected, keyed by source path. */
  private record Analysis(
      Map<Path, List<ServiceLoaderCallSite>> sites, Map<Path, String> errors) {}

  private static final Map<SourceMetadataScanner, Optional<Analysis>> CACHE =
      Collections.synchronizedMap(new WeakHashMap<>());

  /**
   * Returns compiler-bound sites per source file. A present empty map means
   * javac could not produce a safe result, so the caller fails closed rather
   * than falling back to textual guesses.
   */
  static Optional<Map<Path, List<ServiceLoaderCallSite>>> sites(SourceMetadataScanner scanner) {
    return analysis(scanner).map(Analysis::sites);
  }

  /** Returns the source files javac rejected and the reason recorded for each. */
  static Map<Path, String> errors(SourceMetadataScanner scanner) {
    return analysis(scanner).map(Analysis::errors).orElse(Map.of());
  }

  private static Optional<Analysis> analysis(SourceMetadataScanner scanner) {
    return CACHE.computeIfAbsent(scanner, JavacServiceLoaderSiteResolver::resolveSites);
  }

  private static Optional<Analysis> resolveSites(SourceMetadataScanner scanner) {
    try {
      return Optional.of(compile(scanner));
    } catch (IOException | IllegalArgumentException error) {
      return Optional.of(new Analysis(Map.of(), Map.of()));
    }
  }

  private static boolean causedByStackOverflow(Throwable error) {
    for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof StackOverflowError) {
        return true;
      }
    }
    return false;
  }

  private static Analysis compile(SourceMetadataScanner scanner) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      return new Analysis(Map.of(), Map.of());
    }
    if (scanner.files().isEmpty()) {
      return new Analysis(Map.of(), Map.of());
    }
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager manager =
        compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
      List<JavaFileObject> units = new ArrayList<>();
      for (SourceMetadataScanner.SourceFile file : scanner.files()) {
        units.add(new TextSource(file.path().toUri(), file.source()));
      }
      JavacTask task = (JavacTask) compiler.getTask(
          null, manager, diagnostics, options(scanner), null, units);
      Iterable<? extends CompilationUnitTree> parsed = task.parse();
      analyze(task);
      List<Diagnostic<? extends JavaFileObject>> capturedDiagnostics =
          List.copyOf(diagnostics.getDiagnostics());
      boolean globalError = capturedDiagnostics.stream()
          .anyMatch(item -> item.getKind() == Diagnostic.Kind.ERROR && item.getSource() == null);
      if (globalError) {
        return new Analysis(Map.of(), Map.of());
      }
      Map<Path, String> errors = capturedDiagnostics.stream()
          .filter(item -> item.getKind() == Diagnostic.Kind.ERROR)
          .map(item -> sourcePath(item.getSource()))
          .filter(Objects::nonNull)
          .distinct()
          .collect(Collectors.toMap(
              path -> path,
              path -> "javac reported an error; ServiceLoader sites in this file are unavailable",
              (first, next) -> first,
              LinkedHashMap::new));
      Trees trees = Trees.instance(task);
      Types types = task.getTypes();
      Elements elements = task.getElements();
      SiteScanner siteScanner = new SiteScanner(trees, types, elements);
      for (CompilationUnitTree unit : parsed) {
        siteScanner.scan(unit, null);
      }
      Map<Path, List<ServiceLoaderCallSite>> safe = new LinkedHashMap<>();
      for (Map.Entry<Path, List<ServiceLoaderCallSite>> entry : siteScanner.sites().entrySet()) {
        if (!errors.containsKey(entry.getKey())) {
          safe.put(entry.getKey(), entry.getValue());
        }
      }
      return new Analysis(Map.copyOf(safe), Map.copyOf(errors));
    }
  }

  private static void analyze(JavacTask task) throws IOException {
    try {
      task.analyze();
    } catch (IllegalStateException error) {
      if (causedByStackOverflow(error)) {
        throw new IOException("javac exhausted its parser stack", error);
      }
      throw error;
    } catch (StackOverflowError error) {
      throw new IOException("javac exhausted its parser stack", error);
    }
  }

  private static Path sourcePath(JavaFileObject source) {
    if (source == null) {
      return null;
    }
    try {
      return Path.of(source.toUri()).toAbsolutePath().normalize();
    } catch (IllegalArgumentException error) {
      return null;
    }
  }

  private static List<String> options(SourceMetadataScanner scanner) {
    List<String> options = new ArrayList<>();
    options.add("-proc:none");
    options.add("-encoding");
    options.add("UTF-8");
    if (scanner.context.javaRelease() > 0) {
      int release = Math.min(scanner.context.javaRelease(), Runtime.version().feature());
      options.add("--release");
      options.add(Integer.toString(release));
    }
    List<Path> classpath = new ArrayList<>(scanner.context.dependencyClasspath());
    classpath.add(scanner.context.applicationClasses());
    if (!classpath.isEmpty()) {
      options.add("-classpath");
      options.add(String.join(File.pathSeparator,
          classpath.stream().map(Path::toString).toList()));
    }
    return List.copyOf(options);
  }

  private static final class TextSource extends SimpleJavaFileObject {
    private final String source;

    TextSource(URI uri, String source) {
      super(uri, JavaFileObject.Kind.SOURCE);
      this.source = source;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return source;
    }
  }

  private static final class SiteScanner extends TreePathScanner<Void, Void> {
    private final Trees trees;
    private final Types types;
    private final Elements elements;
    private final Map<Path, List<ServiceLoaderCallSite>> sites = new LinkedHashMap<>();

    SiteScanner(Trees trees, Types types, Elements elements) {
      this.trees = trees;
      this.types = types;
      this.elements = elements;
    }

    Map<Path, List<ServiceLoaderCallSite>> sites() {
      Map<Path, List<ServiceLoaderCallSite>> result = new LinkedHashMap<>();
      for (Map.Entry<Path, List<ServiceLoaderCallSite>> entry : sites.entrySet()) {
        List<ServiceLoaderCallSite> sorted = new ArrayList<>(entry.getValue());
        sorted.sort(Comparator.comparingInt(ServiceLoaderCallSite::offset));
        result.put(entry.getKey(), List.copyOf(sorted));
      }
      return Map.copyOf(result);
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
      TreePath path = getCurrentPath();
      ExpressionTree select = node.getMethodSelect();
      if (!(select instanceof MemberSelectTree memberSelect)
          || !memberSelect.getIdentifier().contentEquals(LOAD)
          || !isServiceLoaderLoad(trees.getElement(new TreePath(path, select)))) {
        return super.visitMethodInvocation(node, unused);
      }
      String serviceType = null;
      int classLiteralIndex = -1;
      for (int index = 0; index < node.getArguments().size(); index++) {
        serviceType = classLiteralType(path, List.of(node.getArguments().get(index)));
        if (serviceType != null) {
          classLiteralIndex = index;
          break;
        }
      }
      if (classLiteralIndex != 0) {
        // The module-layer overload does not discover classpath providers.
        return super.visitMethodInvocation(node, unused);
      }
      long position = startPosition(path);
      if (position < 0) {
        return super.visitMethodInvocation(node, unused);
      }
      CompilationUnitTree unit = path.getCompilationUnit();
      Path file = Path.of(unit.getSourceFile().toUri()).normalize();
      int line = (int) unit.getLineMap().getLineNumber(position);
      boolean explicitClassLoader = node.getArguments().size() == 2
          && classLiteralType(path, List.of(node.getArguments().get(0))) != null;
      sites.computeIfAbsent(file, ignored -> new ArrayList<>()).add(new ServiceLoaderCallSite(
          serviceType, explicitClassLoader, (int) position, line));
      return super.visitMethodInvocation(node, unused);
    }

    private long startPosition(TreePath path) {
      SourcePositions positions = trees.getSourcePositions();
      return positions.getStartPosition(path.getCompilationUnit(), path.getLeaf());
    }

    private static boolean isServiceLoaderLoad(Element method) {
      if (method == null
          || method.getKind() != ElementKind.METHOD
          || !method.getSimpleName().contentEquals(LOAD)) {
        return false;
      }
      Element owner = method.getEnclosingElement();
      return owner instanceof TypeElement type
          && SERVICE_LOADER.contentEquals(type.getQualifiedName());
    }

    private String classLiteralType(TreePath invocationPath, List<? extends ExpressionTree> arguments) {
      if (arguments.isEmpty() || !(arguments.get(0) instanceof MemberSelectTree select)
          || !select.getIdentifier().contentEquals("class")) {
        return null;
      }
      TreePath argumentPath = new TreePath(invocationPath, arguments.get(0));
      TypeMirror mirror = trees.getTypeMirror(new TreePath(argumentPath, select.getExpression()));
      if (mirror == null || mirror.getKind() == TypeKind.ERROR) {
        return null;
      }
      TypeMirror erased = types.erasure(mirror);
      if (erased.getKind() != TypeKind.DECLARED) {
        return null;
      }
      Element element = ((DeclaredType) erased).asElement();
      if (!(element instanceof TypeElement type)
          || type.getNestingKind() == javax.lang.model.element.NestingKind.ANONYMOUS) {
        return null;
      }
      String binary = elements.getBinaryName(type).toString();
      return binary.isEmpty() ? null : binary;
    }
  }
}
