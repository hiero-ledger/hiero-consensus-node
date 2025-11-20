// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.chaosbot.internal;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.chaosbot.internal.RandomUtil.randomGaussianDuration;

import com.swirlds.common.test.fixtures.Randotron;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.chaosbot.ChaosBot;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;

/**
 * Implementation of a chaos bot that creates random failures in the test environment.
 */
public class ChaosBotImpl implements ChaosBot {

    private static final Logger log = LogManager.getLogger();

    // These values will become configurable in the future.
    private static final Duration CHAOS_INTERVAL = Duration.ofMinutes(3L);
    private static final Duration CHAOS_DEVIATION = Duration.ofMinutes(2L);

    private final TestEnvironment env;
    private final Randotron randotron;
    private final ExperimentFactory factory;
    private final Map<Class<?>, Integer> statistics = new HashMap<>();

    /**
     * Create a new chaos bot.
     *
     * @param env the test environment
     */
    public ChaosBotImpl(@NonNull final TestEnvironment env) {
        this(env, Randotron.create());
    }

    /**
     * Create a new chaos bot with a specific random seed.
     *
     * @param env the test environment
     * @param seed the random seed
     */
    public ChaosBotImpl(@NonNull final TestEnvironment env, final long seed) {
        this(env, Randotron.create(seed));
    }

    private ChaosBotImpl(@NonNull final TestEnvironment env, @NonNull final Randotron randotron) {
        this.env = requireNonNull(env);
        this.randotron = requireNonNull(randotron);
        this.factory = new ExperimentFactory(env, randotron);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void runChaos(@NonNull final Duration duration) {
        log.info("Run chaos bot for {}", duration);

        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();
        final Instant endTime = timeManager.now().plus(duration);

        final PriorityQueue<Experiment> runningExperiments =
                new PriorityQueue<>(Comparator.comparing(Experiment::endTime));
        Instant nextStart = calculateNextStart(randotron, timeManager.now());

        while (timeManager.now().isBefore(endTime)) {
            final Instant nextBreak = findEarliestInstant(endTime, nextStart, nextExperimentEnd(runningExperiments));
            timeManager.waitFor(Duration.between(timeManager.now(), nextBreak));

            while (nextExperimentEnd(runningExperiments)
                    .isBefore(timeManager.now().plusNanos(1L))) {
                final Experiment finishedExperiment = runningExperiments.poll();
                assert finishedExperiment != null; // nextExperimentEnd would have returned Instant.MAX if empty
                finishedExperiment.end();
            }

            if (nextStart.isBefore(timeManager.now().plusNanos(1L))) {
                final Experiment experiment = factory.createExperiment();
                if (experiment != null) {
                    statistics.merge(experiment.getClass(), 1, Integer::sum);
                }
                if (experiment != null) {
                    runningExperiments.add(experiment);
                }
                nextStart = calculateNextStart(randotron, timeManager.now());
            }
        }

        log.info("Chaos bot finished. Statistics of experiments run:");
        for (final Map.Entry<Class<?>, Integer> entry : statistics.entrySet()) {
            log.info("  {}: {}", entry.getKey().getSimpleName(), entry.getValue());
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

    @NonNull
    private static Instant nextExperimentEnd(@NonNull final PriorityQueue<Experiment> runningExperiments) {
        return runningExperiments.isEmpty()
                ? Instant.MAX
                : runningExperiments.peek().endTime();
    }

    @NonNull
    private static Instant findEarliestInstant(
            @NonNull final Instant i1, @NonNull final Instant i2, @NonNull final Instant i3) {
        return i1.isBefore(i2) ? (i1.isBefore(i3) ? i1 : i3) : (i2.isBefore(i3) ? i2 : i3);
    }

    @NonNull
    private Instant calculateNextStart(@NonNull final Randotron randotron, @NonNull final Instant now) {
        return now.plus(randomGaussianDuration(randotron, CHAOS_INTERVAL, CHAOS_DEVIATION));
    }
}
