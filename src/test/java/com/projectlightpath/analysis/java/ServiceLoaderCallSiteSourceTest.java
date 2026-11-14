package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        source, sites.get(0), "record"));
  }
}
