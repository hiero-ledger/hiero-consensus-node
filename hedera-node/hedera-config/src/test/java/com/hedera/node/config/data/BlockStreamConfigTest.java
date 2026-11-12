// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.config.types.BlockStreamWriterMode;
import com.hedera.node.config.types.StreamMode;
import java.time.Duration;
import org.hiero.base.crypto.DigestType;
import org.junit.jupiter.api.Test;

class BlockStreamConfigTest {

    @Test
    void partialsAreOffByDefaultAndOnlyEnabledForCompleteBlockStreams() {
        for (final var mode : StreamMode.values()) {
            final var defaults = com.hedera.node.config.testfixtures.HederaTestConfigBuilder.create()
                    .withValue("blockStream.streamMode", mode.name())
                    .getOrCreateConfig()
                    .getConfigData(BlockStreamConfig.class);
            assertThat(defaults.stateChangePartialsEnabled()).isFalse();
            assertThat(defaults.stateChangePartialMinSaving()).isEqualTo(16);
            final var enabled = com.hedera.node.config.testfixtures.HederaTestConfigBuilder.create()
                    .withValue("blockStream.streamMode", mode.name())
                    .withValue("blockStream.enableStateChangePartials", true)
                    .withValue("blockStream.stateChangePartialMinSaving", 32)
                    .getOrCreateConfig()
                    .getConfigData(BlockStreamConfig.class);
            assertThat(enabled.stateChangePartialMinSaving()).isEqualTo(32);
            assertThat(enabled.stateChangePartialsEnabled()).isEqualTo(mode == StreamMode.BLOCKS);
            final var disabled = com.hedera.node.config.testfixtures.HederaTestConfigBuilder.create()
                    .withValue("blockStream.streamMode", mode.name())
                    .withValue("blockStream.enableStateChangePartials", false)
                    .getOrCreateConfig()
                    .getConfigData(BlockStreamConfig.class);
            assertThat(disabled.stateChangePartialsEnabled()).isFalse();
        }
    }

    @Test
    void blockSizeLimitsHaveIndependentDefaults() {
        final var config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .getOrCreateConfig()
                .getConfigData(BlockStreamConfig.class);

        assertThat(config.maxBlockSizeBytes()).isEqualTo(20L * 1024 * 1024);
        assertThat(config.maxBlockExecutionSizeBytes()).isEqualTo(100L * 1024 * 1024);
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

    @Test
    void digestTypeDefaultsToSha384() {
        assertThat(configWith(BlockStreamWriterMode.FILE_AND_GRPC, false).digestType())
                .isEqualTo(DigestType.SHA_384);
    }

    @Test
    void sha256DigestTypeIsEquivalentToUseSha256True() {
        final var config = configWithDigestType(DigestType.SHA_256);
        assertThat(config.digestType()).isEqualTo(DigestType.SHA_256);
        assertThat(config.digestType() == DigestType.SHA_256).isTrue();
    }

    private static BlockStreamConfig configWith(BlockStreamWriterMode writerMode, boolean streamWrappedRecordBlocks) {
        return configWithDigestType(DigestType.SHA_384, writerMode, streamWrappedRecordBlocks);
    }

    private static BlockStreamConfig configWithDigestType(DigestType digestType) {
        return configWithDigestType(digestType, BlockStreamWriterMode.FILE_AND_GRPC, false);
    }

    private static BlockStreamConfig configWithDigestType(
            DigestType digestType, BlockStreamWriterMode writerMode, boolean streamWrappedRecordBlocks) {
        return new BlockStreamConfig(
                StreamMode.BOTH,
                writerMode,
                "/opt/hgcapp/blockStreams",
                1,
                Duration.ofSeconds(2),
                0,
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
                digestType,
                streamWrappedRecordBlocks,
                false,
                false,
                64);
    }
}
