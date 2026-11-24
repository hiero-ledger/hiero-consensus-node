// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.MigrationWrappedHashes;
import com.hedera.hapi.node.state.blockrecords.NodeMigrationRootHashVote;
import com.hedera.node.app.records.schemas.V0490BlockRecordSchema;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Comparator;
import java.util.List;

public class ReadableBlockRecordStore {

    /** The underlying data storage class that holds the block info data. */
    private final ReadableSingletonState<BlockInfo> blockInfo;

    public ReadableBlockRecordStore(@NonNull final ReadableStates states) {
        requireNonNull(states);
        this.blockInfo = states.getSingleton(V0490BlockRecordSchema.BLOCKS_STATE_ID);
    }

    /**
     * Returns information about the currently-ongoing and latest completed record blocks
     */
    @NonNull
    public BlockInfo getLastBlockInfo() {
        return blockInfo.get();
    }

    public boolean isVotingComplete() {
        return getLastBlockInfo().votingComplete();
    }

    /**
     * Returns whether migration root hash voting is still incomplete but its completion deadline has passed, meaning
     * a block at or after {@code votingCompletionDeadlineBlockNumber} has already closed without being queued for
     * replay. A vote finalizing now would build the agreed chain from a queue missing those blocks, so such votes
     * must be ignored. A deadline of zero means no deadline was set.
     */
    public boolean isVotingDeadlinePassed() {
        final var info = getLastBlockInfo();
        return !info.votingComplete()
                && info.votingCompletionDeadlineBlockNumber() > 0
                && info.lastBlockNumber() >= info.votingCompletionDeadlineBlockNumber();
    }

    @NonNull
    public List<NodeMigrationRootHashVote> votes() {
        return getLastBlockInfo().migrationRootHashVotes();
    }

    @NonNull
    public List<MigrationWrappedHashes> wrappedHashesInOrder() {
        return getLastBlockInfo().migrationWrappedHashes().stream()
                .sorted(Comparator.comparingLong(MigrationWrappedHashes::blockNumber))
                .toList();
    }
}
