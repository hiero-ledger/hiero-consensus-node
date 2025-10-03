// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TransactionFactory;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.app.OtterTransaction;

/**
 * A {@link TransactionGenerator} for the container environment.
 * This class is a placeholder and does not implement any functionality yet.
 */
public class ContainerTransactionGenerator implements TransactionGenerator {

    private static final Logger log = LogManager.getLogger();

    /** Duration between two transaction submissions to a single node. */
    private static final Duration CYCLE_DURATION = Duration.ofSeconds(1).dividedBy(TPS);

    /** Random instance */
    private final Random random = new Random();

    /** Supplies the current list of nodes to which transactions should be sent. */
    private Supplier<List<Node>> nodesSupplier = List::of;

    /** The scheduler used to run the periodic generation job. */
    private final ScheduledExecutorService scheduler;

    /** The handle of the scheduled generation job returned by the scheduler. Volatile to ensure visibility of updates to generationTask from the thread starting/stopping the scheduler and the scheduled thread. */
    private volatile ScheduledFuture<?> generationTask;

    /**
     * Creates a new generator with a default single-threaded scheduler.
     */
    public ContainerTransactionGenerator() {
        this(Executors.newSingleThreadScheduledExecutor());
    }

    /**
     * Creates a new generator using the provided scheduler. This constructor is primarily intended
     * for unit tests where a deterministic or mocked scheduler is desirable.
     *
     * @param scheduler the scheduler to use for running the generation task
     */
    public ContainerTransactionGenerator(final ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * Sets the supplier that provides the up-to-date list of nodes. Must be called by the network once
     * the nodes are available.
     *
     * @param supplier supplier returning the current nodes
     */
    public void setNodesSupplier(final Supplier<List<Node>> supplier) {
        this.nodesSupplier = supplier;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void start() {
        if (generationTask != null && !generationTask.isCancelled()) {
            return;
        }

        generationTask = scheduler.scheduleAtFixedRate(
                this::generateAndSubmit, 0, CYCLE_DURATION.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void stop() {
        if (generationTask != null) {
            generationTask.cancel(true);
            generationTask = null;
        }
    }

    /**
     * Generates a random transaction payload and submits it to all active nodes.
     */
    private void generateAndSubmit() {
        if (generationTask == null || generationTask.isCancelled()) {
            return;
        }

        final List<Node> nodes = nodesSupplier.get();

        if (nodes == null || nodes.isEmpty()) {
            return;
        }

        for (final Node node : nodes) {
            if (node.platformStatus() == PlatformStatus.ACTIVE) {
                final OtterTransaction transaction = TransactionFactory.createEmptyTransaction(random.nextLong());
                try {
                    ((ContainerNode) node).submitTransaction(transaction);
                } catch (final Exception e) {
                    log.debug("Unable to submit transaction to node {}", node.selfId(), e);
                }
            }
        }
    }
}
