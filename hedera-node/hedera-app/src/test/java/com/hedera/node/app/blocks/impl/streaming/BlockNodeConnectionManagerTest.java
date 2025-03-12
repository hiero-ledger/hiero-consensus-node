// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.protoc.BlockItemSet;
import com.hedera.hapi.block.protoc.BlockStreamServiceGrpc;
import com.hedera.hapi.block.protoc.PublishStreamRequest;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.platform.event.EventTransaction;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith({MockitoExtension.class})
class BlockNodeConnectionManagerTest {
    private BlockNodeConnectionManager blockNodeConnectionManager;

    @Mock
    private ConfigProvider mockConfigProvider;

    @BeforeEach
    void setUp() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.writerMode", "FILE_AND_GRPC")
                .withValue("blockStream.blockNodeConnectionFileDir", "./src/test/resources/bootstrap")
                .getOrCreateConfig();
        given(mockConfigProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        blockNodeConnectionManager = new BlockNodeConnectionManager(mockConfigProvider);
    }

    @Test
    void testNewBlockNodeConnectionManager() {
        final var expectedGrpcEndpoint =
                BlockStreamServiceGrpc.getPublishBlockStreamMethod().getBareMethodName();
        assertEquals(expectedGrpcEndpoint, blockNodeConnectionManager.getGrpcEndPoint());
    }

    @Test
    void testCreatePublishStreamRequests() throws ParseException {
        // Create dummy block items
        BlockItem item1 = BlockItem.newBuilder()
                .eventTransaction(EventTransaction.newBuilder()
                        .applicationTransaction(Bytes.wrap("tx1"))
                        .build())
                .build();
        BlockItem item2 = BlockItem.newBuilder()
                .eventTransaction(EventTransaction.newBuilder()
                        .applicationTransaction(Bytes.wrap("tx2"))
                        .build())
                .build();
        BlockItem item3 = BlockItem.newBuilder()
                .eventTransaction(EventTransaction.newBuilder()
                        .applicationTransaction(Bytes.wrap("tx3"))
                        .build())
                .build();

        // Create bytes from block items
        List<Bytes> itemBytes = new ArrayList<>();
        itemBytes.add(BlockItem.PROTOBUF.toBytes(item1));
        itemBytes.add(BlockItem.PROTOBUF.toBytes(item2));
        itemBytes.add(BlockItem.PROTOBUF.toBytes(item3));

        BlockState mockBlock = mock(BlockState.class);
        when(mockBlock.itemBytes()).thenReturn(itemBytes);

        // When
        int batchSize = 2;
        List<PublishStreamRequest> requests =
                BlockNodeConnectionManager.createPublishStreamRequests(mockBlock, batchSize);

        // Then
        // Should create 2 batches: [item1, item2] and [item3]
        assertEquals(2, requests.size(), "Should create 2 batches");

        // Verify first batch contains 2 items
        BlockItemSet firstBatch = requests.get(0).getBlockItems();
        assertEquals(2, firstBatch.getBlockItemsCount(), "First batch should contain 2 items");

        // Verify second batch contains 1 item
        BlockItemSet secondBatch = requests.get(1).getBlockItems();
        assertEquals(1, secondBatch.getBlockItemsCount(), "Second batch should contain 1 item");

        // Verify the items in batches
        assertEquals(
                item1,
                BlockItem.PROTOBUF.parse(Bytes.wrap(firstBatch.getBlockItems(0).toByteArray())),
                "First item in first batch should be item1");
        assertEquals(
                item2,
                BlockItem.PROTOBUF.parse(Bytes.wrap(firstBatch.getBlockItems(1).toByteArray())),
                "Second item in first batch should be item2");
        assertEquals(
                item3,
                BlockItem.PROTOBUF.parse(Bytes.wrap(secondBatch.getBlockItems(0).toByteArray())),
                "First item in second batch should be item3");
    }

    @Test
    void testCreatePublishStreamRequestsWithEmptyBlock() {
        // Given
        int batchSize = 2;
        List<Bytes> emptyItemBytes = new ArrayList<>();

        // Mock BlockState
        BlockState mockBlockState = mock(BlockState.class);
        when(mockBlockState.itemBytes()).thenReturn(emptyItemBytes);

        // When
        List<PublishStreamRequest> requests =
                BlockNodeConnectionManager.createPublishStreamRequests(mockBlockState, batchSize);

        // Then
        assertEquals(0, requests.size(), "Should create no batches for empty block");
    }
}
