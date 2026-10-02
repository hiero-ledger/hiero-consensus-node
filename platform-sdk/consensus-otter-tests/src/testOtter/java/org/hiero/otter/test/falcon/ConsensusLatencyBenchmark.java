// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test.falcon;

import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.assertj.core.data.Percentage;
import org.hiero.consensus.event.creator.config.EventCreationConfig_;
import org.hiero.consensus.test.fixtures.WeightGenerators;
import org.hiero.consensus.test.fixtures.io.RealisticPingSamples;
import org.hiero.otter.fixtures.FalconTest;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.falcon.ConsensusLatencyResult;
import org.hiero.otter.fixtures.falcon.FalconTestEnvironment;
import org.hiero.otter.fixtures.network.BandwidthLimit;
import org.hiero.otter.fixtures.network.MeshTopologyConfiguration;
import org.hiero.otter.fixtures.network.PredefinedPingTopologyConfiguration;
import org.hiero.otter.fixtures.network.TopologyConfiguration;
import org.junit.jupiter.api.Disabled;

/**
 * Benchmarks that measure the creation-to-consensus latency (C2C) and the event throughput of a network under
 * different network sizes, latencies and numbers of other parents, in simulated time.
 *
 * <p>These are the Falcon counterparts of the benchmarks in {@code NetworkSimulationTest} of the
 * {@code consensus-network-simulation} module. They make no assertions on the measured values and are therefore
 * disabled; they are meant to be run manually, and print their results to standard output.
 *
 * <p>Every benchmark uses a pinned seed, so its results are reproducible. Parameter sweeps create a fresh
 * {@link FalconTestEnvironment} for every point of the sweep, because a Falcon test invocation provides only one
 * environment.
 *
 * <p>The numbers are not expected to match those of {@code NetworkSimulationTest} exactly. The old harness measured
 * with a single consensus engine that saw every event at the moment it was created, while Falcon measures each node's
 * own consensus engine, which sees the events of other nodes only after the simulated network latency.
 */
class ConsensusLatencyBenchmark {

    /** The seed used by all benchmarks. Must not be zero, because a seed of zero makes a Falcon test run a sweep. */
    private static final long SEED = 1L;

    /** The reason all benchmarks are disabled. */
    private static final String DISABLED_REASON = "Benchmark without assertions, meant to be run manually";

    /** The configuration key of the scheduler of the orphan buffer. */
    private static final String ORPHAN_BUFFER_SCHEDULER = "event.intake.wiring.orphanBuffer";

    /** The configuration key of the scheduler of the event creation manager. */
    private static final String EVENT_CREATION_MANAGER_SCHEDULER = "event.creation.wiring.eventCreationManager";

    /** No jitter, so that the results are comparable with those of the old harness, which had none. */
    private static final Percentage NO_JITTER = Percentage.withPercentage(0);

    /** The header of the CSV printed by the sweeps over the number of other parents. */
    private static final String MOP_HEADER = "MaxParents;avgC2C;maxC2C;events/s;bytes/s";

    /**
     * The parameters of a single benchmark run.
     *
     * @param nodes the number of nodes in the network
     * @param granularity the granularity of the simulation, i.e. the simulated time that passes with each tick
     * @param duration the simulated time the network runs for after it has been started
     * @param maxCreationRate the maximum rate at which each node creates events, in Hz
     * @param maxOtherParents the maximum number of other parents of an event
     * @param topology the topology of the network, which defines the latencies between nodes
     */
    private record BenchmarkSetup(
            int nodes,
            @NonNull Duration granularity,
            @NonNull Duration duration,
            double maxCreationRate,
            int maxOtherParents,
            @NonNull TopologyConfiguration topology) {}

    /**
     * A 32 node network with the latencies of mainnet.
     *
     * @param env the test environment for this test
     */
    @FalconTest(randomSeed = SEED, granularityMicros = 5_000L)
    @Disabled(DISABLED_REASON)
    void mainnet(@NonNull final FalconTestEnvironment env) {
        final BenchmarkSetup setup =
                new BenchmarkSetup(32, Duration.ofMillis(5), Duration.ofSeconds(10), 20, 4, mainnetTopology());
        System.out.println(run(env, setup));
    }

    /**
     * A 32 node network with the latencies of mainnet, run once for every possible number of other parents.
     */
    @FalconTest(randomSeed = SEED)
    @Disabled(DISABLED_REASON)
    void mainnetMopComparison() {
        final int numNodes = 32;
        final ConsensusLatencyResult[] results = new ConsensusLatencyResult[numNodes];

        for (int maxParents = 1; maxParents < numNodes; maxParents++) {
            final BenchmarkSetup setup = new BenchmarkSetup(
                    numNodes, Duration.ofMillis(1), Duration.ofSeconds(10), 20, maxParents, mainnetTopology());
            results[maxParents] = run(SEED, setup);
        }

        System.out.println(MOP_HEADER);
        for (int maxParents = 1; maxParents < numNodes; maxParents++) {
            printMillisRow(maxParents, results[maxParents]);
        }
    }

    /**
     * A 7 node network with a uniform latency of 300µs, run once for every possible number of other parents.
     */
    @FalconTest(randomSeed = SEED)
    @Disabled(DISABLED_REASON)
    void latitudeMopComparison() {
        final int numNodes = 7;
        final Duration tick = Duration.ofNanos(300_000L);
        final ConsensusLatencyResult[] results = new ConsensusLatencyResult[numNodes];

        for (int maxParents = 1; maxParents < numNodes; maxParents++) {
            final BenchmarkSetup setup =
                    new BenchmarkSetup(numNodes, tick, Duration.ofSeconds(10), 20, maxParents, uniformTopology(tick));
            results[maxParents] = run(SEED, setup);
        }

        System.out.println(MOP_HEADER);
        for (int maxParents = 1; maxParents < numNodes; maxParents++) {
            printMillisRow(maxParents, results[maxParents]);
        }
    }

    /**
     * Networks of 2 to 20 nodes with a uniform latency of 300µs and a high maximum creation rate.
     */
    @FalconTest(randomSeed = SEED)
    @Disabled(DISABLED_REASON)
    void ententeSizeComparison() {
        final int maxParents = 4;
        final int maxNumNodes = 20;
        final Duration tick = Duration.ofNanos(300_000L);
        final ConsensusLatencyResult[] results = new ConsensusLatencyResult[maxNumNodes + 1];

        for (int numNodes = 2; numNodes <= maxNumNodes; numNodes++) {
            final BenchmarkSetup setup =
                    new BenchmarkSetup(numNodes, tick, Duration.ofSeconds(10), 3000, maxParents, uniformTopology(tick));
            results[numNodes] = run(SEED, setup);
        }

        System.out.println(MOP_HEADER);
        for (int numNodes = 2; numNodes <= maxNumNodes; numNodes++) {
            final ConsensusLatencyResult result = results[numNodes];
            System.out.printf(
                    "%d;%s;%s;%d;%d%n",
                    numNodes,
                    toSeconds(result.averageC2C()),
                    toSeconds(result.maxC2C()),
                    result.eventsPerSec(),
                    result.bytesPerSec());
        }
    }

    /**
     * Networks of 2 to 20 nodes with a uniform latency of 300µs, run for every possible number of other parents. Prints
     * a matrix of the average C2C, with one row per network size and one column per number of other parents.
     */
    @FalconTest(randomSeed = SEED)
    @Disabled(DISABLED_REASON)
    void ententeSizeParentMatrix() {
        final int maxNumNodes = 20;
        final Duration tick = Duration.ofNanos(300_000L);
        final ConsensusLatencyResult[][] results = new ConsensusLatencyResult[maxNumNodes + 1][maxNumNodes];

        for (int numNodes = 2; numNodes <= maxNumNodes; numNodes++) {
            for (int maxParents = 1; maxParents < numNodes; maxParents++) {
                final BenchmarkSetup setup = new BenchmarkSetup(
                        numNodes, tick, Duration.ofSeconds(2), 3000, maxParents, uniformTopology(tick));
                results[numNodes][maxParents] = run(SEED, setup);
            }
        }

        for (int numNodes = 2; numNodes <= maxNumNodes; numNodes++) {
            System.out.print(";" + numNodes);
        }
        System.out.println();

        for (int numNodes = 2; numNodes <= maxNumNodes; numNodes++) {
            System.out.print(numNodes + ";");
            for (int maxParents = 1; maxParents < numNodes; maxParents++) {
                System.out.print(toSeconds(results[numNodes][maxParents].averageC2C()) + ";");
            }
            System.out.println();
        }
    }

    /**
     * A small 4 node network with a uniform latency of 100µs, for quick experiments.
     *
     * @param env the test environment for this test
     */
    @FalconTest(randomSeed = SEED, granularityMicros = 100L)
    @Disabled(DISABLED_REASON)
    void fastFourNodeNetwork(@NonNull final FalconTestEnvironment env) {
        final Duration tick = Duration.ofNanos(100_000L);
        final BenchmarkSetup setup = new BenchmarkSetup(4, tick, Duration.ofMillis(100), 20, 16, uniformTopology(tick));
        System.out.println(run(env, setup));
    }

    /**
     * Runs a single benchmark in a fresh environment, which is destroyed afterward.
     *
     * @param seed the seed of the environment
     * @param setup the parameters of the benchmark
     * @return the measured latency and throughput
     */
    @NonNull
    private static ConsensusLatencyResult run(final long seed, @NonNull final BenchmarkSetup setup) {
        final FalconTestEnvironment env = new FalconTestEnvironment(seed, setup.granularity());
        try {
            return run(env, setup);
        } finally {
            env.destroy();
        }
    }

    /**
     * Runs a single benchmark in the given environment. The granularity of the environment must match the one of the
     * setup.
     *
     * <p>Besides the parameters of the setup, the network is configured so that its timing resembles the one of the
     * old harness:
     * <ul>
     *     <li>All nodes have the same weight.</li>
     *     <li>Event creation is attempted on every tick. With the default period of 10ms, the creation rate would be
     *     capped at 100 events per second, regardless of the configured maximum creation rate.</li>
     *     <li>The orphan buffer and the event creation manager run as {@code DIRECT} schedulers. The deterministic
     *     wiring model defers the work of all other schedulers to the next tick, which would add several ticks of
     *     artificial latency to every gossip hop.</li>
     * </ul>
     * The consistency of the rounds is asserted continuously, so that a problem caused by this configuration fails the
     * benchmark instead of producing wrong numbers.
     *
     * @param env the environment to run the benchmark in
     * @param setup the parameters of the benchmark
     * @return the measured latency and throughput
     */
    @NonNull
    private static ConsensusLatencyResult run(
            @NonNull final FalconTestEnvironment env, @NonNull final BenchmarkSetup setup) {
        final Network network = env.network();
        network.topology(setup.topology());
        network.weightGenerator(WeightGenerators.BALANCED);
        network.withConfigValue(EventCreationConfig_.MAX_CREATION_RATE, setup.maxCreationRate())
                .withConfigValue(EventCreationConfig_.MAX_OTHER_PARENTS, setup.maxOtherParents())
                .withConfigValue(EventCreationConfig_.PERIOD, setup.granularity())
                .withConfigValue(ORPHAN_BUFFER_SCHEDULER, "DIRECT")
                .withConfigValue(EVENT_CREATION_MANAGER_SCHEDULER, "DIRECT");
        network.addNodes(setup.nodes());

        assertContinuouslyThat(network.newConsensusResults())
                .haveEqualCommonRounds()
                .haveConsistentRounds();

        network.start();
        env.timeManager().waitFor(setup.duration());
        return env.consensusLatency();
    }

    /**
     * Creates the topology of mainnet, based on its measured ping times, without jitter.
     *
     * @return the topology configuration
     */
    @NonNull
    private static TopologyConfiguration mainnetTopology() {
        return new PredefinedPingTopologyConfiguration(
                RealisticPingSamples.MAINNET, NO_JITTER, BandwidthLimit.UNLIMITED_BANDWIDTH);
    }

    /**
     * Creates a topology in which every connection has the same one-way latency, without jitter.
     *
     * @param latency the one-way latency of every connection
     * @return the topology configuration
     */
    @NonNull
    private static TopologyConfiguration uniformTopology(@NonNull final Duration latency) {
        return new MeshTopologyConfiguration(latency, NO_JITTER, BandwidthLimit.UNLIMITED_BANDWIDTH);
    }

    /**
     * Prints one row of a sweep over the number of other parents, with latencies in seconds at millisecond precision.
     *
     * @param maxParents the maximum number of other parents of this row
     * @param result the result of this row
     */
    private static void printMillisRow(final int maxParents, @NonNull final ConsensusLatencyResult result) {
        System.out.printf(
                "%d;%s;%s;%d;%d%n",
                maxParents,
                result.averageC2C().toMillis() / 1000.0,
                result.maxC2C().toMillis() / 1000.0,
                result.eventsPerSec(),
                result.bytesPerSec());
    }

    /**
     * Converts a duration to seconds at nanosecond precision.
     *
     * @param duration the duration
     * @return the duration in seconds
     */
    private static double toSeconds(@NonNull final Duration duration) {
        return duration.toNanos() / 1_000_000_000.0;
    }
}
