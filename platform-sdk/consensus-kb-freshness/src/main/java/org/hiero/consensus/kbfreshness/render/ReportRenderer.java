// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.render;

import java.util.ArrayList;
import java.util.List;
import org.hiero.consensus.kbfreshness.engine.RunResult;
import org.hiero.consensus.kbfreshness.findings.BaselineJoin;
import org.hiero.consensus.kbfreshness.model.Finding;
import org.hiero.consensus.kbfreshness.model.Lane;
import org.hiero.consensus.kbfreshness.model.Occurrence;
import org.hiero.consensus.kbfreshness.model.Outcome;
import org.hiero.consensus.kbfreshness.model.Triage;

/**
 * Renders the human-readable drift report — the assertions a curator acts on. Only {@code assert}-lane
 * findings that are not {@code dismissed} appear here; unverifiable results live in the quiet log and
 * line-only drift in the auto-fix proposals. New drift (ids absent from the baseline) is surfaced first
 * as the signal.
 */
public final class ReportRenderer {

    /** Prevents instantiation of this static-only renderer. */
    private ReportRenderer() {}

    /**
     * Renders the human-readable drift report as Markdown.
     *
     * @param result  the run result.
     * @param runDate the run date shown in the report header; blank or {@code null} omits it.
     * @return the rendered Markdown report.
     */
    public static String render(final RunResult result, final String runDate) {
        final List<BaselineJoin.Joined> asserts = new ArrayList<>();
        for (final BaselineJoin.Joined j : result.join().joined()) {
            if (j.finding().lane() == Lane.ASSERT && j.triage() != Triage.DISMISSED) {
                asserts.add(j);
            }
        }
        final List<BaselineJoin.Joined> newDrift =
                asserts.stream().filter(BaselineJoin.Joined::isNew).toList();
        final List<BaselineJoin.Joined> carried =
                asserts.stream().filter(j -> !j.isNew()).toList();

        final long dismissed = result.join().joined().stream()
                .filter(j -> j.finding().lane() == Lane.ASSERT && j.triage() == Triage.DISMISSED)
                .count();
        final long quiet = countLane(result, Lane.QUIET_LOG);
        final long autoFix = countLane(result, Lane.AUTO_FIX);

        final StringBuilder sb = new StringBuilder();
        sb.append("# KB freshness report\n\n");
        if (runDate != null && !runDate.isBlank()) {
            sb.append("Run date: ").append(runDate).append("\n\n");
        }
        sb.append("## Summary\n\n");
        sb.append("| Category | Count |\n|---|---|\n");
        sb.append("| New drift (assert) | ").append(newDrift.size()).append(" |\n");
        sb.append("| Carried drift (assert) | ").append(carried.size()).append(" |\n");
        sb.append("| Dismissed (suppressed) | ").append(dismissed).append(" |\n");
        sb.append("| Resolved since last run | ")
                .append(result.join().resolvedIds().size())
                .append(" |\n");
        sb.append("| Unverifiable (quiet log) | ").append(quiet).append(" |\n");
        sb.append("| Auto-fix proposals | ").append(autoFix).append(" |\n\n");

        section(sb, "New drift", newDrift, "New assertions since the baseline — the primary signal.");
        section(sb, "Carried drift", carried, "Previously-seen assertions still present.");

        sb.append("## Resolved since last run\n\n");
        if (result.join().resolvedIds().isEmpty()) {
            sb.append("_None._\n\n");
        } else {
            for (final String id : result.join().resolvedIds()) {
                sb.append("- `").append(id).append("` — auto-closed (no longer present).\n");
            }
            sb.append('\n');
        }

        sb.append("---\n\n");
        sb.append("Unverifiable results are in `quiet-log.md`; line-reference fixes are in ")
                .append("`auto-fix.md`; the semantic worklist is in `worklist.md`. ")
                .append("The machine artifact is `findings.json`.\n");
        return sb.toString();
    }

    /**
     * Appends one report section, grouping items by entry key.
     *
     * @param sb    the buffer to append to.
     * @param title the section heading.
     * @param items the findings to render in the section.
     * @param blurb the italic description under the heading.
     */
    private static void section(
            final StringBuilder sb, final String title, final List<BaselineJoin.Joined> items, final String blurb) {
        sb.append("## ").append(title).append("\n\n");
        sb.append('_').append(blurb).append("_\n\n");
        if (items.isEmpty()) {
            sb.append("_None._\n\n");
            return;
        }
        String currentEntry = null;
        for (final BaselineJoin.Joined j : items) {
            final Finding f = j.finding();
            if (!f.entryKey().equals(currentEntry)) {
                currentEntry = f.entryKey();
                sb.append("### ").append(currentEntry).append("  \n");
                sb.append("`").append(f.entryPath()).append("`\n\n");
            }
            sb.append("- **")
                    .append(label(f))
                    .append("** (")
                    .append(f.kind().name().toLowerCase().replace('_', ' '))
                    .append(") `")
                    .append(f.target())
                    .append("` — id `")
                    .append(f.id())
                    .append("`\n");
            sb.append("  - ").append(f.evidence()).append('\n');
            sb.append("  - occurrences (").append(f.occurrenceCount()).append("): ");
            final List<String> occ = new ArrayList<>();
            for (final Occurrence o : f.occurrences()) {
                final String cited = o.citedLine() < 0 ? "" : ":" + o.citedLine();
                occ.add("line " + o.docLine() + cited);
            }
            sb.append(String.join("; ", occ)).append('\n');
        }
        sb.append('\n');
    }

    /**
     * Returns the display label for a finding: {@code GONE} when absent, otherwise {@code MOVED}.
     *
     * @param f the finding.
     * @return the label.
     */
    private static String label(final Finding f) {
        return f.outcome() == Outcome.ABSENT ? "GONE" : "MOVED";
    }

    /**
     * Counts the findings assigned to a given lane.
     *
     * @param result the run result.
     * @param lane   the lane to count.
     * @return the number of findings in that lane.
     */
    private static long countLane(final RunResult result, final Lane lane) {
        return result.findings().stream().filter(f -> f.lane() == lane).count();
    }
}
