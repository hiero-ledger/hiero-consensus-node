// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.crypto.CryptoStatic;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.security.KeyStoreException;
import java.security.cert.CertificateEncodingException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionFactory;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.internal.AbstractNetwork;
import org.hiero.otter.fixtures.internal.RegularTimeManager;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;
import org.hiero.otter.fixtures.internal.network.MeshTopologyImpl;
import org.hiero.otter.fixtures.network.Topology;
import org.hiero.otter.fixtures.network.Topology.ConnectionData;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * An implementation of {@link org.hiero.otter.fixtures.Network} for the container environment. This class provides a
 * basic structure for a container network but does not implement all functionalities yet.
 */
public class ContainerNetwork extends AbstractNetwork {

    /** The format for node identifiers in the network. */
    public static final String NODE_IDENTIFIER_FORMAT = "node-%d";

    private static final int GOSSIP_PORT = 5777;
    private static final Logger log = LogManager.getLogger();

    private static final Duration DEFAULT_START_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration DEFAULT_FREEZE_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofMinutes(1);

    private final Network network = Network.newNetwork();
    private final RegularTimeManager timeManager;
    private final Path rootOutputDirectory;
    private final ContainerTransactionGenerator transactionGenerator;
    private final ImageFromDockerfile dockerImage;
    private final Topology topology = new MeshTopologyImpl(this::createContainerNodes);

    /**
     * Constructor for {@link ContainerNetwork}.
     *
     * @param timeManager the time manager to use
     * @param transactionGenerator the transaction generator to use
     * @param rootOutputDirectory the root output directory for the network
     */
    public ContainerNetwork(
            @NonNull final RegularTimeManager timeManager,
            @NonNull final ContainerTransactionGenerator transactionGenerator,
            @NonNull final Path rootOutputDirectory) {
        super(DEFAULT_START_TIMEOUT, DEFAULT_FREEZE_TIMEOUT, DEFAULT_SHUTDOWN_TIMEOUT);
        this.timeManager = requireNonNull(timeManager);
        this.transactionGenerator = requireNonNull(transactionGenerator);
        this.rootOutputDirectory = requireNonNull(rootOutputDirectory);
        this.dockerImage = new ImageFromDockerfile()
                .withDockerfile(Path.of("..", "consensus-otter-docker-app", "build", "data", "Dockerfile"));
        transactionGenerator.setNodesSupplier(topology::nodes);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected TimeManager timeManager() {
        return timeManager;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected byte[] createFreezeTransaction(@NonNull final Instant freezeTime) {
        return TransactionFactory.createFreezeTransaction(freezeTime).toByteArray();
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
     * {@inheritDoc}
     */
    @Override
    protected void onConnectionsChanged(@NonNull final Map<ConnectionKey, ConnectionData> connections) {
        // No-op for container network, it will be implemented next
        // https://github.com/hiero-ledger/hiero-consensus-node/issues/20258
    }

    @NonNull
    private List<ContainerNode> createContainerNodes(final int count) {
        throwIfInState(State.RUNNING, "Cannot add nodes while the network is running.");

        final List<RosterEntry> rosterEntries = new ArrayList<>();
        final Map<NodeId, KeysAndCerts> keysAndCerts = getKeysAndCerts(count);

        final Iterator<Long> weightIterator =
                weightGenerator.getWeights(0L, count).iterator();
        // Sort the node IDs to guarantee roster entry order
        final List<NodeId> sortedNodeIds = keysAndCerts.keySet().stream()
                .sorted(Comparator.comparingLong(NodeId::id))
                .toList();

        for (final NodeId selfId : sortedNodeIds) {
            final byte[] sigCertBytes = getSigCertBytes(selfId, keysAndCerts);

            rosterEntries.add(RosterEntry.newBuilder()
                    .nodeId(selfId.id())
                    .weight(weightIterator.next())
                    .gossipCaCertificate(Bytes.wrap(sigCertBytes))
                    .gossipEndpoint(ServiceEndpoint.newBuilder()
                            .domainName(String.format(NODE_IDENTIFIER_FORMAT, selfId.id()))
                            .port(GOSSIP_PORT)
                            .build())
                    .build());
        }

        final Roster roster = Roster.newBuilder().rosterEntries(rosterEntries).build();

        return sortedNodeIds.stream()
                .map(nodeId -> createContainerNode(nodeId, roster, keysAndCerts.get(nodeId)))
                .toList();
    }

    private ContainerNode createContainerNode(
            @NonNull final NodeId nodeId, @NonNull final Roster roster, @NonNull final KeysAndCerts keysAndCerts) {
        final Path outputDir = rootOutputDirectory.resolve("node-" + nodeId.id());
        final ContainerNode node = new ContainerNode(nodeId, roster, keysAndCerts, network, dockerImage, outputDir);
        timeManager.addTimeTickReceiver(node);
        return node;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Topology topology() {
        return topology;
    }

    @NonNull
    private static byte[] getSigCertBytes(final NodeId selfId, final Map<NodeId, KeysAndCerts> keysAndCerts) {
        try {
            return keysAndCerts.get(selfId).sigCert().getEncoded();
        } catch (final CertificateEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    @NonNull
    private static Map<NodeId, KeysAndCerts> getKeysAndCerts(final int count) {
        try {
            final List<org.hiero.consensus.model.node.NodeId> nodeIds = IntStream.range(0, count)
                    .mapToObj(org.hiero.consensus.model.node.NodeId::of)
                    .toList();
            final Map<org.hiero.consensus.model.node.NodeId, KeysAndCerts> legacyNodeIdKeysAndCertsMap =
                    CryptoStatic.generateKeysAndCerts(nodeIds, null);
            return legacyNodeIdKeysAndCertsMap.entrySet().stream()
                    .collect(Collectors.toMap(
                            entry -> NodeId.newBuilder()
                                    .id(entry.getKey().id())
                                    .build(), // or use a factory method if needed
                            Map.Entry::getValue));
        } catch (final ExecutionException | InterruptedException | KeyStoreException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Shuts down the network and cleans up resources. Once this method is called, the network cannot be started again.
     * This method is idempotent and can be called multiple times without any side effects.
     */
    void destroy() {
        log.info("Destroying network...");
        transactionGenerator.stop();
        topology.nodes().forEach(node -> ((ContainerNode) node).destroy());
    }
}
