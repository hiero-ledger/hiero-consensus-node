// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blocknode;

import static com.hedera.services.bdd.junit.TestTags.BLOCK_NODE;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.BlockNodeVerbs.blockNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertBlockNodeCommsLogContainsText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertBlockNodeCommsLogContainsTimeframe;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertBlockNodeCommsLogDoesNotContainText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilNextBlocks;
import static com.hedera.services.bdd.suites.regression.system.LifecycleTest.restartAtNextConfigVersion;

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
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

/**
 * This suite class tests the behavior of the consensus node to block node communication.
 * NOTE: com.hedera.node.app.blocks.impl.streaming MUST have DEBUG logging enabled.
 */
@Tag(BLOCK_NODE)
@OrderedInIsolation
public class BlockNodeSuite {
    private static final int BLOCK_PERIOD_SECONDS = 2;

    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.REAL)},
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
    @Order(1)
    final Stream<DynamicTest> node0StreamingHappyPath() {
        return validateHappyPath(20);
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
    @Order(2)
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
                blockNode(0).shutDownImmediately(), // Pri 0
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0),
                        connectionDropTime::get,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(45),
                        String.format("Selected block node localhost:%s for connection attempt", portNumbers.get(1)),
                        String.format(
                                "/localhost:%s/READY] Connection state transitioned from UNINITIALIZED to READY",
                                portNumbers.get(1)),
                        String.format(
                                "/localhost:%s/ACTIVE] Connection state transitioned from READY to ACTIVE",
                                portNumbers.get(1)))),
                waitUntilNextBlocks(10).withBackgroundTraffic(true),
                doingContextual(spec -> connectionDropTime.set(Instant.now())),
                blockNode(1).shutDownImmediately(), // Pri 1
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0),
                        connectionDropTime::get,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(45),
                        String.format(
                                "/localhost:%s/READY] Connection state transitioned from UNINITIALIZED to READY",
                                portNumbers.get(2)),
                        String.format(
                                "/localhost:%s/ACTIVE] Connection state transitioned from READY to ACTIVE",
                                portNumbers.get(2)))),
                waitUntilNextBlocks(10).withBackgroundTraffic(true),
                doingContextual(spec -> connectionDropTime.set(Instant.now())),
                blockNode(2).shutDownImmediately(), // Pri 2
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0),
                        connectionDropTime::get,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(45),
                        String.format(
                                "/localhost:%s/READY] Connection state transitioned from UNINITIALIZED to READY",
                                portNumbers.get(3)),
                        String.format(
                                "/localhost:%s/ACTIVE] Connection state transitioned from READY to ACTIVE",
                                portNumbers.get(3)))),
                waitUntilNextBlocks(10).withBackgroundTraffic(true),
                doingContextual(spec -> connectionDropTime.set(Instant.now())),
                blockNode(1).startImmediately(),
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0),
                        connectionDropTime::get,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(45),
                        String.format(
                                "/localhost:%s/READY] Connection state transitioned from UNINITIALIZED to READY",
                                portNumbers.get(1)),
                        String.format(
                                "/localhost:%s/ACTIVE] Connection state transitioned from READY to ACTIVE",
                                portNumbers.get(1)),
                        String.format(
                                "/localhost:%s/ACTIVE] Connection will be closed at the next block boundary",
                                portNumbers.get(3)),
                        String.format(
                                "/localhost:%s/ACTIVE] Block boundary reached; closing connection", portNumbers.get(3)),
                        String.format("/localhost:%s/CLOSING] Closing connection.", portNumbers.get(3)),
                        String.format(
                                "/localhost:%s/CLOSING] Connection state transitioned from ACTIVE to CLOSING",
                                portNumbers.get(3)),
                        String.format(
                                "/localhost:%s/CLOSED] Connection state transitioned from CLOSING to CLOSED",
                                portNumbers.get(3)))),
                doingContextual(spec -> connectionDropTime.set(Instant.now())),
                waitUntilNextBlocks(5),
                blockNode(1).shutDownImmediately(), // Pri 1
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0),
                        connectionDropTime::get,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(45),
                        String.format(
                                "/localhost:%s/READY] Connection state transitioned from UNINITIALIZED to READY",
                                portNumbers.get(3)),
                        String.format(
                                "/localhost:%s/ACTIVE] Connection state transitioned from READY to ACTIVE",
                                portNumbers.get(3)))));
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
                            "blockStream.buffer.maxBlocks",
                            "30",
                            "blockStream.blockPeriod",
                            BLOCK_PERIOD_SECONDS + "s",
                            "blockStream.streamMode",
                            "BLOCKS",
                            "blockStream.writerMode",
                            "FILE_AND_GRPC",
                            "blockNode.forcedSwitchRescheduleDelay",
                            "30s"
                        })
            })
    @Order(3)
    final Stream<DynamicTest> testProactiveBlockBufferAction() {
        final AtomicReference<Instant> timeRef = new AtomicReference<>();
        final List<Integer> portNumbers = new ArrayList<>();
        return hapiTest(
                doingContextual(spec -> {
                    portNumbers.add(spec.getBlockNodePortById(0));
                    portNumbers.add(spec.getBlockNodePortById(1));
                }),
                doingContextual(
                        spec -> LockSupport.parkNanos(Duration.ofSeconds(5).toNanos())),
                doingContextual(spec -> timeRef.set(Instant.now())),
                blockNode(0).updateSendingBlockAcknowledgements(false),
                doingContextual(
                        spec -> LockSupport.parkNanos(Duration.ofSeconds(5).toNanos())),
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(1),
                        // look for the saturation reaching the action stage (50%)
                        "saturation=50.0%",
                        // look for the log that shows we are forcing a reconnect to a different block node
                        "Attempting to forcefully switch block node connections due to increasing block buffer saturation",
                        "/localhost:" + portNumbers.get(1)
                                + "/ACTIVE] Connection state transitioned from READY to ACTIVE")),
                blockNode(0).updateSendingBlockAcknowledgements(true),
                doingContextual(spec -> timeRef.set(Instant.now())),
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofMinutes(2),
                        Duration.ofMinutes(2),
                        // saturation should fall back to low levels after the reconnect to the different node
                        // then we should see a switch back to higher priority node
                        "saturation=0.0%",
                        "/localhost:" + portNumbers.get(0)
                                + "/ACTIVE] Connection state transitioned from READY to ACTIVE")));
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
                            "blockStream.writerMode", "FILE_AND_GRPC",
                            "blockStream.buffer.maxBlocks", "60",
                            "blockStream.buffer.isBufferPersistenceEnabled", "true",
                            "blockStream.blockPeriod", BLOCK_PERIOD_SECONDS + "s",
                            "blockNode.streamResetPeriod", "20s",
                        })
            })
    @Order(4)
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
        final int maxBufferSize = 60;
        final int halfBufferSize = Math.max(1, maxBufferSize / 2);
        final Duration duration = Duration.ofSeconds(maxBufferSize * BLOCK_PERIOD_SECONDS);

        return hapiTest(
                // create some blocks to establish a baseline
                waitUntilNextBlocks(halfBufferSize).withBackgroundTraffic(true),
                doingContextual(spec -> timeRef.set(Instant.now())),
                // shutdown the block node. this will cause the block buffer to become saturated
                blockNode(0).shutDownImmediately(),
                waitUntilNextBlocks(halfBufferSize).withBackgroundTraffic(true),
                // wait until the buffer is starting to get saturated
                sourcingContextual(
                        spec -> assertBlockNodeCommsLogContainsTimeframe(
                                byNodeId(0),
                                timeRef::get,
                                duration,
                                duration,
                                "Attempting to forcefully switch block node connections due to increasing block buffer saturation")),
                doingContextual(spec -> timeRef.set(Instant.now())),
                // restart the consensus node
                // this should persist the buffer to disk on shutdown and load the buffer on startup
                restartAtNextConfigVersion(),
                // check that the block buffer was saved to disk on shutdown and it was loaded from disk on startup
                // additionally, check that the buffer is still in a partially saturated state
                sourcingContextual(
                        spec -> assertBlockNodeCommsLogContainsTimeframe(
                                byNodeId(0),
                                timeRef::get,
                                Duration.ofMinutes(3),
                                Duration.ofMinutes(3),
                                "Block buffer persisted to disk",
                                "Block buffer is being restored from disk",
                                "Attempting to forcefully switch block node connections due to increasing block buffer saturation")),
                // restart the block node and let it catch up
                blockNode(0).startImmediately(),
                // create some more blocks and ensure the buffer/platform remains healthy
                waitUntilNextBlocks(maxBufferSize + halfBufferSize).withBackgroundTraffic(true),
                doingContextual(spec -> timeRef.set(Instant.now())),
                // after restart and adding more blocks, saturation should be at 0% because the block node has
                // acknowledged all old blocks and the new blocks
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0), timeRef::get, Duration.ofMinutes(3), Duration.ofMinutes(3), "saturation=0.0%")));
    }

    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.REAL)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "FILE_AND_GRPC",
                        })
            })
    @Order(5)
    final Stream<DynamicTest> testBlockNodeRestartSaturation() {
        final Duration duration = Duration.ofSeconds(30);
        final AtomicReference<Instant> timeRef = new AtomicReference<>();
        return hapiTest(
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                blockNode(0).shutDownImmediately(),
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                blockNode(0).startImmediately(),
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                doingContextual(spec -> timeRef.set(Instant.now())),
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0), timeRef::get, duration, duration, "saturation=0.0%")));
    }

    @HapiTest
    @HapiBlockNode(
            blockNodeConfigs = {
                @BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.REAL),
                @BlockNodeConfig(nodeId = 1, mode = BlockNodeMode.REAL),
                @BlockNodeConfig(nodeId = 2, mode = BlockNodeMode.REAL),
                @BlockNodeConfig(nodeId = 3, mode = BlockNodeMode.REAL),
            },
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0, 1, 2, 3},
                        blockNodePriorities = {0, 0, 0, 0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        })
            })
    @Order(6)
    final Stream<DynamicTest> allP0NodesStreamingHappyPath() {
        return validateHappyPath(10);
    }

    private Stream<DynamicTest> validateHappyPath(final int blocksToWait) {
        return hapiTest(
                waitUntilNextBlocks(blocksToWait).withBackgroundTraffic(true),

                // General error assertions
                assertBlockNodeCommsLogDoesNotContainText(byNodeId(0), "ERROR", Duration.ofSeconds(5)),

                // Block node connection error assertions
                assertBlockNodeCommsLogDoesNotContainText(byNodeId(0), "Error received", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Exception caught in block stream worker loop", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "UncheckedIOException caught in block stream worker loop", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Failed to establish connection to block node", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Failed to schedule connection task for block node", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Failed to reschedule connection attempt", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0),
                        "Closing and rescheduling connection for reconnect attempt",
                        Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "No available block nodes found for streaming", Duration.ofSeconds(0)),

                // EndOfStream error assertions
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Block node reported an error at block", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Block node reported an unknown error at block", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0),
                        "Block node has exceeded the allowed number of EndOfStream responses",
                        Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0),
                        "Block node reported status indicating immediate restart should be attempted",
                        Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Block node reported it is behind", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Block node is behind and block state is not available", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Received EndOfStream response", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Sending EndStream (code=", Duration.ofSeconds(0)),

                // Connection state transition error assertions
                assertBlockNodeCommsLogDoesNotContainText(byNodeId(0), "Handling failed stream", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Failed to transition state from ", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Stream completed unexpectedly", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Error while completing request pipeline", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "onNext invoked but connection is already closed", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0),
                        "Cannot run connection task, connection manager has shutdown.",
                        Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "onComplete invoked but connection is already closed", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Error occurred while attempting to close connection", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Unexpected response received", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Failed to shutdown current active connection", Duration.ofSeconds(0)),

                // Block buffer saturation and backpressure assertions
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Block buffer is saturated; backpressure is being enabled", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0),
                        "!!! Block buffer is saturated; blocking thread until buffer is no longer saturated",
                        Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Block buffer still not available to accept new blocks", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0),
                        "Attempting to forcefully switch block node connections due to increasing block buffer saturation",
                        Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0),
                        "Buffer saturation is below or equal to the recovery threshold; back pressure will be disabled.",
                        Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0),
                        "Attempted to disable back pressure, but buffer saturation is not less than or equal to recovery threshold",
                        Duration.ofSeconds(0)),

                // Block processing error assertions
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), " not found in buffer (latestBlock=", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Received SkipBlock response for block ", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Received ResendBlock response for block ", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0),
                        "that block does not exist on this consensus node. Closing connection and will retry later.",
                        Duration.ofSeconds(0)),

                // Configuration and setup error assertions
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "streaming is not enabled", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Failed to read block node configuration from", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Failed to resolve block node host", Duration.ofSeconds(0)),

                // High latency assertions
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Block node has exceeded high latency threshold", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogContainsText(
                        byNodeId(0), "Sending request to block node (type=END_OF_BLOCK)", Duration.ofSeconds(0)));
    }
}
