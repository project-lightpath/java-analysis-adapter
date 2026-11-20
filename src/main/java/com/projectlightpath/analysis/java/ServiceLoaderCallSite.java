package com.projectlightpath.analysis.java;

/** One source-level ServiceLoader.load invocation. */
record ServiceLoaderCallSite(
    String serviceType,
    boolean explicitClassLoader,
    int offset,
    int line) {}
