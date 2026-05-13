// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import org.junit.jupiter.api.Test;

class BlockNodeConnectionConfigTest {

    @Test
    void connectionStallThresholdMillisDefaultsToFiveSeconds() {
        final var config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockNodeConnectionConfig.class)
                .getOrCreateConfig()
                .getConfigData(BlockNodeConnectionConfig.class);

        assertThat(config.connectionStallThresholdMillis()).isEqualTo(5000);
    }
}
