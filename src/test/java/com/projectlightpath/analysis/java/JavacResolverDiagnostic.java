package com.projectlightpath.analysis.java;

import java.util.List;

/** A stable, relative-path-only diagnostic snapshot. */
record JavacResolverDiagnostic(String code, String message, String path, long line, long column) {}
