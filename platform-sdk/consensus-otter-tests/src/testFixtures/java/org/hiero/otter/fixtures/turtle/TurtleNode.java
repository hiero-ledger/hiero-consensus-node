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

    private final NodeId nodeId;
    private final com.swirlds.platform.test.fixtures.turtle.runner.TurtleNode turtleNode;

    public TurtleNode(
            @NonNull final Randotron randotron,
            @NonNull final Time time,
            @NonNull final NodeId nodeId,
            @NonNull final AddressBook addressBook,
            @NonNull final KeysAndCerts privateKey,
            @NonNull final SimulatedNetwork network,
            @NonNull final Path rootOutputDirectory) {
        ThreadContext.put("nodeId", nodeId.toString());
        this.nodeId = requireNonNull(nodeId);
        turtleNode = new com.swirlds.platform.test.fixtures.turtle.runner.TurtleNode(
                randotron,
                time,
                nodeId,
                addressBook,
                privateKey,
                network,
                rootOutputDirectory.resolve("node-" + nodeId.id()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void kill(@NonNull final Duration timeout) {
        ThreadContext.clearAll();
        log.warn("Killing a node has not been implemented yet.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void revive(@NonNull final Duration timeout) {
        log.warn("Reviving a node has not been implemented yet.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void submitTransaction(@NonNull final byte[] transaction) {
        turtleNode.submitTransaction(transaction);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration getConfiguration() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tick(@NonNull Instant now) {
        turtleNode.tick();
    }

    /**
     * Start the node
     */
    public void start() {
        turtleNode.start();
    }

    public void dump() {
        log.info(
                "Dump of node {}: {}",
                nodeId,
                turtleNode.getConsensusRoundsTestCollector().getCollectedRounds());
    }
}
