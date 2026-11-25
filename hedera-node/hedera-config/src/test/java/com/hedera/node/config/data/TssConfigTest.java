// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.validation.ConfigViolationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TssConfigTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 4, 100})
    void realHintsHistoryRejectsUnattainableOrInsufficientWitnessThresholds(final int divisor) {
        final var builder = HederaTestConfigBuilder.create()
                .withValue("tss.forceMockSignatures", false)
                .withValue("tss.signingThresholdDivisor", divisor);
        final var failure = assertThrows(ConfigViolationException.class, builder::getOrCreateConfig);
        assertEquals(
                "tss.signingThresholdDivisor",
                failure.getViolations().getFirst().getPropertyName());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void realHintsHistoryAcceptsWitnessCompatibleThresholds(final int divisor) {
        assertDoesNotThrow(() -> HederaTestConfigBuilder.create()
                .withValue("tss.forceMockSignatures", false)
                .withValue("tss.signingThresholdDivisor", divisor)
                .getOrCreateConfig());
    }

    @ParameterizedTest
    @ValueSource(strings = {"tss.forceMockSignatures", "tss.historyEnabled", "tss.hintsEnabled"})
    void profilesWithoutDurableWitnessesKeepTheirExistingThresholdOptions(final String property) {
        assertDoesNotThrow(() -> HederaTestConfigBuilder.create()
                .withValue("tss.forceMockSignatures", false)
                .withValue("tss.signingThresholdDivisor", 4)
                .withValue(property, property.equals("tss.forceMockSignatures"))
                .getOrCreateConfig());
    }
}
