// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blocknode;

import static com.hedera.services.bdd.junit.TestTags.BLOCK_NODE;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.BlockNodeVerbs.blockNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertBlockNodeCommsLogContainsTimeframe;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertBlockNodeCommsLogDoesNotContainText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.awaitBlockNodeCommsLogContainsText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilNextBlocks;

import com.hedera.services.bdd.HapiBlockNode;
import com.hedera.services.bdd.HapiBlockNode.BlockNodeConfig;
import com.hedera.services.bdd.HapiBlockNode.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import com.hedera.services.bdd.spec.SpecOperation;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

/**
 * Consensus-node to block-node communication tests that use <b>real</b> block node containers.
 * Simulator-based tests live in {@link BlockNodeSimSuite} so they can run as a separate, parallel
 * CI task on a regular (non block-node) runner.
 * NOTE: com.hedera.node.app.blocks.impl.streaming MUST have DEBUG logging enabled.
 */
@Tag(BLOCK_NODE)
@OrderedInIsolation
public class BlockNodeSuite {

    private static final Logger log = LogManager.getLogger(BlockNodeSuite.class);

    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.REAL)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0})
            })
    @Order(1)
    final Stream<DynamicTest> node0StreamingHappyPath() {
        // node0 is its block node's only publisher, so it is never skipped
        return validateHappyPath(
                20,
                "Sending request to block node (type: END_OF_BLOCK)",
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Received SkipBlock response", Duration.ofSeconds(0)));
    }

    // All four block nodes share priority group 0, and the connection manager probes an entire priority
    // group under a single blockNode.blockNodeStatusTimeout budget (one invokeAll call for the group).
    // The 1s default cannot cover constructing four gRPC clients, each of which is itself allowed
    // connectionManagementTimeout (3s), so on a loaded runner every probe times out and every block node
    // is cooled down.
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
                            "blockNode.blockNodeStatusTimeout", "10s",
                            "blockNode.basicNodeCoolDownSeconds", "1"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {0, 1, 2, 3},
                        blockNodePriorities = {0, 0, 0, 0},
                        applicationPropertiesOverrides = {
                            "blockNode.blockNodeStatusTimeout", "10s",
                            "blockNode.basicNodeCoolDownSeconds", "1"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 2,
                        blockNodeIds = {0, 1, 2, 3},
                        blockNodePriorities = {0, 0, 0, 0},
                        applicationPropertiesOverrides = {
                            "blockNode.blockNodeStatusTimeout", "10s",
                            "blockNode.basicNodeCoolDownSeconds", "1"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 3,
                        blockNodeIds = {0, 1, 2, 3},
                        blockNodePriorities = {0, 0, 0, 0},
                        applicationPropertiesOverrides = {
                            "blockNode.blockNodeStatusTimeout", "10s",
                            "blockNode.basicNodeCoolDownSeconds", "1"
                        })
            })
    @Order(2)
    final Stream<DynamicTest> allP0NodesStreamingHappyPath() {
        // Use fewer blocks than the single-node test since 4 real block node containers
        // and 4 consensus nodes need more startup time, reducing the window for block production
        // Nodes may share a block node, which takes each block from the first publisher and sends the
        // others SkipBlock (so no END_OF_BLOCK from them); every publisher sends the block's items first
        return validateHappyPath(5, "Sending request to block node (type: BLOCK_ITEMS)");
    }

    @HapiTest
    @HapiBlockNode(
            networkSize = 3,
            blockNodeConfigs = {
                @BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.REAL),
                @BlockNodeConfig(nodeId = 1, mode = BlockNodeMode.REAL),
                @BlockNodeConfig(nodeId = 2, mode = BlockNodeMode.REAL)
            },
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0, 1, 2},
                        blockNodePriorities = {0, 1, 2},
                        applicationPropertiesOverrides = {
                            "blockNode.blockNodeStatusTimeout", "10s",
                            "blockNode.basicNodeCoolDownSeconds", "1",
                            "blockStream.buffer.minAcksRequiredPerBlock", "3",
                            "blockNode.maxObserverIdleSeconds", "10"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {0, 1, 2},
                        blockNodePriorities = {1, 2, 0},
                        applicationPropertiesOverrides = {
                            "blockNode.blockNodeStatusTimeout", "10s",
                            "blockNode.basicNodeCoolDownSeconds", "1",
                            "blockStream.buffer.minAcksRequiredPerBlock", "3",
                            "blockNode.maxObserverIdleSeconds", "10"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 2,
                        blockNodeIds = {0, 1, 2},
                        blockNodePriorities = {2, 0, 1},
                        applicationPropertiesOverrides = {
                            "blockNode.blockNodeStatusTimeout", "10s",
                            "blockNode.basicNodeCoolDownSeconds", "1",
                            "blockStream.buffer.minAcksRequiredPerBlock", "3",
                            "blockNode.maxObserverIdleSeconds", "10"
                        })
            })
    @Order(3)
    final Stream<DynamicTest> multipleCnsWithMultiAck() {
        return validateHappyPath(20, "Sending request to block node (type: ACKNOWLEDGE_ONLY)");
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
                        blockNodeIds = {0, 1, 2, 3},
                        blockNodePriorities = {0, 1, 2, 3},
                        applicationPropertiesOverrides = {
                            "blockNode.blockNodeStatusTimeout", "10s",
                            "blockNode.basicNodeCoolDownSeconds", "1",
                            "blockStream.buffer.minAcksRequiredPerBlock", "2",
                            "blockNode.maxObserverIdleSeconds", "10",
                            "blockStream.buffer.maxBlocks", "150",
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "tss.forceMockSignatures", "false",
                            "blockStream.buffer.ackedBlocksToRetain", "125"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {0, 1, 2, 3},
                        blockNodePriorities = {1, 2, 3, 0},
                        applicationPropertiesOverrides = {
                            "blockNode.blockNodeStatusTimeout", "10s",
                            "blockNode.basicNodeCoolDownSeconds", "1",
                            "blockStream.buffer.minAcksRequiredPerBlock", "2",
                            "blockNode.maxObserverIdleSeconds", "10",
                            "blockStream.buffer.maxBlocks", "150",
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "tss.forceMockSignatures", "false",
                            "blockStream.buffer.ackedBlocksToRetain", "125"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 2,
                        blockNodeIds = {0, 1, 2, 3},
                        blockNodePriorities = {2, 3, 0, 1},
                        applicationPropertiesOverrides = {
                            "blockNode.blockNodeStatusTimeout", "10s",
                            "blockNode.basicNodeCoolDownSeconds", "1",
                            "blockStream.buffer.minAcksRequiredPerBlock", "2",
                            "blockNode.maxObserverIdleSeconds", "10",
                            "blockStream.buffer.maxBlocks", "150",
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "tss.forceMockSignatures", "false",
                            "blockStream.buffer.ackedBlocksToRetain", "125"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 3,
                        blockNodeIds = {0, 1, 2, 3},
                        blockNodePriorities = {3, 0, 1, 2},
                        applicationPropertiesOverrides = {
                            "blockNode.blockNodeStatusTimeout", "10s",
                            "blockNode.basicNodeCoolDownSeconds", "1",
                            "blockStream.buffer.minAcksRequiredPerBlock", "2",
                            "blockNode.maxObserverIdleSeconds", "10",
                            "blockStream.buffer.maxBlocks", "150",
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "tss.forceMockSignatures", "false",
                            "blockStream.buffer.ackedBlocksToRetain", "125"
                        })
            })
    @Order(4)
    final Stream<DynamicTest> mutliAckWithRollingBlockNodes() {
        return hapiTest(
                doingContextual(spec -> {
                    log.info(
                            "::TEST:: Block Node Ports: 0={}, 1={}, 2={}, 3={}",
                            spec.getBlockNodePortById(0),
                            spec.getBlockNodePortById(1),
                            spec.getBlockNodePortById(2),
                            spec.getBlockNodePortById(3));
                }),
                /*
                Phase 1: Baseline
                 With all nodes active, wait to produce 15 blocks. After creating those 15 blocks, verify that the
                 buffer doesn't have any unacknowledged blocks. If this verification passes, it means we have a good
                 baseline to continue the test with.
                 */
                doingContextual(_ -> log.info("::TEST:: PHASE 1")),
                waitUntilNextBlocks(15).withBackgroundTraffic(true),
                // -- verify no block are pending acknowledgement
                assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0), Instant::now, Duration.ofMinutes(1), Duration.ofMinutes(1), "PendingAck: 0"),
                /*
                Phase 2: Stop 1 Block Node (1 total down)
                  Stop one of the block nodes. Since there are 4 block nodes, and we require a minimum of 2 ACKs, we
                  still have more block nodes than we need for each consensus node. There will be some churn as
                  streaming and observing connections adjust to the topology change, but everything should settle quick
                  and no adverse issues should occur.
                 */
                doingContextual(_ -> log.info("::TEST:: PHASE 2")),
                blockNode(0).shutDownImmediately(),
                waitUntilNextBlocks(15),
                // -- verify no block are pending acknowledgement
                assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0), Instant::now, Duration.ofMinutes(1), Duration.ofMinutes(1), "PendingAck: 0"),
                /*
                Phase 3: Stop Another Block Node (2 total down)
                  Stop another block node. Each consensus node now has 2 block nodes it can connect to. One of the block
                  nodes will be the streaming target and the other the observing target. Since we still have 2 valid
                  sources of acknowledgements, we are able to meet the minimum requirement. Aside from some initial
                  connection churn to adjust to the topology change, no adverse issues are expected.
                 */
                doingContextual(_ -> log.info("::TEST:: PHASE 3")),
                blockNode(1).shutDownImmediately(),
                waitUntilNextBlocks(15),
                // -- verify no block are pending acknowledgement
                assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0), Instant::now, Duration.ofMinutes(1), Duration.ofMinutes(1), "PendingAck: 0"),
                /*
                Phase 4: Stop Another Block Node (3 total down)
                  Stop another block node. Each consensus node will now have only 1 block node it can connect to. This
                  one target will be used for streaming and thus no observing connections can be established. Since this
                  causes us to fall below the minimum number of acknowledgements, we should see buffer saturation start
                  to increase.
                 */
                doingContextual(_ -> log.info("::TEST:: PHASE 4")),
                blockNode(2).shutDownImmediately(),
                // -- verify the buffer is starting to become saturated
                assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0), Instant::now, Duration.ofMinutes(1), Duration.ofMinutes(1), "PendingAck: 10"),
                /*
                Phase 5: Restart a Block Node (2 total down)
                  Start one of the down block nodes. When the block node is restarted, we should see each consensus
                  node establish an observer. At this point, each consensus node should have two sources of
                  acknowledgements: one from the streaming connection and another from the observing connection. This
                  allows us to meet the minimum number of acknowledgements and as such we should see buffer saturation
                  recover.
                 */
                doingContextual(_ -> log.info("::TEST:: PHASE 5")),
                blockNode(2).startImmediately(),
                // -- verify recovery by ensuring no blocks are pending acknowledgement after the BN restart
                assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0), Instant::now, Duration.ofMinutes(1), Duration.ofMinutes(1), "PendingAck: 0"),
                /*
                Phase 6: Restart Remaining Block Nodes (0 total down)
                  Restart the other two down block nodes. Additional observers should be established behind the scenes
                  such that we have our ideal number of observing connections.
                 */
                doingContextual(_ -> log.info("::TEST:: PHASE 6")),
                blockNode(1).startImmediately(),
                blockNode(0).startImmediately(),
                assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0),
                        Instant::now,
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(1),
                        "Desired number of observing connections reached"),
                waitUntilNextBlocks(15),
                assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0), Instant::now, Duration.ofMinutes(1), Duration.ofMinutes(1), "PendingAck: 0"));
    }

    private Stream<DynamicTest> validateHappyPath(
            final int blocksToWait, final String streamingLogText, final SpecOperation... extraChecks) {
        return hapiTest(
                waitUntilNextBlocks(blocksToWait).withBackgroundTraffic(true),

                // Block node connection error assertions
                assertBlockNodeCommsLogDoesNotContainText(byNodeId(0), "Error received", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Exception caught in connection worker thread", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "UncheckedIOException caught in connection worker thread", Duration.ofSeconds(0)),

                // EndOfStream error assertions
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Received EndOfStream response", Duration.ofSeconds(0)),

                // Connection state transition error assertions
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Failed to transition state from ", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Stream completed unexpectedly", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Error while completing request pipeline", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "onNext invoked but connection is already closed", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "onComplete invoked but connection is already closed", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Error occurred while attempting to close connection", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Unexpected response received", Duration.ofSeconds(0)),

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
                        "Buffer saturation is below or equal to the recovery threshold; back pressure will be disabled.",
                        Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0),
                        "Attempted to disable back pressure, but buffer saturation is not less than or equal to recovery threshold",
                        Duration.ofSeconds(0)),

                // Block processing error assertions
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), " not found in buffer (latestBlock: ", Duration.ofSeconds(0)),
                blockingOrder(extraChecks),
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
                awaitBlockNodeCommsLogContainsText(byNodeId(0), streamingLogText, Duration.ofSeconds(30)));
    }
}
