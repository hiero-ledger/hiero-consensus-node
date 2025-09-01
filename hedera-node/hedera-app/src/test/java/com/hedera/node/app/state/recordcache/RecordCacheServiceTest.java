// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.recordcache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.state.recordcache.schemas.V0490RecordCacheSchema;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class RecordCacheServiceTest {
    @Captor
    ArgumentCaptor<Schema> captor;

    @Test
    void constructor() {
        final var svc = new RecordCacheService();
        assertThat(svc.getServiceName()).isEqualTo(RecordCacheService.NAME);
    }

    @Test
    void schema(@Mock final SchemaRegistry registry) {
        final var svc = new RecordCacheService();
        svc.registerSchemas(registry);
        verify(registry, times(1)).register(captor.capture());
        final var schemas = captor.getAllValues();
        assertThat(schemas.get(0)).isInstanceOf(V0490RecordCacheSchema.class);

        assertThat(schemas.get(0).statesToCreate()).hasSize(1);
        assertThat(schemas.get(0).statesToRemove()).hasSize(0);
    }
}
