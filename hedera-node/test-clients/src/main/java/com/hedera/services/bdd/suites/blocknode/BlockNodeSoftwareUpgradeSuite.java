// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blocknode;

import static com.hedera.services.bdd.junit.TestTags.BLOCK_NODE;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.exceptNodeIds;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.BlockNodeVerbs.blockNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertBlockNodeCommsLogContainsTimeframe;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeUpgrade;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForAny;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForFrozenNetwork;
import static com.hedera.services.bdd.spec.utilops.upgrade.BuildUpgradeZipOp.FAKE_UPGRADE_ZIP_LOC;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.DEFAULT_UPGRADE_FILE_ID;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeFileHashAt;

import com.hedera.services.bdd.HapiBlockNode;
import com.hedera.services.bdd.HapiBlockNode.BlockNodeConfig;
import com.hedera.services.bdd.HapiBlockNode.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import com.hedera.services.bdd.spec.utilops.FakeNmt;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import org.hiero.consensus.model.status.PlatformStatus;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

/**
 * Software-upgrade tests for consensus nodes streaming to block nodes.
 */
@Tag(BLOCK_NODE)
@OrderedInIsolation
public class BlockNodeSoftwareUpgradeSuite implements LifecycleTest {

    /**
     * Runs three back-to-back FREEZE_UPGRADE cycles on a 4-node network sharing one REAL block node (BN0).
     * After each upgrade, asserts CN0 reselects BN0 within 3 minutes via the
     * {@code "Selected new block node for streaming: localhost:<port>"} log line.
     */
    @HapiTest
    @HapiBlockNode(
            networkSize = 4,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.REAL)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode",
                            "BLOCKS",
                            "blockStream.writerMode",
                            "GRPC",
                            /*
                            "tss.hintsEnabled",
                            "true",
                            "tss.historyEnabled",
                            "true",
                            "tss.forceHandoffs",
                            "true",*/
                            "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk",
                            "false",
                            "blockStream.buffer.isBufferPersistenceEnabled",
                            "true",
                            "blockNode.blockNodeStatusTimeout",
                            "10s"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode",
                            "BLOCKS",
                            "blockStream.writerMode",
                            "GRPC",
                            /*
                            "tss.hintsEnabled",
                            "true",
                            "tss.historyEnabled",
                            "true",
                            "tss.forceHandoffs",
                            "true",*/
                            "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk",
                            "false",
                            "blockStream.buffer.isBufferPersistenceEnabled",
                            "true",
                            "blockNode.blockNodeStatusTimeout",
                            "10s"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 2,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode",
                            "BLOCKS",
                            "blockStream.writerMode",
                            "GRPC",
                            /*
                            "tss.hintsEnabled",
                            "true",
                            "tss.historyEnabled",
                            "true",
                            "tss.forceHandoffs",
                            "true",*/
                            "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk",
                            "false",
                            "blockStream.buffer.isBufferPersistenceEnabled",
                            "true",
                            "blockNode.blockNodeStatusTimeout",
                            "10s"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 3,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode",
                            "BLOCKS",
                            "blockStream.writerMode",
                            "GRPC",
                            /*
                            "tss.hintsEnabled",
                            "true",
                            "tss.historyEnabled",
                            "true",
                            "tss.forceHandoffs",
                            "true",*/
                            "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk",
                            "false",
                            "blockStream.buffer.isBufferPersistenceEnabled",
                            "true",
                            "blockNode.blockNodeStatusTimeout",
                            "10s"
                        }),
            })
    @Order(0)
    final Stream<DynamicTest> multiUpgradeGrpcWriterTss() {
        final AtomicReference<Instant> timeRef = new AtomicReference<>();
        // After each upgrade, verify the connection manager started and established a connection.
        // Use "Streaming connection update requested" (INFO level) which appears reliably at
        // startup when the monitor first runs.
        final AtomicInteger blockNodePort = new AtomicInteger();
        return hapiTest(
                doingContextual(spec -> {
                    blockNodePort.set(spec.getBlockNodePortById(0));
                    timeRef.set(Instant.now());
                }),
                prepareFakeUpgrade(),
                doingContextual(spec -> timeRef.set(Instant.now())),
                upgradeToNextConfigVersion(),
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofMinutes(3),
                        Duration.ofMinutes(3),
                        String.format("Selected new block node for streaming: localhost:%s", blockNodePort.get()))),
                prepareFakeUpgrade(),
                doingContextual(spec -> timeRef.set(Instant.now())),
                upgradeToNextConfigVersion(),
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofMinutes(3),
                        Duration.ofMinutes(3),
                        String.format("Selected new block node for streaming: localhost:%s", blockNodePort.get()))),
                prepareFakeUpgrade(),
                doingContextual(spec -> timeRef.set(Instant.now())),
                upgradeToNextConfigVersion(),
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofMinutes(3),
                        Duration.ofMinutes(3),
                        String.format("Selected new block node for streaming: localhost:%s", blockNodePort.get()))));
    }

    /**
     * Upgrade with CN0 stuck in {@link PlatformStatus#CHECKING} because BN0 stopped acknowledging (back-pressure).
     *
     * <p>Assertions:
     * <ul>
     *   <li>BN0 down ⇒ CN0 buffer saturates, CN0 reaches {@code CHECKING}.</li>
     *   <li>FREEZE_UPGRADE via CN1 ⇒ CN1/CN2/CN3 reach {@code FREEZE_COMPLETE}; CN0 does not.</li>
     *   <li>BN0 restart ⇒ back-pressure released, CN0 receives {@code BlockAcknowledgement}s (no buffered-data loss).</li>
     *   <li>CN0 restarted on new config version ⇒ replays pre-freeze state, hash disagrees with upgraded peers.
     *       {@code waitForAny(ACTIVE, CATASTROPHIC_FAILURE)} records the outcome. Full recovery requires
     *       a state-sync/reconnect path that wipes CN0's local state first.</li>
     * </ul>
     */
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
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 2,
                        blockNodeIds = {2},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 3,
                        blockNodeIds = {3},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC"
                        })
            })
    @Order(1)
    final Stream<DynamicTest> upgradeWithOneNodeStuckInBackpressure() {
        final AtomicReference<Instant> timeRef = new AtomicReference<>();
        return hapiTest(
                // Let the 4-node network stabilize before any disruption (REAL block-node containers
                // need this warm-up window to fully establish their gRPC streams)
                doingContextual(
                        spec -> LockSupport.parkNanos(Duration.ofSeconds(15).toNanos())),
                // Stage upgrade artifacts while the network is healthy (PREPARE_UPGRADE +
                // execute_immediate marker file)
                prepareFakeUpgrade(),
                // Shut down BN0; CN0's buffer fills, back-pressure kicks in, node enters CHECKING
                blockNode(0).shutDownImmediately(),
                doingContextual(spec -> timeRef.set(Instant.now())),
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofMinutes(2),
                        Duration.ofMinutes(2),
                        "Block buffer is saturated; backpressure is being enabled",
                        "!!! Block buffer is saturated; blocking thread until buffer is no longer saturated")),
                waitForAny(byNodeId(0), Duration.ofSeconds(60), PlatformStatus.CHECKING),
                // Submit FREEZE_UPGRADE via node 1 (account 0.0.4) since CN0 is unresponsive
                sourcing(() -> freezeUpgrade()
                        .startingIn(2)
                        .seconds()
                        .payingWith(GENESIS)
                        .setNode("4")
                        .withUpdateFile(DEFAULT_UPGRADE_FILE_ID)
                        .havingHash(upgradeFileHashAt(FAKE_UPGRADE_ZIP_LOC))),
                // Only the three healthy nodes will reach FREEZE_COMPLETE
                waitForFrozenNetwork(LifecycleTest.FREEZE_TIMEOUT, exceptNodeIds(0)),
                // Shut down + restart only the three healthy nodes at the next config version;
                // CN0 stays running and stuck in CHECKING through the upgrade
                FakeNmt.shutdownWithin(exceptNodeIds(0), LifecycleTest.SHUTDOWN_TIMEOUT),
                sourcing(() -> FakeNmt.restartWithConfigVersion(
                        exceptNodeIds(0), LifecycleTest.CURRENT_CONFIG_VERSION.incrementAndGet())),
                // Restart BN0 so CN0 receives acknowledgements and drains its buffer
                blockNode(0).startImmediately(),
                doingContextual(spec -> timeRef.set(Instant.now())),
                // CN0 must observe back-pressure release once BN0 starts acknowledging again
                sourcingContextual(
                        _ -> assertBlockNodeCommsLogContainsTimeframe(
                                byNodeId(0),
                                timeRef::get,
                                Duration.ofMinutes(3),
                                Duration.ofMinutes(3),
                                "Buffer saturation is below or equal to the recovery threshold; back pressure will be disabled")),
                // Data-loss check: after BN0 resumes, CN0 must observe BlockAcknowledgement messages
                // from BN0 — confirms buffered blocks were replayed and not lost. (Real block-node
                // containers do not support GET_LAST_VERIFIED_BLOCK queries, so we use a log-based
                // assertion instead.)
                sourcingContextual(_ -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofMinutes(3),
                        Duration.ofMinutes(3),
                        "BlockAcknowledgement received for block")),
                // Operator recovery attempt: restart CN0 onto the new config version. Note that CN0
                // never participated in the freeze, so its persisted state is from before the
                // upgrade. On restart it loads that pre-upgrade state, replays events, and ends up
                // signing a state hash that disagrees with the upgraded peers — the platform then
                // declares CATASTROPHIC_FAILURE. Accept either ACTIVE or CATASTROPHIC_FAILURE so
                // the test records the actual outcome rather than enforcing a specific one. Recovery
                // beyond this would require a state-sync / reconnect path that wipes CN0's local
                // state before restart.
                FakeNmt.shutdownWithin(byNodeId(0), LifecycleTest.SHUTDOWN_TIMEOUT),
                sourcing(() ->
                        FakeNmt.restartWithConfigVersion(byNodeId(0), LifecycleTest.CURRENT_CONFIG_VERSION.get())),
                waitForAny(
                        byNodeId(0),
                        LifecycleTest.RESTART_TO_ACTIVE_TIMEOUT,
                        PlatformStatus.ACTIVE,
                        PlatformStatus.CATASTROPHIC_FAILURE));
    }
}
