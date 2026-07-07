// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.newBlockHeader;
import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.newBlockProof;
import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.newEventTransaction;
import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.newStateChanges;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.block.internal.BlockBytes;
import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the backward-compatibility guarantee that lets the persisted block buffer store serialized item bytes
 * directly: the consensus-node-internal {@link BlockBytes} ({@code repeated bytes}) is byte-for-byte wire-identical to
 * {@link Block} ({@code repeated BlockItem}). Because {@code BufferedBlock} embeds the block at the same field number,
 * a buffer persisted by an older version (as a {@code Block}) parses correctly under the new {@code BlockBytes} schema,
 * and vice-versa — so no on-disk migration is required.
 */
class BlockBytesWireCompatTest {

    private static List<BlockItem> sampleItems() {
        return List.of(newBlockHeader(7L), newEventTransaction(), newStateChanges(), newBlockProof(7L));
    }

    private static List<Bytes> serialized(final List<BlockItem> items) {
        return items.stream().map(BlockItem.PROTOBUF::toBytes).toList();
    }

    @Test
    void blockAndBlockBytesAreWireIdenticalAndCrossParse() throws ParseException {
        final List<BlockItem> items = sampleItems();
        final Block block = new Block(items);
        final BlockBytes blockBytes = new BlockBytes(serialized(items));

        final Bytes blockWire = Block.PROTOBUF.toBytes(block);
        final Bytes blockBytesWire = BlockBytes.PROTOBUF.toBytes(blockBytes);

        // The two representations serialize to byte-identical output.
        assertThat(blockBytesWire).isEqualTo(blockWire);

        // An old "Block"-encoded payload parses as BlockBytes, yielding exactly the original serialized item bytes
        // (the items are NOT re-parsed — they come back as opaque bytes).
        final BlockBytes parsedAsBytes = BlockBytes.PROTOBUF.parse(blockWire);
        assertThat(parsedAsBytes.items()).containsExactlyElementsOf(serialized(items));

        // A new "BlockBytes"-encoded payload parses as Block, yielding the original items.
        final Block parsedAsBlock = Block.PROTOBUF.parse(blockBytesWire);
        assertThat(parsedAsBlock).isEqualTo(block);
    }

    @Test
    void emptyBlockIsWireIdentical() throws ParseException {
        final Bytes blockWire = Block.PROTOBUF.toBytes(new Block(List.of()));
        final Bytes blockBytesWire = BlockBytes.PROTOBUF.toBytes(new BlockBytes(List.of()));

        assertThat(blockBytesWire).isEqualTo(blockWire);
        assertThat(BlockBytes.PROTOBUF.parse(blockWire).items()).isEmpty();
        assertThat(Block.PROTOBUF.parse(blockBytesWire).items()).isEmpty();
    }
}
