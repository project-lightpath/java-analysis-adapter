package com.projectlightpath.analysis.java;

/** One source-level ServiceLoader invocation candidate. */
record ServiceLoaderCallSite(
    String receiver,
    String serviceType,
    String fallbackServiceType,
    boolean explicitClassLoader,
    int offset,
    int line) {}
