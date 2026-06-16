// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.config.data.IssBlockUploadConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.config.types.UploaderBackend;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class IssBlockUploadConfigTest {

    @Test
    void hasExpectedDefaults() {
        final var config = HederaTestConfigBuilder.create()
                .withConfigDataType(IssBlockUploadConfig.class)
                .getOrCreateConfig()
                .getConfigData(IssBlockUploadConfig.class);

        assertThat(config.enabled()).isFalse();
        assertThat(config.backend()).isEqualTo(UploaderBackend.BUCKY);
        assertThat(config.bucketName()).isEmpty();
        assertThat(config.endpoint()).isEqualTo("https://storage.googleapis.com");
        assertThat(config.region()).isEqualTo("auto");
        assertThat(config.storageClass()).isEqualTo("STANDARD");
        assertThat(config.objectKeyPrefix()).isEqualTo("iss-blocks");
        assertThat(config.credentialsFileName()).isEqualTo("iss-bucket-credentials.properties");
        assertThat(config.uploadTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.maxRetries()).isEqualTo(3);
    }
}
