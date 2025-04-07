// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.service.token.CryptoServiceDefinition;
import com.hedera.node.app.service.token.TokenServiceDefinition;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.node.app.service.token.impl.schemas.V0500TokenSchema;
import com.hedera.node.app.service.token.impl.schemas.V0610TokenSchema;
import com.hedera.node.app.spi.AppContext;
import com.swirlds.state.lifecycle.EntityIdFactory;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenServiceImplTest {

    @Mock
    private AppContext appContext;

    @Mock
    private EntityIdFactory idFactory;

    private TokenServiceImpl subject;

    @BeforeEach
    void setUp() {
        given(appContext.idFactory()).willReturn(idFactory);
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
        verify(schemaRegistry, times(4)).register(captor.capture());
        final var schemas = captor.getAllValues();
        assertThat(schemas).hasSize(4);
        assertThat(schemas.getFirst()).isInstanceOf(V0490TokenSchema.class);
        assertThat(schemas.get(1)).isInstanceOf(V0500TokenSchema.class);
        assertThat(schemas.getLast()).isInstanceOf(V0610TokenSchema.class);
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
}
