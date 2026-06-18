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
        assertThat(config.bucketName()).isEmpty();
        assertThat(config.endpoint()).isEqualTo("https://storage.googleapis.com");
        assertThat(config.region()).isEqualTo("auto");
        assertThat(config.storageClass()).isEqualTo("STANDARD");
        assertThat(config.objectKeyPrefix()).isEqualTo("iss-blocks");
        assertThat(config.issBlockDir()).isEqualTo("data/iss-blocks");
        assertThat(config.precedingBlocks()).isZero();
        assertThat(config.credentialsFileName()).isEqualTo("iss-bucket-credentials.properties");
        assertThat(config.uploadTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.maxRetries()).isEqualTo(3);
    }
}
