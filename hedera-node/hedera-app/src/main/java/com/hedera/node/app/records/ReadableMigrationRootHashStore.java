// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records;

import com.hedera.hapi.node.state.blockrecords.MigrationRootHashVoteTally;
import com.hedera.hapi.node.state.blockrecords.MigrationRootHashVotingState;
import com.hedera.hapi.node.state.blockrecords.MigrationWrappedHashes;
import com.hedera.hapi.services.auxiliary.blockrecords.MigrationRootHashVoteTransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * Read-only access to migration root hash voting state.
 */
public interface ReadableMigrationRootHashStore {
    @NonNull
    MigrationRootHashVotingState getVotingState();

    boolean isVotingComplete();

    @Nullable
    MigrationRootHashVoteTransactionBody getVoteForNode(long nodeId);

    @Nullable
    MigrationRootHashVoteTally getTally(@NonNull MigrationRootHashVoteTransactionBody vote);

    @NonNull
    List<MigrationWrappedHashes> queuedHashesInOrder();
}
