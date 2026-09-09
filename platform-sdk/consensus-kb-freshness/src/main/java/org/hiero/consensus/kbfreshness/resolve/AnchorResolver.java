// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.resolve;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.hiero.consensus.kbfreshness.model.Anchor;
import org.hiero.consensus.kbfreshness.model.Lane;
import org.hiero.consensus.kbfreshness.model.Outcome;
import org.hiero.consensus.kbfreshness.resolve.JavaParsing.ParsedFile;
import org.hiero.consensus.kbfreshness.resolve.JavaParsing.TypeInfo;
import org.hiero.consensus.kbfreshness.util.Markdown;
import org.hiero.consensus.kbfreshness.util.RepoPaths;

/**
 * Resolves an {@link Anchor} to a three-valued {@link Resolution}. Precision is the mandate: an
 * {@code assert} is emitted only when the target is certainly gone with one-look evidence; anything
 * external, generated, or ambiguous is {@code unverifiable} and routed to the quiet log. A cited
 * symbol that resolves elsewhere is a package/path-move signal (present, but reported), never
 * {@code absent}. A resolved symbol whose cited line moved emits an auto-fix, never an assertion.
 */
public final class AnchorResolver {

    /** Matches an ATX markdown heading, capturing the hash run and the heading text. */
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    /** Matches a markdown link, capturing its display text. */
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
    /** Matches a GitHub duplicate-heading suffix (e.g. {@code -2}) at the end of a slug. */
    private static final Pattern TRAILING_DEDUP = Pattern.compile("-\\d+$");

    /** Catalog ID prefix to the knowledge-base subdirectory that holds its entries. */
    private static final Map<String, String> CATALOG_DIR = Map.of(
            "ADR", "decisions",
            "INV", "invariants",
            "RUL", "rules",
            "SCN", "scenarios",
            "HEU", "heuristics");

    /** Absolute, normalized repository root. */
    private final Path repoRoot;
    /** Absolute, normalized knowledge-base root holding catalog files. */
    private final Path kbRoot;
    /** Source index used for existence checks and parse-only lookups. */
    private final SourceIndex index;
    /** Allowlist classifying external/generated sources and types. */
    private final Allowlist allowlist;
    /** Per-run cache of repo-relative markdown path to its set of heading slugs. */
    private final Map<String, Set<String>> headingCache = new HashMap<>();

    /**
     * Creates a resolver over the given repository and knowledge-base roots.
     *
     * @param repoRoot  the repository root; resolved to an absolute, normalized path.
     * @param kbRoot    the knowledge-base root; resolved to an absolute, normalized path.
     * @param index     the source index for existence and parse lookups.
     * @param allowlist the allowlist classifying external/generated sources.
     */
    public AnchorResolver(final Path repoRoot, final Path kbRoot, final SourceIndex index, final Allowlist allowlist) {
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        this.kbRoot = kbRoot.toAbsolutePath().normalize();
        this.index = index;
        this.allowlist = allowlist;
    }

    /**
     * Resolves an anchor to a three-valued resolution by dispatching on its kind.
     *
     * @param a the anchor to resolve.
     * @return the resolution for the anchor.
     */
    public Resolution resolve(final Anchor a) {
        return switch (a.kind()) {
            case MODULE_DIR -> resolveModuleDir(a);
            case CROSS_DOC_LINK -> resolveCrossDoc(a);
            case DOC_HEADING -> resolveHeading(a);
            case CATALOG_ID -> resolveCatalogId(a);
            case PACKAGE_REF -> resolvePackageRef(a);
            case SOURCE_PATH -> resolveSourcePath(a);
            case SOURCE_BASENAME -> resolveSourceBasename(a);
            case SOURCE_SYMBOL -> resolveSourceSymbol(a);
            case CLASS -> resolveClassFqn(a);
            case METHOD_ON_CLASS -> resolveMethodOnClass(a);
            case METHOD_REF -> resolveMethodRef(a);
            case METHOD_SIGNATURE -> resolveMethodSignature(a);
            // These kinds are never resolved per-anchor: the config kinds and INTERFACE_METHOD are
            // produced and checked by the Tier-2 diff assemblers, and ENUM_CONSTANT is not extracted in
            // this version. Reaching here with one is a wiring error, not a symbol result.
            case ENUM_CONSTANT, CONFIG_KEY, CONFIG_PREFIX, CONFIG_DEFAULT, INTERFACE_METHOD ->
                throw new IllegalArgumentException("Anchor kind " + a.kind()
                        + " is not resolved by the per-anchor pipeline (config/interface kinds are handled by the "
                        + "Tier-2 diff assemblers; ENUM_CONSTANT is unused).");
        };
    }

    /**
     * Resolves a prose package reference by checking the indexed package tree. Present when an indexed
     * source lives in the package or a subpackage of it. Asserted gone only when the package's own
     * namespace (its first two segments) is indexed — a package outside every indexed namespace is
     * external (e.g. a library package) and stays quiet rather than guessed at.
     *
     * @param a the anchor to resolve; its target is the dotted package name.
     * @return present when the package tree exists, an absent assertion when it is certainly gone,
     *     otherwise unverifiable.
     */
    private Resolution resolvePackageRef(final Anchor a) {
        final String pkg = a.target();
        final String q = "package exists: " + pkg;
        if (index.packageExists(pkg)) {
            return Resolution.ok(Outcome.PRESENT, q);
        }
        if (!namespaceIndexed(pkg)) {
            return Resolution.finding(
                    Outcome.UNVERIFIABLE,
                    Lane.QUIET_LOG,
                    q,
                    "Package `" + pkg + "` is outside every indexed namespace (external); unverifiable.");
        }
        return Resolution.finding(
                Outcome.ABSENT,
                Lane.ASSERT,
                q,
                "No indexed source lives in package `" + pkg + "` (or any subpackage); the cited package is gone.");
    }

    /**
     * Resolves a fully-qualified type citation by package + simple name against the source index. The
     * primary (file-defining) type is the first uppercase segment; deeper nested-type segments are not
     * resolved (parse-only nesting is not checked — precision over coverage). A type found only in a
     * different package is a package move (present, but reported; a unique candidate drives a ready
     * FQN rewrite); a basename found nowhere asserts gone only when the cited namespace is indexed.
     *
     * @param a the anchor to resolve; its target is the FQN and its cited scope the primary type name.
     * @return the resolution reflecting the type's existence and location.
     */
    private Resolution resolveClassFqn(final Anchor a) {
        final String fqn = a.target();
        final String simpleName = a.citedScope();
        final String basename = simpleName + ".java";
        final String q = "type exists in cited package: " + fqn;

        if (a.historical()) {
            return resolveHistoricalSource(a, q, basename);
        }
        if (allowlist.isExternalName(simpleName)) {
            return Resolution.finding(
                    Outcome.UNVERIFIABLE,
                    Lane.QUIET_LOG,
                    q,
                    "`" + simpleName + "` is an allowlisted external/generated type.");
        }
        if (!SourceCandidates.forFqn(index, fqn, simpleName).isEmpty()) {
            return Resolution.ok(Outcome.PRESENT, q);
        }
        final List<String> candidates = index.pathsForBasename(basename);
        if (!candidates.isEmpty()) {
            return resolveElsewhere(
                    q,
                    candidates,
                    (resolvedPath, modules) -> "`" + simpleName
                            + "` is not in the cited package"
                            + (resolvedPath != null
                                    ? "; it now resolves at `" + resolvedPath + "` (package move)."
                                    : "; it now resolves in: " + String.join(", ", modules) + " (package move)."));
        }
        if (!namespaceIndexed(fqn)) {
            return Resolution.finding(
                    Outcome.UNVERIFIABLE,
                    Lane.QUIET_LOG,
                    q,
                    "Type `" + fqn + "` is outside every indexed namespace (external); unverifiable.");
        }
        return Resolution.finding(
                Outcome.ABSENT,
                Lane.ASSERT,
                q,
                "No file `" + basename + "` under any indexed module; cited type `" + fqn + "` is gone.");
    }

    /**
     * Whether a dotted name's namespace — its first two segments — contains any indexed package. This is
     * the guard that keeps package/FQN absence assertions inside the repo's own namespaces: a citation of
     * an external library can never assert.
     *
     * @param dotted the dotted package or FQN.
     * @return {@code true} when an indexed package lives under the name's two-segment namespace.
     */
    private boolean namespaceIndexed(final String dotted) {
        final String[] parts = dotted.split("\\.");
        final String namespace = parts.length >= 2 ? parts[0] + "." + parts[1] : dotted;
        return index.packageExists(namespace);
    }

    /**
     * Resolves a module-directory anchor by checking that the cited directory exists.
     *
     * @param a the anchor to resolve.
     * @return present when the directory exists, otherwise an absent assertion.
     */
    private Resolution resolveModuleDir(final Anchor a) {
        final String q = "module directory exists: " + a.target();
        if (index.dirExists(a.target())) {
            return Resolution.ok(Outcome.PRESENT, q);
        }
        return Resolution.finding(
                Outcome.ABSENT, Lane.ASSERT, q, "No directory `" + a.target() + "` in the repository.");
    }

    /**
     * Resolves a cross-document link anchor by checking that the linked file exists. A frontmatter
     * {@code topics:} tag names a slug rather than a hyperlink, so it may equally denote an architecture
     * interface document; real body links get no such fallback — their href must resolve as written.
     *
     * @param a the anchor to resolve.
     * @return present when the file exists, otherwise an absent assertion.
     */
    private Resolution resolveCrossDoc(final Anchor a) {
        final String q = "linked document exists: " + a.target();
        if (index.fileExists(a.target())) {
            return Resolution.ok(Outcome.PRESENT, q);
        }
        if (isTopicTag(a)) {
            final String interfacesTarget = a.target().replace("/architecture/topics/", "/architecture/interfaces/");
            if (index.fileExists(interfacesTarget)) {
                return Resolution.ok(Outcome.PRESENT, q);
            }
            return Resolution.finding(
                    Outcome.ABSENT,
                    Lane.ASSERT,
                    q,
                    "Linked document `" + a.target() + "` does not exist (no such topic; also checked `"
                            + interfacesTarget + "`).");
        }
        return Resolution.finding(
                Outcome.ABSENT, Lane.ASSERT, q, "Linked document `" + a.target() + "` does not exist.");
    }

    /**
     * Whether a cross-doc anchor came from a frontmatter {@code topics:} tag. Such anchors carry the bare
     * slug as their raw text and a target built as {@code <kb>/architecture/topics/<slug>.md}; a body
     * link's raw text is its URL (which always ends in {@code .md} or carries a fragment), so it can
     * never satisfy this shape.
     *
     * @param a the cross-doc anchor.
     * @return {@code true} when the anchor is a topics tag.
     */
    private static boolean isTopicTag(final Anchor a) {
        return a.target().endsWith("/architecture/topics/" + a.rawText() + ".md");
    }

    /**
     * Resolves a document-heading anchor by checking that a matching heading slug exists in the target
     * file. A missing target file is left to the cross-doc anchor and reported as unverifiable here.
     *
     * @param a the anchor to resolve.
     * @return present when the heading slug exists, unverifiable when the file is missing, otherwise an
     *     absent assertion.
     */
    private Resolution resolveHeading(final Anchor a) {
        final int hash = a.target().indexOf('#');
        final String path = hash >= 0 ? a.target().substring(0, hash) : a.target();
        final String fragment = a.citedScope() == null ? "" : a.citedScope();
        final String q = "heading `#" + fragment + "` exists in " + path;
        if (!index.fileExists(path)) {
            // The missing file is reported by the cross-doc link anchor; do not double-assert.
            return Resolution.finding(
                    Outcome.UNVERIFIABLE,
                    Lane.QUIET_LOG,
                    q,
                    "Target document `" + path + "` is missing; heading unverifiable.");
        }
        final Set<String> slugs = headings(path);
        final String want = slugify(fragment);
        if (slugs.contains(want) || slugs.contains(TRAILING_DEDUP.matcher(want).replaceAll(""))) {
            return Resolution.ok(Outcome.PRESENT, q);
        }
        return Resolution.finding(
                Outcome.ABSENT, Lane.ASSERT, q, "No heading anchor `#" + fragment + "` in `" + path + "`.");
    }

    /**
     * Resolves a catalog-ID anchor by locating its entry in the knowledge-base catalog for its prefix
     * (a per-ID file for {@code ADR}/{@code INV}/{@code RUL}/{@code SCN}/{@code HEU}, or a table row for
     * {@code SYM}/{@code TUN}).
     *
     * @param a the anchor to resolve.
     * @return present when the entry exists, unverifiable for an unknown prefix, otherwise an absent
     *     assertion.
     */
    private Resolution resolveCatalogId(final Anchor a) {
        final String id = a.target();
        final String prefix = id.length() >= 3 ? id.substring(0, 3) : id;
        final String q = "catalog entry exists: " + id;
        final boolean exists;
        final String where;
        if (CATALOG_DIR.containsKey(prefix)) {
            where = CATALOG_DIR.get(prefix);
            exists = catalogFileExists(where, id);
        } else if (prefix.equals("SYM")) {
            where = "symptoms.md";
            exists = catalogRowExists("symptoms.md", id);
        } else if (prefix.equals("TUN")) {
            where = "tunables.md";
            exists = catalogRowExists("tunables.md", id);
        } else {
            return Resolution.finding(
                    Outcome.UNVERIFIABLE, Lane.QUIET_LOG, q, "Unknown catalog prefix `" + prefix + "`.");
        }
        if (exists) {
            return Resolution.ok(Outcome.PRESENT, q);
        }
        return Resolution.finding(Outcome.ABSENT, Lane.ASSERT, q, "No catalog entry `" + id + "` in " + where + ".");
    }

    /**
     * Resolves a source-path anchor. External/generated targets are unverifiable; a file present at the
     * cited path may emit a line auto-fix; a basename found only elsewhere is a package/path move
     * (present, but reported); an allowlisted name is unverifiable; otherwise the source is asserted
     * gone.
     *
     * @param a the anchor to resolve.
     * @return the resolution reflecting the source's existence and location.
     */
    private Resolution resolveSourcePath(final Anchor a) {
        final String target = a.target();
        final String basename = RepoPaths.lastSegment(target);
        final String simpleName = RepoPaths.stripExtension(basename);
        final String q = "source exists: " + target;

        if (a.historical()) {
            return resolveHistoricalSource(a, q, basename);
        }
        final boolean abbreviated = target.contains("/.../");

        final Optional<Resolution> external = externalSource(a, abbreviated, q);
        if (external.isPresent()) {
            return external.get();
        }
        final Optional<Resolution> atCitedPath = sourceAtCitedPath(a, basename, simpleName, abbreviated, q);
        if (atCitedPath.isPresent()) {
            return atCitedPath.get();
        }
        final Optional<Resolution> moved = movedSource(a, basename, abbreviated, q);
        if (moved.isPresent()) {
            return moved.get();
        }
        if (allowlist.isExternalName(simpleName)) {
            return Resolution.finding(
                    Outcome.UNVERIFIABLE,
                    Lane.QUIET_LOG,
                    q,
                    "`" + simpleName + "` is an allowlisted external/generated type.");
        }
        return Resolution.finding(
                Outcome.ABSENT,
                Lane.ASSERT,
                q,
                "No file `" + basename + "` under any indexed module; cited source `" + target + "` is gone.");
    }

    /**
     * Resolves an allowlisted external/generated source target: present when it exists on disk, otherwise
     * unverifiable (it may be generated at build time). Empty when the target is not allowlisted.
     *
     * @param a           the anchor to resolve.
     * @param abbreviated whether the cited path uses the {@code /.../} ellipsis (no on-disk lookup).
     * @param q           the resolver question.
     * @return the external-source resolution, or empty when the target is not external.
     */
    private Optional<Resolution> externalSource(final Anchor a, final boolean abbreviated, final String q) {
        final String target = a.target();
        if (!allowlist.isExternalPath(target)) {
            return Optional.empty();
        }
        // Existence is still a filesystem fact even for a source the engine cannot parse: a cited
        // external file that is present resolves cleanly (Tier 0). A missing one stays unverifiable —
        // it may be generated at build time — but the quiet-log evidence flags the absence so a
        // curator skimming the log sees it.
        if (!abbreviated && index.fileExists(target)) {
            return Optional.of(Resolution.ok(Outcome.PRESENT, q));
        }
        final String note = abbreviated ? "" : " Not found on disk either (may be generated at build time).";
        return Optional.of(Resolution.finding(
                Outcome.UNVERIFIABLE,
                Lane.QUIET_LOG,
                q,
                "External/generated source (not indexed): `" + target + "`." + note));
    }

    /**
     * Resolves a source present at exactly the cited (non-abbreviated) path: a stale {@code Module:} label
     * asserts, a moved declaration line for the named type emits a line auto-fix, otherwise it resolves
     * cleanly. Empty when the cited path is abbreviated or the file is not there.
     *
     * @param a           the anchor to resolve.
     * @param basename    the cited file basename.
     * @param simpleName  the cited file basename without its extension (the primary type name).
     * @param abbreviated whether the cited path uses the {@code /.../} ellipsis.
     * @param q           the resolver question.
     * @return the at-cited-path resolution, or empty when the file is not at the cited path.
     */
    private Optional<Resolution> sourceAtCitedPath(
            final Anchor a, final String basename, final String simpleName, final boolean abbreviated, final String q) {
        final String target = a.target();
        if (abbreviated || !index.fileExists(target)) {
            return Optional.empty();
        }
        final String actualModule = RepoPaths.moduleOf(target);
        if (a.statedModule() != null
                && actualModule != null
                && !a.statedModule().equals(actualModule)) {
            return Optional.of(Resolution.finding(
                    Outcome.PRESENT,
                    Lane.ASSERT,
                    q,
                    "`" + basename + "` resolves in module `" + actualModule
                            + "`, but the stated label is `Module: " + a.statedModule() + "`; update the label to `"
                            + actualModule + "`."));
        }
        if (a.citedLine() != Anchor.NO_LINE) {
            final Optional<Resolution> migration = lineMigration(target, a.citedLine(), q);
            if (migration.isPresent()) {
                return migration;
            }
        }
        return Optional.of(Resolution.ok(Outcome.PRESENT, q));
    }

    /**
     * Resolves a source not at the cited path by looking the basename up across the index: an abbreviated
     * citation whose basename exists in the cited module is present; a basename found elsewhere is a
     * package/path move (present, but reported, with a rewrite when the target is unique). Empty when the
     * basename is not indexed anywhere.
     *
     * @param a           the anchor to resolve.
     * @param basename    the cited file basename.
     * @param abbreviated whether the cited path uses the {@code /.../} ellipsis.
     * @param q           the resolver question.
     * @return the moved-source resolution, or empty when the basename is not indexed.
     */
    private Optional<Resolution> movedSource(
            final Anchor a, final String basename, final boolean abbreviated, final String q) {
        // File not at the cited path (or abbreviated): look the basename up across indexed sources.
        final List<String> paths = index.pathsForBasename(basename);
        if (abbreviated
                && !SourceCandidates.inModule(index, basename, a.citedModule()).isEmpty()) {
            return Optional.of(Resolution.ok(Outcome.PRESENT, q));
        }
        if (paths.isEmpty()) {
            return Optional.empty();
        }
        // Exists, but not where cited — a package/path move, not "gone". Present, but reported.
        // With exactly one candidate, the resolved path also drives a path-rewrite auto-fix proposal.
        return Optional.of(resolveElsewhere(q, paths, (resolvedPath, modules) -> {
            String evidence = "`" + basename + "` is not at the cited location"
                    + (a.citedModule() != null ? " in module `" + a.citedModule() + "`" : "")
                    + (resolvedPath != null
                            ? "; it now resolves at `" + resolvedPath + "` (package/path move)."
                            : "; it now resolves in: " + String.join(", ", modules) + " (package/path move).");
            if (a.statedModule() != null && !modules.contains(a.statedModule())) {
                evidence += " Also update the stated `Module: " + a.statedModule() + "` label to "
                        + String.join(", ", modules) + ".";
            }
            return evidence;
        }));
    }

    /**
     * Resolves a bare source-file basename cited in prose (no path). Existence only: present if the
     * basename is indexed anywhere, allowlisted if generated/external, otherwise asserted gone. No location
     * was cited, so a "moved" location is never asserted.
     *
     * @param a the anchor to resolve.
     * @return the resolution reflecting whether the basename exists in the index.
     */
    private Resolution resolveSourceBasename(final Anchor a) {
        final String basename = a.target();
        final String simpleName = RepoPaths.stripExtension(basename);
        final String q = "source exists: " + basename;

        if (a.historical()) {
            return resolveHistoricalSource(a, q, basename);
        }
        if (allowlist.isExternalName(simpleName)) {
            return Resolution.finding(
                    Outcome.UNVERIFIABLE,
                    Lane.QUIET_LOG,
                    q,
                    "`" + simpleName + "` is an allowlisted external/generated type.");
        }
        final List<String> paths = index.pathsForBasename(basename);
        if (!paths.isEmpty()) {
            if (a.citedLine() != Anchor.NO_LINE && paths.size() == 1) {
                final Optional<Resolution> migration = lineMigration(paths.get(0), a.citedLine(), q);
                if (migration.isPresent()) {
                    return migration.get();
                }
            }
            return Resolution.ok(Outcome.PRESENT, q);
        }
        return Resolution.finding(
                Outcome.ABSENT,
                Lane.ASSERT,
                q,
                "No file `" + basename + "` under any indexed module; cited source `" + basename + "` is gone.");
    }

    /**
     * Resolves a source anchor the citing document marked {@code historical:} (expected-gone). The check
     * inverts: a gone source is the expected state (quiet, unverifiable as drift), while a source that
     * still exists contradicts the documented deletion and asserts.
     *
     * @param a        the historical anchor.
     * @param q        the question asked.
     * @param basename the cited file basename.
     * @return an assert when the source still exists, otherwise a quiet expected-gone resolution.
     */
    private Resolution resolveHistoricalSource(final Anchor a, final String q, final String basename) {
        final boolean abbreviated = a.target().contains("/.../");
        final boolean atCitedPath = !abbreviated && a.target().contains("/") && index.fileExists(a.target());
        final List<String> paths = index.pathsForBasename(basename);
        if (atCitedPath || !paths.isEmpty()) {
            final String where = atCitedPath ? a.target() : String.join(", ", paths);
            return Resolution.finding(
                    Outcome.PRESENT,
                    Lane.ASSERT,
                    q,
                    "`" + basename + "` is marked `historical:` (expected deleted) but exists at: `" + where + "`.");
        }
        return Resolution.finding(
                Outcome.UNVERIFIABLE,
                Lane.QUIET_LOG,
                q,
                "Expected-gone (historical): `" + a.target() + "` is cited as removed code.");
    }

    /**
     * Resolves a method-on-class anchor by parsing the resolved class file and checking that it declares
     * the cited method. When the class cannot be located or resolved, existence is unverifiable (the file
     * anchor covers that case).
     *
     * @param a the anchor to resolve.
     * @return present when the method is declared, unverifiable when the class is unresolved, otherwise
     *     an absent assertion.
     */
    private Resolution resolveMethodOnClass(final Anchor a) {
        final String className = a.citedScope();
        final String method = a.target();
        final String q = "class `" + className + "` declares method `" + method + "`";
        final CitedType ct = locateCitedType(a, className);
        if (!ct.pathResolved()) {
            final String scope = a.citedModule() == null ? "the index" : "module `" + a.citedModule() + "`";
            return Resolution.finding(
                    Outcome.UNVERIFIABLE,
                    Lane.QUIET_LOG,
                    q,
                    "Class `" + className + "` not found in " + scope
                            + "; method existence unverifiable (the file anchor covers this).");
        }
        if (!ct.typeResolved()) {
            return Resolution.finding(
                    Outcome.UNVERIFIABLE,
                    Lane.QUIET_LOG,
                    q,
                    "Could not resolve type `" + className + "` in `" + ct.path() + "`.");
        }
        if (ct.type().hasMethod(method)) {
            return Resolution.ok(Outcome.PRESENT, q);
        }
        return Resolution.finding(
                Outcome.ABSENT,
                Lane.ASSERT,
                q,
                "Class `" + className + "` in `" + ct.path() + "` declares no method `" + method + "`.");
    }

    /**
     * Resolves a {@code File.java#symbol} reference: the file's declared method, field, enum constant, or
     * (nested) type of the cited name. Absent asserts — a renamed or removed symbol. Unverifiable when the
     * file is not indexed (the file-existence anchor covers that case).
     *
     * @param a the anchor to resolve; its target is the symbol, {@code citedScope} the file's type name.
     * @return present when the symbol is declared, unverifiable when the file is unresolved, else an absent
     *     assertion.
     */
    private Resolution resolveSourceSymbol(final Anchor a) {
        final String className = a.citedScope();
        final String symbol = a.target();
        final String q = "source `" + className + ".java` declares `" + symbol + "`";
        final CitedType ct = locateCitedType(a, className);
        if (!ct.pathResolved()) {
            return Resolution.finding(
                    Outcome.UNVERIFIABLE,
                    Lane.QUIET_LOG,
                    q,
                    "Source `" + className + ".java` not indexed; symbol existence unverifiable (the file anchor "
                            + "covers this).");
        }
        if (declaresSymbol(index.parse(ct.path()), symbol)) {
            return Resolution.ok(Outcome.PRESENT, q);
        }
        return Resolution.finding(
                Outcome.ABSENT,
                Lane.ASSERT,
                q,
                "`" + className + ".java` declares no method, field, enum constant, or type `" + symbol
                        + "` (renamed or removed).");
    }

    /**
     * A body method-link ({@code Class::method}) with a cited line. Precision-first: it only ever
     * proposes an auto-fix (method resolves at a different line) or stays quiet — it never asserts,
     * because a method missing from the named file may be inherited or overloaded, which parse-only
     * analysis cannot rule out.
     */
    private Resolution resolveMethodRef(final Anchor a) {
        final String className = a.citedScope();
        final String method = a.target();
        final String q = "method `" + className + "#" + method + "` resolves at cited line";
        final CitedType ct = locateCitedType(a, className);
        if (!ct.pathResolved()) {
            return Resolution.finding(
                    Outcome.UNVERIFIABLE,
                    Lane.QUIET_LOG,
                    q,
                    "Class `" + className + "` not indexed; line unverifiable.");
        }
        if (!ct.typeResolved() || !ct.type().hasMethod(method)) {
            return Resolution.finding(
                    Outcome.UNVERIFIABLE,
                    Lane.QUIET_LOG,
                    q,
                    "Method `" + method + "` not declared in `" + ct.path() + "` (may be inherited/overloaded).");
        }
        final int line = ct.type().firstLine(method).orElse(-1);
        if (a.citedLine() != Anchor.NO_LINE && line > 0 && line != a.citedLine()) {
            return Resolution.autoFix(
                    q,
                    "Method `" + className + "#" + method + "` is declared at line " + line + " but is cited at line "
                            + a.citedLine() + ".",
                    line);
        }
        return Resolution.ok(Outcome.PRESENT, q);
    }

    /**
     * Tier-2 signature equality for a {@code Class.method(paramTypes)} citation. Asserts only when the
     * method name is declared on the resolved class but no overload's parameter types match the cited
     * ones — a certain signature change. If the class or method cannot be resolved, or the cited
     * parameters are not cleanly type-like, it stays quiet: precision over coverage.
     *
     * @param a the anchor to resolve; its target is {@code method(paramTypes)} and cited scope the class.
     * @return present when an overload matches, an assert when the signature changed, otherwise unverifiable.
     */
    private Resolution resolveMethodSignature(final Anchor a) {
        final String target = a.target();
        final int lp = target.indexOf('(');
        final String method = lp >= 0 ? target.substring(0, lp) : target;
        final String paramStr = lp >= 0 && target.endsWith(")") ? target.substring(lp + 1, target.length() - 1) : "";
        final String className = a.citedScope();
        final String q = "method `" + className + "#" + method + "(" + paramStr + ")` signature matches";

        final List<String> docParams = new ArrayList<>();
        boolean clean = true;
        for (final String piece : JavaParsing.splitParams(paramStr)) {
            final String type = JavaParsing.dropParamName(piece);
            if (type.isBlank()) {
                clean = false;
                break;
            }
            docParams.add(JavaParsing.canonicalType(type));
        }
        if (!clean) {
            return Resolution.finding(
                    Outcome.UNVERIFIABLE, Lane.QUIET_LOG, q, "Cited parameter list is not cleanly type-like.");
        }

        final CitedType ct = locateCitedType(a, className);
        if (!ct.pathResolved()) {
            return Resolution.finding(
                    Outcome.UNVERIFIABLE,
                    Lane.QUIET_LOG,
                    q,
                    "Class `" + className + "` not indexed; signature unverifiable.");
        }
        if (!ct.typeResolved()) {
            return Resolution.finding(
                    Outcome.UNVERIFIABLE, Lane.QUIET_LOG, q, "Could not resolve type `" + className + "`.");
        }
        final List<JavaParsing.MethodSig> overloads = ct.type().overloads(method);
        if (overloads.isEmpty()) {
            // Method name is absent entirely — could be inherited; leave existence to other anchors.
            return Resolution.finding(
                    Outcome.UNVERIFIABLE,
                    Lane.QUIET_LOG,
                    q,
                    "Method `" + method + "` not declared in `" + ct.path() + "` (may be inherited/overloaded).");
        }
        // Stored parameter types are already canonical (JavaParsing.canonicalType at build time), so the
        // documented and declared forms compare directly with no second normalization.
        final List<String> actualSignatures = new ArrayList<>();
        for (final JavaParsing.MethodSig sig : overloads) {
            if (sig.paramTypes().equals(docParams)) {
                return Resolution.ok(Outcome.PRESENT, q);
            }
            actualSignatures.add(method + "(" + String.join(", ", sig.paramTypes()) + ")");
        }
        return Resolution.finding(
                Outcome.ABSENT,
                Lane.ASSERT,
                q,
                "Documented signature `" + method + "(" + paramStr + ")` has no matching overload in `" + className
                        + "`; declared: " + String.join("; ", actualSignatures) + ".");
    }

    /**
     * A cited type located in the source index: its resolved path and parsed {@link TypeInfo}, either of
     * which may be absent (path not found, or the file parsed but declares no such type). This is the
     * shared preamble of the method resolvers; each caller turns an unresolved path or type into its own
     * short-circuit {@link Resolution} so the (differing) evidence text stays with the caller.
     *
     * @param path the repo-relative path the class resolved at, or {@code null} when unfound.
     * @param type the parsed type, or {@code null} when the path resolved but declares no such type.
     */
    private record CitedType(String path, TypeInfo type) {

        /**
         * Whether the cited class file was located in the index.
         *
         * @return {@code true} when a path resolved.
         */
        boolean pathResolved() {
            return path != null;
        }

        /**
         * Whether the located file declares the cited type.
         *
         * @return {@code true} when the type was parsed.
         */
        boolean typeResolved() {
            return type != null;
        }
    }

    /**
     * Locates a cited class in the index and parses it: the first indexed file of {@code <className>.java}
     * whose module matches the anchor's cited module (or any when none is cited), then the type it
     * declares. Shared by {@code resolveMethodOnClass}, {@code resolveMethodRef}, and
     * {@code resolveMethodSignature}.
     *
     * @param a         the citing anchor (supplies the cited-module scope).
     * @param className the simple class name to locate.
     * @return the located path and type; either component is absent when unresolved.
     */
    private CitedType locateCitedType(final Anchor a, final String className) {
        String resolvedPath = null;
        for (final String p : index.pathsForBasename(className + ".java")) {
            if (a.citedModule() == null || a.citedModule().equals(RepoPaths.moduleOf(p))) {
                resolvedPath = p;
                break;
            }
        }
        if (resolvedPath == null) {
            return new CitedType(null, null);
        }
        return new CitedType(resolvedPath, index.parse(resolvedPath).types().get(className));
    }

    /**
     * Reduces a list of candidate paths a cited source resolved elsewhere into a package/path-move
     * resolution: a unique candidate drives a ready path rewrite ({@link Resolution#moved}); several
     * candidates assert without one. The caller supplies the exact evidence text given the unique resolved
     * path (or {@code null} when ambiguous) and the sorted set of modules the candidates span. Shared by
     * {@code resolveClassFqn} and {@code resolveSourcePath}, which keep their distinct evidence wording.
     *
     * @param q          the question asked.
     * @param candidates the indexed paths the basename resolves at (non-empty).
     * @param evidenceFn builds the evidence from the unique resolved path (nullable) and the module set.
     * @return a moved resolution for a unique candidate, otherwise an asserting present resolution.
     */
    private Resolution resolveElsewhere(
            final String q, final List<String> candidates, final BiFunction<String, Set<String>, String> evidenceFn) {
        final Set<String> modules = new TreeSet<>();
        for (final String p : candidates) {
            final String m = RepoPaths.moduleOf(p);
            modules.add(m == null ? p : m);
        }
        final String resolvedPath = candidates.size() == 1 ? candidates.get(0) : null;
        final String evidence = evidenceFn.apply(resolvedPath, modules);
        return resolvedPath != null
                ? Resolution.moved(q, evidence, resolvedPath)
                : Resolution.finding(Outcome.PRESENT, Lane.ASSERT, q, evidence);
    }

    // ---- Helpers ----

    /**
     * Whether the parsed file declares the named symbol as a method, a field/enum-constant/record
     * component, or a (possibly nested) type — the existence test behind a {@code File.java#symbol}
     * reference.
     *
     * @param parsed the parsed source file.
     * @param symbol the symbol name.
     * @return {@code true} when the file declares the symbol.
     */
    private static boolean declaresSymbol(final ParsedFile parsed, final String symbol) {
        if (parsed.types().containsKey(symbol)) {
            return true;
        }
        for (final TypeInfo t : parsed.types().values()) {
            if (t.hasMethod(symbol) || t.hasMember(symbol)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A {@code File.java:NN} reference whose line NN is exactly a declaration start line migrates to
     * {@code File.java#symbol}: the volatile line is replaced by the durable symbol name. Empty when NN is
     * not a declaration (a body line or past end-of-file) — those are left for the follow-up suggestion
     * pass, not migrated here.
     *
     * @param repoRelPath the resolved source file.
     * @param citedLine   the cited 1-based line.
     * @param q           the resolver question.
     * @return a symbol-migration auto-fix when NN is a declaration line, otherwise empty.
     */
    private Optional<Resolution> lineMigration(final String repoRelPath, final int citedLine, final String q) {
        final String symbol = JavaParsing.symbolAtLine(index.parse(repoRelPath), citedLine);
        if (symbol == null) {
            return Optional.empty();
        }
        return Optional.of(Resolution.autoFixSymbol(
                q,
                "Line " + citedLine + " is the declaration of `" + symbol + "`; cite it as `#" + symbol
                        + "` (line numbers drift and are not maintained).",
                symbol));
    }

    /**
     * Whether a catalog directory contains a file named {@code <id>-*.md}.
     *
     * @param dir the knowledge-base-relative catalog subdirectory.
     * @param id  the catalog ID.
     * @return {@code true} if a matching entry file exists.
     * @throws UncheckedIOException if listing the catalog directory fails.
     */
    private boolean catalogFileExists(final String dir, final String id) {
        final Path catalog = kbRoot.resolve(dir);
        if (!Files.isDirectory(catalog)) {
            return false;
        }
        try (Stream<Path> list = Files.list(catalog)) {
            return list.map(p -> p.getFileName().toString()).anyMatch(n -> n.startsWith(id + "-") && n.endsWith(".md"));
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to list catalog " + catalog, e);
        }
    }

    /**
     * Whether a catalog table file contains a line naming the given ID as a whole token (see
     * {@link #idPresent}).
     *
     * @param file the knowledge-base-relative catalog file.
     * @param id   the catalog ID.
     * @return {@code true} if any line names the ID as a whole token.
     * @throws UncheckedIOException if reading the catalog file fails.
     */
    private boolean catalogRowExists(final String file, final String id) {
        final Path p = kbRoot.resolve(file);
        if (!Files.isRegularFile(p)) {
            return false;
        }
        try {
            for (final String line : Files.readAllLines(p)) {
                if (idPresent(line, id)) {
                    return true;
                }
            }
            return false;
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read " + p, e);
        }
    }

    /**
     * Whether a line names a catalog ID as a whole token — the ID occurs not flanked by a letter or digit
     * on either side, so {@code TUN-1} matches {@code | TUN-1 |} but not {@code TUN-10}.
     *
     * @param line the line to scan.
     * @param id   the catalog ID token.
     * @return {@code true} when the ID appears as a standalone token.
     */
    private static boolean idPresent(final String line, final String id) {
        int from = 0;
        int idx;
        while ((idx = line.indexOf(id, from)) >= 0) {
            final int end = idx + id.length();
            final boolean leftBoundary = idx == 0 || !Character.isLetterOrDigit(line.charAt(idx - 1));
            final boolean rightBoundary = end == line.length() || !Character.isLetterOrDigit(line.charAt(end));
            if (leftBoundary && rightBoundary) {
                return true;
            }
            from = idx + 1;
        }
        return false;
    }

    /**
     * The set of GitHub-style heading slugs in a markdown file, ignoring fenced code blocks. Results are
     * cached per run.
     *
     * @param repoRelMdPath the repo-relative markdown path.
     * @return the heading slugs declared in the file.
     * @throws UncheckedIOException if reading the file fails.
     */
    private Set<String> headings(final String repoRelMdPath) {
        return headingCache.computeIfAbsent(repoRelMdPath, path -> {
            final Set<String> slugs = new LinkedHashSet<>();
            try {
                boolean inFence = false;
                for (final String line : Files.readAllLines(repoRoot.resolve(path))) {
                    if (Markdown.isFenceDelimiter(line)) {
                        inFence = !inFence;
                        continue;
                    }
                    if (inFence) {
                        continue;
                    }
                    final Matcher m = HEADING.matcher(line);
                    if (m.matches()) {
                        slugs.add(slugify(m.group(2)));
                    }
                }
            } catch (final IOException e) {
                throw new UncheckedIOException("Failed to read " + path, e);
            }
            return slugs;
        });
    }

    /**
     * GitHub-style heading anchor slug: strip markdown, lowercase, spaces to hyphens, drop punctuation.
     *
     * @param headingText the raw heading text.
     * @return the anchor slug.
     */
    static String slugify(final String headingText) {
        String t = MD_LINK.matcher(headingText).replaceAll("$1");
        t = t.replace("`", "").toLowerCase(Locale.ROOT).strip();
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            final char c = t.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                sb.append(c);
            } else if (c == ' ') {
                sb.append('-');
            }
            // other punctuation dropped
        }
        return sb.toString();
    }
}
