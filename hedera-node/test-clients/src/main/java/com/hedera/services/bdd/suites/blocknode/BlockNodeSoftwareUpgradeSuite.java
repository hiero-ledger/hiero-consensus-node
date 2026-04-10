// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blocknode;

import static com.hedera.services.bdd.junit.TestTags.BLOCK_NODE;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertBlockNodeCommsLogContainsTimeframe;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;

import com.hedera.services.bdd.HapiBlockNode;
import com.hedera.services.bdd.HapiBlockNode.BlockNodeConfig;
import com.hedera.services.bdd.HapiBlockNode.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

/**
 * This suite is for testing consensus node software upgrade scenarios regarding streaming to block nodes
 */
@Tag(BLOCK_NODE)
@OrderedInIsolation
public class BlockNodeSoftwareUpgradeSuite implements LifecycleTest {

    // Reenable TSS feature flags, after BNs are capable of verifying StateProofs
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
                            /*"blockStream.enableStateProofs",
                            "true",
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
                            /*"blockStream.enableStateProofs",
                            "true",
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
                            /*"blockStream.enableStateProofs",
                            "true",
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
                            /*"blockStream.enableStateProofs",
                            "true",
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
}
