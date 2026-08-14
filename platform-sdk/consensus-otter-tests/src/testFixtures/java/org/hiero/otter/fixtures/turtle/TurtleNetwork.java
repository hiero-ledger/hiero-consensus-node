// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.internal.AbstractTimeManager.TimeTickReceiver;
import org.hiero.otter.fixtures.internal.simulator.SimulatorNetwork;
import org.hiero.otter.fixtures.internal.simulator.SimulatorTimeManager;
import org.hiero.otter.fixtures.logging.context.ContextAwareThreadFactory;
import org.hiero.otter.fixtures.logging.context.NodeLoggingContext;
import org.hiero.otter.fixtures.logging.context.NodeLoggingContext.LoggingContextScope;
import org.hiero.otter.fixtures.turtle.gossip.SimulatedGossip;
import org.hiero.otter.fixtures.turtle.logging.TurtleLogging;
import org.hiero.otter.fixtures.util.OtterSavedStateUtils;

/**
 * An implementation of {@link Network} that is based on the Turtle framework.
 */
public class TurtleNetwork extends SimulatorNetwork implements TimeTickReceiver {

    private static final Logger log = LogManager.getLogger();

    private final TurtleLogging logging;
    private final Path rootOutputDirectory;
    private final TurtleTransactionGenerator turtleTransactionGenerator;

    private ExecutorService executorService;

    /**
     * Constructor for TurtleNetwork.
     *
     * @param random the random generator
     * @param timeManager the time manager
     * @param logging the logging utility
     * @param rootOutputDirectory the directory where the node output will be stored, like saved state and so on
     * @param transactionGenerator the transaction generator that generates a steady flow of transactions to all nodes
     * @param useRandomNodeIds {@code true} if the node IDs should be selected randomly; {@code false} otherwise
     */
    public TurtleNetwork(
            @NonNull final Random random,
            @NonNull final SimulatorTimeManager timeManager,
            @NonNull final TurtleLogging logging,
            @NonNull final Path rootOutputDirectory,
            @NonNull final TurtleTransactionGenerator transactionGenerator,
            final boolean useRandomNodeIds) {
        super(random, timeManager, transactionGenerator, useRandomNodeIds);
        this.turtleTransactionGenerator = requireNonNull(transactionGenerator);
        this.logging = requireNonNull(logging);
        this.rootOutputDirectory = requireNonNull(rootOutputDirectory);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected TurtleNode doCreateNode(@NonNull final NodeId nodeId, @NonNull final KeysAndCerts keysAndCerts) {
        final SimulatedGossip simulatedGossip = new SimulatedGossip(simulatedNetwork, nodeId);
        simulatedNetwork.addNode(nodeId, simulatedGossip);
        final Path outputDir = rootOutputDirectory.resolve(NODE_IDENTIFIER_FORMAT.formatted(nodeId.id()));
        return new TurtleNode(
                random,
                timeManager,
                nodeId,
                keysAndCerts,
                simulatedGossip,
                logging,
                outputDir,
                networkConfiguration,
                consensusRoundPool);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected InstrumentedNode doCreateInstrumentedNode(
            @NonNull final NodeId nodeId, @NonNull final KeysAndCerts keysAndCerts) {
        final SimulatedGossip simulatedGossip = new SimulatedGossip(simulatedNetwork, nodeId);
        simulatedNetwork.addNode(nodeId, simulatedGossip);
        final Path outputDir = rootOutputDirectory.resolve(NODE_IDENTIFIER_FORMAT.formatted(nodeId.id()));
        return new InstrumentedTurtleNode(
                random,
                timeManager,
                nodeId,
                keysAndCerts,
                simulatedGossip,
                logging,
                outputDir,
                networkConfiguration,
                consensusRoundPool);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void preStartHook(@NonNull final Roster roster) {
        final int size = nodes().size();
        executorService = NodeLoggingContext.wrap(Executors.newFixedThreadPool(
                Math.min(size, Runtime.getRuntime().availableProcessors()), new ContextAwareThreadFactory()));

        // Synchronize FakeTime when starting from a saved state.
        // This ensures time never goes backward when starting from saved state.
        final Path savedStateDirectory = networkConfiguration.savedStateDirectory();
        if (savedStateDirectory != null) {
            synchronizeTimeWithSavedState(savedStateDirectory);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doSendQuiescenceCommand(@NonNull final QuiescenceCommand command, @NonNull final Duration timeout) {
        nodes().forEach(node -> node.sendQuiescenceCommand(command));
    }

    /**
     * Synchronizes FakeTime to the saved state's WALL_CLOCK_TIME plus one hour. This ensures time never goes backward
     * when starting from a saved state, and is instantaneous.
     */
    private void synchronizeTimeWithSavedState(@NonNull final Path savedStateDirectory) {
        try {
            final Instant requiredTime = OtterSavedStateUtils.loadSavedStateWallClockTime(savedStateDirectory)
                    .plus(Duration.ofHours(1));
            final Instant currentTime = timeManager.now();

            if (currentTime.isBefore(requiredTime)) {
                final Duration timeAdvance = Duration.between(currentTime, requiredTime);
                log.info("Advancing TimeManager instantaneously by {} to match saved state time", timeAdvance);
                timeManager.advanceTime(timeAdvance);
            }
        } catch (final IOException e) {
            fail("Failed to synchronize TimeManager with saved state", e);
        }
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
        turtleTransactionGenerator.tick(now, nodes());

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
     * {@inheritDoc}
     */
    @Override
    public void destroy() {
        log.info("Destroying network...");
        super.destroy();
        nodes().forEach(node -> ((TurtleNode) node).destroy());
        consensusRoundPool.destroy();
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }
}
