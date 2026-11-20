package com.projectlightpath.analysis.java;

/** One source-level ServiceLoader.load invocation. */
record ServiceLoaderCallSite(
    String serviceType,
    String fallbackServiceType,
    boolean explicitClassLoader,
    int offset,
    int line) {

  /** Compile stub for the removal slice; production still supplies both types. */
  ServiceLoaderCallSite(
      String serviceType, boolean explicitClassLoader, int offset, int line) {
    this(serviceType, serviceType, explicitClassLoader, offset, line);
  }
}
