// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records;

import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_LABEL;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.services.auxiliary.blockrecords.MigrationRootHashVoteTransactionBody;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WritableBlockRecordStoreTest {
    @Test
    void accumulatesVotesFromWritableState() {
        final var blockInfoRef = new AtomicReference<>(BlockInfo.newBuilder()
                .migrationRootHashVotes(java.util.List.of())
                .migrationWrappedHashes(java.util.List.of())
                .build());
        final var state = new FunctionWritableSingletonState<>(
                BLOCKS_STATE_ID, BLOCKS_STATE_LABEL, blockInfoRef::get, blockInfoRef::set);
        final var writableStates = MapWritableStates.builder().state(state).build();
        final var subject = new WritableBlockRecordStore(writableStates);
        final var vote = MigrationRootHashVoteTransactionBody.newBuilder()
                .previousWrappedRecordBlockRootHash(Bytes.wrap(new byte[48]))
                .wrappedIntermediateBlockRootsLeafCount(0)
                .build();

        assertThat(subject.putVoteIfAbsent(0L, vote, 3)).isTrue();
        assertThat(subject.putVoteIfAbsent(1L, vote, 3)).isTrue();
        assertThat(subject.votes()).hasSize(2);
    }

    @Test
    void doesNotAccumulateVotesBeyondTheSuppliedBound() {
        final var blockInfoRef = new AtomicReference<>(BlockInfo.newBuilder()
                .migrationRootHashVotes(java.util.List.of())
                .migrationWrappedHashes(java.util.List.of())
                .build());
        final var state = new FunctionWritableSingletonState<>(
                BLOCKS_STATE_ID, BLOCKS_STATE_LABEL, blockInfoRef::get, blockInfoRef::set);
        final var writableStates = MapWritableStates.builder().state(state).build();
        final var subject = new WritableBlockRecordStore(writableStates);
        final var vote = MigrationRootHashVoteTransactionBody.newBuilder()
                .previousWrappedRecordBlockRootHash(Bytes.wrap(new byte[48]))
                .wrappedIntermediateBlockRootsLeafCount(0)
                .build();

        assertThat(subject.putVoteIfAbsent(0L, vote, 2)).isTrue();
        assertThat(subject.putVoteIfAbsent(1L, vote, 2)).isTrue();
        // The list is now at the bound, so a vote from a further node is rejected rather than stored
        assertThat(subject.putVoteIfAbsent(2L, vote, 2)).isFalse();
        assertThat(subject.votes()).hasSize(2);
    }

    @Test
    void applyFinalizedValuesMarksVotingComplete() {
        final var blockInfoRef = new AtomicReference<>(BlockInfo.newBuilder()
                .migrationRootHashVotes(java.util.List.of())
                .migrationWrappedHashes(java.util.List.of())
                .build());
        final var state = new FunctionWritableSingletonState<>(
                BLOCKS_STATE_ID, BLOCKS_STATE_LABEL, blockInfoRef::get, blockInfoRef::set);
        final var writableStates = MapWritableStates.builder().state(state).build();
        final var subject = new WritableBlockRecordStore(writableStates);

        subject.applyFinalizedValuesAndMarkComplete(
                Bytes.wrap(new byte[48]), java.util.List.of(Bytes.wrap(new byte[48])), 1);

        assertThat(subject.getLastBlockInfo().votingComplete()).isTrue();
        assertThat(subject.getLastBlockInfo().migrationWrappedHashes()).isEmpty();
    }
}
