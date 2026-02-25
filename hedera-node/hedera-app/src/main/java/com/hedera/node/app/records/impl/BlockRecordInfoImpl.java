// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.app.records.schemas.V0490BlockRecordSchema;
import com.hedera.node.app.spi.records.BlockRecordInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A simple implementation of {@link BlockRecordInfo} that uses the objects stored in state ({@link BlockInfo} and
 * {@link RunningHashes}).
 */
public final class BlockRecordInfoImpl implements BlockRecordInfo {

    private final BlockInfo blockInfo;
    private final RunningHashes runningHashes;

    /**
     * Creates a {@code BlockRecordInfoImpl} from the given {@link State}.
     * @param state the state
     * @return the created {@code BlockRecordInfoImpl}
     */
    public static BlockRecordInfoImpl from(@NonNull final State state) {
        final var states = state.getReadableStates(BlockRecordService.NAME);
        final var blockInfoState = requireNonNull(states.<BlockInfo>getSingleton(V0490BlockRecordSchema.BLOCKS_STATE_ID)
                .get());
        final var runningHashState =
                requireNonNull(states.<RunningHashes>getSingleton(V0490BlockRecordSchema.RUNNING_HASHES_STATE_ID)
                        .get());
        return new BlockRecordInfoImpl(blockInfoState, runningHashState);
    }

    /**
     * Constructor of {@code BlockRecordInfoImpl}.
     *
     * @param blockInfo the {@link BlockInfo} object
     * @param runningHashes the {@link RunningHashes} object
     */
    public BlockRecordInfoImpl(@NonNull final BlockInfo blockInfo, @NonNull final RunningHashes runningHashes) {
        this.blockInfo = requireNonNull(blockInfo);
        this.runningHashes = requireNonNull(runningHashes);
    }

    /** {@inheritDoc} */
    @Override
    public Bytes prngSeed() {
        return runningHashes.nMinus3RunningHash();
    }

    /** {@inheritDoc} */
    @Override
    public long blockNo() {
        return blockInfo.lastBlockNumber() + 1;
    }

    @Override
    public @NonNull Timestamp blockTimestamp() {
        // There should always be a current block and a first consensus time within it
        return blockInfo.firstConsTimeOfCurrentBlockOrThrow();
    }

    /** {@inheritDoc} */
    @Nullable
    @Override
    public Bytes blockHashByBlockNumber(final long blockNo) {
        return BlockRecordInfoUtils.blockHashByBlockNumber(blockInfo, blockNo);
    }
}
