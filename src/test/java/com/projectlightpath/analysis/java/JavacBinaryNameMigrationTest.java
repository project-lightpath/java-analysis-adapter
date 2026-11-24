package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Binary names in JVM descriptors for issue #60: the index-rendered dialect
 * ({@code JavacShapeDescriptors.jvmDescriptorFromMember}) and the textual
 * dialect ({@code BoundedFrameworkModelCandidateMetadata.jvmDescriptor})
 * must BOTH render binary names ({@code Ldemo/Outer$Inner;}) in the same
 * change, so the dialects never diverge. Canonical spellings stay canonical
 * in the comma-canonical dialects.
 *
 * <p>Cells C1–C3 pin facet 1 (cross-file resolution in the jointly compiled
 * index) as characterization; cells N* and B2/B3 pin the binary-name
 * rendering red-first. The class runs same-thread and {@link Isolated
 * isolated} because it writes the flag.
 */
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class JavacBinaryNameMigrationTest {
  private static final String FLAG = "flpath.java.symbolindex";

  @TempDir Path temp;

  @AfterEach
  void clearFlag() {
    System.clearProperty(FLAG);
  }

  @Test
  void indexRendersNestedJvmDescriptorsWithBinaryNames() throws IOException {
    AnalysisContext context = context("index-nested", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              static class Inner { }
              public Inner pick(Inner p) { return null; }
              public void arrays(Inner[] a, Inner... rest) { }
            }
            """));
    System.setProperty(FLAG, "true");
    JavacSourceSymbolIndex index = JavacSourceSymbolIndex.of(context).orElseThrow();
    JavacSourceSymbolIndex.MemberInfo pick = index.members("demo.Probe", "pick").get(0);
    // The index dialect renders the BINARY name: nested types join with '$',
    // never slashes. Canonical spellings (demo.Probe.Inner) stay canonical.
    assertEquals(List.of("demo.Probe.Inner"), pick.parameterTypes());
    assertEquals("demo.Probe.Inner", pick.returnType());
    assertEquals("(Ldemo/Probe$Inner;)Ldemo/Probe$Inner;",
        JavacShapeDescriptors.jvmDescriptorFromMember(pick).orElseThrow());
  }

  @Test
  void indexRendersNestedArrayAndVarargsWithBinaryNames() throws IOException {
    AnalysisContext context = context("index-arrays", Map.of(
        "demo/Probe.java", """
            package demo;
            public class Probe {
              static class Inner { }
              public void arrays(Inner[] a, Inner... rest) { }
            }
            """));
    System.setProperty(FLAG, "true");
    JavacSourceSymbolIndex index = JavacSourceSymbolIndex.of(context).orElseThrow();
    JavacSourceSymbolIndex.MemberInfo arrays = index.members("demo.Probe", "arrays").get(0);
    // Nested arrays and varargs keep the component binary name: [Ldemo/Probe$Inner;.
    assertEquals("([Ldemo/Probe$Inner;[Ldemo/Probe$Inner;)V",
        JavacShapeDescriptors.jvmDescriptorFromMember(arrays).orElseThrow());
  }

  @Test
  void textualJvmDescriptorUsesBinaryNamesForQualifiedSpellings() throws IOException {
    AnalysisContext context = context("textual-qualified", Map.of(
        "demo/Api.java", """
            package demo;
            public interface Api {
              java.util.Map.Entry find(java.util.Map.Entry key);
            }
            """));
    System.clearProperty(FLAG);
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile file =
        file(scanner, sourceRoot("textual-qualified"), "demo/Api.java");
    // Flag-off golden under the NEW dialect: the fully-qualified nested
    // spelling renders the binary name java.util.Map$Entry.
    assertEquals(Optional.of("(Ljava/util/Map$Entry;)Ljava/util/Map$Entry;"),
        BoundedFrameworkModelCandidateMetadata.methodDescriptor(
            scanner, file, "java.util.Map.Entry key", "java.util.Map.Entry"));
  }

  @Test
  void textualJvmDescriptorUsesBinaryNamesForImportQualifiedSpellings() throws IOException {
    AnalysisContext context = context("textual-import", Map.of(
        "demo/Api.java", """
            package demo;
            import java.util.Map;
            public interface Api {
              Map.Entry find(Map.Entry key);
            }
            """));
    System.clearProperty(FLAG);
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile file =
        file(scanner, sourceRoot("textual-import"), "demo/Api.java");
    // The import-qualified spelling resolves through the explicit import to
    // java.util.Map.Entry and renders the same binary-name descriptor as the
    // fully-qualified spelling (one dialect, both resolution routes).
    assertEquals(Optional.of("(Ljava/util/Map$Entry;)Ljava/util/Map$Entry;"),
        BoundedFrameworkModelCandidateMetadata.methodDescriptor(
            scanner, file, "Map.Entry key", "Map.Entry"));
  }

  @Test
  void nestedProducerDescriptorParityAcrossFlagStates() throws IOException {
    AnalysisContext context = context("parity", Map.of(
        "demo/Producer.java", """
            package demo;
            import jakarta.enterprise.inject.Produces;
            public class Producer {
              @Produces public java.util.Map.Entry entry() { return null; }
            }
            """));
    System.clearProperty(FLAG);
    SourceMetadataScanner offScanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.BeanCandidate off = producer(offScanner, "entry");
    System.setProperty(FLAG, "true");
    SourceMetadataScanner onScanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.BeanCandidate on = producer(onScanner, "entry");
    // Dialect parity: the flag-off textual derivation and the flag-on index
    // shapes render the SAME binary-name descriptor — the dialects never
    // diverge.
    assertEquals(
        Optional.of("()Ljava/util/Map$Entry;"),
        BoundedFrameworkModelCandidateMetadata.producerDescriptor(offScanner, off));
    assertEquals(
        Optional.of("()Ljava/util/Map$Entry;"),
        BoundedFrameworkModelCandidateMetadata.producerDescriptor(onScanner, on));
  }

  @Test
  void dollarSimpleNamesStayBinaryInBothDialects() throws IOException {
    AnalysisContext context = context("dollar-simple", Map.of(
        "demo/Cash$Flow.java", "package demo; public class Cash$Flow {}\n",
        "demo/Provider.java", """
            package demo;
            public class Provider {
              public Cash$Flow flow() { return null; }
            }
            """));
    System.clearProperty(FLAG);
    SourceMetadataScanner offScanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile provider =
        file(offScanner, sourceRoot("dollar-simple"), "demo/Provider.java");
    // A top-level type whose SIMPLE NAME contains '$' has binary ==
    // canonical: the textual dialect renders it through the file-set
    // top-level rule (never re-splitting the '$').
    assertEquals(Optional.of("()Ldemo/Cash$Flow;"),
        BoundedFrameworkModelCandidateMetadata.methodDescriptor(
            offScanner, provider, "", "Cash$Flow"));
    System.setProperty(FLAG, "true");
    JavacSourceSymbolIndex index = JavacSourceSymbolIndex.of(context).orElseThrow();
    JavacSourceSymbolIndex.MemberInfo flow = index.members("demo.Provider", "flow").get(0);
    // The index dialect carries Elements.getBinaryName — the same spelling.
    assertEquals(Optional.of("()Ldemo/Cash$Flow;"),
        JavacShapeDescriptors.jvmDescriptorFromMember(flow));
  }

  @Test
  void lowercaseNestedMembersJoinTheBinaryChain() throws IOException {
    AnalysisContext context = context("lowercase-nested", Map.of(
        "demo/Outer.java", """
            package demo;
            public class Outer {
              class lower { }
            }
            """));
    System.clearProperty(FLAG);
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile outer =
        file(scanner, sourceRoot("lowercase-nested"), "demo/Outer.java");
    // A lowercase nested member after an uppercase outer type: the chain
    // join renders demo.Outer$lower (the first uppercase segment starts the
    // type chain; everything after joins with '$').
    assertEquals(Optional.of("(Ldemo/Outer$lower;)Ldemo/Outer$lower;"),
        BoundedFrameworkModelCandidateMetadata.methodDescriptor(
            scanner, outer, "demo.Outer.lower p", "demo.Outer.lower"));
  }

  @Test
  void uppercasePackageImportsAbstainInsteadOfFabricating() throws IOException {
    AnalysisContext context = context("uppercase-package", Map.of(
        "demo/Api.java", """
            package demo;
            import Com.example.Foo;
            public interface Api {
              Foo find(Foo key);
            }
            """));
    System.clearProperty(FLAG);
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile file =
        file(scanner, sourceRoot("uppercase-package"), "demo/Api.java");
    // An uppercase-leading package (Com.example) is textually
    // indistinguishable from a default-package nesting (Outer.Inner): the
    // boundary rule cannot prove where the package ends, so the descriptor
    // ABSTAINS rather than fabricating a match. This shape renders
    // LCom/example/Foo; today — a documented, pinned coverage regression
    // (fail-closed direction).
    assertEquals(Optional.empty(),
        BoundedFrameworkModelCandidateMetadata.methodDescriptor(
            scanner, file, "Foo key", "Foo"));
    // The default-package nesting shape abstains through the same guard
    // (direct seam probe: unreachable through resolution).
    assertEquals(Optional.empty(),
        JvmBinaryNames.binaryName(scanner, "Outer.Inner"));
  }

  @Test
  void dollarContainingCanonicalsPassThroughUnchanged() throws IOException {
    AnalysisContext context = context("dollar-passthrough", Map.of(
        "demo/Api.java", """
            package demo;
            public interface Api {
              com.lib.Cash$Flow find(com.lib.Cash$Flow key);
            }
            """));
    System.clearProperty(FLAG);
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile file =
        file(scanner, sourceRoot("dollar-passthrough"), "demo/Api.java");
    // A '$' in a source-derived canonical spelling denotes a '$'-named
    // simple identifier (source references to nested types use dots), so
    // the spelling passes through unchanged: binary == canonical.
    assertEquals(Optional.of("(Lcom/lib/Cash$Flow;)Lcom/lib/Cash$Flow;"),
        BoundedFrameworkModelCandidateMetadata.methodDescriptor(
            scanner, file, "com.lib.Cash$Flow key", "com.lib.Cash$Flow"));
  }

  @Test
  void emptySegmentsAbstain() throws IOException {
    AnalysisContext context = context("empty-segment", Map.of(
        "demo/Api.java", "package demo; public interface Api { }\n"));
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    // Defensive boundary cell: an empty segment can never come out of
    // resolution, and the seam must abstain rather than render it.
    assertEquals(Optional.empty(), JvmBinaryNames.binaryName(scanner, "a..B"));
    assertEquals(Optional.empty(), JvmBinaryNames.binaryName(scanner, ""));
    assertEquals(Optional.empty(), JvmBinaryNames.binaryName(scanner, null));
  }

  @Test
  void crossFileTopLevelReturnsCorrelateFlagOn() throws IOException {
    AnalysisContext context = context("cross-file-top-level", Map.of(
        "demo/Bean.java", "package demo; public class Bean {}\n",
        "demo/Producer.java", """
            package demo;
            import jakarta.enterprise.inject.Produces;
            public class Producer {
              @Produces public Bean make() { return null; }
            }
            """));
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile producer =
        file(scanner, sourceRoot("cross-file-top-level"), "demo/Producer.java");
    SourceMetadataScannerState.MethodInfo make = SourceMetadataScanner
        .methods(producer).stream()
        .filter(method -> method.name().equals("make"))
        .findFirst().orElseThrow();
    // Facet 1 characterization: the index compiles the full selected source
    // set jointly, so a cross-file top-level return is NOT an error type —
    // the member correlates and the canonical return is published.
    JavacSourceSymbolIndex.MemberInfo member = JavacMemberCorrelation.of(scanner)
        .correlatedMethod(producer, "demo.Producer", make).orElseThrow();
    assertFalse(member.errorType());
    assertEquals("demo.Bean", member.returnType());
    List<SourceMetadataScannerState.BeanCandidate> on =
        scanner.beanCandidates(AnalysisContext.Framework.QUARKUS);
    assertTrue(on.stream()
        .anyMatch(candidate -> candidate.producerMethod().equals("make")
            && candidate.candidateType().equals("demo.Bean")),
        on.toString());
  }

  @Test
  void crossFileQualifiedNestedReturnsCorrelateFlagOn() throws IOException {
    AnalysisContext context = context("cross-file-nested", Map.of(
        "demo/Outer.java", """
            package demo;
            public class Outer { static class Inner { } }
            """,
        "demo/Producer.java", """
            package demo;
            import jakarta.enterprise.inject.Produces;
            public class Producer {
              @Produces public Outer.Inner make() { return null; }
            }
            """));
    System.clearProperty(FLAG);
    List<SourceMetadataScannerState.BeanCandidate> off =
        new SourceMetadataScanner(context).beanCandidates(
            AnalysisContext.Framework.QUARKUS);
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    List<SourceMetadataScannerState.BeanCandidate> on =
        scanner.beanCandidates(AnalysisContext.Framework.QUARKUS);
    // Flag-off golden: the type-qualified spelling does not resolve
    // textually (the textual resolver is top-level only), so the bean type
    // stays empty.
    assertTrue(off.stream()
        .anyMatch(candidate -> candidate.producerMethod().equals("make")
            && candidate.candidateType().isEmpty()),
        off.toString());
    // Facet 1 characterization: the qualified nested cross-file return
    // resolves in the jointly compiled index (errorType false), so the
    // correlated member publishes the canonical nested bean type — the
    // differential the #23 residual (mis)attributed to per-file isolation.
    assertTrue(on.stream()
        .anyMatch(candidate -> candidate.producerMethod().equals("make")
            && candidate.candidateType().equals("demo.Outer.Inner")),
        on.toString());
  }

  @Test
  void genuinelyUnresolvableReturnsStillAbstain() throws IOException {
    AnalysisContext context = context("unresolvable", Map.of(
        "demo/Producer.java", """
            package demo;
            import jakarta.enterprise.inject.Produces;
            public class Producer {
              @Produces public missing.Thing make() { return null; }
            }
            """));
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile producer =
        file(scanner, sourceRoot("unresolvable"), "demo/Producer.java");
    SourceMetadataScannerState.MethodInfo make = SourceMetadataScanner
        .methods(producer).stream()
        .filter(method -> method.name().equals("make"))
        .findFirst().orElseThrow();
    // A type that resolves nowhere (not in the source set, not on the
    // classpath) stays an error type: the member abstains — no index shape
    // is published for it. (The textual bean type keeps its pre-existing
    // verbatim spelling; the abstention under test is the correlation.)
    assertTrue(JavacMemberCorrelation.of(scanner)
            .correlatedMethod(producer, "demo.Producer", make).isEmpty(),
        "an unresolvable return type must abstain");
  }

  private SourceMetadataScannerState.BeanCandidate producer(
      SourceMetadataScanner scanner, String method) {
    return scanner.beanCandidates(AnalysisContext.Framework.QUARKUS).stream()
        .filter(candidate -> candidate.producerMethod().equals(method))
        .findFirst().orElseThrow();
  }

  private SourceMetadataScannerState.SourceFile file(
      SourceMetadataScanner scanner, Path sourceRoot, String relative) {
    Path root = sourceRoot.resolve(relative);
    return scanner.files().stream()
        .filter(item -> item.path().equals(root))
        .findFirst().orElseThrow();
  }

  private Path sourceRoot(String id) {
    return temp.resolve(id).resolve("src/main/java");
  }

  private AnalysisContext context(String id, Map<String, String> files) throws IOException {
    Path sourceRoot = sourceRoot(id);
    for (Map.Entry<String, String> entry : files.entrySet()) {
      write(sourceRoot, entry.getKey(), entry.getValue());
    }
    Path root = sourceRoot.getParent().getParent();
    return AnalysisContext.builder()
        .id(root.getFileName().toString())
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("javac-binary-name-test")
        .javaRelease(17)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
  }

  private static void write(Path sourceRoot, String relative, String content)
      throws IOException {
    Path file = sourceRoot.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }
}
