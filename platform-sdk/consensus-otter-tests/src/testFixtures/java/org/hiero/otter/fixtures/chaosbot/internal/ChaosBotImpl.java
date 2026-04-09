// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.chaosbot.internal;

import static java.util.Objects.requireNonNull;

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
import org.hiero.consensus.test.fixtures.Randotron;
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

    /** The test environment the chaos bot is running in. */
    private final TestEnvironment env;

    /** The minimum interval between experiments. */
    private final Duration minInterval;

    /** The maximum interval between experiments. */
    private final Duration maxInterval;

    /** The list of experiments the chaos bot will run. Experiments are picked randomly. */
    private final List<Experiment> experiments;

    /**
     * The random number generator used by the chaos bot. May be initialized with a configurable seed to make
     * the chaos bot's behavior reproducible.
     */
    private final Randotron randotron;

    /** The scheduled steps of experiments to execute, ordered by their timestamp. */
    private final PriorityQueue<Step> scheduledSteps = new PriorityQueue<>(Comparator.comparing(Step::timestamp));

    /** Statistics about how many times each experiment has been run. */
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
        log.info("Run chaos bot for {}", duration);

        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();
        final Instant chaosEndTime = timeManager.now().plus(duration);

        scheduleNextExperiment();

        // This is the main loop of the chaos bot. Note that scheduledSteps is always non-empty because
        // scheduleNextExperiment() always adds at least one step and the moment an experiment is started,
        // we also call scheduleNextExperiment() to schedule the next experiment.
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

    /*
     * This method creates a new {@link Step} and adds it to {@link #scheduledSteps}. The new step will do two things
     * when executed: it will start a randomly selected experiment, and it will call
     * {@link #scheduleNextExperiment()} again to schedule the next experiment. In other words, the moment experiment A
     * is started, the next experiment B is scheduled. This ensures that there is always at least one scheduled step in
     * the queue.
     */
    private void scheduleNextExperiment() {
        // Pick a random delay and a random experiment. Chaos test should be run long enough so that each experiment
        // will be run at least once without the need to iterate through all experiments.
        final Duration delay = randotron.nextDuration(minInterval, maxInterval);
        final Experiment experiment = experiments.stream()
                .skip(randotron.nextInt(experiments.size()))
                .findFirst()
                .orElseThrow();
        log.info("Scheduling experiment {} in {}.", experiment, delay);

        final Instant startTime = env.timeManager().now().plus(delay);

        // Create a step that does two things:
        final Step startExperiment = new Step(startTime, () -> {
            // 1. Start the experiment and schedule its remaining steps
            final List<Step> remainingSteps = experiment.start(env.network(), startTime, randotron);
            if (remainingSteps.isEmpty()) {
                log.info("Experiment '{}' could not be started.", experiment.name());
            } else {
                scheduledSteps.addAll(remainingSteps);
                statistics.merge(experiment.name(), 1, Integer::sum);
            }
            // 2. Schedule the next experiment
            scheduleNextExperiment();
        });
        scheduledSteps.add(startExperiment);
    }
}
