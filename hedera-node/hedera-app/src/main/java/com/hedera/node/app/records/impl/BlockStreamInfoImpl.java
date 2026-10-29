// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static com.hedera.node.app.blocks.impl.BlockImplUtils.appendHash;
import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.RECORD_HASH_SIZE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.node.app.blocks.BlockStreamService;
import com.hedera.node.app.blocks.impl.BlockImplUtils;
import com.hedera.node.app.blocks.impl.BlockStreamManagerImpl;
import com.hedera.node.app.spi.records.BlockRecordInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.base.crypto.DigestType;

/**
 * A {@link BlockRecordInfo} that derives the current block number, timestamp, PRNG seed, and trailing block hashes
 * from the {@link BlockStreamInfo} singleton. Used when {@code blockStream.streamMode=BLOCKS}, where the legacy
 * {@link com.hedera.hapi.node.state.blockrecords.BlockInfo} singleton read by {@link BlockRecordInfoImpl} is not
 * maintained. Reads from an immutable state snapshot, so it is safe to use from query threads (unlike the live
 * {@link com.hedera.node.app.blocks.BlockStreamManager} singleton, whose fields are mutated by the handle thread).
 */
public final class BlockStreamInfoImpl implements BlockRecordInfo {
    /**
     * The default {@code blockRecordStream.numOfBlockHashesInState}; used only by the convenience constructor for
     * callers (chiefly tests) where the configured retention is immaterial. Production reads the configured value via
     * {@link #from(State, DigestType, int)}.
     */
    private static final int DEFAULT_NUM_TRAILING_BLOCKS = 256;

    private final BlockStreamInfo blockStreamInfo;
    private final DigestType digestType;
    // The wrapped parent root to use for the first post-cutover block, or null to derive the parent from the window.
    @Nullable
    private final Bytes firstPostCutoverParentHash;

    /**
     * The configured maximum number of trailing block hashes the state retains
     * ({@code BlockRecordStreamConfig.numOfBlockHashesInState()}); must match the producer's retention so the window
     * stride is decoded correctly (a mature native window of this many entries would otherwise be mistaken for a
     * shorter count of padded entries).
     */
    private final int numTrailingBlocks;

    /**
     * Lazily-computed trailing block hashes extended with the (reconstructed) hash of the last completed block,
     * so {@code blockhash(block.number - 1)} resolves for the most recent block — see
     * {@link #blockHashByBlockNumber(long)}.
     */
    @Nullable
    private Bytes extendedBlockHashes;

    /**
     * Creates a {@code BlockStreamInfoImpl} from the given {@link State}.
     * @param state the state
     * @return the created {@code BlockStreamInfoImpl}
     */
    public static BlockStreamInfoImpl from(
            @NonNull final State state, @NonNull final DigestType digestType, final int numTrailingBlocks) {
        requireNonNull(digestType);

        final var blockStreamInfo = requireNonNull(state.getReadableStates(BlockStreamService.NAME)
                .<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID)
                .get());
        // For the first post-cutover block the window holds the record hash, not the wrapped parent the block used;
        // provide the wrapped parent (from BlockInfo) so snapshot-query reconstruction links to the correct parent.
        final var firstPostCutoverParentHash =
                BlockStreamManagerImpl.firstPostCutoverParentHash(state, blockStreamInfo);
        return new BlockStreamInfoImpl(blockStreamInfo, digestType, numTrailingBlocks, firstPostCutoverParentHash);
    }

    /**
     * Convenience factory defaulting the trailing-window retention to {@link #DEFAULT_NUM_TRAILING_BLOCKS}. Use only
     * where the configured retention is immaterial to the decode; production paths must pass the configured
     * {@code BlockRecordStreamConfig.numOfBlockHashesInState()} via {@link #from(State, DigestType, int)}.
     */
    public static BlockStreamInfoImpl from(@NonNull final State state, @NonNull final DigestType digestType) {
        return from(state, digestType, DEFAULT_NUM_TRAILING_BLOCKS);
    }

    public BlockStreamInfoImpl(
            @NonNull final BlockStreamInfo blockStreamInfo,
            @NonNull final DigestType digestType,
            final int numTrailingBlocks) {
        this(blockStreamInfo, digestType, numTrailingBlocks, null);
    }

    public BlockStreamInfoImpl(
            @NonNull final BlockStreamInfo blockStreamInfo,
            @NonNull final DigestType digestType,
            final int numTrailingBlocks,
            @Nullable final Bytes firstPostCutoverParentHash) {
        this.blockStreamInfo = requireNonNull(blockStreamInfo);
        this.digestType = requireNonNull(digestType);
        this.numTrailingBlocks = numTrailingBlocks;
        this.firstPostCutoverParentHash = firstPostCutoverParentHash;
    }

    /**
     * Convenience constructor defaulting the trailing-window retention to {@link #DEFAULT_NUM_TRAILING_BLOCKS}. Use
     * only where the configured retention is immaterial to the decode; production paths must pass the configured
     * {@code BlockRecordStreamConfig.numOfBlockHashesInState()} via the three-argument constructor or {@link #from}.
     */
    public BlockStreamInfoImpl(@NonNull final BlockStreamInfo blockStreamInfo, @NonNull final DigestType digestType) {
        this(blockStreamInfo, digestType, DEFAULT_NUM_TRAILING_BLOCKS);
    }

    /** {@inheritDoc} */
    @Nullable
    @Override
    public Bytes prngSeed() {
        // Mirrors BlockStreamManagerImpl.RunningHashManager: the n-minus-3 running hash is the seed, and is the
        // leftmost hash of the trailing output hashes once at least four hashes are present. Across a block-stream
        // cutover the window transiently mixes full 48-byte SHA-384 seeds with digestType-sized hashes; a length
        // beyond 4 * hashSize means the leftmost entry is still a 48-byte SHA-384 seed (see RunningHashManager).
        final var hashSize = digestType.digestLength();
        final var hashes = blockStreamInfo.trailingOutputHashes();
        final var len = hashes.length();
        if (len > 4L * hashSize) {
            return hashes.slice(0, DigestType.SHA_384.digestLength());
        }
        final var n = (int) (len / hashSize);
        return n < 4 ? null : hashes.slice(0, hashSize);
    }

    /** {@inheritDoc} */
    @Override
    public long blockNo() {
        // Matches BlockRecordInfoImpl (lastBlockNumber + 1) and the handle path exactly: the current block is
        // one past the last completed block. At genesis (blockNumber == -1) this is 0, as in records mode.
        return blockStreamInfo.blockNumber() + 1;
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public Timestamp blockTimestamp() {
        return blockStreamInfo.blockTimeOrElse(Timestamp.DEFAULT);
    }

    /** {@inheritDoc} */
    @Nullable
    @Override
    public Bytes blockHashByBlockNumber(final long blockNo) {
        final long lastCompleted = blockStreamInfo.blockNumber();
        if (lastCompleted < 0) {
            // No block has completed yet, so no hashes are available.
            return null;
        }
        // The last completed block's own hash is not persisted in its own state, so the state-resident trailing
        // hashes only reach blockNumber - 1. Reconstruct it and append so the set covers up to blockNumber, letting
        // queries resolve blockhash(block.number - 1) for the most recent block exactly as BlockRecordInfoImpl does.
        // The window may be in 48-byte padded mode or compacted 32-byte native mode; the persisted
        // trailing_block_hashes_compacted flag gives the stride directly (extendedBlockHashes() extends the window at
        // that same stride), and realBlockHashByBlockNumber strips any left-padding to recover the real hash.
        return BlockImplUtils.realBlockHashByBlockNumber(
                extendedBlockHashes(),
                lastCompleted,
                blockNo,
                digestType.digestLength(),
                blockStreamInfo.trailingBlockHashesCompacted());
    }

    private Bytes extendedBlockHashes() {
        if (extendedBlockHashes == null) {
            final var lastBlockHash = BlockStreamManagerImpl.reconstructLastBlockHash(
                    blockStreamInfo, digestType, firstPostCutoverParentHash);
            // Append the reconstructed last hash at the window's CURRENT stride (48 padded or 32 native) without
            // compacting — this is a read-only extension for queries, never persisted. The persisted
            // trailing_block_hashes_compacted flag gives the stride directly.
            final var trailing = blockStreamInfo.trailingBlockHashes();
            final int stride =
                    blockStreamInfo.trailingBlockHashesCompacted() ? digestType.digestLength() : RECORD_HASH_SIZE;
            extendedBlockHashes =
                    appendHash(BlockImplUtils.leftPadTo(lastBlockHash, stride), trailing, numTrailingBlocks);
        }
        return extendedBlockHashes;
    }
}
