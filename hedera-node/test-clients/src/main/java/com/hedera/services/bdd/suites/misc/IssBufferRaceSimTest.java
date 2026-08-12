// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.misc;

import static com.hedera.services.bdd.junit.TestTags.ISS_GRPC;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getVersionInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.BlockNodeVerbs.blockNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeOnly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.untilHgcaaLogContainsText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.verify;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForFrozenNetwork;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.crypto.ParseableIssBlockStreamValidationOp.ISS_NODE_ID;
import static com.hedera.services.bdd.suites.regression.system.LifecycleTest.configVersionOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.HapiBlockNode;
import com.hedera.services.bdd.HapiBlockNode.BlockNodeConfig;
import com.hedera.services.bdd.HapiBlockNode.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import com.hederahashgraph.api.proto.java.SemanticVersion;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.block.api.PublishStreamResponse.EndOfStream.Code;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Investigation (local/throwaway) — the simulator counterpart to {@link IssBufferRaceRealTest}. A SIMULATOR block node
 * is Docker-free and lets us SCRIPT conditions a real BN cannot be driven into (withholding acks = "BN behind", an
 * explicit {@code BAD_BLOCK_PROOF} rejection, and a ResendBlock that makes the CN report itself "behind"). Caveat: the
 * simulator blindly acks by block number and does NOT verify proofs, so acks-on behaves like a real BN that has already
 * persisted the honest copy. Each test records the outcome (the {@code ISS-DIAG} line in node1's hgcaa.log, incl.
 * {@code lag} + which {@code iss/} artifact uploaded). See {@code .context/iss-investigation-test-plan.md}.
 *
 * <p>Every node runs gRPC-only + mock TSS signatures (the simulator does not verify proofs) + buffer persistence off +
 * {@code maxBlocks=200} so an ack-less tail cannot saturate the buffer and stall consensus before detection. The
 * per-node override arrays are inlined because {@code @HapiBlockNode} requires literal annotation values.
 */
@Tag(ISS_GRPC)
class IssBufferRaceSimTest implements LifecycleTest {
    private static final Logger log = LogManager.getLogger(IssBufferRaceSimTest.class);

    private static HttpServer s3Mock;
    private static int s3Port;
    private static final Set<String> RECEIVED_OBJECT_KEYS = ConcurrentHashMap.newKeySet();

    @BeforeAll
    static void startS3Mock() throws IOException {
        s3Mock = IssBufferTestSupport.startS3Mock(RECEIVED_OBJECT_KEYS);
        s3Port = s3Mock.getAddress().getPort();
        log.info("In-JVM S3 mock listening on 127.0.0.1:{}", s3Port);
    }

    @AfterAll
    static void stopS3Mock() {
        if (s3Mock != null) {
            s3Mock.stop(0);
        }
    }

    @BeforeEach
    void resetReceivedKeys() {
        RECEIVED_OBJECT_KEYS.clear();
    }

    // C3 — SELF_ISS, simulator, retain=10, acks flowing (blind-ack by number). Docker-free mirror of C1: the ISS block
    // is acked by number but should still be within the 10-block retention window at detection → captured.
    @HapiTest
    @HapiBlockNode(
            networkSize = 4,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "blockStream.buffer.maxBlocks", "200",
                            "tss.forceMockSignatures", "true"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "blockStream.buffer.maxBlocks", "200",
                            "tss.forceMockSignatures", "true"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 2,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "blockStream.buffer.maxBlocks", "200",
                            "tss.forceMockSignatures", "true"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 3,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "blockStream.buffer.maxBlocks", "200",
                            "tss.forceMockSignatures", "true"
                        })
            })
    final Stream<DynamicTest> selfIssRetain10Sim() {
        final AtomicReference<SemanticVersion> startVersion = new AtomicReference<>();
        return hapiTest(
                getVersionInfo().exposingServicesVersionTo(startVersion::set),
                sleepForSeconds(2),
                sourcing(() -> reconnectIssNode(
                        byNodeId(ISS_NODE_ID),
                        configVersionOf(startVersion.get()),
                        IssBufferTestSupport.configureNode(ISS_NODE_ID, s3Port, true, 10, true))),
                assertHgcaaLogContainsText(
                        byNodeId(ISS_NODE_ID), "ledger.transfers.maxLen = 5", Duration.ofSeconds(10)),
                induceIssTransfer(),
                awaitIssDetectionAndDiag(),
                recordOutcome("C3 SELF/SIM/retain=10/acks-on"),
                freezeSurvivors());
    }

    // C4 — SELF_ISS, simulator, retain=1, acks WITHHELD ("BN behind") from before the transfer. Unacked ⇒ never pruned
    // ⇒ RETAINED even at retain=1 → captured. Shows the invariant the feature relies on (needs the BN to not ack).
    @HapiTest
    @HapiBlockNode(
            networkSize = 4,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "blockStream.buffer.maxBlocks", "200",
                            "tss.forceMockSignatures", "true"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "blockStream.buffer.maxBlocks", "200",
                            "tss.forceMockSignatures", "true"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 2,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "blockStream.buffer.maxBlocks", "200",
                            "tss.forceMockSignatures", "true"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 3,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "blockStream.buffer.maxBlocks", "200",
                            "tss.forceMockSignatures", "true"
                        })
            })
    final Stream<DynamicTest> selfIssBnBehindWithheldAcks() {
        final AtomicReference<SemanticVersion> startVersion = new AtomicReference<>();
        return hapiTest(
                getVersionInfo().exposingServicesVersionTo(startVersion::set),
                sleepForSeconds(2),
                sourcing(() -> reconnectIssNode(
                        byNodeId(ISS_NODE_ID),
                        configVersionOf(startVersion.get()),
                        IssBufferTestSupport.configureNode(ISS_NODE_ID, s3Port, true, 1, true))),
                assertHgcaaLogContainsText(
                        byNodeId(ISS_NODE_ID), "ledger.transfers.maxLen = 5", Duration.ofSeconds(10)),
                // "BN behind": stop acking so the ISS block never becomes acknowledged.
                blockNode(0).updateSendingBlockAcknowledgements(false),
                induceIssTransfer(),
                awaitIssDetectionAndDiag(),
                verify(() -> {
                    IssBufferTestSupport.awaitKey(RECEIVED_OBJECT_KEYS, "/iss/", "", Duration.ofSeconds(90));
                    final boolean kept =
                            IssBufferTestSupport.receivedKeyMatches(RECEIVED_OBJECT_KEYS, "/iss/", ".iss.gz");
                    log.warn(
                            "C4 SELF/SIM/retain=1/BN-behind outcome: kept(.iss.gz)={} keys={}",
                            kept,
                            RECEIVED_OBJECT_KEYS);
                    // Unacked ⇒ never pruned ⇒ must be retained + captured even at retain=1.
                    assertTrue(
                            kept,
                            "unacknowledged ISS block should be retained and captured; saw " + RECEIVED_OBJECT_KEYS);
                }),
                blockNode(0).updateSendingBlockAcknowledgements(true),
                freezeSurvivors());
    }

    // C5 — SELF_ISS, simulator, retain=1, acks on; after detection we inject a BAD_BLOCK_PROOF EndOfStream to observe
    // how
    // the CN reacts (transient close + restart at the next block) — the response a real BN sends for a bad proof. The
    // rejection is not tied to the exact ISS block, so this observes generic BAD_BLOCK_PROOF handling + the retain=1
    // buffer outcome.
    @HapiTest
    @HapiBlockNode(
            networkSize = 4,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "blockStream.buffer.maxBlocks", "200",
                            "tss.forceMockSignatures", "true"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "blockStream.buffer.maxBlocks", "200",
                            "tss.forceMockSignatures", "true"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 2,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "blockStream.buffer.maxBlocks", "200",
                            "tss.forceMockSignatures", "true"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 3,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "blockStream.buffer.maxBlocks", "200",
                            "tss.forceMockSignatures", "true"
                        })
            })
    final Stream<DynamicTest> selfIssBadBlockProofRejection() {
        final AtomicReference<SemanticVersion> startVersion = new AtomicReference<>();
        return hapiTest(
                getVersionInfo().exposingServicesVersionTo(startVersion::set),
                sleepForSeconds(2),
                sourcing(() -> reconnectIssNode(
                        byNodeId(ISS_NODE_ID),
                        configVersionOf(startVersion.get()),
                        IssBufferTestSupport.configureNode(ISS_NODE_ID, s3Port, true, 1, true))),
                assertHgcaaLogContainsText(
                        byNodeId(ISS_NODE_ID), "ledger.transfers.maxLen = 5", Duration.ofSeconds(10)),
                induceIssTransfer(),
                untilHgcaaLogContainsText(
                        byNodeId(ISS_NODE_ID), "ISS detected", Duration.ofSeconds(180), () -> new SpecOperation[0]),
                // Simulate the BN rejecting a block's proof; the CN should transiently close and restart the stream.
                blockNode(0).sendEndOfStreamImmediately(Code.BAD_BLOCK_PROOF),
                untilHgcaaLogContainsText(
                        byNodeId(ISS_NODE_ID), "ISS-DIAG ", Duration.ofSeconds(90), () -> new SpecOperation[0]),
                recordOutcome("C5 SELF/SIM/retain=1/BAD_BLOCK_PROOF"),
                freezeSurvivors());
    }

    // C9 — SELF_ISS, simulator, retain=10; the BN asks node1 to resend an old (already-pruned) block, so node1 reports
    // TOO_FAR_BEHIND — the "CN behind the block node" condition. (At networkSize=4 we cannot take a peer offline during
    // a SELF_ISS without collapsing the 3-of-4 majority, so we use the BN's ResendBlock signal, not a node kill.)
    @HapiTest
    @HapiBlockNode(
            networkSize = 4,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "blockStream.buffer.maxBlocks", "200",
                            "tss.forceMockSignatures", "true"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "blockStream.buffer.maxBlocks", "200",
                            "tss.forceMockSignatures", "true"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 2,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "blockStream.buffer.maxBlocks", "200",
                            "tss.forceMockSignatures", "true"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 3,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "blockStream.buffer.maxBlocks", "200",
                            "tss.forceMockSignatures", "true"
                        })
            })
    final Stream<DynamicTest> selfIssCnBehindResend() {
        final AtomicReference<SemanticVersion> startVersion = new AtomicReference<>();
        return hapiTest(
                getVersionInfo().exposingServicesVersionTo(startVersion::set),
                sleepForSeconds(2),
                sourcing(() -> reconnectIssNode(
                        byNodeId(ISS_NODE_ID),
                        configVersionOf(startVersion.get()),
                        IssBufferTestSupport.configureNode(ISS_NODE_ID, s3Port, true, 10, true))),
                assertHgcaaLogContainsText(
                        byNodeId(ISS_NODE_ID), "ledger.transfers.maxLen = 5", Duration.ofSeconds(10)),
                induceIssTransfer(),
                untilHgcaaLogContainsText(
                        byNodeId(ISS_NODE_ID), "ISS detected", Duration.ofSeconds(180), () -> new SpecOperation[0]),
                // Ask for block 0 (long pruned) → node1 finds it below the earliest buffered block → TOO_FAR_BEHIND.
                blockNode(0).sendResendBlockImmediately(0),
                untilHgcaaLogContainsText(
                        byNodeId(ISS_NODE_ID), "ISS-DIAG ", Duration.ofSeconds(90), () -> new SpecOperation[0]),
                recordOutcome("C9 SELF/SIM/retain=10/CN-behind(resend)"),
                freezeSurvivors());
    }

    // C10 — SELF_ISS, simulator, retain=0, acks on. With retain=0 the acked ISS block is prunable as soon as it is
    // acknowledged (retention threshold = highestAcked + 1), so by the time the async ISS capture reads the buffer the
    // block is already gone → only a .txt pointer is written. This deterministically recreates the "notification is
    // late / ISS block already pruned" loss. node1 also runs a fast buffer worker (workerInterval=100ms) so the prune
    // reliably fires before the async capture snapshot; at the default 1s interval it is a coin-flip the capture often
    // wins.
    @HapiTest
    @HapiBlockNode(
            networkSize = 4,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "blockStream.buffer.maxBlocks", "200",
                            "tss.forceMockSignatures", "true"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "blockStream.buffer.maxBlocks", "200",
                            "blockStream.buffer.workerInterval", "100ms",
                            "tss.forceMockSignatures", "true"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 2,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "blockStream.buffer.maxBlocks", "200",
                            "tss.forceMockSignatures", "true"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 3,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "blockStream.buffer.maxBlocks", "200",
                            "tss.forceMockSignatures", "true"
                        })
            })
    final Stream<DynamicTest> selfIssRetain0Pruned() {
        final AtomicReference<SemanticVersion> startVersion = new AtomicReference<>();
        return hapiTest(
                getVersionInfo().exposingServicesVersionTo(startVersion::set),
                sleepForSeconds(2),
                sourcing(() -> reconnectIssNode(
                        byNodeId(ISS_NODE_ID),
                        configVersionOf(startVersion.get()),
                        IssBufferTestSupport.configureNode(ISS_NODE_ID, s3Port, true, 0, true))),
                assertHgcaaLogContainsText(
                        byNodeId(ISS_NODE_ID), "ledger.transfers.maxLen = 5", Duration.ofSeconds(10)),
                // Warm up the stream so the block node's acks are flowing before the ISS: the ISS block must be
                // acknowledged for retain=0 pruning to drop it (an unacked block is never pruned, so it would be kept).
                sleepForSeconds(8),
                induceIssTransfer(),
                awaitIssDetectionAndDiag(),
                verify(() -> {
                    IssBufferTestSupport.awaitKey(RECEIVED_OBJECT_KEYS, "/iss/", "", Duration.ofSeconds(90));
                    final boolean kept =
                            IssBufferTestSupport.receivedKeyMatches(RECEIVED_OBJECT_KEYS, "/iss/", ".iss.gz");
                    final boolean lost = IssBufferTestSupport.receivedKeyMatches(RECEIVED_OBJECT_KEYS, "/iss/", ".txt");
                    log.warn(
                            "C10 SELF/SIM/retain=0 outcome: blockCaptured(.iss.gz)={} blockLost(.txt)={} keys={}",
                            kept,
                            lost,
                            RECEIVED_OBJECT_KEYS);
                    // retain=0 ⇒ the acked ISS block is pruned before the async capture reads the buffer ⇒ the block
                    // is lost and only a .txt pointer is written. This is the "late notification / already pruned"
                    // case.
                    assertTrue(
                            lost,
                            "with retain=0 the acked ISS block should be pruned before capture → .txt pointer; saw "
                                    + RECEIVED_OBJECT_KEYS);
                }),
                freezeSurvivors());
    }

    // C11 — SELF_ISS, simulator, keep=1 (a normal retention window), but the ISS notification arrives LATE relative to
    // block production. blockPeriod=0 + roundsPerBlock=1 means one block per round, so the several rounds it takes to
    // detect the ISS span several blocks: the ISS block ends up many blocks behind the current one (lag > keep). Once
    // lag > keep the ISS block is below the acked-retention threshold and is pruned before the capture → LOST. This is
    // the doc's real-world trigger (a slow/late notification), tested at a normal keep instead of keep=0 (see C10).
    // node1 also runs a fast buffer worker (workerInterval=100ms) so keep=1 is actually enforced despite the fast
    // one-block-per-round production; otherwise the prune lags the block rate and transiently retains far more than 1.
    @HapiTest
    @HapiBlockNode(
            networkSize = 4,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "blockStream.buffer.maxBlocks", "200",
                            "blockStream.blockPeriod", "0",
                            "blockStream.roundsPerBlock", "1",
                            "tss.forceMockSignatures", "true"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "blockStream.buffer.maxBlocks", "200",
                            "blockStream.blockPeriod", "0",
                            "blockStream.roundsPerBlock", "1",
                            "blockStream.buffer.workerInterval", "100ms",
                            "tss.forceMockSignatures", "true"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 2,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "blockStream.buffer.maxBlocks", "200",
                            "blockStream.blockPeriod", "0",
                            "blockStream.roundsPerBlock", "1",
                            "tss.forceMockSignatures", "true"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 3,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "blockStream.buffer.maxBlocks", "200",
                            "blockStream.blockPeriod", "0",
                            "blockStream.roundsPerBlock", "1",
                            "tss.forceMockSignatures", "true"
                        })
            })
    final Stream<DynamicTest> selfIssLateNotification() {
        final AtomicReference<SemanticVersion> startVersion = new AtomicReference<>();
        return hapiTest(
                getVersionInfo().exposingServicesVersionTo(startVersion::set),
                sleepForSeconds(2),
                sourcing(() -> reconnectIssNode(
                        byNodeId(ISS_NODE_ID),
                        configVersionOf(startVersion.get()),
                        IssBufferTestSupport.configureNode(ISS_NODE_ID, s3Port, true, 1, true))),
                assertHgcaaLogContainsText(
                        byNodeId(ISS_NODE_ID), "ledger.transfers.maxLen = 5", Duration.ofSeconds(10)),
                // Warm up so the block node's acks are flowing before the ISS (an unacked block is never pruned).
                sleepForSeconds(8),
                induceIssTransfer(),
                awaitIssDetectionAndDiag(),
                verify(() -> {
                    IssBufferTestSupport.awaitKey(RECEIVED_OBJECT_KEYS, "/iss/", "", Duration.ofSeconds(90));
                    final boolean kept =
                            IssBufferTestSupport.receivedKeyMatches(RECEIVED_OBJECT_KEYS, "/iss/", ".iss.gz");
                    final boolean lost = IssBufferTestSupport.receivedKeyMatches(RECEIVED_OBJECT_KEYS, "/iss/", ".txt");
                    log.warn(
                            "C11 SELF/SIM/keep=1/late-notification outcome: blockCaptured(.iss.gz)={} blockLost(.txt)={} keys={}",
                            kept,
                            lost,
                            RECEIVED_OBJECT_KEYS);
                    // Late notification: detection lags several one-round blocks, so lag > keep=1 and the ISS block is
                    // pruned before the capture → a .txt pointer (loss) even at a normal retention window.
                    assertTrue(
                            lost,
                            "a late ISS notification (lag > keep) should lose the ISS block → .txt pointer; saw "
                                    + RECEIVED_OBJECT_KEYS);
                }),
                freezeSurvivors());
    }

    // --- shared step builders (kept local to avoid touching existing tests) ---

    /** 1 debit + 6 credits = 7 balance adjustments — above node1's maxLen=5, within the others'. */
    private SpecOperation induceIssTransfer() {
        return cryptoTransfer(movingHbar(6L).distributing(GENESIS, "3", "4", "5", "6", "7", "8"))
                .signedBy(GENESIS);
    }

    private SpecOperation awaitIssDetectionAndDiag() {
        return blockingOrder(
                untilHgcaaLogContainsText(
                        byNodeId(ISS_NODE_ID), "ISS detected", Duration.ofSeconds(180), () -> new SpecOperation[0]),
                untilHgcaaLogContainsText(
                        byNodeId(ISS_NODE_ID), "ISS-DIAG ", Duration.ofSeconds(90), () -> new SpecOperation[0]));
    }

    private SpecOperation recordOutcome(final String label) {
        return verify(() -> {
            IssBufferTestSupport.awaitKey(RECEIVED_OBJECT_KEYS, "/iss/", "", Duration.ofSeconds(90));
            final boolean kept = IssBufferTestSupport.receivedKeyMatches(RECEIVED_OBJECT_KEYS, "/iss/", ".iss.gz");
            final boolean lost = IssBufferTestSupport.receivedKeyMatches(RECEIVED_OBJECT_KEYS, "/iss/", ".txt");
            log.warn(
                    "{} outcome: blockCaptured(.iss.gz)={} blockLost(.txt)={} keys={}",
                    label,
                    kept,
                    lost,
                    RECEIVED_OBJECT_KEYS);
            assertTrue(
                    kept || lost,
                    label + ": expected an iss/ artifact (captured block or pointer); saw " + RECEIVED_OBJECT_KEYS);
        });
    }

    private SpecOperation freezeSurvivors() {
        return blockingOrder(
                freezeOnly().startingIn(2).seconds(),
                waitForFrozenNetwork(FREEZE_TIMEOUT, NodeSelector.exceptNodeIds(ISS_NODE_ID)));
    }
}
