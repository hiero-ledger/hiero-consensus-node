// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.simulator;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.internal.AbstractNetwork;
import org.hiero.otter.fixtures.internal.AbstractTimeManager.TimeTickReceiver;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;
import org.hiero.otter.fixtures.internal.result.ConsensusRoundPool;
import org.hiero.otter.fixtures.network.Topology.ConnectionState;
import org.hiero.otter.fixtures.network.simulation.EventReceiver;
import org.hiero.otter.fixtures.network.simulation.GossipNetwork;
import org.hiero.otter.fixtures.network.simulation.SimulatedNetworkConnectivity;

/**
 * An abstract base class for a simulated network environment. This class provides the core functionality for managing time, transactions, and network connections in a simulated environment.
 */
public abstract class SimulatedNetwork extends AbstractNetwork implements TimeTickReceiver {

    protected final SimulatorTimeManager timeManager;
    protected final TransactionGenerator transactionGenerator;
    protected final ConsensusRoundPool consensusRoundPool = new ConsensusRoundPool();

    /**
     * The event receiver of every node created so far. The simulated network is set up with all of them at once when
     * the network starts.
     */
    private final List<EventReceiver> eventReceivers = new ArrayList<>();

    /**
     * The gossip network every node is given. It forwards to {@link #simulatedNetworkConnectivity}, so that nodes can
     * be created before the simulated network exists and keep working when it is replaced on a restart.
     */
    protected final GossipNetwork gossipNetwork = new ForwardingGossipNetwork();

    /**
     * Runs the simulated network while it is running. Created with every node when the network starts, and discarded
     * when the network is shut down. {@code null} while the network is not running.
     */
    @Nullable
    private SimulatedNetworkConnectivity simulatedNetworkConnectivity;

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
     * Gets the simulated network the nodes are connected by.
     *
     * @return the simulated network
     * @throws IllegalStateException if the network is not running
     */
    @NonNull
    protected SimulatedNetworkConnectivity simulatedNetworkConnectivity() {
        if (simulatedNetworkConnectivity == null) {
            throw new IllegalStateException("The simulated network only exists while the network is running");
        }
        return simulatedNetworkConnectivity;
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
     * <p>Creates the simulated network with every node that has been created. Its connections are set right after
     * this hook, before any node starts.
     */
    @Override
    protected void preStartHook(@NonNull final Roster roster) {
        simulatedNetworkConnectivity = new SimulatedNetworkConnectivity(random, timeManager.now(), eventReceivers);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Discards the simulated network together with everything still in flight. A restarted network runs on a new
     * one.
     */
    @Override
    protected void doShutdown(@NonNull final Duration timeout) {
        super.doShutdown(timeout);
        simulatedNetworkConnectivity = null;
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
        // Changes made while the network is not running are picked up when it starts
        if (simulatedNetworkConnectivity != null) {
            simulatedNetworkConnectivity.setConnections(connections);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void recreateConnections(@NonNull final Map<ConnectionKey, ConnectionState> connections) {
        if (simulatedNetworkConnectivity != null) {
            simulatedNetworkConnectivity.setConnections(connections);
        }
    }

    /**
     * Shuts down the network and cleans up resources. Once this method is called, the network cannot be started again.
     * This method is idempotent and can be called multiple times without any side effects.
     */
    public void destroy() {
        transactionGenerator.stop();
    }

    /**
     * Forwards everything the nodes hand to the network to the simulated network that is currently running.
     */
    private final class ForwardingGossipNetwork implements GossipNetwork {

        /**
         * {@inheritDoc}
         */
        @Override
        public void submitEvent(@NonNull final PlatformEvent event) {
            simulatedNetworkConnectivity().submitEvent(event);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void updateEventWindow(@NonNull final NodeId nodeId, @NonNull final EventWindow eventWindow) {
            simulatedNetworkConnectivity().updateEventWindow(nodeId, eventWindow);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void resetCursor(@NonNull final NodeId nodeId) {
            simulatedNetworkConnectivity().resetCursor(nodeId);
        }
    }
}
