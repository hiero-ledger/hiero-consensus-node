// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blocknode;

import static com.hedera.services.bdd.junit.TestTags.BLOCK_NODE;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.allNodes;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.BlockNodeVerbs.blockNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertBlockNodeCommsLogContainsTimeframe;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForActive;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForAny;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilNextBlocks;

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
                            "blockStream.buffer.maxBlocks",
                            "15",
                            "blockStream.streamMode",
                            "BLOCKS",
                            "blockStream.writerMode",
                            "FILE_AND_GRPC"
                        })
            })
    @Order(1)
    final Stream<DynamicTest> backPressureAppliedWhenBlocksAndFileAndGrpc() {
        final AtomicReference<Instant> time = new AtomicReference<>();
        return hapiTest(
                waitUntilNextBlocks(5),
                blockNode(0).shutDownImmediately(),
                doingContextual(spec -> time.set(Instant.now())),
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
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
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.REAL)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.buffer.maxBlocks",
                            "15",
                            "blockStream.streamMode",
                            "BLOCKS",
                            "blockStream.writerMode",
                            "FILE_AND_GRPC"
                        })
            })
    @Order(2)
    final Stream<DynamicTest> testBlockBufferBackPressure() {
        final AtomicReference<Instant> timeRef = new AtomicReference<>();

        return hapiTest(
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                doingContextual(spec -> timeRef.set(Instant.now())),
                blockNode(0).shutDownImmediately(),
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofMinutes(6),
                        Duration.ofMinutes(6),
                        "Block buffer is saturated; backpressure is being enabled",
                        "!!! Block buffer is saturated; blocking thread until buffer is no longer saturated")),
                waitForAny(byNodeId(0), Duration.ofSeconds(30), PlatformStatus.CHECKING),
                blockNode(0).startImmediately(),
                sourcingContextual(
                        spec -> assertBlockNodeCommsLogContainsTimeframe(
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
            networkSize = 4,
            blockNodeConfigs = {
                @BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.REAL),
                @BlockNodeConfig(nodeId = 1, mode = BlockNodeMode.REAL),
                @BlockNodeConfig(nodeId = 2, mode = BlockNodeMode.REAL),
                @BlockNodeConfig(nodeId = 3, mode = BlockNodeMode.REAL)
            },
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.buffer.maxBlocks", "5",
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {1},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.buffer.maxBlocks", "5",
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 2,
                        blockNodeIds = {2},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.buffer.maxBlocks", "5",
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 3,
                        blockNodeIds = {3},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.buffer.maxBlocks", "5",
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC"
                        })
            })
    @Order(3)
    final Stream<DynamicTest> backPressureAllNodesCheckingScenario() {
        final AtomicReference<Instant> time = new AtomicReference<>();
        return hapiTest(
                doingContextual(
                        spec -> LockSupport.parkNanos(Duration.ofSeconds(10).toNanos())),
                blockNode(0).shutDownImmediately(),
                doingContextual(spec -> time.set(Instant.now())),
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0),
                        time::get,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(30),
                        "Block buffer is saturated; backpressure is being enabled",
                        "!!! Block buffer is saturated; blocking thread until buffer is no longer saturated")),
                waitForAny(byNodeId(0), Duration.ofSeconds(30), PlatformStatus.CHECKING),
                doingContextual(
                        spec -> LockSupport.parkNanos(Duration.ofMinutes(1).toNanos())),
                blockNode(0).startImmediately(),
                doingContextual(spec -> time.set(Instant.now())),
                sourcingContextual(
                        spec -> assertBlockNodeCommsLogContainsTimeframe(
                                byNodeId(0),
                                time::get,
                                Duration.ofMinutes(1),
                                Duration.ofMinutes(1),
                                "Buffer saturation is below or equal to the recovery threshold; back pressure will be disabled.")),
                waitForAny(byNodeId(0), Duration.ofSeconds(30), PlatformStatus.ACTIVE),
                doingContextual(
                        spec -> LockSupport.parkNanos(Duration.ofSeconds(30).toNanos())),
                blockNode(0).shutDownImmediately(),
                blockNode(1).shutDownImmediately(),
                waitForAny(allNodes(), Duration.ofSeconds(120), PlatformStatus.CHECKING),
                doingContextual(
                        spec -> LockSupport.parkNanos(Duration.ofMinutes(1).toNanos())),
                blockNode(0).startImmediately(),
                blockNode(1).startImmediately(),
                waitForAny(allNodes(), Duration.ofSeconds(120), PlatformStatus.ACTIVE));
    }
}
