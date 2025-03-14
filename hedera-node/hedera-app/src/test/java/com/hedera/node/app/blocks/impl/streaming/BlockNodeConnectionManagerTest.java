// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.BlockItemSet;
import com.hedera.hapi.block.PublishStreamRequest;
import com.hedera.hapi.block.protoc.BlockStreamServiceGrpc;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.platform.event.EventTransaction;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.fixtures.util.LogCaptureExtension;
import com.hedera.node.app.spi.fixtures.util.LoggingSubject;
import com.hedera.node.app.spi.fixtures.util.LoggingTarget;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith({MockitoExtension.class, LogCaptureExtension.class})
class BlockNodeConnectionManagerTest {
    private static final Duration INITIAL_DELAY = Duration.ofMillis(10);

    @LoggingSubject
    BlockNodeConnectionManager blockNodeConnectionManager;

    @LoggingTarget
    private LogCaptor logCaptor;

    @Mock
    ConfigProvider mockConfigProvider;

    @Mock
    private Supplier<Void> mockSupplier;

    @Mock
    BlockNodeConnection mockConnection;

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
        List<byte[]> itemBytes = new ArrayList<>();
        itemBytes.add(BlockItem.PROTOBUF.toBytes(item1).toByteArray());
        itemBytes.add(BlockItem.PROTOBUF.toBytes(item2).toByteArray());
        itemBytes.add(BlockItem.PROTOBUF.toBytes(item3).toByteArray());

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
        BlockItemSet firstBatch = requests.get(0).blockItems();
        assertEquals(2, firstBatch.blockItems().size(), "First batch should contain 2 items");

        // Verify second batch contains 1 item
        BlockItemSet secondBatch = requests.get(1).blockItems();
        assertEquals(1, secondBatch.blockItems().size(), "Second batch should contain 1 item");

        // Verify the items in batches
        assertEquals(item1, firstBatch.blockItems().get(0), "First item in first batch should be item1");
        assertEquals(item2, firstBatch.blockItems().get(1), "Second item in first batch should be item2");
        assertEquals(item3, secondBatch.blockItems().get(0), "First item in second batch should be item3");
    }

    @Test
    void testCreatePublishStreamRequestsWithEmptyBlock() {
        // Given
        int batchSize = 2;
        List<byte[]> emptyItemBytes = new ArrayList<>();

        // Mock BlockState
        BlockState mockBlockState = mock(BlockState.class);
        when(mockBlockState.itemBytes()).thenReturn(emptyItemBytes);

        // When
        List<PublishStreamRequest> requests =
                BlockNodeConnectionManager.createPublishStreamRequests(mockBlockState, batchSize);

        // Then
        assertEquals(0, requests.size(), "Should create no batches for empty block");
    }

    @Test
    void testRetry_SuccessOnFirstAttempt() {
        blockNodeConnectionManager.retry(mockSupplier, INITIAL_DELAY);

        verify(mockSupplier, times(1)).get();

        assertThat(logCaptor.infoLogs()).containsAnyElementsOf(generateExpectedRetryLogs(INITIAL_DELAY));
    }

    @Test
    void testRetry_SuccessOnRetry() {
        when(mockSupplier.get())
                .thenThrow(new RuntimeException("First attempt failed"))
                .thenReturn(null);

        blockNodeConnectionManager.retry(mockSupplier, INITIAL_DELAY);

        verify(mockSupplier, times(2)).get();
        assertThat(logCaptor.infoLogs()).containsAnyElementsOf(generateExpectedRetryLogs(INITIAL_DELAY));
        assertThat(logCaptor.infoLogs())
                .containsAnyElementsOf(generateExpectedRetryLogs(INITIAL_DELAY.multipliedBy(2)));
    }

    @Test
    void testScheduleReconnect() throws InterruptedException {
        blockNodeConnectionManager.scheduleReconnect(mockConnection);

        verifyNoInteractions(mockConnection); // there should be no immediate attempt to establish a stream

        Thread.sleep(BlockNodeConnectionManager.INITIAL_RETRY_DELAY.plusMillis(100));

        assertThat(logCaptor.infoLogs()).containsAnyElementsOf(generateExpectedRetryLogs(Duration.ofSeconds(1L)));
        verify(mockConnection, times(1)).establishStream();
    }

    private List<String> generateExpectedRetryLogs(Duration delay) {
        final long start = delay.toMillis() / 2;
        final long end = delay.toMillis();
        final List<String> logs = new ArrayList<>();
        for (long i = start; i <= end; i++) {
            logs.add(String.format("Retrying in %d ms", i));
        }

        return logs;
    }
}
