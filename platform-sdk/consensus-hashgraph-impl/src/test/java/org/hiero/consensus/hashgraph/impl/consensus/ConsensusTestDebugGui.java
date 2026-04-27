// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.gui.api.ListEventProvider;
import org.hiero.consensus.gui.api.TestGuiSource;
import org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.ConsensusTestOrchestrator;
import org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.framework.ConsensusTestNode;

/**
 * Bridge class that launches the hashgraph GUI from a consensus test.
 * <p>
 * Usage: add {@code ConsensusTestDebugGui.runGui(orchestrator)} to a test definition method (e.g.,
 * {@code ConsensusTestDefinitions.partitionTests()}) to visualize the event graph the test produces. Remove after
 * debugging.
 * <p>
 * **IMPORTANT**: Most consensus tests clear events throughout the run using
 * {@code ConsensusTestOrchestrator.validateAndClear()}. For the GUI to work correctly, the call must be changed to
 * {@code ConsensusTestOrchestrator.validate()} so that all required events are available to the GUI's consensus
 * instance.
 */
public final class ConsensusTestDebugGui {
    private ConsensusTestDebugGui() {}

    /**
     * Launches the GUI with the events collected by the given orchestrator. Blocks until the GUI window is closed.
     *
     * @param orchestrator the test orchestrator whose events should be visualized
     */
    @SuppressWarnings("unused") // useful for debugging
    public static void runGui(@NonNull final ConsensusTestOrchestrator orchestrator) {
        final ConsensusTestNode node =
                orchestrator.getNodes().stream().findAny().orElseThrow();
        new TestGuiSource(
                        orchestrator.getPlatformContext().getMetrics(),
                        orchestrator.getPlatformContext().getConfiguration(),
                        orchestrator.getRoster(),
                        new ListEventProvider(node.getOutput().getAddedEvents()))
                .runGui();
    }
}
