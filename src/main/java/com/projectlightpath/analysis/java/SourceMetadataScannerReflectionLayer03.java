package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerReflectionLayer03 extends SourceMetadataScannerReflectionLayer02 {

  SourceMetadataScannerReflectionLayer03(AnalysisContext context) {
    super(context);
  }



static Optional<LocalDeclaration> sharedLocalDeclaration(
      String source, MethodInfo owner, String variable, int offset) {
    Pattern sharedDeclaration = Pattern.compile(
        "(?m)(?:^|[;{}:])\\s*"
            + "(?:(?:final|volatile|transient)\\s+"
            + "|@[A-Za-z_][\\w.]*(?:\\s*\\([^;]*\\))?\\s*)*"
            + "([A-Za-z_$][\\w$.]*(?:\\s*<[^;=(){}]+>)?(?:\\s*\\[\\])*)\\s+");
    Pattern basicForSharedDeclaration = Pattern.compile(
        "(?m)\\bfor\\s*\\(\\s*"
            + "(?:(?:final|volatile|transient)\\s+"
            + "|@[A-Za-z_][\\w.]*(?:\\s*\\([^;]*\\))?\\s*)*"
            + "([A-Za-z_$][\\w$.]*(?:\\s*<[^;=(){}]+>)?(?:\\s*\\[\\])*)\\s+");
    LocalDeclaration selected = null;
    for (Pattern pattern : List.of(sharedDeclaration, basicForSharedDeclaration)) {
      Matcher matcher = pattern.matcher(source);
      matcher.region(owner.start(), Math.min(offset, source.length()));
      while (matcher.find()) {
        selected = sharedDeclarationMatch(
            source, matcher, variable, offset, owner.start(), selected);
      }
    }
    return Optional.ofNullable(selected);
  }

  FieldReference visibleField(
      SourceFile file, String variable, int siteOffset, String selectedOwner) {
    Pattern fieldStart = Pattern.compile(
        "(?m)(?:^|[;{}])\\s*(?:@[A-Za-z_][\\w.]*(?:\\s*\\([^;]*\\))?\\s*)*"
            + "(?:(?:public|protected|private|static|final|transient|volatile|strictfp)\\s+)*"
            + "([A-Za-z_$][\\w$.]*(?:\\s*<[^;=(){}]+>)?(?:\\s*\\[\\])*)\\s+");
    String siteType = typeContaining(file, siteOffset);
    Map<String, Integer> visibleOwners = selectedOwner == null
        ? visibleFieldOwners(file, siteType)
        : selectedOwnerFieldOwners(selectedOwner);
    FieldReference selected = null;
    int selectedDistance = Integer.MAX_VALUE;
    for (SourceFile candidate : files()) {
      Optional<VisibleField> visible = visibleFieldInCandidate(
          candidate, file, variable, siteType, fieldStart, visibleOwners);
      if (visible.isPresent() && visible.get().distance() < selectedDistance) {
        selectedDistance = visible.get().distance();
        selected = visible.get().reference();
      }
    }
    return selected;
  }

  Optional<LocalDeclaration> activeLocalDeclaration(
      SourceFile file, MethodInfo owner, String variable, int offset) {
    String source = maskStringLiterals(file.structuralSource());
    Optional<LocalDeclaration> simple = simpleLocalDeclaration(source, owner, variable, offset);
    Optional<LocalDeclaration> shared = sharedLocalDeclaration(source, owner, variable, offset);
    return shared.isPresent() ? shared : simple;
  }

  FieldReference visibleField(SourceFile file, String variable, int siteOffset) {
    return visibleField(file, variable, siteOffset, null);
  }

  boolean hasVisibleValueNamed(SourceFile file, String variable, int offset) {
    MethodInfo owner = methodContaining(file, offset);
    return (!owner.name().equals("<unknown>") && hasParameter(owner, variable))
        || (!owner.name().equals("<unknown>")
            && activeLocalDeclaration(file, owner, variable, offset).isPresent())
        || visibleField(file, variable, offset) != null;
  }

  boolean hasVisibleField(
      SourceFile file, String type, String variable, int siteOffset, String selectedOwner) {
    FieldReference visibleField = visibleField(file, variable, siteOffset, selectedOwner);
    return visibleField != null
        && isJdkDeclaredType(
            visibleField.file(), visibleField.type(), visibleField.offset(), type);
  }

  boolean hasVisibleField(SourceFile file, String type, String variable, int siteOffset) {
    return hasVisibleField(file, type, variable, siteOffset, null);
  }
}
