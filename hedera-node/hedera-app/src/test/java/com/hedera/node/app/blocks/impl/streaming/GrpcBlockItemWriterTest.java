// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GrpcBlockItemWriterTest {

    @Mock
    private BlockBufferService blockBufferService;

    @Mock
    private BlockNodeConnectionManager blockNodeConnectionManager;

    @Test
    void testGrpcBlockItemWriterConstructor() {
        final GrpcBlockItemWriter grpcBlockItemWriter =
                new GrpcBlockItemWriter(blockBufferService, blockNodeConnectionManager);
        assertThat(grpcBlockItemWriter).isNotNull();
    }

    @Test
    void testOpenBlock() {
        GrpcBlockItemWriter grpcBlockItemWriter =
                new GrpcBlockItemWriter(blockBufferService, blockNodeConnectionManager);

        grpcBlockItemWriter.openBlock(0);

        verify(blockBufferService).openBlock(0);
    }

    @Test
    void testOpenBlockNegativeBlockNumber() {
        GrpcBlockItemWriter grpcBlockItemWriter =
                new GrpcBlockItemWriter(blockBufferService, blockNodeConnectionManager);

        assertThatThrownBy(() -> grpcBlockItemWriter.openBlock(-1), "Block number must be non-negative")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testWritePbjItemAndBytes() {
        GrpcBlockItemWriter grpcBlockItemWriter =
                new GrpcBlockItemWriter(blockBufferService, blockNodeConnectionManager);

        // Create BlockProof as easiest way to build object from BlockStreams
        Bytes bytes = Bytes.wrap(new byte[] {1, 2, 3, 4, 5});
        final var proof = BlockItem.newBuilder()
                .blockProof(BlockProof.newBuilder().blockSignature(bytes).siblingHashes(new ArrayList<>()))
                .build();

        grpcBlockItemWriter.writePbjItemAndBytes(proof, bytes);

        verify(blockBufferService).addItem(0L, proof);
    }

    @Test
    void testWritePbjItem() {
        GrpcBlockItemWriter grpcBlockItemWriter =
                new GrpcBlockItemWriter(blockBufferService, blockNodeConnectionManager);

        // Create BlockProof as easiest way to build object from BlockStreams
        Bytes bytes = Bytes.wrap(new byte[] {1, 2, 3, 4, 5});
        final var proof = BlockItem.newBuilder()
                .blockProof(BlockProof.newBuilder().blockSignature(bytes).siblingHashes(new ArrayList<>()))
                .build();

        grpcBlockItemWriter.writePbjItem(proof);

        verify(blockBufferService).addItem(0L, proof);
    }

    @Test
    void testCompleteBlock() {
        GrpcBlockItemWriter grpcBlockItemWriter =
                new GrpcBlockItemWriter(blockBufferService, blockNodeConnectionManager);

        grpcBlockItemWriter.openBlock(0);
        grpcBlockItemWriter.closeCompleteBlock();

        verify(blockBufferService).closeBlock(0);
    }
}
