// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.StateTestBase;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * This test verifies behavior specific to a {@link WrappedWritableKVState}.
 */
class WrappedWritableSingletonTest extends StateTestBase {
    private WritableSingletonState<ProtoBytes> delegate;

    protected AtomicReference<ProtoBytes> backingStore = new AtomicReference<>(AUSTRALIA);

    private WritableSingletonStateBase<ProtoBytes> createState() {
        delegate = new FunctionWritableSingletonState<>(
                COUNTRY_STATE_KEY, COUNTRY_SERVICE_NAME, backingStore::get, backingStore::set);
        return new WrappedWritableSingletonState<>(delegate);
    }

    @Test
    @DisplayName("If we commit on the wrapped state, the commit goes to the delegate, but not the" + " backing store")
    void commitGoesToDelegateNotBackingStore() {
        final var state = createState();
        state.put(BRAZIL);

        // The value should be in the wrapped state, but not in the delegate
        assertThat(state.get()).isEqualTo(BRAZIL); // Has the new value
        assertThat(delegate.get()).isEqualTo(AUSTRALIA); // Has the old value

        // After committing, the values MUST be flushed to the delegate
        state.commit();
        assertThat(state.get()).isEqualTo(BRAZIL); // Has the new value
        assertThat(delegate.get()).isEqualTo(BRAZIL); // Has the new value
    }
}
