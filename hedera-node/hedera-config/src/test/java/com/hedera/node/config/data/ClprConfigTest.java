// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import org.junit.jupiter.api.Test;

class ClprConfigTest {

    @Test
    void disabledByDefault() {
        final var config = HederaTestConfigBuilder.createConfig().getConfigData(ClprConfig.class);

        assertThat(config.enabled()).isFalse();
    }
}
