// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.config.data.FailureBlockUploadConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FailureBlockUploadConfigTest {

    @Test
    void hasExpectedDefaults() {
        final var config = HederaTestConfigBuilder.create()
                .withConfigDataType(FailureBlockUploadConfig.class)
                .getOrCreateConfig()
                .getConfigData(FailureBlockUploadConfig.class);

        assertThat(config.issBlockUploadEnabled()).isFalse();
        assertThat(config.triageUploadEnabled()).isFalse();
        assertThat(config.issBlockDir()).isEqualTo("/opt/hgcapp/issBlocks");
        assertThat(config.precedingBlocks()).isZero();
        assertThat(config.captureTimeout()).isEqualTo(Duration.ofSeconds(30));
    }
}
