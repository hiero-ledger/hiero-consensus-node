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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogDoesNotContainText;
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
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import com.hederahashgraph.api.proto.java.SemanticVersion;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * gRPC-only counterpart to {@link IssHandlingTest}, exercising the best-effort ISS-block capture in
 * {@code blockStream.writerMode=GRPC} — where nothing is written to disk, so the ISS-round block is sourced from the
 * in-memory {@code BlockBufferService} (not the disk resolver) and reconstructed to disk as a single {@code .iss.gz}.
 *
 * <p>{@link #issBlockCapturedFromBufferInGrpcMode()} proves the primary path: the ISS-round block is retained in the
 * buffer through the detection lag and reconstructed + <b>staged</b> to the node-local {@code issBlockDir} as an
 * {@code .iss.gz} (a separate deployment uploader would ship it — the node does no upload itself). This test keeps the
 * block by withholding acks (with mock signatures closing it); on the real path it is retained for a stronger reason —
 * a self-ISS block's divergent root hash never gathers a threshold block proof, so the block is never closed and, since
 * only closed blocks are pruned, never pruned.
 *
 * <p>Runs on its own fresh gRPC-only network with a block node attached (via {@link HapiBlockNode}); a
 * {@link BlockNodeMode#SIMULATOR} keeps it Docker-free for local runs. Uses the same {@code ledger.transfers.maxLen}
 * ISS induction as {@link IssHandlingTest}.
 */
@Tag(ISS_GRPC)
@OrderedInIsolation
class IssGrpcBufferStagingTest implements LifecycleTest {

    /** The absolute staging dir the ISS node writes captured artifacts into; asserted on disk after the ISS. */
    private final AtomicReference<Path> issBlockDir = new AtomicReference<>();

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
                            // Ample headroom so the unacknowledged tail (acks are withheld below) never reaches the
                            // saturation/backpressure thresholds before the ISS is detected and captured.
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
    final Stream<DynamicTest> issBlockCapturedFromBufferInGrpcMode() {
        final AtomicReference<SemanticVersion> startVersion = new AtomicReference<>();
        return hapiTest(
                getVersionInfo().exposingServicesVersionTo(startVersion::set),
                // Let node1 write its round-1 snapshot boundary and accumulate a few blocks in the buffer.
                sleepForSeconds(2),
                // Reconnect node1 with the aberrant transfer limit + the failure-capture feature staging to disk.
                sourcing(() -> reconnectIssNode(
                        byNodeId(ISS_NODE_ID),
                        configVersionOf(startVersion.get()),
                        IssStagingTestSupport.configureFailureStaging(issBlockDir))),
                assertHgcaaLogContainsText(
                        byNodeId(ISS_NODE_ID), "ledger.transfers.maxLen = 5", Duration.ofSeconds(10)),
                // Reconnect alone must not ISS.
                assertHgcaaLogDoesNotContainText(byNodeId(ISS_NODE_ID), "ISS detected", Duration.ofSeconds(30)),
                // Withhold acks so the ISS-round block stays UNacknowledged and is therefore retained in the buffer
                // through the detection lag (the production invariant: a block node never acks an ISS block).
                blockNode(0).updateSendingBlockAcknowledgements(false),
                // A transfer within the normal limit but above node1's artificial limit → node1 diverges.
                cryptoTransfer(movingHbar(6L).distributing(GENESIS, "3", "4", "5", "6", "7", "8"))
                        .signedBy(GENESIS),
                untilHgcaaLogContainsText(
                        byNodeId(ISS_NODE_ID), "ISS detected", Duration.ofSeconds(180), () -> new SpecOperation[0]),
                untilHgcaaLogContainsText(
                        byNodeId(ISS_NODE_ID),
                        "Block stream fatal shutdown complete",
                        Duration.ofSeconds(60),
                        () -> new SpecOperation[0]),
                // The load-bearing proof: only IssBufferBlockReader logs this, so seeing it means the ISS block was
                // sourced from the in-memory buffer (there are no on-disk blocks in gRPC-only mode).
                untilHgcaaLogContainsText(
                        byNodeId(ISS_NODE_ID),
                        "from buffer block #",
                        Duration.ofSeconds(90),
                        () -> new SpecOperation[0]),
                // With precedingBlocks=0 (default) the reader writes only the ISS-round block, so it logs
                // "wrote 1 block(s)" — a substring check, so it confirms a single-block capture was logged rather than
                // strictly asserting exactly one.
                assertHgcaaLogContainsText(byNodeId(ISS_NODE_ID), "wrote 1 block(s)", Duration.ofSeconds(90)),
                untilHgcaaLogContainsText(
                        byNodeId(ISS_NODE_ID), "Staged ISS round", Duration.ofSeconds(90), () -> new SpecOperation[0]),
                // In gRPC-only mode an .iss.gz on disk can only have been reconstructed from the buffer.
                verify(() -> assertBufferBlockStaged(issBlockDir.get())),
                // Restore acks so the remaining nodes can drain and freeze cleanly.
                blockNode(0).updateSendingBlockAcknowledgements(true),
                freezeOnly().startingIn(2).seconds(),
                waitForFrozenNetwork(FREEZE_TIMEOUT, NodeSelector.exceptNodeIds(ISS_NODE_ID)));
    }

    /** Asserts the reconstructed ISS-round block was staged as an {@code .iss.gz} under {@code detect/} or {@code failure/}. */
    private static void assertBufferBlockStaged(final Path issBlockDir) {
        final List<String> staged = IssStagingTestSupport.stagedRegularFiles(issBlockDir);
        final long issBlocks = staged.stream()
                .filter(p -> (p.contains("/detect/") || p.contains("/failure/")) && p.endsWith(".iss.gz"))
                .count();
        assertTrue(issBlocks >= 1, "expected an ISS .iss.gz staged from the buffer; saw " + staged);
    }
}
