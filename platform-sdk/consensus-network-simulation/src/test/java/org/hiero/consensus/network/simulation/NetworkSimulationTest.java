// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.network.simulation;

import com.swirlds.config.api.Configuration;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.hiero.consensus.hashgraph.impl.ConsensusEngineOutput;
import org.hiero.consensus.hashgraph.impl.DefaultConsensusEngine;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.network.simulation.fixtures.EventCreatorNetwork;
import org.hiero.consensus.network.simulation.fixtures.NetworkLatency;
import org.junit.jupiter.api.Test;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import org.hiero.consensus.event.creator.config.EventCreationConfig;
import org.hiero.consensus.event.creator.config.EventCreationConfig_;

public class NetworkSimulationTest {
    private static final Duration SIMULATION_DURATION = Duration.ofMillis(100);

    @Test
    void sim(){
        final Configuration configuration = new TestConfigBuilder()
                .withConfigDataType(EventCreationConfig.class)
                .withValue(EventCreationConfig_.MAX_CREATION_RATE, 0)
                .withValue("event.creation.maxOtherParents", Integer.toString(4))
                .getOrCreateConfig();
        runSimulation(Duration.of(100, ChronoUnit.MICROS), 4, configuration);
    }

    private void runSimulation(final Duration tick, final int nodes, final Configuration configuration) {
        final EventCreatorNetwork creatorNetwork = new EventCreatorNetwork(0, nodes, configuration,
                NetworkLatency.uniformLatency(Duration.of(100, ChronoUnit.MICROS), nodes));
        final DefaultConsensusEngine consensusEngine = new DefaultConsensusEngine(
                creatorNetwork.getPlatformContext().getConfiguration(),
                creatorNetwork.getPlatformContext().getMetrics(),
                creatorNetwork.getPlatformContext().getTime(),
                creatorNetwork.getRoster(),
                NodeId.of(creatorNetwork.getRoster().rosterEntries().getFirst().nodeId()),
                t -> false
        );

        final List<Duration> c2cs = new ArrayList<>();
        final Instant start = creatorNetwork.getPlatformContext().getTime().now();
        final Instant end = start.plus(SIMULATION_DURATION);
        int numEvents = 0;
        while (creatorNetwork.getPlatformContext().getTime().now().isBefore(end)) {
            final List<PlatformEvent> events = creatorNetwork.tick(tick);
            numEvents += events.size();
            final List<ConsensusEngineOutput> engineOutputs = events.stream().map(consensusEngine::addEvent).toList();
            engineOutputs.stream().map(ConsensusEngineOutput::consensusRounds).flatMap(List::stream)
                    .map(ConsensusRound::getEventWindow).forEach(creatorNetwork::setEventWindow);

            engineOutputs.stream()
                    .map(ConsensusEngineOutput::consensusRounds)
                    .flatMap(List::stream)
                    .map(cr->cr.getConsensusEvents().stream()
                            .map(ce->Duration.between(ce.getTimeCreated(), cr.getReachedConsTimestamp()))
                            .toList())
                    .flatMap(List::stream)
                    .forEach(c2cs::add);
        }
        final double averageC2C = c2cs.stream().mapToLong(Duration::toNanos).average().orElse(0);
        final Duration max = c2cs.stream().max(Comparator.naturalOrder()).orElse(Duration.ZERO);
        final Duration timePassed = Duration.between(start, creatorNetwork.getPlatformContext().getTime().now());
        System.out.println("Delay(μs)  Nodes avgC2C(μs) maxC2C(μs)  ev/sec");
        System.out.printf("%,9d %6d %,10d %,10d %,7d %n",
                tick.toNanos()/1000,
                nodes,
                (long)averageC2C/1000,
                toMicros(max),
                (long)(numEvents/((double)timePassed.toMillis()/1000)));
    }

    public static long toMicros(final Duration d) {
        // seconds * 1_000_000 + nanos/1_000 handles negative durations correctly
        return d.getSeconds() * 1_000_000L + d.getNano() / 1_000L;
    }

    @Test
    void stringTest(){
        System.out.println("Delay(μs)  Nodes avgC2C(μs) maxC2C(μs)  ev/sec");
        System.out.printf("%,9d %6d %,10d %,10d %,7d %n",
                80,
                4,
                1123,
                2500,
                100_000);
    }
}
