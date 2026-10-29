// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blocknode;

import static com.hedera.services.bdd.junit.TestTags.BLOCK_NODE;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.allNodes;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertBlockNodeCommsLogContainsTimeframe;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.awaitBlockNodeCommsLogContainsText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.untilHgcaaLogContainsText;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;

import com.hedera.services.bdd.HapiBlockNode;
import com.hedera.services.bdd.HapiBlockNode.BlockNodeConfig;
import com.hedera.services.bdd.HapiBlockNode.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import com.hedera.services.bdd.spec.SpecOperation;
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
    /**
     * The ledger id is only established by the genesis WRAPS proof grounding the chain of trust.
     */
    private static final Duration LEDGER_ID_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Logged for each block the block node acknowledges, which it only does once the block's proof verifies.
     */
    private static final String BLOCK_ACK = "BlockAcknowledgement received for block";

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
                            "true",*/
                            "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk",
                            "false",
                            "blockStream.buffer.isBufferPersistenceEnabled",
                            "true",
                            "blockNode.blockNodeStatusTimeout",
                            "10s",
                            "tss.forceMockSignatures",
                            "false"
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
                            "true",*/
                            "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk",
                            "false",
                            "blockStream.buffer.isBufferPersistenceEnabled",
                            "true",
                            "blockNode.blockNodeStatusTimeout",
                            "10s",
                            "tss.forceMockSignatures",
                            "false"
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
                            "true",*/
                            "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk",
                            "false",
                            "blockStream.buffer.isBufferPersistenceEnabled",
                            "true",
                            "blockNode.blockNodeStatusTimeout",
                            "10s",
                            "tss.forceMockSignatures",
                            "false"
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
                            "true",*/
                            "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk",
                            "false",
                            "blockStream.buffer.isBufferPersistenceEnabled",
                            "true",
                            "blockNode.blockNodeStatusTimeout",
                            "10s",
                            "tss.forceMockSignatures",
                            "false"
                        }),
            })
    @Order(0)
    final Stream<DynamicTest> multiUpgradeGrpcWriterTss() {
        final AtomicReference<Instant> timeRef = new AtomicReference<>();
        // After each upgrade, verify the node reconnected to the block node and that the block node
        // verified (acknowledged) the blocks it streamed
        final AtomicInteger blockNodePort = new AtomicInteger();
        return hapiTest(
                doingContextual(spec -> {
                    blockNodePort.set(spec.getBlockNodePortById(0));
                    timeRef.set(Instant.now());
                }),
                // An upgrade adopts the override network's roster, which needs completed TSS state once the
                // hinTS scheme exists; so finish the genesis TSS ceremony before the first upgrade
                untilHgcaaLogContainsText(
                        allNodes(), "Externalizing ledger id", LEDGER_ID_TIMEOUT, Duration.ofSeconds(1), () ->
                                new SpecOperation[] {cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L))}),
                awaitBlockNodeCommsLogContainsText(allNodes(), BLOCK_ACK, Duration.ofMinutes(2)),
                prepareFakeUpgrade(),
                doingContextual(spec -> timeRef.set(Instant.now())),
                upgradeToNextConfigVersion(),
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofMinutes(3),
                        Duration.ofMinutes(3),
                        String.format("Selected new block node for streaming: localhost:%s", blockNodePort.get()),
                        BLOCK_ACK)),
                prepareFakeUpgrade(),
                doingContextual(spec -> timeRef.set(Instant.now())),
                upgradeToNextConfigVersion(),
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofMinutes(3),
                        Duration.ofMinutes(3),
                        String.format("Selected new block node for streaming: localhost:%s", blockNodePort.get()),
                        BLOCK_ACK)),
                prepareFakeUpgrade(),
                doingContextual(spec -> timeRef.set(Instant.now())),
                upgradeToNextConfigVersion(),
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofMinutes(3),
                        Duration.ofMinutes(3),
                        String.format("Selected new block node for streaming: localhost:%s", blockNodePort.get()),
                        BLOCK_ACK)));
    }
}
