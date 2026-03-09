// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blocknode;

import static com.hedera.services.bdd.junit.TestTags.BLOCK_NODE;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.*;

import com.hedera.services.bdd.HapiBlockNode;
import com.hedera.services.bdd.HapiBlockNode.BlockNodeConfig;
import com.hedera.services.bdd.HapiBlockNode.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import java.time.Duration;
import java.time.Instant;
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
                            "tss.hintsEnabled",
                            "true",
                            "tss.historyEnabled",
                            "true",
                            "tss.forceHandoffs",
                            "true",
                            "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk",
                            "false",
                            "blockStream.buffer.isBufferPersistenceEnabled",
                            "true"
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
                            "tss.hintsEnabled",
                            "true",
                            "tss.historyEnabled",
                            "true",
                            "tss.forceHandoffs",
                            "true",
                            "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk",
                            "false",
                            "blockStream.buffer.isBufferPersistenceEnabled",
                            "true"
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
                            "tss.hintsEnabled",
                            "true",
                            "tss.historyEnabled",
                            "true",
                            "tss.forceHandoffs",
                            "true",
                            "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk",
                            "false",
                            "blockStream.buffer.isBufferPersistenceEnabled",
                            "true"
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
                            "tss.hintsEnabled",
                            "true",
                            "tss.historyEnabled",
                            "true",
                            "tss.forceHandoffs",
                            "true",
                            "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk",
                            "false",
                            "blockStream.buffer.isBufferPersistenceEnabled",
                            "true"
                        }),
            })
    @Order(0)
    final Stream<DynamicTest> multiUpgradeGrpcWriterTss() {
        final AtomicReference<Instant> timeRef = new AtomicReference<>();
        return hapiTest(
                doingContextual(spec -> timeRef.set(Instant.now())),
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0), timeRef::get, Duration.ofSeconds(30), Duration.ofSeconds(30), "saturation=0.0%")),
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(),
                doingContextual((spec) -> sleepForSeconds(30)),
                doingContextual(spec -> timeRef.set(Instant.now())),
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0), timeRef::get, Duration.ofMinutes(3), Duration.ofMinutes(3), "saturation=0.0%")),
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(),
                doingContextual((spec) -> sleepForSeconds(30)),
                doingContextual(spec -> timeRef.set(Instant.now())),
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0), timeRef::get, Duration.ofMinutes(3), Duration.ofMinutes(3), "saturation=0.0%")),
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(),
                doingContextual((spec) -> sleepForSeconds(30)),
                doingContextual(spec -> timeRef.set(Instant.now())),
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0), timeRef::get, Duration.ofMinutes(3), Duration.ofMinutes(3), "saturation=0.0%")));
    }
}
