package com.projectlightpath.analysis.java;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
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
import javax.tools.ToolProvider;

/**
 * javac-backed source-symbol index for one analysis context.
 *
 * <p>One javac parse per {@link AnalysisContext}, cached by context identity,
 * exposing annotations, member type shapes, canonical and binary names, and
 * error-type state as immutable records. The seam is gated by the
 * {@code flpath.java.symbolindex} system property (default on since issue
 * #66): {@code -Dflpath.java.symbolindex=false} is the explicit opt-out
 * that keeps the textual path for field bisection.
 *
 * <p>The index never retains the context: every diagnostic path is rendered
 * at build time, and no accessor stores compiler or context objects.
 */
final class JavacSourceSymbolIndex {
  /** Test seam: number of fully successful index builds that ran javac. */
  static final AtomicLong COMPILATIONS = new AtomicLong();

  /** Test seam: compiler supplier, injectable to simulate unavailability. */
  static final AtomicReference<Supplier<JavaCompiler>> COMPILER =
      new AtomicReference<>(ToolProvider::getSystemJavaCompiler);

  private static final Map<AnalysisContext, JavacSourceSymbolIndex> CACHE =
      Collections.synchronizedMap(new WeakHashMap<>());

  private final Map<Path, List<TypeInfo>> typesByFile;
  private final Map<String, List<TypeInfo>> typesByCanonical;
  private final Map<String, List<TypeInfo>> typesByBinary;
  private final Map<Path, List<AnnotationInfo>> packageAnnotationsByFile;
  private final List<IndexDiagnostic> diagnostics;
  private final long wallMillis;
  private final long peakHeapBytes;

  private JavacSourceSymbolIndex(
      Map<Path, List<TypeInfo>> typesByFile,
      Map<String, List<TypeInfo>> typesByCanonical,
      Map<String, List<TypeInfo>> typesByBinary,
      Map<Path, List<AnnotationInfo>> packageAnnotationsByFile,
      List<IndexDiagnostic> diagnostics,
      long wallMillis,
      long peakHeapBytes) {
    this.typesByFile = typesByFile;
    this.typesByCanonical = typesByCanonical;
    this.typesByBinary = typesByBinary;
    this.packageAnnotationsByFile = packageAnnotationsByFile;
    this.diagnostics = diagnostics;
    this.wallMillis = wallMillis;
    this.peakHeapBytes = peakHeapBytes;
  }

  /**
   * Reports whether the index seam is enabled. Default ON (issue #66);
   * an explicit {@code "false"} (case-insensitive) opts out. Any other
   * value — absent, {@code "true"}, or unrecognized — keeps the default:
   * the opt-out must be deliberate.
   */
  static boolean enabled() {
    return !"false".equalsIgnoreCase(
        System.getProperty("flpath.java.symbolindex"));
  }

  /** Returns the per-context index, parsed at most once per context instance. */
  static Optional<JavacSourceSymbolIndex> of(AnalysisContext context) {
    Objects.requireNonNull(context, "analysis context");
    if (!enabled()) {
      return Optional.empty();
    }
    return Optional.of(CACHE.computeIfAbsent(context, JavacSourceSymbolIndex::build));
  }

  /** Returns every indexed type sharing a canonical or binary name, deduped. */
  List<TypeInfo> type(String canonicalOrBinaryName) {
    if (canonicalOrBinaryName == null || canonicalOrBinaryName.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<TypeInfo> result = new LinkedHashSet<>();
    result.addAll(typesByCanonical.getOrDefault(canonicalOrBinaryName, List.of()));
    result.addAll(typesByBinary.getOrDefault(canonicalOrBinaryName, List.of()));
    return List.copyOf(result);
  }

  /** Returns the types declared in one source file, by absolute normalized path. */
  List<TypeInfo> typesIn(Path sourceFile) {
    if (sourceFile == null) {
      return List.of();
    }
    return typesByFile.getOrDefault(
        sourceFile.toAbsolutePath().normalize(), List.of());
  }

  /** Returns the annotations declared in one package-info.java unit. */
  List<AnnotationInfo> packageAnnotations(Path packageInfoFile) {
    if (packageInfoFile == null) {
      return List.of();
    }
    return packageAnnotationsByFile.getOrDefault(
        packageInfoFile.toAbsolutePath().normalize(), List.of());
  }

  /** Returns every member with the given name on all entries sharing the type name. */
  List<MemberInfo> members(String typeName, String memberName) {
    List<MemberInfo> result = new ArrayList<>();
    for (TypeInfo entry : type(typeName)) {
      for (MemberInfo member : entry.members()) {
        if (member.name().equals(memberName)) {
          result.add(member);
        }
      }
    }
    return List.copyOf(result);
  }

  /** Returns the index-level and compiler diagnostics recorded at build time. */
  List<IndexDiagnostic> diagnostics() {
    return diagnostics;
  }

  /** Wall time of the index build in milliseconds. */
  long wallMillis() {
    return wallMillis;
  }

  /** Peak sampled heap during the index build in bytes. */
  long peakHeapBytes() {
    return peakHeapBytes;
  }

  /** Strips the process temp-directory prefix so messages never leak host paths. */
  static String sanitizeMessage(String message) {
    if (message == null) {
      return "";
    }
    String prefix = System.getProperty("java.io.tmpdir");
    if (prefix == null || prefix.isEmpty()) {
      return message;
    }
    return message.replace(prefix, "<tmp>");
  }

  // The RuntimeException catch below is deliberate and inventoried for manual
  // review: the fail-visible contract routes any IOException or
  // RuntimeException — input scan, supplier, task, or extraction pass — and a
  // parser StackOverflowError into a present index with an index_build_error
  // row, cached like a success. Other Errors (OutOfMemoryError and kin) escape
  // of() by design: catching them would mask JVM-level failure.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private static JavacSourceSymbolIndex build(AnalysisContext context) {
    long startNano = System.nanoTime();
    long heapBefore = heapUsed();
    List<IndexDiagnostic> diagnostics = new ArrayList<>();
    try {
      Inputs inputs = new Inputs(context, diagnostics);
      List<Path> units = inputs.units();
      if (units.isEmpty()) {
        return failed(diagnostics, startNano, heapBefore);
      }
      JavaCompiler compiler = COMPILER.get().get();
      if (compiler == null) {
        diagnostics.add(IndexRenderer.row("javac_unavailable",
            "javac is not available; the source-symbol index cannot be built", "", 0, 0));
        return failed(diagnostics, startNano, heapBefore);
      }
      return compile(context, compiler, inputs, units, diagnostics, startNano, heapBefore);
    } catch (IOException | RuntimeException error) {
      diagnostics.add(IndexRenderer.row("index_build_error",
          "the source-symbol index could not be built: " + error.getMessage(), "", 0, 0));
      return failed(diagnostics, startNano, heapBefore);
    } catch (StackOverflowError error) {
      diagnostics.add(IndexRenderer.row("index_build_error",
          "javac exhausted its parser stack while building the index", "", 0, 0));
      return failed(diagnostics, startNano, heapBefore);
    }
  }

  private static JavacSourceSymbolIndex compile(
      AnalysisContext context,
      JavaCompiler compiler,
      Inputs inputs,
      List<Path> unitFiles,
      List<IndexDiagnostic> diagnostics,
      long startNano,
      long heapBefore)
      throws IOException {
    Path sourcepathDir = Files.createTempDirectory("flpath-index-sourcepath");
    Path classesDir = Files.createTempDirectory("flpath-index-classes");
    DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
    try (StandardJavaFileManager manager =
        compiler.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8)) {
      Iterable<? extends JavaFileObject> units =
          manager.getJavaFileObjectsFromPaths(unitFiles);
      JavacTask task = (JavacTask) compiler.getTask(
          null, manager, collector,
          options(context, inputs.release(), sourcepathDir, classesDir), null, units);
      Iterable<? extends CompilationUnitTree> parsed = task.parse();
      task.analyze();
      List<Diagnostic<? extends JavaFileObject>> captured =
          List.copyOf(collector.getDiagnostics());
      if (IndexRenderer.hasUnmappableSource(captured)) {
        diagnostics.add(IndexRenderer.row("index_build_error",
            "javac reported an error in a classpath entry the index cannot attribute: "
                + IndexRenderer.firstUnmappableName(captured), "", 0, 0));
        return failed(diagnostics, startNano, heapBefore);
      }
      IndexRenderer.addCompilerDiagnostics(
          context, inputs.roots(), java.util.Set.copyOf(unitFiles), captured, diagnostics);
      Extractor extractor = new Extractor(
          Trees.instance(task), task.getElements(), task.getTypes());
      for (CompilationUnitTree unit : parsed) {
        extractor.scan(unit, null);
      }
      JavacSourceSymbolIndex index =
          extractor.finish(diagnostics, startNano, Math.max(heapBefore, heapUsed()));
      COMPILATIONS.incrementAndGet();
      return index;
    } finally {
      deleteRecursively(sourcepathDir);
      deleteRecursively(classesDir);
    }
  }

  private static List<String> options(
      AnalysisContext context, int release, Path sourcepathDir, Path classesDir) {
    List<Path> classpath = new ArrayList<>(context.dependencyClasspath());
    classpath.add(context.applicationClasses());
    return List.of(
        "-proc:none",
        "-encoding", "UTF-8",
        "--release", Integer.toString(release),
        "-classpath", String.join(File.pathSeparator,
            classpath.stream().map(Path::toString).toList()),
        "-sourcepath", sourcepathDir.toString(),
        "-d", classesDir.toString());
  }

  private static JavacSourceSymbolIndex failed(
      List<IndexDiagnostic> diagnostics, long startNano, long heapBefore) {
    return new JavacSourceSymbolIndex(
        Map.of(), Map.of(), Map.of(), Map.of(), List.copyOf(diagnostics),
        elapsedMillis(startNano), Math.max(heapBefore, heapUsed()));
  }

  private static long elapsedMillis(long startNano) {
    return (System.nanoTime() - startNano) / 1_000_000;
  }

  private static long heapUsed() {
    return java.lang.management.ManagementFactory.getMemoryMXBean()
        .getHeapMemoryUsage().getUsed();
  }

  private static boolean deleteRecursively(Path directory) {
    try (Stream<Path> paths = Files.walk(directory)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
      return true;
    } catch (IOException error) {
      return false;
    }
  }

  /** Walk-time input checks: units, classpath, and release clamp diagnostics. */
  private static final class Inputs {
    private final AnalysisContext context;
    private final List<IndexDiagnostic> diagnostics;

    Inputs(AnalysisContext context, List<IndexDiagnostic> diagnostics) {
      this.context = context;
      this.diagnostics = diagnostics;
    }

    List<AnalysisContext.SourceRoot> roots() {
      return context.includedSourceRoots();
    }

    int release() {
      return clampedRelease();
    }

    List<Path> units() {
      diagnoseClasspath();
      if (roots().isEmpty()) {
        diagnostics.add(IndexRenderer.row("missing_source",
            "no source roots are included in the analysis scope", "", 0, 0));
        return List.of();
      }
      Map<Path, AnalysisContext.SourceRoot> discovered = new LinkedHashMap<>();
      for (AnalysisContext.SourceRoot root : AnalysisContext.rootsBySpecificity(roots())) {
        collectRoot(root, discovered);
      }
      if (discovered.isEmpty()) {
        diagnostics.add(IndexRenderer.row("missing_source",
            "no compilation units were discovered under the included source roots",
            "", 0, 0));
      }
      return discovered.keySet().stream()
          .sorted(Comparator.comparing(Path::toString))
          .toList();
    }

    private void collectRoot(
        AnalysisContext.SourceRoot root, Map<Path, AnalysisContext.SourceRoot> discovered) {
      if (!Files.isDirectory(root.path())) {
        diagnostics.add(IndexRenderer.row("missing_source_root",
            "source root is unavailable: " + IndexRenderer.declaredRootPath(root),
            IndexRenderer.declaredRootPath(root), 0, 0));
        return;
      }
      try (Stream<Path> paths = Files.walk(root.path())) {
        paths.filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java"))
            .filter(path -> !path.getFileName().toString().equals("module-info.java"))
            .forEach(path -> discovered.putIfAbsent(
                path.toAbsolutePath().normalize(), root));
      } catch (IOException error) {
        throw new IllegalStateException(
            "cannot scan source root " + IndexRenderer.declaredRootPath(root), error);
      }
    }

    private void diagnoseClasspath() {
      if (!context.dependencyClasspathRequired()) {
        return;
      }
      if (context.dependencyClasspath().isEmpty()) {
        diagnostics.add(IndexRenderer.row("missing_classpath",
            "the dependency classpath is required but empty", "", 0, 0));
        return;
      }
      for (Path entry : context.dependencyClasspath()) {
        if (!Files.exists(entry)) {
          diagnostics.add(IndexRenderer.row("missing_classpath",
              "required dependency classpath entry is unavailable: " + entry.getFileName(),
              IndexRenderer.repositoryRelative(context.repositoryRoot(), entry), 0, 0));
        }
      }
    }

    private int clampedRelease() {
      int feature = Runtime.version().feature();
      if (context.javaRelease() > feature) {
        diagnostics.add(IndexRenderer.row("release_clamped",
            "java release " + context.javaRelease() + " is above the runtime feature "
                + feature + "; compiling with --release " + feature,
            "", 0, 0));
        return feature;
      }
      return context.javaRelease();
    }
  }

  /** Diagnostic-path rendering: never absolute, per-family rules. */
  static final class IndexRenderer {
    private IndexRenderer() {}

    static IndexDiagnostic row(
        String code, String message, String path, long line, long column) {
      return new IndexDiagnostic(code, sanitizeMessage(message), path, line, column);
    }

    static boolean hasUnmappableSource(List<Diagnostic<? extends JavaFileObject>> captured) {
      return captured.stream().anyMatch(item ->
          item.getKind() == Diagnostic.Kind.ERROR
              && item.getSource() != null
              && sourcePath(item.getSource()) == null);
    }

    static String firstUnmappableName(List<Diagnostic<? extends JavaFileObject>> captured) {
      return captured.stream()
          .filter(item -> item.getKind() == Diagnostic.Kind.ERROR)
          .filter(item -> item.getSource() != null && sourcePath(item.getSource()) == null)
          .map(item -> sourceName(item.getSource()))
          .filter(Objects::nonNull)
          .findFirst()
          .orElse("");
    }

    static void addCompilerDiagnostics(
        AnalysisContext context,
        List<AnalysisContext.SourceRoot> roots,
        java.util.Set<Path> unitFiles,
        List<Diagnostic<? extends JavaFileObject>> captured,
        List<IndexDiagnostic> diagnostics) {
      for (Diagnostic<? extends JavaFileObject> item : captured) {
        if (item.getKind() != Diagnostic.Kind.ERROR) {
          continue;
        }
        JavaFileObject source = item.getSource();
        if (source == null) {
          diagnostics.add(row("javac_global_error",
              item.getMessage(Locale.ROOT), "", item.getLineNumber(),
              item.getColumnNumber()));
          continue;
        }
        Path path = sourcePath(source);
        String rendered = path == null
            ? sourceName(source)
            : unitFiles.contains(path)
                ? unitDisplayPath(context, roots, path)
                : repositoryRelative(context.repositoryRoot(), path);
        diagnostics.add(row(item.getCode(), item.getMessage(Locale.ROOT),
            rendered, item.getLineNumber(), item.getColumnNumber()));
      }
    }

    static Path sourcePath(JavaFileObject source) {
      try {
        return Path.of(source.toUri()).toAbsolutePath().normalize();
      } catch (IllegalArgumentException | FileSystemNotFoundException error) {
        return null;
      }
    }

    private static String sourceName(JavaFileObject source) {
      if (source == null) {
        return null;
      }
      try {
        Path fileName = Path.of(source.getName()).getFileName();
        return fileName == null ? "<external>" : "<external>/" + fileName;
      } catch (InvalidPathException error) {
        return "<external>";
      }
    }

    private static String unitDisplayPath(
        AnalysisContext context, List<AnalysisContext.SourceRoot> roots, Path file) {
      String display = context.displayPath(file);
      if (!Path.of(display).isAbsolute()) {
        return display;
      }
      return repositoryRelative(context.repositoryRoot(), file);
    }

    static String declaredRootPath(AnalysisContext.SourceRoot root) {
      String declared = root.declaredPath();
      try {
        if (!Path.of(declared).isAbsolute()) {
          return declared;
        }
      } catch (InvalidPathException error) {
        return "<external>";
      }
      Path fileName = root.path().getFileName();
      return fileName == null ? "<external>" : "<external>/" + fileName;
    }

    static String repositoryRelative(Path repositoryRoot, Path path) {
      Path root = repositoryRoot.toAbsolutePath().normalize();
      Path normalized = path.toAbsolutePath().normalize();
      if (normalized.startsWith(root)) {
        return root.relativize(normalized).toString();
      }
      Path fileName = normalized.getFileName();
      return fileName == null ? "<external>" : "<external>/" + fileName;
    }
  }

  /** One-time extraction pass turning the analyzed trees into immutable records. */
  private static final class Extractor extends TreePathScanner<Void, Void> {
    private final Trees trees;
    private final ElementReader reader;
    private final Map<Path, List<TypeInfo>> typesByFile = new LinkedHashMap<>();
    private final Map<Path, List<AnnotationInfo>> packageAnnotationsByFile =
        new LinkedHashMap<>();

    Extractor(Trees trees, Elements elements, Types types) {
      this.trees = trees;
      this.reader = new ElementReader(elements, types);
    }

    @Override
    public Void visitClass(ClassTree node, Void unused) {
      TreePath path = getCurrentPath();
      if (trees.getElement(path) instanceof TypeElement element) {
        Path file = IndexRenderer.sourcePath(path.getCompilationUnit().getSourceFile());
        if (file != null) {
          typesByFile.computeIfAbsent(file, ignored -> new ArrayList<>())
              .add(reader.typeInfo(element, file));
        }
      }
      return super.visitClass(node, unused);
    }

    @Override
    public Void visitCompilationUnit(CompilationUnitTree node, Void unused) {
      if (isPackageInfo(node)) {
        collectPackageAnnotations(node);
      }
      return super.visitCompilationUnit(node, unused);
    }

    private static boolean isPackageInfo(CompilationUnitTree node) {
      return node.getSourceFile() != null
          && node.getSourceFile().getName().endsWith("package-info.java");
    }

    private void collectPackageAnnotations(CompilationUnitTree node) {
      Path file = IndexRenderer.sourcePath(node.getSourceFile());
      String packageName = node.getPackageName() == null
          ? "" : node.getPackageName().toString();
      PackageElement pkg = reader.packageElement(packageName);
      if (file != null && pkg != null && !pkg.getAnnotationMirrors().isEmpty()) {
        packageAnnotationsByFile.put(file, pkg.getAnnotationMirrors().stream()
            .map(AnnotationRenderer::info).toList());
      }
    }

    JavacSourceSymbolIndex finish(
        List<IndexDiagnostic> diagnostics, long startNano, long peakHeap) {
      List<TypeInfo> all = typesByFile.values().stream()
          .flatMap(List::stream)
          .toList();
      Map<String, List<TypeInfo>> flaggedByBinary = flagDuplicates(all, diagnostics);
      List<TypeInfo> flagged = flaggedByBinary.values().stream()
          .flatMap(List::stream)
          .toList();
      return new JavacSourceSymbolIndex(
          copyOfLists(groupedByPath(flagged)),
          copyOfLists(groupedByCanonical(flagged)),
          copyOfLists(flaggedByBinary),
          copyOfLists(packageAnnotationsByFile), List.copyOf(diagnostics),
          elapsedMillis(startNano), peakHeap);
    }

    private static Map<String, List<TypeInfo>> flagDuplicates(
        List<TypeInfo> all, List<IndexDiagnostic> diagnostics) {
      Map<String, List<TypeInfo>> byBinary = groupedByBinary(all);
      Map<String, List<TypeInfo>> flagged = new LinkedHashMap<>();
      for (Map.Entry<String, List<TypeInfo>> group : byBinary.entrySet()) {
        boolean duplicate = group.getValue().size() > 1;
        if (duplicate) {
          diagnostics.add(IndexRenderer.row("duplicate_binary_name",
              "binary name " + group.getKey() + " is declared in "
                  + group.getValue().size() + " files: "
                  + group.getValue().stream()
                      .map(entry -> entry.file().getFileName().toString())
                      .collect(java.util.stream.Collectors.joining(", ")),
              group.getValue().get(0).file().getFileName().toString(), 0, 0));
        }
        for (TypeInfo entry : group.getValue()) {
          flagged.computeIfAbsent(group.getKey(), ignored -> new ArrayList<>())
              .add(duplicate ? flaggedCopy(entry) : entry);
        }
      }
      return flagged;
    }

    private static TypeInfo flaggedCopy(TypeInfo entry) {
      return new TypeInfo(entry.canonicalName(), entry.binaryName(), entry.nestingKind(),
          entry.file(), entry.annotations(), entry.members(),
          new TypeShape(entry.shape().declarationKind(), entry.shape().errorType(), true,
              entry.shape().staticType()));
    }

    private static Map<String, List<TypeInfo>> groupedByBinary(List<TypeInfo> entries) {
      Map<String, List<TypeInfo>> result = new LinkedHashMap<>();
      for (TypeInfo entry : entries) {
        result.computeIfAbsent(entry.binaryName(), ignored -> new ArrayList<>())
            .add(entry);
      }
      return result;
    }

    private static Map<String, List<TypeInfo>> groupedByCanonical(List<TypeInfo> entries) {
      Map<String, List<TypeInfo>> result = new LinkedHashMap<>();
      for (TypeInfo entry : entries) {
        if (isLocalOrAnonymous(entry) || entry.canonicalName().isEmpty()) {
          continue;
        }
        result.computeIfAbsent(entry.canonicalName(), ignored -> new ArrayList<>())
            .add(entry);
      }
      return result;
    }

    private static Map<Path, List<TypeInfo>> groupedByPath(List<TypeInfo> entries) {
      Map<Path, List<TypeInfo>> result = new LinkedHashMap<>();
      for (TypeInfo entry : entries) {
        result.computeIfAbsent(entry.file(), ignored -> new ArrayList<>()).add(entry);
      }
      return result;
    }

    private static boolean isLocalOrAnonymous(TypeInfo entry) {
      return entry.nestingKind().equals("LOCAL") || entry.nestingKind().equals("ANONYMOUS");
    }

    private static <K, V> Map<K, List<V>> copyOfLists(Map<K, List<V>> source) {
      Map<K, List<V>> result = new LinkedHashMap<>();
      source.forEach((key, value) -> result.put(key, List.copyOf(value)));
      return Collections.unmodifiableMap(result);
    }
  }

  /** Element-to-record conversion: names, kinds, types, and annotation values. */
  private static final class ElementReader {
    private final Elements elements;
    private final Types types;
    private final MemberTypeShapes shapes;

    ElementReader(Elements elements, Types types) {
      this.elements = elements;
      this.types = types;
      this.shapes = new MemberTypeShapes(elements, types);
    }

    PackageElement packageElement(String name) {
      return elements.getPackageElement(name);
    }

    TypeInfo typeInfo(TypeElement element, Path file) {
      return new TypeInfo(
          element.getQualifiedName().toString(),
          elements.getBinaryName(element).toString(),
          element.getNestingKind().toString(),
          file,
          element.getAnnotationMirrors().stream()
              .map(AnnotationRenderer::info).toList(),
          element.getEnclosedElements().stream()
              .filter(ElementReader::isMember)
              .map(this::memberInfo)
              .toList(),
          new TypeShape(declarationKind(element), isError(element.asType()), false,
              // Reflection sees a synthetic enclosing-instance parameter on
              // non-static nested constructors; the static flag lets the
              // reflection-target ladder compare in the reflection domain.
              element.getModifiers().contains(javax.lang.model.element.Modifier.STATIC)));
    }

    private MemberInfo memberInfo(Element element) {
      boolean executable = element.getKind() == ElementKind.METHOD
          || element.getKind() == ElementKind.CONSTRUCTOR;
      List<? extends VariableElement> parameters =
          executable ? ((ExecutableElement) element).getParameters() : List.of();
      return new MemberInfo(
          memberName(element),
          memberKind(element),
          element.getAnnotationMirrors().stream()
              .map(AnnotationRenderer::info).toList(),
          new MemberTypes(
              parameters.stream()
                  .map(variable -> shapes.canonicalType(variable.asType()))
                  .toList(),
              memberReturnType(element),
              parameters.stream()
                  .map(variable -> shapes.binaryType(variable.asType()))
                  .toList(),
              memberBinaryReturnType(element)),
          parameters.stream()
              .map(variable -> variable.getAnnotationMirrors().stream()
                  .map(AnnotationRenderer::info).toList())
              .toList(),
          memberHasErrorType(element, executable),
          // getMethod/getConstructor return public members only, so the
          // reflection-target ladder (issue #25) needs the visibility.
          element.getModifiers().contains(javax.lang.model.element.Modifier.PUBLIC));
    }

    private static boolean isMember(Element element) {
      return switch (element.getKind()) {
        case METHOD, CONSTRUCTOR, FIELD, ENUM_CONSTANT -> true;
        default -> false;
      };
    }

    /** Constructors carry the enclosing type's simple name, not javac's {@code <init>}. */
    private static String memberName(Element element) {
      if (element.getKind() == ElementKind.CONSTRUCTOR
          && element.getEnclosingElement() instanceof TypeElement owner) {
        return owner.getSimpleName().toString();
      }
      return element.getSimpleName().toString();
    }

    private static String memberKind(Element element) {
      return switch (element.getKind()) {
        case METHOD -> "method";
        case CONSTRUCTOR -> "constructor";
        case ENUM_CONSTANT -> "enum_constant";
        default -> "field";
      };
    }

    private String memberReturnType(Element element) {
      if (element.getKind() == ElementKind.CONSTRUCTOR) {
        return "void";
      }
      if (element.getKind() == ElementKind.METHOD) {
        return shapes.canonicalType(((ExecutableElement) element).getReturnType());
      }
      return "";
    }

    private String memberBinaryReturnType(Element element) {
      if (element.getKind() == ElementKind.CONSTRUCTOR) {
        return "void";
      }
      if (element.getKind() == ElementKind.METHOD) {
        return shapes.binaryType(((ExecutableElement) element).getReturnType());
      }
      return "";
    }

    private boolean memberHasErrorType(Element element, boolean executable) {
      if (isError(element.asType())) {
        return true;
      }
      if (executable) {
        // The return type is part of the member's shape: an unresolved
        // return must not surface as an unqualified "canonical" spelling,
        // so it blocks the member like an unresolved parameter. The
        // residual unresolved shapes are SCOPING failures (JLS 6.5.5.1 —
        // e.g. an unqualified sibling nested type) or genuinely missing
        // classpath types, never joint-compilation gaps: the index compiles
        // the full selected source set in one task, so cross-file
        // references among selected files resolve.
        // The ERASED return is checked too: a type variable with an
        // unresolved bound (e.g. <T extends missing.Thing>) has a non-error
        // declared mirror but erases to an error type, whose rendered
        // spelling would otherwise be published as a canonical answer.
        // Valid bounds (<T extends Number>) stay eligible.
        if (element.getKind() == ElementKind.METHOD) {
          TypeMirror returnType = ((ExecutableElement) element).getReturnType();
          if (isError(returnType) || isError(types.erasure(returnType))) {
            return true;
          }
        }
        return ((ExecutableElement) element).getParameters().stream()
            .anyMatch(variable -> isError(variable.asType())
                || isError(types.erasure(variable.asType())));
      }
      return false;
    }

    private static String declarationKind(TypeElement element) {
      return switch (element.getKind()) {
        case INTERFACE -> "interface";
        case ENUM -> "enum";
        case RECORD -> "record";
        case ANNOTATION_TYPE -> "annotation_type";
        default -> "class";
      };
    }

    private static boolean isError(TypeMirror mirror) {
      if (mirror.getKind() == TypeKind.ERROR) {
        return true;
      }
      // An array of an unresolved component (Missing[]) is ARRAY-kinded, not
      // ERROR-kinded; the component must be checked recursively.
      return mirror.getKind() == TypeKind.ARRAY
          && isError(((ArrayType) mirror).getComponentType());
    }
  }

  /**
   * Canonical and binary type-spelling rendering for member shapes. Split
   * from {@code ElementReader} for PMD's GodClass threshold; behavior is
   * identical to the inlined form.
   */
  private static final class MemberTypeShapes {
    private final Elements elements;
    private final Types types;

    MemberTypeShapes(Elements elements, Types types) {
      this.elements = elements;
      this.types = types;
    }

    String canonicalType(TypeMirror mirror) {
      TypeMirror erased = types.erasure(mirror);
      return switch (erased.getKind()) {
        case ARRAY -> canonicalType(((ArrayType) erased).getComponentType()) + "[]";
        case DECLARED, ERROR -> declaredName(erased);
        default -> erased.toString();
      };
    }

    private static String declaredName(TypeMirror erased) {
      if (erased instanceof DeclaredType declared
          && declared.asElement() instanceof TypeElement named) {
        String qualified = named.getQualifiedName().toString();
        return qualified.isEmpty() ? named.getSimpleName().toString() : qualified;
      }
      return erased.toString();
    }

    /**
     * The binary spelling of one mirror ({@code Elements.getBinaryName}),
     * mirroring {@link #canonicalType}: erasure first, arrays append
     * {@code []}, primitives keep their spelling. An ERROR mirror renders
     * blank — unreachable in practice because {@code memberHasErrorType}
     * blocks the member, kept for defensive parity with the canonical
     * blank rule.
     */
    String binaryType(TypeMirror mirror) {
      TypeMirror erased = types.erasure(mirror);
      return switch (erased.getKind()) {
        case ARRAY -> binaryType(((ArrayType) erased).getComponentType()) + "[]";
        case DECLARED -> declaredBinaryName(erased);
        case ERROR -> "";
        default -> erased.toString();
      };
    }

    private String declaredBinaryName(TypeMirror erased) {
      if (erased instanceof DeclaredType declared
          && declared.asElement() instanceof TypeElement named) {
        return elements.getBinaryName(named).toString();
      }
      return "";
    }
  }
  /** Annotation mirrors rendered to canonical names and stable value strings. */
  private static final class AnnotationRenderer {
    private AnnotationRenderer() {}

    static AnnotationInfo info(AnnotationMirror mirror) {
      String canonical = mirror.getAnnotationType().asElement() instanceof TypeElement named
          ? named.getQualifiedName().toString()
          : mirror.getAnnotationType().toString();
      Map<String, String> values = new LinkedHashMap<>();
      mirror.getElementValues().forEach((key, value) ->
          values.put(key.getSimpleName().toString(), render(value)));
      return new AnnotationInfo(canonical, Collections.unmodifiableMap(values));
    }

    private static String render(AnnotationValue value) {
      Object unwrapped = value.getValue();
      if (unwrapped instanceof String text) {
        return "\"" + text + "\"";
      }
      if (unwrapped instanceof VariableElement enumConstant) {
        return enumConstant.getEnclosingElement() instanceof TypeElement owner
            ? owner.getQualifiedName() + "." + enumConstant.getSimpleName()
            : enumConstant.getSimpleName().toString();
      }
      if (unwrapped instanceof TypeMirror classLiteral) {
        return classLiteral instanceof DeclaredType declared
            && declared.asElement() instanceof TypeElement named
                ? named.getQualifiedName().toString()
                : classLiteral.toString();
      }
      if (unwrapped instanceof List<?> array) {
        return array.stream()
            .map(item -> render((AnnotationValue) item))
            .collect(Collectors.joining(", ", "{", "}"));
      }
      if (unwrapped instanceof AnnotationMirror nested) {
        return renderNested(nested);
      }
      return String.valueOf(unwrapped);
    }

    private static String renderNested(AnnotationMirror nested) {
      String canonical =
          nested.getAnnotationType().asElement() instanceof TypeElement named
              ? named.getQualifiedName().toString()
              : nested.getAnnotationType().toString();
      if (nested.getElementValues().isEmpty()) {
        return "@" + canonical;
      }
      String joined = nested.getElementValues().entrySet().stream()
          .map(entry -> entry.getKey().getSimpleName() + "=" + render(entry.getValue()))
          .collect(Collectors.joining(", "));
      return "@" + canonical + "(" + joined + ")";
    }
  }

  /** Declaration kind plus the error and duplicate-binary-name flags of one type. */
  record TypeShape(String declarationKind, boolean errorType, boolean duplicateBinaryName,
      boolean staticType) {}

  /** One indexed type declaration. */
  record TypeInfo(
      String canonicalName,
      String binaryName,
      String nestingKind,
      Path file,
      List<AnnotationInfo> annotations,
      List<MemberInfo> members,
      TypeShape shape) {}

  /** One indexed member declaration. */
  record MemberInfo(
      String name,
      String kind,
      List<AnnotationInfo> annotations,
      MemberTypes types,
      List<List<AnnotationInfo>> parameterAnnotations,
      boolean errorType,
      boolean publicMember) {

    /** Canonical erased parameter spellings ({@code java.lang.String[]}). */
    List<String> parameterTypes() {
      return types.parameterTypes();
    }

    /** Canonical erased return spelling; {@code void} for constructors. */
    String returnType() {
      return types.returnType();
    }

    /** Binary parameter spellings ({@code java.lang.String[]}). */
    List<String> parameterBinaryTypes() {
      return types.parameterBinaryTypes();
    }

    /** Binary return spelling; {@code void} for constructors. */
    String returnBinaryType() {
      return types.returnBinaryType();
    }
  }

  /** Canonical and binary type shapes of one member, kept in one fold. */
  record MemberTypes(
      List<String> parameterTypes,
      String returnType,
      List<String> parameterBinaryTypes,
      String returnBinaryType) {}

  /** One annotation with its rendered element values. */
  record AnnotationInfo(String canonicalName, Map<String, String> values) {}

  /** One diagnostic row; path is never absolute. */
  record IndexDiagnostic(String code, String message, String path, long line, long column) {}
}
