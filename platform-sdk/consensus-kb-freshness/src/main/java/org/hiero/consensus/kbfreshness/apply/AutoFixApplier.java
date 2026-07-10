// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.apply;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.hiero.consensus.kbfreshness.engine.RunResult;
import org.hiero.consensus.kbfreshness.render.AutoFix;
import org.hiero.consensus.kbfreshness.render.AutoFix.Edit;

/**
 * Applies the deterministic auto-fix edits ({@link AutoFix#edits}) to the KB files on disk. Only the
 * certain fixes are written — moved line references and unique package/path moves, exactly the diffs a
 * curator sees in {@code auto-fix.md}. Each edit is guarded by an exact match of the line's current text,
 * so applying is idempotent (a re-run finds the citation already correct and proposes nothing) and never
 * clobbers a line that has since diverged. Fuzzy did-you-mean renames are deliberately out of scope.
 */
public final class AutoFixApplier {

    /** Prevents instantiation of this static-only applier. */
    private AutoFixApplier() {}

    /**
     * The outcome of an apply pass.
     *
     * @param applied      the number of edits written.
     * @param skipped      the number of edits whose target line no longer matched (already fixed or diverged).
     * @param filesChanged the repo-relative paths of the files that were modified, sorted.
     */
    public record Result(int applied, int skipped, List<String> filesChanged) {}

    /**
     * Applies every certain auto-fix edit for the run under {@code repoRoot}.
     *
     * @param result   the run result to draw edits from.
     * @param repoRoot the repository root the edits' document paths are relative to.
     * @return a summary of what was applied and skipped.
     * @throws IOException if reading or writing a KB file fails.
     */
    public static Result apply(final RunResult result, final Path repoRoot) throws IOException {
        final Map<String, List<Edit>> byFile = new LinkedHashMap<>();
        for (final Edit e : AutoFix.edits(result)) {
            byFile.computeIfAbsent(e.docRelPath(), k -> new ArrayList<>()).add(e);
        }

        int applied = 0;
        int skipped = 0;
        final List<String> changed = new ArrayList<>();
        for (final Map.Entry<String, List<Edit>> fileEdits : new TreeMap<>(byFile).entrySet()) {
            final Path file = repoRoot.resolve(fileEdits.getKey());
            final String content = Files.readString(file, StandardCharsets.UTF_8);
            // split with a trailing-empty limit so a final newline round-trips; each element keeps its own
            // line terminator (a trailing \r on CRLF files), which the rewrite preserves.
            final String[] lines = content.split("\n", -1);
            boolean fileChanged = false;
            for (final Edit e : fileEdits.getValue()) {
                final int idx = e.line() - 1;
                if (idx < 0 || idx >= lines.length) {
                    skipped++;
                    continue;
                }
                final String raw = lines[idx];
                final String cr = raw.endsWith("\r") ? "\r" : "";
                final String bare = raw.substring(0, raw.length() - cr.length());
                if (bare.equals(e.before())) {
                    lines[idx] = e.after() + cr;
                    applied++;
                    fileChanged = true;
                } else {
                    skipped++;
                }
            }
            if (fileChanged) {
                Files.writeString(file, String.join("\n", lines), StandardCharsets.UTF_8);
                changed.add(fileEdits.getKey());
            }
        }
        return new Result(applied, skipped, changed);
    }
}
