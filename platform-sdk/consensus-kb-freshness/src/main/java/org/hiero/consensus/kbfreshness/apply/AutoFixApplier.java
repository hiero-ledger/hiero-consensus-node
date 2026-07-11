// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.apply;

import java.io.IOException;
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
 * Applies the deterministic {@link AutoFix#edits} to the KB files on disk under {@code --fix} — exactly
 * the diffs {@code auto-fix.md} shows. Each edit is guarded by an exact match of the line's current text,
 * so applying is idempotent and never clobbers a line that has since diverged.
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
            final GuardedLineEditor editor = GuardedLineEditor.open(repoRoot.resolve(fileEdits.getKey()));
            for (final Edit e : fileEdits.getValue()) {
                if (editor.hasLine(e.line()) && editor.bareLine(e.line()).equals(e.before())) {
                    editor.rewriteLine(e.line(), e.after());
                    applied++;
                } else {
                    skipped++;
                }
            }
            if (editor.changed()) {
                editor.flush();
                changed.add(fileEdits.getKey());
            }
        }
        return new Result(applied, skipped, changed);
    }
}
