// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.api;

import static com.hedera.node.app.service.token.impl.api.TokenServiceApiProvider.TOKEN_SERVICE_API_PROVIDER;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKEN_RELS_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.api.TokenServiceApiImpl;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenServiceApiProviderTest extends CryptoTokenHandlerTestBase {

    private static final Configuration DEFAULT_CONFIG = HederaTestConfigBuilder.createConfig();

    @Mock
    private WritableStates writableStates;

    @Test
    void hasTokenServiceName() {
        assertEquals(TokenService.NAME, TOKEN_SERVICE_API_PROVIDER.serviceName());
    }

    @Test
    void instantiatesApiImpl() {
        given(writableStates.get(ACCOUNTS_STATE_ID))
                .willReturn(new MapWritableKVState<>(ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL));
        assertInstanceOf(
                TokenServiceApiImpl.class,
                TOKEN_SERVICE_API_PROVIDER.newInstance(DEFAULT_CONFIG, writableStates, writableEntityCounters));
    }

    @Test
    void testsCustomFeesByCreatingStep() {
        given(writableStates.get(ACCOUNTS_STATE_ID))
                .willReturn(new MapWritableKVState<>(ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL));
        final var api = TOKEN_SERVICE_API_PROVIDER.newInstance(DEFAULT_CONFIG, writableStates, writableEntityCounters);
        assertFalse(api.checkForCustomFees(CryptoTransferTransactionBody.DEFAULT));
    }

    @Test
    void returnsFalseOnAnyStepCreationFailure() {
        given(writableStates.get(anyInt())).willReturn(null);
        given(writableStates.get(ACCOUNTS_STATE_ID))
                .willReturn(new MapWritableKVState<>(ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL));
        given(writableStates.get(TOKEN_RELS_STATE_ID)).willThrow(IllegalStateException.class);
        final var api = TOKEN_SERVICE_API_PROVIDER.newInstance(DEFAULT_CONFIG, writableStates, writableEntityCounters);
        assertFalse(api.checkForCustomFees(CryptoTransferTransactionBody.DEFAULT));
    }
}
