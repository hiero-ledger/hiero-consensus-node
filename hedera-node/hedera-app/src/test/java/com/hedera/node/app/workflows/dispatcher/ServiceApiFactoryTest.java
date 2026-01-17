// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.dispatcher;

import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_LABEL;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.api.TokenServiceApiProvider.TOKEN_SERVICE_API_PROVIDER;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_LABEL;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.store.ServiceApiFactory;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ServiceApiFactoryTest {

    private static final Configuration DEFAULT_CONFIG = HederaTestConfigBuilder.createConfig();

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableStates entityIdStates;

    @Mock
    private SavepointStackImpl stack;

    private ServiceApiFactory subject;

    @BeforeEach
    void setUp() {
        subject =
                new ServiceApiFactory(stack, DEFAULT_CONFIG, Map.of(TokenServiceApi.class, TOKEN_SERVICE_API_PROVIDER));
    }

    @Test
    void throwsIfNoSuchProvider() {
        assertThrows(IllegalArgumentException.class, () -> subject.getApi(NonExistentApi.class));
    }

    @Test
    void canCreateTokenServiceApi() {
        given(stack.getWritableStates(TokenService.NAME)).willReturn(writableStates);
        given(writableStates.get(anyInt()))
                .willReturn(new MapWritableKVState<>(ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL));
        given(stack.getWritableStates(EntityIdService.NAME)).willReturn(entityIdStates);
        given(entityIdStates.getSingleton(ENTITY_ID_STATE_ID))
                .willReturn(new FunctionWritableSingletonState<>(
                        ENTITY_ID_STATE_ID, ENTITY_ID_STATE_LABEL, () -> null, (a) -> {}));
        given(entityIdStates.getSingleton(ENTITY_COUNTS_STATE_ID))
                .willReturn(new FunctionWritableSingletonState<>(
                        ENTITY_COUNTS_STATE_ID, ENTITY_COUNTS_STATE_LABEL, () -> null, (a) -> {}));
        assertNotNull(subject.getApi(TokenServiceApi.class));
    }

    private static class NonExistentApi {}
}
