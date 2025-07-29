// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.swirlds.state.test.fixtures.StateTestBase;
import java.util.Iterator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/**
 * This test verifies behavior of a {@link WrappedReadableKVState}.
 */
class WrappedReadableKVStateTest extends StateTestBase {
    @Mock
    private ReadableKVState<ProtoBytes, ProtoBytes> delegate;

    @Mock
    private Iterator<ProtoBytes> keys;

    private WrappedReadableKVState<ProtoBytes, ProtoBytes> state;

    @BeforeEach
    void setUp() {
        openMocks(this);
        when(delegate.getServiceName()).thenReturn(FRUIT_SERVICE_NAME);
        when(delegate.getStateKey()).thenReturn(FRUIT_STATE_KEY);
        state = new WrappedReadableKVState<>(delegate);
    }

    @Test
    void testReadFromDelegate() {
        when(delegate.get(A_KEY)).thenReturn(APPLE);

        assertThat(state.get(A_KEY)).isEqualTo(APPLE);
    }

    @Test
    void testIterateFromDataSource() {
        when(delegate.keys()).thenReturn(keys);

        assertThat(state.keys()).isEqualTo(keys);
    }
}
