// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.engine;

import java.util.ArrayList;
import java.util.List;
import org.hiero.consensus.kbfreshness.extract.AnchorExtractor;
import org.hiero.consensus.kbfreshness.extract.KbDocument;
import org.hiero.consensus.kbfreshness.extract.KbScanner;
import org.hiero.consensus.kbfreshness.findings.Baseline;
import org.hiero.consensus.kbfreshness.findings.BaselineJoin;
import org.hiero.consensus.kbfreshness.findings.FindingAssembler;
import org.hiero.consensus.kbfreshness.findings.InterfaceDiffAssembler;
import org.hiero.consensus.kbfreshness.git.Git;
import org.hiero.consensus.kbfreshness.model.Finding;
import org.hiero.consensus.kbfreshness.resolve.AnchorResolver;
import org.hiero.consensus.kbfreshness.resolve.SourceIndex;
import org.hiero.consensus.kbfreshness.worklist.WorklistBuilder;
import org.hiero.consensus.kbfreshness.worklist.WorklistEntry;

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
        final List<Finding> findings = new ArrayList<>(assembler.assembleAll(docs));
        findings.addAll(new InterfaceDiffAssembler(index).assembleAll(docs));
        findings.sort(FindingAssembler.ORDER);

        final Baseline baseline = Baseline.load(config.baselineFile());
        final BaselineJoin.Result join = BaselineJoin.join(findings, baseline, config.runDate());

        final Git git = new Git(config.repoRoot());
        final List<WorklistEntry> worklist = new WorklistBuilder(config.repoRoot(), extractor, git).build(docs);

        return new RunResult(docs, findings, join, worklist);
    }
}
