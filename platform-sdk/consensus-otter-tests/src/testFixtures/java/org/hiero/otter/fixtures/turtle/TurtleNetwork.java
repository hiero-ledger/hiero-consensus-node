// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.test.fixtures.Randotron;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.internal.AbstractNetwork;
import org.hiero.otter.fixtures.internal.AbstractTimeManager.TimeTickReceiver;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;
import org.hiero.otter.fixtures.logging.context.ContextAwareThreadFactory;
import org.hiero.otter.fixtures.logging.context.NodeLoggingContext;
import org.hiero.otter.fixtures.logging.context.NodeLoggingContext.LoggingContextScope;
import org.hiero.otter.fixtures.network.Topology.ConnectionData;
import org.hiero.otter.fixtures.turtle.gossip.SimulatedNetwork;
import org.hiero.otter.fixtures.turtle.logging.TurtleLogging;

/**
 * An implementation of {@link Network} that is based on the Turtle framework.
 */
public class TurtleNetwork extends AbstractNetwork implements TimeTickReceiver {

    private static final Logger log = LogManager.getLogger();

    private final Randotron randotron;
    private final TurtleTimeManager timeManager;
    private final TurtleLogging logging;
    private final Path rootOutputDirectory;
    private final TurtleTransactionGenerator transactionGenerator;
    private final SimulatedNetwork simulatedNetwork;

    private ExecutorService executorService;

    /**
     * Constructor for TurtleNetwork.
     *
     * @param randotron            the random generator
     * @param timeManager          the time manager
     * @param logging              the logging utility
     * @param rootOutputDirectory  the directory where the node output will be stored, like saved state and so on
     * @param transactionGenerator the transaction generator that generates a steady flow of transactions to all nodes
     */
    public TurtleNetwork(
            @NonNull final Randotron randotron,
            @NonNull final TurtleTimeManager timeManager,
            @NonNull final TurtleLogging logging,
            @NonNull final Path rootOutputDirectory,
            @NonNull final TurtleTransactionGenerator transactionGenerator) {
        super(randotron);
        this.randotron = requireNonNull(randotron);
        this.timeManager = requireNonNull(timeManager);
        this.logging = requireNonNull(logging);
        this.rootOutputDirectory = requireNonNull(rootOutputDirectory);
        this.transactionGenerator = requireNonNull(transactionGenerator);
        this.simulatedNetwork = new SimulatedNetwork(randotron);
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
        simulatedNetwork.setConnections(connections);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected TurtleNode doCreateNode(@NonNull final NodeId nodeId, @NonNull final KeysAndCerts keysAndCerts) {
        simulatedNetwork.addNode(nodeId);
        final Path outputDir = rootOutputDirectory.resolve(NODE_IDENTIFIER_FORMAT.formatted(nodeId.id()));
        return new TurtleNode(
                randotron, timeManager.time(), nodeId, keysAndCerts, simulatedNetwork, logging, outputDir);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected InstrumentedNode doCreateInstrumentedNode(
            @NonNull final NodeId nodeId, @NonNull final KeysAndCerts keysAndCerts) {
        simulatedNetwork.addNode(nodeId);
        final Path outputDir = rootOutputDirectory.resolve(NODE_IDENTIFIER_FORMAT.formatted(nodeId.id()));
        return new InstrumentedTurtleNode(
                randotron, timeManager.time(), nodeId, keysAndCerts, simulatedNetwork, logging, outputDir);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void preStartHook(@NonNull final Roster roster) {
        final int size = nodes().size();
        executorService = NodeLoggingContext.wrap(Executors.newFixedThreadPool(
                Math.min(size, Runtime.getRuntime().availableProcessors()), new ContextAwareThreadFactory()));
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
        transactionGenerator.tick(now, nodes());

        // Iteration order over nodes does not need to be deterministic -- nodes are not permitted to communicate with
        // each other during the tick phase, and they run on separate threads to boot.
        CompletableFuture.allOf(nodes().stream()
                        .map(node -> {
                            final TurtleNode turtleNode = (TurtleNode) node;
                            try (final LoggingContextScope ignored = NodeLoggingContext.install(
                                    Long.toString(turtleNode.selfId().id()))) {
                                return CompletableFuture.runAsync(
                                        NodeLoggingContext.wrap(() -> turtleNode.tick(now)), executorService);
                            }
                        })
                        .toArray(CompletableFuture[]::new))
                .join();
    }

    /**
     * Shuts down the network and cleans up resources. Once this method is called, the network cannot be started again.
     * This method is idempotent and can be called multiple times without any side effects.
     */
    void destroy() {
        log.info("Destroying network...");
        transactionGenerator.stop();
        nodes().forEach(node -> ((TurtleNode) node).destroy());
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }
}
