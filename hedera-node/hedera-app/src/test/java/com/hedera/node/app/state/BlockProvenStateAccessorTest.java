// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.spi.state.BlockProvenSnapshot;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.MerkleNodeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BlockProvenStateAccessorTest {

    private static final Bytes TSS_SIGNATURE_A = Bytes.wrap(new byte[] {1, 2, 3, 4});
    private static final Bytes TSS_SIGNATURE_B = Bytes.wrap(new byte[] {5, 6, 7, 8});

    private BlockProvenStateAccessor subject;

    @BeforeEach
    void setUp() {
        subject = new BlockProvenStateAccessor();
    }

    @Test
    void latestSnapshotEmptyUntilUpdated() {
        assertThat(subject.latestSnapshot()).isEmpty();
        assertThat(subject.latestState()).isEmpty();
    }

    @Test
    void updateStoresSnapshot() {
        final var firstState = mock(MerkleNodeState.class);
        final var firstTimestamp = Timestamp.newBuilder()
                .seconds(1_600_000_000L)
                .nanos(123_456_789)
                .build();
        final var firstMerklePath = MerklePath.newBuilder().nextPathIndex(1).build();
        final var secondState = mock(MerkleNodeState.class);
        final var secondTimestamp = Timestamp.newBuilder()
                .seconds(1_600_000_001L)
                .nanos(987_654_321)
                .build();
        final var secondMerklePath = MerklePath.newBuilder().nextPathIndex(2).build();

        subject.update(firstState, TSS_SIGNATURE_A, firstTimestamp, firstMerklePath);

        final BlockProvenSnapshot snapshot = subject.latestSnapshot().orElseThrow();
        assertThat(snapshot.merkleState()).isSameAs(firstState);
        assertThat(subject.latestState()).contains(firstState);
        assertThat(snapshot.tssSignature()).isSameAs(TSS_SIGNATURE_A);
        assertThat(snapshot.blockTimestamp()).isSameAs(firstTimestamp);
        assertThat(snapshot.path()).isSameAs(firstMerklePath);

        subject.update(secondState, TSS_SIGNATURE_B, secondTimestamp, secondMerklePath);

        final BlockProvenSnapshot updatedSnapshot = subject.latestSnapshot().orElseThrow();
        assertThat(updatedSnapshot.merkleState()).isSameAs(secondState);
        assertThat(subject.latestState()).contains(secondState);
        assertThat(updatedSnapshot.tssSignature()).isSameAs(TSS_SIGNATURE_B);
        assertThat(updatedSnapshot.blockTimestamp()).isSameAs(secondTimestamp);
        assertThat(updatedSnapshot.path()).isSameAs(secondMerklePath);
    }
}
