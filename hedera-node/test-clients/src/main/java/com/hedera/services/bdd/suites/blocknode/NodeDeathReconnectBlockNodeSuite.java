// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blocknode;

import static com.hedera.services.bdd.junit.TestTags.BLOCK_NODE;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogDoesNotContain;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForActive;
import static com.hedera.services.bdd.suites.regression.system.LifecycleTest.*;
import static com.hedera.services.bdd.suites.regression.system.MixedOperations.burstOfTps;

import com.hedera.services.bdd.HapiBlockNode;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.utilops.FakeNmt;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Tag(BLOCK_NODE)
@OrderedInIsolation
public class NodeDeathReconnectBlockNodeSuite implements LifecycleTest {

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
                FakeNmt.shutdownWithin(NodeSelector.byNodeId(2), SHUTDOWN_TIMEOUT),
                logIt("Node 2 is supposedly down"),
                sleepFor(PORT_UNBINDING_WAIT_PERIOD.toMillis()),
                // Submit operations when node 2 is down
                burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION),
                // Restart node2
                FakeNmt.restartWithConfigVersion(NodeSelector.byNodeId(2), CURRENT_CONFIG_VERSION.get()),
                // Wait for node2 ACTIVE (BUSY and RECONNECT_COMPLETE are too transient to reliably poll for)
                waitForActive(NodeSelector.byNodeId(2), RESTART_TO_ACTIVE_TIMEOUT),
                // Run some more transactions
                burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION),
                // And validate we can still submit transactions to node2
                cryptoCreate("somebody").setNode("5"),
                burstOfTps(MIXED_OPS_BURST_TPS, Duration.ofSeconds(60)),
                assertHgcaaLogDoesNotContain(byNodeId(0), "ERROR", Duration.ofSeconds(5)));
    }
}
