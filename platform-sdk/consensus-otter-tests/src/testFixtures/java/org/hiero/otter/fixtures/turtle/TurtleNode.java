// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import com.swirlds.base.time.Time;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.Loggers;
import com.swirlds.platform.crypto.KeysAndCerts;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.test.fixtures.turtle.gossip.SimulatedNetwork;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.time.TimeTickReceiver;

/**
 * A node in the turtle network.
 *
 * <p>This class implements the {@link Node} interface and provides methods to control the state of the node.
 */
public class TurtleNode implements Node, TimeTickReceiver {

    private static final Logger log = Loggers.getLogger(TurtleNode.class);

    private final com.swirlds.platform.test.fixtures.turtle.runner.TurtleNode turtleNode;

    public TurtleNode(
            @NonNull final Randotron randotron,
            @NonNull final Time time,
            @NonNull final NodeId nodeId,
            @NonNull final AddressBook addressBook,
            @NonNull final KeysAndCerts privateKey,
            @NonNull final SimulatedNetwork network,
            @NonNull final Path rootOutputDirectory) {
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
    public void tick(@NonNull Instant now) {
        // Not implemented. Logs no warning as this method is called with high frequency.
    }

    /**
     * Start the node
     */
    public void start() {
        turtleNode.start();
    }
}
