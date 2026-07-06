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
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.hiero.consensus.kbfreshness.model.Anchor;
import org.hiero.consensus.kbfreshness.model.Lane;
import org.hiero.consensus.kbfreshness.model.Outcome;
import org.hiero.consensus.kbfreshness.resolve.JavaParsing.ParsedFile;
import org.hiero.consensus.kbfreshness.resolve.JavaParsing.TypeInfo;

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
            case SOURCE_PATH -> resolveSourcePath(a);
            case METHOD_ON_CLASS -> resolveMethodOnClass(a);
            case METHOD_REF -> resolveMethodRef(a);
            case METHOD_SIGNATURE -> resolveMethodSignature(a);
            // Kinds resolved outside the per-anchor pipeline (INTERFACE_METHOD) or not extracted in
            // this version resolve as unverifiable if reached here.
            case CLASS, ENUM_CONSTANT, CONFIG_KEY, INTERFACE_METHOD ->
                Resolution.finding(
                        Outcome.UNVERIFIABLE, Lane.QUIET_LOG, "symbol check", "not implemented in this version");
        };
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
     * Resolves a cross-document link anchor by checking that the linked file exists.
     *
     * @param a the anchor to resolve.
     * @return present when the file exists, otherwise an absent assertion.
     */
    private Resolution resolveCrossDoc(final Anchor a) {
        final String q = "linked document exists: " + a.target();
        if (index.fileExists(a.target())) {
            return Resolution.ok(Outcome.PRESENT, q);
        }
        return Resolution.finding(
                Outcome.ABSENT, Lane.ASSERT, q, "Linked document `" + a.target() + "` does not exist.");
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
        final String basename = lastSegment(target);
        final String simpleName = stripExt(basename);
        final String q = "source exists: " + target;

        if (allowlist.isExternalPath(target)) {
            return Resolution.finding(
                    Outcome.UNVERIFIABLE,
                    Lane.QUIET_LOG,
                    q,
                    "External/generated source (not indexed): `" + target + "`.");
        }

        final boolean abbreviated = target.contains("/.../");
        if (!abbreviated && index.fileExists(target)) {
            if (a.citedLine() != Anchor.NO_LINE) {
                final int declLine = primaryTypeDeclLine(target, simpleName);
                if (declLine > 0 && declLine != a.citedLine()) {
                    return Resolution.autoFix(
                            q,
                            "Type `" + simpleName + "` is declared at line " + declLine + " but is cited at line "
                                    + a.citedLine() + ".",
                            declLine);
                }
            }
            return Resolution.ok(Outcome.PRESENT, q);
        }

        // File not at the cited path (or abbreviated): look the basename up across indexed sources.
        final List<String> paths = index.pathsForBasename(basename);
        final List<String> inCitedModule = new ArrayList<>();
        for (final String p : paths) {
            if (a.citedModule() != null && a.citedModule().equals(moduleOfPath(p))) {
                inCitedModule.add(p);
            }
        }
        if (abbreviated && !inCitedModule.isEmpty()) {
            return Resolution.ok(Outcome.PRESENT, q);
        }
        if (!paths.isEmpty()) {
            // Exists, but not where cited — a package/path move, not "gone". Present, but reported.
            final Set<String> modules = new TreeSet<>();
            for (final String p : paths) {
                final String m = moduleOfPath(p);
                modules.add(m == null ? p : m);
            }
            return Resolution.finding(
                    Outcome.PRESENT,
                    Lane.ASSERT,
                    q,
                    "`" + basename + "` is not at the cited location"
                            + (a.citedModule() != null ? " in module `" + a.citedModule() + "`" : "")
                            + "; it now resolves in: " + String.join(", ", modules) + " (package/path move).");
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
        final String classFile = className + ".java";
        String resolvedPath = null;
        for (final String p : index.pathsForBasename(classFile)) {
            if (a.citedModule() == null || a.citedModule().equals(moduleOfPath(p))) {
                resolvedPath = p;
                break;
            }
        }
        if (resolvedPath == null) {
            return Resolution.finding(
                    Outcome.UNVERIFIABLE,
                    Lane.QUIET_LOG,
                    q,
                    "Class `" + className + "` not found in module `" + a.citedModule()
                            + "`; method existence unverifiable (the file anchor covers this).");
        }
        final ParsedFile parsed = index.parse(resolvedPath);
        final TypeInfo type = parsed.types().get(className);
        if (type == null) {
            return Resolution.finding(
                    Outcome.UNVERIFIABLE,
                    Lane.QUIET_LOG,
                    q,
                    "Could not resolve type `" + className + "` in `" + resolvedPath + "`.");
        }
        if (type.hasMethod(method)) {
            return Resolution.ok(Outcome.PRESENT, q);
        }
        return Resolution.finding(
                Outcome.ABSENT,
                Lane.ASSERT,
                q,
                "Class `" + className + "` in `" + resolvedPath + "` declares no method `" + method + "`.");
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
        String resolvedPath = null;
        for (final String p : index.pathsForBasename(className + ".java")) {
            if (a.citedModule() == null || a.citedModule().equals(moduleOfPath(p))) {
                resolvedPath = p;
                break;
            }
        }
        if (resolvedPath == null) {
            return Resolution.finding(
                    Outcome.UNVERIFIABLE,
                    Lane.QUIET_LOG,
                    q,
                    "Class `" + className + "` not indexed; line unverifiable.");
        }
        final TypeInfo type = index.parse(resolvedPath).types().get(className);
        if (type == null || !type.hasMethod(method)) {
            return Resolution.finding(
                    Outcome.UNVERIFIABLE,
                    Lane.QUIET_LOG,
                    q,
                    "Method `" + method + "` not declared in `" + resolvedPath + "` (may be inherited/overloaded).");
        }
        final int line = type.firstLine(method).orElse(-1);
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
        for (final String piece : splitParams(paramStr)) {
            final String type = dropParamName(piece);
            if (type.isBlank()) {
                clean = false;
                break;
            }
            docParams.add(normalizeType(type));
        }
        if (!clean) {
            return Resolution.finding(
                    Outcome.UNVERIFIABLE, Lane.QUIET_LOG, q, "Cited parameter list is not cleanly type-like.");
        }

        String resolvedPath = null;
        for (final String p : index.pathsForBasename(className + ".java")) {
            if (a.citedModule() == null || a.citedModule().equals(moduleOfPath(p))) {
                resolvedPath = p;
                break;
            }
        }
        if (resolvedPath == null) {
            return Resolution.finding(
                    Outcome.UNVERIFIABLE,
                    Lane.QUIET_LOG,
                    q,
                    "Class `" + className + "` not indexed; signature unverifiable.");
        }
        final TypeInfo type = index.parse(resolvedPath).types().get(className);
        if (type == null) {
            return Resolution.finding(
                    Outcome.UNVERIFIABLE, Lane.QUIET_LOG, q, "Could not resolve type `" + className + "`.");
        }
        final List<JavaParsing.MethodSig> overloads = type.overloads(method);
        if (overloads.isEmpty()) {
            // Method name is absent entirely — could be inherited; leave existence to other anchors.
            return Resolution.finding(
                    Outcome.UNVERIFIABLE,
                    Lane.QUIET_LOG,
                    q,
                    "Method `" + method + "` not declared in `" + resolvedPath + "` (may be inherited/overloaded).");
        }
        final List<String> actualSignatures = new ArrayList<>();
        for (final JavaParsing.MethodSig sig : overloads) {
            final List<String> actual =
                    sig.paramTypes().stream().map(AnchorResolver::normalizeType).toList();
            if (actual.equals(docParams)) {
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
     * Splits a parameter list on top-level commas, ignoring commas nested in generics, arrays, or
     * parentheses.
     *
     * @param paramStr the raw parameter list (without the surrounding parentheses).
     * @return the trimmed parameter pieces, or an empty list when there are none.
     */
    static List<String> splitParams(final String paramStr) {
        final List<String> parts = new ArrayList<>();
        if (paramStr.isBlank()) {
            return parts;
        }
        int depth = 0;
        final StringBuilder cur = new StringBuilder();
        for (int i = 0; i < paramStr.length(); i++) {
            final char c = paramStr.charAt(i);
            if (c == '<' || c == '(' || c == '[') {
                depth++;
            } else if (c == '>' || c == ')' || c == ']') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                parts.add(cur.toString().strip());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) {
            parts.add(cur.toString().strip());
        }
        return parts;
    }

    /**
     * Drops a trailing parameter name from a parameter piece, keeping just the type. A piece with a
     * top-level space (e.g. {@code List<Foo> bar}) is treated as {@code type name}; a piece without one
     * (e.g. {@code byte[]}) is the type itself.
     *
     * @param piece one parameter piece.
     * @return the parameter's type portion.
     */
    static String dropParamName(final String piece) {
        int depth = 0;
        int lastSpace = -1;
        for (int i = 0; i < piece.length(); i++) {
            final char c = piece.charAt(i);
            if (c == '<' || c == '(' || c == '[') {
                depth++;
            } else if (c == '>' || c == ')' || c == ']') {
                depth--;
            } else if (c == ' ' && depth == 0) {
                lastSpace = i;
            }
        }
        return (lastSpace >= 0 ? piece.substring(0, lastSpace) : piece).strip();
    }

    /**
     * Normalizes a type for as-written comparison: removes whitespace and strips package qualifiers
     * from every identifier (e.g. {@code java.util.List<com.x.Foo>} to {@code List<Foo>}), so a doc's
     * simple names compare equal to source's possibly-qualified ones.
     *
     * @param type the type string.
     * @return the normalized type.
     */
    static String normalizeType(final String type) {
        final String noSpace = type.replaceAll("\\s+", "");
        return noSpace.replaceAll("(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)+([A-Za-z_$][A-Za-z0-9_$]*)", "$1");
    }

    // ---- Helpers ----

    /**
     * The declaration line of the named type in the given source file.
     *
     * @param repoRelPath the repo-relative source path.
     * @param simpleName  the simple type name to look up.
     * @return the 1-based declaration line, or {@code -1} if the type is not declared in the file.
     */
    private int primaryTypeDeclLine(final String repoRelPath, final String simpleName) {
        final ParsedFile parsed = index.parse(repoRelPath);
        final TypeInfo type = parsed.types().get(simpleName);
        return type == null ? -1 : type.declLine();
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
     * Whether a catalog table file contains a line mentioning the given ID.
     *
     * @param file the knowledge-base-relative catalog file.
     * @param id   the catalog ID.
     * @return {@code true} if any line contains the ID.
     * @throws UncheckedIOException if reading the catalog file fails.
     */
    private boolean catalogRowExists(final String file, final String id) {
        final Path p = kbRoot.resolve(file);
        if (!Files.isRegularFile(p)) {
            return false;
        }
        try {
            for (final String line : Files.readAllLines(p)) {
                if (line.contains(id)) {
                    return true;
                }
            }
            return false;
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read " + p, e);
        }
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
                    if (line.strip().startsWith("```") || line.strip().startsWith("~~~")) {
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

    /**
     * The module directory of a repo-relative path (the segment preceding {@code src}).
     *
     * @param repoRelPath the repo-relative path.
     * @return the module directory name, or {@code null} if the path has no {@code src} segment.
     */
    static String moduleOfPath(final String repoRelPath) {
        final String[] parts = repoRelPath.split("/");
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].equals("src")) {
                return parts[i - 1];
            }
        }
        return null;
    }

    /**
     * The last {@code /}-separated segment of a path.
     *
     * @param path the path.
     * @return the final segment, or the whole path if it contains no slash.
     */
    private static String lastSegment(final String path) {
        final int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /**
     * A filename with its first extension (and anything after) removed.
     *
     * @param name the filename.
     * @return the name up to the first dot, or the whole name if it has no leading-dot extension.
     */
    private static String stripExt(final String name) {
        final int dot = name.indexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
