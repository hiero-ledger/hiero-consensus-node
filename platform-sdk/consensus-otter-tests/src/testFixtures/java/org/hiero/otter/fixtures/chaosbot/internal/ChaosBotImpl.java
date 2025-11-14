// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.chaosbot.internal;

import static java.util.Objects.requireNonNull;

import com.swirlds.common.test.fixtures.Randotron;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.chaosbot.ChaosBot;
import org.hiero.otter.fixtures.chaosbot.ChaosBotConfiguration;
import org.hiero.otter.fixtures.chaosbot.Experiment;
import org.hiero.otter.fixtures.chaosbot.Experiment.Step;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;

/**
 * Implementation of a chaos bot that creates random failures in the test environment.
 */
public class ChaosBotImpl implements ChaosBot {

    private static final Logger log = LogManager.getLogger();

    private final TestEnvironment env;
    private final Duration minInterval;
    private final Duration maxInterval;
    private final List<Experiment> experiments;
    private final Randotron randotron;
    private final PriorityQueue<Step> scheduledSteps = new PriorityQueue<>(Comparator.comparing(Step::timestamp));
    private final Map<String, Integer> statistics = new HashMap<>();

    /**
     * Create a new chaos bot.
     *
     * @param env the test environment
     * @param configuration the chaos bot configuration
     * @throws NullPointerException if any argument is {@code null}
     */
    public ChaosBotImpl(@NonNull final TestEnvironment env, @NonNull final ChaosBotConfiguration configuration) {
        this.env = requireNonNull(env);
        this.minInterval = configuration.minInterval();
        this.maxInterval = configuration.maxInterval();
        this.experiments = List.copyOf(configuration.experiments());
        this.randotron = configuration.seed() == null ? Randotron.create() : Randotron.create(configuration.seed());
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("DataFlowIssue")
    @Override
    public void runChaos(@NonNull final Duration duration) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();
        final Instant chaosEndTime = timeManager.now().plus(duration);

        scheduleNextExperiment();

        // This is the main loop of the chaos bot. Note that scheduledSteps is always non-empty because
        // scheduleNextExperiment() always adds at least one step.
        while (timeManager.now().isBefore(chaosEndTime)) {
            final Instant nextBreak = scheduledSteps.peek().timestamp();
            timeManager.waitFor(Duration.between(timeManager.now(), nextBreak));

            do {
                final Experiment.Step step = scheduledSteps.poll();
                step.action().run();
            } while (scheduledSteps.peek().timestamp().isBefore(timeManager.now()));
        }

        log.info("Chaos bot finished. Statistics of experiments run:");
        for (final Map.Entry<String, Integer> entry : statistics.entrySet()) {
            log.info("  {}: {}", entry.getKey(), entry.getValue());
        }

        // End any remaining experiments.
        network.restoreConnectivity();
        for (final Node node : network.nodes()) {
            if (!node.isAlive()) {
                node.start();
            }
        }

        // Wait until all nodes are active again.
        timeManager.waitForCondition(
                network::allNodesAreActive,
                Duration.ofMinutes(5L),
                "Not all nodes became active again after chaos bot finished");

        // Check that all nodes make progress
        for (final Node node : network.nodes()) {
            final SingleNodeConsensusResult consensusResult = node.newConsensusResult();
            final long currentRound = consensusResult.lastRoundNum();
            timeManager.waitForCondition(
                    () -> consensusResult.lastRoundNum() > currentRound,
                    Duration.ofSeconds(30L),
                    "Node " + node.selfId() + " did not make progress after chaos bot finished");
        }
    }

    private void scheduleNextExperiment() {
        final Duration delay = randotron.nextDuration(minInterval, maxInterval);
        final Experiment experiment = experiments.stream()
                .skip(randotron.nextInt(experiments.size()))
                .findFirst()
                .orElseThrow();
        log.info("Scheduling experiment {} in {}.", experiment, delay);

        final Instant startTime = env.timeManager().now().plus(delay);
        final Step startExperiment = new Step(startTime, () -> {
            final List<Step> steps = experiment.create(env.network(), startTime, randotron);
            if (steps.isEmpty()) {
                log.info("Experiment '{}' could not be started.", experiment.name());
            } else {
                scheduledSteps.addAll(steps);
                statistics.merge(experiment.name(), 1, Integer::sum);
            }
            scheduleNextExperiment();
        });
        scheduledSteps.add(startExperiment);
    }
}
