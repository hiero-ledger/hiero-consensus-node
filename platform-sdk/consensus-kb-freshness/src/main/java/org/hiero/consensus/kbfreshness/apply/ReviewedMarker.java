// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.apply;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.hiero.consensus.kbfreshness.engine.RunResult;
import org.hiero.consensus.kbfreshness.extract.KbDocument;
import org.hiero.consensus.kbfreshness.util.Patterns;

/**
 * Applies {@code --mark-reviewed}: mechanically bumps a topic's {@code last_reviewed:} frontmatter
 * date after its semantic review, so the next run's worklist does not re-flag a topic whose prose was
 * just read against the code. Strictly guarded like every other write: only an <em>existing</em>
 * {@code last_reviewed:} frontmatter line is rewritten (a doc without the marker is reported, never
 * invented), the entry key must resolve unambiguously, and the date must be ISO {@code yyyy-MM-dd}.
 */
public final class ReviewedMarker {

    /** The frontmatter key this applier rewrites. */
    private static final String KEY = "last_reviewed";

    /** Prevents instantiation of this static-only applier. */
    private ReviewedMarker() {}

    /**
     * The outcome of a mark pass.
     *
     * @param updated  the number of docs whose {@code last_reviewed:} line was rewritten (an
     *                 already-current date counts as success but not as an update).
     * @param problems one message per spec that could not be applied (unknown or ambiguous key, no or
     *                 invalid date, missing {@code last_reviewed:} line).
     */
    public record Result(int updated, List<String> problems) {}

    /**
     * Marks the named entries as reviewed on the given dates.
     *
     * @param result      the run result whose scanned documents resolve the entry keys.
     * @param repoRoot    the repository root the documents' paths are relative to.
     * @param specs       the {@code <key>[=<yyyy-MM-dd>]} specs; a spec without a date uses
     *                    {@code defaultDate}. A key may be the full entry key ({@code topic:gossip}) or
     *                    its bare slug when unambiguous.
     * @param defaultDate the date for specs that carry none (typically {@code --date}).
     * @return a summary of what was rewritten and which specs failed.
     * @throws IOException if reading or writing a KB file fails.
     */
    public static Result apply(
            final RunResult result, final Path repoRoot, final List<String> specs, final String defaultDate)
            throws IOException {
        int updated = 0;
        final List<String> problems = new ArrayList<>();
        for (final String spec : specs) {
            final int eq = spec.indexOf('=');
            final String key = (eq >= 0 ? spec.substring(0, eq) : spec).strip();
            final String date = (eq >= 0 ? spec.substring(eq + 1) : defaultDate).strip();
            if (!Patterns.ISO_DATE.matcher(date).matches()) {
                problems.add("`" + spec + "`: no ISO yyyy-MM-dd date (append `=<date>` or pass --date)");
                continue;
            }
            final KbDocument doc = resolveDoc(result, key, problems, spec);
            if (doc == null) {
                continue;
            }
            if (markDoc(doc, repoRoot, date, problems, spec)) {
                updated++;
            }
        }
        return new Result(updated, problems);
    }

    /**
     * Resolves a spec's key to exactly one scanned document: the full entry key, or the bare slug when
     * exactly one entry key ends in {@code :<slug>}.
     *
     * @param result   the run result.
     * @param key      the key or bare slug.
     * @param problems the problem sink.
     * @param spec     the originating spec, for messages.
     * @return the document, or {@code null} (with a problem recorded) when unknown or ambiguous.
     */
    private static KbDocument resolveDoc(
            final RunResult result, final String key, final List<String> problems, final String spec) {
        final List<KbDocument> matches = new ArrayList<>();
        for (final KbDocument d : result.documents()) {
            final String entryKey = d.entry().key();
            if (entryKey.equals(key) || entryKey.endsWith(":" + key)) {
                matches.add(d);
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        problems.add("`" + spec + "`: "
                + (matches.isEmpty()
                        ? "no scanned entry has key `" + key + "`"
                        : "key `" + key + "` is ambiguous: "
                                + String.join(
                                        ", ",
                                        matches.stream()
                                                .map(d -> d.entry().key())
                                                .toList())));
        return null;
    }

    /**
     * Rewrites the document's existing {@code last_reviewed:} frontmatter line to the given date,
     * preserving the line terminator. An already-current date is a success without a write.
     *
     * @param doc      the document to mark.
     * @param repoRoot the repository root.
     * @param date     the ISO date to record.
     * @param problems the problem sink.
     * @param spec     the originating spec, for messages.
     * @return {@code true} when the file was rewritten.
     * @throws IOException if reading or writing the file fails.
     */
    private static boolean markDoc(
            final KbDocument doc,
            final Path repoRoot,
            final String date,
            final List<String> problems,
            final String spec)
            throws IOException {
        if (!doc.frontmatter().keyLines().containsKey(KEY)) {
            problems.add("`" + spec + "`: `" + doc.entry().relativePath() + "` has no `" + KEY
                    + ":` frontmatter line to update (add one by hand first)");
            return false;
        }
        final int line = doc.frontmatter().lineOf(KEY);
        final GuardedLineEditor editor =
                GuardedLineEditor.open(repoRoot.resolve(doc.entry().relativePath()));
        if (!editor.hasLine(line)) {
            problems.add("`" + spec + "`: `" + KEY + ":` line " + line + " is out of range in `"
                    + doc.entry().relativePath() + "`");
            return false;
        }
        final String bare = editor.bareLine(line);
        if (!bare.strip().startsWith(KEY + ":")) {
            problems.add("`" + spec + "`: line " + line + " of `" + doc.entry().relativePath()
                    + "` no longer starts with `" + KEY + ":` — not rewritten");
            return false;
        }
        final String replacement = KEY + ": " + date;
        if (bare.equals(replacement)) {
            return false; // already current — success, nothing to write.
        }
        editor.rewriteLine(line, replacement);
        editor.flush();
        return true;
    }
}
