// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.chaosbot;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.test.fixtures.Randotron;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;

/**
 * An experiment that isolates a target node from the network for a duration, turning connectivity on and off in short
 * periods.
 *
 * @param minDuration           the minimum duration of the entire experiment
 * @param maxDuration           the maximum duration of the entire experiment
 * @param minDisconnectDuration the minimum duration of the single disconnect event
 * @param maxDisconnectDuration the maximum duration of the single disconnect event
 * @param minConnectedDuration  the minimum duration of pause between disconnect, when connectivity works correctly
 * @param maxConnectedDuration  the maximum duration of pause between disconnect, when connectivity works correctly
 */
public record FlickeringIsolationExperiment(
        @NonNull Duration minDuration,
        @NonNull Duration maxDuration,
        @NonNull Duration minDisconnectDuration,
        @NonNull Duration maxDisconnectDuration,
        @NonNull Duration minConnectedDuration,
        @NonNull Duration maxConnectedDuration)
        implements Experiment {

    private static final Logger log = LogManager.getLogger();

    private static final Duration DEFAULT_MIN_DURATION = Duration.ofMinutes(2L);
    private static final Duration DEFAULT_MAX_DURATION = Duration.ofMinutes(5L);
    private static final Duration DEFAULT_MIN_DISCONNECT_DURATION = Duration.ofSeconds(1);
    private static final Duration DEFAULT_MAX_DISCONNECT_DURATION = Duration.ofSeconds(2);
    private static final Duration DEFAULT_MIN_CONNECTED_DURATION = Duration.ofSeconds(1);
    private static final Duration DEFAULT_MAX_CONNECTED_DURATION = Duration.ofSeconds(2);

    /**
     * Creates a new NodeIsolationExperiment with a default configuration.
     *
     * <p>The default duration range is 2 to 5 minutes.
     */
    public FlickeringIsolationExperiment() {
        this(
                DEFAULT_MIN_DURATION,
                DEFAULT_MAX_DURATION,
                DEFAULT_MIN_DISCONNECT_DURATION,
                DEFAULT_MAX_DISCONNECT_DURATION,
                DEFAULT_MIN_CONNECTED_DURATION,
                DEFAULT_MAX_CONNECTED_DURATION);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String name() {
        return "Node Isolation";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<Step> start(
            @NonNull final Network network, @NonNull final Instant now, @NonNull final Randotron randotron) {
        final List<Node> candidates = network.nodes().stream()
                .filter(node -> !network.isIsolated(node))
                .toList();
        if (candidates.isEmpty()) {
            log.info("No available nodes to apply flickering node isolation experiment.");
            return List.of();
        }
        final Node targetNode = candidates.get(randotron.nextInt(candidates.size()));
        final Duration totalDuration = randotron.nextDuration(minDuration, maxDuration);

        log.info(
                "Starting flickering node isolation experiment for node {} with totalDuration {}.",
                targetNode.selfId(),
                totalDuration);
        network.isolate(targetNode);

        final List<Step> steps = new ArrayList<>();

        final Instant endOfExperiment = now.plus(totalDuration);
        Instant stepNow = now;

        while (true) {
            stepNow = stepNow.plus(randotron.nextDuration(minDisconnectDuration, maxDisconnectDuration));
            if (stepNow.isAfter(endOfExperiment)) {
                steps.add(new Step(endOfExperiment, () -> {
                    log.info("Ending flickering node isolation experiment for node {}.", targetNode.selfId());
                    network.rejoin(targetNode);
                }));
                break;
            } else {
                steps.add(new Step(stepNow, () -> {
                    network.rejoin(targetNode);
                }));
            }

            stepNow = stepNow.plus(randotron.nextDuration(minConnectedDuration, maxConnectedDuration));
            if (stepNow.isBefore(endOfExperiment)) {
                steps.add(new Step(stepNow, () -> {
                    network.isolate(targetNode);
                }));
            } else {
                log.info("Ending flickering node isolation experiment for node {}.", targetNode.selfId());
                break;
            }
        }

        return steps;
    }
}
