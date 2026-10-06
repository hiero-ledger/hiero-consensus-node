// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.engine;

import java.util.List;
import org.hiero.consensus.kbfreshness.extract.KbDocument;
import org.hiero.consensus.kbfreshness.findings.BaselineJoin;
import org.hiero.consensus.kbfreshness.git.Git;
import org.hiero.consensus.kbfreshness.model.Finding;
import org.hiero.consensus.kbfreshness.model.Lane;
import org.hiero.consensus.kbfreshness.model.Triage;
import org.hiero.consensus.kbfreshness.resolve.SourceIndex;
import org.hiero.consensus.kbfreshness.worklist.WorklistEntry;

/**
 * The full deterministic output of a run: the scanned documents, the collapsed findings, the baseline
 * join, the semantic worklist, the source index, the scan-coverage stats, and the git wrapper the run
 * probed (carried so a caller reuses the single probe rather than building a second one). Renderers turn
 * this into the report, quiet log, auto-fix proposals, machine artifact, worklist, and suggestions.
 *
 * @param documents   the scanned KB documents.
 * @param findings    the collapsed findings.
 * @param join        the baseline join result.
 * @param worklist    the semantic worklist.
 * @param sourceIndex the source index (for near-name suggestions on gone sources).
 * @param stats       the scan-coverage statistics (what was scanned and checked).
 * @param git         the git wrapper built for this run (rename/deletion detection for suggestions).
 * @param lineSuggestions body-line and past-EOF source references that cannot migrate to a symbol, with
 *                    their enclosing declaration — rendered as advisory suggestions.
 */
public record RunResult(
        List<KbDocument> documents,
        List<Finding> findings,
        BaselineJoin.Result join,
        List<WorklistEntry> worklist,
        SourceIndex sourceIndex,
        ScanStats stats,
        Git git,
        List<LineSuggestion> lineSuggestions) {

    /**
     * The count of findings that assert new drift: assert-lane, not previously baselined, and not
     * dismissed — the primary drift signal, computed here so the CLI does not re-derive it.
     *
     * @return the number of new asserted-drift findings.
     */
    public long newDriftCount() {
        return join.joined().stream()
                .filter(j -> j.finding().lane() == Lane.ASSERT && j.isNew() && j.triage() != Triage.DISMISSED)
                .count();
    }
}
