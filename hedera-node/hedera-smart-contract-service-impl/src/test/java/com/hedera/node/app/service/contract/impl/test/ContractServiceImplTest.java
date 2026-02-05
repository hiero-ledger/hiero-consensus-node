// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.node.app.service.contract.impl.ContractServiceImpl;
import com.hedera.node.app.service.contract.impl.calculator.ContractCallFeeCalculator;
import com.hedera.node.app.service.contract.impl.calculator.ContractCreateFeeCalculator;
import com.hedera.node.app.service.contract.impl.calculator.ContractDeleteFeeCalculator;
import com.hedera.node.app.service.contract.impl.calculator.ContractUpdateFeeCalculator;
import com.hedera.node.app.service.contract.impl.calculator.EthereumFeeCalculator;
import com.hedera.node.app.service.contract.impl.handlers.HookDispatchHandler;
import com.hedera.node.app.service.contract.impl.handlers.HookStoreHandler;
import com.hedera.node.app.service.contract.impl.schemas.V0490ContractSchema;
import com.hedera.node.app.service.contract.impl.schemas.V065ContractSchema;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.lifecycle.SchemaRegistry;
import java.time.InstantSource;
import java.util.Set;
import java.util.stream.Collectors;
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

    @Test
    void serviceFeeCalculatorsAreAvailable() {
        final var calculators = subject.serviceFeeCalculators();
        assertEquals(7, calculators.size());
        final Set<Class<? extends ServiceFeeCalculator>> expectedClasses = Set.of(
                HookStoreHandler.FeeCalculator.class,
                HookDispatchHandler.FeeCalculator.class,
                ContractCreateFeeCalculator.class,
                ContractCallFeeCalculator.class,
                ContractDeleteFeeCalculator.class,
                ContractUpdateFeeCalculator.class,
                EthereumFeeCalculator.class);

        final var actualClasses =
                calculators.stream().map(ServiceFeeCalculator::getClass).collect(Collectors.toUnmodifiableSet());

        assertEquals(expectedClasses, actualClasses, "Set must contain exactly the expected calculator classes");
    }
}
