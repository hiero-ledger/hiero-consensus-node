// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blocknode;

import static com.hedera.services.bdd.junit.TestTags.BLOCK_NODE;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.DATA_CONFIG_DIR;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.allNodes;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.BlockNodeVerbs.blockNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsTimeframe;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogDoesNotContain;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilNextBlocks;
import static com.hedera.services.bdd.suites.regression.system.LifecycleTest.restartAtNextConfigVersion;

import com.hedera.services.bdd.HapiBlockNode;
import com.hedera.services.bdd.HapiBlockNode.BlockNodeConfig;
import com.hedera.services.bdd.HapiBlockNode.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import com.hedera.node.internal.network.BlockNodeConnectionInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

/**
 * Test suite for verifying Block Node streaming functionality after software upgrades.
 * Tests various network topologies with different CN to BN streaming configurations.
 *
 * <p>Each test follows the pattern:
 * 1. Start network with 4 CNs and N BNs (no streaming initially)
 * 2. Run background traffic for ~2 minutes
 * 3. Perform software upgrade with properties overrides to enable streaming
 * 4. Verify streaming works correctly after restart
 */
@Tag(BLOCK_NODE)
@OrderedInIsolation
public class BlockNodeUpgradeTest implements LifecycleTest {

    private static final int BACKGROUND_TRAFFIC_BLOCKS = 20; // ~40 seconds at default block period
    private static final Duration LOG_CHECK_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration LOG_CHECK_WINDOW = Duration.ofMinutes(2);

    /**
     * Test: 4 CN <-> 1 BN (1 Node Streams after Upgrade)
     *
     * <p>Network configuration:
     * - 4 Consensus Nodes
     * - 1 Block Node (REAL)
     * - Initial state: No streaming configured
     * - After upgrade: Only CN0 streams to BN0
     *
     * <p>Assertions:
     * - CN0 successfully connects to BN0
     * - Block acknowledgements are received
     * - No errors in logs
     */
    @LeakyHapiTest
    @HapiBlockNode(
            networkSize = 4,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.REAL)},
            subProcessNodeConfigs = {
                // Only CN0 configured to stream to BN0 (post-upgrade state)
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        })
            })
    @Order(0)
    final Stream<DynamicTest> fourCN_oneBN_singleNodeStreamsAfterUpgrade() {
        final AtomicReference<Instant> upgradeTime = new AtomicReference<>();

        return hapiTest(
                // Phase 1: Run background traffic before upgrade
                waitUntilNextBlocks(BACKGROUND_TRAFFIC_BLOCKS).withBackgroundTraffic(true),
                // Phase 2: Prepare and perform upgrade
                prepareFakeUpgrade(),
                doingContextual(spec -> upgradeTime.set(Instant.now())),
                // Restart with streaming enabled for CN0
                restartAtNextConfigVersion(),
                // Phase 3: Verify streaming after upgrade
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                // Verify CN0 is streaming successfully
                assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        upgradeTime::get,
                        LOG_CHECK_WINDOW,
                        LOG_CHECK_TIMEOUT,
                        "Connection state transitioned from UNINITIALIZED to PENDING",
                        "Connection state transitioned from PENDING to ACTIVE",
                        "BlockAcknowledgement received for block"),
                // Verify no errors on streaming node
                assertHgcaaLogDoesNotContain(byNodeId(0), "ERROR", Duration.ofSeconds(5)),
                // Verify other nodes are NOT streaming (no block-nodes.json)
                assertHgcaaLogDoesNotContain(byNodeId(1), "BlockAcknowledgement received", Duration.ofSeconds(5)),
                assertHgcaaLogDoesNotContain(byNodeId(2), "BlockAcknowledgement received", Duration.ofSeconds(5)),
                assertHgcaaLogDoesNotContain(byNodeId(3), "BlockAcknowledgement received", Duration.ofSeconds(5)));
    }

    /**
     * Test: 4 CN <-> 4 BN (Each Node Streams after Upgrade)
     *
     * <p>Network configuration:
     * - 4 Consensus Nodes
     * - 4 Block Nodes (REAL)
     * - Initial state: No streaming configured
     * - After upgrade: Each CN streams to its corresponding BN (CN0→BN0, CN1→BN1, etc.)
     *
     * <p>Assertions:
     * - All CNs successfully connect to their respective BNs
     * - All nodes receive block acknowledgements
     * - No errors across the network
     */
    @LeakyHapiTest
    @HapiBlockNode(
            networkSize = 4,
            blockNodeConfigs = {
                @BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.REAL),
                @BlockNodeConfig(nodeId = 1, mode = BlockNodeMode.REAL),
                @BlockNodeConfig(nodeId = 2, mode = BlockNodeMode.REAL),
                @BlockNodeConfig(nodeId = 3, mode = BlockNodeMode.REAL)
            },
            subProcessNodeConfigs = {
                // Each CN configured to stream to its corresponding BN (post-upgrade state)
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
                        })
            })
    @Order(1)
    final Stream<DynamicTest> fourCN_fourBN_allNodesStreamAfterUpgrade() {
        final AtomicReference<Instant> upgradeTime = new AtomicReference<>();

        return hapiTest(
                // Phase 1: Run background traffic before upgrade
                waitUntilNextBlocks(BACKGROUND_TRAFFIC_BLOCKS).withBackgroundTraffic(true),
                // Phase 2: Prepare and perform upgrade
                prepareFakeUpgrade(),
                doingContextual(spec -> upgradeTime.set(Instant.now())),
                // Restart with streaming enabled for all CNs
                restartAtNextConfigVersion(),
                // Phase 3: Verify streaming after upgrade
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                // Verify all nodes are streaming successfully
                assertHgcaaLogContainsTimeframe(
                        allNodes(),
                        upgradeTime::get,
                        LOG_CHECK_WINDOW,
                        LOG_CHECK_TIMEOUT,
                        "Connection state transitioned from PENDING to ACTIVE",
                        "BlockAcknowledgement received for block"),
                // Verify no errors across the network
                assertHgcaaLogDoesNotContain(allNodes(), "ERROR", Duration.ofSeconds(5)));
    }

    /**
     * Test: 4 CN <-> 2 BN (2,2 Pair of CN's stream to separate BN's)
     *
     * <p>Network configuration:
     * - 4 Consensus Nodes
     * - 2 Block Nodes (REAL)
     * - Initial state: No streaming configured
     * - After upgrade: CN0,CN1 → BN0; CN2,CN3 → BN1
     *
     * <p>Assertions:
     * - Paired CNs successfully connect to their assigned BNs
     * - Both BNs receive blocks from their respective CNs
     * - Load is balanced across BNs
     */
    @LeakyHapiTest
    @HapiBlockNode(
            networkSize = 4,
            blockNodeConfigs = {
                @BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.REAL),
                @BlockNodeConfig(nodeId = 1, mode = BlockNodeMode.REAL)
            },
            subProcessNodeConfigs = {
                // CN0,CN1 → BN0; CN2,CN3 → BN1 (post-upgrade state)
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
                        }),
                @SubProcessNodeConfig(
                        nodeId = 2,
                        blockNodeIds = {1},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 3,
                        blockNodeIds = {1},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        })
            })
    @Order(2)
    final Stream<DynamicTest> fourCN_twoBN_pairwiseStreamingAfterUpgrade() {
        final AtomicReference<Instant> upgradeTime = new AtomicReference<>();

        return hapiTest(
                // Phase 1: Run background traffic before upgrade
                waitUntilNextBlocks(BACKGROUND_TRAFFIC_BLOCKS).withBackgroundTraffic(true),
                // Phase 2: Prepare and perform upgrade
                prepareFakeUpgrade(),
                doingContextual(spec -> upgradeTime.set(Instant.now())),
                // Restart with streaming enabled
                restartAtNextConfigVersion(),
                // Phase 3: Verify streaming after upgrade
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                // Verify CN0 and CN1 stream to BN0
                assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        upgradeTime::get,
                        LOG_CHECK_WINDOW,
                        LOG_CHECK_TIMEOUT,
                        "Connection state transitioned from PENDING to ACTIVE",
                        "BlockAcknowledgement received for block"),
                assertHgcaaLogContainsTimeframe(
                        byNodeId(1),
                        upgradeTime::get,
                        LOG_CHECK_WINDOW,
                        LOG_CHECK_TIMEOUT,
                        "Connection state transitioned from PENDING to ACTIVE",
                        "BlockAcknowledgement received for block"),
                // Verify CN2 and CN3 stream to BN1
                assertHgcaaLogContainsTimeframe(
                        byNodeId(2),
                        upgradeTime::get,
                        LOG_CHECK_WINDOW,
                        LOG_CHECK_TIMEOUT,
                        "Connection state transitioned from PENDING to ACTIVE",
                        "BlockAcknowledgement received for block"),
                assertHgcaaLogContainsTimeframe(
                        byNodeId(3),
                        upgradeTime::get,
                        LOG_CHECK_WINDOW,
                        LOG_CHECK_TIMEOUT,
                        "Connection state transitioned from PENDING to ACTIVE",
                        "BlockAcknowledgement received for block"),
                // Verify no errors
                assertHgcaaLogDoesNotContain(allNodes(), "ERROR", Duration.ofSeconds(5)));
    }

    /**
     * Test: 4 CN <-> 3 BN (Asymmetric streaming distribution)
     *
     * <p>Network configuration:
     * - 4 Consensus Nodes
     * - 3 Block Nodes (REAL)
     * - Initial state: No streaming configured
     * - After upgrade: CN0 → BN0; CN1 → BN1; CN2,CN3 → BN2
     *
     * <p>Assertions:
     * - All streaming connections are established
     * - Asymmetric load distribution works correctly
     * - BN2 handles multiple CN connections
     */
    @LeakyHapiTest
    @HapiBlockNode(
            networkSize = 4,
            blockNodeConfigs = {
                @BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.REAL),
                @BlockNodeConfig(nodeId = 1, mode = BlockNodeMode.REAL),
                @BlockNodeConfig(nodeId = 2, mode = BlockNodeMode.REAL)
            },
            subProcessNodeConfigs = {
                // CN0→BN0, CN1→BN1, CN2&CN3→BN2 (post-upgrade state)
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
                        blockNodeIds = {2},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        })
            })
    @Order(3)
    final Stream<DynamicTest> fourCN_threeBN_asymmetricStreamingAfterUpgrade() {
        final AtomicReference<Instant> upgradeTime = new AtomicReference<>();

        return hapiTest(
                // Phase 1: Run background traffic before upgrade
                waitUntilNextBlocks(BACKGROUND_TRAFFIC_BLOCKS).withBackgroundTraffic(true),
                // Phase 2: Prepare and perform upgrade
                prepareFakeUpgrade(),
                doingContextual(spec -> upgradeTime.set(Instant.now())),
                // Restart with streaming enabled
                restartAtNextConfigVersion(),
                // Phase 3: Verify streaming after upgrade
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                // Verify each CN streams to correct BN
                assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        upgradeTime::get,
                        LOG_CHECK_WINDOW,
                        LOG_CHECK_TIMEOUT,
                        "Connection state transitioned from PENDING to ACTIVE",
                        "BlockAcknowledgement received for block"),
                assertHgcaaLogContainsTimeframe(
                        byNodeId(1),
                        upgradeTime::get,
                        LOG_CHECK_WINDOW,
                        LOG_CHECK_TIMEOUT,
                        "Connection state transitioned from PENDING to ACTIVE",
                        "BlockAcknowledgement received for block"),
                // Verify CN2 and CN3 both stream to BN2
                assertHgcaaLogContainsTimeframe(
                        byNodeId(2),
                        upgradeTime::get,
                        LOG_CHECK_WINDOW,
                        LOG_CHECK_TIMEOUT,
                        "Connection state transitioned from PENDING to ACTIVE",
                        "BlockAcknowledgement received for block"),
                assertHgcaaLogContainsTimeframe(
                        byNodeId(3),
                        upgradeTime::get,
                        LOG_CHECK_WINDOW,
                        LOG_CHECK_TIMEOUT,
                        "Connection state transitioned from PENDING to ACTIVE",
                        "BlockAcknowledgement received for block"),
                // Verify no errors
                assertHgcaaLogDoesNotContain(allNodes(), "ERROR", Duration.ofSeconds(5)));
    }

    /**
     * Test: 4 CN <-> 2 BN with Priority Failover after Upgrade
     *
     * <p>Network configuration:
     * - 4 Consensus Nodes
     * - 2 Block Nodes (SIMULATOR for controlled failure)
     * - Initial state: No streaming configured
     * - After upgrade: CN0 streams to BN0 (priority 0) with BN1 (priority 1) as failover
     *
     * <p>Test flow:
     * 1. Verify streaming to primary BN0
     * 2. Shutdown BN0 mid-test
     * 3. Verify automatic failover to BN1
     *
     * <p>Assertions:
     * - Initial connection to BN0
     * - Failover to BN1 after BN0 shutdown
     * - No errors during failover
     */
    @LeakyHapiTest
    @HapiBlockNode(
            networkSize = 4,
            blockNodeConfigs = {
                @BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR),
                @BlockNodeConfig(nodeId = 1, mode = BlockNodeMode.SIMULATOR)
            },
            subProcessNodeConfigs = {
                // CN0 with BN0 (priority 0) and BN1 (priority 1) for failover testing
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0, 1},
                        blockNodePriorities = {0, 1},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        })
            })
    @Order(4)
    final Stream<DynamicTest> fourCN_twoBN_priorityFailoverAfterUpgrade() {
        final AtomicReference<Instant> upgradeTime = new AtomicReference<>();
        final AtomicReference<Instant> failoverTime = new AtomicReference<>();
        final List<Integer> portNumbers = new ArrayList<>();

        return hapiTest(
                // Phase 1: Run background traffic before upgrade
                waitUntilNextBlocks(BACKGROUND_TRAFFIC_BLOCKS).withBackgroundTraffic(true),
                // Phase 2: Prepare and perform upgrade
                prepareFakeUpgrade(),
                doingContextual(spec -> {
                    upgradeTime.set(Instant.now());
                    // Capture port numbers for log verification
                    portNumbers.add(spec.getBlockNodePortById(0));
                    portNumbers.add(spec.getBlockNodePortById(1));
                }),
                // Restart with streaming enabled
                restartAtNextConfigVersion(),
                // Phase 3: Verify initial streaming to BN0
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        upgradeTime::get,
                        LOG_CHECK_WINDOW,
                        LOG_CHECK_TIMEOUT,
                        "Connection state transitioned from PENDING to ACTIVE",
                        "BlockAcknowledgement received for block"),
                // Phase 4: Simulate BN0 failure and verify failover
                doingContextual(spec -> failoverTime.set(Instant.now())),
                blockNode(0).shutDownImmediately(),
                // Verify failover to BN1 happens immediately
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        failoverTime::get,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(45),
                        String.format("/localhost:%s/CLOSING] Closing connection.", portNumbers.get(0)),
                        String.format("Selected block node localhost:%s for connection attempt", portNumbers.get(1)),
                        String.format(
                                "/localhost:%s/PENDING] Connection state transitioned from UNINITIALIZED to PENDING.",
                                portNumbers.get(1)),
                        String.format(
                                "/localhost:%s/ACTIVE] Connection state transitioned from PENDING to ACTIVE.",
                                portNumbers.get(1)),
                        "BlockAcknowledgement received for block")),
                waitUntilNextBlocks(10).withBackgroundTraffic(true),
                // Phase 5: Restart BN0 and verify failback to primary
                doingContextual(spec -> failoverTime.set(Instant.now())),
                blockNode(0).startImmediately(),
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                // Verify connection returns to higher priority BN0
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        failoverTime::get,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(45),
                        String.format(
                                "/localhost:%s/PENDING] Connection state transitioned from UNINITIALIZED to PENDING.",
                                portNumbers.get(0)),
                        String.format(
                                "/localhost:%s/ACTIVE] Connection state transitioned from PENDING to ACTIVE.",
                                portNumbers.get(0)),
                        String.format("/localhost:%s/CLOSING] Closing connection.", portNumbers.get(1)))),
                // Verify no errors throughout test
                assertHgcaaLogDoesNotContain(byNodeId(0), "ERROR", Duration.ofSeconds(5)));
    }

    /**
     * Test: 4 CN upgrade from FILE to FILE_AND_GRPC with dynamic block-nodes.json
     *
     * <p>Network configuration:
     * - 4 Consensus Nodes
     * - 2 Block Nodes (REAL)
     * - Initial state: All CNs use writerMode=FILE (no GRPC streaming)
     * - After upgrade: All CNs upgraded to writerMode=FILE_AND_GRPC
     * - Dynamic config: CN0 and CN1 get block-nodes.json files during test
     *
     * <p>Test phases:
     * 1. Run background traffic before upgrade
     * 2. Perform software upgrade (FILE -> FILE_AND_GRPC)
     * 3. Dynamically create block-nodes.json for CN0 pointing to BN0
     * 4. Verify file detection and connection establishment
     * 5. Dynamically create block-nodes.json for CN1 pointing to BN1
     * 6. Verify file detection and connection establishment
     * 7. Delete block-nodes.json for CN0 and verify graceful shutdown
     * 8. Delete block-nodes.json for CN1 and verify graceful shutdown
     *
     * <p>Assertions:
     * - ENTRY_CREATE events detected for both CNs
     * - Connections established successfully after file creation
     * - ENTRY_DELETE events detected for both CNs
     * - Graceful shutdown after file deletion
     */
    @LeakyHapiTest
    @HapiBlockNode(
            networkSize = 4,
            blockNodeConfigs = {
                @BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.REAL),
                @BlockNodeConfig(nodeId = 1, mode = BlockNodeMode.REAL)
            },
            subProcessNodeConfigs = {
                // CN0 configured to stream to BN0 (post-upgrade state)
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {},
                        blockNodePriorities = {},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        }),
                // CN1 configured to stream to BN1 (post-upgrade state)
                @SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {},
                        blockNodePriorities = {},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC"
                        })
            })
    @Order(5)
    final Stream<DynamicTest> fourCN_dynamicBlockNodeConfig_upgradeFromFileToFileAndGrpc() {
        final AtomicReference<Instant> upgradeTime = new AtomicReference<>();
        final AtomicReference<Instant> cn0ConfigCreateTime = new AtomicReference<>();
        final AtomicReference<Instant> cn1ConfigCreateTime = new AtomicReference<>();
        final AtomicReference<Instant> cn0ConfigDeleteTime = new AtomicReference<>();
        final AtomicReference<Instant> cn1ConfigDeleteTime = new AtomicReference<>();
        final List<Integer> portNumbers = new ArrayList<>();

        return hapiTest(
                // Phase 1: Run background traffic before upgrade
                waitUntilNextBlocks(BACKGROUND_TRAFFIC_BLOCKS).withBackgroundTraffic(true),
                // Phase 2: Prepare and perform upgrade (FILE -> FILE_AND_GRPC)
                prepareFakeUpgrade(),
                doingContextual(spec -> {
                    upgradeTime.set(Instant.now());
                    // Capture block node ports for later use
                    portNumbers.add(spec.getBlockNodePortById(0));
                    portNumbers.add(spec.getBlockNodePortById(1));
                }),
                // Restart with FILE_AND_GRPC enabled (but no block-nodes.json yet)
                restartAtNextConfigVersion(),
                // Phase 3: Wait for network to stabilize
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                // Verify no GRPC streaming yet (since no block-nodes.json)
                assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        upgradeTime::get,
                        Duration.ofMinutes(1),
                        LOG_CHECK_TIMEOUT,
                        "No active connections available for streaming block"),
                assertHgcaaLogContainsTimeframe(
                        byNodeId(1),
                        upgradeTime::get,
                        Duration.ofMinutes(1),
                        LOG_CHECK_TIMEOUT,
                        "No active connections available for streaming block"),
                // Phase 4: Dynamically create block-nodes.json for CN0
                doingContextual(spec -> {
                    cn0ConfigCreateTime.set(Instant.now());
                    final var bn0Port = portNumbers.get(0);
                    List<com.hedera.node.internal.network.BlockNodeConfig> blockNodes = new ArrayList<>();
                    blockNodes.add(new com.hedera.node.internal.network.BlockNodeConfig("localhost", bn0Port, 0));
                    BlockNodeConnectionInfo connectionInfo = new BlockNodeConnectionInfo(blockNodes);
                    try {
                        Path configPath = spec.getNetworkNodes()
                                .get(0)
                                .getExternalPath(DATA_CONFIG_DIR)
                                .resolve("block-nodes.json");
                        Files.writeString(configPath, BlockNodeConnectionInfo.JSON.toJSON(connectionInfo));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }),
                // Verify CN0 config was reloaded and connection established
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        cn0ConfigCreateTime::get,
                        Duration.ofMinutes(1),
                        LOG_CHECK_TIMEOUT,
                        "Detected ENTRY_CREATE event for block-nodes.json",
                        "Stopping block node connections (keeping worker loop running)",
                        String.format(
                                "/localhost:%s/ACTIVE] Connection state transitioned from PENDING to ACTIVE.",
                                portNumbers.get(0)))),
                waitUntilNextBlocks(10).withBackgroundTraffic(true),
                // Phase 5: Dynamically create block-nodes.json for CN1
                doingContextual(spec -> {
                    cn1ConfigCreateTime.set(Instant.now());
                    final var bn1Port = portNumbers.get(1);
                    List<com.hedera.node.internal.network.BlockNodeConfig> blockNodes = new ArrayList<>();
                    blockNodes.add(new com.hedera.node.internal.network.BlockNodeConfig("localhost", bn1Port, 0));
                    BlockNodeConnectionInfo connectionInfo = new BlockNodeConnectionInfo(blockNodes);
                    try {
                        Path configPath = spec.getNetworkNodes()
                                .get(1)
                                .getExternalPath(DATA_CONFIG_DIR)
                                .resolve("block-nodes.json");
                        Files.writeString(configPath, BlockNodeConnectionInfo.JSON.toJSON(connectionInfo));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }),
                // Verify CN1 config was reloaded and connection established
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(1),
                        cn1ConfigCreateTime::get,
                        Duration.ofMinutes(1),
                        LOG_CHECK_TIMEOUT,
                        "Detected ENTRY_CREATE event for block-nodes.json",
                        "Stopping block node connections (keeping worker loop running)",
                        String.format(
                                "/localhost:%s/ACTIVE] Connection state transitioned from PENDING to ACTIVE.",
                                portNumbers.get(1)))),
                waitUntilNextBlocks(10).withBackgroundTraffic(true),
                // Phase 6: Delete block-nodes.json for CN0
                doingContextual(spec -> {
                    cn0ConfigDeleteTime.set(Instant.now());
                    try {
                        Path configPath = spec.getNetworkNodes()
                                .get(0)
                                .getExternalPath(DATA_CONFIG_DIR)
                                .resolve("block-nodes.json");
                        Files.delete(configPath);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }),
                // Verify CN0 file deletion is detected and handled gracefully
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        cn0ConfigDeleteTime::get,
                        Duration.ofSeconds(45),
                        LOG_CHECK_TIMEOUT,
                        "Detected ENTRY_DELETE event for block-nodes.json",
                        "Stopping block node connections (keeping worker loop running)",
                        String.format(
                                "/localhost:%s/CLOSED] Connection state transitioned from CLOSING to CLOSED",
                                portNumbers.get(0)),
                        "Block node configuration file does not exist:",
                        "No valid block node configurations available after file change. Connections remain stopped.")),
                waitUntilNextBlocks(10).withBackgroundTraffic(true),
                // Phase 7: Delete block-nodes.json for CN1
                doingContextual(spec -> {
                    cn1ConfigDeleteTime.set(Instant.now());
                    try {
                        Path configPath = spec.getNetworkNodes()
                                .get(1)
                                .getExternalPath(DATA_CONFIG_DIR)
                                .resolve("block-nodes.json");
                        Files.delete(configPath);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }),
                // Verify CN1 file deletion is detected and handled gracefully
                sourcingContextual(spec -> assertHgcaaLogContainsTimeframe(
                        byNodeId(1),
                        cn1ConfigDeleteTime::get,
                        Duration.ofSeconds(45),
                        LOG_CHECK_TIMEOUT,
                        "Detected ENTRY_DELETE event for block-nodes.json",
                        "Stopping block node connections (keeping worker loop running)",
                        String.format(
                                "/localhost:%s/CLOSED] Connection state transitioned from CLOSING to CLOSED",
                                portNumbers.get(1)),
                        "Block node configuration file does not exist:",
                        "No valid block node configurations available after file change. Connections remain stopped.")),
                // Verify no errors throughout test
                assertHgcaaLogDoesNotContain(byNodeId(0), "ERROR", Duration.ofSeconds(5)),
                assertHgcaaLogDoesNotContain(byNodeId(1), "ERROR", Duration.ofSeconds(5)));
    }
}
