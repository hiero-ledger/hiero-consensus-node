/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.token.impl.test.api;

import static com.hedera.node.app.service.token.impl.api.TokenServiceApiProvider.TOKEN_SERVICE_API_PROVIDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.api.TokenServiceApiImpl;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
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
        given(writableStates.get("ACCOUNTS")).willReturn(new MapWritableKVState<>(TokenService.NAME, "ACCOUNTS"));
        assertInstanceOf(
                TokenServiceApiImpl.class,
                TOKEN_SERVICE_API_PROVIDER.newInstance(DEFAULT_CONFIG, writableStates, writableEntityCounters));
    }

    @Test
    void testsCustomFeesByCreatingStep() {
        given(writableStates.get("ACCOUNTS")).willReturn(new MapWritableKVState<>(TokenService.NAME, "ACCOUNTS"));
        final var api = TOKEN_SERVICE_API_PROVIDER.newInstance(DEFAULT_CONFIG, writableStates, writableEntityCounters);
        assertFalse(api.checkForCustomFees(CryptoTransferTransactionBody.DEFAULT));
    }

    @Test
    void returnsFalseOnAnyStepCreationFailure() {
        given(writableStates.get(any())).willReturn(null);
        given(writableStates.get("ACCOUNTS")).willReturn(new MapWritableKVState<>(TokenService.NAME, "ACCOUNTS"));
        given(writableStates.get(V0490TokenSchema.TOKEN_RELS_KEY)).willThrow(IllegalStateException.class);
        final var api = TOKEN_SERVICE_API_PROVIDER.newInstance(DEFAULT_CONFIG, writableStates, writableEntityCounters);
        assertFalse(api.checkForCustomFees(CryptoTransferTransactionBody.DEFAULT));
    }
}
