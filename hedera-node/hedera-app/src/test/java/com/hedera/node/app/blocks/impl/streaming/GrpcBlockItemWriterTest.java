// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.internal.network.PendingProof;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GrpcBlockItemWriterTest {

    @Mock
    private BlockBufferService blockBufferService;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private VersionedConfiguration configuration;

    @Mock
    private BlockStreamConfig blockStreamConfig;

    @Mock
    private SelfNodeAccountIdManager selfNodeAccountIdManager;

    private final FileSystem fileSystem = FileSystems.getDefault();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        when(configProvider.getConfiguration()).thenReturn(configuration);
        when(configuration.getConfigData(BlockStreamConfig.class)).thenReturn(blockStreamConfig);
        when(blockStreamConfig.blockFileDir()).thenReturn(tempDir.toString());
        when(selfNodeAccountIdManager.getSelfNodeAccountId())
                .thenReturn(AccountID.newBuilder()
                        .shardNum(0)
                        .realmNum(0)
                        .accountNum(1)
                        .build());
    }

    @Test
    void testGrpcBlockItemWriterConstructor() {
        final GrpcBlockItemWriter grpcBlockItemWriter =
                new GrpcBlockItemWriter(configProvider, selfNodeAccountIdManager, fileSystem, blockBufferService);
        assertThat(grpcBlockItemWriter).isNotNull();
    }

    @Test
    void testOpenBlock() {
        GrpcBlockItemWriter grpcBlockItemWriter =
                new GrpcBlockItemWriter(configProvider, selfNodeAccountIdManager, fileSystem, blockBufferService);

        grpcBlockItemWriter.openBlock(0);

        verify(blockBufferService).openBlock(0);
    }

    @Test
    void testOpenBlockNegativeBlockNumber() {
        GrpcBlockItemWriter grpcBlockItemWriter =
                new GrpcBlockItemWriter(configProvider, selfNodeAccountIdManager, fileSystem, blockBufferService);

        assertThatThrownBy(() -> grpcBlockItemWriter.openBlock(-1), "Block number must be non-negative")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testWritePbjItemAndBytes() {
        GrpcBlockItemWriter grpcBlockItemWriter =
                new GrpcBlockItemWriter(configProvider, selfNodeAccountIdManager, fileSystem, blockBufferService);

        // Create BlockProof as easiest way to build object from BlockStreams
        Bytes bytes = Bytes.wrap(new byte[] {1, 2, 3, 4, 5});
        final var proof =
                BlockItem.newBuilder().blockProof(BlockProof.newBuilder()).build();

        grpcBlockItemWriter.writePbjItemAndBytes(proof, bytes);

        verify(blockBufferService).addItem(0L, proof);
    }

    @Test
    void testWritePbjItem() {
        GrpcBlockItemWriter grpcBlockItemWriter =
                new GrpcBlockItemWriter(configProvider, selfNodeAccountIdManager, fileSystem, blockBufferService);

        // Create BlockProof as easiest way to build object from BlockStreams
        final var proof =
                BlockItem.newBuilder().blockProof(BlockProof.newBuilder()).build();

        grpcBlockItemWriter.writePbjItem(proof);

        verify(blockBufferService).addItem(0L, proof);
    }

    @Test
    void testCompleteBlock() {
        GrpcBlockItemWriter grpcBlockItemWriter =
                new GrpcBlockItemWriter(configProvider, selfNodeAccountIdManager, fileSystem, blockBufferService);

        grpcBlockItemWriter.openBlock(0);
        grpcBlockItemWriter.closeCompleteBlock();

        verify(blockBufferService).closeBlock(0);
    }

    @Test
    void testFlushPendingBlockWritesPendingArtifacts() throws Exception {
        final var blockNumber = 7L;
        final var blockState = new BlockState(blockNumber);
        blockState.addItem(BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().number(blockNumber).build())
                .build());
        when(configProvider.getConfiguration()).thenReturn(configuration);
        when(configuration.getConfigData(BlockStreamConfig.class)).thenReturn(blockStreamConfig);
        when(blockStreamConfig.blockFileDir()).thenReturn(tempDir.toString());
        when(selfNodeAccountIdManager.getSelfNodeAccountId())
                .thenReturn(AccountID.newBuilder()
                        .shardNum(0)
                        .realmNum(0)
                        .accountNum(3)
                        .build());
        when(blockBufferService.getBlockState(blockNumber)).thenReturn(blockState);

        final GrpcBlockItemWriter grpcBlockItemWriter =
                new GrpcBlockItemWriter(configProvider, selfNodeAccountIdManager, fileSystem, blockBufferService);
        grpcBlockItemWriter.openBlock(blockNumber);
        grpcBlockItemWriter.flushPendingBlock(PendingProof.newBuilder()
                .block(blockNumber)
                .blockHash(Bytes.wrap(new byte[48]))
                .previousBlockHash(Bytes.wrap(new byte[48]))
                .blockTimestamp(Timestamp.DEFAULT)
                .build());

        final var baseName = FileBlockItemWriter.longToFileName(blockNumber);
        final var nodeDir = tempDir.resolve("block-0.0.3");
        assertThat(Files.exists(nodeDir.resolve(baseName + ".pnd.gz"))).isTrue();
        assertThat(Files.exists(nodeDir.resolve(baseName + ".pnd.json"))).isTrue();
    }
}
