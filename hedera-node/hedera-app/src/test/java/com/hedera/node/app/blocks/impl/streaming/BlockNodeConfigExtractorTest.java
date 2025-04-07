// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.internal.network.BlockNodeConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockNodeConfigExtractorTest {
    private BlockNodeConfigExtractorImpl blockNodeConfigExtractor;

    @BeforeEach
    void setUp() {
        // Ensure the test config file exists
        final var configPath = Objects.requireNonNull(
                        BlockNodeConfigExtractorTest.class.getClassLoader().getResource("bootstrap/"))
                .getPath();
        assertThat(Files.exists(Path.of(configPath))).isTrue();
        blockNodeConfigExtractor = new BlockNodeConfigExtractorImpl(configPath);
    }

    @Test
    void testLoadConfig() {
        List<BlockNodeConfig> nodes = blockNodeConfigExtractor.getAllNodes();
        assertThat(nodes).isNotEmpty();
        assertThat(nodes).allMatch(node -> node.address() != null && node.port() > 0);
    }

    @Test
    void testBlockItemBatchSize() {
        int batchSize = blockNodeConfigExtractor.getBlockItemBatchSize();
        assertThat(batchSize).isEqualTo(256);
    }

    @Test
    void testInvalidConfigPath() {
        assertThatThrownBy(() -> new BlockNodeConfigExtractorImpl("invalid/path"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to read block node configuration");
    }

    @Test
    void testNoOpBlockNodeConfigExtractor() {
        final NoOpBlockNodeConfigExtractor noOpBlockNodeConfigExtractor = new NoOpBlockNodeConfigExtractor();
        assertThat(noOpBlockNodeConfigExtractor.getAllNodes()).isEqualTo(List.of());
        assertThat(noOpBlockNodeConfigExtractor.getBlockItemBatchSize()).isEqualTo(0);
    }
}
