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
 * An experiment that isolates a target node from the network for a duration.
 */
public class NodeIsolationExperiment extends Experiment {

    private static final Logger log = LogManager.getLogger();

    // These values will become configurable in the future.
    private static final Duration MEAN_DURATION = Duration.ofMinutes(2L);
    private static final Duration DURATION_DEVIATION = Duration.ofSeconds(30L);

    private final Node targetNode;

    private NodeIsolationExperiment(
            @NonNull final Network network, @NonNull final Instant endTime, @NonNull final Node targetNode) {
        super(network, endTime);
        this.targetNode = targetNode;
    }

    /**
     * Creates and starts a node isolation experiment.
     *
     * @param env the test environment
     * @param randotron the random number generator
     * @return the created experiment, or {@code null} if no suitable node was found
     */
    @Nullable
    public static NodeIsolationExperiment create(
            @NonNull final TestEnvironment env, @NonNull final Randotron randotron) {
        final TimeManager timeManager = env.timeManager();
        final Network network = env.network();
        final List<Node> candidates = network.nodes().stream()
                .filter(node -> !network.isIsolated(node))
                .toList();
        if (candidates.isEmpty()) {
            log.info("No available nodes to apply node isolation experiment.");
            return null;
        }
        final Node targetNode = candidates.get(randotron.nextInt(candidates.size()));
        network.isolate(targetNode);
        final Duration duration = randomGaussianDuration(randotron, MEAN_DURATION, DURATION_DEVIATION);
        log.info("Starting node isolation experiment for node {} with duration {}.", targetNode.selfId(), duration);
        return new NodeIsolationExperiment(network, timeManager.now().plus(duration), targetNode);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void end() {
        network.rejoin(targetNode);
        log.info("Ended node isolation experiment for node {}.", targetNode.selfId());
    }
}
