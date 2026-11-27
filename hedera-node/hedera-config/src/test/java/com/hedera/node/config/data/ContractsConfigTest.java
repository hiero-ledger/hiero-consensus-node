// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ContractsConfigTest {
    @Test
    void selfDestructExternalizationEnabledByDefault() {
        final var config = HederaTestConfigBuilder.createConfig().getConfigData(ContractsConfig.class);

        assertThat(config.selfDestructsExternalizationEnabled()).isTrue();
    }

    @Test
    void selfDestructExternalizationCanBeDisabled() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("contracts.selfDestructs.externalization.enabled", false)
                .getOrCreateConfig()
                .getConfigData(ContractsConfig.class);

        assertThat(config.selfDestructsExternalizationEnabled()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        // The gas throttle is off: maxGasPerSec does not limit a single transaction
        "false, 8000000, 15000000, 15000000",
        // The gas throttle is on: a transaction cannot exceed the bucket of maxGasPerSec
        "true, 8000000, 15000000, 8000000",
        "true, 20000000, 15000000, 15000000"
    })
    void effectiveMaxGasPerTransactionHonorsGasThrottle(
            final boolean throttleByGas,
            final long maxGasPerSec,
            final long maxGasPerTransaction,
            final long expected) {
        final var config = HederaTestConfigBuilder.create()
                .withValue("contracts.throttle.throttleByGas", throttleByGas)
                .withValue("contracts.maxGasPerSec", maxGasPerSec)
                .withValue("contracts.maxGasPerTransaction", maxGasPerTransaction)
                .getOrCreateConfig()
                .getConfigData(ContractsConfig.class);

        assertThat(config.effectiveMaxGasPerTransaction()).isEqualTo(expected);
    }
}
