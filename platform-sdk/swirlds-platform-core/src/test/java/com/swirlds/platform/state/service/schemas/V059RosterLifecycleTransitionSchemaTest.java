/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
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

package com.swirlds.platform.state.service.schemas;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.platform.state.PlatformState;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V059RosterLifecycleTransitionSchemaTest {
    @Mock
    private MigrationContext ctx;

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableSingletonState<PlatformState> stateSingleton;

    private V059RosterLifecycleTransitionSchema subject;

    @BeforeEach
    void setUp() {
        subject = new V059RosterLifecycleTransitionSchema();
    }

    @Test
    void migrateNullsOutAddressBooksAtBoundary() {
        final var oldState = PlatformState.newBuilder()
                .previousAddressBook(com.hedera.hapi.platform.state.AddressBook.DEFAULT)
                .addressBook(com.hedera.hapi.platform.state.AddressBook.DEFAULT)
                .build();
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<PlatformState>getSingleton("PLATFORM_STATE")).willReturn(stateSingleton);
        given(stateSingleton.get()).willReturn(oldState);
        subject.migrate(ctx);
        verify(stateSingleton).put(PlatformState.DEFAULT);
    }
}
