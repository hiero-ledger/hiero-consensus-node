// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.config.types.BlockStreamWriterMode;
import com.hedera.node.config.types.StreamMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BlockStreamingBaseTest {
    @ParameterizedTest
    @CsvSource({
        "BLOCKS, GRPC, true",
        "BLOCKS, FILE_AND_GRPC, false",
        "BLOCKS, FILE, false",
        "BOTH, GRPC, false",
        "BOTH, FILE_AND_GRPC, false",
        "RECORDS, GRPC, false",
    })
    void backPressureOnlyForGrpcOnlyBlocksStream(
            final StreamMode streamMode, final BlockStreamWriterMode writerMode, final boolean expected) {
        final var config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.streamMode", streamMode)
                .withValue("blockStream.writerMode", writerMode)
                .getOrCreateConfig();
        final ConfigProvider configProvider = () -> new VersionedConfigImpl(config, 1);
        final var subject = new BlockStreamingBase(configProvider) {};

        assertThat(subject.isBackpressureEnabled()).isEqualTo(expected);
    }
}
