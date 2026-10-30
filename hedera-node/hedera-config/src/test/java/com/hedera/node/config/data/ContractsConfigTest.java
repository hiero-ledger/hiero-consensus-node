// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import org.junit.jupiter.api.Test;

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
}
