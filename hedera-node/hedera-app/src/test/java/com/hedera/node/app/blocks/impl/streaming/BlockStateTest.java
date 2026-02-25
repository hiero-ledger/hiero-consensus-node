// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.node.app.blocks.impl.streaming.BlockNodeCommunicationTestBase.newBlockHeaderItem;
import static com.hedera.node.app.blocks.impl.streaming.BlockNodeCommunicationTestBase.newBlockProofItem;
import static com.hedera.node.app.blocks.impl.streaming.BlockNodeCommunicationTestBase.newBlockTxItem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockItem.ItemOneOfType;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.time.Instant;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BlockState}.
 */
class BlockStateTest {

    private static final VarHandle blockItemsHandle;

    static {
        try {
            final Lookup lookup = MethodHandles.lookup();
            blockItemsHandle = MethodHandles.privateLookupIn(BlockState.class, lookup)
                    .findVarHandle(BlockState.class, "blockItems", ConcurrentMap.class);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BlockState block;

    @BeforeEach
    void beforeEach() {
        block = new BlockState(1);
    }

    @Test
    void testInit() {
        assertThat(blockItems()).isEmpty();
        assertThat(block.closedTimestamp()).isNull();
        assertThat(block.blockNumber()).isEqualTo(1);
        assertThat(block.itemCount()).isZero();
        assertThat(block.blockItem(0)).isNull();
    }

    @Test
    void testAddItem_null() {
        block.addItem(null);

        assertThat(blockItems()).isEmpty();
    }

    @Test
    void testAddItem_closedBlock() {
        block.closeBlock();

        final BlockItem item = newBlockHeaderItem();

        assertThatThrownBy(() -> block.addItem(item))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Block is closed; adding more items is not permitted");

        assertThat(blockItems()).isEmpty();
    }

    @Test
    void testAddItem_nonProof() {
        final BlockItem header = newBlockHeaderItem();
        block.addItem(header);

        final BlockItem item = block.blockItem(0);

        assertThat(item).isEqualTo(header);
        assertThat(block.itemCount()).isOne();
    }

    @Test
    void testAddItem_withProof() {
        final BlockItem header = newBlockHeaderItem();
        final BlockItem proof = newBlockProofItem();

        block.addItem(header);
        block.addItem(proof);

        assertThat(block.itemCount()).isEqualTo(2);

        final BlockItem item1 = block.blockItem(0);
        final BlockItem item2 = block.blockItem(1);

        assertThat(item1).isEqualTo(header);
        assertThat(item2).isEqualTo(proof);
    }

    @Test
    void testGetBlockItem_notFound() {
        final BlockItem item = block.blockItem(0);

        assertThat(item).isNull();
    }

    @Test
    void testGetBlockItem() {
        block.addItem(newBlockHeaderItem());
        block.addItem(newBlockProofItem());

        final BlockItem header = block.blockItem(0);
        assertThat(header).isNotNull();
        assertThat(header.item().kind()).isEqualTo(ItemOneOfType.BLOCK_HEADER);

        final BlockItem proof = block.blockItem(1);
        assertThat(proof).isNotNull();
        assertThat(proof.item().kind()).isEqualTo(ItemOneOfType.BLOCK_PROOF);
    }

    @Test
    void testItemCount() {
        block.addItem(newBlockHeaderItem());
        block.addItem(newBlockTxItem());
        block.addItem(newBlockTxItem());
        block.addItem(newBlockProofItem());

        assertThat(block.itemCount()).isEqualTo(4);
    }

    @Test
    void testItemCount_empty() {
        assertThat(block.itemCount()).isZero();
    }

    @Test
    void testCloseBlock_auto() {
        assertThat(block.isClosed()).isFalse();
        assertThat(block.closedTimestamp()).isNull();

        block.closeBlock();

        assertThat(block.isClosed()).isTrue();
        assertThat(block.closedTimestamp()).isNotNull();
    }

    @Test
    void testCloseBlock_explicit() {
        assertThat(block.isClosed()).isFalse();
        assertThat(block.closedTimestamp()).isNull();

        final Instant timestamp = Instant.now();

        block.closeBlock(timestamp);

        assertThat(block.isClosed()).isTrue();
        assertThat(block.closedTimestamp()).isEqualTo(timestamp);
    }

    @Test
    void testCloseBlock_explicitNull() {
        assertThat(block.isClosed()).isFalse();
        assertThat(block.closedTimestamp()).isNull();

        assertThatThrownBy(() -> block.closeBlock(null)).isInstanceOf(NullPointerException.class);

        assertThat(block.isClosed()).isFalse();
        assertThat(block.closedTimestamp()).isNull();
    }

    // Utilities

    @SuppressWarnings("unchecked")
    private ConcurrentMap<Integer, BlockItem> blockItems() {
        return (ConcurrentMap<Integer, BlockItem>) blockItemsHandle.get(block);
    }
}
