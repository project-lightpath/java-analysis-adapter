package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceLoaderCallSiteSourceTest {
  @TempDir Path temp;

  /**
   * Lexical boundary (CodeRabbit 4614619769): in Java, {@code 外ServiceLoader}
   * is one identifier, so a call on it is not a ServiceLoader call even though
   * the ASCII regex boundary accepted the suffix.
   */
  @Test
  void nonAsciiIdentifierPrefixIsNotServiceLoaderCall() throws IOException {
    String source = """
        package demo;
        class Bridge {
          void audit() { 外ServiceLoader.load(Object.class); }
        }
        class 外ServiceLoader { static void load(Class<?> type) { } }
        """;
    assertEquals(0, sites("non-ascii-prefix", source).size());
  }

  @Test
  void supplementaryCodePointPrefixIsNotServiceLoaderCall() throws IOException {
    String source = """
        package demo;
        class Bridge {
          void audit() { \uD840\uDC00ServiceLoader.load(Object.class); }
        }
        class \uD840\uDC00ServiceLoader { static void load(Class<?> type) { } }
        """;
    assertEquals(0, sites("supplementary-prefix", source).size());
  }

  @Test
  void sameLineServiceLoaderImportsAreRecognized() throws IOException {
    String source = """
        package demo;
        import java.util.List; import java.util.ServiceLoader;
        class Bridge { void audit() { ServiceLoader.load(Object.class); } }
        """;
    assertEquals(1, sites("same-line-imports", source).size());
  }

  @Test
  void staticMemberTypeImportDoesNotOwnTheName() throws IOException {
    String source = """
        package demo;
        import java.util.*;
        import static other.Base.ServiceLoader;
        class Bridge { void audit() { ServiceLoader.load(Object.class); } }
        """;
    assertEquals(0, sites("static-member-type", source, Map.of("other/Base.java", """
        package other;
        public class Base {
          public static class ServiceLoader<T> {
            public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
          }
        }
        """)).size());
  }

  @Test
  void longDottedImportDoesNotOverflowTheStack() throws IOException {
    StringBuilder name = new StringBuilder("a");
    for (int index = 0; index < 20000; index++) {
      name.append(".b");
    }
    String source = "package demo;\nimport " + name + ".*;\nclass Bridge { }\n";
    assertDoesNotThrow(() -> sites("long-dotted-import", source));
  }

  @Test
  void staticOnDemandImportDoesNotSuppressWildcardOwnership() throws IOException {
    String source = """
        package demo;
        import java.util.*;
        import static other.X.*;
        class Bridge { void audit() { ServiceLoader.load(Object.class); } }
        """;
    assertEquals(1, sites("static-on-demand", source, Map.of("other/X.java", """
        package other;
        public class X { public static void touch() { } }
        """)).size());
  }

  @Test
  void prohibitedPackageNameFailsClosed() throws IOException {
    String source = """
        package java.util;
        import static other.X.*;
        class Bridge { void audit() { ServiceLoader.load(Object.class); } }
        """;
    SourceMetadataScanner scanner = scanner("prohibited-package", source, Map.of("other/X.java", """
        package other;
        public class X { public static void touch() { } }
        """));
    assertTrue(JavacServiceLoaderSiteResolver.sites(scanner).orElseThrow().values().stream()
        .allMatch(List::isEmpty));
  }

  @Test
  void unicodeClassLiteralIsAttributed() throws IOException {
    String source = """
        package demo;
        import java.util.ServiceLoader;
        class Bridge { void audit() { ServiceLoader.load(Данные.class); } }
        interface Данные { }
        """;
    List<ServiceLoaderCallSite> sites = sites("unicode-class-literal", source);
    assertEquals(1, sites.size());
    assertEquals("demo.Данные", sites.get(0).serviceType());
  }

  @Test
  void dottedMemberSelectionIsNotABareServiceLoaderCall() throws IOException {
    String source = """
        package demo;
        class Bridge {
          static Holder holder;
          void audit() { holder.ServiceLoader.load(Object.class); }
        }
        class Holder {
          static class ServiceLoader<T> {
            static <S> ServiceLoader<S> load(Class<S> type) { return null; }
          }
        }
        """;
    assertEquals(0, sites("dotted-member-selection", source).size());
  }

  @Test
  void lambdaSuppliedToAnOffChainCallIsNotBound() {
    String source = "import java.util.ServiceLoader;\n"
        + "ServiceLoader.load(AuditProvider.class).findFirst()"
        + ".equals(build(q -> q.record()));\n";
    ServiceLoaderCallSite site = siteAt(source);

    assertFalse(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
        source, site, "record", 2, "q"));
  }

  @Test
  void bindsProviderLambdaInsideStreamExpression() {
    String source = "package demo;\n"
        + "import java.util.ServiceLoader;\n"
        + "class Bridge {\n"
        + "  String audit(OtherImpl other) {\n"
        + "    return ServiceLoader.load(AuditProvider.class).findFirst()\n"
        + "        .map(value -> value.record())\n"
        + "        .orElseGet(() -> other.record());\n"
        + "  }\n"
        + "}\n";
    ServiceLoaderCallSite site = siteAt(source);
    assertTrue(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
        source, site, "record", 6, "value"));
  }

  @Test
  void usesReceiverIdentityForSameLineInvocations() {
    String source = "import java.util.ServiceLoader;\n"
        + "ServiceLoader.load(AuditProvider.class).findFirst()"
        + ".ifPresent(p -> { p.record(); other.record(); });\n";
    ServiceLoaderCallSite site = siteAt(source);

    assertTrue(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
        source, site, "record", 2, "p"));
    assertFalse(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
        source, site, "record", 2, "other"));
    assertFalse(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
        source, site, "record", 2));
  }

  @Test
  void filtersEveryTargetOccurrenceByRequestedLine() {
    String source = "import java.util.ServiceLoader;\n"
        + "ServiceLoader.load(AuditProvider.class).findFirst().ifPresent(p -> {\n"
        + "  p.record();\n"
        + "  p.record();\n"
        + "});\n";
    ServiceLoaderCallSite site = siteAt(source);

    assertTrue(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(source, site, "record", 3, "p"));
    assertTrue(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(source, site, "record", 4, "p"));
  }

  @Test
  void doesNotCarryProviderProvenanceThroughReplacingMapLambda() {
    String source = "import java.util.ServiceLoader;\n"
        + "ServiceLoader.load(AuditProvider.class).findFirst()"
        + ".map(p -> other).ifPresent(q -> q.record());\n";
    ServiceLoaderCallSite site = siteAt(source);

    assertFalse(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
        source, site, "record", 2, "q"));
  }

  @Test
  void orElseThrowBeforeLambdaMethodAbstains() {
    String source = "import java.util.ServiceLoader;\n"
        + "ServiceLoader.load(AuditProvider.class).findFirst()"
        + ".orElseThrow().configure(q -> q.record());\n";
    ServiceLoaderCallSite site = siteAt(source);

    assertFalse(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
        source, site, "record", 2, "q"));
  }

  @Test
  void localClassInsideLambdaDoesNotInheritParameterOwnership() {
    String source = "import java.util.ServiceLoader;\n"
        + "ServiceLoader.load(AuditProvider.class).findFirst()"
        + ".ifPresent(p -> { class Local { void run(AuditProvider p) { p.record(); } }"
        + " new Local().run(other); });\n";
    ServiceLoaderCallSite site = siteAt(source);

    assertFalse(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
        source, site, "record", 2, "p"));
  }

  @Test
  void anonymousClassInsideLambdaDoesNotInheritParameterOwnership() {
    String source = "import java.util.ServiceLoader;\n"
        + "ServiceLoader.load(AuditProvider.class).findFirst()"
        + ".ifPresent(p -> { new Runnable() { void run(AuditProvider p) { p.record(); } }"
        + ".run(); });\n";
    ServiceLoaderCallSite site = siteAt(source);

    assertFalse(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
        source, site, "record", 2, "p"));
  }

  @Test
  void preservesProviderProvenanceThroughFilteringLambda() {
    String source = "import java.util.ServiceLoader;\n"
        + "ServiceLoader.load(AuditProvider.class).findFirst()"
        + ".filter(p -> true).ifPresent(q -> q.record());\n";
    ServiceLoaderCallSite site = siteAt(source);

    assertTrue(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
        source, site, "record", 2, "q"));
  }

  @Test
  void allowsKeywordBeforeBareProviderReceiver() {
    String source = "import java.util.ServiceLoader;\n"
        + "return ServiceLoader.load(AuditProvider.class).findFirst()"
        + ".ifPresent(p -> { return p.record(); });\n";
    ServiceLoaderCallSite site = siteAt(source);

    assertTrue(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
        source, site, "record", 2, "p"));
  }

  @Test
  void interveningNonLambdaMethodCallAbstains() {
    String source = "import java.util.ServiceLoader;\n"
        + "ServiceLoader.load(AuditProvider.class).findFirst()"
        + ".replacement().ifPresent(q -> q.record());\n";
    ServiceLoaderCallSite site = siteAt(source);

    assertFalse(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
        source, site, "record", 2, "q"));
  }

  @Test
  void bindsDirectProviderCallThroughOrElseThrow() {
    String source = "import java.util.ServiceLoader;\n"
        + "ServiceLoader.load(AuditProvider.class).findFirst().orElseThrow().record();\n";
    ServiceLoaderCallSite site = siteAt(source);

    assertTrue(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
        source, site, "record", 2));
  }

  @Test
  void rejectsReassignedProviderLambdaParameter() {
    String source = "import java.util.ServiceLoader;\n"
        + "ServiceLoader.load(AuditProvider.class).findFirst()"
        + ".ifPresent(p -> { p = other; p.record(); });\n";
    ServiceLoaderCallSite site = siteAt(source);

    assertFalse(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
        source, site, "record", 2, "p"));
  }

  @Test
  void prefixUpdatesOnlyRejectCompleteProviderParameterNames() {
    for (String update : List.of("++processed", "--processed", "++pÉ", "--p$")) {
      String source = "import java.util.ServiceLoader;\n"
          + "ServiceLoader.load(AuditProvider.class).findFirst()"
          + ".ifPresent(p -> { " + update + "; p.record(); });\n";
      ServiceLoaderCallSite site = siteAt(source);

      assertTrue(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
          source, site, "record", 2, "p"), update);
    }
    for (String update : List.of("++p", "--p", "p++", "p--")) {
      String source = "import java.util.ServiceLoader;\n"
          + "ServiceLoader.load(AuditProvider.class).findFirst()"
          + ".ifPresent(p -> { " + update + "; p.record(); });\n";
      ServiceLoaderCallSite site = siteAt(source);

      assertFalse(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
          source, site, "record", 2, "p"), update);
    }
  }

  private static ServiceLoaderCallSite siteAt(String source) {
    int offset = source.indexOf("ServiceLoader.load");
    int line = (int) source.substring(0, offset).chars().filter(value -> value == '\n').count() + 1;
    return new ServiceLoaderCallSite("AuditProvider", false, offset, line);
  }

  private List<ServiceLoaderCallSite> sites(String id, String bridgeSource) throws IOException {
    return sites(id, bridgeSource, Map.of());
  }

  private List<ServiceLoaderCallSite> sites(
      String id, String bridgeSource, Map<String, String> extraFiles) throws IOException {
    return ServiceLoaderSourceCache.source(
        scanner(id, bridgeSource, extraFiles), new MethodSymbol("demo.Bridge", "audit", "()V"))
        .orElseThrow().sites();
  }

  private SourceMetadataScanner scanner(
      String id, String bridgeSource, Map<String, String> extraFiles) throws IOException {
    Path root = temp.resolve(id);
    Path sourceRoot = root.resolve("src/main/java");
    Path bridge = sourceRoot.resolve("demo/Bridge.java");
    Files.createDirectories(bridge.getParent());
    Files.writeString(bridge, bridgeSource);
    for (Map.Entry<String, String> entry : extraFiles.entrySet()) {
      Path extra = sourceRoot.resolve(entry.getKey());
      Files.createDirectories(extra.getParent());
      Files.writeString(extra, entry.getValue());
    }
    AnalysisContext context = AnalysisContext.builder()
        .id(id)
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("call-site-source-test")
        .javaRelease(21)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
    return new SourceMetadataScanner(context);
  }
}
