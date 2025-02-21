// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.service.token.CryptoServiceDefinition;
import com.hedera.node.app.service.token.TokenServiceDefinition;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.node.app.service.token.impl.schemas.V0500TokenSchema;
import com.hedera.node.app.service.token.impl.schemas.V0530TokenSchema;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TokenServiceImplTest {

    private TokenServiceImpl subject;

    @BeforeEach
    void setUp() {
        subject = new TokenServiceImpl();
    }

    @Test
    void defaultConstructor() {
        assertThat(new TokenServiceImpl()).isNotNull();
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
        verify(schemaRegistry, times(3)).register(captor.capture());
        final var schemas = captor.getAllValues();
        assertThat(schemas).hasSize(3);
        assertThat(schemas.getFirst()).isInstanceOf(V0490TokenSchema.class);
        assertThat(schemas.get(1)).isInstanceOf(V0500TokenSchema.class);
        assertThat(schemas.getLast()).isInstanceOf(V0530TokenSchema.class);
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
