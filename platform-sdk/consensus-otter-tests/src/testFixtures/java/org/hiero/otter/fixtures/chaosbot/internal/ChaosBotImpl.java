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
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.hiero.otter.fixtures.exceptions.NetworkControlUnavailableException;
import org.hiero.otter.fixtures.exceptions.TimeoutException;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;

/**
 * Implementation of a chaos bot that creates random failures in the test environment.
 */
public class ChaosBotImpl implements ChaosBot {

    private static final Logger log = LogManager.getLogger();

    /**
     * Guards against more than one chaos bot running at a time within a single JVM.
     */
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

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

    /** The number of experiment steps that threw while being executed and were skipped. */
    private int failedSteps = 0;

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
        if (!RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("Another chaos bot is already running in this JVM.");
        }
        try {
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
                    try {
                        step.action().run();
                    } catch (final NetworkControlUnavailableException e) {
                        // A chaos run must not die because the network was momentarily too chaotic to modify: when the
                        // network-control mechanism is transiently unavailable, skip the step, record it, and carry on.
                        failedSteps++;
                        log.warn(
                                "A chaos experiment step failed and was skipped (total failed steps: {})",
                                failedSteps,
                                e);
                    }
                } while (scheduledSteps.peek().timestamp().isBefore(timeManager.now()));
            }

            log.info("Chaos bot finished. Statistics of experiments run:");
            for (final Map.Entry<String, Integer> entry : statistics.entrySet()) {
                log.info("  {}: {}", entry.getKey(), entry.getValue());
            }
            log.info("  Failed steps: {}", failedSteps);

            if (statistics.isEmpty()) {
                throw new IllegalStateException(
                        "Chaos bot did not successfully execute a single experiment; the network was never perturbed");
            }

            // End any remaining experiments.
            network.restoreConnectivity();
            for (final Node node : network.nodes()) {
                if (!node.isAlive()) {
                    node.start();
                }
            }

            // Wait until all nodes are active again
            try {
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
            } catch (final TimeoutException e) {
                dumpStuckNodes(network);
                throw e;
            }
        } finally {
            RUNNING.set(false);
        }
    }

    /**
     * Logs a JVM thread dump of every node that has not reached {@code ACTIVE}, to help diagnose why a node failed to
     * recover after chaos. If all nodes report {@code ACTIVE} (for example, a node was active but not making progress),
     * every node is dumped instead. This is diagnostic only and never changes the outcome of the test.
     *
     * @param network the network whose nodes should be inspected
     */
    private static void dumpStuckNodes(@NonNull final Network network) {
        boolean anyNonActive = false;
        for (final Node node : network.nodes()) {
            if (!node.isActive()) {
                anyNonActive = true;
                logThreadDump(node);
            }
        }
        if (!anyNonActive) {
            log.error("All nodes report ACTIVE, yet recovery still failed; dumping every node.");
            for (final Node node : network.nodes()) {
                logThreadDump(node);
            }
        }
    }

    /**
     * Captures and logs a JVM thread dump of a single node.
     *
     * @param node the node to dump
     */
    private static void logThreadDump(@NonNull final Node node) {
        log.error(
                "Recovery diagnostic — node {} did not recover (status {}):\n"
                        + "===== THREAD DUMP node {} =====\n{}\n===== END THREAD DUMP node {} =====",
                node.selfId(),
                node.platformStatus(),
                node.selfId(),
                node.dumpThreads(),
                node.selfId());
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
            try {
                // 1. Start the experiment and schedule its remaining steps
                final List<Step> remainingSteps = experiment.start(env.network(), startTime, randotron);
                if (remainingSteps.isEmpty()) {
                    log.info("Experiment '{}' could not be started.", experiment.name());
                } else {
                    scheduledSteps.addAll(remainingSteps);
                    statistics.merge(experiment.name(), 1, Integer::sum);
                }
            } finally {
                // 2. Schedule the next experiment. This runs even if starting the experiment threw, so the
                //    scheduled-steps queue that the main loop relies on can never be left empty.
                scheduleNextExperiment();
            }
        });
        scheduledSteps.add(startExperiment);
    }
}
