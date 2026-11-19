package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.projectlightpath.analysis.java.SourceMetadataScannerState.AnonymousType;
import com.projectlightpath.analysis.java.SourceMetadataScannerState.FieldReference;
import com.projectlightpath.analysis.java.SourceMetadataScannerState.MethodInfo;
import com.projectlightpath.analysis.java.SourceMetadataScannerState.SourceFile;
import com.projectlightpath.analysis.java.SourceMetadataScannerState.TypeInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Characterization of the source-backed extraction used by the reflection and
 * framework scans, plus the per-file reuse contract of issue #11.
 */
class ScannerExtractionCacheTest {
  @TempDir Path temp;

  private static final String SAMPLE = """
      package demo;

      public class Sample {
        static class Nested {
          Object nested() { return null; }
        }
        interface Contract {
          default Object run() { return null; }
        }
        private int first = 1, second = 2;
        private Object holder = new Object() {
          Object anonymousBody() { return null; }
        };
        @Deprecated
        Sample() { }
        static {
          System.out.println("static");
        }
        {
          System.out.println("instance");
        }
        @Deprecated
        public Object annotated(String value) { return value; }
        Object generic() { return null; }
      }
      """;

  @Test
  void methodExtractionIsStableAndCachedPerFile() throws IOException {
    SourceFile file = sourceFile("method-cache", SAMPLE);

    List<MethodInfo> first = SourceMetadataScannerTextLayer13.methods(file);
    List<MethodInfo> second = SourceMetadataScannerTextLayer13.methods(file);

    assertEquals(
        List.of("nested", "run", "anonymousBody", "Sample", "annotated", "generic"),
        first.stream().map(MethodInfo::name).toList(),
        first.toString());
    assertEquals(
        List.of("", "", "", "", "String value", ""),
        first.stream().map(MethodInfo::parameters).toList(),
        first.toString());
    assertEquals(List.of("Object", "Object", "Object", ";", "Object", "Object"),
        first.stream().map(MethodInfo::returnType).toList(), first.toString());
    assertTrue(first.stream().filter(method -> method.name().equals("annotated"))
        .allMatch(method -> method.line() == 22), first.toString());
    assertSame(first, second, "the per-file method extraction must be reused");
  }

  @Test
  void constructorTypeAndAnonymousExtractionIsStable() throws IOException {
    SourceFile file = sourceFile("constructor-cache", SAMPLE);

    List<MethodInfo> constructors = SourceMetadataScannerTextLayer11.constructors(file);
    List<TypeInfo> types = SourceMetadataScannerTextLayer08.typeDeclarations(file);
    List<AnonymousType> anonymous = SourceMetadataScannerTextLayer15.anonymousTypes(file);

    assertEquals(1, constructors.size(), constructors.toString());
    assertEquals("<init>", constructors.get(0).name(), constructors.toString());
    assertEquals(
        List.of("Sample", "Nested", "Contract"),
        types.stream().map(TypeInfo::name).toList(),
        types.toString());
    assertEquals(
        List.of("class", "class", "interface"),
        types.stream().map(TypeInfo::kind).toList(),
        types.toString());
    assertEquals(1, anonymous.size(), anonymous.toString());
    assertSame(types, SourceMetadataScannerTextLayer08.typeDeclarations(file));
    assertSame(
        constructors, SourceMetadataScannerTextLayer11.constructors(file));
    assertSame(anonymous, SourceMetadataScannerTextLayer15.anonymousTypes(file));
  }

  @Test
  void braceDepthRestatedContractHoldsForEveryBoundaryCell() {
    List<String> sources = List.of(
        "",
        "{",
        "}",
        "a",
        "{}{}",
        "}}{{",
        "{{}}",
        "class A { void m() { if (x) { y(); } } }",
        "class A { String s = \"{\"; char c = '}'; }",
        "class A { String s = \"\"\"\n{ }\n\"\"\"; }",
        "@A({1, 2}) class B { }",
        "a\r\n{\r\n}\r\n");

    for (String source : sources) {
      String masked = SourceMetadataScannerTextLayer05.maskStringLiterals(
          JavaSourceText.stripComments(source));
      for (int index = -1; index <= masked.length() + 1; index++) {
        assertEquals(
            referenceBraceDepth(masked, index),
            SourceMetadataScannerTextLayer02.braceDepthAt(masked, index),
            "source=" + source + " index=" + index);
      }
    }
  }

  @Test
  void fieldInitializerEndRestatedContractHoldsForEveryBoundaryCell() {
    List<String> sources = List.of(
        "",
        ";",
        "= 1;",
        "= f(1;2);",
        "= a[1;2];",
        "= new X() { void m() { int a = 1; } };",
        "= {1;2}; x = 3;",
        "= 1",
        "= 1 distractor;",
        "= \"\"\"\n;\n\"\"\";",
        "= 1; = 2;");

    for (String source : sources) {
      for (int start = 0; start <= source.length(); start++) {
        assertEquals(
            referenceFieldInitializerEnd(source, start),
            SourceMetadataScannerTextLayer03.fieldInitializerEnd(source, start),
            "source=" + source + " start=" + start);
      }
    }
    assertThrows(
        StringIndexOutOfBoundsException.class,
        () -> SourceMetadataScannerTextLayer03.fieldInitializerEnd("x", -1),
        "a negative start is outside the contract and must not be silently clamped");
  }

  @Test
  void fileExtractsAreReusedForOneRun() throws IOException {
    SourceFile file = sourceFile("extracts-cache", SAMPLE);

    SourceMetadataScannerFileExtracts first = SourceMetadataScannerFileExtracts.of(file);
    SourceMetadataScannerFileExtracts second = SourceMetadataScannerFileExtracts.of(file);

    assertSame(first, second, "one run must reuse the extracts for a file");
    assertSame(first.methods(file), second.methods(file));
    assertSame(first.typeDeclarations(), second.typeDeclarations());
    assertSame(first.constructors(file), second.constructors(file));
    assertSame(first.anonymousTypes(), second.anonymousTypes());
    assertSame(first.literalMaskedSource(), second.literalMaskedSource());
    assertSame(first.declarationMaskedSource(), second.declarationMaskedSource());
  }

  @Test
  void extractsDoNotDependOnRequestOrder() throws IOException {
    SourceFile methodsFirst = sourceFile("order-methods-first", SAMPLE);
    List<MethodInfo> methods = SourceMetadataScannerFileExtracts.of(methodsFirst).methods(methodsFirst);
    List<TypeInfo> types = SourceMetadataScannerFileExtracts.of(methodsFirst).typeDeclarations();

    SourceFile typesFirst = sourceFile("order-types-first", SAMPLE);
    List<TypeInfo> otherTypes = SourceMetadataScannerFileExtracts.of(typesFirst).typeDeclarations();
    List<MethodInfo> otherMethods = SourceMetadataScannerFileExtracts.of(typesFirst).methods(typesFirst);

    assertEquals(methods, otherMethods);
    assertEquals(types, otherTypes);
  }

  @Test
  void samePathWithNewContentIsNotReused() throws IOException {
    String id = "freshness";
    String firstContent = "package demo; class Sample { Object first() { return null; } }\n";
    String secondContent =
        "package demo; class Sample { Object second() { return null; } }\n";

    SourceFile before = scannedSourceFile(id, firstContent, "demo/Sample.java");
    SourceMetadataScannerFileExtracts beforeExtracts = SourceMetadataScannerFileExtracts.of(before);
    assertEquals(List.of("first"), methodNames(before));

    SourceFile after = scannedSourceFile(id, secondContent, "demo/Sample.java");
    SourceMetadataScannerFileExtracts afterExtracts = SourceMetadataScannerFileExtracts.of(after);
    assertEquals(List.of("second"), methodNames(after));

    assertNotSame(beforeExtracts, afterExtracts,
        "a re-read of the same path must never answer from the previous content");
    assertEquals(List.of("first"), methodNames(before),
        "previously returned extracts keep their own values");

    SourceFile back = scannedSourceFile(id, firstContent, "demo/Sample.java");
    SourceMetadataScannerFileExtracts backExtracts = SourceMetadataScannerFileExtracts.of(back);
    assertNotSame(afterExtracts, backExtracts,
        "A->B->A must not answer from the B entry");
    assertEquals(List.of("first"), methodNames(back));
  }

  @Test
  void differentPathsDoNotShareExtracts() throws IOException {
    String source = "package demo; class Sample { Object run() { return null; } }\n";
    SourceFile one = scannedSourceFile("paths-one", source, "demo/One.java");
    SourceFile two = scannedSourceFile("paths-two", source, "demo/Two.java");

    assertNotSame(SourceMetadataScannerFileExtracts.of(one),
        SourceMetadataScannerFileExtracts.of(two));
  }

  @Test
  void braceIndexMatchesTheReferenceScanForEveryBoundaryCell() throws IOException {
    List<String> sources = List.of(
        "",
        "{",
        "}",
        "a",
        "{}{}",
        "}}{{",
        "{{}}",
        "class A { void m() { if (x) { y(); } } }",
        "class A { String s = \"{\"; char c = '}'; }",
        "class A { String s = \"\"\"\n{ }\n\"\"\"; }",
        "@A({1, 2}) class B { }",
        "a\r\n{\r\n}\r\n");
    for (String source : sources) {
      SourceFile file = syntheticSource(source);
      SourceMetadataScannerFileExtracts extracts = SourceMetadataScannerFileExtracts.of(file);
      String masked = extracts.literalMaskedSource();
      for (int offset = -1; offset <= masked.length() + 1; offset++) {
        assertEquals(
            referenceBraceDepth(masked, offset),
            extracts.braceDepthAt(offset),
            "source=" + source + " index=" + offset);
      }
    }
  }

  @Test
  void fieldExtentIndexMatchesTheReferenceScanForEveryBoundaryCell() throws IOException {
    List<String> sources = List.of(
        "",
        ";",
        "= 1;",
        "= f(1;2);",
        "= a[1;2];",
        "= new X() { void m() { int a = 1; } };",
        "= {1;2}; x = 3;",
        "= 1",
        "= 1 distractor;",
        "= \"\"\"\n;\n\"\"\";",
        "= 1; = 2;");
    for (String source : sources) {
      SourceFile file = syntheticSource(source);
      SourceMetadataScannerFileExtracts extracts = SourceMetadataScannerFileExtracts.of(file);
      String masked = extracts.literalMaskedSource();
      for (int start = 0; start <= masked.length(); start++) {
        assertEquals(
            referenceFieldInitializerEnd(masked, start),
            extracts.fieldInitializerEnd(start),
            "source=" + source + " start=" + start);
      }
    }
  }

  @Test
  void annotationArgumentsAreMaskedOnlyForDeclarations() throws IOException {
    String source = "package demo;\n"
        + "@Marker({1, 2})\n"
        + "class Sample {\n"
        + "  @Marker({\"a\", \"b\"})\n"
        + "  Object run(String value) { return value; }\n"
        + "}\n";
    SourceFile file = sourceFile("mask-variants", source);
    SourceMetadataScannerFileExtracts extracts = SourceMetadataScannerFileExtracts.of(file);

    assertEquals(file.structuralSource().length(), extracts.literalMaskedSource().length());
    assertEquals(file.structuralSource().length(), extracts.declarationMaskedSource().length());
    assertTrue(extracts.literalMaskedSource().contains("1, 2"),
        extracts.literalMaskedSource());
    assertFalse(extracts.declarationMaskedSource().contains("1, 2"),
        extracts.declarationMaskedSource());
  }

  @Test
  void samePathWithIdenticalContentIsReReadNotShared() throws IOException {
    String id = "identical-content";
    String source = "package demo; class Sample { Object run() { return null; } }\n";
    SourceFile first = scannedSourceFile(id, source, "demo/Sample.java");
    SourceFile second = scannedSourceFile(id, source, "demo/Sample.java");

    assertNotSame(first.structuralSource(), second.structuralSource(),
        "every read must produce its own structural string");
    assertNotSame(SourceMetadataScannerFileExtracts.of(first),
        SourceMetadataScannerFileExtracts.of(second),
        "freshness is per read, not per content equality");
  }

  @Test
  void initializerAttributionKeepsFieldAndBlockPrecedence() throws IOException {
    String id = "initializer-attribution";
    Path root = temp.resolve(id).resolve("src/main/java");
    Files.createDirectories(root.resolve("demo"));
    Files.writeString(root.resolve("demo/Sample.java"), """
        package demo;
        class Sample {
          static Object staticField = create("static");
          Object instanceField = create("instance");
          static { create("static-block"); }
          { create("instance-block"); }
          Object laterField = create("later");
          static Object create(Object value) { return value; }
        }
        """);
    SourceMetadataScanner scanner = new SourceMetadataScanner(contextFor(id, root));
    SourceFile file = scanner.files().get(0);

    assertInitializerAt(scanner, file, "create(\"static\")", "<clinit>", "staticField");
    assertInitializerAt(scanner, file, "create(\"instance\")", "<init>", "instanceField");
    assertInitializerAt(scanner, file, "create(\"static-block\")", "<clinit>", "");
    assertInitializerAt(scanner, file, "create(\"instance-block\")", "<init>", "");
    assertInitializerAt(scanner, file, "create(\"later\")", "<init>", "laterField");
  }

  @Test
  void visibleFieldSelectionKeepsTheNearestOwner() throws IOException {
    String id = "visible-field";
    Path root = temp.resolve(id).resolve("src/main/java");
    Files.createDirectories(root.resolve("demo"));
    Files.writeString(root.resolve("demo/Base.java"),
        "package demo; class Base { protected Object value; }\n");
    Files.writeString(root.resolve("demo/Sample.java"), """
        package demo;
        class Sample extends Base {
          private Object value;
          Object use() { return value; }
          private Object late;
          Object useLate() { return late; }
        }
        """);
    SourceMetadataScanner scanner = new SourceMetadataScanner(contextFor(id, root));
    SourceFile sample = scanner.files().stream()
        .filter(file -> file.simpleTypeName().equals("Sample"))
        .findFirst()
        .orElseThrow();

    FieldReference nearest = scanner.visibleField(
        sample, "value", offsetOf(sample, "return value"));
    assertNotNull(nearest, "the subclass field must hide the inherited field");
    assertEquals(sample.path(), nearest.file().path(), nearest.toString());
    assertEquals("Object", nearest.type(), nearest.toString());

    FieldReference late = scanner.visibleField(
        sample, "late", offsetOf(sample, "return late"));
    assertNotNull(late, "a field declared after the site is still visible");
    assertEquals(sample.path(), late.file().path(), late.toString());

    List<SourceMetadataScannerFileExtracts.FieldCandidate> candidates =
        SourceMetadataScannerFileExtracts.of(sample).fieldCandidates(sample);
    assertEquals(2, candidates.size(), candidates.toString());
    assertTrue(candidates.stream().noneMatch(candidate ->
        candidate.declaration().contains("use(")), candidates.toString());
  }

  private void assertInitializerAt(
      SourceMetadataScanner scanner,
      SourceFile file,
      String marker,
      String expectedName,
      String expectedDeclaration) {
    int offset = offsetOf(file, marker);
    MethodInfo info = SourceMetadataScannerTextLayer15.initializerContaining(file, offset)
        .orElseThrow(() -> new AssertionError("no initializer for " + marker));
    assertEquals(expectedName, info.name(), marker);
    assertTrue(info.start() <= offset && info.end() > offset,
        marker + " -> " + info);
    if (!expectedDeclaration.isEmpty()) {
      assertTrue(info.annotationBlock().contains(expectedDeclaration), info.toString());
    }
  }

  private static int offsetOf(SourceFile file, String marker) {
    int offset = file.source().indexOf(marker);
    assertTrue(offset >= 0, "missing marker: " + marker);
    return offset;
  }

  @Test
  void rawSourceIdentityIsRequiredForReuse() {
    String structural = "class Sample { }";
    Path shared = Path.of("shared-raw-source.java");
    SourceFile first = new SourceFile(
        shared, "shared", "class Sample { }", structural,
        "demo", "demo.Sample", "Sample", 0, false);
    SourceFile samePathNewRaw = new SourceFile(
        shared, "shared", "class Sample { /* edited */ }", structural,
        "demo", "demo.Sample", "Sample", 0, false);

    assertSame(first.structuralSource(), samePathNewRaw.structuralSource());
    assertNotSame(SourceMetadataScannerFileExtracts.of(first),
        SourceMetadataScannerFileExtracts.of(samePathNewRaw),
        "extraction copies raw-source text, so a structural match alone must not be reused");
  }

  @Test
  void onDemandImportLookupUsesTheCachedSetAndKeepsDollarPackages() {
    SourceFile file = syntheticSource(
        "package demo;\nimport java.lang.reflect.*;\nimport a$b.*;\nclass Sample {}\n");

    assertTrue(SourceMetadataScannerTextLayer06.hasOnDemandImport(
        file, "java.lang.reflect"), "the cached set must answer ascii packages");
    assertFalse(SourceMetadataScannerTextLayer06.hasOnDemandImport(file, "java.util"));
    assertTrue(SourceMetadataScannerTextLayer06.hasOnDemandImport(file, "a$b"),
        "a '$' package is outside the cached capture pattern and must still match");
  }

  private static List<String> methodNames(SourceFile file) {
    return SourceMetadataScannerFileExtracts.of(file).methods(file).stream()
        .map(MethodInfo::name).toList();
  }

  private static SourceFile syntheticSource(String source) {
    String structural = JavaSourceText.stripComments(source);
    return new SourceFile(
        Path.of("synthetic-" + Math.abs(source.hashCode()) + "-" + SYNTHETIC.incrementAndGet()
            + ".java"),
        "synthetic.java",
        source,
        structural,
        "demo",
        "demo.Synthetic",
        "Synthetic",
        0,
        false);
  }

  private static final java.util.concurrent.atomic.AtomicInteger SYNTHETIC =
      new java.util.concurrent.atomic.AtomicInteger();

  private SourceFile sourceFile(String id, String sourceText) throws IOException {
    return scannedSourceFile(id, sourceText, "demo/Sample.java");
  }

  private SourceFile scannedSourceFile(String id, String sourceText, String relative)
      throws IOException {
    Path root = temp.resolve(id).resolve("src/main/java");
    Path source = root.resolve(relative);
    Files.createDirectories(source.getParent());
    Files.writeString(source, sourceText);
    return new SourceMetadataScanner(contextFor(id, root)).files().get(0);
  }

  private AnalysisContext contextFor(String id, Path root) {
    return AnalysisContext.builder()
        .id(id)
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("scanner-extraction-cache")
        .javaRelease(17)
        .repositoryRoot(temp.resolve(id))
        .applicationClasses(temp.resolve(id).resolve("classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", root, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
  }

  /** Restatement of the documented depth contract; the index-side test is the real oracle. */
  private static int referenceBraceDepth(String source, int index) {
    int depth = 0;
    for (int position = 0; position < Math.min(index, source.length()); position++) {
      if (source.charAt(position) == '{') {
        depth++;
      } else if (source.charAt(position) == '}') {
        depth = Math.max(0, depth - 1);
      }
    }
    return depth;
  }

  /** Restatement of the documented extent contract; the index-side test is the real oracle. */
  private static int referenceFieldInitializerEnd(String source, int start) {
    int parentheses = 0;
    int brackets = 0;
    int braces = 0;
    for (int index = start; index < source.length(); index++) {
      switch (source.charAt(index)) {
        case '(' -> parentheses++;
        case ')' -> parentheses = Math.max(0, parentheses - 1);
        case '[' -> brackets++;
        case ']' -> brackets = Math.max(0, brackets - 1);
        case '{' -> braces++;
        case '}' -> braces = Math.max(0, braces - 1);
        case ';' -> {
          if (parentheses == 0 && brackets == 0 && braces == 0) {
            return index;
          }
        }
        default -> { }
      }
    }
    return -1;
  }
}
