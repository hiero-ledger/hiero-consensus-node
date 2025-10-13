// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import static com.hedera.statevalidation.analyzer.StateAnalyzer.analyzeKeyToPathValueStorage;
import static com.hedera.statevalidation.analyzer.StateAnalyzer.analyzePathToHashStorage;
import static com.hedera.statevalidation.analyzer.StateAnalyzer.analyzePathToKeyValueStorage;
import static java.util.Objects.requireNonNull;

import com.hedera.statevalidation.parameterresolver.StateResolver;
import com.hedera.statevalidation.reporting.Report;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.virtualmap.VirtualMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine;

@CommandLine.Command(name = "analyze", description = "Analyzes the state and generates detailed report.")
public class AnalyzeCommand implements Runnable {

    private static final Logger log = LogManager.getLogger(AnalyzeCommand.class);

    @CommandLine.ParentCommand
    private StateOperatorCommand parent;

    @CommandLine.Option(
            names = {"-p2kv", "--path-to-kv"},
            description = "Analyze path to key-value storage.")
    private boolean analyzePathToKeyValueStorage;

    @CommandLine.Option(
            names = {"-k2p", "--key-to-path"},
            description = "Analyze key to path storage.")
    private boolean analyzeKeyToPathStorage;

    @CommandLine.Option(
            names = {"-p2h", "--path-to-hash"},
            description = "Analyze path to hash storage.")
    private boolean analyzePathToHashStorage;

    private AnalyzeCommand() {}

    @Override
    public void run() {
        System.setProperty("state.dir", parent.getStateDir().getAbsolutePath());

        final VirtualMap virtualMap;
        try {
            final DeserializedSignedState deserializedSignedState = StateResolver.initState();
            final MerkleNodeState state =
                    deserializedSignedState.reservedSignedState().get().getState();
            virtualMap = (VirtualMap) state.getRoot();
            requireNonNull(virtualMap);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        final MerkleDbDataSource vds = (MerkleDbDataSource) virtualMap.getDataSource();
        requireNonNull(vds);
        final Report report = new Report();

        // Check flags to pick the branch to run
        boolean anyFlagSet = analyzePathToKeyValueStorage || analyzeKeyToPathStorage || analyzePathToHashStorage;

        // Run all analysis methods if no flags are provided
        if (!anyFlagSet) {
            analyzePathToKeyValueStorage(report, vds);
            analyzeKeyToPathValueStorage(report, vds);
            analyzePathToHashStorage(report, vds);
        } else {
            // Run only the requested validations
            if (analyzePathToKeyValueStorage) {
                analyzePathToKeyValueStorage(report, vds);
            }
            if (analyzeKeyToPathStorage) {
                analyzeKeyToPathValueStorage(report, vds);
            }
            if (analyzePathToHashStorage) {
                analyzePathToHashStorage(report, vds);
            }
        }

        log.info(report);
    }
}
