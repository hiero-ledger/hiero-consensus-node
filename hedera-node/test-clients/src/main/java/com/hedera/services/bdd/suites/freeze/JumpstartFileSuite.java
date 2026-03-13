// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.freeze;

import static com.hedera.services.bdd.junit.TestTags.RESTART;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.APPLICATION_LOG;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.BLOCK_STREAMS_DIR;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.RECORD_STREAMS_DIR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsPattern;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogDoesNotContainText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.buildDynamicJumpstartFile;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.getWrappedRecordHashes;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.verifyJumpstartHash;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.verifyLiveWrappedHash;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForActive;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.upgrade.GetWrappedRecordHashesOp.CLASSIC_NODE_IDS;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static java.nio.file.Files.isDirectory;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashes;
import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.SingletonUpdateChange;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.node.app.blocks.impl.BlockImplUtils;
import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.node.app.hapi.utils.exports.recordstreaming.RecordStreamingUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.utilops.FakeNmt;
import com.hedera.services.bdd.spec.utilops.upgrade.RemoveNodeOp;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import com.hedera.services.bdd.suites.regression.system.MixedOperations;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Isolated;

@Tag(RESTART)
@HapiTestLifecycle
@Isolated
@Order(Integer.MAX_VALUE - 2)
class JumpstartFileSuite implements LifecycleTest {

    // For excluding any of the 'non-core' nodes that are expected to be added, reconnected, or removed
    private static final long[] LATER_NODE_IDS = new long[] {4, 5, 6, 7, 8};

    @SuppressWarnings("DuplicatedCode")
    @LeakyHapiTest(
            overrides = {
                "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk",
                "hedera.recordStream.computeHashesFromWrappedRecordBlocks",
                "hedera.recordStream.liveWritePrevWrappedRecordHashes",
                "blockStream.enableCutover",
                "blockStream.streamMode"
            })
    final Stream<DynamicTest> jumpstartsCorrectLiveWrappedRecordBlockHashes() {
        final AtomicReference<List<WrappedRecordFileBlockHashes>> wrappedRecordHashes = new AtomicReference<>();
        final AtomicReference<byte[]> jumpstartFileContents = new AtomicReference<>();
        final AtomicReference<String> nodeComputedHash = new AtomicReference<>();
        final AtomicReference<String> freezeBlockNum = new AtomicReference<>();
        final AtomicReference<String> liveWrappedHash = new AtomicReference<>();
        final AtomicReference<String> liveBlockNum = new AtomicReference<>();
        final AtomicReference<String> lastRecordFile = new AtomicReference<>();
        final AtomicReference<BlockInfo> capturedBlockInfo = new AtomicReference<>();
        final AtomicReference<RunningHashes> capturedRunningHashes = new AtomicReference<>();

        return hapiTest(
                overriding("hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk", "true"),
                // Any nodes added after genesis will not have a complete wrapped hashes file on disk, so shut them down
                logIt("Phase 0: Shut down extra nodes (if any)"),
                doingContextual(spec -> {
                    final var nodesToShutDown = spec.targetNetworkOrThrow().nodes().stream()
                            .filter(node -> !CLASSIC_NODE_IDS.contains(node.getNodeId()))
                            .map(node -> FakeNmt.removeNode(NodeSelector.byNodeId(node.getNodeId())))
                            .toList();
                    if (!nodesToShutDown.isEmpty()) {
                        allRunFor(spec, nodesToShutDown.toArray(new RemoveNodeOp[0]));
                    }
                }),
                logIt("Phase 1: Writing wrapped record hashes to disk"),
                MixedOperations.burstOfTps(5, Duration.ofSeconds(30)),
                logIt("Phase 2: Restarting with jumpstart file"),
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(
                        Map.of(
                                "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk",
                                "true",
                                "hedera.recordStream.computeHashesFromWrappedRecordBlocks",
                                "true",
                                "hedera.recordStream.liveWritePrevWrappedRecordHashes",
                                "true"),
                        buildDynamicJumpstartFile(jumpstartFileContents)),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                logIt("Phase 3: Verify node can process transactions after jumpstart migration"),
                cryptoCreate("shouldWork").payingWith(GENESIS),
                logIt("Phase 4: Verify jumpstart file processed successfully"),
                assertHgcaaLogDoesNotContainText(
                        NodeSelector.exceptNodeIds(LATER_NODE_IDS),
                        "Resuming calculation of wrapped record file hashes until next attempt, but this node will likely experience an ISS",
                        Duration.ofSeconds(30)),
                assertHgcaaLogContainsPattern(
                                NodeSelector.exceptNodeIds(LATER_NODE_IDS),
                                "Completed processing all \\d+ recent wrapped record hashes\\. Final wrapped record block hash \\(as of expected freeze block (\\d+)\\): (\\S+)",
                                Duration.ofSeconds(30))
                        .exposingMatchGroupTo(1, freezeBlockNum)
                        .exposingMatchGroupTo(2, nodeComputedHash),
                // Verify the jumpstart file was archived after successful migration
                doingContextual(spec -> {
                    for (final var node : spec.targetNetworkOrThrow().nodes()) {
                        if (!CLASSIC_NODE_IDS.contains(node.getNodeId())) {
                            continue;
                        }

                        final var workingDir = requireNonNull(node.metadata().workingDir());
                        final var cutoverDir = workingDir.resolve(Path.of("data", "cutover"));
                        final var original = cutoverDir.resolve("jumpstart.bin");
                        org.junit.jupiter.api.Assertions.assertFalse(
                                Files.exists(original),
                                "Jumpstart file should have been archived on node " + node.getNodeId()
                                        + " but still exists at " + original);
                        final var archived = cutoverDir.resolve("archived_jumpstart.bin");
                        assertTrue(
                                Files.exists(archived),
                                "Archived jumpstart file not found on node " + node.getNodeId() + " at " + archived);
                    }
                }),
                // Independently verify the node's computed hash. The wrapped record hashes file
                // may have grown since the migration ran (nodes continue writing after restart),
                // so we pass the freeze block number to bound the replay to the same range the
                // migration processed.
                getWrappedRecordHashes(wrappedRecordHashes),
                sourcing(() -> verifyJumpstartHash(
                        jumpstartFileContents.get(),
                        wrappedRecordHashes.get(),
                        nodeComputedHash.get(),
                        freezeBlockNum.get())),
                logIt("Phase 5: Ops burst with live wrapped record hashes"),
                MixedOperations.burstOfTps(5, Duration.ofSeconds(30)),
                logIt("Phase 6: Second freeze and live hash verification"),
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(
                        Map.of(
                                "hedera.recordStream.computeHashesFromWrappedRecordBlocks",
                                "false",
                                "hedera.recordStream.liveWritePrevWrappedRecordHashes",
                                "true"),
                        assertHgcaaLogContainsPattern(
                                        NodeSelector.exceptNodeIds(LATER_NODE_IDS),
                                        "Persisted live wrapped record block root hash \\(as of block (\\d+)\\): (\\S+)",
                                        Duration.ofSeconds(1))
                                .exposingMatchGroupTo(1, liveBlockNum)
                                .exposingMatchGroupTo(2, liveWrappedHash)),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                sourcing(() -> verifyLiveWrappedHash(liveWrappedHash.get(), liveBlockNum.get())),
                logIt("Phase 7: Ops burst prior to cutover"),
                MixedOperations.burstOfTps(5, Duration.ofSeconds(30)),
                logIt("Phase 8: Execute cutover logic"),
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(
                        Map.of(
                                "hedera.recordStream.computeHashesFromWrappedRecordBlocks",
                                "false",
                                "hedera.recordStream.liveWritePrevWrappedRecordHashes",
                                "false",
                                "blockStream.enableCutover",
                                "true",
                                "blockStream.streamMode",
                                "BLOCKS"),
                        // Pre-restart: capture the final BlockInfo from the last block before cutover
                        withOpContext((spec, opLog) -> {
                            final var node0 = spec.targetNetworkOrThrow().getRequiredNode(NodeSelector.byNodeId(0));
                            final var blockStreamsDir = node0.getExternalPath(BLOCK_STREAMS_DIR);
                            final var allBlocks =
                                    BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocksIgnoringMarkers(blockStreamsDir);
                            final var captured = BlockStreamAccess.computeSingletonValueFromUpdates(
                                    allBlocks, SingletonUpdateChange::blockInfoValue, 19);
                            org.junit.jupiter.api.Assertions.assertNotNull(
                                    captured, "BlockInfo should be present in block state changes");
                            capturedBlockInfo.set(captured);
                            // Also capture RunningHashes (state ID 18) for trailing output hash verification
                            final var capturedRH = BlockStreamAccess.computeSingletonValueFromUpdates(
                                    allBlocks, SingletonUpdateChange::runningHashesValue, 18);
                            org.junit.jupiter.api.Assertions.assertNotNull(
                                    capturedRH, "RunningHashes should be present in block state changes");
                            capturedRunningHashes.set(capturedRH);
                            opLog.info(
                                    "Captured pre-cutover BlockInfo: blockNum={}, blockHashesLen={},"
                                            + " runningHash={}",
                                    captured.lastBlockNumber(),
                                    captured.blockHashes().length(),
                                    capturedRH.runningHash().toHex());

                            // Preserve preview block files before cutover deletes them,
                            // so StateChangesValidator can replay state from genesis
                            for (final var node :
                                    spec.targetNetworkOrThrow().nodesFor(NodeSelector.exceptNodeIds(LATER_NODE_IDS))) {
                                final var srcDir = node.getExternalPath(BLOCK_STREAMS_DIR);
                                final var destDir = node.metadata()
                                        .workingDir()
                                        .resolve("data")
                                        .resolve("cutover")
                                        .resolve("preservedPreviewBlocks");
                                opLog.info("Preserving preview blocks from {} to {}", srcDir, destDir);
                                copyDirectory(srcDir, destDir);
                            }
                        })),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                assertHgcaaLogContainsPattern(
                        NodeSelector.exceptNodeIds(LATER_NODE_IDS),
                        "Block streams cutover is enabled, performing cutover initialization",
                        Duration.ofSeconds(1)),
                // Verify logged BlockInfo fields match what we captured pre-restart
                withOpContext((spec, opLog) -> {
                    final var bi = capturedBlockInfo.get();
                    final var node0 = spec.targetNetworkOrThrow().getRequiredNode(NodeSelector.byNodeId(0));
                    final var log = java.nio.file.Files.readString(node0.getExternalPath(APPLICATION_LOG));

                    // Verify BlockInfo fields
                    assertLogContains(log, "lastBlockNumber", bi.lastBlockNumber());
                    assertLogContains(log, "blockHashesLength", bi.blockHashes().length());
                    assertLogContains(
                            log,
                            "previousWrappedRecordBlockRootHash",
                            bi.previousWrappedRecordBlockRootHash().toHex());
                    assertLogContains(
                            log,
                            "wrappedIntermediateCount",
                            bi.wrappedIntermediatePreviousBlockRootHashes().size());
                    assertLogContains(log, "wrappedIntermediateLeafCount", bi.wrappedIntermediateBlockRootsLeafCount());

                    // Verify block stream info fields derived from BlockInfo
                    assertTrue(log.contains("Cutover initial BlockStreamInfo:"), "Log should contain cutover BSI dump");
                    assertLogContains(log, "blockNumber", bi.lastBlockNumber());
                    // trailingBlockHashes = blockHashes minus last HASH_SIZE (off-by-one)
                    assertLogContains(
                            log, "trailingBlockHashesLength", bi.blockHashes().length() - 48);
                    // trailingOutputHashes must be exactly the final four record stream running hashes
                    assertLogContains(log, "trailingOutputHashesLength", 192);

                    // Verify the logged RunningHashes hex values match what we captured
                    final var rh = capturedRunningHashes.get();
                    assertLogContains(log, "runningHash", rh.runningHash().toHex());
                    assertLogContains(log, "nMinus1", rh.nMinus1RunningHash().toHex());
                    assertLogContains(log, "nMinus2", rh.nMinus2RunningHash().toHex());
                    assertLogContains(log, "nMinus3", rh.nMinus3RunningHash().toHex());
                    assertLogContains(
                            log,
                            "intermediatePreviousBlockRootHashes",
                            bi.wrappedIntermediatePreviousBlockRootHashes());
                    assertLogContains(
                            log, "intermediateBlockRootsLeafCount", bi.wrappedIntermediateBlockRootsLeafCount());

                    opLog.info("All cutover log field verifications passed");
                }),
                // Verify the cutover transferred record stream state into the block stream correctly
                withOpContext((spec, opLog) -> {
                    final var node0 = spec.targetNetworkOrThrow().getRequiredNode(NodeSelector.byNodeId(0));
                    final var blockStreamsDir = node0.getExternalPath(BLOCK_STREAMS_DIR);
                    final var allBlocks = BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocks(blockStreamsDir);
                    org.junit.jupiter.api.Assertions.assertFalse(
                            allBlocks.isEmpty(), "Expected blocks after cutover but found none");

                    final long liveBlock = Long.parseLong(liveBlockNum.get());

                    // Find blocks produced after cutover (number > liveBlock)
                    final var postCutoverBlocks = allBlocks.stream()
                            .filter(b -> b.items().stream()
                                    .filter(BlockItem::hasBlockHeader)
                                    .findFirst()
                                    .map(item -> item.blockHeaderOrThrow().number() > liveBlock)
                                    .orElse(false))
                            .toList();
                    org.junit.jupiter.api.Assertions.assertFalse(
                            postCutoverBlocks.isEmpty(),
                            "Expected blocks with number > " + liveBlock + " after cutover");

                    final var firstPostCutover = postCutoverBlocks.getFirst();
                    final long firstBlockNum = firstPostCutover.items().stream()
                            .filter(BlockItem::hasBlockHeader)
                            .findFirst()
                            .orElseThrow()
                            .blockHeaderOrThrow()
                            .number();
                    opLog.info("First post-cutover block: {}, liveBlockNum: {}", firstBlockNum, liveBlock);

                    // Block number must be exactly one greater than BlockInfo.lastBlockNumber
                    org.junit.jupiter.api.Assertions.assertEquals(
                            capturedBlockInfo.get().lastBlockNumber() + 1,
                            firstBlockNum,
                            "First post-cutover block number should be exactly one greater"
                                    + " than BlockInfo.lastBlockNumber ("
                                    + capturedBlockInfo.get().lastBlockNumber() + 1 + ")");

                    // === Verify first block's trailingBlockHashes ===
                    final var blockStreamInfo = BlockStreamAccess.computeSingletonValueFromUpdates(
                            List.of(firstPostCutover), SingletonUpdateChange::blockStreamInfoValue, 24);
                    org.junit.jupiter.api.Assertions.assertNotNull(
                            blockStreamInfo, "First post-cutover block should contain BlockStreamInfo state change");
                    org.junit.jupiter.api.Assertions.assertEquals(
                            capturedBlockInfo.get().blockHashes(),
                            blockStreamInfo.trailingBlockHashes(),
                            "BlockStreamInfo.trailingBlockHashes should equal BlockInfo.blockHashes"
                                    + " (original record hashes)");

                    // === Verify trailingOutputHashes by evolving captured RunningHashes ===
                    // The BSI from endRound has trailingOutputHashes AFTER processing all
                    // TRANSACTION_RESULT items in this block. So we seed from the captured
                    // RunningHashes and evolve through each TRANSACTION_RESULT to verify
                    // the chain is correct end-to-end.
                    final var runningHashes = capturedRunningHashes.get();
                    byte[] nMinus3 = runningHashes.nMinus3RunningHash().toByteArray();
                    byte[] nMinus2 = runningHashes.nMinus2RunningHash().toByteArray();
                    byte[] nMinus1 = runningHashes.nMinus1RunningHash().toByteArray();
                    byte[] current = runningHashes.runningHash().toByteArray();
                    int resultCount = 0;
                    for (final var item : firstPostCutover.items()) {
                        if (item.hasTransactionResult()) {
                            final var serialized =
                                    BlockItem.PROTOBUF.toBytes(item).toByteArray();
                            final var hashedLeaf = BlockImplUtils.hashLeaf(serialized);
                            // Rotate: shift all four slots left
                            nMinus3 = nMinus2;
                            nMinus2 = nMinus1;
                            nMinus1 = current;
                            // Compute: SHA384(previousHash || hashedLeaf)
                            final var digest = CommonUtils.sha384DigestOrThrow();
                            digest.update(current);
                            digest.update(hashedLeaf);
                            current = digest.digest();
                            resultCount++;
                        }
                    }
                    opLog.info("Computed running hashes through {} TRANSACTION_RESULT items", resultCount);
                    assertTrue(
                            resultCount > 0, "First post-cutover block should contain at least one transaction result");
                    // Build expected trailingOutputHashes from the evolved state
                    Bytes expectedOutputHashes = BlockImplUtils.appendHash(Bytes.wrap(nMinus3), Bytes.EMPTY, 4);
                    expectedOutputHashes = BlockImplUtils.appendHash(Bytes.wrap(nMinus2), expectedOutputHashes, 4);
                    expectedOutputHashes = BlockImplUtils.appendHash(Bytes.wrap(nMinus1), expectedOutputHashes, 4);
                    expectedOutputHashes = BlockImplUtils.appendHash(Bytes.wrap(current), expectedOutputHashes, 4);
                    org.junit.jupiter.api.Assertions.assertEquals(
                            expectedOutputHashes,
                            blockStreamInfo.trailingOutputHashes(),
                            "trailingOutputHashes should match RunningHashes evolved"
                                    + " through first post-cutover block's transaction results");

                    // === Verify first block's footer previousBlockRootHash is the wrapped record block hash ===
                    final var firstFooter = firstPostCutover.items().stream()
                            .filter(BlockItem::hasBlockFooter)
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("No footer in first post-cutover block"))
                            .blockFooterOrThrow();
                    org.junit.jupiter.api.Assertions.assertEquals(
                            capturedBlockInfo.get().previousWrappedRecordBlockRootHash(),
                            firstFooter.previousBlockRootHash(),
                            "Footer previousBlockRootHash should match"
                                    + " block info's previousWrappedRecordBlockRootHash");

                    // === Verify hash chain by computing block root hashes from items ===
                    // Initialize the previous block hashes tree with wrapped record block hashes,
                    // then add real block stream hashes for post-cutover blocks.
                    final var prevBlockHashesTree = new IncrementalStreamingHasher(
                            CommonUtils.sha384DigestOrThrow(),
                            capturedBlockInfo.get().wrappedIntermediatePreviousBlockRootHashes().stream()
                                    .map(Bytes::toByteArray)
                                    .toList(),
                            capturedBlockInfo.get().wrappedIntermediateBlockRootsLeafCount());
                    prevBlockHashesTree.addNodeByHash(capturedBlockInfo
                            .get()
                            .previousWrappedRecordBlockRootHash()
                            .toByteArray());

                    var prevBlockHash = capturedBlockInfo.get().previousWrappedRecordBlockRootHash();

                    for (int i = 0; i < postCutoverBlocks.size(); i++) {
                        final var block = postCutoverBlocks.get(i);
                        final long blockNum = block.items().stream()
                                .filter(BlockItem::hasBlockHeader)
                                .findFirst()
                                .orElseThrow()
                                .blockHeaderOrThrow()
                                .number();

                        // Verify sequential block numbers
                        if (i > 0) {
                            final long prevNum = postCutoverBlocks.get(i - 1).items().stream()
                                    .filter(BlockItem::hasBlockHeader)
                                    .findFirst()
                                    .orElseThrow()
                                    .blockHeaderOrThrow()
                                    .number();
                            org.junit.jupiter.api.Assertions.assertEquals(
                                    prevNum + 1, blockNum, "Block numbers should be sequential");
                        }

                        // Verify footer's previousBlockRootHash matches our computed hash
                        final var footer = block.items().stream()
                                .filter(BlockItem::hasBlockFooter)
                                .findFirst()
                                .orElseThrow(() -> new AssertionError("No footer in block #" + blockNum))
                                .blockFooterOrThrow();
                        org.junit.jupiter.api.Assertions.assertEquals(
                                prevBlockHash,
                                footer.previousBlockRootHash(),
                                "Block #" + blockNum + " footer.previousBlockRootHash"
                                        + " should match computed hash of previous block");

                        // Verify rootHashOfAllBlockHashesTree
                        final var expectedTreeHash = Bytes.wrap(prevBlockHashesTree.computeRootHash());
                        org.junit.jupiter.api.Assertions.assertEquals(
                                expectedTreeHash,
                                footer.rootHashOfAllBlockHashesTree(),
                                "Block #" + blockNum + " footer.rootHashOfAllBlockHashesTree"
                                        + " should match incrementally computed tree hash");

                        // Sanity-check that the first post-cutover block has a real state hash;
                        // the actual value will be verified by StateChangesValidator
                        if (i == 0) {
                            org.junit.jupiter.api.Assertions.assertNotEquals(
                                    Bytes.wrap(new byte[48]),
                                    footer.startOfBlockStateRootHash(),
                                    "Block #" + blockNum + " footer.startOfBlockStateRootHash"
                                            + " should not be the hash of zero");
                        }

                        // Compute this block's root hash from its items
                        final var computedRootHash = computeBlockRootHash(block, prevBlockHash, prevBlockHashesTree);

                        opLog.info("Block #{}: computed root hash {}", blockNum, computedRootHash.toHex());

                        // Update state for next block
                        prevBlockHash = computedRootHash;
                        prevBlockHashesTree.addNodeByHash(computedRootHash.toByteArray());
                    }

                    opLog.info("Hash chain verified for {} post-cutover blocks", postCutoverBlocks.size());

                    // Capture last record file before Phase 8 burst to verify no new records
                    final var recordStreamsDir = node0.getExternalPath(RECORD_STREAMS_DIR);
                    if (isDirectory(recordStreamsDir)) {
                        final var recordFiles = RecordStreamingUtils.orderedRecordFilesFrom(
                                recordStreamsDir.toString(), ignored -> true);
                        if (!recordFiles.isEmpty()) {
                            lastRecordFile.set(recordFiles.getLast());
                        }
                    }
                }),
                logIt("Phase 9: First post-cutover burst"),
                MixedOperations.burstOfTps(5, Duration.ofSeconds(30)),
                logIt("Phase 10: Verify clean cutover with additional blocks"),
                doingContextual(spec -> {
                    final var node0 = spec.targetNetworkOrThrow().getRequiredNode(NodeSelector.byNodeId(0));
                    final var blockStreamsDir = node0.getExternalPath(BLOCK_STREAMS_DIR);
                    final var allBlocks = BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocks(blockStreamsDir);
                    final long liveBlock = Long.parseLong(liveBlockNum.get());

                    final var postCutoverBlocks = allBlocks.stream()
                            .filter(b -> b.items().stream()
                                    .filter(BlockItem::hasBlockHeader)
                                    .findFirst()
                                    .map(item -> item.blockHeaderOrThrow().number() > liveBlock)
                                    .orElse(false))
                            .toList();
                    assertTrue(
                            postCutoverBlocks.size() >= 2,
                            "Expected at least 2 post-cutover blocks after burst, found " + postCutoverBlocks.size());

                    // Verify block numbers are sequential
                    long prevBlockNum = -1;
                    for (final var block : postCutoverBlocks) {
                        final long blockNum = block.items().stream()
                                .filter(BlockItem::hasBlockHeader)
                                .findFirst()
                                .orElseThrow()
                                .blockHeaderOrThrow()
                                .number();
                        if (prevBlockNum != -1) {
                            org.junit.jupiter.api.Assertions.assertEquals(
                                    prevBlockNum + 1, blockNum, "Block numbers should be sequential");
                        }
                        prevBlockNum = blockNum;
                    }

                    // Verify no new record files were produced after cutover
                    if (lastRecordFile.get() != null) {
                        final var recordStreamsDir = node0.getExternalPath(RECORD_STREAMS_DIR);
                        final List<String> recordFiles;
                        try {
                            recordFiles = RecordStreamingUtils.orderedRecordFilesFrom(
                                    recordStreamsDir.toString(), ignored -> true);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        final var newerRecordFiles = recordFiles.stream()
                                .filter(f -> f.compareTo(lastRecordFile.get()) > 0)
                                .toList();
                        assertTrue(
                                newerRecordFiles.isEmpty(),
                                "Expected no new record files after cutover to BLOCKS mode, but found "
                                        + newerRecordFiles.size());
                    }
                }),
                // restart with cutover enabled one more time, to verify it doesn't do anything
                logIt("Phase 11: Restart with cutover enabled to verify idempotent operation"),
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(Map.of(
                        "hedera.recordStream.computeHashesFromWrappedRecordBlocks",
                        "false",
                        "hedera.recordStream.liveWritePrevWrappedRecordHashes",
                        "false",
                        "blockStream.enableCutover",
                        "true",
                        "blockStream.streamMode",
                        "BLOCKS")),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                assertHgcaaLogContainsPattern(
                        NodeSelector.exceptNodeIds(LATER_NODE_IDS),
                        "Block streams cutover already executed, skipping cutover initialization",
                        Duration.ofSeconds(1)),
                // Verify blocks are still produced after idempotent restart
                cryptoCreate("postIdempotentRestart").payingWith(GENESIS));
    }

    /**
     * Computes a block's root hash from its items, following the same merkle tree structure
     * used by {@code BlockStreamManagerImpl.combine()}.
     *
     * @param block the block whose root hash to compute
     * @param previousBlockHash the root hash of the previous block (branch 1)
     * @param prevBlockHashesTree the incremental hasher for all previous block root hashes (branch 2);
     *                            note: {@code computeRootHash()} is called but does not consume the hasher
     * @return the computed block root hash
     */
    private static Bytes computeBlockRootHash(
            final Block block, final Bytes previousBlockHash, final IncrementalStreamingHasher prevBlockHashesTree) {
        final var inputTreeHasher = new IncrementalStreamingHasher(CommonUtils.sha384DigestOrThrow(), List.of(), 0);
        final var outputTreeHasher = new IncrementalStreamingHasher(CommonUtils.sha384DigestOrThrow(), List.of(), 0);
        final var consensusHeaderHasher =
                new IncrementalStreamingHasher(CommonUtils.sha384DigestOrThrow(), List.of(), 0);
        final var stateChangesHasher = new IncrementalStreamingHasher(CommonUtils.sha384DigestOrThrow(), List.of(), 0);
        final var traceDataHasher = new IncrementalStreamingHasher(CommonUtils.sha384DigestOrThrow(), List.of(), 0);

        Timestamp blockTimestamp = null;
        for (final var item : block.items()) {
            if (blockTimestamp == null && item.hasBlockHeader()) {
                blockTimestamp = item.blockHeaderOrThrow().blockTimestamp();
            }
            final var serialized = BlockItem.PROTOBUF.toBytes(item).toByteArray();
            switch (item.item().kind()) {
                case EVENT_HEADER, ROUND_HEADER -> consensusHeaderHasher.addLeaf(serialized);
                case SIGNED_TRANSACTION -> inputTreeHasher.addLeaf(serialized);
                case TRANSACTION_RESULT, TRANSACTION_OUTPUT, BLOCK_HEADER -> outputTreeHasher.addLeaf(serialized);
                case STATE_CHANGES -> stateChangesHasher.addLeaf(serialized);
                case TRACE_DATA -> traceDataHasher.addLeaf(serialized);
                default -> {
                    // BlockFooter, BlockProof, and other items are not part of the merkle subtrees
                }
            }
        }
        requireNonNull(blockTimestamp, "Block has no header with timestamp");

        final var footer = block.items().stream()
                .filter(BlockItem::hasBlockFooter)
                .findFirst()
                .orElseThrow()
                .blockFooterOrThrow();

        // Use footer values for branches 1-3; compute branches 4-8 from items
        final var prevBlockRootsHash = Bytes.wrap(prevBlockHashesTree.computeRootHash());
        final var startOfBlockStateHash = footer.startOfBlockStateRootHash();
        final var consensusHeaderHash = Bytes.wrap(consensusHeaderHasher.computeRootHash());
        final var inputsHash = Bytes.wrap(inputTreeHasher.computeRootHash());
        final var outputsHash = Bytes.wrap(outputTreeHasher.computeRootHash());
        final var stateChangesHash = Bytes.wrap(stateChangesHasher.computeRootHash());
        final var traceDataHash = Bytes.wrap(traceDataHasher.computeRootHash());

        // Depth 5
        final var d5n1 = BlockImplUtils.hashInternalNode(previousBlockHash, prevBlockRootsHash);
        final var d5n2 = BlockImplUtils.hashInternalNode(startOfBlockStateHash, consensusHeaderHash);
        final var d5n3 = BlockImplUtils.hashInternalNode(inputsHash, outputsHash);
        final var d5n4 = BlockImplUtils.hashInternalNode(stateChangesHash, traceDataHash);
        // Depth 4
        final var d4n1 = BlockImplUtils.hashInternalNode(d5n1, d5n2);
        final var d4n2 = BlockImplUtils.hashInternalNode(d5n3, d5n4);
        // Depth 3
        final var d3n1 = BlockImplUtils.hashInternalNode(d4n1, d4n2);
        // Depth 2
        final var tsBytes = Timestamp.PROTOBUF.toBytes(blockTimestamp);
        final var d2n1 = BlockImplUtils.hashLeaf(tsBytes);
        final var d2n2 = BlockImplUtils.hashInternalNodeSingleChild(d3n1);
        // Root
        return BlockImplUtils.hashInternalNode(d2n1, d2n2);
    }

    private static void assertLogContains(final String log, final String key, final Object expected) {
        final var needle = key + "=" + expected;
        assertTrue(log.contains(needle), key + " mismatch (expected=" + expected + ", actual=<not found in log>)");
    }

    private static void copyDirectory(final Path src, final Path dest) throws java.io.IOException {
        try (final var walk = Files.walk(src)) {
            walk.forEach(source -> {
                final var target = dest.resolve(src.relativize(source));
                try {
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target);
                    }
                } catch (final IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }
}
