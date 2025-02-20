// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.test;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.service.networkadmin.NetworkService;
import com.hedera.node.app.service.networkadmin.impl.NetworkServiceImpl;
import com.swirlds.state.lifecycle.SchemaRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NetworkServiceImplTest {
    @Mock
    private SchemaRegistry registry;

    private NetworkServiceImpl subject;

    @BeforeEach
    void setUp() {
        subject = new NetworkServiceImpl();
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void registerSchemasNullArgsThrow() {
        assertThatThrownBy(() -> subject.registerSchemas(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void registerSchemasRegistersTopicSchema() {
        final var schemaRegistry = mock(SchemaRegistry.class);

        subject.registerSchemas(schemaRegistry);
        verify(schemaRegistry).register(notNull());
    }

    @Test
    void testSpi() {
        // when
        final NetworkService service = new NetworkServiceImpl();

        // then
        Assertions.assertNotNull(service, "We must always receive an instance");
        Assertions.assertEquals(
                NetworkServiceImpl.class,
                service.getClass(),
                "We must always receive an instance of type " + NetworkServiceImpl.class.getName());
    }
}
