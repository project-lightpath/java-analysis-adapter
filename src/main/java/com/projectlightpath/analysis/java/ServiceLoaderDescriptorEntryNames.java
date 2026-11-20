package com.projectlightpath.analysis.java;

/** Classifies ServiceLoader descriptor entry names inside an archive. */
final class ServiceLoaderDescriptorEntryNames {
  private static final String VERSIONED_PREFIX = "META-INF/versions/";
  private static final String VERSIONED_SUFFIX =
      "/" + ServiceLoaderDescriptorSource.MODULE_INFO;

  private ServiceLoaderDescriptorEntryNames() {}

  static boolean isVersionedModuleInfo(String name) {
    // Bounds first: a path whose prefix and suffix overlap (for example
    // META-INF/versions/module-info.class) has no room for a version
    // component, and an exactly-boundary path has an empty one.
    if (name.length() <= VERSIONED_PREFIX.length() + VERSIONED_SUFFIX.length()
        || !name.startsWith(VERSIONED_PREFIX) || !name.endsWith(VERSIONED_SUFFIX)) {
      return false;
    }
    String version = name.substring(
        VERSIONED_PREFIX.length(), name.length() - VERSIONED_SUFFIX.length());
    return version.chars().allMatch(Character::isDigit);
  }
}
