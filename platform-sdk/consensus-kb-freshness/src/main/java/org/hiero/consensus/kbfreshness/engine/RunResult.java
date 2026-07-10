// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.engine;

import java.util.List;
import org.hiero.consensus.kbfreshness.extract.KbDocument;
import org.hiero.consensus.kbfreshness.findings.BaselineJoin;
import org.hiero.consensus.kbfreshness.model.Finding;
import org.hiero.consensus.kbfreshness.resolve.SourceIndex;
import org.hiero.consensus.kbfreshness.worklist.WorklistEntry;

/**
 * The full deterministic output of a run: the scanned documents, the collapsed findings, the baseline
 * join, the semantic worklist, the source index, and the scan-coverage stats. Renderers turn this into
 * the report, quiet log, auto-fix proposals, machine artifact, worklist, and did-you-mean suggestions.
 *
 * @param documents   the scanned KB documents.
 * @param findings    the collapsed findings.
 * @param join        the baseline join result.
 * @param worklist    the semantic worklist.
 * @param sourceIndex the source index (for near-name suggestions on gone sources).
 * @param stats       the scan-coverage statistics (what was scanned and checked).
 */
public record RunResult(
        List<KbDocument> documents,
        List<Finding> findings,
        BaselineJoin.Result join,
        List<WorklistEntry> worklist,
        SourceIndex sourceIndex,
        ScanStats stats) {}
