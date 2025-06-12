// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blocknode;

import static com.hedera.services.bdd.junit.TestTags.BLOCK_NODE_SIMULATOR;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.allNodes;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.BlockNodeSimulatorVerbs.blockNodeSimulator;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsTimeframe;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogDoesNotContain;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilNextBlocks;
import static com.hedera.services.bdd.suites.regression.system.MixedOperations.burstOfTps;
import static java.time.temporal.ChronoUnit.SECONDS;

import com.hedera.services.bdd.HapiBlockNode;
import com.hedera.services.bdd.HapiBlockNode.BlockNodeConfig;
import com.hedera.services.bdd.HapiBlockNode.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.hiero.block.api.protoc.PublishStreamResponse.EndOfStream.Code;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

/**
 * This suite is for testing with the block node simulator.
 */
@Tag(BLOCK_NODE_SIMULATOR)
@OrderedInIsolation
public class BlockNodeSimulatorSuite {

    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0})
            })
    @Order(0)
    final Stream<DynamicTest> node0StreamingHappyPath() {
        return hapiTest(
                burstOfTps(300, Duration.ofSeconds(600)),
                assertHgcaaLogDoesNotContain(byNodeId(0), "ERROR", Duration.ofSeconds(5)));
    }

    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0})
            })
    @Order(0)
    final Stream<DynamicTest> node0StreamingBufferFull() {
        return hapiTest(
                waitUntilNextBlocks(10).withBackgroundTraffic(true),
                waitUntilNextBlocks(10).withBackgroundTraffic(true));
    }

    @HapiTest
    @HapiBlockNode(
            blockNodeConfigs = {
                @BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR),
                @BlockNodeConfig(nodeId = 1, mode = BlockNodeMode.SIMULATOR),
                @BlockNodeConfig(nodeId = 2, mode = BlockNodeMode.SIMULATOR),
                @BlockNodeConfig(nodeId = 3, mode = BlockNodeMode.SIMULATOR),
            },
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0}),
                @SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {1},
                        blockNodePriorities = {0}),
                @SubProcessNodeConfig(
                        nodeId = 2,
                        blockNodeIds = {2},
                        blockNodePriorities = {0}),
                @SubProcessNodeConfig(
                        nodeId = 3,
                        blockNodeIds = {3},
                        blockNodePriorities = {0}),
            })
    @Order(1)
    final Stream<DynamicTest> allNodesStreamingHappyPath() {
        return hapiTest(
                waitUntilNextBlocks(10).withBackgroundTraffic(true),
                assertHgcaaLogDoesNotContain(allNodes(), "ERROR", Duration.ofSeconds(5)));
    }

    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0})
            })
    @Order(2)
    final Stream<DynamicTest> node0StreamingBlockNodeConnectionDropsCanStreamGenesisBlock() {
        final AtomicReference<Instant> time = new AtomicReference<>();
        final List<Integer> portNumbers = new ArrayList<>();
        return hapiTest(
                doingContextual(spec -> portNumbers.add(spec.getBlockNodePortById(0))),
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                doingContextual(spec -> time.set(Instant.now())),
                blockNodeSimulator(0).sendEndOfStreamImmediately(Code.BEHIND).withBlockNumber(Long.MAX_VALUE),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        time::get,
                        Duration.of(10, SECONDS),
                        Duration.of(45, SECONDS),
                        String.format(
                                "[localhost:%s/UNINITIALIZED] Block node reported it is behind. Will restart stream at block 0.",
                                portNumbers.getFirst()),
                        String.format(
                                "[localhost:%s/ACTIVE] Received EndOfStream response (block=9223372036854775807, responseCode=BEHIND)",
                                portNumbers.getFirst()))),
                waitUntilNextBlocks(5).withBackgroundTraffic(true));
    }

    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {
                @BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR),
                @BlockNodeConfig(nodeId = 1, mode = BlockNodeMode.SIMULATOR),
                @BlockNodeConfig(nodeId = 2, mode = BlockNodeMode.SIMULATOR),
                @BlockNodeConfig(nodeId = 3, mode = BlockNodeMode.SIMULATOR)
            },
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0, 1, 2, 3},
                        blockNodePriorities = {0, 1, 2, 3})
            })
    @Order(3)
    final Stream<DynamicTest> node0StreamingBlockNodeConnectionDropsTrickle() {
        final AtomicReference<Instant> connectionDropTime = new AtomicReference<>();
        final List<Integer> portNumbers = new ArrayList<>();
        return hapiTest(
                doingContextual(spec -> {
                    portNumbers.add(spec.getBlockNodePortById(0));
                    portNumbers.add(spec.getBlockNodePortById(1));
                    portNumbers.add(spec.getBlockNodePortById(2));
                    portNumbers.add(spec.getBlockNodePortById(3));
                }),
                waitUntilNextBlocks(10).withBackgroundTraffic(true),
                doingContextual(spec -> connectionDropTime.set(Instant.now())),
                blockNodeSimulator(0).shutDownImmediately(), // Pri 0
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        connectionDropTime::get,
                        Duration.of(10, SECONDS),
                        Duration.of(45, SECONDS),
                        String.format("[localhost:%s/ACTIVE] Stream encountered an error", portNumbers.getFirst()),
                        String.format("Selected block node localhost:%s for connection attempt", portNumbers.get(1)),
                        String.format("[localhost:%s/CONNECTING] Running connection task...", portNumbers.get(1)),
                        String.format(
                                "[localhost:%s/PENDING] Connection state transitioned from CONNECTING to PENDING",
                                portNumbers.get(1)),
                        String.format(
                                "[localhost:%s/ACTIVE] Connection state transitioned from PENDING to ACTIVE",
                                portNumbers.get(1)))),
                waitUntilNextBlocks(10).withBackgroundTraffic(true),
                doingContextual(spec -> connectionDropTime.set(Instant.now())),
                blockNodeSimulator(1).shutDownImmediately(), // Pri 1
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        connectionDropTime::get,
                        Duration.of(10, SECONDS),
                        Duration.of(45, SECONDS),
                        String.format("[localhost:%s/ACTIVE] Stream encountered an error", portNumbers.get(1)),
                        String.format("Selected block node localhost:%s for connection attempt", portNumbers.get(2)),
                        String.format("[localhost:%s/CONNECTING] Running connection task...", portNumbers.get(2)),
                        String.format(
                                "[localhost:%s/PENDING] Connection state transitioned from CONNECTING to PENDING",
                                portNumbers.get(2)),
                        String.format(
                                "[localhost:%s/ACTIVE] Connection state transitioned from PENDING to ACTIVE",
                                portNumbers.get(2)))),
                waitUntilNextBlocks(10).withBackgroundTraffic(true),
                doingContextual(spec -> connectionDropTime.set(Instant.now())),
                blockNodeSimulator(2).shutDownImmediately(), // Pri 2
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        connectionDropTime::get,
                        Duration.of(10, SECONDS),
                        Duration.of(45, SECONDS),
                        String.format("[localhost:%s/ACTIVE] Stream encountered an error", portNumbers.get(2)),
                        String.format("Selected block node localhost:%s for connection attempt", portNumbers.get(3)),
                        String.format("[localhost:%s/CONNECTING] Running connection task...", portNumbers.get(3)),
                        String.format(
                                "[localhost:%s/PENDING] Connection state transitioned from CONNECTING to PENDING",
                                portNumbers.get(3)),
                        String.format(
                                "[localhost:%s/ACTIVE] Connection state transitioned from PENDING to ACTIVE",
                                portNumbers.get(3)))),
                waitUntilNextBlocks(10).withBackgroundTraffic(true),
                doingContextual(spec -> connectionDropTime.set(Instant.now())),
                blockNodeSimulator(1).startImmediately(),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        connectionDropTime::get,
                        Duration.of(15, SECONDS),
                        Duration.of(45, SECONDS),
                        String.format("[localhost:%s/CONNECTING] Running connection task...", portNumbers.get(1)),
                        String.format(
                                "[localhost:%s/PENDING] Connection state transitioned from CONNECTING to PENDING",
                                portNumbers.get(1)),
                        String.format(
                                "[localhost:%s/ACTIVE] Connection state transitioned from PENDING to ACTIVE",
                                portNumbers.get(1)),
                        String.format("[localhost:%s/ACTIVE] Closing connection...", portNumbers.get(3)),
                        String.format(
                                "[localhost:%s/UNINITIALIZED] Connection state transitioned from ACTIVE to UNINITIALIZED",
                                portNumbers.get(3)),
                        String.format(
                                "[localhost:%s/UNINITIALIZED] Connection successfully closed", portNumbers.get(3)),
                        String.format(
                                "The existing active connection (localhost:%s/ACTIVE) has an equal or higher priority"
                                        + " than the connection (localhost:%s/CONNECTING) we are attempting to connect to"
                                        + " and this new connection attempt will be ignored",
                                portNumbers.get(1), portNumbers.get(2)))),
                waitUntilNextBlocks(10).withBackgroundTraffic(true));
    }

    @HapiTest
    @HapiBlockNode(
            networkSize = 2,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0}),
                @SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {0},
                        blockNodePriorities = {0})
            })
    @Order(4)
    final Stream<DynamicTest> twoNodesStreamingOneBlockNodeHappyPath() {
        return hapiTest(
                waitUntilNextBlocks(10).withBackgroundTraffic(true),
                assertHgcaaLogDoesNotContain(allNodes(), "ERROR", Duration.ofSeconds(5)));
    }
}
