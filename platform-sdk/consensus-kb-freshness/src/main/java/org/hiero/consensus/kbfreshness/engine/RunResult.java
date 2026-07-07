// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.engine;

import java.util.List;
import org.hiero.consensus.kbfreshness.extract.KbDocument;
import org.hiero.consensus.kbfreshness.findings.BaselineJoin;
import org.hiero.consensus.kbfreshness.model.Finding;
import org.hiero.consensus.kbfreshness.worklist.WorklistEntry;

/**
 * The full deterministic output of a run: the scanned documents, the collapsed findings, the baseline
 * join, and the semantic worklist. Renderers turn this into the report, quiet log, auto-fix proposals,
 * machine artifact, and worklist.
 */
public record RunResult(
        List<KbDocument> documents, List<Finding> findings, BaselineJoin.Result join, List<WorklistEntry> worklist) {}
