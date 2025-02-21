// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class GrpcBlockItemWriterTest {

    @Mock
    private BlockStreamConfig blockStreamConfig;

    @Test
    public void testGrpcBlockItemWriterConstructor() {
        when(blockStreamConfig.grpcAddress()).thenReturn("localhost");
        when(blockStreamConfig.grpcPort()).thenReturn(8080);

        final GrpcBlockItemWriter grpcBlockItemWriter = new GrpcBlockItemWriter(blockStreamConfig);
        assertThat(grpcBlockItemWriter).isNotNull();
    }

    @Test
    public void testOpenBlockNegativeBlockNumber() {
        when(blockStreamConfig.grpcAddress()).thenReturn("localhost");
        when(blockStreamConfig.grpcPort()).thenReturn(8080);

        GrpcBlockItemWriter grpcBlockItemWriter = new GrpcBlockItemWriter(blockStreamConfig);

        assertThatThrownBy(() -> grpcBlockItemWriter.openBlock(-1), "Block number must be non-negative")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testWriteItemBeforeOpen() {
        when(blockStreamConfig.grpcAddress()).thenReturn("localhost");
        when(blockStreamConfig.grpcPort()).thenReturn(8080);

        GrpcBlockItemWriter grpcBlockItemWriter = new GrpcBlockItemWriter(blockStreamConfig);

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
    public void testCloseBlockNotOpen() {
        when(blockStreamConfig.grpcAddress()).thenReturn("localhost");
        when(blockStreamConfig.grpcPort()).thenReturn(8080);

        GrpcBlockItemWriter grpcBlockItemWriter = new GrpcBlockItemWriter(blockStreamConfig);

        assertThatThrownBy(grpcBlockItemWriter::closeBlock, "Cannot close a GrpcBlockItemWriter that is not open")
                .isInstanceOf(IllegalStateException.class);
    }
}
