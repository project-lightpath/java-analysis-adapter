package com.projectlightpath.analysis.java;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Test-scope javac/Trees prototype: the authority for source-defined ServiceLoader facts.
 *
 * <p>No production code calls this class. The spike harness compares it with the
 * textual path without changing default adapter output.
 */
final class JavacSourceSymbolResolver {
  private static final String SERVICE_LOADER = "java.util.ServiceLoader";
  private static final String LOAD = "load";

  private JavacSourceSymbolResolver() {}

  static JavacResolverResult resolve(
      JavacResolverCell cell, List<Path> unitRoots, Path classesDirectory) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("a JDK compiler is required for the javac prototype");
    }
    List<Path> sourceFiles = sourceFiles(unitRoots);
    if (sourceFiles.isEmpty()) {
      return JavacResolverResult.empty(cell.id());
    }
    DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
    Path emptyClasspath = Files.createDirectories(classesDirectory.resolve("empty-cp"));
    Path emptySourcepath = Files.createDirectories(classesDirectory.resolve("empty-sp"));
    try (StandardJavaFileManager manager =
        compiler.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8)) {
      Iterable<? extends JavaFileObject> units = manager.getJavaFileObjectsFromPaths(sourceFiles);
      JavacTask task = (JavacTask) compiler.getTask(
          null,
          manager,
          collector,
          List.of(
              "-proc:none",
              "--release",
              Integer.toString(cell.release()),
              "-encoding",
              "UTF-8",
              "-classpath",
              emptyClasspath.toString(),
              "-sourcepath",
              emptySourcepath.toString(),
              "-d",
              classesDirectory.toString()),
          null,
          units);
      Iterable<? extends CompilationUnitTree> parsed = task.parse();
      task.analyze();
      Trees trees = Trees.instance(task);
      Types types = task.getTypes();
      List<Diagnostic<? extends JavaFileObject>> diagnostics = List.copyOf(collector.getDiagnostics());
      boolean taskHasError = diagnostics.stream()
          .anyMatch(item -> item.getKind() == Diagnostic.Kind.ERROR);
      Path siteFile = unitRoots.get(0).resolve(cell.siteFile()).normalize();
      Facts scanner = new Facts(cell, trees, types, parsed, diagnostics, taskHasError, unitRoots, siteFile);
      for (CompilationUnitTree unit : parsed) {
        scanner.scan(unit, null);
      }
      return scanner.result();
    }
  }

  /** Resolves a parameter type by javac for the annotation/varargs probes. */
  static String parameterType(
      String source, String className, String methodName, int parameterIndex, Path workRoot)
      throws IOException {
    Path sourceRoot = workRoot.resolve("probe");
    Path file = sourceRoot.resolve(className.replace('.', '/') + ".java");
    Files.createDirectories(file.getParent());
    Files.writeString(file, source, StandardCharsets.UTF_8);
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
    Path classes = Files.createDirectories(workRoot.resolve("probe-classes"));
    try (StandardJavaFileManager manager =
        compiler.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8)) {
      JavaFileObject unit = manager.getJavaFileObjectsFromPaths(List.of(file)).iterator().next();
      JavacTask task = (JavacTask) compiler.getTask(
          null, manager, collector,
          List.of("-proc:none", "--release", "21", "-encoding", "UTF-8",
              "-classpath", classes.toString(), "-d", classes.toString()),
          null, List.of(unit));
      task.parse();
      task.analyze();
      Types types = task.getTypes();
      TypeElement type = task.getElements().getTypeElement(className);
      if (type == null) {
        return "<unresolved-type>";
      }
      for (Element member : type.getEnclosedElements()) {
        if (member.getKind() == ElementKind.METHOD
            && member.getSimpleName().contentEquals(methodName)
            && ((javax.lang.model.element.ExecutableElement) member).getParameters().size()
                > parameterIndex) {
          TypeMirror mirror =
              ((javax.lang.model.element.ExecutableElement) member).getParameters().get(parameterIndex).asType();
          return canonicalType(mirror, types);
        }
      }
      return "<unresolved-method>";
    }
  }

  private static List<Path> sourceFiles(List<Path> roots) throws IOException {
    List<Path> files = new ArrayList<>();
    for (Path root : roots) {
      try (Stream<Path> paths = Files.walk(root)) {
        files.addAll(paths.filter(path -> path.toString().endsWith(".java")).toList());
      }
    }
    files.sort(Comparator.comparing(Path::toString));
    return files;
  }

  private static final class Facts extends TreePathScanner<Void, Void> {
    private final JavacResolverCell cell;
    private final Trees trees;
    private final Types types;
    private final List<Diagnostic<? extends JavaFileObject>> diagnostics;
    private final boolean taskHasError;
    private final List<Path> unitRoots;
    private final Path siteFile;
    private final List<JavacResolverFact> facts = new ArrayList<>();
    private final List<JavacResolverAbstention> abstentions = new ArrayList<>();

    Facts(
        JavacResolverCell cell,
        Trees trees,
        Types types,
        Iterable<? extends CompilationUnitTree> parsed,
        List<Diagnostic<? extends JavaFileObject>> diagnostics,
        boolean taskHasError,
        List<Path> unitRoots,
        Path siteFile) {
      this.cell = cell;
      this.trees = trees;
      this.types = types;
      this.diagnostics = diagnostics;
      this.taskHasError = taskHasError;
      this.unitRoots = unitRoots;
      this.siteFile = siteFile;
    }

    JavacResolverResult result() {
      java.util.Set<Integer> shadowLines = abstentions.stream()
          .filter(item -> item.reason().equals("SHADOWED"))
          .map(JavacResolverAbstention::line)
          .collect(java.util.stream.Collectors.toSet());
      List<JavacResolverFact> stableFacts = new ArrayList<>();
      for (JavacResolverFact fact : facts) {
        if ("POSSIBLE".equals(fact.certainty())
            && !shadowLines.isEmpty()
            && diagnostics.stream()
                .filter(item -> item.getKind() == Diagnostic.Kind.ERROR)
                .findAny()
                .isPresent()
            && diagnostics.stream()
                .filter(item -> item.getKind() == Diagnostic.Kind.ERROR)
                .allMatch(item -> shadowLines.contains((int) item.getLineNumber()))) {
          stableFacts.add(new JavacResolverFact(
              fact.cellId(), fact.path(), fact.topLevel(), fact.line(), fact.column(),
              fact.offset(), fact.serviceType(), fact.owner(), fact.member(),
              fact.accessibility(), "DEFINITE", "shadow-only-errors", fact.diagnostics()));
        } else {
          stableFacts.add(fact);
        }
      }
      stableFacts.sort(Comparator.comparingInt(JavacResolverFact::offset)
          .thenComparing(JavacResolverFact::serviceType));
      abstentions.sort(Comparator.comparingInt(JavacResolverAbstention::offset));
      return new JavacResolverResult(cell.id(), stableFacts, abstentions);
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
      TreePath invocationPath = getCurrentPath();
      ExpressionTree selectTree = node.getMethodSelect();
      String selectedName = selectedName(selectTree);
      if (!LOAD.equals(selectedName) || !isSiteFile(invocationPath)) {
        return super.visitMethodInvocation(node, unused);
      }
      long position = startPosition(invocationPath);
      if (position < 0) {
        addAbstention(0, 0, 0, "UNRESOLVED");
        return super.visitMethodInvocation(node, unused);
      }
      int line = lineNumber(invocationPath, position);
      int column = columnNumber(invocationPath, position);
      Element method = trees.getElement(new TreePath(invocationPath, selectTree));
      Element receiver = selectTree instanceof MemberSelectTree memberSelect
          ? trees.getElement(new TreePath(new TreePath(invocationPath, memberSelect),
              memberSelect.getExpression()))
          : trees.getElement(invocationPath);
      if (isServiceLoaderLoad(method)) {
        addFact(invocationPath, position, line, column, method);
      } else {
        addAbstention((int) position, line, column, reason(method, receiver, line));
      }
      return super.visitMethodInvocation(node, unused);
    }

    private static String selectedName(ExpressionTree selectTree) {
      if (selectTree instanceof MemberSelectTree memberSelect) {
        return memberSelect.getIdentifier().toString();
      }
      if (selectTree instanceof com.sun.source.tree.IdentifierTree identifier) {
        return identifier.getName().toString();
      }
      return "";
    }

    private boolean isSiteFile(TreePath path) {
      CompilationUnitTree unit = path.getCompilationUnit();
      try {
        return Path.of(unit.getSourceFile().toUri()).normalize().equals(siteFile);
      } catch (IllegalArgumentException error) {
        return false;
      }
    }

    private boolean isServiceLoaderLoad(Element method) {
      if (method == null
          || method.getKind() != ElementKind.METHOD
          || !method.getSimpleName().contentEquals(LOAD)) {
        return false;
      }
      Element owner = method.getEnclosingElement();
      return owner instanceof TypeElement type
          && SERVICE_LOADER.contentEquals(type.getQualifiedName());
    }

    private void addFact(
        TreePath invocationPath, long position, int line, int column, Element method) {
      MethodInvocationTree node = (MethodInvocationTree) invocationPath.getLeaf();
      String serviceType = node.getArguments().isEmpty()
          ? null : classLiteralType(invocationPath, node.getArguments().get(0));
      if (serviceType == null) {
        addAbstention((int) position, line, column, "NO_CLASS_LITERAL");
        return;
      }
      boolean errorType = serviceType.contains("<error>") || serviceType.isEmpty();
      String certainty = taskHasError || errorType ? "POSSIBLE" : "DEFINITE";
      List<JavacResolverDiagnostic> attached = diagnosticsFor(line);
      facts.add(new JavacResolverFact(
          cell.id(),
          cell.siteFile(),
          topLevelName(invocationPath),
          line,
          column,
          (int) position,
          serviceType,
          SERVICE_LOADER,
          LOAD,
          accessibility(method),
          certainty,
          "",
          attached));
    }

    private static String accessibility(Element method) {
      boolean isPublic = method.getModifiers().contains(Modifier.PUBLIC);
      boolean isStatic = method.getModifiers().contains(Modifier.STATIC);
      String access = isPublic ? "PUBLIC" : "PACKAGE";
      return isStatic ? access + "_STATIC" : access;
    }

    private void addAbstention(int position, int line, int column, String reason) {
      abstentions.add(new JavacResolverAbstention(
          cell.id(), cell.siteFile(), line, column, position, reason, diagnosticsFor(line)));
    }

    private String reason(Element method, Element receiver, int line) {
      if (receiver != null && isVariable(receiver)) {
        return "SHADOWED";
      }
      if (method != null && method.getKind() == ElementKind.CLASS) {
        return "AMBIGUOUS";
      }
      if (method != null && method.getKind() == ElementKind.METHOD) {
        Element owner = method.getEnclosingElement();
        if (owner instanceof TypeElement type && type.asType().getKind()
            == javax.lang.model.type.TypeKind.ERROR) {
          return "ERROR_TYPE";
        }
        return "SHADOWED";
      }
      List<JavacResolverDiagnostic> local = diagnosticsFor(line);
      if (local.stream().anyMatch(item -> item.message().toLowerCase(Locale.ROOT).contains("ambiguous"))) {
        return "AMBIGUOUS";
      }
      if (local.stream().anyMatch(item -> missingInput(item.message()))) {
        return "MISSING_INPUT";
      }
      if (taskHasError) {
        return "ERROR_TYPE";
      }
      return "SHADOWED";
    }

    private static boolean isVariable(Element element) {
      return switch (element.getKind()) {
        case FIELD, LOCAL_VARIABLE, PARAMETER, BINDING_VARIABLE, RESOURCE_VARIABLE, EXCEPTION_PARAMETER -> true;
        default -> false;
      };
    }

    private static boolean missingInput(String message) {
      String lower = message.toLowerCase(Locale.ROOT);
      return lower.contains("does not exist")
          || lower.contains("cannot find symbol")
          || lower.contains("cannot access")
          || lower.contains("package") && lower.contains("not found");
    }

    private String classLiteralType(TreePath invocationPath, ExpressionTree argument) {
      if (!(argument instanceof MemberSelectTree select)
          || !select.getIdentifier().contentEquals("class")) {
        return null;
      }
      TreePath argumentPath = new TreePath(invocationPath, argument);
      TreePath typePath = new TreePath(argumentPath, select.getExpression());
      TypeMirror mirror = trees.getTypeMirror(typePath);
      if (mirror == null) {
        return null;
      }
      String canonical = canonicalType(mirror, types);
      return canonical.isEmpty() ? null : canonical;
    }

    private String topLevelName(TreePath path) {
      CompilationUnitTree unit = path.getCompilationUnit();
      String packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
      String outer = "";
      for (TreePath current = path; current != null; current = current.getParentPath()) {
        if (current.getLeaf() instanceof ClassTree type
            && !type.getSimpleName().isEmpty()
            && !type.getSimpleName().toString().matches("\\d+")) {
          outer = type.getSimpleName().toString();
        }
      }
      return packageName.isEmpty() ? outer : packageName + "." + outer;
    }

    private List<JavacResolverDiagnostic> diagnosticsFor(int line) {
      return diagnostics.stream()
          .filter(item -> item.getKind() == Diagnostic.Kind.ERROR)
          .filter(item -> item.getLineNumber() <= 0 || item.getLineNumber() == line || taskHasError)
          .map(item -> new JavacResolverDiagnostic(
              item.getCode(),
              item.getMessage(Locale.ROOT),
              relativePath(item.getSource()),
              item.getLineNumber(),
              item.getColumnNumber()))
          .sorted(Comparator.comparing(JavacResolverDiagnostic::path)
              .thenComparingLong(JavacResolverDiagnostic::line)
              .thenComparingLong(JavacResolverDiagnostic::column)
              .thenComparing(JavacResolverDiagnostic::code)
              .thenComparing(JavacResolverDiagnostic::message))
          .toList();
    }

    private String relativePath(JavaFileObject source) {
      if (source == null) {
        return "";
      }
      try {
        Path absolute = Path.of(source.toUri()).normalize();
        for (Path root : unitRoots) {
          Path normalizedRoot = root.normalize();
          if (absolute.startsWith(normalizedRoot)) {
            return normalizedRoot.relativize(absolute).toString();
          }
        }
        return absolute.getFileName().toString();
      } catch (IllegalArgumentException error) {
        return "";
      }
    }

    private long startPosition(TreePath path) {
      SourcePositions positions = trees.getSourcePositions();
      return positions.getStartPosition(path.getCompilationUnit(), path.getLeaf());
    }

    private int lineNumber(TreePath path, long position) {
      return (int) path.getCompilationUnit().getLineMap().getLineNumber(position);
    }

    private int columnNumber(TreePath path, long position) {
      return (int) path.getCompilationUnit().getLineMap().getColumnNumber(position);
    }
  }

  private static String canonicalType(TypeMirror mirror, Types types) {
    TypeMirror erased = types.erasure(mirror);
    return switch (erased.getKind()) {
      case ARRAY -> canonicalType(((ArrayType) erased).getComponentType(), types) + "[]";
      case DECLARED -> declaredName((DeclaredType) erased);
      case ERROR -> errorName((DeclaredType) erased);
      default -> erased.toString();
    };
  }

  private static String declaredName(DeclaredType type) {
    Element element = type.asElement();
    if (element instanceof TypeElement named) {
      String qualified = named.getQualifiedName().toString();
      return qualified.isEmpty() ? named.getSimpleName().toString() : qualified;
    }
    return element.getSimpleName().toString();
  }

  private static String errorName(DeclaredType type) {
    Element element = type.asElement();
    if (element instanceof TypeElement named) {
      String qualified = named.getQualifiedName().toString();
      return qualified.isEmpty() ? named.getSimpleName().toString() : qualified;
    }
    return type.toString();
  }
}
