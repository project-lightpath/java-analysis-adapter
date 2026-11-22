package com.projectlightpath.analysis.java;

/** One source-archive extraction rejection cause; translated once, at the run boundary. */
class SourceArchiveExtractionFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;

  SourceArchiveExtractionFailure(String message) {
    super(message);
  }

  SourceArchiveExtractionFailure(String message, Throwable cause) {
    super(message, cause);
  }
}
