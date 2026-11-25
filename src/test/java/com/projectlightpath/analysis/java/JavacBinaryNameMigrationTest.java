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
  void textualFullyQualifiedNestedSpellingsKeepThePackageReading() throws IOException {
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
    // A FULLY-QUALIFIED nested spelling carries no boundary evidence: it is
    // textually indistinguishable from a top-level type in a package named
    // like the outer type (JLS-default package reading). The textual
    // dialect keeps that reading (no fabricated '$'); the flag-on index
    // resolves the nested reading definitively for correlated members.
    // Nested binary names reach the textual dialect through type-qualified
    // spellings (import-qualified cell) and file-set declarations.
    assertEquals(Optional.of("(Ljava/util/Map/Entry;)Ljava/util/Map/Entry;"),
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
        "outer/Outer.java", """
            package outer;
            public class Outer { public static class Inner { } }
            """,
        "demo/Producer.java", """
            package demo;
            import jakarta.enterprise.inject.Produces;
            import outer.Outer;
            public class Producer {
              @Produces public Outer.Inner entry() { return null; }
            }
            """));
    System.clearProperty(FLAG);
    SourceMetadataScanner offScanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.BeanCandidate off = producer(offScanner, "entry");
    // The flag-off descriptor must be computed while the flag is CLEAR:
    // producerDescriptor reads the flag at call time, not scanner-creation
    // time.
    Optional<String> offDescriptor =
        BoundedFrameworkModelCandidateMetadata.producerDescriptor(offScanner, off);
    System.setProperty(FLAG, "true");
    SourceMetadataScanner onScanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.BeanCandidate on = producer(onScanner, "entry");
    // The parity comparison is only meaningful if the flag-on arm actually
    // ran the INDEX path: assert the correlated member exists (the index
    // resolved the nested return) before comparing descriptors, so a
    // regression that silently falls back to the textual renderer fails
    // here instead of passing vacuously.
    SourceMetadataScannerState.SourceFile producerFile =
        file(onScanner, sourceRoot("parity"), "demo/Producer.java");
    SourceMetadataScannerState.MethodInfo entry = SourceMetadataScanner
        .methods(producerFile).stream()
        .filter(method -> method.name().equals("entry"))
        .findFirst().orElseThrow();
    assertTrue(JavacMemberCorrelation.of(onScanner)
            .correlatedMethod(producerFile, "demo.Producer", entry).isPresent(),
        "the flag-on parity arm must run through the index path");
    // Dialect parity: the flag-off textual derivation (the type-qualified
    // spelling Outer.Inner resolves its qualifier through the explicit
    // import, proving the boundary) and the flag-on index shapes render
    // the SAME binary-name descriptor — the dialects never diverge.
    assertEquals(
        Optional.of("()Louter/Outer$Inner;"),
        offDescriptor);
    assertEquals(
        Optional.of("()Louter/Outer$Inner;"),
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
    // A lowercase nested member after an uppercase outer type: the
    // file-set DECLARATION evidence proves the boundary (demo.Outer is a
    // top-level declaration; lower is declared inside its body), so the
    // chain joins with '$' regardless of the member's case.
    assertEquals(Optional.of("(Ldemo/Outer$lower;)Ldemo/Outer$lower;"),
        BoundedFrameworkModelCandidateMetadata.methodDescriptor(
            scanner, outer, "demo.Outer.lower p", "demo.Outer.lower"));
  }

  @Test
  void uppercasePackageImportsKeepThePackageReading() throws IOException {
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
    // An uppercase-leading package (Com.example) carries NO boundary
    // evidence — capitalization is not evidence of a type — so the
    // JLS-default package reading keeps binary == canonical and the
    // descriptor renders exactly as before #60 (no fabricated '$', no
    // abstention either: nothing is ambiguous, merely unproven-nested).
    assertEquals(Optional.of("(LCom/example/Foo;)LCom/example/Foo;"),
        BoundedFrameworkModelCandidateMetadata.methodDescriptor(
            scanner, file, "Foo key", "Foo"));
  }

  @Test
  void ambiguousIndexLookupsAbstainInsteadOfThrowing() throws IOException {
    AnalysisContext context = context("ambiguous-canonical", Map.of(
        "p/Outer.java", """
            package p;
            public class Outer { static class Inner { } }
            """,
        "p/Outer/Inner.java", """
            package p.Outer;
            public class Inner { }
            """));
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    // Two file-set types share the canonical spelling p.Outer.Inner (the
    // nested p.Outer.Inner, binary p.Outer$Inner, and a top-level Inner in
    // a package literally named p.Outer, binary p.Outer.Inner): the index
    // lookup is ambiguous and must ABSTAIN — never throw (an ambiguous
    // reduce must not reach the stream machinery) and never pick a side.
    assertEquals(Optional.empty(),
        JvmBinaryNames.binaryName(scanner, "p.Outer.Inner", "p.Outer.Inner",
            file(scanner, sourceRoot("ambiguous-canonical"), "p/Outer.java")));
  }

  @Test
  void dollarSegmentsBeforeTheLastSegmentAbstain() throws IOException {
    AnalysisContext context = context("dollar-nested", Map.of(
        "demo/Api.java", """
            package demo;
            public interface Api {
              pkg.Outer$X.Inner find(pkg.Outer$X.Inner key);
            }
            """));
    System.clearProperty(FLAG);
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile file =
        file(scanner, sourceRoot("dollar-nested"), "demo/Api.java");
    // A '$' in a segment BEFORE the last is ambiguous (a '$'-named simple
    // identifier with further nesting vs a package segment): the spelling
    // cannot prove which reading applies, so the descriptor abstains. A
    // final-segment '$' (com.lib.Cash$Flow) keeps the passthrough — that
    // cell pins it.
    assertEquals(Optional.empty(),
        BoundedFrameworkModelCandidateMetadata.methodDescriptor(
            scanner, file, "pkg.Outer$X.Inner key", "pkg.Outer$X.Inner"));
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
    SourceMetadataScannerState.SourceFile file =
        file(scanner, sourceRoot("empty-segment"), "demo/Api.java");
    // Defensive boundary cell: an empty segment can never come out of
    // resolution, and the seam must abstain rather than render it.
    assertEquals(Optional.empty(),
        JvmBinaryNames.binaryName(scanner, "a..B", "a..B", file));
    assertEquals(Optional.empty(),
        JvmBinaryNames.binaryName(scanner, "", "", file));
    assertEquals(Optional.empty(),
        JvmBinaryNames.binaryName(scanner, null, null, file));
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

  @Test
  void fileSetTopLevelTypesInUppercasePackagesRender() throws IOException {
    AnalysisContext context = context("uppercase-file-set", Map.of(
        "DEMO/Foo.java", "package DEMO; public class Foo {}\n",
        "DEMO/User.java", """
            package DEMO;
            public class User {
              public Foo foo() { return null; }
            }
            """));
    System.clearProperty(FLAG);
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile user =
        file(scanner, sourceRoot("uppercase-file-set"), "DEMO/User.java");
    // A file-set TOP-LEVEL type in an uppercase-leading package: the
    // file-set top-level rule (b) proves binary == canonical, where the
    // segment rule alone would abstain at boundary 0 (uppercase first
    // segment). This cell is rule b's mutation pin — deleting the rule
    // makes the descriptor absent.
    assertEquals(Optional.of("()LDEMO/Foo;"),
        BoundedFrameworkModelCandidateMetadata.methodDescriptor(
            scanner, user, "", "Foo"));
  }

  @Test
  void lowercaseTopLevelNestingResolvesThroughDeclarations() throws IOException {
    AnalysisContext context = context("lowercase-top-level", Map.of(
        "demo/lower.java", """
            package demo;
            public class lower { static class Inner { } }
            """,
        "demo/Api.java", """
            package demo;
            public interface Api {
              demo.lower.Inner find(demo.lower.Inner key);
            }
            """));
    System.clearProperty(FLAG);
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile api =
        file(scanner, sourceRoot("lowercase-top-level"), "demo/Api.java");
    // A lowercase TOP-LEVEL class with a nested type (true binary
    // demo.lower$Inner): the file-set declaration evidence proves the
    // boundary (demo.lower is a top-level declaration; Inner is declared
    // inside its body), so capitalization never matters — the binary
    // chain joins correctly.
    assertEquals(Optional.of("(Ldemo/lower$Inner;)Ldemo/lower$Inner;"),
        BoundedFrameworkModelCandidateMetadata.methodDescriptor(
            scanner, api, "demo.lower.Inner key", "demo.lower.Inner"));
  }

  @Test
  void inheritedMemberTypesAbstainThroughDirectOwnership() throws IOException {
    AnalysisContext context = context("inherited-member", Map.of(
        "demo/Base.java", """
            package demo;
            public class Base { public static class Inner { } }
            """,
        "demo/Child.java", """
            package demo;
            public class Child extends Base {
              class Helper { class Inner { } }
            }
            """,
        "demo/Api.java", """
            package demo;
            public interface Api {
              demo.Child.Inner find(demo.Child.Inner key);
            }
            """));
    System.clearProperty(FLAG);
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile api =
        file(scanner, sourceRoot("inherited-member"), "demo/Api.java");
    // demo.Child.Inner names the member type INHERITED from Base (true
    // binary demo.Base$Inner); the only Inner inside Child's body is a
    // GRANDCHILD (Helper.Inner, binary demo.Child$Helper$Inner).
    // Containment alone proves neither: direct declaration ownership
    // cannot be established, so the derivation must ABSTAIN rather than
    // fabricate demo.Child$Inner — a binary name that does not exist.
    assertEquals(Optional.empty(),
        BoundedFrameworkModelCandidateMetadata.methodDescriptor(
            scanner, api, "demo.Child.Inner key", "demo.Child.Inner"));
  }

  @Test
  void multiSegmentTypeQualifiedSpellingsJoinTheChain() throws IOException {
    AnalysisContext context = context("multi-segment", Map.of(
        "ext/Outer.java", """
            package ext;
            public class Outer { public static class Middle { public static class Inner { } } }
            """,
        "demo/Api.java", """
            package demo;
            import ext.Outer;
            import java.a.B;
            public interface Api {
              Outer.Middle.Inner find(Outer.Middle.Inner key);
            }
            """));
    System.clearProperty(FLAG);
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile api =
        file(scanner, sourceRoot("multi-segment"), "demo/Api.java");
    // A multi-segment type-qualified spelling (Outer.Middle.Inner, the
    // qualifier resolvable through the explicit import) asserts the same
    // boundary chain as the single-segment form: every tail segment joins
    // the binary chain.
    assertEquals(Optional.of("(Lext/Outer$Middle$Inner;)Lext/Outer$Middle$Inner;"),
        BoundedFrameworkModelCandidateMetadata.methodDescriptor(
            scanner, api, "Outer.Middle.Inner key", "Outer.Middle.Inner"));
    // The spelling rule's own multi-dot handling, isolated from the
    // file-set declaration rule: a NON-file-set qualifier (resolved
    // through its import) with a two-segment tail. The tail join replaces
    // every dot with '$'; a second-dot rejection would fall through to
    // the package reading.
    assertEquals(Optional.of("java.a.B$C$D"),
        JvmBinaryNames.binaryName(scanner, "java.a.B.C.D", "B.C.D", api));
  }

  @Test
  void duplicateFlaggedIndexMatchesAbstainInsteadOfFallingThrough() throws IOException {
    AnalysisContext context = context("duplicate-flagged", Map.of(
        "p/Outer.java", """
            package p;
            public class Outer { static class Inner { } }
            """,
        "p/Outer$Inner.java", """
            package p;
            public class Outer$Inner { }
            """));
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile outer =
        file(scanner, sourceRoot("duplicate-flagged"), "p/Outer.java");
    // The nested p.Outer.Inner and the top-level p.Outer$Inner share the
    // binary name p.Outer$Inner, so the index flags BOTH entries. The
    // index lookup for the canonical finds only the (flagged) nested
    // entry: duplicate ownership is NOT proven, and the derivation must
    // abstain — never fall through to the file-set declaration chain,
    // which would return exactly the binary name the index rejected.
    assertEquals(Optional.empty(),
        JvmBinaryNames.binaryName(scanner, "p.Outer.Inner", "p.Outer.Inner", outer));
  }

  @Test
  void mixedFlaggedAndUnflaggedIndexMatchesAbstain() throws IOException {
    AnalysisContext context = context("mixed-flagged", Map.of(
        "p/Outer.java", """
            package p;
            public class Outer { static class Inner { } }
            """,
        "p/Outer$Inner.java", """
            package p;
            public class Outer$Inner { }
            """,
        "p/Outer/Inner.java", """
            package p.Outer;
            public class Inner { }
            """));
    System.setProperty(FLAG, "true");
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    SourceMetadataScannerState.SourceFile outer =
        file(scanner, sourceRoot("mixed-flagged"), "p/Outer.java");
    // The canonical p.Outer.Inner names the flagged nested entry (binary
    // p.Outer$Inner, colliding with the top-level p.Outer$Inner) AND the
    // unflagged top-level Inner in the package literally named p.Outer
    // (binary p.Outer.Inner). One eligible entry remains after the
    // filter, but the flagged entry is a COMPETING declaration for the
    // same canonical name: the returned binary is not proven, so the
    // derivation abstains — a single eligible entry is accepted only
    // when it is the only indexed match.
    assertEquals(Optional.empty(),
        JvmBinaryNames.binaryName(scanner, "p.Outer.Inner", "p.Outer.Inner", outer));
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
