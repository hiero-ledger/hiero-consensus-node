// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.MigrationRootHashVoteTally;
import com.hedera.hapi.node.state.blockrecords.MigrationRootHashVotingState;
import com.hedera.hapi.node.state.blockrecords.MigrationWrappedHashes;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.hapi.services.auxiliary.blockrecords.MigrationRootHashVoteTransactionBody;
import com.hedera.node.app.records.MigrationRootHashStateApplier;
import com.hedera.node.app.records.WritableMigrationRootHashStore;
import com.hedera.node.app.records.schemas.V0490BlockRecordSchema;
import com.hedera.node.app.records.schemas.V0730BlockRecordSchema;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableQueueState;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Default writable implementation for migration vote and queue state.
 */
public class WritableMigrationRootHashStoreImpl implements WritableMigrationRootHashStore {
    private final WritableSingletonState<MigrationRootHashVotingState> votingState;
    private final WritableSingletonState<BlockInfo> blockInfoState;
    private final WritableKVState<NodeId, MigrationRootHashVoteTransactionBody> votes;
    private final WritableKVState<ProtoBytes, MigrationRootHashVoteTally> tallies;
    private final WritableQueueState<MigrationWrappedHashes> queue;

    public WritableMigrationRootHashStoreImpl(@NonNull final WritableStates states) {
        requireNonNull(states);
        this.votingState = states.getSingleton(V0730BlockRecordSchema.MIGRATION_ROOT_HASH_VOTING_STATE_ID);
        this.blockInfoState = states.getSingleton(V0490BlockRecordSchema.BLOCKS_STATE_ID);
        this.votes = states.get(V0730BlockRecordSchema.MIGRATION_ROOT_HASH_VOTES_STATE_ID);
        this.tallies = states.get(V0730BlockRecordSchema.MIGRATION_ROOT_HASH_TALLIES_STATE_ID);
        this.queue = states.getQueue(V0730BlockRecordSchema.MIGRATION_WRAPPED_HASHES_QUEUE_STATE_ID);
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

    @Override
    public boolean putVoteIfAbsent(final long nodeId, @NonNull final MigrationRootHashVoteTransactionBody vote) {
        requireNonNull(vote);
        final var key = new NodeId(nodeId);
        if (votes.get(key) != null) {
            return false;
        }
        votes.put(key, vote);
        return true;
    }

    @Override
    public void addToTally(@NonNull final Bytes voteHash, final long weight) {
        final var key = asProtoBytes(voteHash);
        final var current = tallies.get(key);
        final var currentWeight = current == null ? 0 : current.totalWeight();
        final var currentCount = current == null ? 0 : current.voteCount();
        tallies.put(
                key,
                MigrationRootHashVoteTally.newBuilder()
                        .totalWeight(currentWeight + weight)
                        .voteCount(currentCount + 1)
                        .build());
    }

    @Override
    public void putVotingState(@NonNull final MigrationRootHashVotingState votingState) {
        this.votingState.put(requireNonNull(votingState));
    }

    @Override
    public void addQueuedHashes(@NonNull final MigrationWrappedHashes queuedHashes) {
        queue.add(requireNonNull(queuedHashes));
    }

    @Override
    public boolean applyFinalizedValuesAndMarkComplete(
            @NonNull final Bytes agreedVoteHash,
            @NonNull final Bytes previousWrappedRecordBlockRootHash,
            @NonNull final List<Bytes> wrappedIntermediatePreviousBlockRootHashes,
            final long wrappedIntermediateBlockRootsLeafCount) {
        requireNonNull(agreedVoteHash);
        requireNonNull(previousWrappedRecordBlockRootHash);
        requireNonNull(wrappedIntermediatePreviousBlockRootHashes);
        if (isVotingComplete()) {
            return false;
        }
        final var changed = MigrationRootHashStateApplier.applyToBlockInfo(
                blockInfoState,
                previousWrappedRecordBlockRootHash,
                wrappedIntermediatePreviousBlockRootHashes,
                wrappedIntermediateBlockRootsLeafCount);
        votingState.put(MigrationRootHashVotingState.newBuilder()
                .votingComplete(true)
                .agreedVoteHash(agreedVoteHash)
                .previousWrappedRecordBlockRootHash(previousWrappedRecordBlockRootHash)
                .wrappedIntermediatePreviousBlockRootHashes(wrappedIntermediatePreviousBlockRootHashes)
                .wrappedIntermediateBlockRootsLeafCount(wrappedIntermediateBlockRootsLeafCount)
                .build());
        return changed;
    }
}
