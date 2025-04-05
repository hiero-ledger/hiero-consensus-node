// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.validators;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.node.app.service.token.impl.validators.CryptoCreateValidator;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.TokensConfig;
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
    private TokensConfig tokensConfig;
    private LedgerConfig ledgerConfig;
    private EntitiesConfig entitiesConfig;

    private Configuration configuration;

    private TestConfigBuilder testConfigBuilder;

    @BeforeEach
    void setUp() {
        subject = new CryptoCreateValidator();
        testConfigBuilder = HederaTestConfigBuilder.create()
                .withValue("ledger.maxAutoAssociations", 5000)
                .withValue("entities.limitTokenAssociations", false)
                .withValue("tokens.maxPerAccount", 1000)
                .withValue("entities.unlimitedAutoAssociations", true);
    }

    @Test
    void checkTooManyAutoAssociations() {
        testConfigBuilder = testConfigBuilder.withValue("entities.unlimitedAutoAssociationsEnabled", true);
        configuration = testConfigBuilder.getOrCreateConfig();
        getConfigs(configuration);
        assertTrue(subject.tooManyAutoAssociations(5001, ledgerConfig, entitiesConfig, tokensConfig));
        assertFalse(subject.tooManyAutoAssociations(3000, ledgerConfig, entitiesConfig, tokensConfig));
        assertFalse(subject.tooManyAutoAssociations(-1, ledgerConfig, entitiesConfig, tokensConfig));
    }

    @Test
    void checkDiffTooManyAutoAssociations() {
        testConfigBuilder = testConfigBuilder
                .withValue("entities.limitTokenAssociations", true)
                .withValue("entities.unlimitedAutoAssociationsEnabled", true);
        configuration = testConfigBuilder.getOrCreateConfig();
        getConfigs(configuration);
        assertTrue(subject.tooManyAutoAssociations(1001, ledgerConfig, entitiesConfig, tokensConfig));
        assertFalse(subject.tooManyAutoAssociations(999, ledgerConfig, entitiesConfig, tokensConfig));
        assertFalse(subject.tooManyAutoAssociations(-1, ledgerConfig, entitiesConfig, tokensConfig));
        assertTrue(subject.tooManyAutoAssociations(-2, ledgerConfig, entitiesConfig, tokensConfig));
        assertTrue(subject.tooManyAutoAssociations(-100000, ledgerConfig, entitiesConfig, tokensConfig));
    }

    private void getConfigs(Configuration configuration) {
        tokensConfig = configuration.getConfigData(TokensConfig.class);
        ledgerConfig = configuration.getConfigData(LedgerConfig.class);
        entitiesConfig = configuration.getConfigData(EntitiesConfig.class);
    }
}
