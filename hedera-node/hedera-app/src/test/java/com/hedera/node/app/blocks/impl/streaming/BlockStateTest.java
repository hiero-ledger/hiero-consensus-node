// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.node.app.blocks.impl.streaming.BlockNodeCommunicationTestBase.newBlockHeaderItem;
import static com.hedera.node.app.blocks.impl.streaming.BlockNodeCommunicationTestBase.newBlockProofItem;
import static com.hedera.node.app.blocks.impl.streaming.BlockNodeCommunicationTestBase.newBlockTxItem;
import static com.hedera.node.app.blocks.impl.streaming.BlockNodeCommunicationTestBase.newPreProofBlockStateChangesItem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.blocks.impl.streaming.BlockState.ItemInfo;
import com.hedera.node.app.blocks.impl.streaming.BlockState.ItemState;
import com.hedera.node.app.blocks.impl.streaming.BlockState.RequestWrapper;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.time.Instant;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hiero.block.api.BlockItemSet;
import org.hiero.block.api.PublishStreamRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BlockState}.
 */
class BlockStateTest {

    private static final VarHandle pendingItemsHandle;
    private static final VarHandle requestsHandle;
    private static final VarHandle headerItemHandle;
    private static final VarHandle preProofItemHandle;
    private static final VarHandle proofItemHandle;

    static {
        try {
            final Lookup lookup = MethodHandles.lookup();
            pendingItemsHandle = MethodHandles.privateLookupIn(BlockState.class, lookup)
                    .findVarHandle(BlockState.class, "pendingItems", Queue.class);
            requestsHandle = MethodHandles.privateLookupIn(BlockState.class, lookup)
                    .findVarHandle(BlockState.class, "requestsByIndex", ConcurrentMap.class);
            headerItemHandle = MethodHandles.privateLookupIn(BlockState.class, lookup)
                    .findVarHandle(BlockState.class, "headerItemInfo", ItemInfo.class);
            preProofItemHandle = MethodHandles.privateLookupIn(BlockState.class, lookup)
                    .findVarHandle(BlockState.class, "preProofItemInfo", ItemInfo.class);
            proofItemHandle = MethodHandles.privateLookupIn(BlockState.class, lookup)
                    .findVarHandle(BlockState.class, "proofItemInfo", ItemInfo.class);
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
        assertThat(pendingItems()).isEmpty();
        assertThat(requestsByIndex()).isEmpty();
        assertThat(block.closedTimestamp()).isNull();
        assertThat(block.blockNumber()).isEqualTo(1);

        final ItemInfo headerInfo = headerItemInfo();
        assertThat(headerInfo.state()).hasValue(ItemState.NIL);
        assertThat(headerInfo.requestIndex()).hasValue(-1);

        final ItemInfo preProofInfo = preProofItemInfo();
        assertThat(preProofInfo.state()).hasValue(ItemState.NIL);
        assertThat(preProofInfo.requestIndex()).hasValue(-1);

        final ItemInfo proofInfo = proofItemInfo();
        assertThat(proofInfo.state()).hasValue(ItemState.NIL);
        assertThat(proofInfo.requestIndex()).hasValue(-1);
    }

    @Test
    void testAddItem_null() {
        block.addItem(null);

        assertThat(pendingItems()).isEmpty();
    }

    @Test
    void testAddItem_closedBlock() {
        block.closeBlock();

        final BlockItem item = newBlockHeaderItem();

        assertThatThrownBy(() -> block.addItem(item))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Block is closed; adding more items is not permitted");

        assertThat(pendingItems()).isEmpty();
    }

    @Test
    void testAddItem_headerItem() {
        final ItemInfo headerInfo = headerItemInfo();
        final Queue<BlockItem> pendingItems = pendingItems();
        final BlockItem headerItem = newBlockHeaderItem();

        block.addItem(headerItem);

        assertThat(headerInfo.state()).hasValue(ItemState.ADDED);
        assertThat(headerInfo.requestIndex()).hasValue(-1);
        assertThat(pendingItems).hasSize(1).contains(headerItem);
    }

    @Test
    void testAddItem_normalItem() {
        final ItemInfo headerInfo = headerItemInfo();
        final ItemInfo preProofInfo = preProofItemInfo();
        final ItemInfo proofInfo = proofItemInfo();
        final Queue<BlockItem> pendingItems = pendingItems();
        final BlockItem item = newBlockTxItem();

        block.addItem(item);

        assertThat(headerInfo.state()).hasValue(ItemState.NIL);
        assertThat(preProofInfo.state()).hasValue(ItemState.NIL);
        assertThat(proofInfo.state()).hasValue(ItemState.NIL);
        assertThat(pendingItems).hasSize(1).contains(item);
    }

    @Test
    void testAddItem_preProofItem() {
        final ItemInfo headerInfo = headerItemInfo();
        final ItemInfo preProofInfo = preProofItemInfo();
        final ItemInfo proofInfo = proofItemInfo();
        final Queue<BlockItem> pendingItems = pendingItems();
        final BlockItem item = newPreProofBlockStateChangesItem();

        block.addItem(item);

        assertThat(headerInfo.state()).hasValue(ItemState.NIL);
        assertThat(preProofInfo.state()).hasValue(ItemState.ADDED);
        assertThat(proofInfo.state()).hasValue(ItemState.NIL);
        assertThat(pendingItems).hasSize(1).contains(item);
    }

    @Test
    void testAddItem_proofItem() {
        final ItemInfo headerInfo = headerItemInfo();
        final ItemInfo preProofInfo = preProofItemInfo();
        final ItemInfo proofInfo = proofItemInfo();
        final Queue<BlockItem> pendingItems = pendingItems();
        final BlockItem item = newBlockProofItem();

        block.addItem(item);

        assertThat(headerInfo.state()).hasValue(ItemState.NIL);
        assertThat(preProofInfo.state()).hasValue(ItemState.NIL);
        assertThat(proofInfo.state()).hasValue(ItemState.ADDED);
        assertThat(pendingItems).hasSize(1).contains(item);
    }

    @Test
    void testGetRequest_notFound() {
        final PublishStreamRequest req = block.getRequest(10);
        assertThat(req).isNull();
    }

    @Test
    void testGetRequest_found() {
        final PublishStreamRequest req = newRequest(newBlockTxItem(), newBlockTxItem());
        final Map<Integer, RequestWrapper> requestsByIndex = requestsByIndex();
        requestsByIndex.put(2, new RequestWrapper(2, req, new AtomicBoolean(false)));

        final PublishStreamRequest actualReq = block.getRequest(2);
        assertThat(actualReq).isNotNull().isEqualTo(req);
    }

    @Test
    void testCloseBlock() {
        final Instant now = Instant.now();

        block.closeBlock();

        assertThat(block.closedTimestamp()).isNotNull().isAfterOrEqualTo(now);
    }

    @Test
    void testCloseBlock_alreadyClosed() throws InterruptedException {
        block.closeBlock();

        Thread.sleep(3_000);
        final Instant aLilBeforeNow = Instant.now().minusSeconds(2);

        block.closeBlock();
        assertThat(block.closedTimestamp()).isNotNull().isBefore(aLilBeforeNow);
    }

    @Test
    void testProcessPendingItems_nonePending() {
        final Map<Integer, RequestWrapper> requestsByIndex = requestsByIndex();
        final Queue<BlockItem> pendingItems = pendingItems();
        pendingItems.clear(); // ensure nothing exists
        requestsByIndex.clear(); // ensure nothing exists

        block.processPendingItems(10);

        assertThat(pendingItems).isEmpty();
        assertThat(block.numRequestsCreated()).isZero();
    }

    @Test
    void testProcessPendingItems_notEnoughForBatch() {
        final Map<Integer, RequestWrapper> requestsByIndex = requestsByIndex();
        final Queue<BlockItem> pendingItems = pendingItems();
        pendingItems.clear(); // ensure nothing exists
        requestsByIndex.clear(); // ensure nothing exists

        // add some pending items, but not enough to create a batch
        block.addItem(newBlockTxItem());
        block.addItem(newBlockTxItem());
        block.addItem(newBlockTxItem());
        block.addItem(newBlockTxItem());
        block.addItem(newBlockTxItem());
        block.addItem(newBlockTxItem());

        block.processPendingItems(10);

        assertThat(pendingItems).hasSize(6); // no pending requests should be removed from the queue
        assertThat(block.numRequestsCreated()).isZero();
    }

    @Test
    void testProcessPendingItems_enoughForBatch() {
        final Map<Integer, RequestWrapper> requestsByIndex = requestsByIndex();
        final Queue<BlockItem> pendingItems = pendingItems();
        pendingItems.clear(); // ensure nothing exists
        requestsByIndex.clear(); // ensure nothing exists

        block.addItem(newBlockTxItem());
        block.addItem(newBlockTxItem());
        block.addItem(newBlockTxItem());
        block.addItem(newBlockTxItem());
        block.addItem(newBlockTxItem());
        block.addItem(newBlockTxItem());
        block.addItem(newBlockTxItem());
        block.addItem(newBlockTxItem());
        block.addItem(newBlockTxItem());
        block.addItem(newBlockTxItem());
        block.addItem(newBlockTxItem());

        block.processPendingItems(10);

        assertThat(pendingItems).hasSize(1); // all of the pending requests except 1 should be removed
        assertThat(block.numRequestsCreated()).isEqualTo(1); // should be one request with 10 items

        final PublishStreamRequest request = block.getRequest(0);
        assertThat(request).isNotNull();
        assertThat(request.blockItems().blockItems()).hasSize(10);
    }

    @Test
    void testProcessPendingItems_multipleBatches() {
        final Map<Integer, RequestWrapper> requestsByIndex = requestsByIndex();
        final Queue<BlockItem> pendingItems = pendingItems();
        pendingItems.clear(); // ensure nothing exists
        requestsByIndex.clear(); // ensure nothing exists
        final int batchSize = 10;
        final int numToCreate = 25;

        for (int i = 0; i < numToCreate; ++i) {
            block.addItem(newBlockTxItem());
        }

        block.processPendingItems(batchSize);

        assertThat(pendingItems).hasSize(5); // should be 5 extra items that didn't fit in the batches
        assertThat(block.numRequestsCreated()).isEqualTo(2); // should be 2 requests

        final PublishStreamRequest request1 = block.getRequest(0);
        assertThat(request1).isNotNull();
        assertThat(request1.blockItems().blockItems()).hasSize(10);
        final PublishStreamRequest request2 = block.getRequest(0);
        assertThat(request2).isNotNull();
        assertThat(request2.blockItems().blockItems()).hasSize(10);
    }

    @Test
    void testProcessPendingItems_withHeader() {
        final ItemInfo headerInfo = headerItemInfo();
        final Map<Integer, RequestWrapper> requestsByIndex = requestsByIndex();
        final Queue<BlockItem> pendingItems = pendingItems();
        pendingItems.clear(); // ensure nothing exists
        requestsByIndex.clear(); // ensure nothing exists

        block.addItem(newBlockHeaderItem());

        block.processPendingItems(10);

        assertThat(pendingItems).isEmpty();
        assertThat(block.numRequestsCreated()).isEqualTo(1);
        final PublishStreamRequest request1 = block.getRequest(0);
        assertThat(request1).isNotNull();
        assertThat(request1.blockItems().blockItems()).hasSize(1);
        assertThat(headerInfo.state()).hasValue(ItemState.PACKED);
    }

    @Test
    void testProcessPendingItems_withPreProof() {
        final ItemInfo preProofInfo = preProofItemInfo();
        final Map<Integer, RequestWrapper> requestsByIndex = requestsByIndex();
        final Queue<BlockItem> pendingItems = pendingItems();
        pendingItems.clear(); // ensure nothing exists
        requestsByIndex.clear(); // ensure nothing exists

        block.addItem(newBlockTxItem());
        block.addItem(newPreProofBlockStateChangesItem());

        block.processPendingItems(10);

        assertThat(pendingItems).isEmpty();
        assertThat(block.numRequestsCreated()).isEqualTo(1);
        final PublishStreamRequest request1 = block.getRequest(0);
        assertThat(request1).isNotNull();
        assertThat(request1.blockItems().blockItems()).hasSize(2);
        assertThat(preProofInfo.state()).hasValue(ItemState.PACKED);
    }

    @Test
    void testProcessPendingItems_withProof() {
        final ItemInfo proofInfo = proofItemInfo();
        final Map<Integer, RequestWrapper> requestsByIndex = requestsByIndex();
        final Queue<BlockItem> pendingItems = pendingItems();
        pendingItems.clear(); // ensure nothing exists
        requestsByIndex.clear(); // ensure nothing exists

        block.addItem(newBlockProofItem());

        block.processPendingItems(10);

        assertThat(pendingItems).isEmpty();
        assertThat(block.numRequestsCreated()).isEqualTo(1);
        final PublishStreamRequest request1 = block.getRequest(0);
        assertThat(request1).isNotNull();
        assertThat(request1.blockItems().blockItems()).hasSize(1);
        assertThat(proofInfo.state()).hasValue(ItemState.PACKED);
    }

    @Test
    void testProcessPendingItems_mixed() {
        final ItemInfo headerInfo = headerItemInfo();
        final ItemInfo preProofInfo = preProofItemInfo();
        final ItemInfo proofInfo = proofItemInfo();
        final Map<Integer, RequestWrapper> requestsByIndex = requestsByIndex();
        final Queue<BlockItem> pendingItems = pendingItems();
        pendingItems.clear(); // ensure nothing exists
        requestsByIndex.clear(); // ensure nothing exists

        block.addItem(newBlockHeaderItem());
        block.addItem(newBlockTxItem());
        block.addItem(newBlockTxItem());
        block.addItem(newBlockTxItem());
        block.addItem(newBlockTxItem());
        block.addItem(newBlockTxItem());
        block.addItem(newBlockTxItem());
        block.addItem(newPreProofBlockStateChangesItem());
        block.addItem(newBlockProofItem());

        assertThat(pendingItems).hasSize(9);
        assertThat(headerInfo.state()).hasValue(ItemState.ADDED);
        assertThat(preProofInfo.state()).hasValue(ItemState.ADDED);
        assertThat(proofInfo.state()).hasValue(ItemState.ADDED);

        block.processPendingItems(4);

        assertThat(pendingItems).isEmpty();
        assertThat(block.numRequestsCreated()).isEqualTo(3);

        final PublishStreamRequest request1 = block.getRequest(0);
        assertThat(request1.blockItems().blockItems()).hasSize(4);
        final PublishStreamRequest request2 = block.getRequest(1);
        assertThat(request2.blockItems().blockItems()).hasSize(4);
        final PublishStreamRequest request3 = block.getRequest(2);
        assertThat(request3.blockItems().blockItems()).hasSize(1);

        assertThat(headerInfo.state()).hasValue(ItemState.PACKED);
        assertThat(preProofInfo.state()).hasValue(ItemState.PACKED);
        assertThat(proofInfo.state()).hasValue(ItemState.PACKED);
    }

    @Test
    void testMarkRequestSent_invalidRequest() {
        assertThatThrownBy(() -> block.markRequestSent(-10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid request index: -10");
    }

    @Test
    void testMarkRequestSent_valid_notProof() {
        final ItemInfo proofInfo = proofItemInfo();
        final Map<Integer, RequestWrapper> requestsByIndex = requestsByIndex();

        block.addItem(newBlockProofItem());
        assertThat(proofInfo.state()).hasValue(ItemState.ADDED);

        proofInfo.packedInRequest(0);
        final RequestWrapper rw = new RequestWrapper(1, newRequest(newBlockTxItem()), new AtomicBoolean(false));
        requestsByIndex.put(1, rw);

        block.markRequestSent(1);
        assertThat(proofInfo.state()).hasValue(ItemState.PACKED);
        assertThat(block.isBlockProofSent()).isFalse();
        assertThat(rw.isSent()).isTrue();
    }

    @Test
    void testMarkRequestSent_valid_withProof() {
        final ItemInfo proofInfo = proofItemInfo();
        final Map<Integer, RequestWrapper> requestsByIndex = requestsByIndex();

        block.addItem(newBlockProofItem());
        assertThat(proofInfo.state()).hasValue(ItemState.ADDED);

        proofInfo.packedInRequest(0);
        final RequestWrapper rw = new RequestWrapper(0, newRequest(newBlockProofItem()), new AtomicBoolean(false));
        requestsByIndex.put(0, rw);

        block.markRequestSent(0);
        assertThat(proofInfo.state()).hasValue(ItemState.SENT);
        assertThat(block.isBlockProofSent()).isTrue();
        assertThat(rw.isSent()).isTrue();
    }

    // Utilities

    private Queue<BlockItem> pendingItems() {
        return (Queue<BlockItem>) pendingItemsHandle.get(block);
    }

    private Map<Integer, RequestWrapper> requestsByIndex() {
        return (Map<Integer, RequestWrapper>) requestsHandle.get(block);
    }

    private ItemInfo headerItemInfo() {
        return (ItemInfo) headerItemHandle.get(block);
    }

    private ItemInfo preProofItemInfo() {
        return (ItemInfo) preProofItemHandle.get(block);
    }

    private ItemInfo proofItemInfo() {
        return (ItemInfo) proofItemHandle.get(block);
    }

    private PublishStreamRequest newRequest(final BlockItem... items) {
        final BlockItemSet bis = BlockItemSet.newBuilder().blockItems(items).build();
        return PublishStreamRequest.newBuilder().blockItems(bis).build();
    }
}
