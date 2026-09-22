// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.render;

import org.hiero.consensus.kbfreshness.engine.RunResult;
import org.hiero.consensus.kbfreshness.model.Finding;
import org.hiero.consensus.kbfreshness.model.Lane;
import org.hiero.consensus.kbfreshness.util.Markdown;

/**
 * Renders the quiet log: unverifiable results (generated/external symbols, ambiguous or unresolvable
 * citations). These are never asserted, but are recorded so a curator can audit what the engine chose
 * not to decide.
 */
public final class QuietLogRenderer {

    /** Prevents instantiation of this static-only renderer. */
    private QuietLogRenderer() {}

    /**
     * Renders the quiet log of unverifiable results as Markdown.
     *
     * @param result the run result.
     * @return the rendered Markdown quiet log.
     */
    public static String render(final RunResult result) {
        final StringBuilder sb = new StringBuilder();
        sb.append("# KB freshness — quiet log (unverifiable)\n\n");
        sb.append("_Checks the engine could not decide as a fact. Not drift; not asserted._\n\n");
        boolean any = false;
        for (final Finding f : result.findings()) {
            if (f.lane() != Lane.QUIET_LOG) {
                continue;
            }
            any = true;
            sb.append("- `")
                    .append(f.entryKey())
                    .append("` — ")
                    .append(Markdown.humanize(f.kind()))
                    .append(" `")
                    .append(f.target())
                    .append("`: ")
                    .append(f.evidence())
                    .append(" (id `")
                    .append(f.id())
                    .append("`)\n");
        }
        if (!any) {
            sb.append("_None._\n");
        }
        return sb.toString();
    }
}
