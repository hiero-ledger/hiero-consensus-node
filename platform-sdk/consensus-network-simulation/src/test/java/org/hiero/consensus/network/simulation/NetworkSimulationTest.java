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
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.network.simulation.fixtures.EventCreatorNetwork;
import org.hiero.consensus.network.simulation.fixtures.NetworkLatency;
import org.junit.jupiter.api.Test;

public class NetworkSimulationTest {

    @Test
    void mainnet() {
        final int numNodes = 32;
        final int[][] matrix = new int[][] {
            {
                0, 217, 158, 67, 183, 241, 328, 185, 152, 204, 180, 196, 165, 92, 162, 164, 223, 156, 159, 217, 170,
                163, 153, 173, 213, 60, 177, 216, 163, 184, 188, 165
            },
            {
                217, 0, 110, 234, 125, 208, 154, 126, 119, 16, 117, 31, 106, 185, 105, 107, 15, 110, 101, 36, 107, 96,
                119, 118, 40, 229, 123, 25, 107, 126, 118, 95
            },
            {
                158, 110, 0, 233, 35, 88, 205, 29, 7, 118, 17, 95, 11, 255, 12, 7, 109, 14, 2, 87, 8, 8, 8, 10, 90, 129,
                24, 90, 6, 29, 35, 7
            },
            {
                67, 234, 233, 0, 249, 267, 291, 249, 221, 136, 233, 151, 231, 101, 219, 224, 176, 226, 234, 180, 229,
                225, 223, 254, 160, 228, 238, 160, 223, 249, 226, 223
            },
            {
                183, 124, 35, 249, 0, 138, 219, 2, 30, 145, 34, 110, 42, 277, 38, 35, 125, 34, 35, 101, 38, 40, 29, 32,
                101, 165, 10, 103, 38, 3, 59, 39
            },
            {
                241, 208, 87, 271, 138, 0, 302, 117, 87, 259, 123, 167, 96, 248, 95, 97, 199, 87, 99, 161, 93, 95, 88,
                105, 180, 109, 111, 170, 95, 112, 117, 116
            },
            {
                328, 154, 205, 291, 219, 296, 0, 218, 211, 143, 203, 132, 200, 302, 193, 194, 147, 205, 191, 117, 217,
                181, 212, 200, 126, 312, 223, 126, 211, 217, 214, 187
            },
            {
                185, 126, 29, 249, 2, 116, 217, 0, 20, 146, 29, 104, 32, 281, 38, 37, 129, 25, 28, 118, 36, 33, 23, 34,
                96, 141, 8, 96, 36, 0, 64, 37
            },
            {
                152, 119, 7, 221, 30, 89, 211, 20, 0, 124, 9, 83, 12, 262, 11, 15, 111, 5, 8, 95, 9, 11, 0, 2, 92, 166,
                22, 94, 13, 20, 36, 15
            },
            {
                204, 16, 118, 136, 145, 260, 143, 146, 124, 0, 133, 34, 129, 180, 127, 113, 39, 132, 124, 33, 127, 113,
                122, 114, 34, 251, 138, 34, 110, 145, 135, 109
            },
            {
                180, 117, 17, 232, 34, 112, 203, 29, 9, 133, 0, 111, 22, 254, 23, 18, 116, 14, 20, 106, 19, 22, 10, 19,
                111, 132, 25, 104, 18, 29, 42, 23
            },
            {
                196, 31, 95, 151, 110, 170, 132, 104, 83, 34, 111, 0, 83, 251, 86, 73, 26, 88, 78, 12, 79, 73, 102, 93,
                11, 234, 99, 6, 72, 104, 112, 77
            },
            {
                165, 106, 11, 231, 42, 99, 200, 32, 12, 129, 22, 83, 0, 254, 7, 3, 103, 17, 9, 82, 5, 4, 13, 13, 86,
                134, 40, 82, 3, 32, 30, 9
            },
            {
                92, 185, 255, 101, 277, 238, 302, 281, 262, 180, 255, 250, 249, 0, 240, 256, 189, 246, 247, 205, 249,
                244, 244, 245, 209, 151, 266, 188, 260, 284, 289, 273
            },
            {
                162, 105, 12, 219, 38, 92, 193, 38, 11, 127, 23, 87, 7, 240, 0, 10, 102, 16, 16, 89, 3, 9, 11, 12, 89,
                140, 32, 83, 9, 38, 35, 12
            },
            {
                164, 107, 7, 226, 35, 99, 194, 37, 16, 113, 18, 73, 3, 256, 10, 0, 90, 19, 8, 76, 7, 1, 15, 14, 75, 180,
                28, 75, 0, 33, 34, 2
            },
            {
                223, 15, 109, 176, 125, 199, 138, 129, 111, 39, 116, 26, 103, 189, 102, 90, 0, 132, 109, 19, 103, 93,
                112, 115, 23, 266, 131, 23, 89, 128, 122, 93
            },
            {
                156, 110, 14, 226, 34, 87, 205, 25, 5, 132, 14, 88, 17, 246, 16, 19, 132, 0, 12, 109, 16, 19, 5, 22,
                110, 129, 27, 114, 19, 25, 41, 16
            },
            {
                159, 101, 2, 234, 35, 105, 191, 28, 8, 124, 20, 78, 9, 247, 16, 8, 109, 12, 0, 96, 11, 9, 8, 11, 93,
                179, 24, 99, 9, 28, 43, 8
            },
            {
                217, 36, 87, 180, 101, 158, 117, 118, 95, 33, 106, 12, 82, 205, 89, 76, 19, 109, 96, 0, 82, 76, 95, 88,
                1, 206, 104, 0, 74, 107, 113, 75
            },
            {
                170, 107, 8, 229, 38, 92, 215, 36, 9, 127, 19, 79, 5, 247, 3, 7, 102, 17, 11, 97, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0
            },
            {
                163, 96, 8, 225, 40, 91, 181, 33, 11, 113, 22, 72, 4, 244, 9, 1, 93, 19, 9, 76, 0, 0, 12, 13, 76, 137,
                33, 73, 1, 33, 30, 2
            },
            {
                154, 119, 8, 223, 29, 94, 212, 23, 0, 122, 10, 102, 13, 244, 11, 15, 112, 5, 8, 95, 0, 12, 0, 2, 95,
                120, 22, 95, 15, 25, 34, 14
            },
            {
                173, 118, 10, 254, 32, 102, 200, 34, 2, 114, 19, 93, 13, 245, 12, 14, 115, 22, 11, 88, 0, 13, 2, 0, 93,
                123, 31, 92, 13, 31, 31, 15
            },
            {
                213, 40, 90, 160, 101, 184, 126, 96, 92, 33, 111, 11, 86, 209, 89, 75, 23, 110, 93, 1, 0, 76, 95, 93, 0,
                206, 100, 0, 74, 96, 114, 76
            },
            {
                60, 229, 129, 228, 165, 109, 311, 140, 166, 251, 132, 234, 134, 151, 140, 180, 266, 129, 179, 206, 0,
                137, 120, 123, 206, 0, 144, 253, 179, 143, 141, 134
            },
            {
                177, 123, 24, 238, 10, 112, 223, 8, 22, 138, 25, 99, 40, 266, 32, 28, 131, 27, 24, 104, 0, 33, 22, 31,
                100, 144, 0, 104, 27, 8, 59, 31
            },
            {
                215, 25, 89, 160, 103, 164, 126, 96, 94, 34, 104, 6, 82, 188, 83, 75, 23, 114, 99, 0, 0, 73, 95, 92, 0,
                253, 104, 0, 74, 103, 109, 76
            },
            {
                163, 107, 6, 223, 38, 97, 211, 36, 13, 110, 18, 72, 3, 260, 9, 0, 89, 19, 9, 74, 0, 1, 15, 13, 74, 179,
                27, 74, 0, 36, 32, 2
            },
            {
                184, 126, 29, 249, 3, 113, 217, 0, 20, 145, 29, 104, 32, 284, 38, 33, 128, 25, 28, 107, 0, 33, 25, 31,
                96, 143, 8, 103, 36, 0, 64, 38
            },
            {
                188, 118, 35, 226, 59, 108, 214, 64, 36, 134, 42, 112, 30, 289, 35, 33, 134, 41, 43, 113, 0, 30, 34, 31,
                114, 141, 59, 109, 32, 64, 0, 33
            },
            {
                165, 95, 7, 223, 39, 116, 187, 37, 15, 109, 23, 77, 9, 272, 12, 2, 93, 16, 8, 75, 0, 2, 14, 15, 76, 134,
                31, 76, 2, 38, 33, 0
            }
        };

        final Duration tick = Duration.of(5, ChronoUnit.MILLIS);
        final Duration duration = Duration.ofSeconds(10);
        final Configuration configuration = new TestConfigBuilder()
                .withConfigDataType(EventCreationConfig.class)
                .withValue(EventCreationConfig_.MAX_CREATION_RATE, 0)
                .withValue(EventCreationConfig_.MAX_OTHER_PARENTS, 4)
                .getOrCreateConfig();
        final NetworkLatency latency = NetworkLatency.pingMatrix(matrix);
        runSimulation(tick, duration, numNodes, configuration, latency);
    }

    @Test
    void fastFourNodeNetwork() {
        final int numNodes = 4;
        final Duration tick = Duration.of(100, ChronoUnit.MICROS);
        final Duration duration = Duration.ofMillis(100);
        final Configuration configuration = new TestConfigBuilder()
                .withConfigDataType(EventCreationConfig.class)
                .withValue(EventCreationConfig_.MAX_CREATION_RATE, 0)
                .withValue(EventCreationConfig_.MAX_OTHER_PARENTS, 4)
                .getOrCreateConfig();
        final NetworkLatency latency = NetworkLatency.uniformLatency(tick, numNodes);
        runSimulation(tick, duration, numNodes, configuration, latency);
    }

    private void runSimulation(
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
                NodeId.of(creatorNetwork.getRoster().rosterEntries().getFirst().nodeId()),
                _ -> false,
                0L);

        final SimulationStats stats = new SimulationStats();
        final Instant start = creatorNetwork.getPlatformContext().getTime().now();
        final Instant end = start.plus(simulationDuration);
        while (creatorNetwork.getPlatformContext().getTime().now().isBefore(end)) {
            final List<PlatformEvent> events = creatorNetwork.tick(tick);
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
        stats.print(nodes, timePassed);
    }
}
