/*
 * Copyright (C) 2022-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.state.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

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
    private ReadableKVState<String, String> delegate;

    @Mock
    private Iterator<String> keys;

    private WrappedReadableKVState<String, String> state;

    @BeforeEach
    void setUp() {
        openMocks(this);
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
