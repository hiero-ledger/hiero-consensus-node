// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.validators;

import static com.hedera.node.app.hapi.utils.keys.KeyUtils.IMMUTABILITY_SENTINEL_KEY;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.node.app.service.token.impl.validators.CryptoCreateValidator;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.TokensConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import org.hiero.consensus.model.utility.CommonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CryptoCreateValidatorTest {
    private CryptoCreateValidator subject;
    private TokensConfig tokensConfig;
    private LedgerConfig ledgerConfig;
    private EntitiesConfig entitiesConfig;

    private Configuration configuration;

    private TestConfigBuilder testConfigBuilder;

    @Mock
    private AttributeValidator attributeValidator;

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
    void permitsHollowAccountCreationWithSentinelKey() {
        final var typicalHollowAccountCreation = CryptoCreateTransactionBody.newBuilder()
                .alias(Bytes.wrap(CommonUtils.unhex("abababababababababababababababababababab")))
                .key(IMMUTABILITY_SENTINEL_KEY)
                .build();
        configuration = testConfigBuilder.getOrCreateConfig();
        subject = new CryptoCreateValidator();
        assertDoesNotThrow(() -> subject.validateKey(typicalHollowAccountCreation.key(), attributeValidator, true));
    }

    @Test
    void doesNotPermitHollowAccountCreationWithNonSentinelEmptyKey() {
        final var typicalHollowAccountCreation = CryptoCreateTransactionBody.newBuilder()
                .alias(Bytes.wrap(CommonUtils.unhex("abababababababababababababababababababab")))
                .key(Key.newBuilder().keyList(KeyList.newBuilder().keys(IMMUTABILITY_SENTINEL_KEY)))
                .build();
        configuration = testConfigBuilder.getOrCreateConfig();
        subject = new CryptoCreateValidator();
        assertThrows(
                HandleException.class,
                () -> subject.validateKey(typicalHollowAccountCreation.key(), attributeValidator, true));
    }

    @Test
    void doesNotPermitSentinelEmptyKeyIfNotHollowCreation() {
        final var typicalHollowAccountCreation = CryptoCreateTransactionBody.newBuilder()
                .alias(Bytes.wrap(CommonUtils.unhex("abababababababababababababababababababab")))
                .key(IMMUTABILITY_SENTINEL_KEY)
                .build();
        configuration = testConfigBuilder.getOrCreateConfig();
        subject = new CryptoCreateValidator();
        assertThrows(
                HandleException.class,
                () -> subject.validateKey(typicalHollowAccountCreation.key(), attributeValidator, false));
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
