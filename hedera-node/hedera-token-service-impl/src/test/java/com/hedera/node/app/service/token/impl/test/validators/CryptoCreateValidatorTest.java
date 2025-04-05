// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.validators;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.node.app.service.token.impl.validators.CryptoCreateValidator;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CryptoCreateValidatorTest {
    private CryptoCreateValidator subject;
    private LedgerConfig ledgerConfig;

    private Configuration configuration;

    private TestConfigBuilder testConfigBuilder;

    @BeforeEach
    void setUp() {
        subject = new CryptoCreateValidator();
        testConfigBuilder = HederaTestConfigBuilder.create()
                .withValue("cryptoCreateWithAlias.enabled", true)
                .withValue("ledger.maxAutoAssociations", 5000)
                .withValue("tokens.maxPerAccount", 1000);
    }

    @Test
    void checkTooManyAutoAssociations() {
        configuration = testConfigBuilder.getOrCreateConfig();
        getConfigs(configuration);
        assertTrue(subject.tooManyAutoAssociations(5001, ledgerConfig));
        assertFalse(subject.tooManyAutoAssociations(3000, ledgerConfig));
        assertFalse(subject.tooManyAutoAssociations(-1, ledgerConfig));
    }

    @Test
    void checkDiffTooManyAutoAssociations() {
        configuration = testConfigBuilder.getOrCreateConfig();
        getConfigs(configuration);
        assertFalse(subject.tooManyAutoAssociations(1001, ledgerConfig));
        assertFalse(subject.tooManyAutoAssociations(999, ledgerConfig));
        assertFalse(subject.tooManyAutoAssociations(-1, ledgerConfig));
        assertTrue(subject.tooManyAutoAssociations(-2, ledgerConfig));
        assertTrue(subject.tooManyAutoAssociations(-100000, ledgerConfig));
    }

    private void getConfigs(Configuration configuration) {
        ledgerConfig = configuration.getConfigData(LedgerConfig.class);
    }
}
