// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.node.app.spi.state.BlockProvenSnapshot;
import com.swirlds.state.State;
import com.swirlds.state.StateLifecycleManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BlockProvenStateAccessorTest {

    private BlockProvenStateAccessor subject;
    private StateLifecycleManager<State, Object> stateLifecycleManager;

    @BeforeEach
    void setUp() {
        // Mockito can only create a raw mock; we cast to the parameterized type for compilation.
        @SuppressWarnings("unchecked")
        final var mocked = (StateLifecycleManager<State, Object>) mock(StateLifecycleManager.class);
        stateLifecycleManager = mocked;
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
        final var state = mock(State.class);
        when(state.isDestroyed()).thenReturn(false);
        when(state.isMutable()).thenReturn(false);
        when(stateLifecycleManager.getLatestImmutableState()).thenReturn(state);
        final BlockProvenSnapshot snapshot = subject.latestSnapshot().orElseThrow();
        assertThat(snapshot.state()).isSameAs(state);
        assertThat(subject.latestState()).contains(state);
    }
}
