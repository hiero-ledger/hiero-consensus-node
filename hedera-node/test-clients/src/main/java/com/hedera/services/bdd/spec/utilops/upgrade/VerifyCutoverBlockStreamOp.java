// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.upgrade;

import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.BLOCK_STREAMS_DIR;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.SingletonUpdateChange;
import com.hedera.hapi.block.stream.output.StateIdentifier;
import com.hedera.hapi.node.base.BlockHashAlgorithm;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.node.app.blocks.impl.BlockImplUtils;
import com.hedera.node.app.blocks.impl.BlockRootTreeHasher;
import com.hedera.node.app.blocks.impl.BlockStreamManagerImpl;
import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.DigestType;

/**
 * Verifies that the cutover correctly transferred record stream state into the block stream.
 * Checks block numbering, trailing hashes, footer hashes, and the full hash chain across
 * all post-cutover blocks.
 */
public class VerifyCutoverBlockStreamOp extends UtilOp {
    private static final Logger log = LogManager.getLogger(VerifyCutoverBlockStreamOp.class);

    private final AtomicReference<String> liveBlockNum;
    private final AtomicReference<BlockInfo> capturedBlockInfo;
    private final AtomicReference<RunningHashes> capturedRunningHashes;

    public VerifyCutoverBlockStreamOp(
            @NonNull final AtomicReference<String> liveBlockNum,
            @NonNull final AtomicReference<BlockInfo> capturedBlockInfo,
            @NonNull final AtomicReference<RunningHashes> capturedRunningHashes) {
        this.liveBlockNum = requireNonNull(liveBlockNum);
        this.capturedBlockInfo = requireNonNull(capturedBlockInfo);
        this.capturedRunningHashes = requireNonNull(capturedRunningHashes);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final var node0 = spec.targetNetworkOrThrow().getRequiredNode(NodeSelector.byNodeId(0));
        final var blockStreamsDir = node0.getExternalPath(BLOCK_STREAMS_DIR);
        final var allBlocks = BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocks(blockStreamsDir);
        assertFalse(allBlocks.isEmpty(), "Expected blocks after cutover but found none");

        final long liveBlock = Long.parseLong(liveBlockNum.get());

        // Find blocks produced after cutover (number > liveBlock)
        final var postCutoverBlocks = allBlocks.stream()
                .filter(b -> b.items().stream()
                        .filter(BlockItem::hasBlockHeader)
                        .findFirst()
                        .map(item -> item.blockHeaderOrThrow().number() > liveBlock)
                        .orElse(false))
                .toList();
        assertFalse(postCutoverBlocks.isEmpty(), "Expected blocks with number > " + liveBlock + " after cutover");

        final var firstPostCutover = postCutoverBlocks.getFirst();
        final long firstBlockNum = firstPostCutover.items().stream()
                .filter(BlockItem::hasBlockHeader)
                .findFirst()
                .orElseThrow()
                .blockHeaderOrThrow()
                .number();
        log.info("First post-cutover block: {}, liveBlockNum: {}", firstBlockNum, liveBlock);

        // Block number must be exactly one greater than BlockInfo.lastBlockNumber
        assertEquals(
                capturedBlockInfo.get().lastBlockNumber() + 1,
                firstBlockNum,
                "First post-cutover block number should be exactly one greater"
                        + " than BlockInfo.lastBlockNumber ("
                        + capturedBlockInfo.get().lastBlockNumber() + 1 + ")");

        // === Verify first block's trailingBlockHashes ===
        final var blockStreamInfo = BlockStreamAccess.computeSingletonValueFromUpdates(
                List.of(firstPostCutover), SingletonUpdateChange::blockStreamInfoValue, BLOCK_STREAM_INFO_STATE_ID);
        assertNotNull(blockStreamInfo, "First post-cutover block should contain BlockStreamInfo state change");
        assertEquals(
                capturedBlockInfo.get().blockHashes(),
                blockStreamInfo.trailingBlockHashes(),
                "BlockStreamInfo.trailingBlockHashes should equal BlockInfo.blockHashes" + " (original record hashes)");

        // The digest in effect after cutover, as declared by the first post-cutover block (the startup
        // properties may not reflect later upgrade overrides); the block-root Merkle tree and the
        // post-cutover running-hash chain both use it.
        final var digestType = firstPostCutover.items().stream()
                                .filter(BlockItem::hasBlockHeader)
                                .findFirst()
                                .orElseThrow()
                                .blockHeaderOrThrow()
                                .hashAlgorithm()
                        == BlockHashAlgorithm.SHA2_256
                ? DigestType.SHA_256
                : DigestType.SHA_384;

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
                final var serialized = BlockItem.PROTOBUF.toBytes(item).toByteArray();
                // The captured seed hashes are the pre-cutover block-stream running hashes (SHA-384).
                // Transaction results in this block are AFTER cutover, so they evolve the chain with
                // the configured digest (SHA-256 when enabled), matching BlockStreamManagerImpl.
                final var hashedLeaf = BlockImplUtils.hashLeaf(CommonUtils.digestOrThrow(digestType), serialized);
                nMinus3 = nMinus2;
                nMinus2 = nMinus1;
                nMinus1 = current;
                final var digest = CommonUtils.digestOrThrow(digestType);
                digest.update(current);
                digest.update(hashedLeaf);
                current = digest.digest();
                resultCount++;
            }
        }
        log.info("Computed running hashes through {} TRANSACTION_RESULT items", resultCount);
        // The expected-window construction below chains all four running-hash slots under the configured digest,
        // which holds only if the first post-cutover block closed with at least four transaction results.
        assertTrue(
                resultCount >= 4,
                "First post-cutover block should contain at least four transaction results, but had " + resultCount);
        Bytes expectedOutputHashes = BlockImplUtils.appendHash(Bytes.wrap(nMinus3), Bytes.EMPTY, 4);
        expectedOutputHashes = BlockImplUtils.appendHash(Bytes.wrap(nMinus2), expectedOutputHashes, 4);
        expectedOutputHashes = BlockImplUtils.appendHash(Bytes.wrap(nMinus1), expectedOutputHashes, 4);
        expectedOutputHashes = BlockImplUtils.appendHash(Bytes.wrap(current), expectedOutputHashes, 4);
        assertEquals(
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
        assertEquals(
                capturedBlockInfo.get().previousWrappedRecordBlockRootHash(),
                firstFooter.previousBlockRootHash(),
                "Footer previousBlockRootHash should match" + " block info's previousWrappedRecordBlockRootHash");

        // === Verify hash chain by computing block root hashes from items ===
        final var prevBlockHashesTree = new IncrementalStreamingHasher(
                CommonUtils.digestOrThrow(digestType),
                capturedBlockInfo.get().wrappedIntermediatePreviousBlockRootHashes().stream()
                        .map(Bytes::toByteArray)
                        .toList(),
                capturedBlockInfo.get().wrappedIntermediateBlockRootsLeafCount());
        prevBlockHashesTree.addNodeByHash(
                capturedBlockInfo.get().previousWrappedRecordBlockRootHash().toByteArray());

        var prevBlockHash = capturedBlockInfo.get().previousWrappedRecordBlockRootHash();

        for (int i = 0; i < postCutoverBlocks.size(); i++) {
            final var block = postCutoverBlocks.get(i);
            final long blockNum = block.items().stream()
                    .filter(BlockItem::hasBlockHeader)
                    .findFirst()
                    .orElseThrow()
                    .blockHeaderOrThrow()
                    .number();

            if (i > 0) {
                final long prevNum = postCutoverBlocks.get(i - 1).items().stream()
                        .filter(BlockItem::hasBlockHeader)
                        .findFirst()
                        .orElseThrow()
                        .blockHeaderOrThrow()
                        .number();
                assertEquals(prevNum + 1, blockNum, "Block numbers should be sequential");
            }

            final var footer = block.items().stream()
                    .filter(BlockItem::hasBlockFooter)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No footer in block #" + blockNum))
                    .blockFooterOrThrow();
            assertEquals(
                    prevBlockHash,
                    footer.previousBlockRootHash(),
                    "Block #" + blockNum + " footer.previousBlockRootHash"
                            + " should match computed hash of previous block");

            final var expectedTreeHash = Bytes.wrap(prevBlockHashesTree.computeRootHash());
            assertEquals(
                    expectedTreeHash,
                    footer.rootHashOfAllBlockHashesTree(),
                    "Block #" + blockNum + " footer.rootHashOfAllBlockHashesTree"
                            + " should match incrementally computed tree hash");

            if (i == 0) {
                assertNotEquals(
                        Bytes.wrap(new byte[Cryptography.DEFAULT_DIGEST_TYPE.digestLength()]),
                        footer.startOfBlockStateRootHash(),
                        "Block #" + blockNum + " footer.startOfBlockStateRootHash" + " should not be the hash of zero");
            }

            final var computedRootHash = computeBlockRootHash(block, prevBlockHash, prevBlockHashesTree, digestType);
            log.info("Block #{}: computed root hash {}", blockNum, computedRootHash.toHex());

            if (i == 0) {
                // The first post-cutover block's parent was the wrapped record block root, not the trailing-window
                // record hash. Reconstructing it via firstPostCutoverParentHash (reading the block's own BlockInfo
                // delta) must therefore yield the block's true root, while the window-only reconstruction must not.
                final var firstBlockInfo = BlockStreamAccess.computeSingletonValueFromUpdates(
                        List.of(firstPostCutover),
                        SingletonUpdateChange::blockInfoValue,
                        StateIdentifier.STATE_ID_BLOCKS.protoOrdinal());
                assertNotNull(firstBlockInfo, "First post-cutover block should contain a BlockInfo state change");

                // Ground truth: block 0's true root is block 1's footer.previousBlockRootHash. Only enforce when a
                // following block exists to supply it.
                if (postCutoverBlocks.size() > 1) {
                    final var trueRoot = postCutoverBlocks.get(1).items().stream()
                            .filter(BlockItem::hasBlockFooter)
                            .findFirst()
                            .orElseThrow()
                            .blockFooterOrThrow()
                            .previousBlockRootHash();
                    final var providedParent =
                            BlockStreamManagerImpl.firstPostCutoverParentHash(firstBlockInfo, blockStreamInfo);
                    final var reconstructWithWrappedParent = BlockStreamManagerImpl.reconstructLastBlockHash(
                            blockStreamInfo, digestType, providedParent);
                    final var reconstructWithWindow =
                            BlockStreamManagerImpl.reconstructLastBlockHash(blockStreamInfo, digestType, null);
                    assertEquals(
                            trueRoot,
                            reconstructWithWrappedParent,
                            "First post-cutover block root reconstructed via firstPostCutoverParentHash should equal the"
                                    + " true root (block 1's footer.previousBlockRootHash)");
                    assertNotEquals(
                            trueRoot,
                            reconstructWithWindow,
                            "Window-only reconstruction (null parent) must NOT match the true root for the first"
                                    + " post-cutover block");
                }
            }

            prevBlockHash = computedRootHash;
            prevBlockHashesTree.addNodeByHash(computedRootHash.toByteArray());
        }

        log.info("Hash chain verified for {} post-cutover blocks", postCutoverBlocks.size());
        verifyRestartReconstructions(postCutoverBlocks, digestType);
        return false;
    }

    /**
     * Checks that a restart right after any post-cutover block would rebuild that block's hash, i.e. the next block's
     * footer {@code previousBlockRootHash}. Each block is reconstructed from its persisted {@code BlockStreamInfo} and
     * the {@code BlockInfo} as it stood at the end of that block, the same inputs {@code init()} reads on restart.
     */
    private static void verifyRestartReconstructions(
            @NonNull final List<Block> postCutoverBlocks, @NonNull final DigestType digestType) {
        BlockInfo blockInfo = null;
        final List<Long> mismatches = new ArrayList<>();
        for (int i = 0; i < postCutoverBlocks.size() - 1; i++) {
            final var block = postCutoverBlocks.get(i);
            final var blockInfoUpdate = BlockStreamAccess.computeSingletonValueFromUpdates(
                    List.of(block),
                    SingletonUpdateChange::blockInfoValue,
                    StateIdentifier.STATE_ID_BLOCKS.protoOrdinal());
            if (blockInfoUpdate != null) {
                blockInfo = blockInfoUpdate;
            }
            final var blockStreamInfo = BlockStreamAccess.computeSingletonValueFromUpdates(
                    List.of(block), SingletonUpdateChange::blockStreamInfoValue, BLOCK_STREAM_INFO_STATE_ID);
            if (blockStreamInfo == null) {
                continue;
            }
            final var reconstructed = BlockStreamManagerImpl.reconstructLastBlockHash(
                    blockStreamInfo,
                    digestType,
                    BlockStreamManagerImpl.firstPostCutoverParentHash(blockInfo, blockStreamInfo));
            final var nextParent = postCutoverBlocks.get(i + 1).items().stream()
                    .filter(BlockItem::hasBlockFooter)
                    .findFirst()
                    .orElseThrow()
                    .blockFooterOrThrow()
                    .previousBlockRootHash();
            if (!nextParent.equals(reconstructed)) {
                mismatches.add(blockStreamInfo.blockNumber());
            }
        }
        assertTrue(
                mismatches.isEmpty(),
                "Reconstructing these post-cutover blocks on restart doesn't match the next block's parent hash: "
                        + mismatches);
    }

    private static Bytes computeBlockRootHash(
            final Block block,
            final Bytes previousBlockHash,
            final IncrementalStreamingHasher prevBlockHashesTree,
            final DigestType digestType) {
        final var inputTreeHasher = new IncrementalStreamingHasher(CommonUtils.digestOrThrow(digestType), List.of(), 0);
        final var outputTreeHasher =
                new IncrementalStreamingHasher(CommonUtils.digestOrThrow(digestType), List.of(), 0);
        final var consensusHeaderHasher =
                new IncrementalStreamingHasher(CommonUtils.digestOrThrow(digestType), List.of(), 0);
        final var stateChangesHasher =
                new IncrementalStreamingHasher(CommonUtils.digestOrThrow(digestType), List.of(), 0);
        final var traceDataHasher = new IncrementalStreamingHasher(CommonUtils.digestOrThrow(digestType), List.of(), 0);

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
                default -> {}
            }
        }
        requireNonNull(blockTimestamp, "Block has no header with timestamp");

        final var footer = block.items().stream()
                .filter(BlockItem::hasBlockFooter)
                .findFirst()
                .orElseThrow()
                .blockFooterOrThrow();

        final var prevBlockRootsHash = Bytes.wrap(prevBlockHashesTree.computeRootHash());
        final var startOfBlockStateHash = footer.startOfBlockStateRootHash();
        final var consensusHeaderHash = Bytes.wrap(consensusHeaderHasher.computeRootHash());
        final var inputsHash = Bytes.wrap(inputTreeHasher.computeRootHash());
        final var outputsHash = Bytes.wrap(outputTreeHasher.computeRootHash());
        final var stateChangesHash = Bytes.wrap(stateChangesHasher.computeRootHash());
        final var traceDataHash = Bytes.wrap(traceDataHasher.computeRootHash());

        // Built by hand, on purpose. This check must not share the block root tree implementation with block
        // production: if it did, any error in that implementation would be reproduced here and the check
        // would pass regardless. Branches 1-8 carry data, branches 9-16 are reserved and empty.
        final var branches12 = BlockImplUtils.hashInternalNode(
                CommonUtils.digestOrThrow(digestType), previousBlockHash, prevBlockRootsHash);
        final var branches34 = BlockImplUtils.hashInternalNode(
                CommonUtils.digestOrThrow(digestType), startOfBlockStateHash, consensusHeaderHash);
        final var branches56 =
                BlockImplUtils.hashInternalNode(CommonUtils.digestOrThrow(digestType), inputsHash, outputsHash);
        final var branches78 =
                BlockImplUtils.hashInternalNode(CommonUtils.digestOrThrow(digestType), stateChangesHash, traceDataHash);
        final var branches1234 =
                BlockImplUtils.hashInternalNode(CommonUtils.digestOrThrow(digestType), branches12, branches34);
        final var branches5678 =
                BlockImplUtils.hashInternalNode(CommonUtils.digestOrThrow(digestType), branches56, branches78);
        final var assignedHalf =
                BlockImplUtils.hashInternalNode(CommonUtils.digestOrThrow(digestType), branches1234, branches5678);

        final var subtreesRoot = BlockImplUtils.hashInternalNode(
                CommonUtils.digestOrThrow(digestType), assignedHalf, emptyReservedHalf(digestType));
        final var timestampLeaf = BlockImplUtils.hashLeaf(
                CommonUtils.digestOrThrow(digestType), Timestamp.PROTOBUF.toBytes(blockTimestamp));
        return BlockImplUtils.hashInternalNode(CommonUtils.digestOrThrow(digestType), timestampLeaf, subtreesRoot);
    }

    private static Bytes emptyReservedHalf(final DigestType digestType) {
        final var empty = BlockRootTreeHasher.emptySubtreeFor(CommonUtils.digestOrThrow(digestType));
        final var pairOfEmpties = BlockImplUtils.hashInternalNode(CommonUtils.digestOrThrow(digestType), empty, empty);
        final var fourEmpties =
                BlockImplUtils.hashInternalNode(CommonUtils.digestOrThrow(digestType), pairOfEmpties, pairOfEmpties);
        return BlockImplUtils.hashInternalNode(CommonUtils.digestOrThrow(digestType), fourEmpties, fourEmpties);
    }

    @Override
    public String toString() {
        return "VerifyCutoverBlockStreamOp";
    }
}
