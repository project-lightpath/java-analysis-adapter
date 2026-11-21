package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalysisContextLoaderTest {
  @TempDir Path temp;

  /** Rejects an excluded ordinary source root because the builder includes it unconditionally. */
  @Test
  void rejectsExcludedOrdinarySourceRoot() throws IOException {
    Path sourceRoot = temp.resolve("src/optional/java");
    Files.createDirectories(sourceRoot);
    AnalysisContext.Builder builder = AnalysisContext.builder();

    assertThrows(IllegalArgumentException.class, () -> AnalysisContextLoaderLayer05.addRoot(
        builder,
        new AnalysisContextLoaderSupport.ResolvedRootDocument(
            "src/optional/java", sourceRoot.toString(), "production", false, false, false, null),
        false,
        temp));
  }

  /** Loads the VPE handoff and preserves build and provenance metadata. */
  @Test
  void loadsVpeContextAndPreservesBuildAndProvenanceHandoff() throws IOException {
    Path sourceRoot = temp.resolve("src/main/java");
    Path generatedRoot = temp.resolve("target/generated-sources");
    Path classes = temp.resolve("target/classes");
    Path dependency = temp.resolve("dependency.jar");
    Files.createDirectories(sourceRoot);
    Files.createDirectories(generatedRoot);
    Files.createDirectories(classes);
    Files.writeString(sourceRoot.resolve("Application.java"), "package demo; class Application {}\n");
    Files.writeString(dependency, "dependency");
    Path contextFile = temp.resolve("analysis-context.json");
    Files.writeString(contextFile, """
        {
          "schemaVersion": "0.1.0",
          "id": "fsi-spring-boot-production",
          "fixtureId": "fsi-spring-boot",
          "fixture": {"id": "fsi-spring-boot", "javaRelease": 17, "repositoryPath": "%s"},
          "profile": {"id": "fsi-spring-boot-production", "version": "1.0.0", "digest": "profile-digest"},
          "application": {"id": "fsi-spring-boot", "name": "FSI", "repository": "https://github.com/example/app"},
          "framework": "spring-boot",
          "frameworkVersion": "2.7.18",
          "javaRelease": 17,
          "repositoryRoot": "%s",
          "sourceRevision": "0123456789012345678901234567890123456789",
          "selectedProfile": "default",
          "analysisScope": "production",
          "sourceRoots": [{
            "declaredPath": "src/main/java",
            "path": "%s",
            "scope": "production",
            "generated": false,
            "included": true,
            "required": true,
            "fingerprint": "source-fingerprint"
          }],
          "generatedSourceRoots": [{
            "declaredPath": "target/generated-sources",
            "path": "%s",
            "scope": "production",
            "generated": true,
            "included": true,
            "required": false,
            "fingerprint": "generated-root-fingerprint"
          }],
          "dependencyClasspath": ["%s"],
          "applicationClasses": "%s",
          "frameworkMetadata": [{
            "id": "spring-web-annotations",
            "kind": "source_annotations",
            "status": "observed",
            "path": "src/main/java"
          }],
          "frameworkBindingMetadata": {
            "schemaVersion": "0.1.0",
            "candidates": [{
              "id": "demo-service",
              "candidateType": "demo.ServiceImpl",
              "contracts": ["Service"],
              "qualifiers": ["Qualifier:fast"],
              "kind": "Service",
              "active": true,
              "primary": true
            }],
            "resolvedBindings": [{
              "id": "application-service",
              "injectionOwner": "demo.Application",
              "injectionMember": "<init>",
              "injectionType": "Service",
              "parameterIndex": 0,
              "qualifiers": ["Qualifier:fast"],
              "candidateId": "demo-service",
              "selected": true,
              "active": true,
              "selectionReason": "unique active candidate"
            }],
            "proxyTargets": [{
              "id": "service-proxy",
              "candidateId": "demo-service",
              "targetType": "demo.ServiceImpl",
              "methodName": "call",
              "proxyKind": "spring-aop-advisor",
              "selected": true,
              "active": true
            }]
          },
          "build": {
            "tool": "maven",
            "command": ["mvn", "package"],
            "artifact": "target/application.jar",
            "fingerprint": "build-fingerprint"
          },
          "provenance": {
            "sourceRepository": "https://github.com/example/app.git",
            "sourceReference": "0123456789012345678901234567890123456789",
            "sourceRevision": "0123456789012345678901234567890123456789",
            "sourceFingerprint": "source-fingerprint",
            "generatedSourceFingerprint": "generated-fingerprint",
            "dependencyFingerprint": "dependency-fingerprint",
            "buildFingerprint": "build-fingerprint",
            "toolchainFingerprint": "toolchain-fingerprint",
            "analyzerFingerprint": "analyzer-fingerprint",
            "gitRevision": "0123456789012345678901234567890123456789",
            "providerRuntime": "project-lightpath/java-analysis-adapter@0.1.0/java-17",
            "profileDigest": "profile-digest",
            "preparedAt": "2026-01-01T00:00:00Z",
            "freshness": {"maxAgeSeconds": 86400}
          },
          "limitations": ["manual run"]
        }
        """.formatted(
         temp,
         temp,
         sourceRoot,
         generatedRoot,
         dependency,
         classes));

    AnalysisContext context = AnalysisContextLoader.load(contextFile);

    assertEquals("fsi-spring-boot-production", context.id());
    assertEquals(AnalysisContext.Framework.SPRING_BOOT, context.framework());
    assertEquals(Path.of(classes.toString()).toAbsolutePath().normalize(), context.applicationClasses());
    assertTrue(context.dependencyClasspathRequired());
    assertEquals("0123456789012345678901234567890123456789", context.provenance().gitRevision());
    assertEquals("dependency-fingerprint", context.provenance().dependencyFingerprint());
    assertEquals("generated-root-fingerprint", context.generatedSourceRoots().get(0).fingerprint());
    assertEquals("maven", context.handoff().buildTool());
    assertEquals(List.of("mvn", "package"), context.handoff().buildCommand());
    assertEquals(List.of("manual run"), context.handoff().limitations());
    assertTrue(context.metadataObserved("spring-web-annotations"));
    assertEquals(1, context.bindingCandidates().size());
    assertEquals("demo-service", context.bindingCandidates().get(0).candidateId());
    assertEquals(1, context.resolvedBindings().size());
    assertEquals("demo-service", context.resolvedBindings().get(0).candidateId());
    assertEquals(1, context.proxyTargetMetadata().size());
    assertEquals("call", context.proxyTargetMetadata().get(0).methodName());

    Path factsFile = temp.resolve("semantic-call-facts.json");
    SemanticCallFactsJsonWriter.write(
        new JavaAnalysisAdapter().analyze(context, AnalysisGraph.empty()), factsFile);
    assertTrue(Files.readString(factsFile).contains("\"fingerprint\" : \"generated-root-fingerprint\""));
  }
}
