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
import static com.hedera.services.bdd.suites.regression.system.LifecycleTest.restartAtNextConfigVersion;
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
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import org.hiero.block.api.PublishStreamResponse.EndOfStream.Code;
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
                doingContextual(
                        spec -> LockSupport.parkNanos(Duration.ofSeconds(10).toNanos())),
                doingContextual(spec -> time.set(Instant.now())),
                blockNodeSimulator(0).sendEndOfStreamImmediately(Code.BEHIND).withBlockNumber(Long.MAX_VALUE),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        time::get,
                        Duration.of(30, SECONDS),
                        Duration.of(45, SECONDS),
                        String.format(
                                "[localhost:%s/ACTIVE] Block node reported it is behind. Will restart stream at block 0.",
                                portNumbers.getFirst()),
                        String.format(
                                "[localhost:%s/ACTIVE] Received EndOfStream response (block=9223372036854775807, responseCode=BEHIND)",
                                portNumbers.getFirst()))),
                doingContextual(
                        spec -> LockSupport.parkNanos(Duration.ofSeconds(10).toNanos())));
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
                        Duration.ofMinutes(1),
                        Duration.of(45, SECONDS),
                        "onError invoked",
                        String.format("Selected block node localhost:%s for connection attempt", portNumbers.get(1)),
                        String.format(
                                "[localhost:%s/PENDING] Connection state transitioned from UNINITIALIZED to PENDING",
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
                        Duration.ofMinutes(1),
                        Duration.of(45, SECONDS),
                        String.format(
                                "[localhost:%s/PENDING] Connection state transitioned from UNINITIALIZED to PENDING",
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
                        Duration.ofMinutes(1),
                        Duration.of(45, SECONDS),
                        String.format(
                                "[localhost:%s/PENDING] Connection state transitioned from UNINITIALIZED to PENDING",
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
                        Duration.ofMinutes(1),
                        Duration.of(45, SECONDS),
                        String.format(
                                "[localhost:%s/PENDING] Connection state transitioned from UNINITIALIZED to PENDING",
                                portNumbers.get(1)),
                        String.format(
                                "[localhost:%s/ACTIVE] Connection state transitioned from PENDING to ACTIVE",
                                portNumbers.get(1)),
                        String.format("[localhost:%s/ACTIVE] Closing connection...", portNumbers.get(3)),
                        String.format(
                                "[localhost:%s/CLOSED] Connection state transitioned from ACTIVE to CLOSED",
                                portNumbers.get(3)))),
                doingContextual(
                        spec -> LockSupport.parkNanos(Duration.ofSeconds(20).toNanos())));
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
                            "blockStream.buffer.blockTtl", "1m",
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
                        spec -> LockSupport.parkNanos(Duration.ofSeconds(5).toNanos())),
                doingContextual(spec -> timeRef.set(Instant.now())),
                blockNodeSimulator(0).updateSendingBlockAcknowledgements(false),
                doingContextual(
                        spec -> LockSupport.parkNanos(Duration.ofSeconds(5).toNanos())),
                sourcingContextual(
                        spec -> assertHgcaaLogContainsTimeframe(
                                byNodeId(0),
                                timeRef::get,
                                Duration.ofMinutes(1),
                                Duration.ofMinutes(1),
                                // look for the saturation reaching the action stage (50%)
                                "saturation=50.0%",
                                // look for the log that shows we are forcing a reconnect to a different block node
                                "Attempting to forcefully switch block node connections due to increasing block buffer saturation")),
                doingContextual(spec -> timeRef.set(Instant.now())),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(1),
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
                            "blockStream.buffer.blockTtl", "30s",
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        })
            })
    @Order(6)
    final Stream<DynamicTest> testBlockBufferBackPressure() {
        final AtomicReference<Instant> timeRef = new AtomicReference<>();

        return hapiTest(
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                doingContextual(spec -> timeRef.set(Instant.now())),
                blockNodeSimulator(0).shutDownImmediately(),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofMinutes(6),
                        Duration.ofMinutes(6),
                        "Block buffer is saturated; backpressure is being enabled",
                        "!!! Block buffer is saturated; blocking thread until buffer is no longer saturated")),
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
                            "blockNode.streamResetPeriod", "10s",
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        })
            })
    @Order(7)
    final Stream<DynamicTest> activeConnectionPeriodicallyRestarts() {
        final AtomicReference<Instant> connectionResetTime = new AtomicReference<>();
        final List<Integer> portNumbers = new ArrayList<>();
        return hapiTest(
                doingContextual(spec -> {
                    portNumbers.add(spec.getBlockNodePortById(0));
                    portNumbers.add(spec.getBlockNodePortById(1));
                    connectionResetTime.set(Instant.now());
                }),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        connectionResetTime::get,
                        Duration.of(30, SECONDS),
                        Duration.of(15, SECONDS),
                        String.format(
                                "[localhost:%s/ACTIVE] Scheduled periodic stream reset every PT10S",
                                portNumbers.getFirst()))),
                waitUntilNextBlocks(6).withBackgroundTraffic(true),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        connectionResetTime::get,
                        Duration.of(60, SECONDS),
                        Duration.of(15, SECONDS),
                        // Verify that the periodic reset is performed after the period and the connection is closed
                        String.format(
                                "[localhost:%s/ACTIVE] Performing scheduled stream reset", portNumbers.getFirst()),
                        String.format("[localhost:%s/ACTIVE] Closing connection...", portNumbers.getFirst()),
                        String.format(
                                "[localhost:%s/CLOSED] Connection state transitioned from ACTIVE to CLOSED",
                                portNumbers.getFirst()),
                        String.format("[localhost:%s/CLOSED] Connection successfully closed", portNumbers.getFirst()),
                        // Select the next block node to connect to based on priorities
                        "Selected block node",
                        "Running connection task...",
                        "Connection state transitioned from UNINITIALIZED to PENDING",
                        "Connection state transitioned from PENDING to ACTIVE")),
                assertHgcaaLogDoesNotContain(byNodeId(0), "ERROR", Duration.ofSeconds(5)));
    }

    private static final int BLOCK_TTL_MINUTES = 2;
    private static final int BLOCK_PERIOD_SECONDS = 2;

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
                            "blockStream.streamMode",
                            "BLOCKS",
                            "blockStream.writerMode",
                            "FILE_AND_GRPC",
                            "blockStream.buffer.blockTtl",
                            BLOCK_TTL_MINUTES + "m",
                            "blockStream.blockPeriod",
                            BLOCK_PERIOD_SECONDS + "s"
                        })
            })
    @Order(7)
    final Stream<DynamicTest> testBlockBufferDurability() {
        /*
        1. Create some background traffic for a while.
        2. Shutdown the block node.
        3. Wait until block buffer becomes partially saturated.
        4. Restart consensus node (this should both save the buffer to disk on shutdown and load it back on startup)
        5. Check that the consensus node is still in a state with the block buffer saturated
        6. Start the block node.
        7. Wait for the blocks to be acked and the consensus node recovers
         */
        final AtomicReference<Instant> timeRef = new AtomicReference<>();
        final Duration blockTtl = Duration.ofMinutes(BLOCK_TTL_MINUTES);
        final Duration blockPeriod = Duration.ofSeconds(BLOCK_PERIOD_SECONDS);
        final int maxBufferSize = (int) blockTtl.dividedBy(blockPeriod);
        final int halfBufferSize = Math.max(1, maxBufferSize / 2);

        return hapiTest(
                // create some blocks to establish a baseline
                waitUntilNextBlocks(halfBufferSize).withBackgroundTraffic(true),
                doingContextual(spec -> timeRef.set(Instant.now())),
                // shutdown the block node. this will cause the block buffer to become saturated
                blockNodeSimulator(0).shutDownImmediately(),
                waitUntilNextBlocks(halfBufferSize).withBackgroundTraffic(true),
                // wait until the buffer is starting to get saturated
                sourcingContextual(
                        spec -> assertHgcaaLogContainsTimeframe(
                                byNodeId(0),
                                timeRef::get,
                                blockTtl,
                                blockTtl,
                                "Attempting to forcefully switch block node connections due to increasing block buffer saturation")),
                doingContextual(spec -> timeRef.set(Instant.now())),
                // restart the consensus node
                // this should persist the buffer to disk on shutdown and load the buffer on startup
                restartAtNextConfigVersion(),
                // check that the block buffer was saved to disk on shutdown and it was loaded from disk on startup
                // additionally, check that the buffer is still in a partially saturated state
                sourcingContextual(
                        spec -> assertHgcaaLogContainsTimeframe(
                                byNodeId(0),
                                timeRef::get,
                                Duration.ofMinutes(3),
                                Duration.ofMinutes(3),
                                "Block buffer persisted to disk",
                                "Block buffer is being restored from disk",
                                "Attempting to forcefully switch block node connections due to increasing block buffer saturation")),
                // restart the block node and let it catch up
                blockNodeSimulator(0).startImmediately(),
                // create some more blocks and ensure the buffer/platform remains healthy
                waitUntilNextBlocks(maxBufferSize + halfBufferSize).withBackgroundTraffic(true),
                doingContextual(spec -> timeRef.set(Instant.now())),
                // after restart and adding more blocks, saturation should be at 0% because the block node has
                // acknowledged all old blocks and the new blocks (Note: DEBUG logging is required for this to pass)
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0), timeRef::get, Duration.ofMinutes(3), Duration.ofMinutes(3), "saturation=0.0%")));
    }
}
