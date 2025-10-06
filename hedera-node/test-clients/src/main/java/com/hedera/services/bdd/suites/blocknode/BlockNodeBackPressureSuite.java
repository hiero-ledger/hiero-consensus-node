// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blocknode;

import static com.hedera.services.bdd.junit.TestTags.BLOCK_NODE;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.BlockNodeVerbs.blockNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsTimeframe;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogDoesNotContain;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForAny;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import org.hiero.consensus.model.status.PlatformStatus;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

/**
 * This suite specifically tests the behavior of the block buffer service blocking the transaction handling thread
 * in HandleWorkflow depending on the configuration of streamMode and writerMode.
 */
@Tag(BLOCK_NODE)
@OrderedInIsolation
public class BlockNodeBackPressureSuite {
    private static final int BLOCK_TTL_MINUTES = 2;
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
                            "blockStream.buffer.blockTtl", "30s",
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        })
            })
    @Order(0)
    final Stream<DynamicTest> noBackPressureAppliedWhenBufferFull() {
        return hapiTest(
                waitUntilNextBlocks(5),
                blockNode(0).shutDownImmediately(),
                waitUntilNextBlocks(30),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0),
                        "Block buffer is saturated; backpressure is being enabled",
                        Duration.ofSeconds(15)));
    }

    @Disabled
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
                            "blockStream.buffer.blockTtl", "30s",
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        })
            })
    @Order(1)
    final Stream<DynamicTest> backPressureAppliedWhenBlocksAndFileAndGrpc() {
        final AtomicReference<Instant> time = new AtomicReference<>();
        return hapiTest(
                waitUntilNextBlocks(5),
                blockNode(0).shutDownImmediately(),
                doingContextual(spec -> time.set(Instant.now())),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        time::get,
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(1),
                        "Block buffer is saturated; backpressure is being enabled",
                        "!!! Block buffer is saturated; blocking thread until buffer is no longer saturated")),
                waitForAny(byNodeId(0), Duration.ofSeconds(30), PlatformStatus.CHECKING));
    }

    @Disabled
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
                            "blockStream.buffer.blockTtl", "30s",
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC"
                        })
            })
    @Order(2)
    final Stream<DynamicTest> backPressureAppliedWhenBlocksAndGrpc() {
        final AtomicReference<Instant> time = new AtomicReference<>();
        return hapiTest(
                doingContextual(
                        spec -> LockSupport.parkNanos(Duration.ofSeconds(10).toNanos())),
                blockNode(0).shutDownImmediately(),
                doingContextual(spec -> time.set(Instant.now())),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        time::get,
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(1),
                        "Block buffer is saturated; backpressure is being enabled",
                        "!!! Block buffer is saturated; blocking thread until buffer is no longer saturated")),
                waitForAny(byNodeId(0), Duration.ofSeconds(30), PlatformStatus.CHECKING));
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
                            "blockStream.buffer.blockTtl", BLOCK_TTL_MINUTES + "m",
                            "blockStream.blockPeriod", BLOCK_PERIOD_SECONDS + "s",
                            "blockNode.streamResetPeriod", "20s",
                        })
            })
    @Order(3)
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
                blockNode(0).shutDownImmediately(),
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
                blockNode(0).startImmediately(),
                // create some more blocks and ensure the buffer/platform remains healthy
                waitUntilNextBlocks(maxBufferSize + halfBufferSize).withBackgroundTraffic(true),
                doingContextual(spec -> timeRef.set(Instant.now())),
                // after restart and adding more blocks, saturation should be at 0% because the block node has
                // acknowledged all old blocks and the new blocks
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0), timeRef::get, Duration.ofMinutes(3), Duration.ofMinutes(3), "saturation=0.0%")));
    }
}
