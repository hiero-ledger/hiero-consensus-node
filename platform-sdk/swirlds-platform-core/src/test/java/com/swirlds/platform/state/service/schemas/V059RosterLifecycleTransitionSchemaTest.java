// SPDX-License-Identifier: Apache-2.0
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
