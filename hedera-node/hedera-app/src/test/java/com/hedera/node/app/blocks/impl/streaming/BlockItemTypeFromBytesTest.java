// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.newBlockHeader;
import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.newBlockProof;
import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.newEventHeader;
import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.newEventTransaction;
import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.newRoundHeader;
import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.newStateChanges;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockItem.ItemOneOfType;
import com.hedera.hapi.block.stream.RedactedItem;
import com.hedera.hapi.block.stream.output.BlockFooter;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BlockState#itemTypeOf(Bytes)}, which derives a block item's type from the leading protobuf tag of
 * its serialized form (used when restoring the buffer from disk, where only the serialized bytes are available).
 * <p>
 * This is correctness-critical: it must read multi-byte varint tags correctly — field {@code 19} (redacted_item)
 * encodes its tag as a two-byte varint — and it must always agree with the deserialized item's own
 * {@link BlockItem#item() kind()}.
 */
class BlockItemTypeFromBytesTest {

    @Test
    void deriveTypeMatchesKindForRepresentativeTypes() {
        final List<BlockItem> items = List.of(
                newBlockHeader(1L), // field 1  (one-byte tag)
                newEventHeader(), // field 2
                newRoundHeader(1L), // field 3
                newEventTransaction(), // field 4  (bytes oneof field)
                newStateChanges(), // field 7
                newBlockProof(1L), // field 9
                BlockItem.newBuilder().blockFooter(BlockFooter.DEFAULT).build(), // field 12 (highest one-byte tag)
                BlockItem.newBuilder().redactedItem(RedactedItem.DEFAULT).build()); // field 19 (two-byte varint tag)

        for (final BlockItem item : items) {
            final Bytes serialized = BlockItem.PROTOBUF.toBytes(item);
            assertThat(BlockState.itemTypeOf(serialized))
                    .as("derived type for %s (%d serialized bytes)", item.item().kind(), serialized.length())
                    .isEqualTo(item.item().kind());
        }
    }

    @Test
    void emptyOrUnsetItemIsUnset() {
        // An explicitly empty buffer yields UNSET.
        assertThat(BlockState.itemTypeOf(Bytes.EMPTY)).isEqualTo(ItemOneOfType.UNSET);

        // An unset BlockItem serializes to zero bytes and is reported as UNSET.
        final Bytes unsetSerialized = BlockItem.PROTOBUF.toBytes(BlockItem.DEFAULT);
        assertThat(unsetSerialized.length()).isZero();
        assertThat(BlockState.itemTypeOf(unsetSerialized)).isEqualTo(ItemOneOfType.UNSET);
    }
}
