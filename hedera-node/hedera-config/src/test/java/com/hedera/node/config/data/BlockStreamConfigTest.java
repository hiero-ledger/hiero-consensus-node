// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.config.types.BlockStreamWriterMode;
import com.hedera.node.config.types.StreamMode;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BlockStreamConfigTest {

    @Test
    void streamToBlockNodesFalseWhenFileWriterAndWrbDisabled() {
        assertThat(configWith(BlockStreamWriterMode.FILE, false).streamToBlockNodes())
                .isFalse();
    }

    @Test
    void streamToBlockNodesTrueWhenFileWriterAndWrbEnabled() {
        assertThat(configWith(BlockStreamWriterMode.FILE, true).streamToBlockNodes())
                .isTrue();
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
                32,
                1,
                Duration.ofSeconds(2),
                8192,
                Duration.ofMillis(10),
                100,
                Duration.ofSeconds(1),
                512,
                500_000_000,
                false,
                4096,
                1024,
                256,
                false,
                streamWrappedRecordBlocks);
    }
}
