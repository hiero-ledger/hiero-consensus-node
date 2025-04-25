// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.fail;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.FREEZE_COMPLETE;
import static org.hiero.otter.fixtures.turtle.TurtleTestEnvironment.AVERAGE_NETWORK_DELAY;
import static org.hiero.otter.fixtures.turtle.TurtleTestEnvironment.STANDARD_DEVIATION_NETWORK_DELAY;

import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.platform.crypto.KeysAndCerts;
import com.swirlds.platform.test.fixtures.addressbook.RandomAddressBookBuilder;
import com.swirlds.platform.test.fixtures.turtle.gossip.SimulatedNetwork;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.AddressBook;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.NodeFilter;
import org.hiero.otter.fixtures.internal.result.MultipleNodeConsensusResultsImpl;
import org.hiero.otter.fixtures.result.MultipleNodeConsensusResults;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.turtle.app.TurtleTransaction;

/**
 * An implementation of {@link Network} that is based on the Turtle framework.
 */
@SuppressWarnings("removal")
public class TurtleNetwork implements Network, TurtleTimeManager.TimeTickReceiver {

    private static final Logger log = LogManager.getLogger(TurtleNetwork.class);

    private enum State {
        INIT,
        RUNNING,
        SHUTDOWN
    }

    private final Randotron randotron;
    private final TurtleTimeManager timeManager;
    private final Path rootOutputDirectory;
    private final List<TurtleNode> nodes = new ArrayList<>();

    private List<Node> publicNodes = List.of();
    private ExecutorService executorService;
    private SimulatedNetwork simulatedNetwork;

    private State state = State.INIT;

    /**
     * Constructor for TurtleNetwork.
     *
     * @param randotron the random generator
     * @param timeManager the time manager
     * @param rootOutputDirectory the directory where the node output will be stored, like saved state and so on
     */
    public TurtleNetwork(
            @NonNull final Randotron randotron,
            @NonNull final TurtleTimeManager timeManager,
            @NonNull final Path rootOutputDirectory) {
        this.randotron = requireNonNull(randotron);
        this.timeManager = requireNonNull(timeManager);
        this.rootOutputDirectory = requireNonNull(rootOutputDirectory);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<Node> addNodes(final int count) {
        if (state != State.INIT) {
            throw new IllegalStateException("Cannot add nodes after the network has been started.");
        }
        if (!nodes.isEmpty()) {
            throw new UnsupportedOperationException("Adding nodes incrementally is not supported yet.");
        }

        executorService = Executors.newFixedThreadPool(
                Math.min(count, Runtime.getRuntime().availableProcessors()));

        final RandomAddressBookBuilder addressBookBuilder =
                RandomAddressBookBuilder.create(randotron).withSize(count).withRealKeysEnabled(true);
        final AddressBook addressBook = addressBookBuilder.build();

        simulatedNetwork =
                new SimulatedNetwork(randotron, addressBook, AVERAGE_NETWORK_DELAY, STANDARD_DEVIATION_NETWORK_DELAY);

        final List<TurtleNode> nodeList = addressBook.getNodeIdSet().stream()
                .sorted()
                .map(nodeId -> createTurtleNode(nodeId, addressBook, addressBookBuilder.getPrivateKeys(nodeId)))
                .toList();
        nodes.addAll(nodeList);

        publicNodes = nodes.stream().map(Node.class::cast).toList();
        return publicNodes;
    }

    private TurtleNode createTurtleNode(
            @NonNull final NodeId nodeId,
            @NonNull final AddressBook addressBook,
            @NonNull final KeysAndCerts privateKeys) {
        final Path outputDir = rootOutputDirectory.resolve("node-" + nodeId.id());
        return new TurtleNode(
                randotron, timeManager.time(), nodeId, addressBook, privateKeys, simulatedNetwork, outputDir);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start(@NonNull final Duration timeout) {
        if (state != State.INIT) {
            throw new IllegalStateException("Cannot start the network more than once.");
        }

        log.info("Starting network...");
        state = State.RUNNING;
        for (final TurtleNode node : nodes) {
            node.start();
        }

        log.debug("Waiting for nodes to become active...");
        if (!timeManager.waitForCondition(allNodesInStatus(ACTIVE), timeout)) {
            fail("Timeout while waiting for nodes to become active.");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InstrumentedNode addInstrumentedNode() {
        throw new UnsupportedOperationException("Adding instrumented nodes is not implemented yet.");
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
     * {@inheritDoc}
     */
    @Override
    public void prepareUpgrade(@NonNull Duration timeout) throws InterruptedException {
        if (state != State.RUNNING) {
            throw new IllegalStateException("Cannot prepare upgrade when the network is not running.");
        }
        log.info("Preparing upgrade...");

        log.debug("Sending TurtleFreezeTransaction transaction...");
        final TurtleTransaction freezeTransaction =
                TransactionFactory.createFreezeTransaction(timeManager.time().now());
        nodes.getFirst().submitTransaction(freezeTransaction.toByteArray());

        log.debug("Waiting for nodes to freeze...");
        if (!timeManager.waitForCondition(allNodesInStatus(FREEZE_COMPLETE), timeout)) {
            fail("Timeout while waiting for all nodes to freeze.");
        }

        log.debug("Shutting down nodes gracefully...");
        for (final TurtleNode node : nodes) {
            node.shutdownGracefully(timeout);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void resume(@NonNull Duration timeout) {
        log.info("Resuming network...");
        for (final TurtleNode node : nodes) {
            node.revive(timeout);
        }

        log.debug("Waiting for nodes to become active again...");
        if (!timeManager.waitForCondition(allNodesInStatus(ACTIVE), timeout)) {
            fail("Timeout while waiting for nodes to become active.");
        }
    }

    @NonNull
    @Override
    public MultipleNodeConsensusResults getConsensusResult(@NonNull NodeFilter... filters) {
        final NodeFilter combined = NodeFilter.andAll(filters);
        final List<SingleNodeConsensusResult> results =
                nodes.stream().filter(combined).map(Node::getConsensusResult).toList();
        return new MultipleNodeConsensusResultsImpl(results);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tick(@NonNull final Instant now) {
        if (state != State.RUNNING) {
            return;
        }

        simulatedNetwork.tick(now);

        // Iteration order over nodes does not need to be deterministic -- nodes are not permitted to communicate with
        // each other during the tick phase, and they run on separate threads to boot.
        CompletableFuture.allOf(nodes.stream()
                        .map(node -> CompletableFuture.runAsync(() -> node.tick(now), executorService))
                        .toArray(CompletableFuture[]::new))
                .join();
    }

    /**
     * Shuts down the network and cleans up resources. Once this method is called, the network cannot be started again.
     * This method is idempotent and can be called multiple times without any side effects.
     */
    public void destroy() throws InterruptedException {
        log.info("Destroying network...");
        for (final TurtleNode node : nodes) {
            node.destroy();
        }
        executorService.shutdownNow();
    }

    /**
     * Creates a {@link BooleanSupplier} that returns {@code true} if all nodes are in the given {@link PlatformStatus}.
     *
     * @param status the status to check
     * @return the {@link BooleanSupplier}
     */
    private BooleanSupplier allNodesInStatus(@NonNull final PlatformStatus status) {
        return () -> nodes.stream().allMatch(node -> node.platformStatus() == status);
    }
}
