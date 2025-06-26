// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.turtle.TurtleTestEnvironment.AVERAGE_NETWORK_DELAY;
import static org.hiero.otter.fixtures.turtle.TurtleTestEnvironment.STANDARD_DEVIATION_NETWORK_DELAY;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterUtils;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.internal.AbstractNetwork;
import org.hiero.otter.fixtures.turtle.gossip.SimulatedNetwork;

/**
 * An implementation of {@link Network} that is based on the Turtle framework.
 */
public class TurtleNetwork extends AbstractNetwork implements TurtleTimeManager.TimeTickReceiver {

    private static final Logger log = LogManager.getLogger();

    private static final Duration DEFAULT_START_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_FREEZE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ZERO;

    private final Randotron randotron;
    private final TurtleTimeManager timeManager;
    private final TurtleLogging logging;
    private final Path rootOutputDirectory;
    private final List<TurtleNode> nodes = new ArrayList<>();
    private final TurtleTransactionGenerator transactionGenerator;

    private List<Node> publicNodes = Collections.unmodifiableList(nodes);
    private ExecutorService executorService;
    private SimulatedNetwork simulatedNetwork;

    /**
     * Constructor for TurtleNetwork.
     *
     * @param randotron           the random generator
     * @param timeManager         the time manager
     * @param logging             the logging utility
     * @param rootOutputDirectory the directory where the node output will be stored, like saved state and so on
     * @param transactionGenerator the transaction generator that generates a steady flow of transactions to all nodes
     */
    public TurtleNetwork(
            @NonNull final Randotron randotron,
            @NonNull final TurtleTimeManager timeManager,
            @NonNull final TurtleLogging logging,
            @NonNull final Path rootOutputDirectory,
            @NonNull final TurtleTransactionGenerator transactionGenerator) {
        super(DEFAULT_START_TIMEOUT, DEFAULT_FREEZE_TIMEOUT, DEFAULT_SHUTDOWN_TIMEOUT);
        this.randotron = requireNonNull(randotron);
        this.timeManager = requireNonNull(timeManager);
        this.logging = requireNonNull(logging);
        this.rootOutputDirectory = requireNonNull(rootOutputDirectory);
        this.transactionGenerator = requireNonNull(transactionGenerator);
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
    @NonNull
    public List<Node> addNodes(final int count) {
        throwIfInState(State.RUNNING, "Cannot add nodes after the network has been started.");
        throwIfInState(State.SHUTDOWN, "Cannot add nodes after the network has been started.");
        if (!nodes.isEmpty()) {
            throw new UnsupportedOperationException("Adding nodes incrementally is not supported yet.");
        }

        executorService = Executors.newFixedThreadPool(
                Math.min(count, Runtime.getRuntime().availableProcessors()));

        final RandomRosterBuilder rosterBuilder =
                RandomRosterBuilder.create(randotron).withSize(count).withRealKeysEnabled(true);
        final Roster roster = rosterBuilder.build();

        simulatedNetwork =
                new SimulatedNetwork(randotron, roster, AVERAGE_NETWORK_DELAY, STANDARD_DEVIATION_NETWORK_DELAY);

        final List<TurtleNode> nodeList = roster.rosterEntries().stream()
                .map(RosterUtils::getNodeId)
                .sorted()
                .map(nodeId -> createTurtleNode(nodeId, roster, rosterBuilder.getPrivateKeys(nodeId)))
                .toList();
        nodes.addAll(nodeList);

        return publicNodes;
    }

    private TurtleNode createTurtleNode(
            @NonNull final NodeId nodeId, @NonNull final Roster roster, @NonNull final KeysAndCerts privateKeys) {
        final Path outputDir = rootOutputDirectory.resolve("node-" + nodeId.id());
        return new TurtleNode(
                randotron, timeManager.time(), nodeId, roster, privateKeys, simulatedNetwork, logging, outputDir);
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
    public void tick(@NonNull final Instant now) {
        if (state != State.RUNNING) {
            return;
        }

        simulatedNetwork.tick(now);
        transactionGenerator.tick(now, nodes);

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
     *
     * @throws InterruptedException if the thread is interrupted while the network is being destroyed
     */
    void destroy() throws InterruptedException {
        log.info("Destroying network...");
        transactionGenerator.stop();
        for (final TurtleNode node : nodes) {
            node.destroy();
        }
        executorService.shutdownNow();
    }
}
