package com.projectlightpath.analysis.java;

/** One source-level ServiceLoader invocation candidate. */
record ServiceLoaderCallSite(
    String receiver,
    String serviceType,
    boolean serviceTypeRequiresResolution,
    boolean explicitClassLoader,
    int offset,
    int line) {}
