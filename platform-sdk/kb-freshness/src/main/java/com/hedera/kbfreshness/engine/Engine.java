// SPDX-License-Identifier: Apache-2.0
package com.hedera.kbfreshness.engine;

import com.hedera.kbfreshness.extract.AnchorExtractor;
import com.hedera.kbfreshness.extract.KbDocument;
import com.hedera.kbfreshness.extract.KbScanner;
import com.hedera.kbfreshness.findings.Baseline;
import com.hedera.kbfreshness.findings.BaselineJoin;
import com.hedera.kbfreshness.findings.FindingAssembler;
import com.hedera.kbfreshness.git.Git;
import com.hedera.kbfreshness.model.Finding;
import com.hedera.kbfreshness.resolve.AnchorResolver;
import com.hedera.kbfreshness.resolve.SourceIndex;
import com.hedera.kbfreshness.worklist.WorklistBuilder;
import com.hedera.kbfreshness.worklist.WorklistEntry;
import java.util.List;

/**
 * Orchestrates one deterministic run: scan the KB, index sources, extract and resolve anchors,
 * collapse findings, join the baseline, and build the semantic worklist. No model, no network.
 */
public final class Engine {

    /** The inputs for this run. */
    private final RunConfig config;

    /**
     * Creates an engine for the given run configuration.
     *
     * @param config the run inputs.
     */
    public Engine(final RunConfig config) {
        this.config = config;
    }

    /**
     * Executes the run and returns its full deterministic output.
     *
     * @return the scanned documents, collapsed findings, baseline join, and semantic worklist.
     */
    public RunResult run() {
        final KbScanner scanner = new KbScanner(config.repoRoot(), config.kbRoot());
        final List<KbDocument> docs = scanner.scan();

        final SourceIndex index = SourceIndex.build(config.repoRoot(), config.moduleRoots());
        final AnchorExtractor extractor = new AnchorExtractor(config.repoRoot(), config.kbRoot());
        final AnchorResolver resolver =
                new AnchorResolver(config.repoRoot(), config.kbRoot(), index, config.allowlist());
        final FindingAssembler assembler = new FindingAssembler(extractor, resolver);
        final List<Finding> findings = assembler.assembleAll(docs);

        final Baseline baseline = Baseline.load(config.baselineFile());
        final BaselineJoin.Result join = BaselineJoin.join(findings, baseline, config.runDate());

        final Git git = new Git(config.repoRoot());
        final List<WorklistEntry> worklist = new WorklistBuilder(config.repoRoot(), extractor, git).build(docs);

        return new RunResult(docs, findings, join, worklist);
    }
}
