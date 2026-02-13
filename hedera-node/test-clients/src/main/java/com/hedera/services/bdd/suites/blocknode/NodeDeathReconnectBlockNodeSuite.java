// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blocknode;

import static com.hedera.services.bdd.junit.TestTags.BLOCK_NODE;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.allNodes;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogDoesNotContainText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForActive;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForAny;
import static com.hedera.services.bdd.suites.regression.system.MixedOperations.burstOfTps;

import com.hedera.services.bdd.HapiBlockNode;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import com.hedera.services.bdd.spec.utilops.FakeNmt;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.hiero.consensus.model.status.PlatformStatus;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Tag(BLOCK_NODE)
@OrderedInIsolation
public class NodeDeathReconnectBlockNodeSuite implements LifecycleTest {

    /**
     * Exercises shutdown and restart of a node while block node streaming remains stable.
     *
     * @return dynamic tests for the restart flow
     */
    @HapiTest
    @HapiBlockNode(
            networkSize = 4,
            blockNodeConfigs = {
                @HapiBlockNode.BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.REAL),
            },
            subProcessNodeConfigs = {
                @HapiBlockNode.SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        }),
                @HapiBlockNode.SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        }),
                @HapiBlockNode.SubProcessNodeConfig(
                        nodeId = 2,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        }),
                @HapiBlockNode.SubProcessNodeConfig(
                        nodeId = 3,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        })
            })
    @Order(1)
    final Stream<DynamicTest> nodeDeathReconnectBothAndFileAndGrpc() {
        return hapiTest(
                // Validate we can initially submit transactions to node2
                cryptoCreate("nobody").setNode("5"),
                // Run some mixed transactions
                burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION),
                // Stop node 2
                FakeNmt.shutdownWithin(byNodeId(2), SHUTDOWN_TIMEOUT),
                logIt("Node 2 is supposedly down"),
                sleepFor(PORT_UNBINDING_WAIT_PERIOD.toMillis()),
                // Submit operations when node 2 is down
                burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION),
                // Restart node2
                FakeNmt.restartWithConfigVersion(byNodeId(2), CURRENT_CONFIG_VERSION.get()),
                // Wait for node2 ACTIVE (BUSY and RECONNECT_COMPLETE are too transient to reliably poll for)
                waitForActive(byNodeId(2), RESTART_TO_ACTIVE_TIMEOUT),
                // Run some more transactions
                burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION),
                // And validate we can still submit transactions to node2
                cryptoCreate("somebody").setNode("5"),
                burstOfTps(MIXED_OPS_BURST_TPS, Duration.ofSeconds(60)),
                assertHgcaaLogDoesNotContainText(byNodeId(0), "ERROR", Duration.ofSeconds(5)));
    }

    // FUTURE: This scenario should be updated after the behavior changes on the BN side
    @Disabled
    @HapiTest
    @HapiBlockNode(
            networkSize = 2,
            blockNodeConfigs = {
                @HapiBlockNode.BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.REAL),
            },
            subProcessNodeConfigs = {
                @HapiBlockNode.SubProcessNodeConfig(
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
                        }),
                @HapiBlockNode.SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.buffer.maxBlocks",
                            "15",
                            "blockStream.streamMode",
                            "BLOCKS",
                            "blockStream.writerMode",
                            "FILE_AND_GRPC"
                        }),
            })
    @Order(2)
    final Stream<DynamicTest> nodeDeathReconnectAllNodes() {
        final AtomicReference<Instant> time = new AtomicReference<>();
        return hapiTest(
                burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION),
                // Stop all nodes
                FakeNmt.shutdownWithin(allNodes(), SHUTDOWN_TIMEOUT),
                logIt("All nodes is supposedly down"),
                sleepFor(PORT_UNBINDING_WAIT_PERIOD.toMillis()),
                // Restart all nodes
                FakeNmt.restartWithConfigVersion(allNodes(), CURRENT_CONFIG_VERSION.get()),
                // Wait for all nodes to become active
                waitForActive(allNodes(), RESTART_TO_ACTIVE_TIMEOUT),
                doingContextual(spec -> time.set(Instant.now())),
                burstOfTps(MIXED_OPS_BURST_TPS, Duration.ofSeconds(20)),
                waitForAny(allNodes(), Duration.ofSeconds(120), PlatformStatus.CHECKING));
    }
}
