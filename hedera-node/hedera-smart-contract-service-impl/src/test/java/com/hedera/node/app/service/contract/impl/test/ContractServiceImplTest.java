// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.node.app.service.contract.impl.ContractServiceImpl;
import com.hedera.node.app.service.contract.impl.schemas.V0490ContractSchema;
import com.hedera.node.app.service.contract.impl.schemas.V065ContractSchema;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.ids.EntityIdFactory;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.lifecycle.SchemaRegistry;
import java.time.InstantSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    private Metrics metrics;

    @Mock
    private EntityIdFactory entityIdFactory;

    private ContractServiceImpl subject;

    @BeforeEach
    void setUp() {
        // given
        when(appContext.instantSource()).thenReturn(instantSource);
        when(appContext.signatureVerifier()).thenReturn(signatureVerifier);
        when(appContext.idFactory()).thenReturn(entityIdFactory);

        subject = new ContractServiceImpl(appContext, metrics);
    }

    @Test
    void handlersAreAvailable() {
        assertNotNull(subject.handlers());
    }

    @Test
    void registersContractSchema() {
        final var mockRegistry = mock(SchemaRegistry.class);
        subject.registerSchemas(mockRegistry);
        verify(mockRegistry).registerAll(isA(V0490ContractSchema.class), isA(V065ContractSchema.class));
    }
}
