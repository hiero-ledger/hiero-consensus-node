// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.chaosbot.internal;

import static org.hiero.otter.fixtures.chaosbot.internal.RandomUtil.randomGaussianBandwidthLimit;
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
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.network.BandwidthLimit;

/**
 * An experiment that introduces low bandwidth to all connections of a target node for a duration.
 */
public class LowBandwidthNodeExperiment extends Experiment {

    private static final Logger log = LogManager.getLogger();

    // These values will become configurable in the future.
    private static final Duration MEAN_DURATION = Duration.ofMinutes(2L);
    private static final Duration DURATION_DEVIATION = Duration.ofSeconds(30L);
    private static final BandwidthLimit MEAN_BANDWIDTH_LIMIT = BandwidthLimit.ofKilobytesPerSecond(5);
    private static final BandwidthLimit BANDWIDTH_DEVIATION = BandwidthLimit.ofKilobytesPerSecond(2);

    private static final Set<Node> affectedNodes = new HashSet<>();

    private final Node targetNode;

    private LowBandwidthNodeExperiment(
            @NonNull final Network network, @NonNull final Instant endTime, @NonNull final Node targetNode) {
        super(network, endTime);
        this.targetNode = targetNode;
    }

    /**
     * Creates and starts a low-bandwidth node experiment.
     *
     * @param env the test environment
     * @param randotron the random number generator
     * @return the created experiment, or {@code null} if no suitable node was found
     */
    @Nullable
    public static LowBandwidthNodeExperiment create(
            @NonNull final TestEnvironment env, @NonNull final Randotron randotron) {
        final TimeManager timeManager = env.timeManager();
        final Network network = env.network();
        final List<Node> candidates = network.nodes().stream()
                .filter(node -> !affectedNodes.contains(node))
                .toList();
        if (candidates.isEmpty()) {
            log.info("No available nodes to apply low bandwidth experiment.");
            return null;
        }
        final Node targetNode = candidates.get(randotron.nextInt(candidates.size()));
        final BandwidthLimit bandwidthLimit =
                randomGaussianBandwidthLimit(randotron, MEAN_BANDWIDTH_LIMIT, BANDWIDTH_DEVIATION);
        network.setBandwidthForAllConnections(targetNode, bandwidthLimit);
        affectedNodes.add(targetNode);
        final Duration duration = randomGaussianDuration(randotron, MEAN_DURATION, DURATION_DEVIATION);
        log.info(
                "Starting low bandwidth node experiment for node {} with bandwidth limit {} and duration {}.",
                targetNode.selfId(),
                bandwidthLimit,
                duration);
        return new LowBandwidthNodeExperiment(network, timeManager.now().plus(duration), targetNode);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void end() {
        network.restoreBandwidthLimitsForAllConnections(targetNode);
        affectedNodes.remove(targetNode);
        log.info("Ended low bandwidth experiment for node {}.", targetNode.selfId());
    }
}
