// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blocknode;

import static com.hedera.services.bdd.junit.TestTags.BLOCK_NODE;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.DATA_CONFIG_DIR;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.BlockNodeVerbs.blockNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsTimeframe;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogDoesNotContain;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForActive;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForAny;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilNextBlocks;
import static com.hedera.services.bdd.suites.regression.system.LifecycleTest.*;

import com.hedera.node.internal.network.BlockNodeConnectionInfo;
import com.hedera.services.bdd.HapiBlockNode;
import com.hedera.services.bdd.HapiBlockNode.BlockNodeConfig;
import com.hedera.services.bdd.HapiBlockNode.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import org.hiero.block.api.PublishStreamResponse.EndOfStream.Code;
import org.hiero.consensus.model.status.PlatformStatus;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Disabled;
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

    private static final int BLOCK_TTL_MINUTES = 2;
    private static final int BLOCK_PERIOD_SECONDS = 2;

    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.REAL)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        })
            })
    @Order(0)
    final Stream<DynamicTest> node0SupportsDynamicBlockNodeConnectionInfo() {
        final AtomicReference<Instant> timeRef = new AtomicReference<>();
        final List<Integer> portNumbers = new ArrayList<>();
        return hapiTest(
                doingContextual(spec -> {
                    portNumbers.add(spec.getBlockNodePortById(0));
                }),
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                // Verify buffer saturation increases without block node connection
                doingContextual(spec -> timeRef.set(Instant.now())),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(30),
                        // Blocks are accumulating in buffer without being sent
                        "No active connections available for streaming block")),
                waitUntilNextBlocks(2).withBackgroundTraffic(true),
                // Create block-nodes.json to establish connection
                doingContextual((spec) -> {
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
                        "Detected ENTRY_CREATE event for block-nodes.json",
                        String.format(
                                "/localhost:%s/ACTIVE] Connection state transitioned from PENDING to ACTIVE.",
                                portNumbers.getFirst()))),
                doingContextual((spec) -> timeRef.set(Instant.now())),
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                // Update block-nodes.json to have an invalid entry
                doingContextual((spec) -> {
                    List<com.hedera.node.internal.network.BlockNodeConfig> blockNodes = new ArrayList<>();
                    blockNodes.add(new com.hedera.node.internal.network.BlockNodeConfig("26dsfg2364", 1234, 0));
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
                // Verify config was reloaded but connection fails with invalid address
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(45),
                        "Detected ENTRY_MODIFY event for block-nodes.json",
                        String.format(
                                "/localhost:%s/CLOSED] Connection state transitioned from CLOSING to CLOSED",
                                portNumbers.getFirst()),
                        // New invalid config is loaded
                        // Connection client created but exception occurs with invalid address
                        "Created BlockStreamPublishServiceClient for 26dsfg2364:1234")),
                doingContextual((spec) -> timeRef.set(Instant.now())),
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
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
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(45),
                        "Detected ENTRY_DELETE event for block-nodes.json",
                        "Stopping block node connections",
                        // Config file is missing
                        "Block node configuration file does not exist:",
                        "No valid block node configurations available after file change. Connections remain stopped.")),
                doingContextual((spec) -> timeRef.set(Instant.now())),
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                // Unparsable block-nodes.json
                doingContextual((spec) -> {
                    try {
                        Path configPath = spec.getNetworkNodes()
                                .getFirst()
                                .getExternalPath(DATA_CONFIG_DIR)
                                .resolve("block-nodes.json");
                        Files.writeString(configPath, "{ this is not valid json");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }),
                // Verify parse error is handled gracefully
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(45),
                        "Detected ENTRY_CREATE event for block-nodes.json",
                        "Block node configuration unchanged. No action taken")),
                doingContextual((spec) -> timeRef.set(Instant.now())),
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                // Create valid block-nodes.json again
                doingContextual((spec) -> {
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
                // Verify recovery with valid config and connection re-established
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(45),
                        // File watcher detects new valid config (MODIFY because file was already created with invalid
                        // JSON)
                        "Detected ENTRY_MODIFY event for block-nodes.json",
                        // Valid config is loaded
                        "Found available node in priority group 0",
                        // Connection is re-established
                        String.format(
                                "Created BlockStreamPublishServiceClient for localhost:%s", portNumbers.getFirst()),
                        String.format(
                                "/localhost:%s/UNINITIALIZED] Scheduling reconnection for node in 0 ms",
                                portNumbers.getFirst()),
                        String.format("/localhost:%s/UNINITIALIZED] Running connection task", portNumbers.getFirst()),
                        String.format(
                                "/localhost:%s/UNINITIALIZED] Request pipeline initialized", portNumbers.getFirst()),
                        String.format(
                                "/localhost:%s/PENDING] Connection state transitioned from UNINITIALIZED to PENDING.",
                                portNumbers.getFirst()),
                        String.format(
                                "/localhost:%s/ACTIVE] Connection state transitioned from PENDING to ACTIVE.",
                                portNumbers.getFirst()),
                        String.format(
                                "Active block node connection updated to: localhost:%s", portNumbers.getFirst()))),
                waitUntilNextBlocks(10).withBackgroundTraffic(true),
                assertHgcaaLogDoesNotContain(byNodeId(0), "ERROR", Duration.ofSeconds(5)));
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
            blockNodeConfigs = {
                @BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.REAL),
                @BlockNodeConfig(nodeId = 1, mode = BlockNodeMode.REAL),
                @BlockNodeConfig(nodeId = 2, mode = BlockNodeMode.REAL),
                @BlockNodeConfig(nodeId = 3, mode = BlockNodeMode.REAL),
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
    @Order(2)
    final Stream<DynamicTest> allNodesStreamingHappyPath() {
        return validateHappyPath(10);
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
    @Order(3)
    final Stream<DynamicTest> node0StreamingBlockNodeConnectionDropsCanStreamGenesisBlock() {
        final AtomicReference<Instant> time = new AtomicReference<>();
        final List<Integer> portNumbers = new ArrayList<>();
        return hapiTest(
                doingContextual(spec -> portNumbers.add(spec.getBlockNodePortById(0))),
                doingContextual(
                        spec -> LockSupport.parkNanos(Duration.ofSeconds(10).toNanos())),
                doingContextual(spec -> time.set(Instant.now())),
                blockNode(0).sendEndOfStreamImmediately(Code.BEHIND).withBlockNumber(Long.MAX_VALUE),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        time::get,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(45),
                        String.format(
                                "/localhost:%s/ACTIVE] Block node reported it is behind. Will restart stream at block 0.",
                                portNumbers.getFirst()),
                        String.format(
                                "/localhost:%s/ACTIVE] Received EndOfStream response (block=9223372036854775807, responseCode=BEHIND).",
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
    @Order(4)
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
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        connectionDropTime::get,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(45),
                        String.format("Selected block node localhost:%s for connection attempt", portNumbers.get(1)),
                        String.format(
                                "/localhost:%s/PENDING] Connection state transitioned from UNINITIALIZED to PENDING.",
                                portNumbers.get(1)),
                        String.format(
                                "/localhost:%s/ACTIVE] Connection state transitioned from PENDING to ACTIVE.",
                                portNumbers.get(1)))),
                waitUntilNextBlocks(10).withBackgroundTraffic(true),
                doingContextual(spec -> connectionDropTime.set(Instant.now())),
                blockNode(1).shutDownImmediately(), // Pri 1
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        connectionDropTime::get,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(45),
                        String.format(
                                "/localhost:%s/PENDING] Connection state transitioned from UNINITIALIZED to PENDING.",
                                portNumbers.get(2)),
                        String.format(
                                "/localhost:%s/ACTIVE] Connection state transitioned from PENDING to ACTIVE.",
                                portNumbers.get(2)))),
                waitUntilNextBlocks(10).withBackgroundTraffic(true),
                doingContextual(spec -> connectionDropTime.set(Instant.now())),
                blockNode(2).shutDownImmediately(), // Pri 2
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        connectionDropTime::get,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(45),
                        String.format(
                                "/localhost:%s/PENDING] Connection state transitioned from UNINITIALIZED to PENDING.",
                                portNumbers.get(3)),
                        String.format(
                                "/localhost:%s/ACTIVE] Connection state transitioned from PENDING to ACTIVE.",
                                portNumbers.get(3)))),
                waitUntilNextBlocks(10).withBackgroundTraffic(true),
                doingContextual(spec -> connectionDropTime.set(Instant.now())),
                blockNode(1).startImmediately(),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        connectionDropTime::get,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(45),
                        String.format(
                                "/localhost:%s/PENDING] Connection state transitioned from UNINITIALIZED to PENDING.",
                                portNumbers.get(1)),
                        String.format(
                                "/localhost:%s/ACTIVE] Connection state transitioned from PENDING to ACTIVE.",
                                portNumbers.get(1)),
                        String.format("/localhost:%s/CLOSING] Closing connection.", portNumbers.get(3)),
                        String.format(
                                "/localhost:%s/CLOSING] Connection state transitioned from ACTIVE to CLOSING.",
                                portNumbers.get(3)),
                        String.format(
                                "/localhost:%s/CLOSED] Connection state transitioned from CLOSING to CLOSED.",
                                portNumbers.get(3)))),
                doingContextual(
                        spec -> LockSupport.parkNanos(Duration.ofSeconds(20).toNanos())));
    }

    @HapiTest
    @HapiBlockNode(
            networkSize = 2,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.REAL)},
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
    @Order(5)
    final Stream<DynamicTest> twoNodesStreamingOneBlockNodeHappyPath() {
        return validateHappyPath(10);
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
    @Order(6)
    final Stream<DynamicTest> testProactiveBlockBufferAction() {
        final AtomicReference<Instant> timeRef = new AtomicReference<>();
        return hapiTest(
                doingContextual(
                        spec -> LockSupport.parkNanos(Duration.ofSeconds(5).toNanos())),
                doingContextual(spec -> timeRef.set(Instant.now())),
                blockNode(0).updateSendingBlockAcknowledgements(false),
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
    @Order(7)
    final Stream<DynamicTest> testBlockBufferBackPressure() {
        final AtomicReference<Instant> timeRef = new AtomicReference<>();

        return hapiTest(
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                doingContextual(spec -> timeRef.set(Instant.now())),
                blockNode(0).shutDownImmediately(),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofMinutes(6),
                        Duration.ofMinutes(6),
                        "Block buffer is saturated; backpressure is being enabled",
                        "!!! Block buffer is saturated; blocking thread until buffer is no longer saturated")),
                waitForAny(byNodeId(0), Duration.ofSeconds(30), PlatformStatus.CHECKING),
                blockNode(0).startImmediately(),
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
                @BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.REAL),
                @BlockNodeConfig(nodeId = 1, mode = BlockNodeMode.REAL)
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
    @Order(8)
    final Stream<DynamicTest> activeConnectionPeriodicallyRestarts() {
        final AtomicReference<Instant> connectionResetTime = new AtomicReference<>(Instant.now());
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
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(15),
                        String.format(
                                "/localhost:%s/ACTIVE] Scheduled periodic stream reset every PT10S.",
                                portNumbers.getFirst()))),
                waitUntilNextBlocks(6).withBackgroundTraffic(true),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        connectionResetTime::get,
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(15),
                        // Verify that the periodic reset is performed after the period and the connection is closed
                        String.format(
                                "/localhost:%s/ACTIVE] Performing scheduled stream reset.", portNumbers.getFirst()),
                        String.format("/localhost:%s/CLOSING] Closing connection.", portNumbers.getFirst()),
                        String.format(
                                "/localhost:%s/CLOSING] Connection state transitioned from ACTIVE to CLOSING.",
                                portNumbers.getFirst()),
                        String.format("/localhost:%s/CLOSING] Connection successfully closed.", portNumbers.getFirst()),
                        // Select the next block node to connect to based on priorities
                        "Scheduling reconnection for node in 0 ms (force=false).",
                        "Running connection task.",
                        "Connection state transitioned from UNINITIALIZED to PENDING.",
                        "Connection state transitioned from PENDING to ACTIVE.")),
                assertHgcaaLogDoesNotContain(byNodeId(0), "ERROR", Duration.ofSeconds(5)));
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
                            "blockStream.buffer.isBufferPersistenceEnabled", "true",
                            "blockStream.blockPeriod", BLOCK_PERIOD_SECONDS + "s",
                            "blockNode.streamResetPeriod", "20s",
                        })
            })
    @Order(9)
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
                            "blockStream.streamMode",
                            "BOTH",
                            "blockStream.writerMode",
                            "FILE_AND_GRPC",
                            "blockNode.maxEndOfStreamsAllowed",
                            "1"
                        })
            })
    @Order(10)
    final Stream<DynamicTest> node0StreamingMultipleEndOfStreamsReceived() {
        final AtomicReference<Instant> time = new AtomicReference<>();
        final List<Integer> portNumbers = new ArrayList<>();
        return hapiTest(
                doingContextual(spec -> {
                    portNumbers.add(spec.getBlockNodePortById(0));
                    portNumbers.add(spec.getBlockNodePortById(1));
                }),
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                doingContextual(spec -> time.set(Instant.now())),
                blockNode(0).sendEndOfStreamImmediately(Code.TIMEOUT).withBlockNumber(9L),
                blockNode(0).sendEndOfStreamImmediately(Code.TIMEOUT).withBlockNumber(10L),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        time::get,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(45),
                        String.format(
                                "/localhost:%s/ACTIVE] Block node has exceeded the allowed number of EndOfStream responses",
                                portNumbers.getFirst()),
                        String.format("Selected block node localhost:%s for connection attempt", portNumbers.getLast()),
                        String.format(
                                "/localhost:%s/PENDING] Connection state transitioned from UNINITIALIZED to PENDING.",
                                portNumbers.getLast()),
                        String.format(
                                "/localhost:%s/ACTIVE] Connection state transitioned from PENDING to ACTIVE.",
                                portNumbers.getLast()))),
                waitUntilNextBlocks(5).withBackgroundTraffic(true));
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
                            "blockStream.streamMode",
                            "BOTH",
                            "blockStream.writerMode",
                            "FILE_AND_GRPC"
                        })
            })
    @Order(11)
    final Stream<DynamicTest> node0StreamingExponentialBackoff() {
        final AtomicReference<Instant> time = new AtomicReference<>();
        return hapiTest(
                waitUntilNextBlocks(1).withBackgroundTraffic(true),
                doingContextual(spec -> time.set(Instant.now())),
                blockNode(0).sendEndOfStreamImmediately(Code.BEHIND).withBlockNumber(1L),
                waitUntilNextBlocks(1).withBackgroundTraffic(true),
                blockNode(0).sendEndOfStreamImmediately(Code.BEHIND).withBlockNumber(2L),
                waitUntilNextBlocks(1).withBackgroundTraffic(true),
                blockNode(0).sendEndOfStreamImmediately(Code.BEHIND).withBlockNumber(3L),
                waitUntilNextBlocks(1).withBackgroundTraffic(true),
                blockNode(0).sendEndOfStreamImmediately(Code.BEHIND).withBlockNumber(4L),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        time::get,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(45),
                        "(attempt=0)",
                        "(attempt=1)",
                        "(attempt=2)",
                        "(attempt=3)")),
                waitUntilNextBlocks(5).withBackgroundTraffic(true));
    }

    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {
                @BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR, highLatency = true),
                @BlockNodeConfig(nodeId = 1, mode = BlockNodeMode.SIMULATOR, highLatency = true)
            },
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0, 1},
                        blockNodePriorities = {0, 1},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC",
                            "blockNode.highLatencyThreshold", "1s"
                        })
            })
    @Order(12)
    final Stream<DynamicTest> node0StreamingToHighLatencyBlockNode() {
        final AtomicReference<Instant> time = new AtomicReference<>();
        final List<Integer> portNumbers = new ArrayList<>();
        return hapiTest(
                doingContextual(spec -> {
                    portNumbers.add(spec.getBlockNodePortById(0));
                }),
                doingContextual(spec -> time.set(Instant.now())),
                waitUntilNextBlocks(10).withBackgroundTraffic(true),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        time::get,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(45),
                        String.format(
                                "/localhost:%s/ACTIVE] Block node has exceeded high latency threshold 5 times consecutively.",
                                portNumbers.getFirst()),
                        String.format(
                                "/localhost:%s/CLOSED] Closing and rescheduling connection for reconnect attempt",
                                portNumbers.getFirst()),
                        "No available block nodes found for streaming.")));
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
    @Order(13)
    final Stream<DynamicTest> testCNReactionToPublishStreamResponses() {
        final AtomicReference<Instant> time = new AtomicReference<>();
        final List<Integer> portNumbers = new ArrayList<>();
        return hapiTest(
                doingContextual(spec -> portNumbers.add(spec.getBlockNodePortById(0))),
                doingContextual(spec -> time.set(Instant.now())),
                waitUntilNextBlocks(1).withBackgroundTraffic(true),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        time::get,
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(20),
                        String.format(
                                "/localhost:%s/ACTIVE] BlockAcknowledgement received for block",
                                portNumbers.getFirst()))),
                blockNode(0).sendEndOfStreamImmediately(Code.BEHIND).withBlockNumber(Long.MAX_VALUE),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        time::get,
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(20),
                        String.format(
                                "/localhost:%s/ACTIVE] Received EndOfStream response (block=9223372036854775807, responseCode=BEHIND)",
                                portNumbers.getFirst()),
                        String.format(
                                "/localhost:%s/ACTIVE] Block node reported it is behind. Will restart stream at block 0.",
                                portNumbers.getFirst()))),
                waitUntilNextBlocks(1).withBackgroundTraffic(true),
                blockNode(0).sendSkipBlockImmediately(Long.MAX_VALUE),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        time::get,
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(20),
                        String.format(
                                "/localhost:%s/ACTIVE] Received SkipBlock response for block 9223372036854775807, but we are streaming block",
                                portNumbers.getFirst()))),
                blockNode(0).sendResendBlockImmediately(Long.MAX_VALUE),
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        time::get,
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(20),
                        String.format(
                                "/localhost:%s/ACTIVE] Received ResendBlock response for block 9223372036854775807",
                                portNumbers.getFirst()),
                        String.format(
                                "/localhost:%s/ACTIVE] Block node requested a ResendBlock for block 9223372036854775807 but that block does not exist on this consensus node. Closing connection and will retry later",
                                portNumbers.getFirst()))),
                assertHgcaaLogDoesNotContain(byNodeId(0), "ERROR", Duration.ofSeconds(5)));
    }

    @NotNull
    private Stream<DynamicTest> validateHappyPath(int blocksToWait) {
        return hapiTest(
                waitUntilNextBlocks(blocksToWait).withBackgroundTraffic(true),

                // General error assertions
                assertHgcaaLogDoesNotContain(byNodeId(0), "ERROR", Duration.ofSeconds(5)),

                // Block node connection error assertions
                assertHgcaaLogDoesNotContain(byNodeId(0), "Error received", Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0), "Exception caught in block stream worker loop", Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0), "UncheckedIOException caught in block stream worker loop", Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0), "Failed to establish connection to block node", Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0), "Failed to schedule connection task for block node", Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0), "Failed to reschedule connection attempt", Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0),
                        "Closing and rescheduling connection for reconnect attempt",
                        Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0), "No available block nodes found for streaming", Duration.ofSeconds(0)),

                // EndOfStream error assertions
                assertHgcaaLogDoesNotContain(
                        byNodeId(0), "Block node reported an error at block", Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0), "Block node reported an unknown error at block", Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0),
                        "Block node has exceeded the allowed number of EndOfStream responses",
                        Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0),
                        "Block node reported status indicating immediate restart should be attempted",
                        Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(byNodeId(0), "Block node reported it is behind", Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0), "Block node is behind and block state is not available", Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(byNodeId(0), "Received EndOfStream response", Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(byNodeId(0), "Sending EndStream (code=", Duration.ofSeconds(0)),

                // Connection state transition error assertions
                assertHgcaaLogDoesNotContain(byNodeId(0), "Handling failed stream", Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(byNodeId(0), "Failed to transition state from ", Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(byNodeId(0), "Stream completed unexpectedly", Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0), "Error while completing request pipeline", Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0), "onNext invoked but connection is already closed", Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0),
                        "Cannot run connection task, connection manager has shutdown.",
                        Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0), "onComplete invoked but connection is already closed", Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0), "Error occurred while attempting to close connection", Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(byNodeId(0), "Unexpected response received", Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0), "Failed to shutdown current active connection", Duration.ofSeconds(0)),

                // Block buffer saturation and backpressure assertions
                assertHgcaaLogDoesNotContain(
                        byNodeId(0), "Block buffer is saturated; backpressure is being enabled", Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0),
                        "!!! Block buffer is saturated; blocking thread until buffer is no longer saturated",
                        Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0), "Block buffer still not available to accept new blocks", Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0),
                        "Attempting to forcefully switch block node connections due to increasing block buffer saturation",
                        Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0),
                        "Buffer saturation is below or equal to the recovery threshold; back pressure will be disabled.",
                        Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0),
                        "Attempted to disable back pressure, but buffer saturation is not less than or equal to recovery threshold",
                        Duration.ofSeconds(0)),

                // Block processing error assertions
                assertHgcaaLogDoesNotContain(byNodeId(0), " not found in buffer (latestBlock=", Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0), "Received SkipBlock response for block ", Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0), "Received ResendBlock response for block ", Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0),
                        "that block does not exist on this consensus node. Closing connection and will retry later.",
                        Duration.ofSeconds(0)),

                // Configuration and setup error assertions
                assertHgcaaLogDoesNotContain(byNodeId(0), "streaming is not enabled", Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0), "Failed to read block node configuration from", Duration.ofSeconds(0)),
                assertHgcaaLogDoesNotContain(byNodeId(0), "Failed to resolve block node host", Duration.ofSeconds(0)),

                // High latency assertions
                assertHgcaaLogDoesNotContain(
                        byNodeId(0), "Block node has exceeded high latency threshold", Duration.ofSeconds(0)));
    }
}
