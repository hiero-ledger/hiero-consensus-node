// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.NodeMigrationRootHashVote;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.hapi.services.auxiliary.blockrecords.MigrationRootHashVoteTransactionBody;
import com.hedera.node.app.records.schemas.V0490BlockRecordSchema;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

public class WritableBlockRecordStore extends ReadableBlockRecordStore {
    private final WritableSingletonState<BlockInfo> writableBlockInfo;

    public WritableBlockRecordStore(@NonNull final WritableStates states) {
        super(states);
        writableBlockInfo = requireNonNull(states).getSingleton(V0490BlockRecordSchema.BLOCKS_STATE_ID);
    }

    @Override
    @NonNull
    public BlockInfo getLastBlockInfo() {
        return writableBlockInfo.get();
    }

    public boolean putVoteIfAbsent(final long nodeId, @NonNull final MigrationRootHashVoteTransactionBody vote) {
        requireNonNull(vote);
        final var blockInfo = getLastBlockInfo();
        if (blockInfo.migrationRootHashVotes().stream()
                .anyMatch(existing -> existing.nodeIdOrElse(NodeId.DEFAULT).id() == nodeId)) {
            return false;
        }
        final var votes = new ArrayList<>(blockInfo.migrationRootHashVotes());
        votes.add(NodeMigrationRootHashVote.newBuilder()
                .nodeId(new NodeId(nodeId))
                .vote(vote)
                .build());
        putBlockInfo(blockInfo.copyBuilder().migrationRootHashVotes(votes).build());
        return true;
    }

    public void applyFinalizedValuesAndMarkComplete(
            @NonNull final Bytes previousWrappedRecordBlockRootHash,
            @NonNull final List<Bytes> wrappedIntermediatePreviousBlockRootHashes,
            final long wrappedIntermediateBlockRootsLeafCount) {
        requireNonNull(previousWrappedRecordBlockRootHash);
        requireNonNull(wrappedIntermediatePreviousBlockRootHashes);
        final var blockInfo = getLastBlockInfo();
        putBlockInfo(blockInfo
                .copyBuilder()
                .previousWrappedRecordBlockRootHash(previousWrappedRecordBlockRootHash)
                .wrappedIntermediatePreviousBlockRootHashes(wrappedIntermediatePreviousBlockRootHashes)
                .wrappedIntermediateBlockRootsLeafCount(wrappedIntermediateBlockRootsLeafCount)
                .votingComplete(true)
                .migrationWrappedHashes(List.of())
                .build());
    }

    private void putBlockInfo(@NonNull final BlockInfo blockInfo) {
        writableBlockInfo.put(requireNonNull(blockInfo));
    }
}
