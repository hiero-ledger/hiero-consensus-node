// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blocknode;

import static com.hedera.services.bdd.junit.TestTags.BLOCK_NODE;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.DATA_CONFIG_DIR;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.*;
import static com.hedera.services.bdd.suites.HapiSuite.APP_PROPERTIES;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.regression.system.MixedOperations.burstOfTps;

import com.hedera.node.internal.network.BlockNodeConnectionInfo;
import com.hedera.services.bdd.HapiBlockNode;
import com.hedera.services.bdd.HapiBlockNode.BlockNodeConfig;
import com.hedera.services.bdd.HapiBlockNode.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

/**
 * This suite is for testing consensus node software upgrade scenarios which change the writerMode to
 * FILE_AND_GRPC or other scenarios involving upgrade paths.
 */
@Tag(BLOCK_NODE)
@OrderedInIsolation
public class BlockNodeSoftwareUpgradeSuite implements LifecycleTest {

    @HapiTest
    @HapiBlockNode(
            networkSize = 4,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.REAL)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE"
                        }),
            })
    @Order(0)
    final Stream<DynamicTest> upgradeFromFileToFileAndGrpc() {
        // Initially every CN has a writerMode of FILE and there is no block-nodes.json file present in their
        // respective data/config directories.
        final AtomicReference<Instant> timeRef = new AtomicReference<>();
        final List<Integer> portNumbers = new ArrayList<>();
        return hapiTest(
                doingContextual(spec -> {
                    portNumbers.add(spec.getBlockNodePortById(0));
                }),
                waitUntilNextBlocks(10).withBackgroundTraffic(true),
                prepareFakeUpgrade(),
                doingContextual((spec) -> {
                    timeRef.set(Instant.now());
                }),
                // Now, we simulate a software upgrade which changes the default writerMode to FILE_AND_GRPC
                upgradeToNextConfigVersion(Map.of("blockStream.writerMode", "FILE_AND_GRPC")),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                // Assert that there is no block-nodes.json file present
                assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofMinutes(2),
                        Duration.ofSeconds(45),
                        "Block node configuration unchanged. No action taken."),
                burstOfTps(MIXED_OPS_BURST_TPS, Duration.ofSeconds(30)),
                // Now let's write a block-nodes.json file to the data/config directory of node 0
                // Create block-nodes.json to establish connection
                doingContextual((spec) -> {
                    timeRef.set(Instant.now());
                    // Create a new block-nodes.json file at runtime with localhost and the correct port
                    final var node0Port = spec.getBlockNodePortById(0);
                    List<com.hedera.node.internal.network.BlockNodeConfig> blockNodes = new ArrayList<>();
                    blockNodes.add(new com.hedera.node.internal.network.BlockNodeConfig("localhost", node0Port, 0));
                    BlockNodeConnectionInfo connectionInfo = new BlockNodeConnectionInfo(blockNodes);
                    try {
                        // Write the config to this consensus node's block-nodes.json
                        Path configPath = spec.getNetworkNodes()
                                .getFirst()
                                .getExternalPath(DATA_CONFIG_DIR)
                                .resolve("block-nodes.json");
                        Files.writeString(configPath, BlockNodeConnectionInfo.JSON.toJSON(connectionInfo));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }),
                // Verify config was reloaded and connection established
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(45),
                        String.format(
                                "/localhost:%s/ACTIVE] Connection state transitioned from PENDING to ACTIVE.",
                                portNumbers.getFirst()),
                        String.format(
                                "Active block node connection updated to: localhost:%s", portNumbers.getFirst()))),
                // Cleanup - delete the block-nodes.json file to stop streaming to block nodes
                // Delete block-nodes.json
                doingContextual((spec) -> {
                    try {
                        Path configPath = spec.getNetworkNodes()
                                .getFirst()
                                .getExternalPath(DATA_CONFIG_DIR)
                                .resolve("block-nodes.json");
                        Files.delete(configPath);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }),
                // Verify file deletion is detected and handled gracefully
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofSeconds(45),
                        Duration.ofSeconds(45),
                        "Detected ENTRY_DELETE event for block-nodes.json.",
                        "No valid block node configurations available after file change. Connections remain stopped.")),
                assertHgcaaLogDoesNotContain(NodeSelector.allNodes(), "ERROR", Duration.ofSeconds(5)));
    }

    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.REAL)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodePriorities = {0},
                        blockNodeIds = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        })
            })
    @Order(0)
    final Stream<DynamicTest> updateAppPropertiesWriterMode() {
        final AtomicReference<Instant> timeRef = new AtomicReference<>(Instant.now());
        final List<Integer> portNumbers = new ArrayList<>();
        return hapiTest(
                doingContextual(spec -> portNumbers.add(spec.getBlockNodePortById(0))),
                // Verify Block Node Streaming is Active
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofMinutes(2),
                        Duration.ofMinutes(2),
                        String.format(
                                "/localhost:%s/ACTIVE] Connection state transitioned from PENDING to ACTIVE.",
                                portNumbers.getFirst()),
                        String.format(
                                "Active block node connection updated to: localhost:%s", portNumbers.getFirst()))),
                waitUntilNextBlocks(10).withBackgroundTraffic(true),
                doingContextual(spec -> timeRef.set(Instant.now())),
                fileUpdate(APP_PROPERTIES)
                        .payingWith(GENESIS)
                        .overridingProps(Map.of("blockStream.writerMode", "FILE")),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofMinutes(2),
                        Duration.ofMinutes(2),
                        "Disabling gRPC Block Node streaming as the network properties have changed writerMode from FILE_AND_GRPC to FILE only",
                        String.format(
                                "/localhost:%s/CLOSED] Connection state transitioned from CLOSING to CLOSED.",
                                portNumbers.getFirst()))),
                waitUntilNextBlocks(20).withBackgroundTraffic(true),
                // Now that the writerMode is FILE only, let's enable gRPC streaming by changing the writerMode back to
                // FILE_AND_GRPC
                doingContextual(spec -> timeRef.set(Instant.now())),
                fileUpdate(APP_PROPERTIES)
                        .payingWith(GENESIS)
                        .overridingProps(Map.of("blockStream.writerMode", "FILE_AND_GRPC")),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofMinutes(2),
                        Duration.ofMinutes(2),
                        "Enabling gRPC Block Node streaming as the network properties have changed writerMode from FILE to FILE_AND_GRPC",
                        "Current streaming block number is",
                        String.format(
                                "/localhost:%s/ACTIVE] Connection state transitioned from PENDING to ACTIVE.",
                                portNumbers.getFirst()))),
                waitUntilNextBlocks(20).withBackgroundTraffic(true),
                // Verify no errors in the log after the config change and all nodes are active
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(30)),
                assertHgcaaLogDoesNotContain(NodeSelector.allNodes(), "ERROR", Duration.ofSeconds(5)));
    }
}
