// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.TransactionConfigModule;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TransactionConfigModuleTest {
    @Mock
    private HandleContext context;

    @Test
    void providesExpectedConfig() {
        final var config = HederaTestConfigBuilder.create().getOrCreateConfig();
        given(context.configuration()).willReturn(config);
        assertSame(config, TransactionConfigModule.provideConfiguration(context));
        assertNotNull(TransactionConfigModule.provideContractsConfig(config));
        assertNotNull(TransactionConfigModule.provideLedgerConfig(config));
        assertNotNull(TransactionConfigModule.provideStakingConfig(config));
        assertNotNull(TransactionConfigModule.provideHederaConfig(config));
        assertNotNull(TransactionConfigModule.provideEntitiesConfig(config));
        assertNotNull(TransactionConfigModule.provideAccountsConfig(config));
    }
}
