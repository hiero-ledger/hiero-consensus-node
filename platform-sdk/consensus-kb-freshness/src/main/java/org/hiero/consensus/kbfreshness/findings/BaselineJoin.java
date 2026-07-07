// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.findings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hiero.consensus.kbfreshness.model.Finding;
import org.hiero.consensus.kbfreshness.model.Triage;

/**
 * Joins the current finding set against the baseline by finding {@code id}: an id only in the current
 * set is <em>new</em> (the signal); an id in both carries its triage forward; an id only in the
 * baseline is <em>resolved</em> and auto-closed. Because identity is keyed on the KB's claim, a
 * {@code dismissed} finding whose KB text later changes gets a new id and re-surfaces as new — a
 * dismissal can never silently silence a different problem.
 */
public final class BaselineJoin {

    /**
     * A current finding annotated with its baseline join status.
     *
     * @param finding   the finding.
     * @param triage    its carried triage ({@code NEW} if not previously seen).
     * @param isNew     true if the id was not in the baseline.
     * @param firstSeen the run date first recorded (from the baseline, or {@code runDate} if new).
     */
    public record Joined(Finding finding, Triage triage, boolean isNew, String firstSeen) {}

    /**
     * @param joined            every current finding with its join status, in the input order.
     * @param resolvedIds       baseline ids no longer present this run (sorted).
     * @param proposedBaseline  the baseline to write back: carried entries plus new findings.
     */
    public record Result(List<Joined> joined, List<String> resolvedIds, List<BaselineEntry> proposedBaseline) {}

    /** Prevents instantiation of this static-only helper. */
    private BaselineJoin() {}

    /**
     * Joins the current findings against the baseline, carrying triage forward, marking new findings, and
     * collecting resolved ids.
     *
     * @param current  the current findings.
     * @param baseline the baseline to join against.
     * @param runDate  the run date recorded as {@code firstSeen} for new findings; {@code null} is treated
     *                 as empty.
     * @return the join result: annotated findings, resolved ids, and the proposed baseline.
     */
    public static Result join(final List<Finding> current, final Baseline baseline, final String runDate) {
        final List<Joined> joined = new ArrayList<>();
        final List<BaselineEntry> proposed = new ArrayList<>();
        final Set<String> currentIds = new HashSet<>();
        final String date = runDate == null ? "" : runDate;

        for (final Finding f : current) {
            currentIds.add(f.id());
            final BaselineEntry prior = baseline.get(f.id());
            if (prior != null) {
                joined.add(new Joined(f, prior.triage(), false, prior.firstSeen()));
                proposed.add(prior);
            } else {
                joined.add(new Joined(f, Triage.NEW, true, date));
                proposed.add(new BaselineEntry(f.id(), Triage.NEW, date, ""));
            }
        }

        final List<String> resolved = new ArrayList<>();
        for (final BaselineEntry e : baseline.entries()) {
            if (!currentIds.contains(e.id())) {
                resolved.add(e.id());
            }
        }
        resolved.sort(Comparator.naturalOrder());
        return new Result(joined, resolved, proposed);
    }
}
