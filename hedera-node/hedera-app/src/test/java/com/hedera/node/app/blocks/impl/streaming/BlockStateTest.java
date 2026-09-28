// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.node.app.blocks.impl.streaming.BlockNodeCommunicationTestBase.newBlockFooter;
import static com.hedera.node.app.blocks.impl.streaming.BlockNodeCommunicationTestBase.newBlockHeaderItem;
import static com.hedera.node.app.blocks.impl.streaming.BlockNodeCommunicationTestBase.newBlockProofItem;
import static com.hedera.node.app.blocks.impl.streaming.BlockNodeCommunicationTestBase.newBlockTxItem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockItem.ItemOneOfType;
import com.hedera.node.app.blocks.impl.streaming.BlockState.BufferedItem;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterEach;
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
                    .findVarHandle(BlockState.class, "bufferedItems", ConcurrentMap.class);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BlockState block;
    private LogCaptor logCaptor;

    @BeforeEach
    void beforeEach() {
        block = new BlockState(1, 2_000L);
        logCaptor = new LogCaptor(LogManager.getLogger(BlockState.class));
    }

    @AfterEach
    void afterEach() {
        logCaptor.stopCapture();
    }

    @Test
    void testInit() {
        assertThat(blockItems()).isEmpty();
        assertThat(block.closedTimestamp()).isNull();
        assertThat(block.blockNumber()).isEqualTo(1);
        assertThat(block.itemCount()).isZero();
        assertThat(block.blockItem(0)).isNull();
        assertThat(block.blockPeriodMillis()).isEqualTo(2_000L);
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
    void testAddSerializedItem_null_returnsMinusOne() {
        final int index = block.addSerializedItem(null, ItemOneOfType.BLOCK_HEADER);

        assertThat(index).isEqualTo(-1);
        assertThat(blockItems()).isEmpty();
        assertThat(block.itemCount()).isZero();
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

    @Test
    void testCheckForDelays_blockClosed() {
        block = new BlockState(1, 250L);

        block.closeBlock();
        block.checkForDelays();

        assertThat(logCaptor.warnLogs()).isEmpty();
        assertThat(logCaptor.infoLogs()).isEmpty();
    }

    @Test
    void testCheckForDelays_blockNotOpened() throws Exception {
        block = new BlockState(1, 250L);

        Thread.sleep(1_000L);

        block.closeBlock();
        block.checkForDelays();

        // since the block was never opened - i.e. the header was never added - there is no delay
        assertThat(logCaptor.warnLogs()).isEmpty();
        assertThat(logCaptor.infoLogs()).isEmpty();
    }

    @Test
    void testCheckForDelays_noBlockPeriod() throws Exception {
        block = new BlockState(1, -1L);
        Thread.sleep(200L);

        // add the header to "open" the block
        final BlockItem header = newBlockHeaderItem(1);
        block.addSerializedItem(
                BlockItem.PROTOBUF.toBytes(header), header.item().kind());

        block.checkForDelays();
        block.closeBlock();

        // since the block period is missing (-1), the close delay check will not proceed
        assertThat(logCaptor.warnLogs()).isEmpty();
        assertThat(logCaptor.infoLogs()).isEmpty();
    }

    @Test
    void testCheckForDelays_closeDelay_withinThreshold() throws Exception {
        block = new BlockState(1, 500);
        Thread.sleep(550L);

        // add the header to "open" the block
        final BlockItem header = newBlockHeaderItem(1);
        block.addSerializedItem(
                BlockItem.PROTOBUF.toBytes(header), header.item().kind());

        block.checkForDelays();
        block.closeBlock();

        // since the block was closed within the block period, the close delay warning will not be triggered
        assertThat(logCaptor.warnLogs()).isEmpty();
        assertThat(logCaptor.infoLogs()).isEmpty();
    }

    @Test
    void testCheckForDelays_closeDelay_exceededThreshold() throws Exception {
        block = new BlockState(1, 500);
        // add the header to "open" the block
        final BlockItem header = newBlockHeaderItem(1);
        block.addSerializedItem(
                BlockItem.PROTOBUF.toBytes(header), header.item().kind());

        Thread.sleep(1250L);

        // add another item so the check doesn't trigger on the slow append path too
        final BlockItem item = newBlockTxItem(100);
        block.addSerializedItem(BlockItem.PROTOBUF.toBytes(item), item.item().kind());

        block.checkForDelays();

        final List<String> warnLogs = logCaptor.warnLogs();
        assertThat(warnLogs).hasSize(1);
        assertThat(warnLogs.getFirst()).startsWith("Block has been opened for an extended period of time");
        assertThat(logCaptor.infoLogs()).isEmpty();

        // check for delays again - there should NOT be additional warnings
        Thread.sleep(25L);
        block.checkForDelays();
        Thread.sleep(25L);
        block.checkForDelays();

        assertThat(logCaptor.warnLogs()).hasSize(1);
        assertThat(logCaptor.infoLogs()).isEmpty();

        // now close the block - an INFO log should be included now
        block.closeBlock();

        final List<String> infoLogs = logCaptor.infoLogs();
        assertThat(logCaptor.warnLogs()).hasSize(1);
        assertThat(infoLogs).hasSize(1);
        assertThat(infoLogs.getFirst()).startsWith("Block (1) was open for ");
    }

    @Test
    void testCheckForDelays_appendDelay_withinThreshold() throws Exception {
        block = new BlockState(1, 250);
        // add the header to "open" the block
        final BlockItem header = newBlockHeaderItem(1);
        block.addSerializedItem(
                BlockItem.PROTOBUF.toBytes(header), header.item().kind());

        Thread.sleep(50);

        block.checkForDelays();

        assertThat(logCaptor.warnLogs()).isEmpty();
        assertThat(logCaptor.infoLogs()).isEmpty();
    }

    @Test
    void testCheckForDelays_appendDelay_exceededThreshold() throws Exception {
        block = new BlockState(1, 250);
        // add the header to "open" the block
        final BlockItem header = newBlockHeaderItem(1);
        block.addSerializedItem(
                BlockItem.PROTOBUF.toBytes(header), header.item().kind());

        // sleep for a little over the block period, but don't sleep for too long else we will trigger the close delay
        Thread.sleep(400);

        block.checkForDelays();

        final List<String> warnLogs1 = logCaptor.warnLogs();
        assertThat(warnLogs1).hasSize(1);
        assertThat(warnLogs1.getFirst()).contains("Block is slow appending items", "lastItemIndex: 0");
        assertThat(logCaptor.infoLogs()).isEmpty();

        // append another item - this should generate an INFO log about the next item taking X amount of time to append
        final BlockItem item = newBlockTxItem(100);
        block.addSerializedItem(BlockItem.PROTOBUF.toBytes(item), item.item().kind());

        final List<String> infoLogs1 = logCaptor.infoLogs();
        assertThat(logCaptor.warnLogs()).hasSize(1);
        assertThat(infoLogs1).hasSize(1);
        assertThat(infoLogs1.getFirst()).contains("Block (1) took", "to append the next item (itemIndex: 0->1)");

        // add another slow item - it should retrigger and a new WARN log will be generated
        // this will also trigger the block close delay too as a side effect of sleeping more than 2x the block period
        Thread.sleep(400);

        block.checkForDelays();

        final List<String> warnLogs2 = logCaptor.warnLogs();
        warnLogs2.forEach(System.out::println);
        assertThat(warnLogs2).hasSize(3);
        assertThat(warnLogs2.get(0)).contains("Block is slow appending items", "lastItemIndex: 0");
        assertThat(warnLogs2.get(1)).contains("Block is slow appending items", "lastItemIndex: 1");
        assertThat(warnLogs2.get(2)).contains("Block has been opened for an extended period of time (block: 1,");

        // close the block and check the INFO logs
        block.closeBlock();

        // there should be 2 INFO logs: one for the second item delay and one for the block close delay
        // there will not be a "recovery" INFO log for the second item
        final List<String> infoLogs2 = logCaptor.infoLogs();
        assertThat(infoLogs2).hasSize(2);
        assertThat(infoLogs2.get(0)).contains("Block (1) took", "to append the next item (itemIndex: 0->1)");
        assertThat(infoLogs2.get(1)).contains("Block (1) was open for", "before being closed");
    }

    @Test
    void testAddSerializedItem_nullItem() {
        assertThat(block.itemCount()).isZero();

        block.addSerializedItem(null);

        assertThat(block.itemCount()).isZero();
    }

    @Test
    void testAddSerializedItem_realItem() {
        assertThat(block.itemCount()).isZero();

        final BlockItem header = newBlockHeaderItem(1);
        block.addSerializedItem(BlockItem.PROTOBUF.toBytes(header));

        assertThat(block.itemCount()).isOne();

        final BlockItem item = block.blockItem(0);
        assertThat(item).isEqualTo(header);
    }

    @Test
    void testItemOfType_null() {
        assertThatThrownBy(() -> BlockState.itemTypeOf(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("serializedItem must not be null");
    }

    @Test
    void testItemOfType_zeroLength() {
        final BlockItem.ItemOneOfType type = BlockState.itemTypeOf(Bytes.EMPTY);
        assertThat(type).isEqualTo(BlockItem.ItemOneOfType.UNSET);
    }

    @Test
    void testItemOfType() {
        final Bytes header = BlockItem.PROTOBUF.toBytes(newBlockHeaderItem(10));
        final Bytes signedTransaction = BlockItem.PROTOBUF.toBytes(newBlockTxItem(100));
        final Bytes footer = BlockItem.PROTOBUF.toBytes(newBlockFooter());
        final Bytes proof = BlockItem.PROTOBUF.toBytes(newBlockProofItem(10, 1_000));

        assertThat(BlockState.itemTypeOf(header)).isEqualTo(BlockItem.ItemOneOfType.BLOCK_HEADER);
        assertThat(BlockState.itemTypeOf(signedTransaction)).isEqualTo(BlockItem.ItemOneOfType.SIGNED_TRANSACTION);
        assertThat(BlockState.itemTypeOf(footer)).isEqualTo(BlockItem.ItemOneOfType.BLOCK_FOOTER);
        assertThat(BlockState.itemTypeOf(proof)).isEqualTo(BlockItem.ItemOneOfType.BLOCK_PROOF);
    }

    @Test
    void testSizeBytes() {
        assertThat(block.sizeBytes()).isZero();

        final BlockItem item = newBlockTxItem(1_000);
        block.addSerializedItem(BlockItem.PROTOBUF.toBytes(item), BlockItem.ItemOneOfType.SIGNED_TRANSACTION);

        assertThat(block.sizeBytes()).isGreaterThan(1_000);
    }

    @Test
    void testGetBufferedItem() {
        assertThat(block.bufferedItem(0)).isNull();

        final Bytes item1 = BlockItem.PROTOBUF.toBytes(newBlockHeaderItem(1));
        final Bytes item2 = BlockItem.PROTOBUF.toBytes(newBlockTxItem(1_000));
        final Bytes item3 = BlockItem.PROTOBUF.toBytes(newBlockProofItem(1, 500));
        block.addSerializedItem(item1, BlockItem.ItemOneOfType.BLOCK_HEADER);
        block.addSerializedItem(item2, BlockItem.ItemOneOfType.SIGNED_TRANSACTION);
        block.addSerializedItem(item3, BlockItem.ItemOneOfType.BLOCK_PROOF);

        final BufferedItem bufferedItem1 = block.bufferedItem(0);
        final BufferedItem bufferedItem2 = block.bufferedItem(1);
        final BufferedItem bufferedItem3 = block.bufferedItem(2);
        assertThat(bufferedItem1).isNotNull();
        assertThat(bufferedItem2).isNotNull();
        assertThat(bufferedItem3).isNotNull();

        assertThat(bufferedItem1.serializedItem()).isEqualTo(item1);
        assertThat(bufferedItem2.serializedItem()).isEqualTo(item2);
        assertThat(bufferedItem3.serializedItem()).isEqualTo(item3);
    }

    // Utilities

    @SuppressWarnings("unchecked")
    private ConcurrentMap<Integer, BlockState.BufferedItem> blockItems() {
        return (ConcurrentMap<Integer, BlockState.BufferedItem>) blockItemsHandle.get(block);
    }
}
