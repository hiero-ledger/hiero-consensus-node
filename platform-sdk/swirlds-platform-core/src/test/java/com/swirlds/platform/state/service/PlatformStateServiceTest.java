/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.state.service;

import static com.swirlds.platform.state.service.PlatformStateService.PLATFORM_STATE_SERVICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.BDDMockito.given;

import com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema;
import com.swirlds.platform.state.service.schemas.V059RosterLifecycleTransitionSchema;
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
    void registersOneSchema() {
        final ArgumentCaptor<Schema> captor = ArgumentCaptor.forClass(Schema.class);
        given(registry.register(captor.capture())).willReturn(registry);
        PLATFORM_STATE_SERVICE.registerSchemas(registry);
        final var schemas = captor.getAllValues();
        assertEquals(2, schemas.size());
        assertInstanceOf(V0540PlatformStateSchema.class, schemas.getFirst());
        assertInstanceOf(V059RosterLifecycleTransitionSchema.class, schemas.getLast());
    }
}
