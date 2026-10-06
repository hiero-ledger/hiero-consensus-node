// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.render;

import org.hiero.consensus.kbfreshness.engine.RunResult;
import org.hiero.consensus.kbfreshness.model.Finding;
import org.hiero.consensus.kbfreshness.render.AutoFix.Change;
import org.hiero.consensus.kbfreshness.render.AutoFix.Proposal;

/**
 * Renders the {@link AutoFix} proposals as Markdown: each corrected citation shown as a before/after
 * edit — the same edits {@code AutoFixApplier} writes under {@code --fix}.
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
        final StringBuilder sb = new StringBuilder();
        sb.append("# KB freshness — auto-fix proposals (line references and path moves)\n\n");
        sb.append("_The symbol still resolves; only the cited line or path moved. Apply automatically with "
                + "`--fix`, or by hand._\n\n");

        final var proposals = AutoFix.plan(result);
        for (final Proposal p : proposals) {
            appendHeader(sb, p.finding());
            for (final Change c : p.changes()) {
                sb.append("- ").append(c.header()).append('\n');
                if (c.edit() != null) {
                    appendDiff(sb, c.edit().before(), c.edit().after());
                }
            }
            sb.append('\n');
        }
        if (proposals.isEmpty()) {
            sb.append("_None._\n");
        }
        return sb.toString();
    }

    /**
     * Appends the shared per-finding header (entry key, target, entry path, evidence).
     *
     * @param sb the buffer to append to.
     * @param f  the finding to describe.
     */
    private static void appendHeader(final StringBuilder sb, final Finding f) {
        Md.findingHeader(sb, f.entryKey(), f.target(), f.entryPath(), f.evidence());
    }

    /**
     * Appends a before/after diff block.
     *
     * @param sb     the buffer to append to.
     * @param before the KB line as it is.
     * @param after  the KB line with the proposed fix applied.
     */
    private static void appendDiff(final StringBuilder sb, final String before, final String after) {
        sb.append("  ```diff\n");
        sb.append("  - ").append(before).append('\n');
        sb.append("  + ").append(after).append('\n');
        sb.append("  ```\n");
    }
}
