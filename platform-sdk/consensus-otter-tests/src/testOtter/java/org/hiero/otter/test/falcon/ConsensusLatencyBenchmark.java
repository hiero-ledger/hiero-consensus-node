// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test.falcon;

import static org.assertj.core.api.Assertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.assertj.core.data.Percentage;
import org.hiero.base.crypto.SigningSchema;
import org.hiero.consensus.event.creator.config.EventCreationConfig_;
import org.hiero.consensus.event.creator.config.EventCreationWiringConfig_;
import org.hiero.consensus.event.intake.config.EventIntakeWiringConfig_;
import org.hiero.consensus.test.fixtures.WeightGenerators;
import org.hiero.consensus.test.fixtures.io.RealisticPingSamples;
import org.hiero.otter.fixtures.FalconTest;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.falcon.ConsensusLatencyResult;
import org.hiero.otter.fixtures.falcon.FalconNetwork;
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
 * {@code consensus-network-simulation} module, and print reports with the same layout. Apart from {@link #smoke()},
 * they make no assertions and are therefore disabled; they are meant to be run manually, and print their results to
 * standard output. Sweeps print semicolon-separated reports with latencies in seconds.
 *
 * <p>Each network has a single full node, which runs the only consensus engine of the network and observes the C2C,
 * and mini-nodes for the rest. A mini-node only creates and gossips events and takes its event window from its peers,
 * so the cost of a run grows with the number of events rather than with the number of consensus engines, as in the old
 * harness. Where the latencies differ between nodes, the C2C depends on which node observes it, so a run can choose the
 * position of the full node among the nodes, which is also its row in a ping matrix.
 *
 * <p>Every benchmark uses a pinned seed, so its results are reproducible. Each run creates a fresh
 * {@link FalconTestEnvironment}, with the granularity the run needs, because the environment a Falcon test invocation
 * provides has the default granularity.
 *
 * <p>The numbers are not expected to match those of {@code NetworkSimulationTest} exactly. With uniform latencies
 * they come close; with the mainnet latencies the C2C is higher here, for these reasons, from the largest effect to the
 * smallest:
 * <ul>
 *     <li>The event creators of the old harness built on every event as soon as it arrived, even before its parents
 *     had. Here, as in production, an event waits in a node's intake until its parents are there. With the mainnet
 *     latencies this holds back the whole network: node 16 reaches nodes 0 to 15 quickly, but its links to nodes 17
 *     to 27 are marked {@link RealisticPingSamples#BROKEN}. The events that nodes 0 to 15 build on node 16's events
 *     wait at nodes 17 to 27 until node 16's events get there, and without those 11 of the 28 nodes no round can
 *     reach a supermajority. A node that is equally slow to all others, or not connected at all, does not have this
 *     effect, because no event builds on its events before every node has them.</li>
 *     <li>The old harness fed every event to its consensus engine at the moment it was created. Here the full node
 *     sees the events of other nodes only once they have arrived and their parents are there, as a real node
 *     does.</li>
 *     <li>The old harness gave every event a transaction received when the event was created, so the creation time of
 *     an event was the time it was created. Here events carry no transactions, and the event creator takes their
 *     creation time from when their latest parent was received, which can be earlier.</li>
 *     <li>The old harness pushed every event window to all event creators at once. Here mini-nodes get it by gossip,
 *     with the network latency.</li>
 *     <li>The consensus engine of the full node keeps its scheduler, so that, as in production, the event creator gets
 *     each event before the event window it causes. That adds up to one tick to every C2C.</li>
 * </ul>
 */
class ConsensusLatencyBenchmark {

    /** The seed used by all benchmarks. Must not be zero, because a seed of zero makes a Falcon test run a sweep. */
    private static final long SEED = 1L;

    /** The reason all benchmarks without assertions are disabled. */
    private static final String DISABLED_REASON = "Benchmark without assertions, meant to be run manually";

    /** The scheduler type of the components of the full nodes that must not delay events. */
    private static final String DIRECT = "DIRECT";

    /** No jitter, so that the results are comparable with those of the old harness, which had none. */
    private static final Percentage NO_JITTER = Percentage.withPercentage(0);

    /** The name of the key column of the reports of the sweeps over the number of other parents. */
    private static final String MAX_PARENTS_COLUMN = "MaxParents";

    /** The name of the key column of the reports of the sweeps over the number of nodes. */
    private static final String NUM_NODES_COLUMN = "NumNodes";

    /** The name of the key column of the reports of the sweeps over the observing node. */
    private static final String OBSERVER_COLUMN = "Observer";

    /** The number of full nodes of every network: one, which observes the C2C as the old harness did. */
    private static final int FULL_NODES = 1;

    /**
     * The time a network with the mainnet latencies runs before it is measured, so that the measurement leaves out the
     * start of the network, before its nodes have settled into a steady pace of rounds. The links have no reconnect
     * delay at the start, because the first connections are taken to be established already, so the links marked
     * {@link RealisticPingSamples#BROKEN} do not hold up the start either. The old harness had no such start-up.
     */
    private static final Duration MAINNET_WARM_UP = Duration.ofSeconds(5);

    /**
     * The row of {@link RealisticPingSamples#MAINNET} that the full node takes in the mainnet benchmarks, which is the
     * node that observes the C2C. The C2C depends on the observer: over the seeds 1 to 4 the mean C2C averages 1.45 s
     * over all rows, from 1.29 s for row 14 to 1.70 s for row 16, which only hears from nodes 17 to 27 through others.
     * Row 24 is a typical observer, at 1.43 s, and varies little from seed to seed. The mean C2C of a single run varies
     * by about 0.08 s from seed to seed. See {@link #mainnetObserverComparison()}.
     * For observing the fastest possible connectivity, try {@code MAINNET_OBSERVER = 2}
     */
    private static final int MAINNET_OBSERVER = 24;

    /**
     * The parameters of a single benchmark run.
     *
     * @param nodes the number of nodes in the network, full nodes and mini-nodes alike
     * @param granularity the granularity of the simulation, i.e. the simulated time that passes with each tick
     * @param duration the simulated time the network runs for after it has been started
     * @param maxCreationRate the maximum rate at which each node creates events, in Hz
     * @param maxOtherParents the maximum number of other parents of an event
     * @param topology the topology of the network, which defines the latencies between nodes
     * @param observer the position of the full node among all nodes, which is its row in a ping matrix
     */
    private record BenchmarkSetup(
            int nodes,
            @NonNull Duration granularity,
            @NonNull Duration duration,
            double maxCreationRate,
            int maxOtherParents,
            @NonNull TopologyConfiguration topology,
            int observer) {

        /**
         * @throws IllegalArgumentException if the observer is not the position of one of the nodes
         */
        BenchmarkSetup {
            if (observer < 0 || observer > nodes - FULL_NODES) {
                throw new IllegalArgumentException("The observer must be between 0 and " + (nodes - FULL_NODES));
            }
        }

        /**
         * Creates the parameters of a run whose full node comes first, see
         * {@link #BenchmarkSetup(int, Duration, Duration, double, int, TopologyConfiguration, int)}.
         */
        BenchmarkSetup(
                final int nodes,
                @NonNull final Duration granularity,
                @NonNull final Duration duration,
                final double maxCreationRate,
                final int maxOtherParents,
                @NonNull final TopologyConfiguration topology) {
            this(nodes, granularity, duration, maxCreationRate, maxOtherParents, topology, 0);
        }
    }

    /**
     * A short run of a 12 node network, which checks that the benchmark setup keeps working. The counterpart of
     * {@code NetworkSimulationTest.smokeTest}.
     */
    @FalconTest(randomSeed = SEED)
    void smoke() {
        final Duration tick = Duration.ofMillis(5);
        final ConsensusLatencyResult result =
                run(new BenchmarkSetup(12, tick, Duration.ofSeconds(2), 20, 4, uniformTopology(tick)));

        assertThat(result.nodes()).isEqualTo(12);
        assertThat(result.c2cSamples()).isPositive();
        assertThat(result.eventsPerSec()).isPositive();
        assertThat(result.bytesPerSec()).isPositive();
    }

    /**
     * A 28 node network with the latencies of mainnet.
     */
    @FalconTest(randomSeed = SEED)
    @Disabled(DISABLED_REASON)
    void mainnet() {
        System.out.println(run(
                new BenchmarkSetup(
                        RealisticPingSamples.MAINNET.length,
                        Duration.ofMillis(5),
                        Duration.ofSeconds(10),
                        20,
                        4,
                        mainnetTopology(),
                        MAINNET_OBSERVER),
                MAINNET_WARM_UP));
    }

    /**
     * A 28 node network with the latencies of mainnet, run once with every node as the observer of the C2C.
     */
    @FalconTest(randomSeed = SEED)
    @Disabled(DISABLED_REASON)
    void mainnetObserverComparison() {
        final int numNodes = RealisticPingSamples.MAINNET.length;
        final ConsensusLatencyResult[] results = new ConsensusLatencyResult[numNodes];

        for (int observer = 0; observer < numNodes; observer++) {
            results[observer] = run(
                    new BenchmarkSetup(
                            numNodes, Duration.ofMillis(5), Duration.ofSeconds(10), 20, 4, mainnetTopology(), observer),
                    MAINNET_WARM_UP);
        }

        System.out.println(ConsensusLatencyResult.csvHeader(OBSERVER_COLUMN));
        for (int observer = 0; observer < numNodes; observer++) {
            System.out.println(results[observer].toCsvRow(String.valueOf(observer)));
        }
    }

    /**
     * A 28 node network with the latencies of mainnet, run once for every possible number of other parents.
     */
    @FalconTest(randomSeed = SEED)
    @Disabled(DISABLED_REASON)
    void mainnetMopComparison() {
        final int numNodes = RealisticPingSamples.MAINNET.length;
        final ConsensusLatencyResult[] results = new ConsensusLatencyResult[numNodes];

        for (int maxParents = 1; maxParents < numNodes; maxParents++) {
            results[maxParents] = run(
                    new BenchmarkSetup(
                            numNodes,
                            Duration.ofMillis(1),
                            Duration.ofSeconds(10),
                            20,
                            maxParents,
                            mainnetTopology(),
                            MAINNET_OBSERVER),
                    MAINNET_WARM_UP);
        }

        System.out.println(ConsensusLatencyResult.csvHeader(MAX_PARENTS_COLUMN));
        for (int maxParents = 1; maxParents < numNodes; maxParents++) {
            System.out.println(results[maxParents].toCsvRow(String.valueOf(maxParents)));
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
            results[maxParents] = run(
                    new BenchmarkSetup(numNodes, tick, Duration.ofSeconds(10), 20, maxParents, uniformTopology(tick)));
        }

        System.out.println(ConsensusLatencyResult.csvHeader(MAX_PARENTS_COLUMN));
        for (int maxParents = 1; maxParents < numNodes; maxParents++) {
            System.out.println(results[maxParents].toCsvRow(String.valueOf(maxParents)));
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
            results[numNodes] = run(new BenchmarkSetup(
                    numNodes, tick, Duration.ofSeconds(10), 3000, maxParents, uniformTopology(tick)));
        }

        System.out.println(ConsensusLatencyResult.csvHeader(NUM_NODES_COLUMN));
        for (int numNodes = 2; numNodes <= maxNumNodes; numNodes++) {
            System.out.println(results[numNodes].toCsvRow(String.valueOf(numNodes)));
        }
    }

    /**
     * Networks of 2 to 20 nodes with a uniform latency of 300µs, run for every possible number of other parents. Prints
     * a matrix of the mean C2C in seconds, with one row per network size and one column per number of other parents.
     */
    @FalconTest(randomSeed = SEED)
    @Disabled(DISABLED_REASON)
    void ententeSizeParentMatrix() {
        final int maxNumNodes = 20;
        final Duration tick = Duration.ofNanos(300_000L);
        final ConsensusLatencyResult[][] results = new ConsensusLatencyResult[maxNumNodes + 1][maxNumNodes];

        for (int numNodes = 2; numNodes <= maxNumNodes; numNodes++) {
            for (int maxParents = 1; maxParents < numNodes; maxParents++) {
                results[numNodes][maxParents] = run(new BenchmarkSetup(
                        numNodes, tick, Duration.ofSeconds(2), 3000, maxParents, uniformTopology(tick)));
            }
        }

        for (int numNodes = 2; numNodes <= maxNumNodes; numNodes++) {
            System.out.print(";" + numNodes);
        }
        System.out.println();

        for (int numNodes = 2; numNodes <= maxNumNodes; numNodes++) {
            System.out.print(numNodes + ";");
            for (int maxParents = 1; maxParents < numNodes; maxParents++) {
                System.out.print(ConsensusLatencyResult.seconds(results[numNodes][maxParents].meanC2C()) + ";");
            }
            System.out.println();
        }
    }

    /**
     * A small 4 node network with a uniform latency of 100µs, for quick experiments.
     */
    @FalconTest(randomSeed = SEED)
    @Disabled(DISABLED_REASON)
    void fastFourNodeNetwork() {
        final Duration tick = Duration.ofNanos(100_000L);
        System.out.println(run(new BenchmarkSetup(4, tick, Duration.ofMillis(100), 20, 16, uniformTopology(tick))));
    }

    /**
     * Runs a single benchmark in a fresh environment, which is destroyed afterward.
     *
     * <p>Besides the parameters of the setup, the network is configured so that its timing resembles the one of the
     * old harness:
     * <ul>
     *     <li>All nodes have the same weight, and sign their events with ED25519 keys.</li>
     *     <li>Event creation is attempted on every tick. With the default period of 10ms, the creation rate would be
     *     capped at 100 events per second, regardless of the configured maximum creation rate.</li>
     *     <li>The event deduplicator, the orphan buffer and the event creation manager of the full nodes run on
     *     {@code DIRECT} schedulers. The deterministic wiring model defers the work of all other schedulers to the
     *     next tick, which would add several ticks of artificial latency to every gossip hop. Mini-nodes pass events
     *     on without any scheduler.</li>
     * </ul>
     *
     * @param setup the parameters of the benchmark
     * @return the measured latency and throughput
     */
    @NonNull
    private static ConsensusLatencyResult run(@NonNull final BenchmarkSetup setup) {
        return run(setup, Duration.ZERO);
    }

    /**
     * Runs a single benchmark in a fresh environment, like {@link #run(BenchmarkSetup)}, but lets the network run for
     * a while after it has started before the measurement begins.
     *
     * @param setup the parameters of the benchmark
     * @param warmUp the simulated time the network runs before it is measured
     * @return the measured latency and throughput
     */
    @NonNull
    private static ConsensusLatencyResult run(@NonNull final BenchmarkSetup setup, @NonNull final Duration warmUp) {
        final FalconTestEnvironment env = new FalconTestEnvironment(SEED, setup.granularity());
        try {
            final FalconNetwork network = env.network();
            network.topology(setup.topology());
            network.weightGenerator(WeightGenerators.BALANCED);
            network.signingSchema(SigningSchema.ED25519);
            network.withConfigValue(EventCreationConfig_.MAX_CREATION_RATE, setup.maxCreationRate())
                    .withConfigValue(EventCreationConfig_.MAX_OTHER_PARENTS, setup.maxOtherParents())
                    .withConfigValue(EventCreationConfig_.PERIOD, setup.granularity());

            // nodes take the rows of a ping matrix in the order they are added
            if (setup.observer() > 0) {
                network.addMiniNodes(setup.observer());
            }
            for (final Node node : network.addNodes(FULL_NODES)) {
                node.configuration()
                        .withConfigValue(EventIntakeWiringConfig_.EVENT_DEDUPLICATOR, DIRECT)
                        .withConfigValue(EventIntakeWiringConfig_.ORPHAN_BUFFER, DIRECT)
                        .withConfigValue(EventCreationWiringConfig_.EVENT_CREATION_MANAGER, DIRECT);
            }
            final int remaining = setup.nodes() - FULL_NODES - setup.observer();
            if (remaining > 0) {
                network.addMiniNodes(remaining);
            }

            network.start();
            if (!warmUp.isZero()) {
                env.timeManager().waitFor(warmUp);
            }
            // The mini-nodes create events while the full node is still starting up, which the old harness had no
            // counterpart of, so the measurement only starts once the network is up
            env.restartConsensusLatencyMeasurement();
            env.timeManager().waitFor(setup.duration());
            return env.consensusLatency();
        } finally {
            env.destroy();
        }
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
}
