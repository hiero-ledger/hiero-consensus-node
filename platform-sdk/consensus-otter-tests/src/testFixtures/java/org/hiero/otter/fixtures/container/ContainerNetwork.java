// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.platform.gossip.config.GossipConfig_;
import com.swirlds.platform.gossip.config.NetworkEndpoint;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.container.network.NetworkBehavior;
import org.hiero.otter.fixtures.internal.AbstractNetwork;
import org.hiero.otter.fixtures.internal.RegularTimeManager;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;
import org.hiero.otter.fixtures.network.Topology.ConnectionData;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * An implementation of {@link org.hiero.otter.fixtures.Network} for the container environment. This class provides a
 * basic structure for a container network but does not implement all functionalities yet.
 */
public class ContainerNetwork extends AbstractNetwork {

    private static final Logger log = LogManager.getLogger();

    private final Network network = Network.newNetwork();
    private final RegularTimeManager timeManager;
    private final Path rootOutputDirectory;
    private final ContainerTransactionGenerator transactionGenerator;
    private final ImageFromDockerfile dockerImage;

    private ToxiproxyContainer toxiproxyContainer;
    private NetworkBehavior networkBehavior;

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
        super(new Random());
        this.timeManager = requireNonNull(timeManager);
        this.transactionGenerator = requireNonNull(transactionGenerator);
        this.rootOutputDirectory = requireNonNull(rootOutputDirectory);
        this.dockerImage = new ImageFromDockerfile()
                .withDockerfile(Path.of("..", "consensus-otter-docker-app", "build", "data", "Dockerfile"));
        transactionGenerator.setNodesSupplier(this::nodes);
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
    protected TransactionGenerator transactionGenerator() {
        return transactionGenerator;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onConnectionsChanged(@NonNull final Map<ConnectionKey, ConnectionData> connections) {
        networkBehavior.onConnectionsChanged(nodes(), connections);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected ContainerNode doCreateNode(@NonNull final NodeId nodeId, @NonNull final KeysAndCerts keysAndCerts) {
        final Path outputDir = rootOutputDirectory.resolve(NODE_IDENTIFIER_FORMAT.formatted(nodeId.id()));
        final ContainerNode node = new ContainerNode(nodeId, keysAndCerts, network, dockerImage, outputDir);
        timeManager.addTimeTickReceiver(node);
        return node;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected InstrumentedNode doCreateInstrumentedNode(
            @NonNull final NodeId nodeId, @NonNull final KeysAndCerts keysAndCerts) {
        final Path outputDir = rootOutputDirectory.resolve(NODE_IDENTIFIER_FORMAT.formatted(nodeId.id()));
        final InstrumentedContainerNode node =
                new InstrumentedContainerNode(nodeId, keysAndCerts, network, dockerImage, outputDir);
        timeManager.addTimeTickReceiver(node);
        return node;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void preStartHook(@NonNull final Roster roster) {
        // set up the toxiproxy container and network behavior
        toxiproxyContainer = new ToxiproxyContainer(network);
        toxiproxyContainer.start();
        final String toxiproxyHost = toxiproxyContainer.getHost();
        final int toxiproxyPort = toxiproxyContainer.getMappedPort(ToxiproxyContainer.CONTROL_PORT);
        final String toxiproxyIpAddress = toxiproxyContainer.getNetworkIpAddress();
        networkBehavior = new NetworkBehavior(toxiproxyHost, toxiproxyPort, roster, toxiproxyIpAddress);

        // override the endpoint for each node with the corresponding proxy endpoint
        for (final Node sender : nodes()) {
            final List<NetworkEndpoint> endpointOverrides = nodes().stream()
                    .filter(receiver -> !receiver.equals(sender))
                    .map(receiver -> networkBehavior.getProxyEndpoint(sender, receiver))
                    .toList();
            sender.configuration().set(GossipConfig_.ENDPOINT_OVERRIDES, endpointOverrides);
        }
    }

    /**
     * Shuts down the network and cleans up resources. Once this method is called, the network cannot be started again.
     * This method is idempotent and can be called multiple times without any side effects.
     */
    void destroy() {
        log.info("Destroying network...");
        transactionGenerator.stop();
        nodes().forEach(node -> ((ContainerNode) node).destroy());
        if (toxiproxyContainer != null) {
            toxiproxyContainer.stop();
        }
    }
}
