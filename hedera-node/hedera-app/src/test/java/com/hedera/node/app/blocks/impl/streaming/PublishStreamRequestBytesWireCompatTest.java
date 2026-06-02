// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.block.internal.BlockItemSetBytes;
import com.hedera.hapi.block.internal.EndStreamBytes;
import com.hedera.hapi.block.internal.PublishStreamRequestBytes;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.hiero.block.api.BlockEnd;
import org.hiero.block.api.BlockItemSet;
import org.hiero.block.api.PublishStreamRequest;
import org.hiero.block.api.PublishStreamRequest.EndStream;
import org.junit.jupiter.api.Test;

/**
 * Verifies the core correctness guarantee of the serialized block buffer: the consensus-node-internal
 * {@link PublishStreamRequestBytes} (whose block items are carried as already-serialized {@code bytes}) serializes to
 * the <b>exact same wire bytes</b> as the equivalent {@link PublishStreamRequest}. This is what allows the consensus
 * node to send serialized items without the block node needing any changes. Also guards against future field-number
 * drift between the two message definitions.
 */
class PublishStreamRequestBytesWireCompatTest {

    private static BlockItem headerItem() {
        return BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().number(42L).build())
                .build();
    }

    private static BlockItem txItem() {
        return BlockItem.newBuilder()
                .signedTransaction(Bytes.wrap(new byte[] {1, 2, 3, 4, 5}))
                .build();
    }

    private static BlockItem proofItem() {
        return BlockItem.newBuilder()
                .blockProof(BlockProof.newBuilder().block(42L).build())
                .build();
    }

    @Test
    void blockItemsRequestSerializesIdenticallyAndRoundTrips() throws ParseException {
        final List<BlockItem> items = List.of(headerItem(), txItem(), proofItem());

        final PublishStreamRequest normal = PublishStreamRequest.newBuilder()
                .blockItems(BlockItemSet.newBuilder().blockItems(items).build())
                .build();
        final PublishStreamRequestBytes bytesReq = PublishStreamRequestBytes.newBuilder()
                .blockItems(BlockItemSetBytes.newBuilder()
                        .blockItems(
                                items.stream().map(BlockItem.PROTOBUF::toBytes).toList())
                        .build())
                .build();

        final Bytes normalWire = PublishStreamRequest.PROTOBUF.toBytes(normal);
        final Bytes bytesWire = PublishStreamRequestBytes.PROTOBUF.toBytes(bytesReq);

        // The two requests serialize to byte-identical output...
        assertThat(bytesWire).isEqualTo(normalWire);
        // ...so a block node parsing the wire bytes as a PublishStreamRequest sees exactly the original request.
        assertThat(PublishStreamRequest.PROTOBUF.parse(bytesWire)).isEqualTo(normal);
    }

    @Test
    void endStreamRequestSerializesIdenticallyAndRoundTrips() throws ParseException {
        final PublishStreamRequest normal = PublishStreamRequest.newBuilder()
                .endStream(EndStream.newBuilder()
                        .endCode(EndStream.Code.RESET)
                        .earliestBlockNumber(1L)
                        .latestBlockNumber(9L))
                .build();
        final PublishStreamRequestBytes bytesReq = PublishStreamRequestBytes.newBuilder()
                .endStream(EndStreamBytes.newBuilder()
                        .endCode(EndStream.Code.RESET)
                        .earliestBlockNumber(1L)
                        .latestBlockNumber(9L))
                .build();

        final Bytes bytesWire = PublishStreamRequestBytes.PROTOBUF.toBytes(bytesReq);
        assertThat(bytesWire).isEqualTo(PublishStreamRequest.PROTOBUF.toBytes(normal));
        assertThat(PublishStreamRequest.PROTOBUF.parse(bytesWire)).isEqualTo(normal);
    }

    @Test
    void endOfBlockRequestSerializesIdenticallyAndRoundTrips() throws ParseException {
        final PublishStreamRequest normal = PublishStreamRequest.newBuilder()
                .endOfBlock(BlockEnd.newBuilder().blockNumber(42L))
                .build();
        final PublishStreamRequestBytes bytesReq = PublishStreamRequestBytes.newBuilder()
                .endOfBlock(BlockEnd.newBuilder().blockNumber(42L))
                .build();

        final Bytes bytesWire = PublishStreamRequestBytes.PROTOBUF.toBytes(bytesReq);
        assertThat(bytesWire).isEqualTo(PublishStreamRequest.PROTOBUF.toBytes(normal));
        assertThat(PublishStreamRequest.PROTOBUF.parse(bytesWire)).isEqualTo(normal);
    }
}
