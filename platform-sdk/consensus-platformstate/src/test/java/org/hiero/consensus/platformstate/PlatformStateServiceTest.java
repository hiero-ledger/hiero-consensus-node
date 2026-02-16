// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.platformstate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.BDDMockito.given;

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

    private final PlatformStateService subject = new PlatformStateService();

    @Test
    void registersTwoSchema() {
        final ArgumentCaptor<Schema> captor = ArgumentCaptor.forClass(Schema.class);
        given(registry.register(captor.capture())).willReturn(registry);
        subject.registerSchemas(registry);
        final var schemas = captor.getAllValues();
        assertEquals(1, schemas.size(), "Wrong number of registered schemas");
        assertInstanceOf(V0540PlatformStateSchema.class, schemas.getFirst());
    }
}
