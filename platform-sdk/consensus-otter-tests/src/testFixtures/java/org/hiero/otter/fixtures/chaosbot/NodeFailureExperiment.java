// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.chaosbot;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.test.fixtures.Randotron;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;

/**
 * An experiment that kills a target node for a duration.
 *
 * @param minDuration the minimum duration of the node failure period
 * @param maxDuration the maximum duration of the node failure period
 */
public record NodeFailureExperiment(
        @NonNull Duration minDuration, @NonNull Duration maxDuration) implements Experiment {

    private static final Logger log = LogManager.getLogger();

    private static final Duration DEFAULT_MIN_DURATION = Duration.ofMinutes(2L);
    private static final Duration DEFAULT_MAX_DURATION = Duration.ofMinutes(5L);

    /**
     * Creates a new NodeFailureExperiment with a default configuration.
     *
     * <p>The default duration range is 2 to 5 minutes.
     */
    public NodeFailureExperiment() {
        this(DEFAULT_MIN_DURATION, DEFAULT_MAX_DURATION);
    }

    /**
     * Creates a new NodeFailureExperiment with the specified weight and default configuration.
     *
     * @param minDuration the minimum duration of the node failure period
     * @param maxDuration the maximum duration of the node failure period
     * @throws NullPointerException if any argument is {@code null}
     */
    public NodeFailureExperiment(@NonNull final Duration minDuration, @NonNull final Duration maxDuration) {
        this.minDuration = requireNonNull(minDuration);
        this.maxDuration = requireNonNull(maxDuration);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String name() {
        return "Node Failure";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<Step> start(
            @NonNull final Network network, @NonNull final Instant now, @NonNull final Randotron randotron) {
        final List<Node> candidates =
                network.nodes().stream().filter(Node::isAlive).toList();
        if (candidates.isEmpty()) {
            log.info("No available nodes to apply node failure experiment.");
            return List.of();
        }
        final Node targetNode = candidates.get(randotron.nextInt(candidates.size()));
        final Duration duration = randotron.nextDuration(minDuration, maxDuration);

        log.info("Starting node failure experiment for node {} with duration {}.", targetNode.selfId(), duration);
        targetNode.killImmediately();

        return List.of(new Step(now.plus(duration), () -> {
            log.info("Ending node failure experiment for node {}.", targetNode.selfId());
            targetNode.start();
        }));
    }
}
