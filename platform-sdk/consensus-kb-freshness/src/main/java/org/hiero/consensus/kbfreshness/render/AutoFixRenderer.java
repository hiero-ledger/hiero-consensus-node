// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.render;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hiero.consensus.kbfreshness.engine.RunResult;
import org.hiero.consensus.kbfreshness.extract.KbDocument;
import org.hiero.consensus.kbfreshness.model.Finding;
import org.hiero.consensus.kbfreshness.model.Lane;
import org.hiero.consensus.kbfreshness.model.Occurrence;

/**
 * Renders deterministic auto-fix proposals for citations that still resolve but are cited wrongly:
 * a moved line reference (the symbol resolves at another line) or a package/path move with exactly one
 * new location (the file resolves at another path). Line numbers and paths are never rewritten by the
 * tool — the corrected citation is proposed as a before/after edit for the curator to apply. Nothing is
 * written to the KB; these are suggestions only.
 */
public final class AutoFixRenderer {

    /** Prevents instantiation of this static-only renderer. */
    private AutoFixRenderer() {}

    /**
     * Renders the auto-fix proposals (line references and path moves) as Markdown.
     *
     * @param result the run result.
     * @return the rendered Markdown proposals.
     */
    public static String render(final RunResult result) {
        final Map<String, KbDocument> byKey = new HashMap<>();
        for (final KbDocument d : result.documents()) {
            byKey.put(d.entry().key(), d);
        }

        final StringBuilder sb = new StringBuilder();
        sb.append("# KB freshness — auto-fix proposals (line references and path moves)\n\n");
        sb.append("_The symbol still resolves; only the cited line or path moved. Suggestions only — "
                + "apply by hand._\n\n");

        boolean any = false;
        for (final Finding f : result.findings()) {
            if (f.lane() == Lane.AUTO_FIX && f.autoFixLine() != null) {
                renderLineFix(sb, f, byKey.get(f.entryKey()));
                any = true;
            } else if (f.resolvedPath() != null) {
                renderPathFix(sb, f, byKey.get(f.entryKey()));
                any = true;
            }
        }
        if (!any) {
            sb.append("_None._\n");
        }
        return sb.toString();
    }

    /**
     * Renders one moved-line proposal: the cited {@code :NN} suffix updated to the line the symbol is
     * declared at.
     *
     * @param sb  the buffer to append to.
     * @param f   the auto-fix finding carrying the corrected line.
     * @param doc the citing document, or {@code null} when unavailable.
     */
    private static void renderLineFix(final StringBuilder sb, final Finding f, final KbDocument doc) {
        appendHeader(sb, f);
        for (final Occurrence o : f.occurrences()) {
            final int corrected = f.autoFixLine();
            sb.append("- KB line ")
                    .append(o.docLine())
                    .append(": update `:")
                    .append(o.citedLine())
                    .append("` → `:")
                    .append(corrected)
                    .append("`\n");
            final String before = docLine(doc, o.docLine());
            if (before != null && o.citedLine() >= 0) {
                appendDiff(sb, before, before.replace(":" + o.citedLine(), ":" + corrected));
            }
        }
        sb.append('\n');
    }

    /**
     * Renders one package/path-move proposal: the cited path rewritten to the single location the file
     * actually resolves at, in whichever citation style the KB line uses.
     *
     * @param sb  the buffer to append to.
     * @param f   the finding carrying the resolved path.
     * @param doc the citing document, or {@code null} when unavailable.
     */
    private static void renderPathFix(final StringBuilder sb, final Finding f, final KbDocument doc) {
        appendHeader(sb, f);
        for (final Occurrence o : f.occurrences()) {
            sb.append("- KB line ")
                    .append(o.docLine())
                    .append(": update path to `")
                    .append(f.resolvedPath())
                    .append("`\n");
            final int diffLine = rewritableLine(doc, o.docLine(), f.target(), f.resolvedPath());
            if (diffLine > 0) {
                final String before = docLine(doc, diffLine);
                appendDiff(sb, before, rewritePath(before, f.target(), f.resolvedPath()));
            }
        }
        sb.append('\n');
    }

    /**
     * Finds the KB line to diff for a path rewrite: the occurrence line itself, or — when the occurrence
     * points at a frontmatter key line ({@code components:}) rather than the item carrying the path —
     * the first following line where the citation actually rewrites.
     *
     * @param doc      the citing document, possibly {@code null}.
     * @param docLine  the occurrence's 1-based line hint.
     * @param oldPath  the cited path (the finding's target).
     * @param newPath  the repo-relative path the source actually resolves at.
     * @return the 1-based line to diff, or {@code 0} when no line rewrites.
     */
    private static int rewritableLine(
            final KbDocument doc, final int docLine, final String oldPath, final String newPath) {
        if (doc == null) {
            return 0;
        }
        for (int line = docLine; line <= doc.lines().size(); line++) {
            final String text = docLine(doc, line);
            if (text != null && !rewritePath(text, oldPath, newPath).equals(text)) {
                return line;
            }
        }
        return 0;
    }

    /**
     * Rewrites the cited path within a KB line to the resolved path, trying the citation styles the KB
     * uses: the abbreviated {@code module/.../File.java} form, the full repo-relative path (code spans),
     * and the root-relative form without its first segment (frontmatter {@code components:} entries and
     * relative markdown links, whose {@code ../} prefix is preserved by substring replacement).
     *
     * @param line     the KB line containing the citation.
     * @param oldPath  the cited path (the finding's target).
     * @param newPath  the repo-relative path the source actually resolves at.
     * @return the rewritten line, or the original line when no citation style matched.
     */
    private static String rewritePath(final String line, final String oldPath, final String newPath) {
        if (oldPath.contains("/.../")) {
            final String newModule = moduleOf(newPath);
            if (newModule != null && line.contains(oldPath)) {
                return line.replace(oldPath, newModule + "/.../" + lastSegment(newPath));
            }
            return line;
        }
        if (line.contains(oldPath)) {
            return line.replace(oldPath, newPath);
        }
        final String oldRel = withoutFirstSegment(oldPath);
        final String newRel = withoutFirstSegment(newPath);
        if (oldRel != null && newRel != null && line.contains(oldRel)) {
            return line.replace(oldRel, newRel);
        }
        return line;
    }

    /**
     * Appends the shared per-finding header (entry key, target, entry path, evidence).
     *
     * @param sb the buffer to append to.
     * @param f  the finding to describe.
     */
    private static void appendHeader(final StringBuilder sb, final Finding f) {
        sb.append("### `")
                .append(f.entryKey())
                .append("` — `")
                .append(f.target())
                .append("`\n");
        sb.append("`").append(f.entryPath()).append("` — ").append(f.evidence()).append("\n\n");
    }

    /**
     * Appends a before/after diff block when the rewrite changed the line.
     *
     * @param sb     the buffer to append to.
     * @param before the KB line as it is.
     * @param after  the KB line with the proposed fix applied.
     */
    private static void appendDiff(final StringBuilder sb, final String before, final String after) {
        if (!after.equals(before)) {
            sb.append("  ```diff\n");
            sb.append("  - ").append(before).append('\n');
            sb.append("  + ").append(after).append('\n');
            sb.append("  ```\n");
        }
    }

    /**
     * Returns a document's 1-based line text, or {@code null} when the document is missing or the line is
     * out of range.
     *
     * @param doc  the document, possibly {@code null}.
     * @param line the 1-based line number.
     * @return the line text, or {@code null} if unavailable.
     */
    private static String docLine(final KbDocument doc, final int line) {
        if (doc == null) {
            return null;
        }
        final List<String> lines = doc.lines();
        return (line >= 1 && line <= lines.size()) ? lines.get(line - 1) : null;
    }

    /**
     * The module directory of a repo-relative path (the segment preceding {@code src}).
     *
     * @param repoRelPath the repo-relative path.
     * @return the module directory name, or {@code null} if the path has no {@code src} segment.
     */
    private static String moduleOf(final String repoRelPath) {
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
     * The path with its first {@code /}-separated segment removed (e.g. {@code platform-sdk/m/F.java}
     * to {@code m/F.java}), or {@code null} when the path has no slash.
     *
     * @param path the path.
     * @return the path without its first segment, or {@code null}.
     */
    private static String withoutFirstSegment(final String path) {
        final int slash = path.indexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : null;
    }
}
