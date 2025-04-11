// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.platform.crypto.KeysAndCerts;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.test.fixtures.turtle.gossip.SimulatedNetwork;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.NodeConfiguration;

/**
 * A node in the turtle network.
 *
 * <p>This class implements the {@link Node} interface and provides methods to control the state of the node.
 */
public class TurtleNode implements Node, TurtleTimeManager.TimeTickReceiver {

    private static final Logger log = LogManager.getLogger(TurtleNode.class);

    private final Randotron randotron;
    private final Time time;
    private final NodeId nodeId;
    private final AddressBook addressBook;
    private final KeysAndCerts privateKey;
    private final SimulatedNetwork network;
    private final TurtleNodeConfiguration configuration;

    private com.swirlds.platform.test.fixtures.turtle.runner.TurtleNode turtleNode;

    public TurtleNode(
            @NonNull final Randotron randotron,
            @NonNull final Time time,
            @NonNull final NodeId nodeId,
            @NonNull final AddressBook addressBook,
            @NonNull final KeysAndCerts privateKey,
            @NonNull final SimulatedNetwork network,
            @NonNull final Path outputDirectory) {
        ThreadContext.put("nodeId", nodeId.toString());
        this.randotron = requireNonNull(randotron);
        this.time = requireNonNull(time);
        this.nodeId = requireNonNull(nodeId);
        this.addressBook = requireNonNull(addressBook);
        this.privateKey = requireNonNull(privateKey);
        this.network = requireNonNull(network);
        this.configuration = new TurtleNodeConfiguration(outputDirectory);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void failUnexpectedly(@NonNull final Duration timeout) {
        destroy();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void shutdownGracefully(@NonNull final Duration timeout) {
        log.warn("Simulating a graceful shutdown of a node has not been implemented yet.");
        destroy();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void revive(@NonNull final Duration timeout) {
        log.warn("Reviving a node has not been implemented yet.");
        turtleNode = new com.swirlds.platform.test.fixtures.turtle.runner.TurtleNode(
                randotron,
                time,
                nodeId,
                addressBook,
                privateKey,
                network,
                configuration.createConfiguration());
        turtleNode.start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void submitTransaction(@NonNull final byte[] transaction) {
        if (turtleNode == null) {
            throw new IllegalStateException("Node has not been started yet.");
        }
        turtleNode.submitTransaction(transaction);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tick(@NonNull Instant now) {
        if (turtleNode != null) {
            turtleNode.tick();
        }
    }

    /**
     * Start the node
     */
    public void start() {
        if (turtleNode != null) {
            throw new IllegalStateException("Node has already been started.");
        }
        // TODO: Wipe the output directory if it exists
        turtleNode = new com.swirlds.platform.test.fixtures.turtle.runner.TurtleNode(
                randotron,
                time,
                nodeId,
                addressBook,
                privateKey,
                network,
                configuration.createConfiguration());
        turtleNode.start();
    }

    /**
     * Shuts down the node and cleans up resources. Once this method is called, the node cannot be started
     * again. This method is idempotent and can be called multiple times without any side effects.
     */
    public void destroy() {
        ThreadContext.clearAll();
        if (turtleNode != null) {
            turtleNode.destroy();
        }
        turtleNode = null;
    }
}
