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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForActive;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForAny;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import org.hiero.block.api.protoc.PublishStreamResponse.EndOfStream.Code;
import org.hiero.consensus.model.status.PlatformStatus;
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
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        })
            })
    @Order(0)
    final Stream<DynamicTest> node0StreamingHappyPath() {
        return hapiTest(
                waitUntilNextBlocks(100).withBackgroundTraffic(true),
                assertHgcaaLogDoesNotContain(byNodeId(0), "ERROR", Duration.ofSeconds(5)));
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
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {1},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 2,
                        blockNodeIds = {2},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 3,
                        blockNodeIds = {3},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        }),
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
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        })
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
                        blockNodePriorities = {0, 1, 2, 3},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        })
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
                        String.format(
                                "[localhost:%s/UNINITIALIZED] Cancelled periodic stream reset", portNumbers.getFirst()),
                        // Select the next block node to connect to based on priorities
                        String.format("Selected block node localhost:%s for connection attempt", portNumbers.get(1)),
                        String.format("[localhost:%s/CONNECTING] Running connection task...", portNumbers.get(1)),
                        String.format(
                                "[localhost:%s/PENDING] Connection state transitioned from CONNECTING to PENDING",
                                portNumbers.get(1)),
                        String.format(
                                "[localhost:%s/ACTIVE] Connection state transitioned from PENDING to ACTIVE",
                                portNumbers.get(1)),
                        String.format(
                                "[localhost:%s/ACTIVE] Scheduled periodic stream reset every PT1M",
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
                        String.format(
                                "[localhost:%s/UNINITIALIZED] Cancelled periodic stream reset", portNumbers.get(1)),
                        // Select the next block node to connect to based on priorities
                        String.format("Selected block node localhost:%s for connection attempt", portNumbers.get(2)),
                        String.format("[localhost:%s/CONNECTING] Running connection task...", portNumbers.get(2)),
                        String.format(
                                "[localhost:%s/PENDING] Connection state transitioned from CONNECTING to PENDING",
                                portNumbers.get(2)),
                        String.format(
                                "[localhost:%s/ACTIVE] Connection state transitioned from PENDING to ACTIVE",
                                portNumbers.get(2)),
                        String.format(
                                "[localhost:%s/ACTIVE] Scheduled periodic stream reset every PT1M",
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
                        String.format(
                                "[localhost:%s/UNINITIALIZED] Cancelled periodic stream reset", portNumbers.get(2)),
                        // Select the next block node to connect to based on priorities
                        String.format("Selected block node localhost:%s for connection attempt", portNumbers.get(3)),
                        String.format("[localhost:%s/CONNECTING] Running connection task...", portNumbers.get(3)),
                        String.format(
                                "[localhost:%s/PENDING] Connection state transitioned from CONNECTING to PENDING",
                                portNumbers.get(3)),
                        String.format(
                                "[localhost:%s/ACTIVE] Connection state transitioned from PENDING to ACTIVE",
                                portNumbers.get(3)),
                        String.format(
                                "[localhost:%s/ACTIVE] Scheduled periodic stream reset every PT1M",
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
                        String.format(
                                "[localhost:%s/ACTIVE] Scheduled periodic stream reset every PT1M", portNumbers.get(1)),
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
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        })
            })
    @Order(4)
    final Stream<DynamicTest> twoNodesStreamingOneBlockNodeHappyPath() {
        return hapiTest(
                waitUntilNextBlocks(10).withBackgroundTraffic(true),
                assertHgcaaLogDoesNotContain(allNodes(), "ERROR", Duration.ofSeconds(5)));
    }

    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {
                @BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR),
                @BlockNodeConfig(nodeId = 1, mode = BlockNodeMode.SIMULATOR)
            },
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0, 1},
                        blockNodePriorities = {0, 1},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        })
            })
    @Order(5)
    final Stream<DynamicTest> testProactiveBlockBufferAction() {
        // NOTE: com.hedera.node.app.blocks.impl.streaming MUST have DEBUG logging enabled
        final AtomicReference<Instant> timeRef = new AtomicReference<>();
        return hapiTest(
                doingContextual(
                        spec -> LockSupport.parkNanos(Duration.ofSeconds(20).toNanos())),
                doingContextual(spec -> timeRef.set(Instant.now())),
                blockNodeSimulator(0).updateSendingBlockAcknowledgements(false),
                doingContextual(
                        spec -> LockSupport.parkNanos(Duration.ofSeconds(20).toNanos())),
                sourcingContextual(
                        spec -> assertHgcaaLogContainsTimeframe(
                                byNodeId(0),
                                timeRef::get,
                                Duration.ofMinutes(6),
                                Duration.ofMinutes(6),
                                // look for the saturation reaching the action stage (50%)
                                "saturation=50.0%",
                                // look for the log that shows we are forcing a reconnect to a different block node
                                "Attempting to forcefully switch block node connections due to increasing block buffer saturation")),
                doingContextual(spec -> timeRef.set(Instant.now())),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofMinutes(6),
                        Duration.ofMinutes(6),
                        // saturation should fall back to low levels after the reconnect to the different node
                        "saturation=0.0%")));
    }

    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        })
            })
    @Order(6)
    final Stream<DynamicTest> testBlockBufferBackPressure() {
        final AtomicReference<Instant> timeRef = new AtomicReference<>();

        return hapiTest(
                doingContextual(
                        spec -> LockSupport.parkNanos(Duration.ofSeconds(20).toNanos())),
                doingContextual(spec -> timeRef.set(Instant.now())),
                blockNodeSimulator(0).shutDownImmediately(),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofMinutes(6),
                        Duration.ofMinutes(6),
                        "Block buffer is saturated; backpressure is being enabled",
                        "!!! Block buffer is saturated; blocking thread until buffer is no longer saturated")),
                doingContextual(spec -> {
                    timeRef.set(Instant.now());
                    LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(20));
                }),
                waitForAny(byNodeId(0), Duration.ofSeconds(30), PlatformStatus.CHECKING),
                blockNodeSimulator(0).startImmediately(),
                sourcingContextual(
                        spec -> assertHgcaaLogContainsTimeframe(
                                byNodeId(0),
                                timeRef::get,
                                Duration.ofMinutes(6),
                                Duration.ofMinutes(6),
                                "Buffer saturation is below or equal to the recovery threshold; back pressure will be disabled")),
                waitForActive(byNodeId(0), Duration.ofSeconds(30)),
                doingContextual(
                        spec -> LockSupport.parkNanos(Duration.ofSeconds(20).toNanos())));
    }

    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {
                @BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR),
                @BlockNodeConfig(nodeId = 1, mode = BlockNodeMode.SIMULATOR)
            },
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0, 1},
                        blockNodePriorities = {0, 1},
                        applicationPropertiesOverrides = {
                            "blockNode.streamResetPeriod", "1m",
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        })
            })
    @Order(7)
    final Stream<DynamicTest> activeConnectionPeriodicallyRestarts() {
        final AtomicReference<Instant> connectionDropTime = new AtomicReference<>(Instant.now());
        final List<Integer> portNumbers = new ArrayList<>();
        return hapiTest(
                doingContextual(spec -> {
                    portNumbers.add(spec.getBlockNodePortById(0));
                    portNumbers.add(spec.getBlockNodePortById(1));
                }),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        connectionDropTime::get,
                        Duration.of(30, SECONDS),
                        Duration.of(15, SECONDS),
                        String.format(
                                "[localhost:%s/ACTIVE] Scheduled periodic stream reset every PT1M",
                                portNumbers.getFirst()))),
                burstOfTps(300, Duration.ofSeconds(60)),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        connectionDropTime::get,
                        Duration.of(90, SECONDS),
                        Duration.of(15, SECONDS),
                        // Verify that the periodic reset is performed after the period and the connection is closed
                        String.format(
                                "[localhost:%s/ACTIVE] Performing scheduled stream reset", portNumbers.getFirst()),
                        String.format("[localhost:%s/ACTIVE] Closing connection...", portNumbers.getFirst()),
                        String.format(
                                "[localhost:%s/UNINITIALIZED] Connection state transitioned from ACTIVE to UNINITIALIZED",
                                portNumbers.getFirst()),
                        String.format(
                                "[localhost:%s/UNINITIALIZED] Connection successfully closed", portNumbers.getFirst()),
                        // Select the next block node to connect to based on priorities
                        String.format("Selected block node localhost:%s for connection attempt", portNumbers.getLast()),
                        String.format("[localhost:%s/CONNECTING] Running connection task...", portNumbers.getLast()),
                        String.format(
                                "[localhost:%s/PENDING] Connection state transitioned from CONNECTING to PENDING",
                                portNumbers.getLast()),
                        String.format(
                                "[localhost:%s/ACTIVE] Connection state transitioned from PENDING to ACTIVE",
                                portNumbers.getLast()))),
                assertHgcaaLogDoesNotContain(byNodeId(0), "ERROR", Duration.ofSeconds(5)));
    }
}
