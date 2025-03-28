// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager;
import com.hedera.node.app.blocks.impl.streaming.GrpcBlockItemWriter;
import com.hedera.node.internal.network.PendingProof;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GrpcBlockItemWriterTest {

    @Mock
    private BlockNodeConnectionManager blockNodeConnectionManager;

    private GrpcBlockItemWriter subject;

    @BeforeEach
    void setUp() {
        subject = new GrpcBlockItemWriter(blockNodeConnectionManager);
    }

    @Test
    void testOpenBlockNegativeBlockNumber() {
        assertThatThrownBy(() -> subject.openBlock(-1), "Block number must be non-negative")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testWriteItemBeforeOpen() {
        // Create BlockProof as easiest way to build object from BlockStreams
        Bytes bytes = Bytes.wrap(new byte[] {1, 2, 3, 4, 5});
        final var proof = BlockItem.newBuilder()
                .blockProof(BlockProof.newBuilder().blockSignature(bytes).siblingHashes(new ArrayList<>()))
                .build();

        assertThatThrownBy(() -> subject.writePbjItem(proof), "Cannot write item before opening a block")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testCloseCompleteBlockNotOpen() {
        assertThatThrownBy(subject::closeCompleteBlock, "Cannot close a GrpcBlockItemWriter that is not open")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void flushPendingBlockCurrentlyNoop() {
        assertDoesNotThrow(() -> subject.flushPendingBlock(PendingProof.DEFAULT));
    }
}
