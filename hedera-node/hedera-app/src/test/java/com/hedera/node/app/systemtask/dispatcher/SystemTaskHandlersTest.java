// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.systemtask.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.state.systemtask.KeyPropagation;
import com.hedera.hapi.node.state.systemtask.SystemTask;
import com.hedera.node.app.services.ServicesRegistry;
import com.hedera.node.app.spi.RpcService;
import com.hedera.node.app.spi.systemtasks.SystemTaskContext;
import com.hedera.node.app.spi.systemtasks.SystemTaskHandler;
import com.hedera.node.app.systemtask.SystemTaskHandlers;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.Service;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemTaskHandlersTest {
    @Mock
    private SchemaRegistry schemaRegistry;

    @Mock
    private Service nonRpcService;

    @Mock
    private RpcService rpcService;

    @Mock
    private ServicesRegistry registry;

    @Mock
    private SystemTaskHandler otherHandler;

    @Mock
    private SystemTaskHandler targetHandler;

    @Mock
    private SystemTaskContext context;

    private SystemTaskHandlers subject;

    @BeforeEach
    void setUp() {
        given(rpcService.systemTaskHandlers()).willReturn(Set.of(otherHandler, targetHandler));
        given(registry.registrations())
                .willReturn(Set.of(
                        new ServicesRegistry.Registration(rpcService, schemaRegistry),
                        new ServicesRegistry.Registration(nonRpcService, schemaRegistry)));
        subject = new SystemTaskHandlers(registry);
    }

    @Test
    void dispatchesToSupportingHandler() {
        final var task = SystemTask.newBuilder()
                .keyPropagation(KeyPropagation.newBuilder().build())
                .build();
        when(context.currentTask()).thenReturn(task);
        when(targetHandler.supports(task)).thenReturn(true);

        final var registration = subject.getRegistration(task);

        assertNotNull(registration);
        assertEquals(targetHandler, registration.handler());
    }

    @Test
    void throwsIfNoHandlerSupportsTask() {
        final var task = SystemTask.newBuilder()
                .keyPropagation(KeyPropagation.newBuilder().build())
                .build();
        when(context.currentTask()).thenReturn(task);
        when(targetHandler.supports(task)).thenReturn(false);

        final var registration = subject.getRegistration(task);

        assertNull(registration);
    }
}
