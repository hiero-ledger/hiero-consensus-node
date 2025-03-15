// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager;
import com.hedera.node.app.blocks.impl.streaming.GrpcBlockItemWriter;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GrpcBlockItemWriterTest {

    private GrpcBlockItemWriter grpcBlockItemWriter;

    @Mock
    private BlockNodeConnectionManager blockNodeConnectionManager;

    @BeforeEach
    void setUp() {
        grpcBlockItemWriter = new GrpcBlockItemWriter(blockNodeConnectionManager);
    }

    @Test
    void testOpenBlockNegativeBlockNumber() {
        assertThatThrownBy(() -> grpcBlockItemWriter.openBlock(-1), "Block number must be non-negative")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testWriteItemBeforeOpen() {
        // Create BlockProof as easiest way to build object from BlockStreams
        Bytes bytes = Bytes.wrap(new byte[] {1, 2, 3, 4, 5});
        final var proof = new BlockProof.Builder().blockSignature(bytes).siblingHashes(new ArrayList<>());
        final var blockProof = BlockItem.PROTOBUF.toBytes(
                BlockItem.newBuilder().blockProof(proof).build());

        assertThatThrownBy(
                        () -> grpcBlockItemWriter.writePbjItem(blockProof), "Cannot write item before opening a block")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testCloseBlockNotOpen() {
        assertThatThrownBy(grpcBlockItemWriter::closeBlock, "Cannot close a GrpcBlockItemWriter that is not open")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testWriteBlockHeaderItemFailsWithNullItem() {
        assertThatThrownBy(() -> grpcBlockItemWriter.writeBlockHeaderItem(null), "bytes must not be null")
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testWriteBlockHeaderItemFailsWhenBlockNotOpen() {
        final var blockHeaderBytes = Bytes.wrap(new byte[] {1, 2, 3, 4, 5});
        assertThatThrownBy(
                        () -> grpcBlockItemWriter.writeBlockHeaderItem(blockHeaderBytes),
                        "Cannot write block header when block is not open")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testWriteBlockHeaderItemSuccess() {
        final var blockHeaderBytes = Bytes.wrap(new byte[] {1, 2, 3, 4, 5});
        final long blockNumber = 1;
        grpcBlockItemWriter.openBlock(blockNumber);

        grpcBlockItemWriter.writeBlockHeaderItem(blockHeaderBytes);

        verify(blockNodeConnectionManager).startStreamingBlockHeader(blockNumber, blockHeaderBytes);
    }
}
