// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager;
import com.hedera.node.app.blocks.impl.streaming.BlockStreamStateManager;
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

    @Mock
    private BlockStreamStateManager blockStreamStateManager;

    @Test
    void testGrpcBlockItemWriterConstructor() {
        final GrpcBlockItemWriter grpcBlockItemWriter =
                new GrpcBlockItemWriter(blockNodeConnectionManager, blockStreamStateManager);
        assertThat(grpcBlockItemWriter).isNotNull();
    }

    @Test
    void testOpenBlockNegativeBlockNumber() {
        GrpcBlockItemWriter grpcBlockItemWriter =
                new GrpcBlockItemWriter(blockNodeConnectionManager, blockStreamStateManager);

        assertThatThrownBy(() -> grpcBlockItemWriter.openBlock(-1), "Block number must be non-negative")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testWriteItem() {
        GrpcBlockItemWriter grpcBlockItemWriter =
                new GrpcBlockItemWriter(blockNodeConnectionManager, blockStreamStateManager);

        // Create BlockProof as easiest way to build object from BlockStreams
        Bytes bytes = Bytes.wrap(new byte[] {1, 2, 3, 4, 5});
        final var proof = new BlockProof.Builder().blockSignature(bytes).siblingHashes(new ArrayList<>());
        final var blockProof = BlockItem.PROTOBUF.toBytes(
                BlockItem.newBuilder().blockProof(proof).build());

        grpcBlockItemWriter.writePbjItem(blockProof);

        verify(blockStreamStateManager).addItem(0L, blockProof);
    }

    @Test
    void testCloseBlock() {
        GrpcBlockItemWriter grpcBlockItemWriter =
                new GrpcBlockItemWriter(blockNodeConnectionManager, blockStreamStateManager);

        grpcBlockItemWriter.closeBlock();

        verify(blockStreamStateManager).closeBlock(0L);
    }
}
