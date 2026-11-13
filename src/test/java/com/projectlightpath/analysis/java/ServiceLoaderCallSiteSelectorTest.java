package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceLoaderCallSiteSelectorTest {
  @TempDir Path temp;

  @Test
  void statementMatchingRequiresOneLexicallyBoundTarget() {
    String source = """
        class Bridge {
          void audit() {
            java.util.ServiceLoader.load(AuditProvider.class)
                .map(value -> helper(new int[] {1, 2}, new int[] {3, 4}, value))
                .ifPresent(provider -> provider.record());
          }
        }
        """;
    ServiceLoaderCallSite site = ServiceLoaderCallSiteParser.parse(source).get(0);

    assertTrue(ServiceLoaderCallSiteSelector.hasCallOnStatement(
        source, site.line(), List.of(site), ignored -> true));
    assertTrue(ServiceLoaderCallSiteSelector.hasCallOnStatement(
        source, site.line(), "record", List.of(site), ignored -> true));
    assertFalse(ServiceLoaderCallSiteSelector.hasCallOnStatement(
        source, site.line(), "missing", List.of(site), ignored -> true));
    assertTrue(ServiceLoaderCallSiteSelector.hasCallOnStatement(
        source, site.line(), null, List.of(site), ignored -> true));
    assertFalse(ServiceLoaderCallSiteSelector.hasCallOnStatement(
        source, site.line(), "<init>", List.of(site), ignored -> true));
    assertFalse(ServiceLoaderCallSiteSelector.hasCallOnStatement(
        source, site.line(), "record", List.of(site, site), ignored -> true));
    assertFalse(ServiceLoaderCallSiteSelector.hasCallOnStatement(
        source, site.line(), "record", List.of(site), ignored -> false));

    String multipleTargets = "java.util.ServiceLoader.load(AuditProvider.class)"
        + ".map(value -> value.record()).ifPresent(provider -> provider.record());";
    ServiceLoaderCallSite multipleTargetSite =
        ServiceLoaderCallSiteParser.parse(multipleTargets).get(0);
    assertFalse(ServiceLoaderCallSiteSelector.hasCallOnStatement(
        multipleTargets, multipleTargetSite.line(), "record", List.of(multipleTargetSite),
        ignored -> true));

    String siblingArgument = "consume(java.util.ServiceLoader.load(AuditProvider.class),"
        + " provider.record());";
    ServiceLoaderCallSite siblingSite = ServiceLoaderCallSiteParser.parse(siblingArgument).get(0);
    assertFalse(ServiceLoaderCallSiteSelector.hasCallOnStatement(
        siblingArgument, siblingSite.line(), "record", List.of(siblingSite), ignored -> true));
  }

  @Test
  void selectBindsStatementAndUsesConservativeSingleSiteFallback() throws IOException {
    Path sourceRoot = temp.resolve("src/main/java");
    String source = """
        package demo;
        class Bridge {
          void audit() {
            java.util.ServiceLoader.load(AuditProvider.class).findFirst()
                .ifPresent(provider -> provider.record());
          }
        }
        """;
    write(sourceRoot, "demo/Bridge.java", source);
    AnalysisContext context = context(sourceRoot);
    List<ServiceLoaderCallSite> sites = ServiceLoaderCallSiteParser.parse(source);
    int callLine = sites.get(0).line();

    ServiceLoaderCallSiteSelector.Selection statement = ServiceLoaderCallSiteSelector.select(
        context, edge(callLine), source, sites);
    assertEquals(List.of(sites.get(0)), statement.sites());
    assertTrue(statement.statementScoped());
    assertTrue(statement.uniquelyBound());

    ServiceLoaderCallSiteSelector.Selection beforeCall = ServiceLoaderCallSiteSelector.select(
        context, edge(callLine - 1), source, sites);
    assertEquals(List.of(sites.get(0)), beforeCall.sites());
    assertFalse(beforeCall.statementScoped());
    assertFalse(beforeCall.uniquelyBound());

    ServiceLoaderCallSiteSelector.Selection unknownLine = ServiceLoaderCallSiteSelector.select(
        context, edge(-1), source, sites);
    assertEquals(List.of(sites.get(0)), unknownLine.sites());
    assertFalse(unknownLine.statementScoped());
    assertFalse(unknownLine.uniquelyBound());

    assertTrue(ServiceLoaderCallSiteSelector.select(
        context, edge(callLine), source, List.of()).sites().isEmpty());
    assertTrue(ServiceLoaderCallSiteSelector.select(
        context,
        new CallEdge(
            new MethodSymbol("demo.Missing", "audit", "()V"),
            edge(callLine).callee(),
            callLine,
            edge(callLine).sourcePath(),
            SemanticCallFacts.Certainty.POSSIBLE,
            "service_loader"),
        source,
        sites).sites().isEmpty());
  }

  @Test
  void malformedSourcePositionsDoNotCreateMatches() {
    String malformed = "java.util.ServiceLoader.load(AuditProvider.class";
    ServiceLoaderCallSite malformedSite = new ServiceLoaderCallSite(
        "java.util.ServiceLoader", "AuditProvider", false, false,
        malformed.indexOf("load"), 1);
    assertFalse(ServiceLoaderCallSiteSelector.hasCallOnStatement(
        malformed, 1, "record", List.of(malformedSite), ignored -> true));

    String noTerminator = "java.util.ServiceLoader.load(AuditProvider.class)"
        + ".ifPresent(provider -> provider.record())";
    ServiceLoaderCallSite noTerminatorSite = ServiceLoaderCallSiteParser.parse(noTerminator).get(0);
    assertTrue(ServiceLoaderCallSiteSelector.hasCallOnStatement(
        noTerminator, 1, "record", List.of(noTerminatorSite), ignored -> true));
    assertFalse(ServiceLoaderCallSiteSelector.hasCallOnStatement(
        noTerminator, 2, "record", List.of(noTerminatorSite), ignored -> true));
  }

  private AnalysisContext context(Path sourceRoot) {
    return AnalysisContext.builder()
        .id("selector-test")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("java21-quarkus")
        .javaRelease(21)
        .repositoryRoot(temp)
        .applicationClasses(temp.resolve("classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
  }

  private CallEdge edge(int line) {
    return new CallEdge(
        new MethodSymbol("demo.Bridge", "audit", "()V"),
        new MethodSymbol("demo.Provider", "record", "()V"),
        line,
        "src/main/java/demo/Bridge.java",
        SemanticCallFacts.Certainty.POSSIBLE,
        "service_loader");
  }

  private static void write(Path root, String relative, String source) throws IOException {
    Path path = root.resolve(relative);
    Files.createDirectories(path.getParent());
    Files.writeString(path, source);
  }
}
