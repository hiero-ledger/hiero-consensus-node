// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.simulator;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.internal.AbstractNetwork;
import org.hiero.otter.fixtures.internal.AbstractTimeManager.TimeTickReceiver;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;
import org.hiero.otter.fixtures.internal.result.ConsensusRoundPool;
import org.hiero.otter.fixtures.network.Topology.ConnectionState;
import org.hiero.otter.fixtures.network.simulation.EventReceiver;
import org.hiero.otter.fixtures.network.simulation.SimulatedNetworkConnectivity;

/**
 * An abstract base class for a simulated network environment. This class provides the core functionality for managing time, transactions, and network connections in a simulated environment.
 */
public abstract class SimulatedNetwork extends AbstractNetwork implements TimeTickReceiver {

    protected final SimulatorTimeManager timeManager;
    protected final TransactionGenerator transactionGenerator;
    protected final SimulatedNetworkConnectivity simulatedNetworkConnectivity;
    protected final ConsensusRoundPool consensusRoundPool = new ConsensusRoundPool();

    /**
     * The event receiver of every node created so far. The simulated network is set up with all of them at once when
     * the network starts.
     */
    private final List<EventReceiver> eventReceivers = new ArrayList<>();

    /**
     * Constructor for SimulatorNetwork.
     *
     * @param random the random number generator
     * @param timeManager the time manager
     * @param transactionGenerator the transaction generator
     * @param useRandomNodeIds whether to use random node IDs
     */
    protected SimulatedNetwork(
            @NonNull final Random random,
            @NonNull final SimulatorTimeManager timeManager,
            @NonNull final TransactionGenerator transactionGenerator,
            final boolean useRandomNodeIds) {
        super(random, useRandomNodeIds);
        this.timeManager = requireNonNull(timeManager);
        this.transactionGenerator = requireNonNull(transactionGenerator);
        this.simulatedNetworkConnectivity = new SimulatedNetworkConnectivity(this.random, timeManager.now());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected SimulatorTimeManager timeManager() {
        return timeManager;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected TransactionGenerator transactionGenerator() {
        return transactionGenerator;
    }

    /**
     * Registers the event receiver of a newly created node, so that the node becomes part of the simulated network when
     * the network starts.
     *
     * @param eventReceiver the event receiver of the node
     */
    protected void addEventReceiver(@NonNull final EventReceiver eventReceiver) {
        eventReceivers.add(requireNonNull(eventReceiver));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sets up the simulated network for every node that has been created.
     */
    @Override
    protected void preStartHook(@NonNull final Roster roster) {
        simulatedNetworkConnectivity.setUp(eventReceivers);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onConnectionsChanged(@NonNull final Map<ConnectionKey, ConnectionState> connections) {
        final boolean limited = connections.values().stream()
                .anyMatch(state -> !state.bandwidthLimit().isUnlimited());
        if (limited) {
            throw new UnsupportedOperationException("Bandwidth limits are not supported in this environment.");
        }
        simulatedNetworkConnectivity.setConnections(connections);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void recreateConnections(@NonNull final Map<ConnectionKey, ConnectionState> connections) {
        simulatedNetworkConnectivity.setConnections(connections);
    }

    /**
     * Shuts down the network and cleans up resources. Once this method is called, the network cannot be started again.
     * This method is idempotent and can be called multiple times without any side effects.
     */
    public void destroy() {
        transactionGenerator.stop();
    }
}
