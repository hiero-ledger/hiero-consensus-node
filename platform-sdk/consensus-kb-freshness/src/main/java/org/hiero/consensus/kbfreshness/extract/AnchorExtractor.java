// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.extract;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hiero.consensus.kbfreshness.model.Anchor;
import org.hiero.consensus.kbfreshness.model.AnchorKind;

/**
 * Extracts code citations ("anchors") from a {@link KbDocument}. Precision-first: only citations that
 * carry enough context to be verified as a fact are emitted. Frontmatter yields {@code components:}
 * source paths, {@code verification:} method-on-class, and {@code related:}/{@code topics:} references;
 * the body yields markdown links (doc/source/module), abbreviated {@code module/.../File.java} paths,
 * and bare catalog IDs. Fenced code blocks are skipped. Bare prose symbol names are intentionally not
 * asserted on in this version.
 */
public final class AnchorExtractor {

    /** Matches a markdown link {@code [text](url)}, capturing the link text and the URL. */
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]*)\\]\\(([^)\\s]+)\\)");

    /** Matches a bare catalog ID ({@code ADR}/{@code INV}/{@code RUL}/{@code SCN}/{@code HEU}/{@code SYM}/{@code TUN} plus three digits) on word boundaries. */
    private static final Pattern CATALOG_ID = Pattern.compile("\\b(ADR|INV|RUL|SCN|HEU|SYM|TUN)-(\\d{3})\\b");

    /** Matches an abbreviated {@code module/.../File.java} (or {@code .proto}) path, capturing the module and file name. */
    private static final Pattern ABBREV_PATH = Pattern.compile(
            "([A-Za-z0-9][A-Za-z0-9._-]*)/\\.\\.\\./(?:[A-Za-z0-9/._-]*/)?([A-Za-z0-9_$]+\\.(?:java|proto))");

    // "path — `method`" or "path -- `method`": em dash (U+2014) or double hyphen separator.
    /** Matches a {@code verification:} value {@code path — `method`}, capturing the path and the method name. */
    private static final Pattern VERIFICATION = Pattern.compile("^(\\S+)\\s*(?:\\u2014|--)\\s*`([A-Za-z0-9_$]+)`");

    /** Matches a {@code path:line} suffix on a source file ({@code .java}/{@code .proto}/{@code .kt}), capturing the path and line number. */
    private static final Pattern SOURCE_FILE_LINE = Pattern.compile("^(.*\\.(?:java|proto|kt)):(\\d+)$");

    // Link text naming a method: `Class::method`, `Class.method`, optionally with `()`.
    /** Matches link text naming a method ({@code Class::method}, {@code Class.method}, optionally with {@code ()}), capturing the class and method names. */
    private static final Pattern METHOD_LINK =
            Pattern.compile("^([A-Z][A-Za-z0-9_]*)(?:::|\\.)([a-z][A-Za-z0-9_]*)(?:\\(\\))?$");

    /** Matches link text naming a method WITH a non-empty parameter list ({@code Class.method(Params)}), capturing class, method, and the parameter list. */
    private static final Pattern METHOD_SIG_LINK =
            Pattern.compile("^([A-Z][A-Za-z0-9_]*)(?:::|\\.)([a-z][A-Za-z0-9_]*)\\((.+)\\)$");

    /** Repository root (absolute, normalized), used to resolve and namespace cited paths. */
    private final Path repoRoot;

    /** KB root as a repo-relative, forward-slashed path, used to build topic-doc targets. */
    private final String kbRootRel;

    /**
     * Creates an extractor bound to a repository and its KB root.
     *
     * @param repoRoot the repository root.
     * @param kbRoot   the consensus-layer KB root.
     */
    public AnchorExtractor(final Path repoRoot, final Path kbRoot) {
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        this.kbRootRel = this.repoRoot
                .relativize(kbRoot.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    /**
     * Extracts all verifiable anchors from a document's frontmatter and body.
     *
     * @param doc the scanned KB document.
     * @return the extracted anchors, in document order.
     */
    public List<Anchor> extract(final KbDocument doc) {
        final List<Anchor> anchors = new ArrayList<>();
        extractFrontmatter(doc, anchors);
        extractBody(doc, anchors);
        return anchors;
    }

    // ---- Frontmatter ----

    /**
     * Emits anchors from the frontmatter: {@code components:} source paths, the {@code verification:}
     * method-on-class, {@code related:} catalog IDs, and {@code topics:} topic-doc references.
     *
     * @param doc the scanned KB document.
     * @param out the list to append discovered anchors to.
     */
    private void extractFrontmatter(final KbDocument doc, final List<Anchor> out) {
        final Frontmatter fm = doc.frontmatter();

        // components: block list of platform-sdk-relative source paths.
        final List<String> components = fm.list("components");
        if (!components.isEmpty()) {
            final int base = fm.lineOf("components");
            for (final String c : components) {
                final String repoRel = "platform-sdk/" + normalizeSlashes(c);
                out.add(new Anchor(
                        AnchorKind.SOURCE_PATH, repoRel, moduleOfPath(repoRel), null, base, Anchor.NO_LINE, c));
            }
        }

        // verification: "path — `method`".
        final String verification = fm.scalar("verification");
        if (verification != null) {
            final int line = fm.lineOf("verification");
            final Matcher m = VERIFICATION.matcher(verification.strip());
            if (m.find()) {
                final String path = "platform-sdk/" + normalizeSlashes(m.group(1));
                final String method = m.group(2);
                final String module = moduleOfPath(path);
                final String className = classNameOfPath(path);
                out.add(new Anchor(AnchorKind.SOURCE_PATH, path, module, null, line, Anchor.NO_LINE, m.group(1)));
                out.add(new Anchor(
                        AnchorKind.METHOD_ON_CLASS,
                        method,
                        module,
                        className,
                        line,
                        Anchor.NO_LINE,
                        verification.strip()));
            }
        }

        // related: nested lists of catalog IDs.
        for (final var child : fm.nestedMap("related").entrySet()) {
            if (child.getValue() instanceof List<?> ids) {
                final int line = fm.lineOf("related");
                for (final Object id : ids) {
                    final String s = String.valueOf(id).strip();
                    if (CATALOG_ID.matcher(s).matches()) {
                        out.add(new Anchor(AnchorKind.CATALOG_ID, s, null, null, line, Anchor.NO_LINE, s));
                    }
                }
            }
        }

        // topics: flow list of topic slugs → topic doc existence.
        final int topicsLine = fm.lineOf("topics");
        for (final String slug : fm.list("topics")) {
            final String target = kbRootRel + "/architecture/topics/" + slug + ".md";
            out.add(new Anchor(AnchorKind.CROSS_DOC_LINK, target, null, null, topicsLine, Anchor.NO_LINE, slug));
        }
    }

    // ---- Body ----

    /**
     * Emits anchors from the document body outside fenced code blocks: markdown links, abbreviated
     * {@code module/.../File.java} paths, and bare catalog IDs (excluding the entry's own ID).
     *
     * @param doc the scanned KB document.
     * @param out the list to append discovered anchors to.
     */
    private void extractBody(final KbDocument doc, final List<Anchor> out) {
        final List<String> lines = doc.lines();
        final String ownKey = doc.entry().key();
        final int bodyStart = doc.frontmatter().bodyLine();
        final String docDir = parentDir(doc.entry().relativePath());
        boolean inFence = false;

        for (int idx = bodyStart - 1; idx < lines.size(); idx++) {
            final String line = lines.get(idx);
            final int fileLine = idx + 1;
            if (line.strip().startsWith("```") || line.strip().startsWith("~~~")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                continue;
            }

            // Markdown links.
            final Matcher link = MD_LINK.matcher(line);
            while (link.find()) {
                extractLink(link.group(1), link.group(2), docDir, fileLine, out);
            }

            // Abbreviated module/.../File.java paths.
            final Matcher abbr = ABBREV_PATH.matcher(line);
            while (abbr.find()) {
                final String module = abbr.group(1);
                final String fileName = abbr.group(2);
                out.add(new Anchor(
                        AnchorKind.SOURCE_PATH,
                        module + "/.../" + fileName,
                        module,
                        null,
                        fileLine,
                        Anchor.NO_LINE,
                        abbr.group()));
            }

            // Bare catalog IDs (skip the entry's own ID).
            final Matcher id = CATALOG_ID.matcher(line);
            while (id.find()) {
                final String catId = id.group();
                if (!catId.equals(ownKey)) {
                    out.add(new Anchor(AnchorKind.CATALOG_ID, catId, null, null, fileLine, Anchor.NO_LINE, catId));
                }
            }
        }
    }

    /**
     * Emits anchors for a single markdown link, dispatching by target kind: cross-doc {@code .md}
     * links (with optional heading fragment), source files (with an optional named-method reference),
     * and directory links. External ({@code http}/{@code https}/{@code mailto}) and pure in-document
     * fragment links are ignored.
     *
     * @param linkText the link's display text.
     * @param url      the link's target URL.
     * @param docDir   the repo-relative directory of the containing document, for resolving relatives.
     * @param fileLine the 1-based line of the link in the document.
     * @param out      the list to append discovered anchors to.
     */
    private void extractLink(
            final String linkText, final String url, final String docDir, final int fileLine, final List<Anchor> out) {
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:")) {
            return;
        }
        String pathPart = url;
        String fragment = null;
        final int hash = pathPart.indexOf('#');
        if (hash >= 0) {
            fragment = pathPart.substring(hash + 1);
            pathPart = pathPart.substring(0, hash);
        }
        int citedLine = Anchor.NO_LINE;
        final Matcher fl = SOURCE_FILE_LINE.matcher(pathPart);
        if (fl.matches()) {
            pathPart = fl.group(1);
            citedLine = Integer.parseInt(fl.group(2));
        }
        if (pathPart.isEmpty()) {
            // Pure in-document fragment link (e.g. "#heading"); out of scope for drift.
            return;
        }

        final String lower = pathPart.toLowerCase();
        if (lower.endsWith(".md")) {
            final String repoRel = resolveRelative(docDir, pathPart);
            out.add(new Anchor(AnchorKind.CROSS_DOC_LINK, repoRel, null, null, fileLine, Anchor.NO_LINE, url));
            if (fragment != null && !fragment.isEmpty()) {
                out.add(new Anchor(
                        AnchorKind.DOC_HEADING,
                        repoRel + "#" + fragment,
                        null,
                        fragment,
                        fileLine,
                        Anchor.NO_LINE,
                        url));
            }
        } else if (lower.endsWith(".java") || lower.endsWith(".proto") || lower.endsWith(".kt")) {
            final String repoRel = resolveRelative(docDir, pathPart);
            final String module = moduleOfPath(repoRel);
            // File-existence check carries no line: a bare `File.java:NN` link is ambiguous (the KB
            // uses it for members too), so line-move detection is done only for named-method links.
            out.add(new Anchor(AnchorKind.SOURCE_PATH, repoRel, module, null, fileLine, Anchor.NO_LINE, url));
            if (lower.endsWith(".java")) {
                final String method = methodFromLinkText(linkText);
                if (method != null && citedLine != Anchor.NO_LINE) {
                    out.add(new Anchor(
                            AnchorKind.METHOD_REF,
                            method,
                            module,
                            classNameOfPath(pathPart),
                            fileLine,
                            citedLine,
                            url));
                }
                final String signature = signatureFromLinkText(linkText);
                if (signature != null) {
                    out.add(new Anchor(
                            AnchorKind.METHOD_SIGNATURE,
                            signature,
                            module,
                            classNameOfPath(pathPart),
                            fileLine,
                            Anchor.NO_LINE,
                            url));
                }
            }
        } else if (isDirectoryLink(pathPart)) {
            final String repoRel = resolveRelative(docDir, pathPart);
            out.add(new Anchor(
                    AnchorKind.MODULE_DIR, repoRel, lastSegment(repoRel), null, fileLine, Anchor.NO_LINE, url));
        }
    }

    // ---- Path helpers ----

    /**
     * If a markdown link's text names a method ({@code Class::method}, {@code Class.method}, or with
     * {@code ()}), returns the method name; otherwise {@code null}. This is what makes a line reference
     * auto-fixable — a named method whose line can be resolved — as opposed to an ambiguous bare
     * {@code File.java:NN} citation.
     */
    static String methodFromLinkText(final String linkText) {
        if (linkText == null) {
            return null;
        }
        final Matcher m = METHOD_LINK.matcher(linkText.replace("`", "").strip());
        return m.matches() ? m.group(2) : null;
    }

    /**
     * If a markdown link's text names a method with a non-empty parameter list
     * ({@code Class.method(ParamTypes)}), returns the normalized signature target
     * {@code method(paramTypes)} (whitespace removed); otherwise {@code null}. Drives Tier-2 signature
     * equality.
     *
     * @param linkText the link's display text.
     * @return the {@code method(paramTypes)} target, or {@code null} when the text is not a signature.
     */
    static String signatureFromLinkText(final String linkText) {
        if (linkText == null) {
            return null;
        }
        final Matcher m = METHOD_SIG_LINK.matcher(linkText.replace("`", "").strip());
        if (!m.matches()) {
            return null;
        }
        return m.group(2) + "(" + m.group(3).replaceAll("\\s+", " ").strip() + ")";
    }

    /**
     * Derives the module name from a repo-relative path: the segment immediately preceding the first
     * {@code src} segment.
     *
     * @param repoRelPath the repo-relative, forward-slashed path.
     * @return the module name, or {@code null} if the path has no {@code src} segment (or {@code src}
     *     is the first segment).
     */
    static String moduleOfPath(final String repoRelPath) {
        final String[] parts = repoRelPath.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].equals("src")) {
                return i > 0 ? parts[i - 1] : null;
            }
        }
        return null;
    }

    /**
     * Derives the class name from a path: the file name's last segment up to its first dot.
     *
     * @param path the path (repo-relative or otherwise).
     * @return the class name (extension stripped).
     */
    static String classNameOfPath(final String path) {
        final String name = lastSegment(path);
        final int dot = name.indexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * Heuristically decides whether a link target names a directory: it contains a slash and its last
     * segment is non-empty and extension-less.
     *
     * @param pathPart the link's path portion.
     * @return {@code true} if the target looks like a directory link.
     */
    private static boolean isDirectoryLink(final String pathPart) {
        final String last = lastSegment(pathPart);
        return pathPart.contains("/") && !last.isEmpty() && !last.contains(".");
    }

    /**
     * Returns the last path segment, ignoring a single trailing slash.
     *
     * @param path the path.
     * @return the final segment, or the whole (de-slashed) path if it has no slash.
     */
    private static String lastSegment(final String path) {
        final String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        final int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    /**
     * Returns the parent directory portion of a repo-relative path.
     *
     * @param repoRelPath the repo-relative path.
     * @return the parent directory, or an empty string if the path has no directory component.
     */
    private static String parentDir(final String repoRelPath) {
        final int slash = repoRelPath.replace('\\', '/').lastIndexOf('/');
        return slash >= 0 ? repoRelPath.substring(0, slash) : "";
    }

    /**
     * Resolves {@code rel} against {@code baseDir} (both repo-relative), normalizing {@code ..}.
     *
     * @param baseDir the repo-relative base directory.
     * @param rel     the path to resolve against the base.
     * @return the normalized, forward-slashed repo-relative path.
     */
    static String resolveRelative(final String baseDir, final String rel) {
        final Path base = baseDir.isEmpty() ? Path.of("") : Path.of(baseDir);
        final String normalized = base.resolve(rel).normalize().toString().replace('\\', '/');
        return normalized;
    }

    /**
     * Trims a path and converts backslashes to forward slashes.
     *
     * @param p the path.
     * @return the trimmed, forward-slashed path.
     */
    private static String normalizeSlashes(final String p) {
        return p.strip().replace('\\', '/');
    }
}
