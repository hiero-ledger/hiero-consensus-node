// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.BlockHeader;
import org.hiero.block.api.PublishStreamRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BlockState}.
 */
public class BlockStateTest {

    private BlockState blockState;

    @BeforeEach
    void setUp() {
        // Initialize BlockState with a test block number
        blockState = new BlockState(1L);
    }

    @Test
    void testBlockStateInitialization() {
        // Verify initial state
        assertThat(blockState.blockNumber()).isEqualTo(1L);
        assertThat(blockState.requestsCompleted()).isFalse();
        assertThat(blockState.completionTimestamp()).isNull();
        assertThat(blockState.requestsSize()).isEqualTo(0);
    }

    @Test
    void testAddItem() {
        // Add an item to the block state
        BlockItem item = newBlockHeaderItem();
        blockState.addItem(item);

        // Verify the Requests size is still 0
        assertThat(blockState.requestsSize()).isEqualTo(0);
    }

    @Test
    void testGetRequestByIndex() {
        // Add an item to the block state
        BlockItem item = newBlockHeaderItem();
        blockState.addItem(item);

        // Verify the Requests size is still 0
        assertThat(blockState.requestsSize()).isEqualTo(0);

        blockState.createRequestFromCurrentItems(1, false);

        // Get the request by index
        PublishStreamRequest request = blockState.getRequest(0);

        // Verify the retrieved item is the same as the added item
        assertThat(request.blockItems().blockItems().getFirst().equals(item)).isTrue();
    }

    @Test
    void testSetRequestsCompleted() {
        // Set requests completed to true
        blockState.setRequestsCompleted();

        // Verify the requests completed status
        assertThat(blockState.requestsCompleted()).isTrue();
    }

    @Test
    void testSetCompletionTimestamp() {
        // Set a completion timestamp
        blockState.setCompletionTimestamp();

        // Verify the completion timestamp
        assertThat(blockState.completionTimestamp()).isNotNull();
    }

    @Test
    void testCreateRequestFromCurrentItemsForcingPublishStreamRequestCreation() {
        // Add an item to the block state
        BlockItem item = newBlockHeaderItem();
        blockState.addItem(item);

        // Verify the Requests size is still 0
        assertThat(blockState.requestsSize()).isEqualTo(0);

        // Create a request from current items, forcing PublishStreamRequest creation
        blockState.createRequestFromCurrentItems(2, true);

        // Verify the Requests size is now 1
        assertThat(blockState.requestsSize()).isEqualTo(1);
    }

    @Test
    void testCreateRequestFromCurrentItemsBatchNotCreated() {
        // Add an item to the block state
        BlockItem item = newBlockHeaderItem();
        blockState.addItem(item);

        // Verify the Requests size is still 0
        assertThat(blockState.requestsSize()).isEqualTo(0);

        // Create a request from current items, forcing PublishStreamRequest creation
        blockState.createRequestFromCurrentItems(2, false);

        // Verify the Requests size is now 1
        assertThat(blockState.requestsSize()).isEqualTo(0);
    }

    private static BlockItem newBlockHeaderItem() {
        return BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().build())
                .build();
    }
}
