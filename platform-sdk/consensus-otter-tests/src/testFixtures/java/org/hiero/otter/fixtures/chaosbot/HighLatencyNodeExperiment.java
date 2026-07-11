// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.chaosbot;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.data.Percentage.withPercentage;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.test.fixtures.Randotron;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.network.LatencyRange;

/**
 * An experiment that introduces high latency to all connections of a target node for a duration.
 *
 * @param minDuration the minimum duration of the high-latency period
 * @param maxDuration the maximum duration of the high-latency period
 * @param latencyRange the latency range to apply during the experiment
 */
public record HighLatencyNodeExperiment(
        @NonNull Duration minDuration,
        @NonNull Duration maxDuration,
        @NonNull LatencyRange latencyRange) implements Experiment {

    private static final Logger log = LogManager.getLogger();

    private static final Duration DEFAULT_MIN_DURATION = Duration.ofMinutes(2L);
    private static final Duration DEFAULT_MAX_DURATION = Duration.ofMinutes(5L);
    private static final LatencyRange DEFAULT_LATENCY_RANGE =
            LatencyRange.of(Duration.ofMillis(1_000L), Duration.ofMillis(3_000L), withPercentage(10.0));

    /** Set of nodes currently affected by this experiment. */
    private static final Set<Node> affectedNodes = new HashSet<>();

    /**
     * Creates a new HighLatencyNodeExperiment with a default configuration.
     *
     * <p>The default duration range is 2 to 5 minutes, and the default latency range is 1000 ms to 3000 ms with 10% jitter.
     */
    public HighLatencyNodeExperiment() {
        this(DEFAULT_MIN_DURATION, DEFAULT_MAX_DURATION, DEFAULT_LATENCY_RANGE);
    }

    /**
     * Creates a new HighLatencyNodeExperiment with the specified weight and default configuration.
     *
     * @param minDuration the minimum duration of the high-latency period
     * @param maxDuration the maximum duration of the high-latency period
     * @param latencyRange the latency range to apply during the experiment
     * @throws NullPointerException if any argument is {@code null}
     */
    public HighLatencyNodeExperiment {
        requireNonNull(minDuration);
        requireNonNull(maxDuration);
        requireNonNull(latencyRange);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String name() {
        return "High-Latency Node";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<Step> start(
            @NonNull final Network network, @NonNull final Instant now, @NonNull final Randotron randotron) {
        final List<Node> candidates = network.nodes().stream()
                .filter(node -> !affectedNodes.contains(node))
                .toList();
        if (candidates.isEmpty()) {
            log.info("No available nodes to apply high latency experiment.");
            return List.of();
        }
        final Node targetNode = candidates.get(randotron.nextInt(candidates.size()));
        affectedNodes.add(targetNode);
        final Duration duration = randotron.nextDuration(minDuration, maxDuration);

        log.info(
                "Starting high latency node experiment for node {} with latency range {} and duration {}.",
                targetNode.selfId(),
                latencyRange,
                duration);
        network.setLatencyForAllConnections(targetNode, latencyRange);

        return List.of(new Step(now.plus(duration), () -> {
            log.info("Ending high latency experiment for node {}.", targetNode.selfId());
            network.restoreLatencyForAllConnections(targetNode);
            affectedNodes.remove(targetNode);
        }));
    }
}
