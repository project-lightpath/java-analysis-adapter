package com.projectlightpath.analysis.java;

/** Bounded Spring MVC/bean/AOP/repository/lifecycle model for the selected context. */
public final class SpringBootFrameworkModel extends BoundedFrameworkModel {
  public SpringBootFrameworkModel() {
    super(AnalysisContext.Framework.SPRING_BOOT);
  }
}
