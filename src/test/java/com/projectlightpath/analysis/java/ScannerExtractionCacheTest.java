package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.projectlightpath.analysis.java.SourceMetadataScannerState.AnonymousType;
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
  void braceDepthMatchesTheReferenceScanForEveryBoundaryCell() {
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
  void fieldInitializerEndMatchesTheReferenceScanForEveryBoundaryCell() {
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
    assertSame(first.methods(), second.methods());
    assertSame(first.typeDeclarations(), second.typeDeclarations());
    assertSame(first.constructors(), second.constructors());
    assertSame(first.anonymousTypes(), second.anonymousTypes());
    assertSame(first.literalMaskedSource(), second.literalMaskedSource());
    assertSame(first.declarationMaskedSource(), second.declarationMaskedSource());
  }

  @Test
  void extractsDoNotDependOnRequestOrder() throws IOException {
    SourceFile methodsFirst = sourceFile("order-methods-first", SAMPLE);
    List<MethodInfo> methods = SourceMetadataScannerFileExtracts.of(methodsFirst).methods();
    List<TypeInfo> types = SourceMetadataScannerFileExtracts.of(methodsFirst).typeDeclarations();

    SourceFile typesFirst = sourceFile("order-types-first", SAMPLE);
    List<TypeInfo> otherTypes = SourceMetadataScannerFileExtracts.of(typesFirst).typeDeclarations();
    List<MethodInfo> otherMethods = SourceMetadataScannerFileExtracts.of(typesFirst).methods();

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
    assertEquals(List.of("first"), methodNames(beforeExtracts));

    SourceFile after = scannedSourceFile(id, secondContent, "demo/Sample.java");
    SourceMetadataScannerFileExtracts afterExtracts = SourceMetadataScannerFileExtracts.of(after);
    assertEquals(List.of("second"), methodNames(afterExtracts));

    assertNotSame(beforeExtracts, afterExtracts,
        "a re-read of the same path must never answer from the previous content");
    assertEquals(List.of("first"), methodNames(beforeExtracts),
        "previously returned extracts keep their own values");

    SourceFile back = scannedSourceFile(id, firstContent, "demo/Sample.java");
    SourceMetadataScannerFileExtracts backExtracts = SourceMetadataScannerFileExtracts.of(back);
    assertNotSame(afterExtracts, backExtracts,
        "A->B->A must not answer from the B entry");
    assertEquals(List.of("first"), methodNames(backExtracts));
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
        assertEquals(
            extracts.fieldInitializerEnd(start),
            extracts.fieldInitializerEnd(start),
            "the memoized extent must be stable for " + source + " start=" + start);
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

  private static List<String> methodNames(SourceMetadataScannerFileExtracts extracts) {
    return extracts.methods().stream().map(MethodInfo::name).toList();
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
    AnalysisContext context = AnalysisContext.builder()
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
    return new SourceMetadataScanner(context).files().get(0);
  }

  /** Independent restatement of the documented depth contract. */
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

  /** Independent restatement of the documented field-extent contract. */
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
