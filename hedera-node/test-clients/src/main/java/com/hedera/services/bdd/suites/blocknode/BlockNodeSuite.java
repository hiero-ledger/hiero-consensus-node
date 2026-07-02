// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blocknode;

import static com.hedera.services.bdd.junit.TestTags.BLOCK_NODE;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertBlockNodeCommsLogDoesNotContainText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.awaitBlockNodeCommsLogContainsText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilNextBlocks;

import com.hedera.services.bdd.HapiBlockNode;
import com.hedera.services.bdd.HapiBlockNode.BlockNodeConfig;
import com.hedera.services.bdd.HapiBlockNode.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import java.time.Duration;
import java.util.stream.Stream;
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
        return validateHappyPath(20);
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
                        blockNodePriorities = {0, 0, 0, 0}),
                @SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {0, 1, 2, 3},
                        blockNodePriorities = {0, 0, 0, 0}),
                @SubProcessNodeConfig(
                        nodeId = 2,
                        blockNodeIds = {0, 1, 2, 3},
                        blockNodePriorities = {0, 0, 0, 0}),
                @SubProcessNodeConfig(
                        nodeId = 3,
                        blockNodeIds = {0, 1, 2, 3},
                        blockNodePriorities = {0, 0, 0, 0})
            })
    @Order(2)
    final Stream<DynamicTest> allP0NodesStreamingHappyPath() {
        // Use fewer blocks than the single-node test since 4 real block node containers
        // and 4 consensus nodes need more startup time, reducing the window for block production
        return validateHappyPath(5);
    }

    private Stream<DynamicTest> validateHappyPath(final int blocksToWait) {
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
                        byNodeId(0), "Block node reported an error at block", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Block node reported an unknown error at block", Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0),
                        "Block node has exceeded the number of allowed EndOfStream responses",
                        Duration.ofSeconds(0)),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0),
                        "Block node reported status indicating immediate restart should be attempted",
                        Duration.ofSeconds(0)),

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
                awaitBlockNodeCommsLogContainsText(
                        byNodeId(0), "Sending request to block node (type: END_OF_BLOCK)", Duration.ofSeconds(30)));
    }
}
