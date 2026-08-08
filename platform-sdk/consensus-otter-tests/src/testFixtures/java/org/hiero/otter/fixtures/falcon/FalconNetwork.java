// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.internal.AbstractTimeManager.TimeTickReceiver;
import org.hiero.otter.fixtures.internal.simulator.SimulatorNetwork;
import org.hiero.otter.fixtures.internal.simulator.SimulatorTimeManager;

public class FalconNetwork extends SimulatorNetwork implements TimeTickReceiver {

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
    protected FalconNetwork(@NonNull final Random random, @NonNull final SimulatorTimeManager timeManager) {
        super(random, timeManager, new FalconTransactionGenerator(), false);
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
        if (lifecycle != Lifecycle.RUNNING) {
            return;
        }

        simulatedNetwork.tick(now);

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
