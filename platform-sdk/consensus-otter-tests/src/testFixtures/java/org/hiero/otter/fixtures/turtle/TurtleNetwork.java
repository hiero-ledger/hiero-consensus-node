// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.turtle.TurtleTestEnvironment.AVERAGE_NETWORK_DELAY;
import static org.hiero.otter.fixtures.turtle.TurtleTestEnvironment.STANDARD_DEVIATION_NETWORK_DELAY;

import com.swirlds.base.time.Time;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.platform.crypto.KeysAndCerts;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.test.fixtures.addressbook.RandomAddressBookBuilder;
import com.swirlds.platform.test.fixtures.turtle.gossip.SimulatedNetwork;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;

/**
 * An implementation of {@link Network} that is based on the Turtle framework.
 */
public class TurtleNetwork implements Network, TurtleTimeManager.TimeTickReceiver {

    private static final Logger log = LogManager.getLogger(TurtleNetwork.class);

    private enum State {
        INIT,
        RUNNING,
        SHUTDOWN
    }

    private final Randotron randotron;
    private final Time time;
    private final Path rootOutputDirectory;
    private final List<TurtleNode> nodes = new ArrayList<>();

    private List<Node> publicNodes = List.of();
    private ExecutorService threadPool;
    private SimulatedNetwork simulatedNetwork;

    private State state = State.INIT;

    /**
     * Constructor for TurtleNetwork.
     *
     * @param randotron the random generator
     * @param time the source of the time
     * @param rootOutputDirectory the directory where the node output will be stored, like saved state and so on
     * @param averageNetworkDelay the average network delay
     * @param standardDeviationNetworkDelay the standard deviation of the network delay
     */
    public TurtleNetwork(
            @NonNull final Randotron randotron,
            @NonNull final Time time,
            @NonNull final Path rootOutputDirectory,
            @NonNull final Duration averageNetworkDelay,
            @NonNull final Duration standardDeviationNetworkDelay) {
        this.randotron = requireNonNull(randotron);
        this.time = requireNonNull(time);
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

        threadPool = Executors.newFixedThreadPool(
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
        return new TurtleNode(randotron, time, nodeId, addressBook, privateKeys, simulatedNetwork, rootOutputDirectory);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start(@NonNull final Duration timeout) {
        if (state != State.INIT) {
            throw new IllegalStateException("Cannot start the network more than once.");
        }

        state = State.RUNNING;
        for (final TurtleNode node : nodes) {
            node.start();
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
    public void prepareUpgrade(@NonNull Duration timeout) {
        for (final TurtleNode node : nodes) {
            node.shutdownGracefully(timeout);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void resume(@NonNull Duration duration) {
        log.warn("Resuming the network is not implemented yet.");
        for (final TurtleNode node : nodes) {
            node.revive(duration);
        }
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
        final List<Future<?>> futures = nodes.stream()
                .<Future<?>>map(node -> threadPool.submit(() -> node.tick(now)))
                .toList();

        for (final Future<?> future : futures) {
            try {
                future.get();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while ticking nodes", e);
            } catch (final ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Shuts down the network and cleans up resources. Once this method is called, the network cannot be started
     * again. This method is idempotent and can be called multiple times without any side effects.
     */
    public void destroy() {
        for (final TurtleNode node : nodes) {
            node.destroy();
        }
        threadPool.shutdownNow();
    }
}
