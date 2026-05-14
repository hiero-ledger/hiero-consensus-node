// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.network.simulation;

import com.swirlds.config.api.Configuration;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    void fastFourNodeNetwork(){
        final int numNodes = 4;
        final Duration tick = Duration.of(100, ChronoUnit.MICROS);
        final Configuration configuration = new TestConfigBuilder()
                .withConfigDataType(EventCreationConfig.class)
                .withValue(EventCreationConfig_.MAX_CREATION_RATE, 0)
                .withValue("event.creation.maxOtherParents", Integer.toString(4))
                .getOrCreateConfig();
        final NetworkLatency latency = NetworkLatency.uniformLatency(tick, numNodes);
        runSimulation(tick, numNodes, configuration, latency);
    }

    private void runSimulation(final Duration tick, final int nodes, final Configuration configuration, final NetworkLatency latency) {
        final EventCreatorNetwork creatorNetwork = new EventCreatorNetwork(0, nodes, configuration, latency);
        final DefaultConsensusEngine consensusEngine = new DefaultConsensusEngine(
                creatorNetwork.getPlatformContext().getConfiguration(),
                creatorNetwork.getPlatformContext().getMetrics(),
                creatorNetwork.getPlatformContext().getTime(),
                creatorNetwork.getRoster(),
                NodeId.of(creatorNetwork.getRoster().rosterEntries().getFirst().nodeId()),
                _ -> false,
                0L
        );

        final SimulationStats stats = new SimulationStats();
        final Instant start = creatorNetwork.getPlatformContext().getTime().now();
        final Instant end = start.plus(SIMULATION_DURATION);
        while (creatorNetwork.getPlatformContext().getTime().now().isBefore(end)) {
            final List<PlatformEvent> events = creatorNetwork.tick(tick);
            final List<ConsensusEngineOutput> engineOutputs = events.stream().map(consensusEngine::addEvent).toList();
            engineOutputs.stream().map(ConsensusEngineOutput::consensusRounds).flatMap(List::stream)
                    .map(ConsensusRound::getEventWindow).forEach(creatorNetwork::setEventWindow);
            stats.record(engineOutputs);
        }
        final Duration timePassed = Duration.between(start, creatorNetwork.getPlatformContext().getTime().now());
        stats.print(tick, nodes, timePassed);
    }
}
