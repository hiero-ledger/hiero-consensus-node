// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.hedera.node.app.spi.state.BlockProvenSnapshot;
import com.swirlds.state.MerkleNodeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BlockProvenStateAccessorTest {

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
        final var secondState = mock(MerkleNodeState.class);

        subject.update(firstState);

        final BlockProvenSnapshot snapshot = subject.latestSnapshot().orElseThrow();
        assertThat(snapshot.merkleState()).isSameAs(firstState);
        assertThat(subject.latestState()).contains(firstState);

        subject.update(secondState);

        final BlockProvenSnapshot updatedSnapshot = subject.latestSnapshot().orElseThrow();
        assertThat(updatedSnapshot.merkleState()).isSameAs(secondState);
        assertThat(subject.latestState()).contains(secondState);
    }
}
