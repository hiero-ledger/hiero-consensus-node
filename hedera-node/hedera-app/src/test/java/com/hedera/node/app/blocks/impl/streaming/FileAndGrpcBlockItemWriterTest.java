// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.internal.network.PendingProof;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileAndGrpcBlockItemWriterTest {

    private FileBlockItemWriter fileWriter;
    private GrpcBlockItemWriter grpcWriter;

    private FileAndGrpcBlockItemWriter jointWriter;
    private final AtomicBoolean isStreamingEnabled = new AtomicBoolean(false);

    @BeforeEach
    void beforeEach() {
        fileWriter = mock(FileBlockItemWriter.class);
        grpcWriter = mock(GrpcBlockItemWriter.class);

        final BlockStreamConfig blockStreamConfig = mock(BlockStreamConfig.class);
        doAnswer(answer -> isStreamingEnabled.get()).when(blockStreamConfig).streamToBlockNodes();
        final VersionedConfiguration configuration = mock(VersionedConfiguration.class);
        when(configuration.getConfigData(BlockStreamConfig.class)).thenReturn(blockStreamConfig);
        final ConfigProvider configProvider = mock(ConfigProvider.class);
        when(configProvider.getConfiguration()).thenReturn(configuration);

        jointWriter = new FileAndGrpcBlockItemWriter(configProvider, fileWriter, grpcWriter);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testOpenBlock(final boolean streamingEnabled) {
        isStreamingEnabled.set(streamingEnabled);
        final long blockNumber = 10L;

        jointWriter.openBlock(blockNumber);

        verify(fileWriter).openBlock(blockNumber);
        verify(grpcWriter, times(streamingEnabled ? 1 : 0)).openBlock(blockNumber);

        verifyNoMoreInteractions(fileWriter);
        verifyNoMoreInteractions(grpcWriter);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testCloseCompleteBlock(final boolean streamingEnabled) {
        isStreamingEnabled.set(streamingEnabled);

        jointWriter.closeCompleteBlock();

        verify(fileWriter).closeCompleteBlock();
        verify(grpcWriter, times(streamingEnabled ? 1 : 0)).closeCompleteBlock();

        verifyNoMoreInteractions(fileWriter);
        verifyNoMoreInteractions(grpcWriter);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testFlushPendingBlock(final boolean streamingEnabled) {
        isStreamingEnabled.set(streamingEnabled);
        final PendingProof pendingProof = PendingProof.DEFAULT;

        jointWriter.flushPendingBlock(pendingProof);

        verify(fileWriter).flushPendingBlock(pendingProof);
        verify(grpcWriter, times(streamingEnabled ? 1 : 0)).flushPendingBlock(pendingProof);

        verifyNoMoreInteractions(fileWriter);
        verifyNoMoreInteractions(grpcWriter);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testWritePbjItem(final boolean streamingEnabled) {
        isStreamingEnabled.set(streamingEnabled);
        final BlockItem item = BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().number(101L).build())
                .build();

        jointWriter.writePbjItem(item);

        verify(fileWriter).writePbjItem(item);
        verify(grpcWriter, times(streamingEnabled ? 1 : 0)).writePbjItem(item);

        verifyNoMoreInteractions(fileWriter);
        verifyNoMoreInteractions(grpcWriter);
    }
}
