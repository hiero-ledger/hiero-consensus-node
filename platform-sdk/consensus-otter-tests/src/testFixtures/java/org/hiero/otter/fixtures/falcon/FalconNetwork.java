// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import com.hedera.hapi.node.base.SemanticVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.internal.AbstractTimeManager.TimeTickReceiver;
import org.hiero.otter.fixtures.internal.simulator.SimulatedNetwork;
import org.hiero.otter.fixtures.internal.simulator.SimulatorTimeManager;
import org.hiero.otter.fixtures.network.transactions.OtterTransaction;

/**
 * An implementation of {@link Network} that is based on the Falcon framework.
 */
public class FalconNetwork extends SimulatedNetwork implements TimeTickReceiver {

    /**
     * The keys and certificates of every node ever created by a Falcon network in this JVM.
     *
     * <p>Generating them is by far the most expensive part of setting up a network, and a sweep would otherwise pay
     * that cost on every repetition. Caching is safe because {@code KeysAndCertsGenerator} derives the keys
     * deterministically from the node ID, so a cached entry is exactly what a fresh generation would produce.
     */
    private static final Map<NodeId, KeysAndCerts> KEYS_AND_CERTS_CACHE = new ConcurrentHashMap<>();

    /**
     * Constructor for {@code FalconNetwork}.
     *
     * @param random the random number generator
     */
    protected FalconNetwork(
            @NonNull final Random random,
            @NonNull final SimulatorTimeManager timeManager,
            @NonNull final TransactionGenerator transactionGenerator) {
        super(random, timeManager, transactionGenerator, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected Node doCreateNode(@NonNull final NodeId nodeId, @NonNull final KeysAndCerts keysAndCerts) {
        final FalconNode node = new FalconNode(
                random,
                timeManager,
                nodeId,
                keysAndCerts,
                simulatedNetworkConnectivity,
                networkConfiguration,
                consensusRoundPool);
        simulatedNetworkConnectivity.addNode(nodeId, node);
        return node;
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
     *
     * <p>Serves the keys and certificates from {@link #KEYS_AND_CERTS_CACHE}, generating only the ones not cached yet.
     */
    @Override
    @NonNull
    protected Map<NodeId, KeysAndCerts> createKeysAndCerts(@NonNull final List<NodeId> nodeIds) {
        final List<NodeId> missing = nodeIds.stream()
                .filter(nodeId -> !KEYS_AND_CERTS_CACHE.containsKey(nodeId))
                .toList();
        if (!missing.isEmpty()) {
            // Generated in a single call, because the generator parallelizes across the requested nodes
            KEYS_AND_CERTS_CACHE.putAll(super.createKeysAndCerts(missing));
        }

        final Map<NodeId, KeysAndCerts> result = new LinkedHashMap<>();
        nodeIds.forEach(nodeId -> result.put(nodeId, KEYS_AND_CERTS_CACHE.get(nodeId)));
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doSendQuiescenceCommand(@NonNull final QuiescenceCommand command, @NonNull final Duration timeout) {
        throw new UnsupportedOperationException("Quiescence command is not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doFreeze(@NonNull final Duration timeout) {
        throw new UnsupportedOperationException("Freezing is not supported in FalconNetwork.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doShutdown(@NonNull final Duration timeout) {
        throw new UnsupportedOperationException("Shutting the network down is not supported in FalconNetwork.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doTriggerCatastrophicIss(@NonNull final Duration timeout) {
        throw new UnsupportedOperationException("Catastrophic ISS is not supported in FalconNetwork.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void submitTransactions(@NonNull final List<OtterTransaction> transactions) {
        throw new UnsupportedOperationException("Transactions are not supported in FalconNetwork.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void savedStateDirectory(@NonNull final Path savedStateDirectory) {
        throw new UnsupportedOperationException("Saved states are not supported in FalconNetwork.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void version(@NonNull final SemanticVersion version) {
        throw new UnsupportedOperationException("Versions are not supported in FalconNetwork.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bumpConfigVersion() {
        throw new UnsupportedOperationException("Versions are not supported in FalconNetwork.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tick(@NonNull final Instant now) {
        if (lifecycle != Lifecycle.RUNNING) {
            return;
        }

        simulatedNetworkConnectivity.tick(now);

        for (final Node node : nodes()) {
            final FalconNode falconNode = (FalconNode) node;
            falconNode.tick(now);
        }
    }
}
