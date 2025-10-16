// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.systemtask.dispatcher;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.state.systemtask.KeyPropagation;
import com.hedera.hapi.node.state.systemtask.SystemTask;
import com.hedera.node.app.spi.systemtasks.SystemTaskContext;
import com.hedera.node.app.spi.systemtasks.SystemTaskHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemTaskDispatcherTest {

    @Mock
    private SystemTaskHandler handler;

    @Mock
    private SystemTaskContext context;

    @Test
    void dispatchesToSupportingHandler() {
        final var task = SystemTask.newBuilder()
                .keyPropagation(KeyPropagation.newBuilder().build())
                .build();
        when(context.currentTask()).thenReturn(task);
        when(handler.supports(task)).thenReturn(true);

        final var subject = new SystemTaskDispatcher(List.of(handler));
        subject.dispatch(context);

        verify(handler).handle(context);
    }

    @Test
    void throwsIfNoHandlerSupportsTask() {
        final var task = SystemTask.newBuilder()
                .keyPropagation(KeyPropagation.newBuilder().build())
                .build();
        when(context.currentTask()).thenReturn(task);
        when(handler.supports(task)).thenReturn(false);

        final var subject = new SystemTaskDispatcher(List.of(handler));
        assertThrows(UnsupportedOperationException.class, () -> subject.dispatch(context));
    }
}
