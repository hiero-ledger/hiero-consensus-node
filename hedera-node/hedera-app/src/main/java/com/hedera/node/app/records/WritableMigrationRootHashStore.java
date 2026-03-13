// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records;

import com.hedera.hapi.services.auxiliary.blockrecords.MigrationRootHashVoteTransactionBody;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Write access for migration root hash voting state.
 */
public interface WritableMigrationRootHashStore extends ReadableMigrationRootHashStore {
    boolean putVoteIfAbsent(long nodeId, @NonNull MigrationRootHashVoteTransactionBody vote);

    void addToTally(@NonNull MigrationRootHashVoteTransactionBody vote, long weight);

    void applyFinalizedValuesAndMarkComplete(
            @NonNull Bytes previousWrappedRecordBlockRootHash,
            @NonNull List<Bytes> wrappedIntermediatePreviousBlockRootHashes,
            long wrappedIntermediateBlockRootsLeafCount);
}
