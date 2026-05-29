// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.node.app.blocks.BlockStreamService;
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

    private final BlockStreamInfo blockStreamInfo;

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
        // The trailing block hashes in state cover up to blockNumber - 1, so reporting
        // blockNumber as the current block makes blockhash(block.number - 1) resolvable.
        // Guard against initial state where blockNumber may be 0 (no blocks completed yet).
        return Math.max(1, blockStreamInfo.blockNumber());
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
        // The rightmost hash in the trailing block hashes is for block (blockNumber - 1)
        return BlockRecordInfoUtils.blockHashByBlockNumber(
                blockStreamInfo.trailingBlockHashes(), blockStreamInfo.blockNumber() - 1, blockNo);
    }
}
