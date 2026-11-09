package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MethodSymbolTest {
  @Test
  void normalizesSootMethodSignaturesToJvmDescriptors() {
    MethodSymbol method = MethodSymbol.fromSootSignature(
        "<demo.Service: java.lang.String run(java.lang.String, int)>");

    assertEquals("demo.Service", method.owner());
    assertEquals("run", method.name());
    assertEquals("(Ljava/lang/String;I)Ljava/lang/String;", method.descriptor());
  }

  @Test
  void normalizesConstructorsArraysAndNestedTypes() {
    MethodSymbol constructor = MethodSymbol.fromSootSignature(
        "<demo.Outer$Inner: void <init>(java.lang.String[], int[][])>");

    assertEquals("<init>", constructor.name());
    assertEquals("([Ljava/lang/String;[[I)V", constructor.descriptor());
  }
}
