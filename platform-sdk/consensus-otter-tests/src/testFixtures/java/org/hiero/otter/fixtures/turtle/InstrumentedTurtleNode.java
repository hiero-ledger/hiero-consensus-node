// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle.RUNNING;
import static org.hiero.otter.fixtures.internal.InstrumentedModules.INSTRUMENTED_EVENT_CREATOR;
import static org.hiero.otter.fixtures.internal.InstrumentedModules.PING_CHANNEL;

import com.swirlds.platform.builder.ModulesConfig_;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.Random;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.test.fixtures.eventbus.EventBus;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.internal.NetworkConfiguration;
import org.hiero.otter.fixtures.internal.result.ConsensusRoundPool;
import org.hiero.otter.fixtures.internal.simulator.SimulatorTimeManager;
import org.hiero.otter.fixtures.logging.context.NodeLoggingContext.LoggingContextScope;
import org.hiero.otter.fixtures.turtle.gossip.SimulatedGossip;
import org.hiero.otter.fixtures.turtle.logging.TurtleLogging;

/**
 * An implementation of {@link InstrumentedNode} for the Turtle framework.
 */
public class InstrumentedTurtleNode extends TurtleNode implements InstrumentedNode {

    private final Logger log = LogManager.getLogger();

    /**
     * Constructor for the {@link InstrumentedTurtleNode} class.
     *
     * @param random the random number generator
     * @param timeManager the time provider
     * @param selfId the node ID of the node
     * @param keysAndCerts the keys and certificates of the node
     * @param gossip the simulated gossip instance
     * @param logging the logging instance for the node
     * @param outputDirectory the output directory for the node
     * @param networkConfiguration the network configuration
     * @param consensusRoundPool the shared pool for deduplicating consensus rounds
     */
    public InstrumentedTurtleNode(
            @NonNull final Random random,
            @NonNull final SimulatorTimeManager timeManager,
            @NonNull final NodeId selfId,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final SimulatedGossip gossip,
            @NonNull final TurtleLogging logging,
            @NonNull final Path outputDirectory,
            @NonNull final NetworkConfiguration networkConfiguration,
            @NonNull final ConsensusRoundPool consensusRoundPool) {
        super(
                random,
                timeManager,
                selfId,
                keysAndCerts,
                gossip,
                logging,
                outputDirectory,
                networkConfiguration,
                consensusRoundPool);
        configuration().withConfigValue(ModulesConfig_.EVENT_CREATOR, INSTRUMENTED_EVENT_CREATOR);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void ping(@NonNull final String message) {
        try (final LoggingContextScope ignored = installNodeContext()) {
            throwIsNotInLifecycle(RUNNING, "Cannot ping a node that is not running");
            log.info("Sending ping '{}' to node {}", message, selfId);
            EventBus.getInstance(selfId).publish(PING_CHANNEL, message);
        }
    }
}
