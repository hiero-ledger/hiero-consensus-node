// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.network.simulation;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.hiero.consensus.event.creator.config.EventCreationConfig;
import org.hiero.consensus.event.creator.config.EventCreationConfig_;
import org.hiero.consensus.hashgraph.impl.ConsensusEngineOutput;
import org.hiero.consensus.hashgraph.impl.DefaultConsensusEngine;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.network.simulation.fixtures.EventCreatorNetwork;
import org.hiero.consensus.network.simulation.fixtures.NetworkLatency;
import org.hiero.consensus.test.fixtures.io.RealisticPingSamples;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class NetworkSimulationTest {

    @Test
    @Disabled("This test has no assertions, its only goal to speed up certain testing")
    void mainnet() {
        final int numNodes = 32;

        final Duration tick = Duration.of(5, ChronoUnit.MILLIS);
        final Duration duration = Duration.ofSeconds(10);
        final Configuration configuration = new TestConfigBuilder()
                .withConfigDataType(EventCreationConfig.class)
                .withValue(EventCreationConfig_.MAX_CREATION_RATE, 20)
                .withValue(EventCreationConfig_.MAX_OTHER_PARENTS, 4)
                .getOrCreateConfig();
        final NetworkLatency latency = NetworkLatency.pingMatrix(RealisticPingSamples.MAINNET);
        runSimulation(tick, duration, numNodes, configuration, latency);
    }

    @Test
    @Disabled("This test has no assertions, its only goal to speed up certain testing")
    void mainnetMopComparison() {
        final int numNodes = 32;

        final Duration tick = Duration.of(1, ChronoUnit.MILLIS);
        final Duration duration = Duration.ofSeconds(10);

        final SimulationResult[] results = new SimulationResult[numNodes];

        for (int maxParents = 1; maxParents < numNodes; maxParents++) {
            final Configuration configuration = new TestConfigBuilder()
                    .withConfigDataType(EventCreationConfig.class)
                    .withValue(EventCreationConfig_.MAX_CREATION_RATE, 20)
                    .withValue(EventCreationConfig_.MAX_OTHER_PARENTS, maxParents)
                    .getOrCreateConfig();
            final NetworkLatency latency = NetworkLatency.pingMatrix(RealisticPingSamples.MAINNET);
            results[maxParents] = runSimulation(tick, duration, numNodes, configuration, latency);
        }

        System.out.println("MaxParents;avgC2C;maxC2C;events/s;bytes/s");
        for (int maxParents = 1; maxParents < numNodes; maxParents++) {
            final SimulationResult res = results[maxParents];
            System.out.printf(
                    "%d;%s;%s;%d;%d%n",
                    maxParents,
                    res.averageC2C().toMillis() / 1000.0,
                    res.maxC2C().toMillis() / 1000.0,
                    res.eventsPerSec(),
                    res.bytesPerSec());
        }
    }

    @Test
    @Disabled("This test has no assertions, its only goal to speed up certain testing")
    void latitudeMopComparison() {
        final int numNodes = 7;

        final Duration tick = Duration.of(300, ChronoUnit.MICROS);
        final Duration duration = Duration.ofSeconds(10);

        final SimulationResult[] results = new SimulationResult[numNodes];

        for (int maxParents = 1; maxParents < numNodes; maxParents++) {
            final Configuration configuration = new TestConfigBuilder()
                    .withConfigDataType(EventCreationConfig.class)
                    .withValue(EventCreationConfig_.MAX_CREATION_RATE, 20)
                    .withValue(EventCreationConfig_.MAX_OTHER_PARENTS, maxParents)
                    .getOrCreateConfig();
            final NetworkLatency latency = NetworkLatency.uniformLatency(tick, numNodes);
            results[maxParents] = runSimulation(tick, duration, numNodes, configuration, latency);
        }

        System.out.println("MaxParents;avgC2C;maxC2C;events/s;bytes/s");
        for (int maxParents = 1; maxParents < numNodes; maxParents++) {
            final SimulationResult res = results[maxParents];
            System.out.printf(
                    "%d;%s;%s;%d;%d%n",
                    maxParents,
                    res.averageC2C().toMillis() / 1000.0,
                    res.maxC2C().toMillis() / 1000.0,
                    res.eventsPerSec(),
                    res.bytesPerSec());
        }
    }

    @Test
    @Disabled("This test has no assertions, its only goal to speed up certain testing")
    void ententeSizeComparison() {
        final int maxParents = 4;
        final int maxNumNodes = 20;

        final Duration tick = Duration.of(300, ChronoUnit.MICROS);
        final Duration duration = Duration.ofSeconds(10);

        final SimulationResult[] results = new SimulationResult[maxNumNodes + 1];

        for (int numNodes = 2; numNodes <= maxNumNodes; numNodes++) {

            final Configuration configuration = new TestConfigBuilder()
                    .withConfigDataType(EventCreationConfig.class)
                    .withValue(EventCreationConfig_.MAX_CREATION_RATE, 3000)
                    .withValue(EventCreationConfig_.MAX_OTHER_PARENTS, maxParents)
                    .getOrCreateConfig();
            final NetworkLatency latency = NetworkLatency.uniformLatency(tick, numNodes);
            results[numNodes] = runSimulation(tick, duration, numNodes, configuration, latency);
        }

        System.out.println("MaxParents;avgC2C;maxC2C;events/s;bytes/s");
        for (int numNodes = 2; numNodes <= maxNumNodes; numNodes++) {
            final SimulationResult res = results[numNodes];
            System.out.printf(
                    "%d;%s;%s;%d;%d%n",
                    numNodes,
                    res.averageC2C().toNanos() / 1000000000.0,
                    res.maxC2C().toNanos() / 1000000000.0,
                    res.eventsPerSec(),
                    res.bytesPerSec());
        }
    }

    @Test
    @Disabled("This test has no assertions, its only goal to speed up certain testing")
    void ententeSizeParentMatrix() {
        final int maxNumNodes = 20;

        final Duration tick = Duration.of(300, ChronoUnit.MICROS);
        final Duration duration = Duration.ofSeconds(2);

        final SimulationResult[][] results = new SimulationResult[maxNumNodes + 1][maxNumNodes];

        for (int numNodes = 2; numNodes <= maxNumNodes; numNodes++) {
            for (int maxParents = 1; maxParents < numNodes; maxParents++) {

                final Configuration configuration = new TestConfigBuilder()
                        .withConfigDataType(EventCreationConfig.class)
                        .withValue(EventCreationConfig_.MAX_CREATION_RATE, 3000)
                        .withValue(EventCreationConfig_.MAX_OTHER_PARENTS, maxParents)
                        .getOrCreateConfig();
                final NetworkLatency latency = NetworkLatency.uniformLatency(tick, numNodes);
                results[numNodes][maxParents] = runSimulation(tick, duration, numNodes, configuration, latency);
            }
        }

        for (int numNodes = 2; numNodes <= maxNumNodes; numNodes++) {
            System.out.print(";" + numNodes);
        }
        System.out.println();

        for (int numNodes = 2; numNodes <= maxNumNodes; numNodes++) {
            System.out.print(numNodes + ";");
            for (int maxParents = 1; maxParents < numNodes; maxParents++) {
                System.out.print(results[numNodes][maxParents].averageC2C().toNanos() / 1000000000.0 + ";");
            }
            System.out.println();
        }
    }

    @Test
    @Disabled("This test has no assertions, its only goal to speed up certain testing")
    void fastFourNodeNetwork() {
        final int numNodes = 4;
        final Duration tick = Duration.of(100, ChronoUnit.MICROS);
        final Duration duration = Duration.ofMillis(100);
        final Configuration configuration = new TestConfigBuilder()
                .withConfigDataType(EventCreationConfig.class)
                .withValue(EventCreationConfig_.MAX_CREATION_RATE, 20)
                .withValue(EventCreationConfig_.MAX_OTHER_PARENTS, 16)
                .getOrCreateConfig();
        final NetworkLatency latency = NetworkLatency.uniformLatency(tick, numNodes);
        runSimulation(tick, duration, numNodes, configuration, latency);
    }

    /**
     * Runs a network simulation and prints statistics to standard output.
     *
     * @param tick               the simulated time step used for each iteration of the main loop
     * @param simulationDuration the total simulated wall-clock duration of the run
     * @param nodes              the number of nodes in the network
     * @param configuration      platform configuration applied to all event creators
     * @param latency            the latency model applied to the broadcast simulation
     */
    private SimulationResult runSimulation(
            final Duration tick,
            final Duration simulationDuration,
            final int nodes,
            final Configuration configuration,
            final NetworkLatency latency) {
        final EventCreatorNetwork creatorNetwork = new EventCreatorNetwork(0, nodes, configuration, latency);
        final DefaultConsensusEngine consensusEngine = new DefaultConsensusEngine(
                creatorNetwork.getPlatformContext().getConfiguration(),
                creatorNetwork.getPlatformContext().getMetrics(),
                creatorNetwork.getPlatformContext().getTime(),
                creatorNetwork.getRoster(),
                creatorNetwork.getRoster().rosterEntry(0).nodeId(),
                _ -> false,
                0L);

        final SimulationStats stats = new SimulationStats();
        final Instant start = creatorNetwork.getPlatformContext().getTime().now();
        final Instant end = start.plus(simulationDuration);
        while (creatorNetwork.getPlatformContext().getTime().now().isBefore(end)) {
            final List<PlatformEvent> events = creatorNetwork.tick(tick);
            stats.records(events);
            final List<ConsensusEngineOutput> engineOutputs =
                    events.stream().map(consensusEngine::addEvent).toList();
            engineOutputs.stream()
                    .map(ConsensusEngineOutput::consensusRounds)
                    .flatMap(List::stream)
                    .map(ConsensusRound::getEventWindow)
                    .forEach(creatorNetwork::setEventWindow);
            stats.record(engineOutputs);
        }
        final Duration timePassed = Duration.between(
                start, creatorNetwork.getPlatformContext().getTime().now());
        return stats.print(nodes, timePassed);
    }
}
