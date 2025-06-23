// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.container.ContainerNode.GOSSIP_PORT;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.crypto.CryptoStatic;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyStoreException;
import java.security.cert.CertificateEncodingException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.internal.AbstractNetwork;
import org.hiero.otter.fixtures.internal.RegularTimeManager;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * An implementation of {@link org.hiero.otter.fixtures.Network} for the container environment.
 * This class provides a basic structure for a container network, but does not implement all functionalities yet.
 */
public class ContainerNetwork extends AbstractNetwork {

    private static final Logger log = LogManager.getLogger();

    private static final Duration DEFAULT_START_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration DEFAULT_FREEZE_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofMinutes(1);

    private final Network network = Network.newNetwork();
    private final RegularTimeManager timeManager;
    private final ContainerTransactionGenerator transactionGenerator;
    private final List<ContainerNode> nodes = new ArrayList<>();
    private final List<Node> publicNodes = Collections.unmodifiableList(nodes);
    private final ImageFromDockerfile dockerImage;

    private long nextNodeId = 1L;

    /**
     * Constructor for SoloNetwork.
     *
     * @param timeManager the time manager to use
     * @param transactionGenerator the transaction generator to use
     */
    public ContainerNetwork(
            @NonNull final RegularTimeManager timeManager,
            @NonNull final ContainerTransactionGenerator transactionGenerator) {
        super(DEFAULT_START_TIMEOUT, DEFAULT_FREEZE_TIMEOUT, DEFAULT_SHUTDOWN_TIMEOUT);
        this.timeManager = requireNonNull(timeManager);
        this.transactionGenerator = requireNonNull(transactionGenerator);
        this.dockerImage = new ImageFromDockerfile()
                .withDockerfile(Path.of("..", "consensus-otter-docker-app", "build", "data", "Dockerfile"));
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
        throw new UnsupportedOperationException("Not implemented yet!");
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
    @NonNull
    public List<Node> addNodes(final int count) throws IOException, InterruptedException {
        throwIfInState(State.RUNNING, "Cannot add nodes while the network is running.");

        final List<ContainerNode> newNodes = new ArrayList<>();
        final List<RosterEntry> rosterEntries = new ArrayList<>();
        final Map<org.hiero.consensus.model.node.NodeId, KeysAndCerts> keysAndCerts = getKeysAndCerts(count);

        for (final var oldSelfId : keysAndCerts.keySet()) {
            final NodeId selfId = NodeId.newBuilder().id(oldSelfId.id()).build();
            final byte[] sigCertBytes = getSigCertBytes(oldSelfId, keysAndCerts);

            rosterEntries.add(RosterEntry.newBuilder()
                    .nodeId(selfId.id())
                    .weight(1L)
                    .gossipCaCertificate(Bytes.wrap(sigCertBytes))
                    .gossipEndpoint(ServiceEndpoint.newBuilder()
                            .domainName(String.format("node-%d", selfId.id()))
                            .port(GOSSIP_PORT)
                            .build())
                    .build());
        }

        final Roster roster = Roster.newBuilder().rosterEntries(rosterEntries).build();

        for (final var oldSelfId : keysAndCerts.keySet()) {
            final NodeId selfId = NodeId.newBuilder().id(oldSelfId.id()).build();
            final ContainerNode node =
                    new ContainerNode(selfId, roster, keysAndCerts.get(oldSelfId), network, dockerImage);
            newNodes.add(node);
        }
        nodes.addAll(newNodes);
        return Collections.unmodifiableList(newNodes);
    }

    @NonNull
    private static byte[] getSigCertBytes(
            final org.hiero.consensus.model.node.NodeId oldSelfId,
            final Map<org.hiero.consensus.model.node.NodeId, KeysAndCerts> keysAndCerts) {
        try {
            return keysAndCerts.get(oldSelfId).sigCert().getEncoded();
        } catch (final CertificateEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    @NonNull
    private static Map<org.hiero.consensus.model.node.NodeId, KeysAndCerts> getKeysAndCerts(final int count) {
        try {
            return CryptoStatic.generateKeysAndCerts(
                    IntStream.range(0, count)
                            .mapToObj(org.hiero.consensus.model.node.NodeId::of)
                            .toList(),
                    null);
        } catch (final ExecutionException | InterruptedException | KeyStoreException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InstrumentedNode addInstrumentedNode() {
        throw new UnsupportedOperationException("InstrumentedNode is not implemented yet!");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<Node> getNodes() {
        return publicNodes;
    }

    /**
     * Shuts down the network and cleans up resources. Once this method is called, the network cannot be started again.
     * This method is idempotent and can be called multiple times without any side effects.
     *
     * @throws IOException if an I/O error occurs during the shutdown process
     * @throws InterruptedException if the thread is interrupted while waiting for the shutdown process to complete
     */
    void destroy() throws IOException, InterruptedException {
        log.info("Destroying network...");
        transactionGenerator.stop();
        for (final ContainerNode node : nodes) {
            node.destroy();
        }
    }
}
