// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.systemtask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.systemtask.schemas.V0690SystemTaskSchema;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemTaskServiceTest {
    @Captor
    ArgumentCaptor<Schema<?>> captor;

    @Test
    void registersSchema(@Mock final SchemaRegistry registry) {
        final var svc = new SystemTaskService();
        assertThat(svc.getServiceName()).isEqualTo(SystemTaskService.NAME);
        svc.registerSchemas(registry);
        verify(registry, times(1)).register(captor.capture());
        final var schemas = captor.getAllValues();
        assertThat(schemas.get(0)).isInstanceOf(V0690SystemTaskSchema.class);
    }
}
