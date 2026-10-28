// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.security.KeyStoreException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import org.hiero.base.crypto.SigningSchema;
import org.hiero.consensus.benchmark.tools.event.EventShape;
import org.hiero.consensus.fakes.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.MiniNode;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.internal.AbstractMiniNode;
import org.hiero.otter.fixtures.internal.AbstractTimeManager.TimeTickReceiver;
import org.hiero.otter.fixtures.internal.simulator.SimulatedNetwork;
import org.hiero.otter.fixtures.internal.simulator.SimulatorTimeManager;
import org.hiero.otter.fixtures.network.transactions.OtterTransaction;

/**
 * An implementation of {@link Network} that is based on the Falcon framework.
 */
public class FalconNetwork extends SimulatedNetwork implements TimeTickReceiver {

    /**
     * The keys and certificates of every node ever created by a Falcon network in this JVM, by signing schema.
     *
     * <p>Generating them is by far the most expensive part of setting up a network, and a sweep would otherwise pay
     * that cost on every repetition. Caching is safe because {@code KeysAndCertsGenerator} derives the keys
     * deterministically from the node ID and the signing schema, so a cached entry is exactly what a fresh generation
     * would produce.
     */
    private static final Map<SigningSchema, Map<NodeId, KeysAndCerts>> KEYS_AND_CERTS_CACHE = new ConcurrentHashMap<>();

    /** The shape the transactions of created events are drawn from, or {@code null} for events without any. */
    @Nullable
    private EventShape transactionShape;

    /** The schema the nodes sign their events with, or {@code null} if they do not sign them. */
    @Nullable
    private SigningSchema signingSchema;

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
     * Gives every event a node creates transactions drawn from a shape, with {@link EventShape#sampleTransactions}.
     * Only the transaction tables of the shape are used; the event creator picks the parents. Without this setting,
     * events carry no transactions.
     *
     * @param shape the shape to draw the transactions from
     * @throws IllegalStateException if nodes have been added already
     */
    public void transactions(@NonNull final EventShape shape) {
        throwIfNodesAdded("Transactions must be configured before nodes are added.");
        this.transactionShape = requireNonNull(shape);
    }

    /**
     * Makes every node sign its events with its own key, of the given schema. Without this setting, the nodes have
     * {@link SigningSchema#ED25519} keys and their events carry an empty signature.
     *
     * @param signingSchema the schema of the nodes' keys and signatures
     * @throws IllegalStateException if nodes have been added already, because their keys are generated when they are
     * added
     */
    public void signingSchema(@NonNull final SigningSchema signingSchema) {
        throwIfNodesAdded("The signing schema must be configured before nodes are added.");
        this.signingSchema = requireNonNull(signingSchema);
    }

    /**
     * Returns the keys and certificates of all nodes, full nodes and mini-nodes alike.
     *
     * @return the keys and certificates, by node ID, in the order of the nodes
     */
    @NonNull
    public Map<NodeId, KeysAndCerts> keysAndCerts() {
        final Map<NodeId, KeysAndCerts> result = new LinkedHashMap<>();
        for (final MiniNode node : allNodes()) {
            result.put(node.selfId(), ((AbstractMiniNode) node).keysAndCerts());
        }
        return result;
    }

    private void throwIfNodesAdded(@NonNull final String message) {
        throwIfNotInLifecycle(Lifecycle.INIT, message);
        if (!allNodes().isEmpty()) {
            throw new IllegalStateException(message);
        }
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
                consensusRoundPool,
                transactionShape,
                signingSchema != null);
        simulatedNetworkConnectivity.addNode(node.getNodeId(), node);
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
     */
    @Override
    @NonNull
    protected MiniNode doCreateMiniNode(@NonNull final NodeId nodeId, @NonNull final KeysAndCerts keysAndCerts) {
        final FalconMiniNode node = new FalconMiniNode(
                random,
                timeManager.time(),
                nodeId,
                keysAndCerts,
                simulatedNetworkConnectivity,
                networkConfiguration,
                transactionShape,
                signingSchema != null);
        simulatedNetworkConnectivity.addNode(node.getNodeId(), node);
        return node;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Serves the keys and certificates from {@link #KEYS_AND_CERTS_CACHE}, generating only the ones not cached yet.
     * The keys are of the configured {@link #signingSchema(SigningSchema) signing schema}. Without one, they are
     * generated with {@link SigningSchema#ED25519}: the nodes do not sign, and the roster only carries the signing
     * certificate, so the much slower RSA scheme used by the other environments buys nothing.
     */
    @Override
    @NonNull
    protected Map<NodeId, KeysAndCerts> createKeysAndCerts(@NonNull final List<NodeId> nodeIds) {
        final SigningSchema schema = signingSchema != null ? signingSchema : SigningSchema.ED25519;
        final Map<NodeId, KeysAndCerts> cache =
                KEYS_AND_CERTS_CACHE.computeIfAbsent(schema, _ -> new ConcurrentHashMap<>());
        final List<NodeId> missing =
                nodeIds.stream().filter(nodeId -> !cache.containsKey(nodeId)).toList();
        if (!missing.isEmpty()) {
            try {
                // Generated in a single call, because the generator parallelizes across the requested nodes
                cache.putAll(KeysAndCertsGenerator.generateKeysAndCerts(missing, schema));
            } catch (final ExecutionException | InterruptedException | KeyStoreException e) {
                throw new RuntimeException("Exception while generating KeysAndCerts", e);
            }
        }

        final Map<NodeId, KeysAndCerts> result = new LinkedHashMap<>();
        nodeIds.forEach(nodeId -> result.put(nodeId, cache.get(nodeId)));
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

        for (final MiniNode node : allNodes()) {
            final TimeTickReceiver tickReceiver = (TimeTickReceiver) node;
            tickReceiver.tick(now);
        }
    }
}
