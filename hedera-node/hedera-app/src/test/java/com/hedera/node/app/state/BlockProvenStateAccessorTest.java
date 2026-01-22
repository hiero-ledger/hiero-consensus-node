// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.node.app.spi.state.BlockProvenSnapshot;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.StateLifecycleManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BlockProvenStateAccessorTest {

    private BlockProvenStateAccessor subject;
    private StateLifecycleManager stateLifecycleManager;

    @BeforeEach
    void setUp() {
        stateLifecycleManager = mock(StateLifecycleManager.class);
        subject = new BlockProvenStateAccessor(stateLifecycleManager);
    }

    @Test
    void latestSnapshotEmptyWhenLifecycleManagerNotInitialized() {
        when(stateLifecycleManager.getLatestImmutableState()).thenThrow(new IllegalStateException("Not initialized"));
        assertThat(subject.latestSnapshot()).isEmpty();
        assertThat(subject.latestState()).isEmpty();
    }

    @Test
    void latestSnapshotReturnsStateFromLifecycleManager() {
        final var state = mock(MerkleNodeState.class);
        when(stateLifecycleManager.getLatestImmutableState()).thenReturn(state);
        final BlockProvenSnapshot snapshot = subject.latestSnapshot().orElseThrow();
        assertThat(snapshot.merkleState()).isSameAs(state);
        assertThat(subject.latestState()).contains(state);
    }
}
