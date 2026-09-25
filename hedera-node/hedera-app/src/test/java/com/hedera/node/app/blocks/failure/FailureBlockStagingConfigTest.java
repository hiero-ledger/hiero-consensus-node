// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.failure;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.config.data.FailureBlockStagingConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FailureBlockStagingConfigTest {

    @Test
    void hasExpectedDefaults() {
        final var config = HederaTestConfigBuilder.create()
                .withConfigDataType(FailureBlockStagingConfig.class)
                .getOrCreateConfig()
                .getConfigData(FailureBlockStagingConfig.class);

        assertThat(config.issBlockStagingEnabled()).isFalse();
        assertThat(config.triageStagingEnabled()).isFalse();
        assertThat(config.issBlockDir()).isEqualTo("/opt/hgcapp/issBlocks");
        assertThat(config.precedingBlocks()).isZero();
        assertThat(config.captureTimeout()).isEqualTo(Duration.ofSeconds(30));
    }
}
