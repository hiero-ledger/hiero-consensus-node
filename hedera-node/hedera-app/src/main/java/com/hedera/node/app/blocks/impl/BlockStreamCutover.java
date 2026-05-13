// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.blocks.BlockStreamManager.HASH_OF_ZERO;
import static com.hedera.node.app.blocks.impl.BlockImplUtils.appendHash;
import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.node.app.blocks.BlockStreamService;
import com.hedera.node.app.blocks.EffectiveStartupBlockStreamInfo;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * Utilities for deriving block stream state from the last record stream state during block stream cutover.
 */
public final class BlockStreamCutover {
    private BlockStreamCutover() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Computes the {@link BlockStreamInfo} that should be used after block stream cutover.
     *
     * @param blockInfo the final record stream {@link BlockInfo}
     * @param runningHashes the final record stream {@link RunningHashes}
     * @param previewBlockStreamInfo the preview {@link BlockStreamInfo} to overwrite
     * @return the cutover {@link BlockStreamInfo}
     */
    public static @NonNull BlockStreamInfo blockStreamInfoFrom(
            @NonNull final BlockInfo blockInfo,
            @NonNull final RunningHashes runningHashes,
            @NonNull final BlockStreamInfo previewBlockStreamInfo) {
        requireNonNull(blockInfo);
        requireNonNull(runningHashes);
        requireNonNull(previewBlockStreamInfo);
        return previewBlockStreamInfo
                .copyBuilder()
                .blockNumber(blockInfo.lastBlockNumber())
                .blockTime(blockInfo.firstConsTimeOfCurrentBlock())
                .trailingOutputHashes(trailingOutputHashesFrom(runningHashes))
                .trailingBlockHashes(trailingBlockHashesFrom(blockInfo))
                .inputTreeRootHash(HASH_OF_ZERO)
                .numPrecedingStateChangesItems(0)
                .rightmostPrecedingStateChangesTreeHashes(List.of())
                .blockEndTime(blockInfo.lastUsedConsTime())
                .lastIntervalProcessTime(blockInfo.lastIntervalProcessTime())
                .lastHandleTime(blockInfo.consTimeOfLastHandledTxn())
                .consensusHeaderRootHash(HASH_OF_ZERO)
                .traceDataRootHash(HASH_OF_ZERO)
                .intermediatePreviousBlockRootHashes(blockInfo.wrappedIntermediatePreviousBlockRootHashes())
                .intermediateBlockRootsLeafCount(blockInfo.wrappedIntermediateBlockRootsLeafCount())
                .build();
    }

    /**
     * Returns the effective block stream info startup components should use.
     *
     * <p>If cutover is enabled and the persisted {@link BlockInfo} still says its preview stream has not been
     * overwritten, this returns a synthesized cutover {@link BlockStreamInfo}. Otherwise it returns the persisted
     * {@link BlockStreamInfo}, if any.
     *
     * @param state the startup state
     * @param cutoverEnabled whether block stream cutover is enabled
     * @return the effective startup block stream info
     */
    public static @NonNull EffectiveStartupBlockStreamInfo effectiveStartupBlockStreamInfoFrom(
            @NonNull final State state, final boolean cutoverEnabled) {
        requireNonNull(state);
        final var persistedBlockStreamInfo = persistedBlockStreamInfoFrom(state);
        if (!cutoverEnabled || persistedBlockStreamInfo == null) {
            return EffectiveStartupBlockStreamInfo.fromPersisted(persistedBlockStreamInfo);
        }

        final var blockInfo = blockInfoFrom(state);
        if (blockInfo == null || blockInfo.previewStreamOverwritten()) {
            return EffectiveStartupBlockStreamInfo.fromPersisted(persistedBlockStreamInfo);
        }

        final var runningHashes = requireNonNull(runningHashesFrom(state));
        return EffectiveStartupBlockStreamInfo.previewingCutover(
                blockStreamInfoFrom(blockInfo, runningHashes, persistedBlockStreamInfo));
    }

    /**
     * Returns the record block hashes to copy into {@link BlockStreamInfo#trailingBlockHashes()}.
     */
    public static @NonNull Bytes trailingBlockHashesFrom(@NonNull final BlockInfo blockInfo) {
        requireNonNull(blockInfo);
        final var fullBlockHashes = blockInfo.blockHashes().toByteArray();
        if (fullBlockHashes.length < HASH_SIZE) {
            throw new IllegalStateException(
                    "Cutover requires at least one record block hash in BlockInfo.blockHashes, but found "
                            + fullBlockHashes.length + " bytes (need >= " + HASH_SIZE + ")");
        }
        return Bytes.wrap(fullBlockHashes, 0, fullBlockHashes.length - HASH_SIZE);
    }

    /**
     * Returns the record running hashes to copy into {@link BlockStreamInfo#trailingOutputHashes()}.
     */
    public static @NonNull Bytes trailingOutputHashesFrom(@NonNull final RunningHashes runningHashes) {
        requireNonNull(runningHashes);
        Bytes lastFourHashes =
                appendHash(Bytes.wrap(runningHashes.nMinus3RunningHash().toByteArray()), Bytes.EMPTY, 4);
        lastFourHashes =
                appendHash(Bytes.wrap(runningHashes.nMinus2RunningHash().toByteArray()), lastFourHashes, 4);
        lastFourHashes =
                appendHash(Bytes.wrap(runningHashes.nMinus1RunningHash().toByteArray()), lastFourHashes, 4);
        return appendHash(Bytes.wrap(runningHashes.runningHash().toByteArray()), lastFourHashes, 4);
    }

    private static @Nullable BlockStreamInfo persistedBlockStreamInfoFrom(@NonNull final State state) {
        final var blockStreamStates = state.getReadableStates(BlockStreamService.NAME);
        if (!blockStreamStates.contains(BLOCK_STREAM_INFO_STATE_ID)) {
            return null;
        }
        return blockStreamStates
                .<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID)
                .get();
    }

    private static @Nullable BlockInfo blockInfoFrom(@NonNull final State state) {
        final var blockRecordStates = state.getReadableStates(BlockRecordService.NAME);
        if (!blockRecordStates.contains(BLOCKS_STATE_ID)) {
            return null;
        }
        return blockRecordStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID).get();
    }

    private static @Nullable RunningHashes runningHashesFrom(@NonNull final State state) {
        final var blockRecordStates = state.getReadableStates(BlockRecordService.NAME);
        if (!blockRecordStates.contains(RUNNING_HASHES_STATE_ID)) {
            return null;
        }
        return blockRecordStates
                .<RunningHashes>getSingleton(RUNNING_HASHES_STATE_ID)
                .get();
    }
}
