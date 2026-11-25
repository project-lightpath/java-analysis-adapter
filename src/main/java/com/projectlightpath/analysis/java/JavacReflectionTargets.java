package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves source-bound reflection targets against the javac source-symbol
 * index (issue #25): a {@code Class.forName} / {@code ClassLoader
 * .loadClass} literal or a {@code getMethod}-family site whose receiver
 * provably names a source-defined type is keyed by its binary name and
 * proven with javac shapes; runtime strings return {@link
 * JavacTargetEvidence#NONE} and keep the pre-existing abstention. Targets
 * the index sees but cannot prove (error, duplicate, ambiguity, visibility,
 * shape mismatch) carry a diagnostic and downgrade to POSSIBLE — never a
 * fabricated DEFINITE.
 */
final class JavacReflectionTargets {

  /**
   * Index integrity rows that disable the whole path: under any of these
   * the index is partial or unbuildable, so its uniqueness claims would be
   * fabricated (a type declared in both a present and a missing source
   * root would look unique). Per-file javac error rows are NOT here —
   * they manifest as per-entry errorType, which abstains per target.
   */
  private static final Set<String> INTEGRITY_CODES = Set.of(
      "index_build_error", "javac_unavailable", "missing_source",
      "missing_classpath", "missing_source_root");

  private final SourceMetadataScanner scanner;
  private final Optional<JavacSourceSymbolIndex> index;
  private final boolean disabled;

  private JavacReflectionTargets(SourceMetadataScanner scanner) {
    this.scanner = scanner;
    this.index = JavacSourceSymbolIndex.enabled()
        ? JavacSourceSymbolIndex.of(scanner.context)
        : Optional.empty();
    this.disabled = index.map(current -> current.diagnostics().stream()
            .anyMatch(row -> INTEGRITY_CODES.contains(row.code())))
        .orElse(true);
  }

  /** Returns the resolver bound to one scanner's context. */
  static JavacReflectionTargets of(SourceMetadataScanner scanner) {
    Objects.requireNonNull(scanner);
    return new JavacReflectionTargets(scanner);
  }

  /** Resolves one reflection site's target, or NONE for the runtime-string path. */
  JavacTargetEvidence evidence(
      SourceMetadataScannerState.SourceFile file,
      SourceMetadataScannerState.DynamicSite site) {
    Objects.requireNonNull(file);
    Objects.requireNonNull(site);
    if (disabled) {
      return JavacTargetEvidence.NONE;
    }
    return switch (site.symbol()) {
      case "Class.forName", "ClassLoader.loadClass" -> classEvidence(file, site);
      case "getMethod" -> memberEvidence(file, site, false, true);
      case "getDeclaredMethod" -> memberEvidence(file, site, false, false);
      case "getConstructor" -> memberEvidence(file, site, true, true);
      case "getDeclaredConstructor" -> memberEvidence(file, site, true, false);
      default -> JavacTargetEvidence.NONE;
    };
  }

  private JavacTargetEvidence classEvidence(
      SourceMetadataScannerState.SourceFile file,
      SourceMetadataScannerState.DynamicSite site) {
    String literal = SourceMetadataScannerTextLayer10
        .firstQuotedArgument(file.structuralSource(), site.offset());
    if (literal == null || literal.isBlank()) {
      return JavacTargetEvidence.NONE;
    }
    OwnerResolution owner = classLookup(literal);
    if (owner.absent()) {
      return JavacTargetEvidence.NONE;
    }
    if (owner.diagnostic() != null) {
      return possible(owner.diagnostic(), owner.keyed(), "class", List.of(), List.of());
    }
    JavacSourceSymbolIndex.TypeInfo entry = owner.entry();
    List<String> annotations = typeAnnotationNames(entry.annotations());
    // Class.forName resolves BINARY names: a literal that matched only the
    // canonical spelling (demo.Outer.Inner) cannot load the type.
    if (!entry.binaryName().equals(literal)) {
      return possible("binary_name_mismatch", entry.binaryName(), "class",
          List.of(), annotations);
    }
    return new JavacTargetEvidence(true, entry.binaryName(), "class", "",
        List.of(), annotations, "");
  }

  private JavacTargetEvidence memberEvidence(
      SourceMetadataScannerState.SourceFile file,
      SourceMetadataScannerState.DynamicSite site,
      boolean constructorLookup,
      boolean publicOnly) {
    Optional<LookupSite> lookup = lookupSite(file, site, constructorLookup);
    if (lookup.isEmpty()) {
      return JavacTargetEvidence.NONE;
    }
    LookupSite current = lookup.orElseThrow();
    OwnerResolution owner = classLookup(current.ownerName());
    if (owner.absent()) {
      return JavacTargetEvidence.NONE;
    }
    String kind = constructorLookup ? "constructor" : "method";
    String memberKey = constructorLookup ? "<init>" : current.nameLiteral();
    String ownerKeyed = owner.keyed() + "#" + memberKey;
    if (owner.diagnostic() != null) {
      return possible(owner.diagnostic(), ownerKeyed, kind, List.of(), List.of());
    }
    List<JavacSourceSymbolIndex.MemberInfo> candidates = constructorLookup
        ? owner.entry().members().stream()
            .filter(member -> "constructor".equals(member.kind())).toList()
        : index.orElseThrow().members(owner.entry().canonicalName(), current.nameLiteral());
    if (candidates.isEmpty()) {
      return possible("member_not_declared", ownerKeyed, kind, List.of(), List.of());
    }
    if (candidates.stream().anyMatch(JavacSourceSymbolIndex.MemberInfo::errorType)) {
      return possible("error_member", ownerKeyed, kind, List.of(), List.of());
    }
    List<String> vector = new JavacReflectionArguments(scanner, index.orElseThrow())
        .vector(file, current.arguments(), constructorLookup, site.offset());
    if (vector == null) {
      return JavacTargetEvidence.NONE;
    }
    return selectMember(candidates, vector, ownerKeyed, kind, publicOnly);
  }

  /** One lookup site's extraction: name literal, arguments, and owner name. */
  private Optional<LookupSite> lookupSite(
      SourceMetadataScannerState.SourceFile file,
      SourceMetadataScannerState.DynamicSite site,
      boolean constructorLookup) {
    String structural = file.structuralSource();
    String masked = SourceMetadataScannerFileExtracts.of(file).literalMaskedSource();
    JavacReflectionReceivers receivers = new JavacReflectionReceivers(scanner);
    int methodNameStart = receivers.methodNameStart(masked, site.symbol(), site.offset());
    if (methodNameStart < 0) {
      return Optional.empty();
    }
    String nameLiteral = SourceMetadataScannerTextLayer10
        .firstQuotedArgument(structural, methodNameStart);
    if (!constructorLookup && nameLiteral == null) {
      return Optional.empty();
    }
    int opening = masked.indexOf('(', methodNameStart + site.symbol().length());
    int closing = opening < 0 ? -1 : SourceMetadataScannerTextLayer05
        .matchingDelimiter(masked, opening, '(', ')');
    if (closing < 0) {
      return Optional.empty();
    }
    List<String> arguments = SourceMetadataScannerTextLayer06
        .splitTopLevelArguments(masked.substring(opening + 1, closing));
    String range = JavacReflectionReceivers.receiverRange(masked, methodNameStart);
    return receivers.ownerName(file, structural, range,
            JavacReflectionReceivers.rangeStart(masked, methodNameStart))
        .map(ownerName -> new LookupSite(nameLiteral, arguments, ownerName));
  }

  private JavacTargetEvidence selectMember(
      List<JavacSourceSymbolIndex.MemberInfo> candidates,
      List<String> vector,
      String ownerKeyed,
      String kind,
      boolean publicOnly) {
    List<JavacSourceSymbolIndex.MemberInfo> matches = candidates.stream()
        .filter(member -> member.parameterTypes().equals(vector))
        .toList();
    if (matches.isEmpty()) {
      return possible("shape_mismatch", ownerKeyed, kind, List.of(), List.of());
    }
    if (matches.size() > 1) {
      return possible("ambiguous_member", ownerKeyed, kind, List.of(), List.of());
    }
    JavacSourceSymbolIndex.MemberInfo match = matches.get(0);
    // getMethod/getConstructor return PUBLIC members only; a non-public
    // match throws NoSuchMethodException at runtime, so it is never a
    // proven target. The declared variants see every visibility.
    if (publicOnly && !match.publicMember()) {
      return possible("visibility_mismatch", ownerKeyed, kind, List.of(), List.of());
    }
    return new JavacTargetEvidence(true, ownerKeyed, kind,
        JavacShapeDescriptors.jvmDescriptorFromMember(match).orElse(""),
        match.parameterTypes(), memberAnnotationNames(match), "");
  }

  /** The class-target ladder: unique unflagged non-error entry, or a diagnostic. */
  private OwnerResolution classLookup(String name) {
    List<JavacSourceSymbolIndex.TypeInfo> entries = index.orElseThrow().type(name);
    if (entries.isEmpty()) {
      return OwnerResolution.ABSENT;
    }
    if (entries.stream().anyMatch(entry -> entry.shape().duplicateBinaryName())) {
      return new OwnerResolution(null, "duplicate_target", name);
    }
    if (entries.size() > 1) {
      return new OwnerResolution(null, "ambiguous_target", name);
    }
    JavacSourceSymbolIndex.TypeInfo entry = entries.get(0);
    if (entry.shape().errorType()) {
      return new OwnerResolution(null, "error_target", entry.binaryName());
    }
    return new OwnerResolution(entry, null, entry.binaryName());
  }

  /** Distinct names (no overloads): JDK 17's PMD mis-resolves private
   * overloads as unused — the #64 environment divergence. */
  private static List<String> typeAnnotationNames(
      List<JavacSourceSymbolIndex.AnnotationInfo> declarations) {
    LinkedHashSet<String> names = new LinkedHashSet<>();
    declarations.forEach(annotation -> names.add(annotation.canonicalName()));
    return List.copyOf(names);
  }

  private static List<String> memberAnnotationNames(
      JavacSourceSymbolIndex.MemberInfo member) {
    List<JavacSourceSymbolIndex.AnnotationInfo> declarations =
        new ArrayList<>(member.annotations());
    member.parameterAnnotations().forEach(declarations::addAll);
    return typeAnnotationNames(declarations);
  }

  private static JavacTargetEvidence possible(
      String diagnostic, String keyedTarget, String kind,
      List<String> parameterTypes, List<String> annotationNames) {
    return new JavacTargetEvidence(false, keyedTarget, kind, "",
        parameterTypes, annotationNames, diagnostic);
  }

  /** One index lookup: a unique entry, a diagnostic, or absent (runtime string). */
  private record OwnerResolution(
      JavacSourceSymbolIndex.TypeInfo entry,
      String diagnostic,
      String keyed) {

    static final OwnerResolution ABSENT = new OwnerResolution(null, null, "");

    boolean absent() {
      return entry == null && diagnostic == null;
    }
  }

  /** One member lookup site's extracted inputs. */
  private record LookupSite(String nameLiteral, List<String> arguments, String ownerName) {}
}
