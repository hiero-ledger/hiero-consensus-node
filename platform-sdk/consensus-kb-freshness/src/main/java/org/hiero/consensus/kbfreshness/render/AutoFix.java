// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hiero.consensus.kbfreshness.engine.RunResult;
import org.hiero.consensus.kbfreshness.extract.KbDocument;
import org.hiero.consensus.kbfreshness.model.AnchorKind;
import org.hiero.consensus.kbfreshness.model.Finding;
import org.hiero.consensus.kbfreshness.model.Lane;
import org.hiero.consensus.kbfreshness.model.Occurrence;
import org.hiero.consensus.kbfreshness.resolve.JavaParsing;
import org.hiero.consensus.kbfreshness.resolve.SourceIndex;
import org.hiero.consensus.kbfreshness.util.RepoPaths;

/**
 * The single source of truth for deterministic auto-fix edits: a citation that still resolves but is
 * cited wrongly (a moved line reference, a package/path move with exactly one new location, or a
 * fully-qualified type whose package moved) yields a precise before/after edit for each affected KB line.
 * {@link AutoFixRenderer} turns these into Markdown proposals
 * and {@code AutoFixApplier} writes them to disk under {@code --fix}; both consume this plan so the
 * proposal a curator reads is exactly the edit the tool would apply. Only certain fixes are produced —
 * fuzzy renames (did-you-mean) are never auto-fixes.
 */
public final class AutoFix {

    /** Matches a trailing {@code :NN}/{@code :NN-MM} line hint on a citation's raw text. */
    private static final Pattern TRAILING_LINE_HINT = Pattern.compile(":(\\d+)(?:-(\\d+))?$");

    /** Prevents instantiation of this static-only planner. */
    private AutoFix() {}

    /**
     * One concrete edit: replace the 1-based {@code line} of {@code docRelPath} — whose current text is
     * {@code before} — with {@code after}.
     *
     * @param docRelPath the repo-relative path of the KB document to edit.
     * @param line       the 1-based line to replace.
     * @param before     the line's current text (a guard: the edit applies only if the line still matches).
     * @param after      the replacement text.
     */
    public record Edit(String docRelPath, int line, String before, String after) {}

    /**
     * One rendered change within a proposal: a human-readable {@code header} and the concrete {@code edit}
     * it corresponds to (or {@code null} when the citation could not be located to diff).
     *
     * @param header the Markdown bullet describing the change.
     * @param edit   the concrete edit, or {@code null} when only the header is shown.
     */
    public record Change(String header, Edit edit) {}

    /**
     * All changes proposed for one finding, plus the finding itself for header rendering.
     *
     * @param finding the finding the changes correct.
     * @param changes the per-occurrence changes.
     */
    public record Proposal(Finding finding, List<Change> changes) {}

    /**
     * Builds the auto-fix plan for a run: one {@link Proposal} per line-move or path-move finding, in the
     * findings' deterministic order.
     *
     * @param result the run result.
     * @return the ordered proposals (possibly empty).
     */
    public static List<Proposal> plan(final RunResult result) {
        final Map<String, KbDocument> byKey = new HashMap<>();
        for (final KbDocument d : result.documents()) {
            byKey.put(d.entry().key(), d);
        }
        final Path repoRoot = result.sourceIndex().repoRoot();
        final Map<String, Integer> lineCounts = new HashMap<>();
        final List<Proposal> proposals = new ArrayList<>();
        for (final Finding f : result.findings()) {
            if (f.lane() == Lane.AUTO_FIX && f.autoFixSymbol() != null) {
                proposals.add(new Proposal(f, symbolChanges(f, byKey.get(f.entryKey()), result.sourceIndex())));
            } else if (f.lane() == Lane.AUTO_FIX && f.autoFixLine() != null) {
                proposals.add(new Proposal(f, lineChanges(f, byKey.get(f.entryKey()))));
            } else if (f.resolvedPath() != null) {
                proposals.add(new Proposal(f, pathChanges(f, byKey.get(f.entryKey()), repoRoot, lineCounts)));
            }
        }
        return proposals;
    }

    /**
     * The flat list of concrete edits across every proposal — what {@code AutoFixApplier} writes.
     *
     * @param result the run result.
     * @return every non-null edit, in plan order.
     */
    public static List<Edit> edits(final RunResult result) {
        final List<Edit> edits = new ArrayList<>();
        for (final Proposal p : plan(result)) {
            for (final Change c : p.changes()) {
                if (c.edit() != null) {
                    edits.add(c.edit());
                }
            }
        }
        return edits;
    }

    /**
     * The changes for a {@code :NN}→{@code #symbol} migration: each occurrence's cited line suffix is
     * replaced by the declaration's symbol name, turning a volatile line reference into a durable one.
     *
     * @param f   the auto-fix finding carrying the migration symbol.
     * @param doc the citing document, or {@code null} when unavailable.
     * @return the per-occurrence changes.
     */
    private static List<Change> symbolChanges(final Finding f, final KbDocument doc, final SourceIndex index) {
        final List<Change> changes = new ArrayList<>();
        final String path = migratedFilePath(f, index);
        final JavaParsing.ParsedFile parsed = path == null ? null : index.parse(path);
        for (final Occurrence o : f.occurrences()) {
            // Each occurrence cites the same file at its own line, so each migrates to its own symbol.
            final String symbol = parsed == null ? null : JavaParsing.symbolAtLine(parsed, o.citedLine());
            if (symbol == null) {
                continue;
            }
            final String raw = o.rawText();
            final String migratedRaw = TRAILING_LINE_HINT.matcher(raw).replaceFirst("#" + symbol);
            final String header = "KB line " + o.docLine() + ": update `" + raw + "` → `" + migratedRaw + "`";
            final String before = docLine(doc, o.docLine());
            Edit edit = null;
            if (before != null) {
                final String after = rewriteCitation(before, raw, migratedRaw, symbol);
                if (after != null && !after.equals(before)) {
                    edit = new Edit(f.entryPath(), o.docLine(), before, after);
                }
            }
            changes.add(new Change(header, edit));
        }
        return changes;
    }

    /**
     * Rewrites a cited source reference on a KB line to its migrated form. A code span's raw text is the
     * citation itself, replaced directly. A markdown link {@code [text](raw)} migrates both parts: the URL,
     * and the link text's trailing {@code :NN} — kept as {@code #symbol} for a {@code File.java}-shaped text
     * or dropped for a bare method-name text.
     *
     * @param before      the KB line's current text.
     * @param raw         the occurrence's raw citation (a code span, or a link URL).
     * @param migratedRaw {@code raw} with its {@code :NN} rewritten to {@code #symbol}.
     * @param symbol      the symbol the reference migrates to.
     * @return the rewritten line, or {@code null} when the citation is not found.
     */
    private static String rewriteCitation(
            final String before, final String raw, final String migratedRaw, final String symbol) {
        final String linkTail = "](" + raw + ")";
        final int linkIdx = before.indexOf(linkTail);
        if (linkIdx < 0) {
            return before.contains(raw) ? before.replace(raw, migratedRaw) : null;
        }
        final int textOpen = before.lastIndexOf('[', linkIdx);
        if (textOpen < 0) {
            return before.replace(raw, migratedRaw);
        }
        final String migratedText = migrateLinkText(before.substring(textOpen + 1, linkIdx), symbol);
        return before.substring(0, textOpen) + "[" + migratedText + "](" + migratedRaw + ")"
                + before.substring(linkIdx + linkTail.length());
    }

    /**
     * Migrates a markdown link's display text: a {@code File.java}-shaped text has its trailing {@code :NN}
     * replaced by {@code #symbol}; a bare method-name text just drops the {@code :NN}; text without a line
     * hint is unchanged.
     *
     * @param text   the link's display text.
     * @param symbol the symbol the reference migrates to.
     * @return the migrated text.
     */
    private static String migrateLinkText(final String text, final String symbol) {
        final Matcher m = TRAILING_LINE_HINT.matcher(text);
        if (!m.find()) {
            return text;
        }
        final String head = text.substring(0, m.start());
        return head.endsWith(".java") ? head + "#" + symbol : head;
    }

    /**
     * The repo-relative file a symbol-migration finding resolves to: the cited path for a
     * {@link AnchorKind#SOURCE_PATH}, or the unique indexed path for a {@link AnchorKind#SOURCE_BASENAME}.
     *
     * @param f     the migration finding.
     * @param index the source index.
     * @return the resolved source path, or {@code null} when it cannot be pinned to a single file.
     */
    private static String migratedFilePath(final Finding f, final SourceIndex index) {
        if (f.kind() == AnchorKind.SOURCE_PATH) {
            return index.fileExists(f.target()) ? f.target() : null;
        }
        if (f.kind() == AnchorKind.SOURCE_BASENAME) {
            final List<String> paths = index.pathsForBasename(f.target());
            return paths.size() == 1 ? paths.get(0) : null;
        }
        return null;
    }

    /**
     * The changes for a moved-line finding: the cited {@code :NN} suffix updated to the declaration line.
     *
     * @param f   the auto-fix finding carrying the corrected line.
     * @param doc the citing document, or {@code null} when unavailable.
     * @return the per-occurrence changes.
     */
    private static List<Change> lineChanges(final Finding f, final KbDocument doc) {
        final List<Change> changes = new ArrayList<>();
        final int corrected = f.autoFixLine();
        for (final Occurrence o : f.occurrences()) {
            final String header = "KB line " + o.docLine() + ": update `:" + o.citedLine() + "` → `:" + corrected + "`";
            final String before = docLine(doc, o.docLine());
            Edit edit = null;
            if (before != null && o.citedLine() >= 0) {
                final String after = before.replace(":" + o.citedLine(), ":" + corrected);
                if (!after.equals(before)) {
                    edit = new Edit(f.entryPath(), o.docLine(), before, after);
                }
            }
            changes.add(new Change(header, edit));
        }
        return changes;
    }

    /**
     * The changes for a package/path-move finding: the cited path (or FQN) rewritten to the single
     * location the file resolves at, plus a stale on-line {@code Module: `X`} label. A citation whose
     * {@code :NN} line hint exceeds the moved file's length gets a re-verify note (the hint is navigation
     * only and never asserted on).
     *
     * @param f          the finding carrying the resolved path.
     * @param doc        the citing document, or {@code null} when unavailable.
     * @param repoRoot   the repository root, for measuring the moved file.
     * @param lineCounts per-plan cache of a repo-relative path to its line count.
     * @return the per-occurrence changes.
     */
    private static List<Change> pathChanges(
            final Finding f, final KbDocument doc, final Path repoRoot, final Map<String, Integer> lineCounts) {
        final boolean fqnCitation = f.kind() == AnchorKind.CLASS;
        final String replacement = fqnCitation ? fqnOfMove(f) : f.resolvedPath();
        final List<Change> changes = new ArrayList<>();
        for (final Occurrence o : f.occurrences()) {
            String header = "KB line " + o.docLine() + ": update " + (fqnCitation ? "type reference" : "path") + " to `"
                    + replacement + "`";
            final int staleHint = staleLineHint(o, f.resolvedPath(), repoRoot, lineCounts);
            if (staleHint > 0) {
                header += " — note: the cited `:" + hintText(o) + "` hint exceeds the moved file's " + staleHint
                        + " line(s); re-verify it after applying";
            }
            Edit edit = null;
            if (replacement != null) {
                final int diffLine = fqnCitation
                        ? plainRewriteLine(doc, o.docLine(), f.target(), replacement)
                        : rewritableLine(doc, o.docLine(), f.target(), f.resolvedPath());
                if (diffLine > 0) {
                    final String before = docLine(doc, diffLine);
                    final String after = fqnCitation
                            ? before.replace(f.target(), replacement)
                            : rewriteModuleLabel(
                                    rewritePath(before, f.target(), f.resolvedPath()),
                                    f.statedModule(),
                                    RepoPaths.moduleOf(f.resolvedPath()));
                    if (!after.equals(before)) {
                        edit = new Edit(f.entryPath(), diffLine, before, after);
                    }
                }
            }
            changes.add(new Change(header, edit));
        }
        return changes;
    }

    /**
     * The moved file's line count when an occurrence carries a line hint that cannot exist in it, else
     * {@code 0}. Reads the file once per plan; an unreadable file yields no note.
     *
     * @param o            the occurrence whose raw text may end in {@code :NN}/{@code :NN-MM}.
     * @param resolvedPath the repo-relative path the citation moves to.
     * @param repoRoot     the repository root.
     * @param lineCounts   per-plan cache of a repo-relative path to its line count.
     * @return the moved file's line count when the hint exceeds it, otherwise {@code 0}.
     */
    private static int staleLineHint(
            final Occurrence o, final String resolvedPath, final Path repoRoot, final Map<String, Integer> lineCounts) {
        final String hint = hintText(o);
        if (hint == null) {
            return 0;
        }
        int max = 0;
        for (final String part : hint.split("-")) {
            max = Math.max(max, Integer.parseInt(part));
        }
        final int count = lineCounts.computeIfAbsent(resolvedPath, p -> {
            try {
                return Files.readAllLines(repoRoot.resolve(p)).size();
            } catch (final IOException | RuntimeException e) {
                return -1;
            }
        });
        return count >= 0 && max > count ? count : 0;
    }

    /**
     * The {@code NN} or {@code NN-MM} line hint at the end of an occurrence's raw citation text, or
     * {@code null} when it carries none.
     *
     * @param o the occurrence.
     * @return the hint digits (without the leading colon), or {@code null}.
     */
    private static String hintText(final Occurrence o) {
        if (o.rawText() == null) {
            return null;
        }
        final Matcher m = TRAILING_LINE_HINT.matcher(o.rawText());
        return m.find() ? o.rawText().substring(m.start() + 1) : null;
    }

    /**
     * The rewritten fully-qualified name for a moved type citation: the moved file's package plus its
     * type name, keeping any nested-type segments the citation carried beyond the file-defining type.
     *
     * @param f the {@link AnchorKind#CLASS} finding; its target is the cited FQN and its cited scope the
     *          file-defining type name.
     * @return the new FQN, or {@code null} when the resolved path carries no main-source package.
     */
    private static String fqnOfMove(final Finding f) {
        final String pkg = SourceIndex.dottedPackageOf(f.resolvedPath());
        if (pkg == null) {
            return null;
        }
        final String primary = f.citedScope();
        final int primaryAt = f.target().indexOf("." + primary);
        final String nestedSuffix = f.target().substring(primaryAt + 1 + primary.length());
        return pkg + "." + RepoPaths.stripExtension(RepoPaths.lastSegment(f.resolvedPath())) + nestedSuffix;
    }

    /**
     * Finds the KB line at or after {@code docLine} containing the given text verbatim — the plain
     * counterpart of {@link #rewritableLine} for citations (FQNs) that have exactly one written form.
     *
     * @param doc     the citing document, possibly {@code null}.
     * @param docLine the occurrence's 1-based line hint.
     * @param text    the citation text to locate.
     * @param replacement the replacement text (a line already carrying it needs no edit).
     * @return the 1-based line to diff, or {@code 0} when no line contains the text.
     */
    private static int plainRewriteLine(
            final KbDocument doc, final int docLine, final String text, final String replacement) {
        if (doc == null || text.equals(replacement)) {
            return 0;
        }
        for (int line = docLine; line <= doc.lines().size(); line++) {
            final String lineText = docLine(doc, line);
            if (lineText != null && lineText.contains(text)) {
                return line;
            }
        }
        return 0;
    }

    /**
     * Finds the KB line to diff for a path rewrite: the occurrence line itself, or — when the occurrence
     * points at a frontmatter key line ({@code components:}) rather than the item carrying the path — the
     * first following line where the citation actually rewrites.
     *
     * @param doc     the citing document, possibly {@code null}.
     * @param docLine the occurrence's 1-based line hint.
     * @param oldPath the cited path (the finding's target).
     * @param newPath the repo-relative path the source actually resolves at.
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
     * uses (abbreviated {@code module/.../File.java}, full repo-relative, and root-relative without the
     * first segment). When the move is also a rename (the basenames differ), remaining mentions of the old
     * file name and its bare class name on the line are rewritten too.
     *
     * @param line    the KB line containing the citation.
     * @param oldPath the cited path (the finding's target).
     * @param newPath the repo-relative path the source actually resolves at.
     * @return the rewritten line, or the original line when no citation style matched.
     */
    static String rewritePath(final String line, final String oldPath, final String newPath) {
        return rewriteRename(rewritePathStyles(line, oldPath, newPath), oldPath, newPath);
    }

    /**
     * The path-portion rewrite of {@link #rewritePath}: the three citation styles, basename untouched
     * unless it is part of the matched path.
     *
     * @param line    the KB line containing the citation.
     * @param oldPath the cited path (the finding's target).
     * @param newPath the repo-relative path the source actually resolves at.
     * @return the rewritten line, or the original line when no citation style matched.
     */
    private static String rewritePathStyles(final String line, final String oldPath, final String newPath) {
        if (oldPath.contains("/.../")) {
            final String newModule = RepoPaths.moduleOf(newPath);
            if (newModule != null && line.contains(oldPath)) {
                return line.replace(oldPath, newModule + "/.../" + RepoPaths.lastSegment(newPath));
            }
            return line;
        }
        if (line.contains(oldPath)) {
            return line.replace(oldPath, newPath);
        }
        final String oldRel = RepoPaths.withoutFirstSegment(oldPath);
        final String newRel = RepoPaths.withoutFirstSegment(newPath);
        if (oldRel != null && newRel != null && line.contains(oldRel)) {
            return line.replace(oldRel, newRel);
        }
        return line;
    }

    /**
     * For a move that is also a rename, rewrites leftover mentions of the old basename (link text) and
     * bare old class name (headings, prose) to the new one. A same-name move — every finding the
     * per-anchor pipeline produces — passes through untouched.
     *
     * @param line    the (already path-rewritten) KB line.
     * @param oldPath the cited path.
     * @param newPath the resolved path.
     * @return the line with renamed-class mentions updated.
     */
    private static String rewriteRename(final String line, final String oldPath, final String newPath) {
        final String oldBase = RepoPaths.lastSegment(oldPath);
        final String newBase = RepoPaths.lastSegment(newPath);
        if (oldBase.equals(newBase)) {
            return line;
        }
        String out = line.replace(oldBase, newBase);
        final String oldStem = RepoPaths.stripExtension(oldBase);
        final String newStem = RepoPaths.stripExtension(newBase);
        if (!oldStem.equals(newStem)) {
            out = out.replaceAll("\\b" + Pattern.quote(oldStem) + "\\b", Matcher.quoteReplacement(newStem));
        }
        return out;
    }

    /**
     * Rewrites a stale {@code Module: `<oldModule>`} label on a line to the new module. Applied only when a
     * stated module was recorded and it differs from the new module, and only to the exact backtick-wrapped
     * token following {@code Module:} — so unrelated occurrences of the module name are left untouched.
     *
     * @param line      the line (already path-rewritten).
     * @param oldModule the stated module label to correct, or {@code null} when none was stated.
     * @param newModule the module the source now resolves in, or {@code null} when indeterminable.
     * @return the line with the module label corrected, or unchanged when nothing applies.
     */
    static String rewriteModuleLabel(final String line, final String oldModule, final String newModule) {
        if (oldModule == null || newModule == null || oldModule.equals(newModule)) {
            return line;
        }
        final String token = "Module: `" + oldModule + "`";
        return line.contains(token) ? line.replace(token, "Module: `" + newModule + "`") : line;
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
}
