// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.chaosbot;

import static java.util.Objects.requireNonNull;

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
import org.hiero.otter.fixtures.network.BandwidthLimit;

/**
 * An experiment that introduces low bandwidth to all connections of a target node for a duration.
 *
 * @param minDuration the minimum duration of the low-bandwidth period
 * @param maxDuration the maximum duration of the low-bandwidth period
 * @param minBandwidthLimit the minimum bandwidth limit to apply during the experiment
 * @param maxBandwidthLimit the maximum bandwidth limit to apply during the experiment
 */
public record LowBandwidthNodeExperiment(
        @NonNull Duration minDuration,
        @NonNull Duration maxDuration,
        @NonNull BandwidthLimit minBandwidthLimit,
        @NonNull BandwidthLimit maxBandwidthLimit)
        implements Experiment {

    private static final Logger log = LogManager.getLogger();

    private static final Duration DEFAULT_MIN_DURATION = Duration.ofMinutes(2L);
    private static final Duration DEFAULT_MAX_DURATION = Duration.ofMinutes(5L);
    private static final BandwidthLimit DEFAULT_MIN_BANDWIDTH_LIMIT = BandwidthLimit.ofKilobytesPerSecond(1);
    private static final BandwidthLimit DEFAULT_MAX_BANDWIDTH_LIMIT = BandwidthLimit.ofKilobytesPerSecond(5);

    /** Set of nodes currently affected by this experiment. */
    private static final Set<Node> affectedNodes = new HashSet<>();

    /**
     * Creates a new LowBandwidthNodeExperiment with a default configuration.
     *
     * <p>The default duration range is 2 to 5 minutes, and the default bandwidth limit range is 1 KB/s to 5 KB/s.
     */
    public LowBandwidthNodeExperiment() {
        this(DEFAULT_MIN_DURATION, DEFAULT_MAX_DURATION, DEFAULT_MIN_BANDWIDTH_LIMIT, DEFAULT_MAX_BANDWIDTH_LIMIT);
    }

    /**
     * Creates a new LowBandwidthNodeExperiment with the specified weight and default configuration.
     *
     * @param minDuration the minimum duration of the low-bandwidth period
     * @param maxDuration the maximum duration of the low-bandwidth period
     * @param minBandwidthLimit the minimum bandwidth limit to apply during the experiment
     * @param maxBandwidthLimit the maximum bandwidth limit to apply during the experiment
     * @throws NullPointerException if any argument is null
     */
    public LowBandwidthNodeExperiment(
            @NonNull final Duration minDuration,
            @NonNull final Duration maxDuration,
            @NonNull final BandwidthLimit minBandwidthLimit,
            @NonNull final BandwidthLimit maxBandwidthLimit) {
        this.minDuration = requireNonNull(minDuration);
        this.maxDuration = requireNonNull(maxDuration);
        this.minBandwidthLimit = requireNonNull(minBandwidthLimit);
        this.maxBandwidthLimit = requireNonNull(maxBandwidthLimit);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String name() {
        return "Low-Bandwidth Node";
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
            log.info("No available nodes to apply low bandwidth experiment.");
            return List.of();
        }
        final Node targetNode = candidates.get(randotron.nextInt(candidates.size()));
        affectedNodes.add(targetNode);
        final int minimum = minBandwidthLimit.toKilobytesPerSecond();
        final int maximum = maxBandwidthLimit.toKilobytesPerSecond();
        final int value = randotron.nextInt(maximum - minimum) + minimum;
        final BandwidthLimit bandwidthLimit = BandwidthLimit.ofKilobytesPerSecond(value);
        final Duration duration = randotron.nextDuration(minDuration, maxDuration);

        log.info(
                "Starting low bandwidth node experiment for node {} with bandwidth limit {} and duration {}.",
                targetNode.selfId(),
                bandwidthLimit,
                duration);
        network.setBandwidthForAllConnections(targetNode, bandwidthLimit);

        return List.of(new Step(now.plus(duration), () -> {
            log.info("Ending low bandwidth experiment for node {}.", targetNode.selfId());
            network.restoreBandwidthLimitsForAllConnections(targetNode);
            affectedNodes.remove(targetNode);
        }));
    }
}
