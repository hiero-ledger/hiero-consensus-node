// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.StateTestBase;
import java.util.HashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * This test verifies the behavior of {@link WrappedWritableKVState}.
 */
class WrappedWritableKVStateTest extends StateTestBase {

    private WritableKVStateBase<ProtoBytes, ProtoBytes> delegate;
    private WrappedWritableKVState<ProtoBytes, ProtoBytes> state;

    @BeforeEach
    public void setUp() {
        final var map = new HashMap<ProtoBytes, ProtoBytes>();
        map.put(A_KEY, APPLE);
        map.put(B_KEY, BANANA);
        this.delegate = new MapWritableKVState<>(FRUIT_STATE_ID, FRUIT_STATE_LABEL, map);
        this.state = Mockito.spy(new WrappedWritableKVState<>(delegate));
    }

    @Test
    @DisplayName("If we commit on the wrapped state, the commit goes to the delegate, but not the" + " backing store")
    void commitGoesToDelegateNotBackingStore() {
        state.put(B_KEY, BLACKBERRY);
        state.put(E_KEY, ELDERBERRY);

        // These values should be in the wrapped state, but not in the delegate
        assertThat(state.get(B_KEY)).isEqualTo(BLACKBERRY); // Has the new value
        assertThat(state.get(E_KEY)).isEqualTo(ELDERBERRY); // Has the new value
        assertThat(delegate.get(B_KEY)).isEqualTo(BANANA); // Has the old value
        assertThat(delegate.get(E_KEY)).isNull(); // Has no value yet

        // After committing, the values MUST be flushed to the delegate
        state.commit();
        assertThat(state.get(B_KEY)).isEqualTo(BLACKBERRY); // Has the new value
        assertThat(state.get(E_KEY)).isEqualTo(ELDERBERRY); // Has the new value
        assertThat(delegate.get(B_KEY)).isEqualTo(BLACKBERRY); // Has the new value
        assertThat(delegate.get(E_KEY)).isEqualTo(ELDERBERRY); // Has the new value
    }

    @Test
    @DisplayName("retarget drops uncommitted mutations and reads from the new delegate")
    void retargetDropsUncommittedAndSwitchesDelegate() {
        state.put(B_KEY, BLACKBERRY);

        final var replacement = new HashMap<ProtoBytes, ProtoBytes>();
        replacement.put(A_KEY, CHERRY);
        final var newDelegate = new MapWritableKVState<>(FRUIT_STATE_ID, FRUIT_STATE_LABEL, replacement);
        state.retarget(newDelegate);

        assertThat(state.get(A_KEY)).isEqualTo(CHERRY);
        assertThat(state.get(B_KEY)).isNull();
        assertThat(delegate.get(B_KEY)).isEqualTo(BANANA);
        assertThat(newDelegate.get(A_KEY)).isEqualTo(CHERRY);
    }

    @Test
    @DisplayName("Wrap commit is the next-txn original even if the delegate has not flushed")
    void commitIsNextTxnOriginalWithoutDelegateFlush() {
        state.put(B_KEY, BLACKBERRY);
        state.commit();

        assertThat(state.getOriginalValue(B_KEY)).isEqualTo(BLACKBERRY);
        assertThat(delegate.get(B_KEY)).isEqualTo(BLACKBERRY);
        assertThat(delegate.getOriginalValue(B_KEY)).isEqualTo(BANANA);
    }

    @Test
    @DisplayName("Wrap apply notifies backend listeners so merkle commit can be deferred")
    void commitNotifiesBackendListeners() {
        final var listener = Mockito.mock(KVChangeListener.class);
        delegate.registerListener(listener);

        state.put(B_KEY, BLACKBERRY);
        state.remove(A_KEY);
        state.commit();

        verify(listener).mapUpdateChange(B_KEY, BLACKBERRY);
        verify(listener).mapDeleteChange(A_KEY);
    }
}
