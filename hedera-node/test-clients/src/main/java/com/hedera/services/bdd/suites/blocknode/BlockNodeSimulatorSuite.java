// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blocknode;

import static com.hedera.services.bdd.junit.TestTags.BLOCK_NODE_SIMULATOR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.BlockNodeSimulatorVerbs.blockNodeSimulator;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilNextBlock;

import com.hedera.hapi.block.protoc.PublishStreamResponseCode;
import com.hedera.services.bdd.HapiBlockNode;
import com.hedera.services.bdd.HapiBlockNode.BlockNodeConfig;
import com.hedera.services.bdd.HapiBlockNode.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
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
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        simulatorPriorities = {0})
            })
    @Order(0)
    final Stream<DynamicTest> node0StreamingHappyPath() {
        return hapiTest(
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true));
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
                        simulatorPriorities = {0}),
                @SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {1},
                        simulatorPriorities = {0}),
                @SubProcessNodeConfig(
                        nodeId = 2,
                        blockNodeIds = {2},
                        simulatorPriorities = {0}),
                @SubProcessNodeConfig(
                        nodeId = 3,
                        blockNodeIds = {3},
                        simulatorPriorities = {0}),
            })
    @Order(1)
    final Stream<DynamicTest> allNodesStreamingHappyPath() {
        return hapiTest(
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true));
    }

    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        simulatorPriorities = {0})
            })
    @Order(2)
    final Stream<DynamicTest> node0StreamingBlockNodeConnectionDropsCanStreamGenesisBlock() {
        return hapiTest(
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                blockNodeSimulator(0).shutDownImmediately(), // Shutdown BN 0
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                blockNodeSimulator(0).startImmediately(), // Start BN 0
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true));
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
                        simulatorPriorities = {0, 1})
            })
    @Order(3)
    final Stream<DynamicTest> node0StreamingBlockNodeConnectionDropsSwitchLower() {
        AtomicReference<Instant> connectionDropTime = new AtomicReference<>();
        return hapiTest(
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                doingContextual(spec -> connectionDropTime.set(Instant.now())),
                blockNodeSimulator(0).shutDownImmediately(),
                waitUntilNextBlock().withBackgroundTraffic(true),
                // TODO assert that consensus node switches to lower priority block node
                blockNodeSimulator(0).startImmediately(),
                // TODO assert that consensus node switches back to higher priority block node
                waitUntilNextBlock().withBackgroundTraffic(true));
        // TODO Switching back, assert that the consensus node is able to stream from the appropriate block through it's
        // buffer
        //  currently the simulator always restarts from block 0.
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
                        simulatorPriorities = {0, 1, 2, 3})
            })
    @Order(4)
    final Stream<DynamicTest> node0StreamingBlockNodeConnectionDropsTrickle() {
        AtomicReference<Instant> connectionDropTime = new AtomicReference<>();
        return hapiTest(
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                doingContextual(spec -> connectionDropTime.set(Instant.now())),
                blockNodeSimulator(0).shutDownImmediately(), // Pri 0
                waitUntilNextBlock().withBackgroundTraffic(true),
                // TODO assert that consensus node switches to lower priority 1 block node
                blockNodeSimulator(1).shutDownImmediately(), // Pri 1
                // TODO assert that consensus node switches to lower priority 2 block node
                blockNodeSimulator(2).shutDownImmediately(), // Pri 2
                // TODO assert that consensus node switches to lower priority 3 block node
                // Startup BN 1
                blockNodeSimulator(1).startImmediately(),
                // TODO assert that consensus node switches to higher priority 1 block node
                // TODO assert that the consensus node doesn't retry connecting to Pri 2 anymore
                blockNodeSimulator(0).startImmediately()
                // CN Should switch to BN 1
                // TODO assert that consensus node switches to higher priority 0 block node
                );
    }

    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {
                @BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR),
            },
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        simulatorPriorities = {0})
            })
    @Order(5)
    final Stream<DynamicTest> node0StreamingBlockNodeMultipleEndOfStreamInSuccession() {
        AtomicReference<Instant> connectionDropTime = new AtomicReference<>();
        return hapiTest(
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                doingContextual(spec -> connectionDropTime.set(Instant.now())),
                blockNodeSimulator(0).sendEndOfStreamImmediately(PublishStreamResponseCode.STREAM_ITEMS_INTERNAL_ERROR),
                // TODO assert that the consensus node restarts stream
                doingContextual(spec -> {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }),
                blockNodeSimulator(0).sendEndOfStreamImmediately(PublishStreamResponseCode.STREAM_ITEMS_INTERNAL_ERROR),
                // TODO assert that the consensus node restarts stream
                doingContextual(spec -> {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }),
                blockNodeSimulator(0).sendEndOfStreamImmediately(PublishStreamResponseCode.STREAM_ITEMS_INTERNAL_ERROR),
                // TODO assert that the consensus node restarts stream
                doingContextual(spec -> {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }),
                waitUntilNextBlock().withBackgroundTraffic(true)
                // TODO assert that the consensus node goes into exponential backoff
                );
    }

    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        simulatorPriorities = {0})
            })
    @Order(6)
    final Stream<DynamicTest> node0StreamingBlockNodeEndOfStreamResponseCodes() {
        return hapiTest(
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                blockNodeSimulator(0).sendEndOfStreamImmediately(PublishStreamResponseCode.STREAM_ITEMS_UNKNOWN),
                // TODO assert that the consensus node restarts stream
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                blockNodeSimulator(0).sendEndOfStreamImmediately(PublishStreamResponseCode.STREAM_ITEMS_TIMEOUT),
                // TODO assert that the consensus node restarts stream
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                blockNodeSimulator(0).sendEndOfStreamImmediately(PublishStreamResponseCode.STREAM_ITEMS_OUT_OF_ORDER),
                // TODO assert that the consensus node restarts stream
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                blockNodeSimulator(0)
                        .sendEndOfStreamImmediately(PublishStreamResponseCode.STREAM_ITEMS_BAD_STATE_PROOF),
                // TODO assert that the consensus node restarts stream
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                blockNodeSimulator(0).sendEndOfStreamImmediately(PublishStreamResponseCode.STREAM_ITEMS_BEHIND),
                // TODO assert that the consensus node restarts stream
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                blockNodeSimulator(0).sendEndOfStreamImmediately(PublishStreamResponseCode.STREAM_ITEMS_INTERNAL_ERROR),
                // TODO assert that the consensus node restarts stream
                // this should immediately go into a longer backoff time period
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                blockNodeSimulator(0)
                        .sendEndOfStreamImmediately(PublishStreamResponseCode.STREAM_ITEMS_PERSISTENCE_FAILED),
                // TODO assert that the consensus node restarts stream
                // this should immediately go into a longer backoff time period
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true));
    }

    @HapiTest
    @HapiBlockNode(
            networkSize = 2,
            blockNodeConfigs = {
                @BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR),
                @BlockNodeConfig(nodeId = 1, mode = BlockNodeMode.SIMULATOR)
            },
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        simulatorPriorities = {0}),
                @SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {1},
                        simulatorPriorities = {0})
            })
    @Order(7)
    final Stream<DynamicTest> twoNodesStreamingHappyPath() {
        return hapiTest(
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true));
    }

    @HapiTest
    @HapiBlockNode(
            networkSize = 2,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        simulatorPriorities = {0}),
                @SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {0},
                        simulatorPriorities = {0})
            })
    @Order(8)
    final Stream<DynamicTest> twoNodesStreamingOneBlockNodeHappyPath() {
        return hapiTest(
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                // TODO assert that the consensus node should eventually receive a SkipBlock message as blocks are
                // being produced
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true));
    }

    @HapiTest
    @HapiBlockNode(
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        simulatorPriorities = {0})
            })
    @Order(9)
    final Stream<DynamicTest> node0StreamingResendBlock() {
        AtomicLong lastVerifiedBlock = new AtomicLong();
        return hapiTest(
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                blockNodeSimulator(0).getLastVerifiedBlockExposing(lastVerifiedBlock::set),
                blockNodeSimulator(0).sendResendBlockImmediately(lastVerifiedBlock.get() - 1),
                // TODO assert that the consensus node should end the stream, and restart at lastVerifiedBlock - 1.
                // It should be available in the buffer.
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true));
    }

    @HapiTest
    @HapiBlockNode(
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        simulatorPriorities = {0})
            })
    @Order(10)
    final Stream<DynamicTest> node0StreamingBufferFullHappyPath() {
        AtomicLong lastVerifiedBlock = new AtomicLong();
        return hapiTest(
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                blockNodeSimulator(0).getLastVerifiedBlockExposing(lastVerifiedBlock::set),
                blockNodeSimulator(0).sendResendBlockImmediately(lastVerifiedBlock.get() - 1),
                // TODO assert that the consensus node should end the stream, and restart at lastVerifiedBlock - 1.
                // It should be available in the buffer.
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true));
    }
}
