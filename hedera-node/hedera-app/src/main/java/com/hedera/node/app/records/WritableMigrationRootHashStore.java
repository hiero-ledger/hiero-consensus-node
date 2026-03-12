// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records;

import com.hedera.hapi.node.state.blockrecords.MigrationRootHashVotingState;
import com.hedera.hapi.node.state.blockrecords.MigrationWrappedHashes;
import com.hedera.hapi.services.auxiliary.blockrecords.MigrationRootHashVoteTransactionBody;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Write access for migration root hash voting state.
 */
public interface WritableMigrationRootHashStore extends ReadableMigrationRootHashStore {
    boolean putVoteIfAbsent(long nodeId, @NonNull MigrationRootHashVoteTransactionBody vote);

    void addToTally(@NonNull Bytes voteHash, long weight);

    void putVotingState(@NonNull MigrationRootHashVotingState votingState);

    void addQueuedHashes(@NonNull MigrationWrappedHashes queuedHashes);

    boolean applyFinalizedValuesAndMarkComplete(
            @NonNull Bytes agreedVoteHash,
            @NonNull Bytes previousWrappedRecordBlockRootHash,
            @NonNull List<Bytes> wrappedIntermediatePreviousBlockRootHashes,
            long wrappedIntermediateBlockRootsLeafCount);
}
