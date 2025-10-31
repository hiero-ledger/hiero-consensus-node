// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import com.swirlds.common.test.fixtures.Randotron;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.internal.NetworkConfiguration;
import org.hiero.otter.fixtures.turtle.gossip.SimulatedNetwork;
import org.hiero.otter.fixtures.turtle.logging.TurtleLogging;

/**
 * An implementation of {@link InstrumentedNode} for the Turtle framework.
 */
public class InstrumentedTurtleNode extends TurtleNode implements InstrumentedNode {

    private final Logger log = LogManager.getLogger();

    /**
     * Constructor for the {@link InstrumentedTurtleNode} class.
     *
     * @param randotron the random number generator
     * @param time the time provider
     * @param selfId the node ID of the node
     * @param keysAndCerts the keys and certificates of the node
     * @param network the simulated network
     * @param logging the logging instance for the node
     * @param outputDirectory the output directory for the node
     */
    public InstrumentedTurtleNode(
            @NonNull final Randotron randotron,
            @NonNull final TurtleTimeManager timeManager,
            @NonNull final NodeId selfId,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final SimulatedNetwork network,
            @NonNull final TurtleLogging logging,
            @NonNull final Path outputDirectory,
            @NonNull final NetworkConfiguration networkConfiguration) {
        super(randotron, timeManager, selfId, keysAndCerts, network, logging, outputDirectory, networkConfiguration);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void ping(@NonNull final String message) {
        log.warn("Pinging is not implemented yet.");
    }
}
