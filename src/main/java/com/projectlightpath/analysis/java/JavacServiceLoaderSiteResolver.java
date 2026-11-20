package com.projectlightpath.analysis.java;

import com.sun.source.tree.ClassTree;
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
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Experimental flag-gated javac-backed ServiceLoader site discovery.
 *
 * <p>When {@code -Dflpath.java.resolver=javac} is set, the source cache asks this
 * class for compiler-bound sites instead of the textual parser. The default is
 * the existing textual path, so adapter output is unchanged unless the flag is
 * explicitly enabled.
 */
final class JavacServiceLoaderSiteResolver {
  static final String PROPERTY = "flpath.java.resolver";
  private static final String SERVICE_LOADER = "java.util.ServiceLoader";
  private static final String LOAD = "load";

  private JavacServiceLoaderSiteResolver() {}

  static boolean enabled() {
    return "javac".equalsIgnoreCase(System.getProperty(PROPERTY, "textual"));
  }

  private static final java.util.Map<SourceMetadataScanner,
      Optional<Map<Path, List<ServiceLoaderCallSite>>>> CACHE =
      java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

  /**
   * Returns compiler-bound sites per source file when the flag is enabled.
   * Empty Optional means the flag is off (textual path). A present empty map
   * means javac was selected but could not produce a safe result; the caller
   * must fail closed rather than fall back to textual guesses.
   */
  static Optional<Map<Path, List<ServiceLoaderCallSite>>> sites(SourceMetadataScanner scanner) {
    return CACHE.computeIfAbsent(scanner, JavacServiceLoaderSiteResolver::resolveSites);
  }

  private static Optional<Map<Path, List<ServiceLoaderCallSite>>> resolveSites(
      SourceMetadataScanner scanner) {
    if (!enabled()) {
      return Optional.empty();
    }
    try {
      return Optional.of(compile(scanner));
    } catch (IOException | IllegalArgumentException error) {
      return Optional.of(Map.of());
    }
  }

  private static Map<Path, List<ServiceLoaderCallSite>> compile(
      SourceMetadataScanner scanner) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      return Map.of();
    }
    List<Path> files = scanner.files().stream().map(SourceMetadataScanner.SourceFile::path).toList();
    if (files.isEmpty()) {
      return Map.of();
    }
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager manager =
        compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
      Iterable<? extends JavaFileObject> units = manager.getJavaFileObjectsFromPaths(files);
      JavacTask task = (JavacTask) compiler.getTask(
          null, manager, diagnostics, options(scanner), null, units);
      Iterable<? extends CompilationUnitTree> parsed = task.parse();
      task.analyze();
      boolean failed = diagnostics.getDiagnostics().stream()
          .anyMatch(item -> item.getKind() == Diagnostic.Kind.ERROR);
      if (failed) {
        // No certainty channel exists on ServiceLoaderCallSite yet. Abstain from
        // the compiler path rather than emit DEFINITE facts from an incomplete build.
        return Map.of();
      }
      Trees trees = Trees.instance(task);
      Types types = task.getTypes();
      SiteScanner siteScanner = new SiteScanner(trees, types);
      for (CompilationUnitTree unit : parsed) {
        siteScanner.scan(unit, null);
      }
      return siteScanner.sites();
    }
  }

  private static List<String> options(SourceMetadataScanner scanner) {
    List<String> options = new ArrayList<>();
    options.add("-proc:none");
    options.add("-encoding");
    options.add("UTF-8");
    if (scanner.context.javaRelease() > 0) {
      options.add("--release");
      options.add(Integer.toString(scanner.context.javaRelease()));
    }
    List<Path> classpath = new ArrayList<>(scanner.context.dependencyClasspath());
    classpath.add(scanner.context.applicationClasses());
    if (!classpath.isEmpty()) {
      options.add("-classpath");
      options.add(String.join(java.io.File.pathSeparator,
          classpath.stream().map(Path::toString).toList()));
    }
    return List.copyOf(options);
  }

  private static final class SiteScanner extends TreePathScanner<Void, Void> {
    private final Trees trees;
    private final Types types;
    private final Map<Path, List<ServiceLoaderCallSite>> sites = new LinkedHashMap<>();

    SiteScanner(Trees trees, Types types) {
      this.trees = trees;
      this.types = types;
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
      for (ExpressionTree argument : node.getArguments()) {
        serviceType = classLiteralType(path, List.of(argument));
        if (serviceType != null) {
          break;
        }
      }
      if (serviceType == null) {
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
          serviceType, serviceType, explicitClassLoader, (int) position, line));
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
          || type.getNestingKind() == javax.lang.model.element.NestingKind.LOCAL
          || type.getNestingKind() == javax.lang.model.element.NestingKind.ANONYMOUS) {
        return null;
      }
      String canonical = type.getQualifiedName().toString();
      return canonical.isEmpty() ? null : canonical;
    }
  }
}
