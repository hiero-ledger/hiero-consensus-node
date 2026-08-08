// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.internal.AbstractTimeManager.TimeTickReceiver;
import org.hiero.otter.fixtures.internal.result.ConsensusRoundPool;
import org.hiero.otter.fixtures.internal.simulator.SimulatorNetwork;
import org.hiero.otter.fixtures.internal.simulator.SimulatorTimeManager;
import org.hiero.otter.fixtures.internal.simulator.SimulatorTransactionGenerator;
import org.hiero.otter.fixtures.logging.context.NodeLoggingContext;
import org.hiero.otter.fixtures.logging.context.NodeLoggingContext.LoggingContextScope;
import org.hiero.otter.fixtures.turtle.TurtleNode;

public class FalconNetwork extends SimulatorNetwork implements TimeTickReceiver {

    /**
     * Constructor for {@code FalconNetwork}.
     *
     * @param random the random number generator
     */
    protected FalconNetwork(
            @NonNull final Random random,
            @NonNull final SimulatorTimeManager timeManager,
            @NonNull final SimulatorTransactionGenerator transactionGenerator) {
        super(random, timeManager, transactionGenerator, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected Node doCreateNode(@NonNull final NodeId nodeId, @NonNull final KeysAndCerts keysAndCerts) {
        return new FalconNode(
                random, timeManager, nodeId, keysAndCerts, simulatedNetwork, networkConfiguration, consensusRoundPool);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected InstrumentedNode doCreateInstrumentedNode(
            @NonNull final NodeId nodeId, @NonNull final KeysAndCerts keysAndCerts) {
        throw new UnsupportedOperationException("Instrumented nodes are not supported in FalconNetwork");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tick(@NonNull final Instant now) {
        super.tick(now);

        if (lifecycle != Lifecycle.RUNNING) {
            return;
        }

        for (final Node node : nodes()) {
            final FalconNode falconNode = (FalconNode) node;
            falconNode.tick(now);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void destroy() {
        super.destroy();
    }
}
