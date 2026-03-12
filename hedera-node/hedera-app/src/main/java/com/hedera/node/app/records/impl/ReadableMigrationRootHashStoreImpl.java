// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.blockrecords.MigrationRootHashVoteTally;
import com.hedera.hapi.node.state.blockrecords.MigrationRootHashVotingState;
import com.hedera.hapi.node.state.blockrecords.MigrationWrappedHashes;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.hapi.services.auxiliary.blockrecords.MigrationRootHashVoteTransactionBody;
import com.hedera.node.app.records.ReadableMigrationRootHashStore;
import com.hedera.node.app.records.schemas.V0570BlockRecordSchema;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableQueueState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Default readable implementation for migration vote and queue state.
 */
public class ReadableMigrationRootHashStoreImpl implements ReadableMigrationRootHashStore {
    private final ReadableSingletonState<MigrationRootHashVotingState> votingState;
    private final ReadableKVState<NodeId, MigrationRootHashVoteTransactionBody> votes;
    private final ReadableKVState<ProtoBytes, MigrationRootHashVoteTally> tallies;
    private final ReadableQueueState<MigrationWrappedHashes> queue;

    public ReadableMigrationRootHashStoreImpl(@NonNull final ReadableStates states) {
        requireNonNull(states);
        this.votingState = states.getSingleton(V0570BlockRecordSchema.MIGRATION_ROOT_HASH_VOTING_STATE_ID);
        this.votes = states.get(V0570BlockRecordSchema.MIGRATION_ROOT_HASH_VOTES_STATE_ID);
        this.tallies = states.get(V0570BlockRecordSchema.MIGRATION_ROOT_HASH_TALLIES_STATE_ID);
        this.queue = states.getQueue(V0570BlockRecordSchema.MIGRATION_WRAPPED_HASHES_QUEUE_STATE_ID);
    }

    @Override
    public @NonNull MigrationRootHashVotingState getVotingState() {
        final var state = votingState.get();
        return state == null ? MigrationRootHashVotingState.DEFAULT : state;
    }

    @Override
    public boolean isVotingComplete() {
        return getVotingState().votingComplete();
    }

    @Override
    public @Nullable MigrationRootHashVoteTransactionBody getVoteForNode(final long nodeId) {
        return votes.get(new NodeId(nodeId));
    }

    @Override
    public @Nullable MigrationRootHashVoteTally getTally(@NonNull final Bytes voteHash) {
        return tallies.get(asProtoBytes(voteHash));
    }

    @Override
    public @NonNull List<MigrationWrappedHashes> queuedHashesInOrder() {
        final List<MigrationWrappedHashes> queuedHashes = new ArrayList<>();
        final var iterator = queue.iterator();
        while (iterator.hasNext()) {
            final var queuedHash = iterator.next();
            if (queuedHash != null) {
                queuedHashes.add(queuedHash);
            }
        }
        queuedHashes.sort(Comparator.comparingLong(MigrationWrappedHashes::blockNumber));
        return queuedHashes;
    }

    @Override
    public @NonNull ProtoBytes asProtoBytes(@NonNull final Bytes bytes) {
        requireNonNull(bytes);
        return new ProtoBytes(bytes);
    }
}
