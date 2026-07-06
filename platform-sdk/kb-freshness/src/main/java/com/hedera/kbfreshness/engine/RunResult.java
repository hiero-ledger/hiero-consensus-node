// SPDX-License-Identifier: Apache-2.0
package com.hedera.kbfreshness.engine;

import com.hedera.kbfreshness.extract.KbDocument;
import com.hedera.kbfreshness.findings.BaselineJoin;
import com.hedera.kbfreshness.model.Finding;
import com.hedera.kbfreshness.worklist.WorklistEntry;
import java.util.List;

/**
 * The full deterministic output of a run: the scanned documents, the collapsed findings, the baseline
 * join, and the semantic worklist. Renderers turn this into the report, quiet log, auto-fix proposals,
 * machine artifact, and worklist.
 */
public record RunResult(
        List<KbDocument> documents, List<Finding> findings, BaselineJoin.Result join, List<WorklistEntry> worklist) {}
