/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.blocks.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.info.NodeInfoImpl;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.info.NodeInfo;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class FileBlockItemWriterTest {

    @TempDir
    Path tempDir;

    @Mock
    private ConfigProvider configProvider;

    private NodeInfo selfNodeInfo =
            new NodeInfoImpl(0, AccountID.newBuilder().accountNum(3).build(), 10, List.of(), Bytes.EMPTY);

    @Mock
    private BlockStreamConfig blockStreamConfig;

    @Mock
    private VersionedConfiguration versionedConfiguration;

    @Mock
    private FileSystem fileSystem;

    @Test
    public void testOpenBlock() {
        when(configProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(versionedConfiguration.getConfigData(BlockStreamConfig.class)).thenReturn(blockStreamConfig);
        when(blockStreamConfig.compressFilesOnCreation()).thenReturn(true);
        when(blockStreamConfig.blockFileDir()).thenReturn("N/A");
        when(fileSystem.getPath(anyString())).thenReturn(tempDir);

        FileBlockItemWriter fileBlockItemWriter = new FileBlockItemWriter(configProvider, selfNodeInfo, fileSystem);
        fileBlockItemWriter.openBlock(1);

        // Assertion to check if the directory is created
        Path expectedDirectory = tempDir.resolve("block-0.0.3");
        assertThat(Files.exists(expectedDirectory)).isTrue();

        // Assertion to check if the block file is created
        Path expectedBlockFile = expectedDirectory.resolve("000000000000000000000000000000000001.blk.gz");
        assertThat(Files.exists(expectedBlockFile)).isTrue();
    }

    @Test
    public void testOpenBlockCannotInitializeTwice() {
        when(configProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(versionedConfiguration.getConfigData(BlockStreamConfig.class)).thenReturn(blockStreamConfig);
        when(blockStreamConfig.compressFilesOnCreation()).thenReturn(true);
        when(blockStreamConfig.blockFileDir()).thenReturn("N/A");
        when(fileSystem.getPath(anyString())).thenReturn(tempDir);

        FileBlockItemWriter fileBlockItemWriter = new FileBlockItemWriter(configProvider, selfNodeInfo, fileSystem);
        fileBlockItemWriter.openBlock(1);

        // Assertion to check if the directory is created
        Path expectedDirectory = tempDir.resolve("block-0.0.3");
        assertThat(Files.exists(expectedDirectory)).isTrue();

        assertThatThrownBy(() -> fileBlockItemWriter.openBlock(1), "Cannot initialize a FileBlockItemWriter twice")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void testOpenBlockNegativeBlockNumber() {
        when(configProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(versionedConfiguration.getConfigData(BlockStreamConfig.class)).thenReturn(blockStreamConfig);
        when(blockStreamConfig.compressFilesOnCreation()).thenReturn(true);
        when(blockStreamConfig.blockFileDir()).thenReturn("N/A");
        when(fileSystem.getPath(anyString())).thenReturn(tempDir);

        FileBlockItemWriter fileBlockItemWriter = new FileBlockItemWriter(configProvider, selfNodeInfo, fileSystem);

        assertThatThrownBy(() -> fileBlockItemWriter.openBlock(-1), "Block number must be non-negative")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testWriteItem() throws IOException {
        when(configProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(versionedConfiguration.getConfigData(BlockStreamConfig.class)).thenReturn(blockStreamConfig);
        when(blockStreamConfig.compressFilesOnCreation()).thenReturn(true);
        when(blockStreamConfig.blockFileDir()).thenReturn("N/A");
        when(fileSystem.getPath(anyString())).thenReturn(tempDir);

        FileBlockItemWriter fileBlockItemWriter = new FileBlockItemWriter(configProvider, selfNodeInfo, fileSystem);

        // Open a block
        fileBlockItemWriter.openBlock(1);

        // Create a Bytes object and write it
        final var bytes = new byte[] {1, 2, 3, 4, 5};
        byte[] expectedBytes = {10, 5, 1, 2, 3, 4, 5};
        fileBlockItemWriter.writeItem(bytes);

        // Close the block
        fileBlockItemWriter.closeBlock();

        // Read the contents of the file
        Path expectedBlockFile = tempDir.resolve("block-0.0.3").resolve("000000000000000000000000000000000001.blk.gz");

        // Ungzip the file
        try (GZIPInputStream gzis = new GZIPInputStream(Files.newInputStream(expectedBlockFile))) {
            byte[] fileContents = gzis.readAllBytes();

            // Verify that the contents of the file match the Bytes object
            // Note: This assertion assumes that the file contains only the Bytes object and nothing else.
            assertArrayEquals(expectedBytes, fileContents, "Serialized item was not written correctly");
        }
    }

    @Test
    public void testWriteItemBeforeOpen() {
        when(configProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(versionedConfiguration.getConfigData(BlockStreamConfig.class)).thenReturn(blockStreamConfig);
        when(blockStreamConfig.compressFilesOnCreation()).thenReturn(true);
        when(blockStreamConfig.blockFileDir()).thenReturn("N/A");
        when(fileSystem.getPath(anyString())).thenReturn(tempDir);

        FileBlockItemWriter fileBlockItemWriter = new FileBlockItemWriter(configProvider, selfNodeInfo, fileSystem);

        // Create a Bytes object and write it
        final var bytes = new byte[] {1, 2, 3, 4, 5};

        assertThatThrownBy(() -> fileBlockItemWriter.writeItem(bytes), "Cannot write item before opening a block")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void testCloseBlock() {
        when(configProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(versionedConfiguration.getConfigData(BlockStreamConfig.class)).thenReturn(blockStreamConfig);
        when(blockStreamConfig.compressFilesOnCreation()).thenReturn(true);
        when(blockStreamConfig.blockFileDir()).thenReturn("N/A");
        when(fileSystem.getPath(anyString())).thenReturn(tempDir);

        FileBlockItemWriter fileBlockItemWriter = new FileBlockItemWriter(configProvider, selfNodeInfo, fileSystem);

        // Open a block
        fileBlockItemWriter.openBlock(1);

        // Close the block
        fileBlockItemWriter.closeBlock();

        // Read the contents of the file
        Path expectedBlockFile = tempDir.resolve("block-0.0.3").resolve("000000000000000000000000000000000001.blk.gz");

        assertThat(Files.exists(expectedBlockFile)).isTrue();
    }

    @Test
    public void testCloseBlockNotOpen() {
        when(configProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(versionedConfiguration.getConfigData(BlockStreamConfig.class)).thenReturn(blockStreamConfig);
        when(blockStreamConfig.compressFilesOnCreation()).thenReturn(true);
        when(blockStreamConfig.blockFileDir()).thenReturn("N/A");
        when(fileSystem.getPath(anyString())).thenReturn(tempDir);

        FileBlockItemWriter fileBlockItemWriter = new FileBlockItemWriter(configProvider, selfNodeInfo, fileSystem);

        assertThatThrownBy(fileBlockItemWriter::closeBlock, "Cannot close a FileBlockItemWriter that is not open")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void testCloseBlockAlreadyClosed() {
        when(configProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(versionedConfiguration.getConfigData(BlockStreamConfig.class)).thenReturn(blockStreamConfig);
        when(blockStreamConfig.compressFilesOnCreation()).thenReturn(true);
        when(blockStreamConfig.blockFileDir()).thenReturn("N/A");
        when(fileSystem.getPath(anyString())).thenReturn(tempDir);

        FileBlockItemWriter fileBlockItemWriter = new FileBlockItemWriter(configProvider, selfNodeInfo, fileSystem);

        // Open a block
        fileBlockItemWriter.openBlock(1);

        // Close the block
        fileBlockItemWriter.closeBlock();

        assertThatThrownBy(fileBlockItemWriter::closeBlock, "Cannot close a FileBlockItemWriter that is already closed")
                .isInstanceOf(IllegalStateException.class);
    }
}
