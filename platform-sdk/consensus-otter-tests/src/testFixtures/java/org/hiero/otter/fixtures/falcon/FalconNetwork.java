// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Random;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.internal.AbstractTimeManager.TimeTickReceiver;
import org.hiero.otter.fixtures.internal.simulator.SimulatorNetwork;
import org.hiero.otter.fixtures.internal.simulator.SimulatorTimeManager;
import org.hiero.otter.fixtures.internal.simulator.SimulatorTransactionGenerator;

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
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected InstrumentedNode doCreateInstrumentedNode(
            @NonNull final NodeId nodeId, @NonNull final KeysAndCerts keysAndCerts) {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void preStartHook(@NonNull final Roster roster) {}

    /**
     * {@inheritDoc}
     */
    protected void destroy() {
        super.destroy();
    }
}
