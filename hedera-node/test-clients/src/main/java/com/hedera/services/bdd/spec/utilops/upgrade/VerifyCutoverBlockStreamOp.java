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
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.node.app.blocks.impl.BlockImplUtils;
import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
                final var hashedLeaf = BlockImplUtils.hashLeaf(serialized);
                nMinus3 = nMinus2;
                nMinus2 = nMinus1;
                nMinus1 = current;
                final var digest = CommonUtils.sha384DigestOrThrow();
                digest.update(current);
                digest.update(hashedLeaf);
                current = digest.digest();
                resultCount++;
            }
        }
        log.info("Computed running hashes through {} TRANSACTION_RESULT items", resultCount);
        assertTrue(resultCount > 0, "First post-cutover block should contain at least one transaction result");
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
                CommonUtils.sha384DigestOrThrow(),
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
                        Bytes.wrap(new byte[48]),
                        footer.startOfBlockStateRootHash(),
                        "Block #" + blockNum + " footer.startOfBlockStateRootHash" + " should not be the hash of zero");
            }

            final var computedRootHash = computeBlockRootHash(block, prevBlockHash, prevBlockHashesTree);
            log.info("Block #{}: computed root hash {}", blockNum, computedRootHash.toHex());

            prevBlockHash = computedRootHash;
            prevBlockHashesTree.addNodeByHash(computedRootHash.toByteArray());
        }

        log.info("Hash chain verified for {} post-cutover blocks", postCutoverBlocks.size());
        return false;
    }

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

        final var d5n1 = BlockImplUtils.hashInternalNode(previousBlockHash, prevBlockRootsHash);
        final var d5n2 = BlockImplUtils.hashInternalNode(startOfBlockStateHash, consensusHeaderHash);
        final var d5n3 = BlockImplUtils.hashInternalNode(inputsHash, outputsHash);
        final var d5n4 = BlockImplUtils.hashInternalNode(stateChangesHash, traceDataHash);
        final var d4n1 = BlockImplUtils.hashInternalNode(d5n1, d5n2);
        final var d4n2 = BlockImplUtils.hashInternalNode(d5n3, d5n4);
        final var d3n1 = BlockImplUtils.hashInternalNode(d4n1, d4n2);
        final var tsBytes = Timestamp.PROTOBUF.toBytes(blockTimestamp);
        final var d2n1 = BlockImplUtils.hashLeaf(tsBytes);
        final var d2n2 = BlockImplUtils.hashInternalNodeSingleChild(d3n1);
        return BlockImplUtils.hashInternalNode(d2n1, d2n2);
    }

    @Override
    public String toString() {
        return "VerifyCutoverBlockStreamOp";
    }
}
