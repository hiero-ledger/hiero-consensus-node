// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.config.types.BlockStreamWriterMode;
import com.hedera.node.config.types.StreamMode;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BlockStreamConfigTest {

    @Test
    void maxBlockSizeBytesDefaultsTo100Mebibytes() {
        final var config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .getOrCreateConfig()
                .getConfigData(BlockStreamConfig.class);

        assertThat(config.maxBlockSizeBytes()).isEqualTo(100L * 1024 * 1024);
    }

    @Test
    void maxBlockSizeLimitEnabledByDefault() {
        final var config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .getOrCreateConfig()
                .getConfigData(BlockStreamConfig.class);

        assertThat(config.maxBlockSizeLimitEnabled()).isTrue();
    }

    @Test
    void streamToBlockNodesFalseWhenFileWriterAndWrbDisabled() {
        assertThat(configWith(BlockStreamWriterMode.FILE, false).streamToBlockNodes())
                .isFalse();
    }

    @Test
    void streamToBlockNodesFalseWhenFileWriterAndWrbEnabled() {
        assertThat(configWith(BlockStreamWriterMode.FILE, true).streamToBlockNodes())
                .isFalse();
    }

    @Test
    void streamToBlockNodesTrueWhenGrpcWriter() {
        assertThat(configWith(BlockStreamWriterMode.GRPC, false).streamToBlockNodes())
                .isTrue();
    }

    @Test
    void streamToBlockNodesTrueWhenFileAndGrpcWriter() {
        assertThat(configWith(BlockStreamWriterMode.FILE_AND_GRPC, false).streamToBlockNodes())
                .isTrue();
    }

    private static BlockStreamConfig configWith(BlockStreamWriterMode writerMode, boolean streamWrappedRecordBlocks) {
        return new BlockStreamConfig(
                StreamMode.BOTH,
                writerMode,
                "/opt/hgcapp/blockStreams",
                1,
                Duration.ofSeconds(2),
                0,
                false,
                8192,
                Duration.ofMillis(10),
                100,
                Duration.ofSeconds(1),
                512,
                500_000_000,
                4096,
                1024,
                256,
                false,
                streamWrappedRecordBlocks,
                false);
    }
}
