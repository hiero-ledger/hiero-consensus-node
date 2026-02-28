// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_NETWORK_REWARDS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0610TokenSchema.NODE_REWARDS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0700TokenSchema.NODE_PAYMENTS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0710TokenSchema.NATIVE_COIN_DECIMALS_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.token.NativeCoinDecimals;
import com.hedera.hapi.node.state.token.NodePayments;
import com.hedera.hapi.node.state.token.NodeRewards;
import com.hedera.node.app.service.token.CryptoServiceDefinition;
import com.hedera.node.app.service.token.TokenServiceDefinition;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.node.app.service.token.impl.schemas.V0710TokenSchema;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.fixtures.util.LogCaptureExtension;
import com.hedera.node.app.spi.fixtures.util.LoggingSubject;
import com.hedera.node.app.spi.fixtures.util.LoggingTarget;
import com.hedera.node.config.data.NativeCoinConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith({MockitoExtension.class, LogCaptureExtension.class})
class TokenServiceImplTest {

    @LoggingTarget
    private LogCaptor logCaptor;

    @Mock
    private AppContext appContext;

    @LoggingSubject
    private TokenServiceImpl subject;

    @BeforeEach
    void setUp() {
        subject = new TokenServiceImpl(appContext);
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void registerSchemasNullArgsThrow() {
        Assertions.assertThatThrownBy(() -> subject.registerSchemas(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void registerSchemasRegistersTokenSchema() {
        final var schemaRegistry = mock(SchemaRegistry.class);

        subject.registerSchemas(schemaRegistry);
        final var captor = ArgumentCaptor.forClass(Schema.class);
        verify(schemaRegistry, times(5)).register(captor.capture());
        final var schemas = captor.getAllValues();
        assertThat(schemas).hasSize(5);
        assertThat(schemas.getFirst()).isInstanceOf(V0490TokenSchema.class);
        assertThat(schemas.getLast()).isInstanceOf(V0710TokenSchema.class);
    }

    @Test
    void verifyServiceName() {
        assertThat(subject.getServiceName()).isEqualTo("TokenService");
    }

    @Test
    void rpcDefinitions() {
        assertThat(subject.rpcDefinitions())
                .containsExactlyInAnyOrder(CryptoServiceDefinition.INSTANCE, TokenServiceDefinition.INSTANCE);
    }

    @SuppressWarnings("unchecked")
    @Test
    void doGenesisSetupPersistsNativeCoinDecimals() {
        // given
        final var configuration = mock(Configuration.class);
        final var nativeCoinConfig = mock(NativeCoinConfig.class);
        given(nativeCoinConfig.decimals()).willReturn(8);
        given(configuration.getConfigData(NativeCoinConfig.class)).willReturn(nativeCoinConfig);

        final var writableStates = mock(WritableStates.class);
        final var networkRewardsState = mock(WritableSingletonState.class);
        final var nodeRewardsState = mock(WritableSingletonState.class);
        final var nodePaymentsState = mock(WritableSingletonState.class);
        final var nativeCoinDecimalsState = mock(WritableSingletonState.class);

        given(writableStates.getSingleton(STAKING_NETWORK_REWARDS_STATE_ID)).willReturn(networkRewardsState);
        given(writableStates.<NodeRewards>getSingleton(NODE_REWARDS_STATE_ID)).willReturn(nodeRewardsState);
        given(writableStates.<NodePayments>getSingleton(NODE_PAYMENTS_STATE_ID)).willReturn(nodePaymentsState);
        given(writableStates.<NativeCoinDecimals>getSingleton(NATIVE_COIN_DECIMALS_STATE_ID))
                .willReturn(nativeCoinDecimalsState);

        // when
        final var result = subject.doGenesisSetup(writableStates, configuration);

        // then
        assertThat(result).isTrue();

        final var captor = ArgumentCaptor.forClass(NativeCoinDecimals.class);
        verify(nativeCoinDecimalsState).put(captor.capture());
        assertThat(captor.getValue().decimals()).isEqualTo(8);

        assertThat(logCaptor.infoLogs()).anyMatch(msg -> msg.contains("8"));
    }
}
