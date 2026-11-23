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
    List<Path> sourceFiles = sourceFiles(unitRoots).stream()
        .map(path -> path.toAbsolutePath().normalize())
        .toList();
    if (sourceFiles.isEmpty()) {
      return JavacResolverResult.empty(cell.id());
    }
    List<Path> normalizedRoots = unitRoots.stream()
        .map(root -> root.toAbsolutePath().normalize())
        .toList();
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
      javax.lang.model.util.Elements elements = task.getElements();
      List<Diagnostic<? extends JavaFileObject>> diagnostics = List.copyOf(collector.getDiagnostics());
      boolean taskHasError = diagnostics.stream()
          .anyMatch(item -> item.getKind() == Diagnostic.Kind.ERROR);
      Path siteFile = normalizedRoots.get(0).resolve(cell.siteFile()).normalize();
      Facts scanner = new Facts(
          cell, trees, types, elements, parsed, diagnostics, taskHasError, normalizedRoots, siteFile);
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
          List.of("-proc:none", "--release", "17", "-encoding", "UTF-8",
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
    private final javax.lang.model.util.Elements elements;
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
        javax.lang.model.util.Elements elements,
        Iterable<? extends CompilationUnitTree> parsed,
        List<Diagnostic<? extends JavaFileObject>> diagnostics,
        boolean taskHasError,
        List<Path> unitRoots,
        Path siteFile) {
      this.cell = cell;
      this.trees = trees;
      this.types = types;
      this.elements = elements;
      this.diagnostics = diagnostics;
      this.taskHasError = taskHasError;
      this.unitRoots = unitRoots;
      this.siteFile = siteFile;
    }

    JavacResolverResult result() {
      facts.sort(Comparator.comparingInt(JavacResolverFact::offset)
          .thenComparing(JavacResolverFact::serviceType));
      abstentions.sort(Comparator.comparingInt(JavacResolverAbstention::offset));
      return new JavacResolverResult(cell.id(), facts, abstentions);
    }

    @Override
    public Void visitMethod(com.sun.source.tree.MethodTree node, Void unused) {
      TreePath methodPath = getCurrentPath();
      if (isProbeClassMethod(methodPath)) {
        javax.lang.model.element.ExecutableElement method =
            (javax.lang.model.element.ExecutableElement) trees.getElement(methodPath);
        if (method.getKind() == ElementKind.METHOD) {
          if (cell.family() == JavacResolverCell.Family.TYPE_USE
              || cell.family() == JavacResolverCell.Family.TYPE_SHAPE) {
            emitParameterFacts(methodPath, node, method);
            emitReturnFact(methodPath, method);
          } else if (cell.family() == JavacResolverCell.Family.ANNOTATION_NAME
              || cell.family() == JavacResolverCell.Family.ANNOTATION_ARGUMENT) {
            emitAnnotationFacts(methodPath, method);
          }
        }
      }
      return super.visitMethod(node, unused);
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
      TreePath invocationPath = getCurrentPath();
      if (isSiteFile(invocationPath)) {
        if (cell.family() == JavacResolverCell.Family.TYPE_SHAPE) {
          emitInvocationFact(invocationPath, node);
          return super.visitMethodInvocation(node, unused);
        }
        if (cell.family() == JavacResolverCell.Family.REFLECTION_SOURCE_BOUND) {
          emitReflectionFact(invocationPath, node);
          return super.visitMethodInvocation(node, unused);
        }
      }
      return superVisitMethodInvocation(node, unused, invocationPath);
    }

    @Override
    public Void visitNewClass(com.sun.source.tree.NewClassTree node, Void unused) {
      TreePath path = getCurrentPath();
      if (cell.family() == JavacResolverCell.Family.TYPE_SHAPE && isSiteFile(path)) {
        Element constructor = trees.getElement(path);
        if (constructor instanceof javax.lang.model.element.ExecutableElement ctor) {
          long position = startPosition(path);
          if (position >= 0) {
            addShapeFact(path, position,
                memberName(ctor.getEnclosingElement(), "<init>", ctor), ctor);
          }
        }
      }
      return super.visitNewClass(node, unused);
    }

    private Void superVisitMethodInvocation(
        MethodInvocationTree node, Void unused, TreePath invocationPath) {
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
      long end = endPosition(invocationPath);
      int endLine = end < 0 ? line : lineNumber(invocationPath, end);
      int endColumn = end < 0 ? column : columnNumber(invocationPath, end);
      Element method = trees.getElement(new TreePath(invocationPath, selectTree));
      if (selectTree instanceof com.sun.source.tree.IdentifierTree && !isServiceLoaderLoad(method)) {
        return super.visitMethodInvocation(node, unused);
      }
      Element receiver = selectTree instanceof MemberSelectTree memberSelect
          ? trees.getElement(new TreePath(new TreePath(invocationPath, memberSelect),
              memberSelect.getExpression()))
          : trees.getElement(invocationPath);
      if (isServiceLoaderLoad(method)) {
        addFact(invocationPath, position, line, column, method);
      } else {
        addAbstention((int) position, line, column,
            reason(method, receiver, line, column, endLine, endColumn));
      }
      return super.visitMethodInvocation(node, unused);
    }

    /** True when the method is declared by the cell's probe class in the site file. */
    private boolean isProbeClassMethod(TreePath methodPath) {
      if (!isSiteFile(methodPath)) {
        return false;
      }
      TreePath parent = methodPath.getParentPath();
      if (parent == null || !(parent.getLeaf() instanceof ClassTree)) {
        return false;
      }
      Element enclosing = trees.getElement(parent);
      return enclosing instanceof TypeElement type
          && cell.owner().equals(type.getQualifiedName().toString());
    }

    private void emitParameterFacts(
        TreePath methodPath, com.sun.source.tree.MethodTree node,
        javax.lang.model.element.ExecutableElement method) {
      for (int index = 0; index < node.getParameters().size(); index++) {
        com.sun.source.tree.VariableTree parameter = node.getParameters().get(index);
        TreePath parameterPath = new TreePath(methodPath, parameter);
        Element parameterElement = trees.getElement(parameterPath);
        if (parameterElement == null) {
          continue;
        }
        long position = startPosition(parameterPath);
        if (position < 0) {
          continue;
        }
        addShapeFact(parameterPath, position,
            canonicalType(parameterElement.asType(), types), method);
      }
    }

    private void emitReturnFact(TreePath methodPath,
        javax.lang.model.element.ExecutableElement method) {
      if (method.getReturnType().getKind() == javax.lang.model.type.TypeKind.VOID) {
        return;
      }
      long position = startPosition(methodPath);
      if (position < 0) {
        return;
      }
      addShapeFact(methodPath, position, canonicalType(method.getReturnType(), types), method);
    }

    private void emitInvocationFact(TreePath invocationPath, MethodInvocationTree node) {
      String name = selectedName(node.getMethodSelect());
      if ("super".equals(name) || "this".equals(name)) {
        return;
      }
      Element method = trees.getElement(new TreePath(invocationPath, node.getMethodSelect()));
      if (!(method instanceof javax.lang.model.element.ExecutableElement executable)) {
        return;
      }
      long position = startPosition(invocationPath);
      if (position < 0) {
        return;
      }
      addShapeFact(invocationPath, position,
          memberName(executable.getEnclosingElement(),
              executable.getSimpleName().toString(), executable), executable);
    }

    private void emitReflectionFact(TreePath invocationPath, MethodInvocationTree node) {
      if (!"forName".equals(selectedName(node.getMethodSelect()))) {
        return;
      }
      long position = startPosition(invocationPath);
      if (!(node.getArguments().size() == 1
          && node.getArguments().get(0) instanceof com.sun.source.tree.LiteralTree literal
          && literal.getValue() instanceof String target)) {
        // Uncovered forName shape (3-arg overload, computed name): visible
        // abstention, never a silent skip.
        if (position >= 0) {
          int skipLine = lineNumber(invocationPath, position);
          addAbstention((int) position, skipLine, columnNumber(invocationPath, position),
              "NON_LITERAL_FORNAME");
        }
        return;
      }
      Element method = trees.getElement(new TreePath(invocationPath, node.getMethodSelect()));
      if (method == null || method.getKind() != ElementKind.METHOD
          || !(method.getEnclosingElement() instanceof TypeElement owner)
          || !"java.lang.Class".contentEquals(owner.getQualifiedName())) {
        return;
      }
      TypeElement bound = elements.getTypeElement(target);
      boolean sourceBound = bound != null && trees.getPath(bound) != null;
      boolean resolvable = bound != null;
      String certainty = sourceBound && !taskHasError ? "DEFINITE" : "POSSIBLE";
      int line = lineNumber(invocationPath, position);
      int column = columnNumber(invocationPath, position);
      facts.add(new JavacResolverFact(
          cell.id(), cell.siteFile(), topLevelName(invocationPath), line, column,
          (int) position, target, "java.lang.Class", "forName",
          accessibility(method), certainty,
          sourceBound ? "source-bound" : resolvable ? "runtime-only" : "unresolved-target",
          diagnosticsFor(line), "", ""));
    }

    private void addShapeFact(
        TreePath path, long position, String canonicalValue,
        javax.lang.model.element.ExecutableElement method) {
      int line = lineNumber(path, position);
      int column = columnNumber(path, position);
      facts.add(new JavacResolverFact(
          cell.id(), cell.siteFile(), topLevelName(path), line, column, (int) position,
          canonicalValue, ownerName(method), method.getSimpleName().toString(),
          accessibility(method),
          taskHasError ? "POSSIBLE" : "DEFINITE", "", diagnosticsFor(line), "", ""));
    }

    private static String ownerName(Element element) {
      Element enclosing = element.getEnclosingElement();
      if (enclosing instanceof TypeElement type) {
        return type.getQualifiedName().toString();
      }
      return enclosing.getSimpleName().toString();
    }

    private String memberName(Element owner, String name,
        javax.lang.model.element.ExecutableElement executable) {
      StringBuilder signature = new StringBuilder();
      if (owner instanceof TypeElement type) {
        signature.append(type.getQualifiedName().toString());
      } else {
        signature.append(owner.getSimpleName().toString());
      }
      signature.append('.').append(name).append('(');
      List<? extends javax.lang.model.element.VariableElement> parameters =
          executable.getParameters();
      for (int index = 0; index < parameters.size(); index++) {
        if (index > 0) {
          signature.append(',');
        }
        signature.append(canonicalType(parameters.get(index).asType(), types));
      }
      signature.append(')');
      return signature.toString();
    }

    private void emitAnnotationFacts(
        TreePath methodPath, javax.lang.model.element.ExecutableElement method) {
      if (!(methodPath.getLeaf() instanceof com.sun.source.tree.MethodTree node)) {
        return;
      }
      for (int index = 0; index < node.getModifiers().getAnnotations().size(); index++) {
        com.sun.source.tree.AnnotationTree annotation =
            node.getModifiers().getAnnotations().get(index);
        TreePath annotationPath = TreePath.getPath(methodPath, annotation);
        if (annotationPath == null) {
          continue;
        }
        long position = startPosition(annotationPath);
        if (position < 0) {
          continue;
        }
        int line = lineNumber(annotationPath, position);
        int column = columnNumber(annotationPath, position);
        String qualified = annotationTypeName(annotationPath, annotation);
        boolean resolved = annotationResolved(annotationPath, annotation);
        String certainty = resolved && !taskHasError ? "DEFINITE" : "POSSIBLE";
        String annotationName = qualified;
        String annotationValue = "";
        if (cell.family() == JavacResolverCell.Family.ANNOTATION_ARGUMENT) {
          annotationValue = renderArguments(annotationPath, annotation);
        }
        facts.add(new JavacResolverFact(
            cell.id(), cell.siteFile(), topLevelName(annotationPath), line, column,
            (int) position,
            cell.family() == JavacResolverCell.Family.ANNOTATION_NAME
                ? qualified : annotationValue,
            ownerName(method), method.getSimpleName().toString(), accessibility(method),
            certainty, resolved ? "" : "error-type",
            resolved ? List.of() : diagnosticsFor(line),
            annotationName, annotationValue));
      }
    }

    private String annotationTypeName(
        TreePath annotationPath, com.sun.source.tree.AnnotationTree annotation) {
      Element element = trees.getElement(
          new TreePath(annotationPath, annotation.getAnnotationType()));
      if (element instanceof TypeElement type
          && type.asType().getKind() != javax.lang.model.type.TypeKind.ERROR) {
        String qualified = type.getQualifiedName().toString();
        if (!qualified.isEmpty()) {
          return qualified;
        }
      }
      return annotation.getAnnotationType().toString();
    }

    private boolean annotationResolved(
        TreePath annotationPath, com.sun.source.tree.AnnotationTree annotation) {
      Element element = trees.getElement(
          new TreePath(annotationPath, annotation.getAnnotationType()));
      return element instanceof TypeElement type
          && type.asType().getKind() != javax.lang.model.type.TypeKind.ERROR;
    }

    private String renderArguments(
        TreePath annotationPath, com.sun.source.tree.AnnotationTree annotation) {
      List<String> rendered = new ArrayList<>();
      for (int index = 0; index < annotation.getArguments().size(); index++) {
        ExpressionTree argument = annotation.getArguments().get(index);
        TreePath argumentPath = new TreePath(annotationPath, argument);
        if (argument instanceof com.sun.source.tree.AssignmentTree assignment) {
          rendered.add(assignment.getVariable().toString() + "="
              + renderValue(new TreePath(argumentPath, assignment.getExpression())));
        } else {
          rendered.add(renderValue(argumentPath));
        }
      }
      return String.join(";", rendered);
    }

    private String renderValue(TreePath valuePath) {
      ExpressionTree value = (ExpressionTree) valuePath.getLeaf();
      if (value instanceof com.sun.source.tree.LiteralTree literal) {
        return literal.getValue() == null ? "null" : literal.getValue().toString();
      }
      if (value instanceof com.sun.source.tree.AnnotationTree nested) {
        TreePath nestedPath = TreePath.getPath(valuePath, nested);
        return "@" + annotationTypeName(nestedPath == null ? valuePath : nestedPath, nested)
            + "(" + renderArguments(nestedPath == null ? valuePath : nestedPath, nested) + ")";
      }
      if (value instanceof com.sun.source.tree.NewArrayTree array) {
        List<String> items = new ArrayList<>();
        for (int index = 0; index < array.getInitializers().size(); index++) {
          items.add(renderValue(new TreePath(valuePath, array.getInitializers().get(index))));
        }
        return String.join(",", items);
      }
      if (value instanceof MemberSelectTree select
          && "class".contentEquals(select.getIdentifier())) {
        TypeMirror mirror = trees.getTypeMirror(new TreePath(valuePath, select.getExpression()));
        if (mirror != null) {
          TypeMirror erased = types.erasure(mirror);
          if (erased instanceof DeclaredType declared
              && declared.asElement() instanceof TypeElement named) {
            String qualified = named.getQualifiedName().toString();
            if (!qualified.isEmpty()) {
              return qualified;
            }
          }
          return erased.toString();
        }
      }
      Element element = trees.getElement(valuePath);
      if (element instanceof javax.lang.model.element.VariableElement variable
          && variable.getEnclosingElement() instanceof TypeElement owner) {
        return owner.getQualifiedName() + "." + variable.getSimpleName();
      }
      return value.toString();
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
      ClassLiteral literal = null;
      for (ExpressionTree argument : node.getArguments()) {
        literal = classLiteralType(invocationPath, argument);
        if (literal != null) {
          break;
        }
      }
      if (literal == null) {
        addAbstention((int) position, line, column, "NO_CLASS_LITERAL");
        return;
      }
      String certainty = taskHasError || literal.error() ? "POSSIBLE" : "DEFINITE";
      List<JavacResolverDiagnostic> attached = diagnosticsFor(line);
      facts.add(new JavacResolverFact(
          cell.id(),
          cell.siteFile(),
          topLevelName(invocationPath),
          line,
          column,
          (int) position,
          literal.type(),
          SERVICE_LOADER,
          LOAD,
          accessibility(method),
          certainty,
          literal.error() ? "error-type" : "",
          attached));
    }

    private record ClassLiteral(String type, boolean error) {}

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

    private String reason(
        Element method, Element receiver, int line, int column, int endLine, int endColumn) {
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
      List<JavacResolverDiagnostic> local = diagnosticsAt(line, column, endLine, endColumn);
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

    private ClassLiteral classLiteralType(TreePath invocationPath, ExpressionTree argument) {
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
      TypeMirror erased = types.erasure(mirror);
      if (erased.getKind() == javax.lang.model.type.TypeKind.ERROR) {
        String name = ((DeclaredType) erased).asElement().getSimpleName().toString();
        return name.isEmpty() ? null : new ClassLiteral(name, true);
      }
      if (erased.getKind() != javax.lang.model.type.TypeKind.DECLARED) {
        return null;
      }
      Element element = ((DeclaredType) erased).asElement();
      if (!(element instanceof TypeElement named)) {
        return null;
      }
      if (named.getNestingKind() == javax.lang.model.element.NestingKind.LOCAL
          || named.getNestingKind() == javax.lang.model.element.NestingKind.ANONYMOUS) {
        return null;
      }
      String canonical = named.getQualifiedName().toString();
      return canonical.isEmpty() ? null : new ClassLiteral(canonical, false);
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

    private boolean sameSource(JavaFileObject source) {
      if (source == null) {
        return false;
      }
      try {
        return Path.of(source.toUri()).normalize().equals(siteFile);
      } catch (IllegalArgumentException error) {
        return false;
      }
    }

    private static boolean within(
        Diagnostic<? extends JavaFileObject> item,
        int line, int column, int endLine, int endColumn) {
      long itemLine = item.getLineNumber();
      long itemColumn = item.getColumnNumber();
      if (itemLine < line || itemLine > endLine) {
        return false;
      }
      if (itemLine == line && itemColumn < column) {
        return false;
      }
      return itemLine != endLine || itemColumn <= endColumn;
    }

    private List<JavacResolverDiagnostic> diagnosticsAt(
        int line, int column, int endLine, int endColumn) {
      return diagnostics.stream()
          .filter(item -> item.getKind() == Diagnostic.Kind.ERROR)
          .filter(item -> sameSource(item.getSource()))
          .filter(item -> within(item, line, column, endLine, endColumn))
          .map(item -> new JavacResolverDiagnostic(
              item.getCode(), item.getMessage(Locale.ROOT), relativePath(item.getSource()),
              item.getLineNumber(), item.getColumnNumber()))
          .sorted(Comparator.comparing(JavacResolverDiagnostic::path)
              .thenComparingLong(JavacResolverDiagnostic::line)
              .thenComparingLong(JavacResolverDiagnostic::column)
              .thenComparing(JavacResolverDiagnostic::code)
              .thenComparing(JavacResolverDiagnostic::message))
          .toList();
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

    private long endPosition(TreePath path) {
      SourcePositions positions = trees.getSourcePositions();
      return positions.getEndPosition(path.getCompilationUnit(), path.getLeaf());
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
