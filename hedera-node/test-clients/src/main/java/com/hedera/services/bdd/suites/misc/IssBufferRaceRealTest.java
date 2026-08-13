// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.misc;

import static com.hedera.services.bdd.junit.TestTags.BLOCK_NODE;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getVersionInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.BlockNodeVerbs.blockNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogDoesNotContainText;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Investigation (local/throwaway) — 4 CNs stream to ONE REAL dockerized block node in pure gRPC mode; we induce an ISS
 * on node1 and OBSERVE whether the ISS-round block is still in node1's in-memory buffer when the ISS is detected, and
 * whether it has already been acknowledged (hence prunable). We do not assert a fixed "correct" outcome for the SELF
 * cases — each test records the outcome (the {@code ISS-DIAG} line in node1's hgcaa.log, incl. {@code lag}, and which
 * {@code iss/} artifact uploaded: {@code .iss.gz} = block captured, {@code .txt} = block lost → pointer). Requires
 * Docker (real block-node image).
 *
 * <p>Key mechanism: the BN acks by block NUMBER and broadcasts to all publishers, so on a SELF_ISS the honest majority's
 * block N gets acked and node1 marks its own divergent N acked → prunable; survival is a race between the (short)
 * detection lag and {@code ackedBlocksToRetain}. On a CATASTROPHIC_ISS no valid block N forms → never acked → retained.
 * Every node runs {@code maxBlocks=200} so the unacked tail (CATASTROPHIC / BN-down) cannot saturate the buffer and
 * stall consensus before the ISS is detected. See {@code .context/iss-investigation-test-plan.md}.
 *
 * <p>Note: at {@code networkSize=4} a "healthy CN behind during a SELF_ISS" is not cleanly reproducible — taking any of
 * the 4 equal-weight nodes offline drops the agreeing weight below the 3-of-4 majority and turns the round CATASTROPHIC
 * (or stalls it). The "CN behind" condition is therefore exercised in the simulator suite via the BN's ResendBlock
 * signal instead of a node kill (see {@code IssBufferRaceSimTest}).
 */
@Tag(BLOCK_NODE)
class IssBufferRaceRealTest implements LifecycleTest {
    private static final Logger log = LogManager.getLogger(IssBufferRaceRealTest.class);

    /** node2 also diverges in the CATASTROPHIC case (2-2 split → no majority hash). */
    private static final long SECOND_ISS_NODE_ID = 2L;

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

    // C1 — SELF_ISS, real BN, ackedBlocksToRetain=10 (production default). Hypothesis: the block is acked-by-number via
    // the healthy majority but the detection lag is < 10 blocks, so it is STILL buffered → captured (iss/*.iss.gz).
    // Also folds C7: a healthy node emits only OTHER_ISS (non-fatal) — it must NOT log "ISS detected" or capture.
    @HapiTest
    @HapiBlockNode(
            networkSize = 4,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.REAL)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {"blockStream.buffer.maxBlocks", "200"}),
                @SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {"blockStream.buffer.maxBlocks", "200"}),
                @SubProcessNodeConfig(
                        nodeId = 2,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {"blockStream.buffer.maxBlocks", "200"}),
                @SubProcessNodeConfig(
                        nodeId = 3,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {"blockStream.buffer.maxBlocks", "200"})
            })
    final Stream<DynamicTest> selfIssRetain10() {
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
                // C7: a healthy node sees OTHER_ISS, which is non-fatal — it must not log the fatal "ISS detected".
                assertHgcaaLogDoesNotContainText(byNodeId(0), "ISS detected", Duration.ofSeconds(2)),
                recordOutcome("C1 SELF/REAL/retain=10"),
                freezeSurvivors());
    }

    // C2 — SELF_ISS, real BN, ackedBlocksToRetain=1. Hypothesis: acked-by-number then pruned before detection (only 1
    // acked block kept) → block LOST → pointer (iss/*.txt). This is the gap: the ack race loses the self-ISS block.
    @HapiTest
    @HapiBlockNode(
            networkSize = 4,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.REAL)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {"blockStream.buffer.maxBlocks", "200"}),
                @SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {"blockStream.buffer.maxBlocks", "200"}),
                @SubProcessNodeConfig(
                        nodeId = 2,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {"blockStream.buffer.maxBlocks", "200"}),
                @SubProcessNodeConfig(
                        nodeId = 3,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {"blockStream.buffer.maxBlocks", "200"})
            })
    final Stream<DynamicTest> selfIssRetain1() {
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
                awaitIssDetectionAndDiag(),
                recordOutcome("C2 SELF/REAL/retain=1"),
                freezeSurvivors());
    }

    // C6 — CATASTROPHIC_ISS: nodes 1 AND 2 diverge (2-2 split) → no hash reaches the 3-of-4 majority → catastrophic on
    // ALL nodes. Even at retain=1 the block should be RETAINED, because no valid block N forms so the BN never acks it.
    @HapiTest
    @HapiBlockNode(
            networkSize = 4,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.REAL)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {"blockStream.buffer.maxBlocks", "200"}),
                @SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {"blockStream.buffer.maxBlocks", "200"}),
                @SubProcessNodeConfig(
                        nodeId = 2,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {"blockStream.buffer.maxBlocks", "200"}),
                @SubProcessNodeConfig(
                        nodeId = 3,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {"blockStream.buffer.maxBlocks", "200"})
            })
    final Stream<DynamicTest> catastrophicIssRetain1() {
        final AtomicReference<SemanticVersion> startVersion = new AtomicReference<>();
        return hapiTest(
                getVersionInfo().exposingServicesVersionTo(startVersion::set),
                sleepForSeconds(2),
                // node1 diverges + captures (retain=1); node2 also diverges so no hash reaches the 3-of-4 majority.
                sourcing(() -> reconnectIssNode(
                        byNodeId(ISS_NODE_ID),
                        configVersionOf(startVersion.get()),
                        IssBufferTestSupport.configureNode(ISS_NODE_ID, s3Port, true, 1, true))),
                sourcing(() -> reconnectIssNode(
                        byNodeId(SECOND_ISS_NODE_ID),
                        configVersionOf(startVersion.get()),
                        IssBufferTestSupport.configureNode(SECOND_ISS_NODE_ID, s3Port, true, null, false))),
                assertHgcaaLogContainsText(
                        byNodeId(ISS_NODE_ID), "ledger.transfers.maxLen = 5", Duration.ofSeconds(10)),
                induceIssTransfer(),
                awaitIssDetectionAndDiag(),
                verify(() -> {
                    IssBufferTestSupport.awaitKey(RECEIVED_OBJECT_KEYS, "/iss/", "", Duration.ofSeconds(90));
                    final boolean kept =
                            IssBufferTestSupport.receivedKeyMatches(RECEIVED_OBJECT_KEYS, "/iss/", ".iss.gz");
                    log.warn(
                            "C6 CATASTROPHIC/REAL/retain=1 outcome: kept(.iss.gz)={} keys={}",
                            kept,
                            RECEIVED_OBJECT_KEYS);
                    // Mechanism-certain: a catastrophic-ISS block is never acked, so it must survive and be captured.
                    assertTrue(
                            kept,
                            "CATASTROPHIC ISS block should be retained (never acked) and captured; saw "
                                    + RECEIVED_OBJECT_KEYS);
                })
                // No freeze here: a CATASTROPHIC_ISS halts ALL nodes, so there are no survivors to freeze; the harness
                // tears the halted network down. The observation above has already completed.
                );
    }

    // C8 — SELF_ISS, real BN, but the BN is taken DOWN around the ISS window ("BN behind"/unavailable). With no acks
    // the
    // ISS block stays unacknowledged → retained regardless of retain=1. maxBlocks=200 keeps the ack-less tail from
    // saturating the buffer and stalling consensus while the BN is down. The container is restarted before the freeze.
    @HapiTest
    @HapiBlockNode(
            networkSize = 4,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.REAL)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {"blockStream.buffer.maxBlocks", "200"}),
                @SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {"blockStream.buffer.maxBlocks", "200"}),
                @SubProcessNodeConfig(
                        nodeId = 2,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {"blockStream.buffer.maxBlocks", "200"}),
                @SubProcessNodeConfig(
                        nodeId = 3,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {"blockStream.buffer.maxBlocks", "200"})
            })
    final Stream<DynamicTest> selfIssBnDown() {
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
                // Take the only block node down so acks stop network-wide before the ISS block could be acked.
                blockNode(0).shutDownImmediately(),
                induceIssTransfer(),
                awaitIssDetectionAndDiag(),
                blockNode(0).startImmediately(),
                recordOutcome("C8 SELF/REAL/retain=1/BN-down"),
                freezeSurvivors());
    }

    // C12 — REAL BN result: SELF_ISS with keep=0 AND a fast 10ms prune worker STILL KEEPS the block (.iss.gz) — the
    // opposite of the SIM loss (C10). Observed kept on 3/3 runs. Why: on the real BN the ISS block's acknowledgement
    // lands at ~the same instant as detection (both follow from the next block being processed and proof-verified), so
    // there is no window to prune the block before the async capture snapshot reads the buffer. The simulator's
    // instant blind-ack acks the block much earlier, which is what lets C10 prune it in time. Upshot: the common
    // self-ISS block is robustly kept on the REAL path even under the most aggressive retention; a loss there would
    // need lag > 1 (a genuinely late notification, i.e. a bigger/slower network — the blockPeriod=0 trick that gives
    // C11 its lag would swamp real TSS block-proof generation). Observation test: records the outcome, passes on kept.
    @HapiTest
    @HapiBlockNode(
            networkSize = 4,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.REAL)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {"blockStream.buffer.maxBlocks", "200"}),
                @SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.buffer.maxBlocks", "200",
                            "blockStream.buffer.workerInterval", "10ms"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 2,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {"blockStream.buffer.maxBlocks", "200"}),
                @SubProcessNodeConfig(
                        nodeId = 3,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {"blockStream.buffer.maxBlocks", "200"})
            })
    final Stream<DynamicTest> selfIssRealKeepsEvenAtRetain0() {
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
                // Warm up so the real block node's acks are flowing before the ISS (an unacked block is never pruned).
                sleepForSeconds(8),
                induceIssTransfer(),
                awaitIssDetectionAndDiag(),
                recordOutcome("C12 SELF/REAL/keep=0/still-kept"),
                freezeSurvivors());
    }

    // --- shared step builders ---

    /** The divergent transfer: 1 debit + 6 credits = 7 balance adjustments — above node1's maxLen=5, within the others'. */
    private SpecOperation induceIssTransfer() {
        return cryptoTransfer(movingHbar(6L).distributing(GENESIS, "3", "4", "5", "6", "7", "8"))
                .signedBy(GENESIS);
    }

    /** Wait for the fatal ISS on node1 and for the structured ISS-DIAG line (both are certain to appear). */
    private SpecOperation awaitIssDetectionAndDiag() {
        return blockingOrder(
                untilHgcaaLogContainsText(
                        byNodeId(ISS_NODE_ID), "ISS detected", Duration.ofSeconds(180), () -> new SpecOperation[0]),
                untilHgcaaLogContainsText(
                        byNodeId(ISS_NODE_ID), "ISS-DIAG ", Duration.ofSeconds(90), () -> new SpecOperation[0]));
    }

    /**
     * Records the outcome without asserting a fixed result (this is an observation): waits for the {@code iss/} artifact
     * and logs whether the block was captured ({@code .iss.gz}) or lost to a pointer ({@code .txt}). The block numbers
     * and {@code lag} are in the {@code ISS-DIAG} line of node1's hgcaa.log.
     */
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

    /** Freeze the surviving nodes (SELF_ISS halts only node1) so the network shuts down cleanly. */
    private SpecOperation freezeSurvivors() {
        return blockingOrder(
                freezeOnly().startingIn(2).seconds(),
                waitForFrozenNetwork(FREEZE_TIMEOUT, NodeSelector.exceptNodeIds(ISS_NODE_ID)));
    }
}
