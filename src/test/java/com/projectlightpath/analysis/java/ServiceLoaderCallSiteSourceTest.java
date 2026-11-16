package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ServiceLoaderCallSiteSourceTest {
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
    List<ServiceLoaderCallSite> sites = ServiceLoaderCallSiteParser.parse(source);
    assertEquals(1, sites.size());
    assertTrue(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
        source, sites.get(0), "record", 6, "value"));
  }

  @Test
  void usesReceiverIdentityForSameLineInvocations() {
    String source = "import java.util.ServiceLoader;\n"
        + "ServiceLoader.load(AuditProvider.class).findFirst()"
        + ".ifPresent(p -> { p.record(); other.record(); });\n";
    ServiceLoaderCallSite site = ServiceLoaderCallSiteParser.parse(source).get(0);

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
    ServiceLoaderCallSite site = ServiceLoaderCallSiteParser.parse(source).get(0);

    assertTrue(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(source, site, "record", 3, "p"));
    assertTrue(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(source, site, "record", 4, "p"));
  }

  @Test
  void doesNotCarryProviderProvenanceThroughReplacingMapLambda() {
    String source = "import java.util.ServiceLoader;\n"
        + "ServiceLoader.load(AuditProvider.class).findFirst()"
        + ".map(p -> other).ifPresent(q -> q.record());\n";
    ServiceLoaderCallSite site = ServiceLoaderCallSiteParser.parse(source).get(0);

    assertFalse(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
        source, site, "record", 2, "q"));
  }

  @Test
  void preservesProviderProvenanceThroughFilteringLambda() {
    String source = "import java.util.ServiceLoader;\n"
        + "ServiceLoader.load(AuditProvider.class).findFirst()"
        + ".filter(p -> true).ifPresent(q -> q.record());\n";
    ServiceLoaderCallSite site = ServiceLoaderCallSiteParser.parse(source).get(0);

    assertTrue(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
        source, site, "record", 2, "q"));
  }

  @Test
  void allowsKeywordBeforeBareProviderReceiver() {
    String source = "import java.util.ServiceLoader;\n"
        + "return ServiceLoader.load(AuditProvider.class).findFirst()"
        + ".ifPresent(p -> { return p.record(); });\n";
    ServiceLoaderCallSite site = ServiceLoaderCallSiteParser.parse(source).get(0);

    assertTrue(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
        source, site, "record", 2, "p"));
  }

  @Test
  void bindsDirectProviderCallThroughOrElseThrow() {
    String source = "import java.util.ServiceLoader;\n"
        + "ServiceLoader.load(AuditProvider.class).findFirst().orElseThrow().record();\n";
    ServiceLoaderCallSite site = ServiceLoaderCallSiteParser.parse(source).get(0);

    assertTrue(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
        source, site, "record", 2));
  }

  @Test
  void rejectsReassignedProviderLambdaParameter() {
    String source = "import java.util.ServiceLoader;\n"
        + "ServiceLoader.load(AuditProvider.class).findFirst()"
        + ".ifPresent(p -> { p = other; p.record(); });\n";
    ServiceLoaderCallSite site = ServiceLoaderCallSiteParser.parse(source).get(0);

    assertFalse(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
        source, site, "record", 2, "p"));
  }
}
