// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test;

import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.LEDGER_CONFIGURATION_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.node.app.service.clpr.impl.ReadableLedgerConfigurationStoreImpl;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReadableLedgerConfigurationStoreImplTest {

    @Mock
    private ReadableStates states;

    @Mock
    private ReadableSingletonState<ClprLedgerConfiguration> singletonState;

    private ReadableLedgerConfigurationStoreImpl subject;

    @BeforeEach
    void setUp() {
        given(states.<ClprLedgerConfiguration>getSingleton(LEDGER_CONFIGURATION_STATE_ID))
                .willReturn(singletonState);
        subject = new ReadableLedgerConfigurationStoreImpl(states);
    }

    @Test
    @DisplayName("should return configuration")
    void returnsConfiguration() {
        final var config = ClprLedgerConfiguration.newBuilder()
                .protocolVersion(1)
                .chainId("hiero:unit")
                .build();
        given(singletonState.get()).willReturn(config);
        assertThat(subject.getConfiguration()).isEqualTo(config);
    }
}
