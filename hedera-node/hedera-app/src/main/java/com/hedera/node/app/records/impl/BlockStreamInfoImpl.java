// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static com.hedera.node.app.blocks.impl.BlockImplUtils.HASH_SIZE;
import static com.hedera.node.app.blocks.impl.BlockImplUtils.appendHash;
import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
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

/**
 * A {@link BlockRecordInfo} that derives the current block number, timestamp, PRNG seed, and trailing block hashes
 * from the {@link BlockStreamInfo} singleton. Used when {@code blockStream.streamMode=BLOCKS}, where the legacy
 * {@link com.hedera.hapi.node.state.blockrecords.BlockInfo} singleton read by {@link BlockRecordInfoImpl} is not
 * maintained. Reads from an immutable state snapshot, so it is safe to use from query threads (unlike the live
 * {@link com.hedera.node.app.blocks.BlockStreamManager} singleton, whose fields are mutated by the handle thread).
 */
public final class BlockStreamInfoImpl implements BlockRecordInfo {
    private static final int NUM_TRAILING_BLOCKS = 256;

    private final BlockStreamInfo blockStreamInfo;

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
    public static BlockStreamInfoImpl from(@NonNull final State state) {
        final var blockStreamInfo = requireNonNull(state.getReadableStates(BlockStreamService.NAME)
                .<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID)
                .get());
        return new BlockStreamInfoImpl(blockStreamInfo);
    }

    public BlockStreamInfoImpl(@NonNull final BlockStreamInfo blockStreamInfo) {
        this.blockStreamInfo = requireNonNull(blockStreamInfo);
    }

    /** {@inheritDoc} */
    @Nullable
    @Override
    public Bytes prngSeed() {
        // Mirrors BlockStreamManagerImpl.RunningHashManager: the n-minus-3 running hash is the seed, and is the
        // leftmost HASH_SIZE bytes of the trailing output hashes once at least four hashes are present.
        final var hashes = blockStreamInfo.trailingOutputHashes();
        final var n = (int) (hashes.length() / HASH_SIZE);
        return n < 4 ? null : hashes.slice(0, HASH_SIZE);
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
        return BlockImplUtils.blockHashByBlockNumber(extendedBlockHashes(), lastCompleted, blockNo);
    }

    private Bytes extendedBlockHashes() {
        if (extendedBlockHashes == null) {
            final var lastBlockHash = BlockStreamManagerImpl.reconstructLastBlockHash(blockStreamInfo);
            extendedBlockHashes = appendHash(lastBlockHash, blockStreamInfo.trailingBlockHashes(), NUM_TRAILING_BLOCKS);
        }
        return extendedBlockHashes;
    }
}
