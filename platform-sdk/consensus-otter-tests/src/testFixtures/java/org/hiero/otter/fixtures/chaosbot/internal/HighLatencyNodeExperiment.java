// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.chaosbot.internal;

import static org.hiero.otter.fixtures.chaosbot.internal.RandomUtil.randomGaussianDuration;

import com.swirlds.common.test.fixtures.Randotron;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.assertj.core.data.Percentage;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.network.LatencyRange;

/**
 * An experiment that introduces high latency to all connections of a target node for a duration.
 */
public class HighLatencyNodeExperiment extends Experiment {

    private static final Logger log = LogManager.getLogger();

    // These values will become configurable in the future.
    private static final Duration MEAN_DURATION = Duration.ofMinutes(2L);
    private static final Duration DURATION_DEVIATION = Duration.ofSeconds(30L);
    private static final Duration MEAN_LATENCY = Duration.ofMillis(2_000L);
    private static final Duration LATENCY_DEVIATION = Duration.ofMillis(500L);
    private static final Percentage JITTER = Percentage.withPercentage(10);

    private static final Set<Node> affectedNodes = new HashSet<>();

    private final Node targetNode;

    private HighLatencyNodeExperiment(
            @NonNull final Network network, @NonNull final Instant endTime, @NonNull final Node targetNode) {
        super(network, endTime);
        this.targetNode = targetNode;
    }

    /**
     * Creates and starts a high-latency node experiment.
     *
     * @param env the test environment
     * @param randotron the random number generator
     * @return the created experiment, or {@code null} if no suitable node was found
     */
    @Nullable
    public static HighLatencyNodeExperiment create(
            @NonNull final TestEnvironment env, @NonNull final Randotron randotron) {
        final TimeManager timeManager = env.timeManager();
        final Network network = env.network();
        final List<Node> candidates = network.nodes().stream()
                .filter(node -> !affectedNodes.contains(node))
                .toList();
        if (candidates.isEmpty()) {
            log.info("No available nodes to apply high latency experiment.");
            return null;
        }
        final Node targetNode = candidates.get(randotron.nextInt(candidates.size()));
        final LatencyRange latencyRange =
                LatencyRange.of(randomGaussianDuration(randotron, MEAN_LATENCY, LATENCY_DEVIATION), JITTER);
        network.setLatencyForAllConnections(targetNode, latencyRange);
        affectedNodes.add(targetNode);
        final Duration duration = randomGaussianDuration(randotron, MEAN_DURATION, DURATION_DEVIATION);
        log.info(
                "Started high latency node experiment for node {} with latency range {} and duration {}.",
                targetNode.selfId(),
                latencyRange,
                duration);
        return new HighLatencyNodeExperiment(network, timeManager.now().plus(duration), targetNode);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void end() {
        network.restoreLatencyForAllConnections(targetNode);
        affectedNodes.remove(targetNode);
        log.info("Ended high latency experiment for node {}.", targetNode.selfId());
    }
}
