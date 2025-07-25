// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import static org.assertj.core.api.Assertions.assertThat;

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
        this.delegate = new MapWritableKVState<>(FRUIT_SERVICE_NAME, FRUIT_STATE_KEY, map);
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
}
