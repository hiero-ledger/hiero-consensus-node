// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.chaosbot.internal;

import static org.hiero.otter.fixtures.chaosbot.internal.RandomUtil.randomGaussianDuration;

import com.swirlds.common.test.fixtures.Randotron;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;

/**
 * An experiment that kills a target node for a duration.
 */
public class NodeFailureExperiment extends Experiment {

    private static final Logger log = LogManager.getLogger();

    // These values will become configurable in the future.
    private static final Duration MEAN_DURATION = Duration.ofMinutes(2L);
    private static final Duration DURATION_DEVIATION = Duration.ofSeconds(30L);

    private final Node targetNode;

    private NodeFailureExperiment(
            @NonNull final Network network, @NonNull final Instant endTime, @NonNull final Node targetNode) {
        super(network, endTime);
        this.targetNode = targetNode;
    }

    /**
     * Creates and starts a node failure experiment.
     *
     * @param env the test environment
     * @param randotron the random number generator
     * @return the created experiment, or {@code null} if no suitable node was found
     */
    @Nullable
    public static NodeFailureExperiment create(@NonNull final TestEnvironment env, @NonNull final Randotron randotron) {
        final TimeManager timeManager = env.timeManager();
        final Network network = env.network();
        final List<Node> candidates =
                network.nodes().stream().filter(Node::isAlive).toList();
        if (candidates.isEmpty()) {
            log.info("No available nodes to apply node failure experiment.");
            return null;
        }
        final Node targetNode = candidates.get(randotron.nextInt(candidates.size()));
        targetNode.killImmediately();
        final Duration duration = randomGaussianDuration(randotron, MEAN_DURATION, DURATION_DEVIATION);
        log.info("Starting node failure experiment for node {} with duration {}.", targetNode.selfId(), duration);
        return new NodeFailureExperiment(network, timeManager.now().plus(duration), targetNode);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void end() {
        targetNode.start();
        log.info("Ended node failure experiment for node {}.", targetNode.selfId());
    }
}
