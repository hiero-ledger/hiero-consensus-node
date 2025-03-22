// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager;
import com.hedera.node.app.blocks.impl.streaming.GrpcBlockItemWriter;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GrpcBlockItemWriterTest {

    @Mock
    private BlockNodeConnectionManager blockNodeConnectionManager;

    @Test
    void testGrpcBlockItemWriterConstructor() {
        final GrpcBlockItemWriter grpcBlockItemWriter = new GrpcBlockItemWriter(blockNodeConnectionManager);
        assertThat(grpcBlockItemWriter).isNotNull();
    }

    @Test
    void testOpenBlockNegativeBlockNumber() {
        GrpcBlockItemWriter grpcBlockItemWriter = new GrpcBlockItemWriter(blockNodeConnectionManager);

        assertThatThrownBy(() -> grpcBlockItemWriter.openBlock(-1), "Block number must be non-negative")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testWriteItemBeforeOpen() {
        GrpcBlockItemWriter grpcBlockItemWriter = new GrpcBlockItemWriter(blockNodeConnectionManager);

        // Create BlockProof as easiest way to build object from BlockStreams
        Bytes bytes = Bytes.wrap(new byte[] {1, 2, 3, 4, 5});
        final var proof = BlockItem.newBuilder()
                .blockProof(BlockProof.newBuilder().blockSignature(bytes).siblingHashes(new ArrayList<>()))
                .build();

        assertThatThrownBy(() -> grpcBlockItemWriter.writePbjItem(proof), "Cannot write item before opening a block")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testCloseBlockNotOpen() {
        GrpcBlockItemWriter grpcBlockItemWriter = new GrpcBlockItemWriter(blockNodeConnectionManager);

        assertThatThrownBy(grpcBlockItemWriter::closeBlock, "Cannot close a GrpcBlockItemWriter that is not open")
                .isInstanceOf(IllegalStateException.class);
    }
}
