// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.node.app.records.schemas.V0490BlockRecordSchema;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableSingletonStateBase;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Shared logic for applying migration wrapped-root values into BlockInfo state.
 */
public final class MigrationRootHashStateApplier {
    private MigrationRootHashStateApplier() {}

    public static boolean applyToBlockInfo(
            @NonNull final WritableStates writableStates,
            @NonNull final Bytes previousWrappedRecordBlockRootHash,
            @NonNull final List<Bytes> wrappedIntermediatePreviousBlockRootHashes,
            final long wrappedIntermediateBlockRootsLeafCount) {
        requireNonNull(writableStates);
        final var blockInfoState = writableStates.<BlockInfo>getSingleton(V0490BlockRecordSchema.BLOCKS_STATE_ID);
        return applyToBlockInfo(
                blockInfoState,
                previousWrappedRecordBlockRootHash,
                wrappedIntermediatePreviousBlockRootHashes,
                wrappedIntermediateBlockRootsLeafCount);
    }

    public static boolean applyToBlockInfo(
            @NonNull final WritableSingletonState<BlockInfo> blockInfoState,
            @NonNull final Bytes previousWrappedRecordBlockRootHash,
            @NonNull final List<Bytes> wrappedIntermediatePreviousBlockRootHashes,
            final long wrappedIntermediateBlockRootsLeafCount) {
        requireNonNull(blockInfoState);
        requireNonNull(previousWrappedRecordBlockRootHash);
        requireNonNull(wrappedIntermediatePreviousBlockRootHashes);

        final var blockInfo = blockInfoState.get();
        if (blockInfo == null) {
            return false;
        }

        boolean changed = false;
        final var builder = blockInfo.copyBuilder();
        if (!previousWrappedRecordBlockRootHash.equals(blockInfo.previousWrappedRecordBlockRootHash())) {
            builder.previousWrappedRecordBlockRootHash(previousWrappedRecordBlockRootHash);
            changed = true;
        }
        if (!wrappedIntermediatePreviousBlockRootHashes.equals(
                blockInfo.wrappedIntermediatePreviousBlockRootHashes())) {
            builder.wrappedIntermediatePreviousBlockRootHashes(wrappedIntermediatePreviousBlockRootHashes);
            changed = true;
        }
        if (wrappedIntermediateBlockRootsLeafCount != blockInfo.wrappedIntermediateBlockRootsLeafCount()) {
            builder.wrappedIntermediateBlockRootsLeafCount(wrappedIntermediateBlockRootsLeafCount);
            changed = true;
        }

        if (changed) {
            blockInfoState.put(builder.build());
            ((WritableSingletonStateBase<BlockInfo>) blockInfoState).commit();
        }
        return changed;
    }
}
