/*
 * Copyright (C) 2020-2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.contract.impl.test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.node.app.service.contract.impl.ContractServiceImpl;
import com.hedera.node.app.service.contract.impl.schemas.V0490ContractSchema;
import com.hedera.node.app.service.contract.impl.schemas.V0500ContractSchema;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import java.time.InstantSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContractServiceImplTest {
    private final InstantSource instantSource = InstantSource.system();

    @Mock
    private AppContext appContext;

    @Mock
    private SignatureVerifier signatureVerifier;

    @Mock
    private Configuration configuration;

    @Mock
    private Metrics metrics;

    @Mock
    private ContractsConfig contractsConfig;

    private ContractServiceImpl subject;

    @BeforeEach
    void setUp() {
        // given
        when(appContext.instantSource()).thenReturn(instantSource);
        when(appContext.signatureVerifier()).thenReturn(signatureVerifier);

        subject = new ContractServiceImpl(appContext, metrics);
    }

    @Test
    void handlersAreAvailable() {
        assertNotNull(subject.handlers());
    }

    @Test
    void registersContractSchema() {
        final var captor = ArgumentCaptor.forClass(Schema.class);
        final var mockRegistry = mock(SchemaRegistry.class);
        subject.registerSchemas(mockRegistry);
        verify(mockRegistry, times(2)).register(captor.capture());
        final var schemas = captor.getAllValues();
        assertInstanceOf(V0490ContractSchema.class, schemas.getFirst());
        assertInstanceOf(V0500ContractSchema.class, schemas.getLast());
    }
}
