package com.projectlightpath.analysis.java;

final class FrameworkModels {
  private FrameworkModels() {}

  static FrameworkModel forContext(AnalysisContext.Framework framework) {
    return switch (framework) {
      case QUARKUS -> new QuarkusFrameworkModel();
      case SPRING_BOOT -> new SpringBootFrameworkModel();
    };
  }
}
