// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GrpcBlockItemWriterTest {

    @Mock
    private BlockBufferService blockBufferService;

    @Test
    void testGrpcBlockItemWriterConstructor() {
        final GrpcBlockItemWriter grpcBlockItemWriter = new GrpcBlockItemWriter(blockBufferService);
        assertThat(grpcBlockItemWriter).isNotNull();
    }

    @Test
    void testOpenBlock() {
        final GrpcBlockItemWriter grpcBlockItemWriter = new GrpcBlockItemWriter(blockBufferService);

        grpcBlockItemWriter.openBlock(0);

        verify(blockBufferService).openBlock(0);
    }

    @Test
    void testOpenBlockNegativeBlockNumber() {
        final GrpcBlockItemWriter grpcBlockItemWriter = new GrpcBlockItemWriter(blockBufferService);

        assertThatThrownBy(() -> grpcBlockItemWriter.openBlock(-1), "Block number must be non-negative")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testWritePbjItem() {
        final GrpcBlockItemWriter grpcBlockItemWriter = new GrpcBlockItemWriter(blockBufferService);

        // Create BlockProof as easiest way to build object from BlockStreams
        final var proof = BlockItem.newBuilder()
                .blockProof(BlockProof.newBuilder().siblingHashes(new ArrayList<>()))
                .build();

        grpcBlockItemWriter.writePbjItem(proof);

        verify(blockBufferService).addItem(0L, proof);
    }

    @Test
    void testCompleteBlock() {
        final GrpcBlockItemWriter grpcBlockItemWriter = new GrpcBlockItemWriter(blockBufferService);

        grpcBlockItemWriter.openBlock(0);
        grpcBlockItemWriter.closeCompleteBlock();

        verify(blockBufferService).closeBlock(0);
    }
}
