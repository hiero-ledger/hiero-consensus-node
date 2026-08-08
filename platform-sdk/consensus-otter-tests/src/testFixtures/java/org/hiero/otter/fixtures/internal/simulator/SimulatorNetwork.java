// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.simulator;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Random;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.otter.fixtures.internal.AbstractNetwork;
import org.hiero.otter.fixtures.internal.AbstractTimeManager.TimeTickReceiver;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;
import org.hiero.otter.fixtures.network.Topology.ConnectionState;
import org.hiero.otter.fixtures.network.simulation.SimulatedNetwork;

public abstract class SimulatorNetwork extends AbstractNetwork implements TimeTickReceiver {

    private final SimulatorTimeManager timeManager;
    private final SimulatorTransactionGenerator transactionGenerator;
    private final SimulatedNetwork simulatedNetwork;

    protected SimulatorNetwork(
            @NonNull final Random random,
            @NonNull final SimulatorTimeManager timeManager,
            @NonNull final SimulatorTransactionGenerator transactionGenerator,
            final boolean useRandomNodeIds) {
        super(random, useRandomNodeIds);
        this.timeManager = requireNonNull(timeManager);
        this.transactionGenerator = requireNonNull(transactionGenerator);
        this.simulatedNetwork = new SimulatedNetwork(this.random);
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
    protected SimulatorTransactionGenerator transactionGenerator() {
        return transactionGenerator;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    protected SimulatedNetwork simulatedNetwork() {
        return simulatedNetwork;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onConnectionsChanged(@NonNull final Map<ConnectionKey, ConnectionState> connections) {
        simulatedNetwork.setConnections(connections);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void recreateConnections(@NonNull final Map<ConnectionKey, ConnectionState> connections) {
        simulatedNetwork.setConnections(connections);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doSendQuiescenceCommand(@NonNull final QuiescenceCommand command, @NonNull final Duration timeout) {
        nodes().forEach(node -> node.sendQuiescenceCommand(command));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tick(@NonNull final Instant now) {
        if (lifecycle != Lifecycle.RUNNING) {
            return;
        }

        simulatedNetwork().tick(now);
        transactionGenerator().tick(now, nodes());
    }

    /**
     * Shuts down the network and cleans up resources. Once this method is called, the network cannot be started again.
     * This method is idempotent and can be called multiple times without any side effects.
     */
    protected void destroy() {
        transactionGenerator.stop();
    }
}
