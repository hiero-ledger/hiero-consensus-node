// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import com.swirlds.base.time.Time;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.platform.config.ModuleConfig_;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.logging.context.NodeLoggingContext.LoggingContextScope;
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
            @NonNull final Time time,
            @NonNull final NodeId selfId,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final SimulatedNetwork network,
            @NonNull final TurtleLogging logging,
            @NonNull final Path outputDirectory) {
        super(randotron, time, selfId, keysAndCerts, network, logging, outputDirectory);
        configuration()
                .set(
                        ModuleConfig_.EVENT_CREATOR_MODULE,
                        "org.hiero.consensus.event.creator.instrumented.InstrumentedEventCreator");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void ping(@NonNull final String message) {
        try (final LoggingContextScope ignored = installNodeContext()) {
            throwIfNotIn(LifeCycle.RUNNING, "Cannot ping a node that is not running");
            log.info("Sending ping '{}' to node {}", message, selfId);
            assert otterApp != null;
            otterApp.handlePing(message);
        }
    }
}
