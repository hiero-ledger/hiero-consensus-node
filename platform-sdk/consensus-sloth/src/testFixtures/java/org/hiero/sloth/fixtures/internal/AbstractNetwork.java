// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.internal;

import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.sloth.fixtures.internal.AbstractNode.UNSET_WEIGHT;
import static org.hiero.sloth.fixtures.network.MeshTopologyConfiguration.ZERO_LATENCY;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.platform.crypto.CryptoStatic;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.security.KeyStoreException;
import java.security.cert.CertificateEncodingException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.test.fixtures.WeightGenerator;
import org.hiero.consensus.test.fixtures.WeightGenerators;
import org.hiero.sloth.fixtures.AsyncNetworkActions;
import org.hiero.sloth.fixtures.Network;
import org.hiero.sloth.fixtures.Node;
import org.hiero.sloth.fixtures.TimeManager;
import org.hiero.sloth.fixtures.internal.network.ConnectionKey;
import org.hiero.sloth.fixtures.internal.network.MeshTopologyImpl;
import org.hiero.sloth.fixtures.internal.result.MultipleNodeLogResultsImpl;
import org.hiero.sloth.fixtures.internal.result.MultipleNodePlatformStatusResultsImpl;
import org.hiero.sloth.fixtures.network.Topology;
import org.hiero.sloth.fixtures.network.Topology.ConnectionState;
import org.hiero.sloth.fixtures.network.transactions.SlothTransaction;
import org.hiero.sloth.fixtures.result.MultipleNodeLogResults;
import org.hiero.sloth.fixtures.result.MultipleNodePlatformStatusResults;
import org.hiero.sloth.fixtures.result.SingleNodeLogResult;
import org.hiero.sloth.fixtures.result.SingleNodePlatformStatusResult;

/**
 * An abstract base class for a network implementation that provides common functionality shared by the different
 * environments.
 */
public abstract class AbstractNetwork implements Network {

    /**
     * The state of the network.
     */
    protected enum Lifecycle {
        INIT,
        RUNNING,
        SHUTDOWN
    }

    private static final Logger log = LogManager.getLogger();

    /** The format for node identifiers in the network. */
    public static final String NODE_IDENTIFIER_FORMAT = "node-%d";

    /** The default port for gossip communication. */
    private static final int GOSSIP_PORT = 5777;

    /** The default timeout duration for network operations. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2L);

    private final Random random;
    private final boolean useRandomNodeIds;

    private final Topology currentTopology;
    protected final NetworkConfiguration networkConfiguration;

    protected Lifecycle lifecycle = Lifecycle.INIT;

    protected WeightGenerator weightGenerator = WeightGenerators.REAL_NETWORK_GAUSSIAN;

    protected Roster roster;

    private NodeId nextNodeId = NodeId.FIRST_NODE_ID;

    protected AbstractNetwork(@NonNull final Random random, final boolean useRandomNodeIds) {
        this.random = requireNonNull(random);
        this.useRandomNodeIds = useRandomNodeIds;
        // Initialize with default GeoMeshTopology
        this.currentTopology = new MeshTopologyImpl(ZERO_LATENCY, this::createNodes);
        this.networkConfiguration = new NetworkConfiguration();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Topology topology() {
        return currentTopology;
    }

    /**
     * Returns the time manager for this network.
     *
     * @return the {@link TimeManager} instance
     */
    @NonNull
    protected abstract TimeManager timeManager();

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public AsyncNetworkActions withTimeout(@NonNull final Duration timeout) {
        return new AsyncNetworkActionsImpl(timeout);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void weightGenerator(@NonNull final WeightGenerator weightGenerator) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Cannot set weight generator when the network is running.");
        this.weightGenerator = requireNonNull(weightGenerator);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void nodeWeight(final long weight) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Cannot set weight generator when the network is running.");
        if (weight <= 0) {
            throw new IllegalArgumentException("Weight must be positive");
        }
        networkConfiguration.weight(weight);
        nodes().forEach(n -> n.weight(weight));
    }

    @Override
    public @NonNull Roster roster() {
        if (lifecycle == Lifecycle.INIT) {
            throw new IllegalStateException("The roster is not available before the network is started.");
        }
        return roster;
    }

    /**
     * Creates a new node with the given ID and keys and certificates. This is a factory method that subclasses must
     * implement to create nodes specific to the environment.
     *
     * @param nodeId the ID of the node to create
     * @param keysAndCerts the keys and certificates for the node
     * @return the newly created node
     */
    protected abstract Node doCreateNode(@NonNull final NodeId nodeId, @NonNull final KeysAndCerts keysAndCerts);

    private List<Node> createNodes(final int count) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Cannot add nodes while the network is running.");
        throwIfInLifecycle(Lifecycle.SHUTDOWN, "Cannot add nodes after the network has been started.");

        try {
            final List<NodeId> nodeIds =
                    IntStream.range(0, count).mapToObj(i -> getNextNodeId()).toList();
            return CryptoStatic.generateKeysAndCerts(nodeIds).entrySet().stream()
                    .map(e -> doCreateNode(e.getKey(), e.getValue()))
                    .toList();
        } catch (final ExecutionException | InterruptedException | KeyStoreException e) {
            throw new RuntimeException("Exception while generating KeysAndCerts", e);
        }
    }

    @NonNull
    private NodeId getNextNodeId() {
        final NodeId nextId = nextNodeId;
        // If enabled, advance by a random number of steps between 1 and 3
        final int randomAdvance = (useRandomNodeIds) ? random.nextInt(3) : 0;

        nextNodeId = nextNodeId.getOffset(randomAdvance + 1L);
        return nextId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        doStart(DEFAULT_TIMEOUT);
    }

    /**
     * A hook method that is called before the network is started.
     *
     * <p>Subclasses can override this method to add custom behavior before the network starts, such as initializing
     * resources or performing setup tasks. They can also modify the roster if needed.
     *
     * @param roster the preliminary roster generated for the network
     */
    protected abstract void preStartHook(@NonNull final Roster roster);

    private void doStart(@NonNull final Duration timeout) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Network is already running.");
        log.info("Starting network...");

        roster = createRoster();
        preStartHook(roster);

        lifecycle = Lifecycle.RUNNING;
        updateConnections();
        for (final Node node : nodes()) {
            ((AbstractNode) node).roster(roster);
            node.start();
        }

        log.debug("Waiting for nodes to become active...");
        timeManager().waitForCondition(() -> allNodesInStatus(ACTIVE), timeout);
        log.info("Network started.");
    }

    private Roster createRoster() {
        final boolean anyNodeHasExplicitWeight = nodes().stream().anyMatch(node -> node.weight() > 0);
        final List<RosterEntry> rosterEntries;
        if (anyNodeHasExplicitWeight) {
            rosterEntries = nodes().stream()
                    .sorted(Comparator.comparing(Node::selfId))
                    .map(node -> createRosterEntry(node, node.weight() == UNSET_WEIGHT ? 0 : node.weight()))
                    .toList();
        } else {
            final int count = nodes().size();
            final Iterator<Long> weightIterator =
                    weightGenerator.getWeights(random.nextLong(), count).iterator();

            rosterEntries = nodes().stream()
                    .sorted(Comparator.comparing(Node::selfId))
                    .map(node -> createRosterEntry(node, weightIterator.next()))
                    .toList();
        }
        return Roster.newBuilder().rosterEntries(rosterEntries).build();
    }

    private RosterEntry createRosterEntry(final Node node, final long weight) {
        try {
            final long id = node.selfId().id();
            final byte[] certificate =
                    ((AbstractNode) node).gossipCaCertificate().getEncoded();
            return RosterEntry.newBuilder()
                    .nodeId(id)
                    .weight(weight)
                    .gossipCaCertificate(Bytes.wrap(certificate))
                    .gossipEndpoint(ServiceEndpoint.newBuilder()
                            .domainName(String.format(NODE_IDENTIFIER_FORMAT, id))
                            .port(GOSSIP_PORT)
                            .build())
                    .build();
        } catch (final CertificateEncodingException e) {
            throw new RuntimeException("Exception while creating roster entry", e);
        }
    }

    /**
     * The actual implementation of sending a quiescence command, to be provided by subclasses.
     *
     * @param command the quiescence command to send
     * @param timeout the maximum duration to wait for the command to be processed
     */
    protected abstract void doSendQuiescenceCommand(@NonNull QuiescenceCommand command, @NonNull Duration timeout);

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendQuiescenceCommand(@NonNull final QuiescenceCommand command) {
        doSendQuiescenceCommand(command, DEFAULT_TIMEOUT);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ConnectionState connectionState(@NonNull final Node sender, @NonNull final Node receiver) {
        return topology().getConnectionData(sender, receiver);
    }

    /**
     * {@inheritDoc}
     */
    public void submitTransactions(@NonNull final List<SlothTransaction> transactions) {
        nodes().stream()
                .filter(Node::isActive)
                .findFirst()
                .map(node -> (AbstractNode) node)
                .orElseThrow(() -> new AssertionError("No active node found to send transaction to."))
                .submitTransactions(transactions);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, @NonNull final String value) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Configuration modification is not allowed when the network is running.");
        networkConfiguration.withConfigValue(key, value);
        nodes().forEach(node -> node.configuration().withConfigValue(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Network withConfigValue(@NonNull final String key, @NonNull final Duration value) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Configuration modification is not allowed when the network is running.");
        networkConfiguration.withConfigValue(key, value);
        nodes().forEach(node -> node.configuration().withConfigValue(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, final int value) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Configuration modification is not allowed when the network is running.");
        networkConfiguration.withConfigValue(key, value);
        nodes().forEach(node -> node.configuration().withConfigValue(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, final long value) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Configuration modification is not allowed when the network is running.");
        networkConfiguration.withConfigValue(key, value);
        nodes().forEach(node -> node.configuration().withConfigValue(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, @NonNull final Path value) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Configuration modification is not allowed when the network is running.");
        networkConfiguration.withConfigValue(key, value);
        nodes().forEach(node -> node.configuration().withConfigValue(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, final boolean value) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Configuration modification is not allowed when the network is running.");
        networkConfiguration.withConfigValue(key, value);
        nodes().forEach(node -> node.configuration().withConfigValue(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, final double value) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Configuration modification is not allowed when the network is running.");
        networkConfiguration.withConfigValue(key, value);
        nodes().forEach(node -> node.configuration().withConfigValue(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, @NonNull final Enum<?> value) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Configuration modification is not allowed when the network is running.");
        networkConfiguration.withConfigValue(key, value);
        nodes().forEach(node -> node.configuration().withConfigValue(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, @NonNull final List<String> values) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Configuration modification is not allowed when the network is running.");
        networkConfiguration.withConfigValue(key, values);
        nodes().forEach(node -> node.configuration().withConfigValue(key, values));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, @NonNull final TaskSchedulerConfiguration value) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Configuration modification is not allowed when the network is running.");
        networkConfiguration.withConfigValue(key, value);
        nodes().forEach(node -> node.configuration().withConfigValue(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void shutdown() {
        doShutdown(DEFAULT_TIMEOUT);
    }

    private void doShutdown(@NonNull final Duration timeout) {
        throwIfInLifecycle(Lifecycle.INIT, "Network has not been started yet.");
        throwIfInLifecycle(Lifecycle.SHUTDOWN, "Network has already been shut down.");

        log.info("Killing nodes immediately...");
        for (final Node node : nodes()) {
            node.killImmediately();
        }

        lifecycle = Lifecycle.SHUTDOWN;

        log.info("Nodes have been killed.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void version(@NonNull final SemanticVersion version) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Cannot set version while the network is running");
        networkConfiguration.version(version);
        nodes().forEach(node -> node.version(version));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bumpConfigVersion() {
        nodes().forEach(Node::bumpConfigVersion);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public MultipleNodeLogResults newLogResults() {
        final List<SingleNodeLogResult> results =
                nodes().stream().map(Node::newLogResult).toList();

        return new MultipleNodeLogResultsImpl(results);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MultipleNodePlatformStatusResults newPlatformStatusResults() {
        final List<SingleNodePlatformStatusResult> statusProgressions =
                nodes().stream().map(Node::newPlatformStatusResult).toList();
        return new MultipleNodePlatformStatusResultsImpl(statusProgressions);
    }

    /**
     * Throws an {@link IllegalStateException} if the network is in the given state.
     *
     * @param expected the state that will cause the exception to be thrown
     * @param message the message to include in the exception
     * @throws IllegalStateException if the network is in the expected state
     */
    protected void throwIfInLifecycle(@NonNull final Lifecycle expected, @NonNull final String message) {
        if (lifecycle == expected) {
            throw new IllegalStateException(message);
        }
    }

    private void updateConnections() {
        final Map<ConnectionKey, ConnectionState> connections = new HashMap<>();
        for (final Node sender : nodes()) {
            for (final Node receiver : nodes()) {
                if (sender.selfId().equals(receiver.selfId())) {
                    continue; // Skip self-connections
                }
                final ConnectionKey key = new ConnectionKey(sender.selfId(), receiver.selfId());
                final ConnectionState connectionState = connectionState(sender, receiver);
                connections.put(key, connectionState);
            }
        }
        onConnectionsChanged(connections);
    }

    /**
     * Callback method to handle changes in the network connections.
     *
     * <p>This method is called whenever the connections in the network change, such as when partitions are created or
     * removed. This allows subclasses to react to changes in the network topology.
     *
     * @param connections a map of connections representing the current state of the network
     */
    protected abstract void onConnectionsChanged(@NonNull final Map<ConnectionKey, ConnectionState> connections);

    /**
     * Default implementation of {@link AsyncNetworkActions}
     */
    protected class AsyncNetworkActionsImpl implements AsyncNetworkActions {

        private final Duration timeout;

        /**
         * Constructs an instance of {@link AsyncNetworkActionsImpl} with the specified timeout.
         *
         * @param timeout the duration to wait for actions to complete
         */
        public AsyncNetworkActionsImpl(@NonNull final Duration timeout) {
            this.timeout = timeout;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void start() {
            doStart(timeout);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void shutdown() {
            doShutdown(timeout);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void sendQuiescenceCommand(@NonNull final QuiescenceCommand command) {
            doSendQuiescenceCommand(command, timeout);
        }
    }
}
