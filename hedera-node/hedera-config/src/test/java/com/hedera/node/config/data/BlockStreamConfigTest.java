// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.config.types.BlockStreamWriterMode;
import com.hedera.node.config.types.StreamMode;
import java.time.Duration;
import org.hiero.base.crypto.DigestType;
import org.junit.jupiter.api.Test;

class BlockStreamConfigTest {

    @Test
    void deltasAreOffByDefaultAndOnlyEnabledForCompleteBlockStreams() {
        for (final var mode : StreamMode.values()) {
            final var defaults = com.hedera.node.config.testfixtures.HederaTestConfigBuilder.create()
                    .withValue("blockStream.streamMode", mode.name())
                    .getOrCreateConfig()
                    .getConfigData(BlockStreamConfig.class);
            assertThat(defaults.stateChangeDeltasEnabled()).isFalse();
            assertThat(defaults.stateChangeDeltaMinSaving()).isEqualTo(64);
            final var enabled = com.hedera.node.config.testfixtures.HederaTestConfigBuilder.create()
                    .withValue("blockStream.streamMode", mode.name())
                    .withValue("blockStream.enableStateChangeDeltas", true)
                    .withValue("blockStream.stateChangeDeltaMinSaving", 32)
                    .getOrCreateConfig()
                    .getConfigData(BlockStreamConfig.class);
            assertThat(enabled.stateChangeDeltaMinSaving()).isEqualTo(32);
            assertThat(enabled.stateChangeDeltasEnabled()).isEqualTo(mode == StreamMode.BLOCKS);
            final var disabled = com.hedera.node.config.testfixtures.HederaTestConfigBuilder.create()
                    .withValue("blockStream.streamMode", mode.name())
                    .withValue("blockStream.enableStateChangeDeltas", false)
                    .getOrCreateConfig()
                    .getConfigData(BlockStreamConfig.class);
            assertThat(disabled.stateChangeDeltasEnabled()).isFalse();
        }
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
