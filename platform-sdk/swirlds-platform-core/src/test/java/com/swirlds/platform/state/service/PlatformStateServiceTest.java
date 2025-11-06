// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.service;

import static com.swirlds.platform.state.service.PlatformStateService.PLATFORM_STATE_SERVICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.BDDMockito.given;

import com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema;
import com.swirlds.platform.state.service.schemas.V0640PlatformStateSchema;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlatformStateServiceTest {
    @Mock
    private SchemaRegistry registry;

    @Test
    void registersTwoSchema() {
        final ArgumentCaptor<Schema> captor = ArgumentCaptor.forClass(Schema.class);
        given(registry.register(captor.capture())).willReturn(registry);
        PLATFORM_STATE_SERVICE.registerSchemas(registry);
        final var schemas = captor.getAllValues();
        assertEquals(2, schemas.size(), "Expected two schemas to be registered, but found: " + schemas.size());
        assertInstanceOf(V0540PlatformStateSchema.class, schemas.getFirst());
        assertInstanceOf(V0640PlatformStateSchema.class, schemas.getLast());
    }
}
