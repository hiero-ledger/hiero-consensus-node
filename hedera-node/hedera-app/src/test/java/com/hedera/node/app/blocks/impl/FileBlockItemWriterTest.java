// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter;
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
class FileBlockItemWriterTest {

    private static final String MF = "000000000000000000000000000000000001.mf";
    private static final String BLK_GZ = "000000000000000000000000000000000001.blk.gz";

    @TempDir
    Path tempDir;

    @Mock
    private ConfigProvider configProvider;

    private NodeInfo selfNodeInfo = new NodeInfoImpl(
            0, AccountID.newBuilder().accountNum(3).build(), 10, List.of(), Bytes.EMPTY, List.of(), false);

    @Mock
    private BlockStreamConfig blockStreamConfig;

    @Mock
    private VersionedConfiguration versionedConfiguration;

    @Mock
    private FileSystem fileSystem;

    @Test
    protected void testOpenBlock() {
        when(configProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(versionedConfiguration.getConfigData(BlockStreamConfig.class)).thenReturn(blockStreamConfig);
        when(blockStreamConfig.compressFilesOnCreation()).thenReturn(true);
        when(blockStreamConfig.blockFileDir()).thenReturn("N/A");
        when(fileSystem.getPath(anyString())).thenReturn(tempDir);

        FileBlockItemWriter fileBlockItemWriter = new FileBlockItemWriter(configProvider, selfNodeInfo, fileSystem);
        fileBlockItemWriter.openBlock(1);

        // Assertion to check if the directory is created
        final Path expectedDirectory = tempDir.resolve("block-0.0.3");
        assertThat(Files.exists(expectedDirectory)).isTrue();

        // Assertion to check if the block file is created
        final Path expectedBlockFile = expectedDirectory.resolve(BLK_GZ);
        assertThat(Files.exists(expectedBlockFile)).isTrue();

        // Marker file should not exist yet since block is not closed
        final Path expectedMarkerFile = expectedDirectory.resolve(MF);
        assertThat(Files.exists(expectedMarkerFile)).isFalse();
    }

    @Test
    protected void testOpenBlockCannotInitializeTwice() {
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
    protected void testOpenBlockNegativeBlockNumber() {
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
    protected void testWriteItem() throws IOException {
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

        Path expectedDirectory = tempDir.resolve("block-0.0.3");
        final Path expectedBlockFile = expectedDirectory.resolve("000000000000000000000000000000000001.blk.gz");
        final Path expectedMarkerFile = expectedDirectory.resolve(MF);

        // Verify both block file and marker file exist
        assertThat(Files.exists(expectedBlockFile)).isTrue();
        assertThat(Files.exists(expectedMarkerFile)).isTrue();

        // Verify marker file is empty
        assertThat(Files.size(expectedMarkerFile)).isZero();

        // Ungzip the file
        try (GZIPInputStream gzis = new GZIPInputStream(Files.newInputStream(expectedBlockFile))) {
            byte[] fileContents = gzis.readAllBytes();

            // Verify that the contents of the file match the Bytes object
            // Note: This assertion assumes that the file contains only the Bytes object and nothing else.
            assertArrayEquals(expectedBytes, fileContents, "Serialized item was not written correctly");
        }
    }

    @Test
    protected void testWriteItemBeforeOpen() {
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
    protected void testCloseBlock() throws IOException {
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

        Path expectedDirectory = tempDir.resolve("block-0.0.3");
        Path expectedBlockFile = expectedDirectory.resolve("000000000000000000000000000000000001.blk.gz");
        Path expectedMarkerFile = expectedDirectory.resolve(MF);

        // Verify both block file and marker file exist
        assertThat(Files.exists(expectedBlockFile)).isTrue();
        assertThat(Files.exists(expectedMarkerFile)).isTrue();

        // Verify marker file is empty
        assertThat(Files.size(expectedMarkerFile)).isZero();
    }

    @Test
    protected void testCloseBlockNotOpen() {
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
    protected void testCloseBlockAlreadyClosed() {
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

        // Verify marker file exists before attempting second close
        Path expectedDirectory = tempDir.resolve("block-0.0.3");
        Path expectedMarkerFile = expectedDirectory.resolve(MF);
        assertThat(Files.exists(expectedMarkerFile)).isTrue();

        assertThatThrownBy(fileBlockItemWriter::closeBlock, "Cannot close a FileBlockItemWriter that is already closed")
                .isInstanceOf(IllegalStateException.class);
    }
}
