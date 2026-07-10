// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.extract;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hiero.consensus.kbfreshness.model.Anchor;
import org.hiero.consensus.kbfreshness.model.AnchorKind;

/**
 * Extracts code citations ("anchors") from a {@link KbDocument}. Precision-first: only citations that
 * carry enough context to be verified as a fact are emitted. Frontmatter yields {@code components:}
 * source paths, {@code verification:} method-on-class, and {@code related:}/{@code topics:} references;
 * the body yields markdown links (doc/source/module), abbreviated {@code module/.../File.java} paths,
 * and bare catalog IDs. Fenced code blocks and HTML comments are skipped — commented-out text is not a
 * claim. Bare prose symbol names are intentionally not asserted on in this version.
 */
public final class AnchorExtractor {

    /** Matches a markdown link {@code [text](url)}, capturing the link text and the URL. */
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]*)\\]\\(([^)\\s]+)\\)");

    /** Matches a bare catalog ID ({@code ADR}/{@code INV}/{@code RUL}/{@code SCN}/{@code HEU}/{@code SYM}/{@code TUN} plus three digits) on word boundaries. */
    private static final Pattern CATALOG_ID = Pattern.compile("\\b(ADR|INV|RUL|SCN|HEU|SYM|TUN)-(\\d{3})\\b");

    /** Matches an abbreviated {@code module/.../File.java} (or {@code .proto}) path, capturing the module and file name. */
    private static final Pattern ABBREV_PATH = Pattern.compile(
            "([A-Za-z0-9][A-Za-z0-9._-]*)/\\.\\.\\./(?:[A-Za-z0-9/._-]*/)?([A-Za-z0-9_$]+\\.(?:java|proto))");

    /** Matches an inline code span, capturing its content (no embedded backtick or newline). */
    private static final Pattern CODE_SPAN = Pattern.compile("`([^`\\n]+)`");

    /** Matches a full repo-relative source path (optionally with a non-asserting {@code :NN}/{@code :NN-MM} suffix). */
    private static final Pattern FULL_SOURCE_PATH =
            Pattern.compile("^(platform-sdk/[A-Za-z0-9/._$-]+\\.(?:java|proto|kt))(?::\\d+(?:-\\d+)?)?$");

    /**
     * Matches a module-relative source path ({@code <module>/src/<set>/…/File.java}, the same convention
     * frontmatter {@code components:} uses), optionally with a non-asserting {@code :NN}/{@code :NN-MM}
     * suffix. The extractor prefixes {@code platform-sdk/}, mirroring the frontmatter normalization.
     */
    private static final Pattern MODULE_RELATIVE_SOURCE_PATH = Pattern.compile(
            "^([A-Za-z0-9][A-Za-z0-9._-]*/src/(?:main|test|testFixtures)/[A-Za-z0-9/._$-]+\\.(?:java|proto|kt))(?::\\d+(?:-\\d+)?)?$");

    /** Matches a bare source-file basename (optional leading {@code ...}/{@code .../} ellipsis, non-asserting line suffix). */
    private static final Pattern BARE_SOURCE_FILE =
            Pattern.compile("^(?:\\.{2,3}/?)?([A-Za-z0-9_$]+\\.(?:java|proto|kt))(?::\\d+(?:-\\d+)?)?$");

    /** Matches a prose {@code Module: `name`} label, capturing the stated module name. */
    private static final Pattern MODULE_LABEL = Pattern.compile("Module:\\s*`([A-Za-z0-9._-]+)`");

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
     * Extracts all verifiable anchors from a document's frontmatter and body. Source anchors matching
     * the document's {@code historical:} frontmatter list are marked expected-gone.
     *
     * @param doc the scanned KB document.
     * @return the extracted anchors, in document order.
     */
    public List<Anchor> extract(final KbDocument doc) {
        final List<Anchor> anchors = new ArrayList<>();
        extractFrontmatter(doc, anchors);
        extractBody(doc, anchors);
        return markHistorical(doc.frontmatter(), anchors);
    }

    /**
     * Marks source anchors named by the document's {@code historical:} frontmatter list (basenames or
     * paths of deliberately deleted code cited as history) as expected-gone. A listed basename matches
     * every source anchor citing that file; a listed path matches its exact target.
     *
     * @param fm      the document's frontmatter.
     * @param anchors the extracted anchors.
     * @return the anchors, with matching source anchors marked historical.
     */
    private static List<Anchor> markHistorical(final Frontmatter fm, final List<Anchor> anchors) {
        final List<String> listed = fm.list("historical");
        if (listed.isEmpty()) {
            return anchors;
        }
        final Set<String> names = new HashSet<>();
        for (final String h : listed) {
            names.add(normalizeSlashes(h));
        }
        final List<Anchor> marked = new ArrayList<>(anchors.size());
        for (final Anchor a : anchors) {
            final boolean isSource = a.kind() == AnchorKind.SOURCE_PATH || a.kind() == AnchorKind.SOURCE_BASENAME;
            if (isSource && (names.contains(a.target()) || names.contains(lastSegment(a.target())))) {
                marked.add(a.asHistorical());
            } else {
                marked.add(a);
            }
        }
        return marked;
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
     * Emits anchors from the document body outside fenced code blocks. Markdown links are matched over
     * the whole (fence-blanked) body so a link whose text wraps across lines is still seen; abbreviated
     * {@code module/.../File.java} paths, inline code-span source citations, prose {@code Module:} labels,
     * and bare catalog IDs are scanned per line.
     *
     * @param doc the scanned KB document.
     * @param out the list to append discovered anchors to.
     */
    private void extractBody(final KbDocument doc, final List<Anchor> out) {
        final List<String> lines = doc.lines();
        final String ownKey = doc.entry().key();
        final int bodyStart = doc.frontmatter().bodyLine();
        final String docDir = parentDir(doc.entry().relativePath());

        // A fence-and-comment-blanked copy of the body (one entry per file line, line count preserved)
        // drives the whole-body markdown-link and code-span passes; lineStart maps a match offset back to
        // a line. HTML comments are blanked because commented-out text is not a claim — an index README's
        // row-convention template would otherwise assert.
        final StringBuilder body = new StringBuilder();
        final long[] lineStart = new long[lines.size()];
        // Stated "Module: `x`" label keyed by the file line it sits on, for the adjacent source link.
        final Map<Integer, String> statedModuleByLine = new HashMap<>();
        boolean inFence = false;
        final boolean[] inComment = {false};

        for (int idx = 0; idx < lines.size(); idx++) {
            lineStart[idx] = body.length();
            final String rawLine = lines.get(idx);
            final int fileLine = idx + 1;
            final String stripped = rawLine.strip();

            if (idx < bodyStart - 1) {
                body.append('\n'); // frontmatter: keep line offsets but nothing to match.
                continue;
            }
            if (stripped.startsWith("```") || stripped.startsWith("~~~")) {
                inFence = !inFence;
                body.append('\n');
                continue;
            }
            if (inFence) {
                body.append('\n');
                continue;
            }
            final String line = blankComments(rawLine, inComment);
            body.append(line).append('\n');

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

            // Prose "Module: `x`" label for the adjacent source link on this same line.
            final Matcher mod = MODULE_LABEL.matcher(line);
            if (mod.find()) {
                statedModuleByLine.put(fileLine, mod.group(1));
            }
        }

        // Markdown links, matched over the whole body so link text may wrap across lines. Mask each link
        // region (preserving offsets) so the code-span pass never re-reads a file citation used as link text.
        final char[] masked = body.toString().toCharArray();
        final Matcher link = MD_LINK.matcher(body);
        while (link.find()) {
            if (link.group(1).contains("\n\n")) {
                continue; // A CommonMark link text cannot span a blank line — not a real link.
            }
            final int urlLine = lineAt(lineStart, link.start(2));
            extractLink(link.group(1), link.group(2), docDir, urlLine, statedModuleByLine.get(urlLine), out);
            for (int i = link.start(); i < link.end(); i++) {
                if (masked[i] != '\n') {
                    masked[i] = ' ';
                }
            }
        }

        // Inline code-span source citations (bare-path prose), over the link-masked body.
        final Matcher span = CODE_SPAN.matcher(new String(masked));
        while (span.find()) {
            extractCodeSpan(span.group(1), lineAt(lineStart, span.start(1)), out);
        }
    }

    /**
     * Emits a source anchor for an inline code-span citation: a full {@code platform-sdk/…} path becomes a
     * {@link AnchorKind#SOURCE_PATH} (existence + move check), a bare {@code File.java} basename becomes a
     * {@link AnchorKind#SOURCE_BASENAME} (existence only). Any {@code :NN}/{@code :NN-MM} suffix is dropped —
     * line numbers are never asserted on. Non-source spans are ignored.
     *
     * @param content  the code-span content (without surrounding backticks).
     * @param fileLine the 1-based line of the span in the document.
     * @param out      the list to append discovered anchors to.
     */
    private void extractCodeSpan(final String content, final int fileLine, final List<Anchor> out) {
        final String span = content.strip();
        final Matcher full = FULL_SOURCE_PATH.matcher(span);
        if (full.matches()) {
            final String path = full.group(1);
            out.add(new Anchor(AnchorKind.SOURCE_PATH, path, moduleOfPath(path), null, fileLine, Anchor.NO_LINE, span));
            return;
        }
        final Matcher moduleRel = MODULE_RELATIVE_SOURCE_PATH.matcher(span);
        if (moduleRel.matches()) {
            final String path = "platform-sdk/" + moduleRel.group(1);
            out.add(new Anchor(AnchorKind.SOURCE_PATH, path, moduleOfPath(path), null, fileLine, Anchor.NO_LINE, span));
            return;
        }
        final Matcher bare = BARE_SOURCE_FILE.matcher(span);
        if (bare.matches()) {
            out.add(new Anchor(AnchorKind.SOURCE_BASENAME, bare.group(1), null, null, fileLine, Anchor.NO_LINE, span));
        }
    }

    /**
     * Blanks the HTML-comment regions of a line, preserving its length, and tracks multi-line comment
     * state across calls. Commented-out text is not a claim, so nothing inside {@code <!-- … -->} may
     * feed an anchor. Fenced lines never reach this method, so a comment marker shown inside a code
     * fence cannot toggle the state.
     *
     * @param line      the raw line.
     * @param inComment single-element carry-over state: whether the previous line left a comment open.
     * @return the line with commented regions replaced by spaces.
     */
    private static String blankComments(final String line, final boolean[] inComment) {
        if (!inComment[0] && !line.contains("<!--")) {
            return line;
        }
        final StringBuilder out = new StringBuilder(line.length());
        int i = 0;
        while (i < line.length()) {
            if (!inComment[0] && line.startsWith("<!--", i)) {
                inComment[0] = true;
                out.append("    ");
                i += 4;
            } else if (inComment[0] && line.startsWith("-->", i)) {
                inComment[0] = false;
                out.append("   ");
                i += 3;
            } else {
                out.append(inComment[0] ? ' ' : line.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    /**
     * Maps a 0-based offset in the fence-blanked body to its 1-based file line via the per-line start
     * offsets, by binary search for the last line starting at or before the offset.
     *
     * @param lineStart the body offset at which each file line begins.
     * @param offset    the body offset to locate.
     * @return the 1-based file line containing the offset.
     */
    private static int lineAt(final long[] lineStart, final int offset) {
        int lo = 0;
        int hi = lineStart.length - 1;
        int ans = 0;
        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            if (lineStart[mid] <= offset) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans + 1;
    }

    /**
     * Emits anchors for a single markdown link, dispatching by target kind: cross-doc {@code .md}
     * links (with optional heading fragment), source files (with an optional named-method reference),
     * and directory links. External ({@code http}/{@code https}/{@code mailto}) and pure in-document
     * fragment links are ignored.
     *
     * @param linkText     the link's display text.
     * @param url          the link's target URL.
     * @param docDir       the repo-relative directory of the containing document, for resolving relatives.
     * @param fileLine     the 1-based line of the link's URL in the document.
     * @param statedModule the prose {@code Module:} label on the link's line, or {@code null}.
     * @param out          the list to append discovered anchors to.
     */
    private void extractLink(
            final String linkText,
            final String url,
            final String docDir,
            final int fileLine,
            final String statedModule,
            final List<Anchor> out) {
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
            out.add(new Anchor(
                    AnchorKind.SOURCE_PATH, repoRel, module, null, fileLine, Anchor.NO_LINE, url, statedModule));
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
