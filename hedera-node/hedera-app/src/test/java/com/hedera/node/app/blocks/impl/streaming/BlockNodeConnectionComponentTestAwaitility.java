// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.pbj.runtime.grpc.Pipeline;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.hiero.block.api.BlockItemSet;
import org.hiero.block.api.BlockStreamPublishServiceInterface;
import org.hiero.block.api.PublishStreamRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockNodeConnectionComponentTestAwaitility extends BlockNodeCommunicationTestBase {

    private static final VarHandle streamingBlockNumberHandle;
    private static final VarHandle workerThreadRefHandle;

    static {
        try {
            final MethodHandles.Lookup lookup = MethodHandles.lookup();
            streamingBlockNumberHandle = MethodHandles.privateLookupIn(BlockNodeConnection.class, lookup)
                    .findVarHandle(BlockNodeConnection.class, "streamingBlockNumber", AtomicLong.class);
            workerThreadRefHandle = MethodHandles.privateLookupIn(BlockNodeConnection.class, lookup)
                    .findVarHandle(BlockNodeConnection.class, "workerThreadRef", AtomicReference.class);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BlockNodeConnection connection;
    private ConfigProvider configProvider;
    private BlockNodeConfiguration nodeConfig;
    private BlockNodeConnectionManager connectionManager;
    private BlockBufferService bufferService;
    private BlockStreamPublishServiceInterface.BlockStreamPublishServiceClient grpcServiceClient;
    private BlockStreamMetrics metrics;
    private Pipeline<? super PublishStreamRequest> requestPipeline;
    private ScheduledExecutorService executorService;
    private ExecutorService pipelineExecutor;
    private BlockNodeClientFactory clientFactory;

    private ExecutorService realExecutor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void beforeEach() throws Exception {
        configProvider = createConfigProvider(createDefaultConfigProvider());
        nodeConfig = newBlockNodeConfig(8080, 1);
        connectionManager = mock(BlockNodeConnectionManager.class);
        bufferService = mock(BlockBufferService.class);
        grpcServiceClient = mock(BlockStreamPublishServiceInterface.BlockStreamPublishServiceClient.class);
        metrics = mock(BlockStreamMetrics.class);
        requestPipeline = mock(Pipeline.class);
        executorService = mock(ScheduledExecutorService.class);
        pipelineExecutor = mock(ExecutorService.class);

        // Set up default behavior for pipelineExecutor using a real executor
        realExecutor = Executors.newCachedThreadPool();
        lenient()
                .doAnswer(invocation -> {
                    final Runnable runnable = invocation.getArgument(0);
                    return realExecutor.submit(runnable);
                })
                .when(pipelineExecutor)
                .submit(any(Runnable.class));

        lenient()
                .doAnswer(invocation -> {
                    realExecutor.shutdown();
                    return null;
                })
                .when(pipelineExecutor)
                .shutdown();

        lenient()
                .doAnswer(invocation -> {
                    final long timeout = invocation.getArgument(0);
                    final TimeUnit unit = invocation.getArgument(1);
                    return realExecutor.awaitTermination(timeout, unit);
                })
                .when(pipelineExecutor)
                .awaitTermination(anyLong(), any(TimeUnit.class));

        clientFactory = mock(BlockNodeClientFactory.class);
        lenient()
                .doReturn(grpcServiceClient)
                .when(clientFactory)
                .createStreamingClient(any(BlockNodeConfiguration.class), any(Duration.class));
        connection = new BlockNodeConnection(
                configProvider,
                nodeConfig,
                connectionManager,
                bufferService,
                metrics,
                executorService,
                pipelineExecutor,
                null,
                clientFactory);

        // Unlike unit tests, we do NOT set a fake worker thread here
        // This allows real worker threads to be spawned during tests

        lenient().doReturn(requestPipeline).when(grpcServiceClient).publishBlockStream(connection);
    }

    @AfterEach
    void afterEach() throws Exception {
        if (realExecutor != null) {
            realExecutor.shutdownNow();
        }

        // Set the connection to closed so the worker thread stops gracefully
        connection.updateConnectionState(ConnectionState.CLOSED);
        final AtomicReference<Thread> workerThreadRef = workerThreadRef();

        // Wait for worker thread to terminate
        final Thread workerThread = workerThreadRef.get();
        if (workerThread != null) {
            assertThat(workerThread.join(Duration.ofSeconds(2))).isTrue();
        }
    }

    @Test
    void testConnectionWorker_sendRequests() throws Exception {
        openConnectionAndResetMocks();
        final AtomicReference<Thread> workerThreadRef = workerThreadRef();
        workerThreadRef.set(null); // clear the fake worker thread
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        streamingBlockNumber.set(10);

        final BlockState block = new BlockState(10);

        doReturn(block).when(bufferService).getBlockState(10);

        final ArgumentCaptor<PublishStreamRequest> requestCaptor = ArgumentCaptor.forClass(PublishStreamRequest.class);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        // sleep to let the worker detect the state change and start doing work
        Thread.sleep(100);

        // add the header to the block, then wait for the max request delay... a request with the header should be sent
        final BlockItem item1 = newBlockHeaderItem();
        block.addItem(item1);

        Thread.sleep(400);
        verify(requestPipeline, atLeastOnce()).onNext(requestCaptor.capture());
        final List<PublishStreamRequest> requests1 = requestCaptor.getAllValues();
        reset(requestPipeline);

        assertThat(requests1).hasSize(1);
        assertRequestContainsItems(requests1.getFirst(), item1);

        // add multiple small items to the block and wait for them to be sent in one batch
        final BlockItem item2 = newBlockTxItem(15);
        final BlockItem item3 = newBlockTxItem(20);
        final BlockItem item4 = newBlockTxItem(50);
        block.addItem(item2);
        block.addItem(item3);
        block.addItem(item4);

        Thread.sleep(400);

        verify(requestPipeline, atLeastOnce()).onNext(requestCaptor.capture());
        final List<PublishStreamRequest> requests2 = requestCaptor.getAllValues();
        reset(requestPipeline);
        requests2.removeAll(requests1);
        assertRequestContainsItems(requests2, item2, item3, item4);

        // add a large item and a smaller item
        final BlockItem item5 = newBlockTxItem(2_097_000);
        final BlockItem item6 = newBlockTxItem(1_000_250);
        block.addItem(item5);
        block.addItem(item6);

        Thread.sleep(500);

        verify(requestPipeline, times(2)).onNext(requestCaptor.capture());
        final List<PublishStreamRequest> requests3 = requestCaptor.getAllValues();
        reset(requestPipeline);
        requests3.removeAll(requests1);
        requests3.removeAll(requests2);
        // there should be two requests since the items together exceed the max per request
        assertThat(requests3).hasSize(2);
        assertRequestContainsItems(requests3, item5, item6);

        // now add some more items and the block proof, then close the block
        // after these requests are sent, we should see the worker loop move to the next block
        final BlockItem item7 = newBlockTxItem(100);
        final BlockItem item8 = newBlockTxItem(250);
        final BlockItem item9 = newPreProofBlockStateChangesItem();
        final BlockItem item10 = newBlockProofItem(10, 1_420_910);
        block.addItem(item7);
        block.addItem(item8);
        block.addItem(item9);
        block.addItem(item10);
        block.closeBlock();

        Thread.sleep(500);

        verify(requestPipeline, atLeastOnce()).onNext(requestCaptor.capture());
        final List<PublishStreamRequest> requests4 = requestCaptor.getAllValues();
        final int totalRequestsSent = requests4.size();
        final int endOfBlockRequest = 1;

        reset(requestPipeline);
        requests4.removeAll(requests1);
        requests4.removeAll(requests2);
        requests4.removeAll(requests3);
        assertRequestContainsItems(requests4, item7, item8, item9, item10);
        assertThat(requests4.getLast()).isEqualTo(createRequest(10));

        assertThat(streamingBlockNumber).hasValue(11);

        // Stop the worker thread before verifying no more interactions to avoid race conditions
        connection.updateConnectionState(ConnectionState.CLOSING);
        final Thread workerThread = workerThreadRef.get();
        if (workerThread != null) {
            assertThat(workerThread.join(Duration.ofSeconds(2))).isTrue();
        }

        verify(metrics, times(endOfBlockRequest)).recordRequestSent(PublishStreamRequest.RequestOneOfType.END_OF_BLOCK);
        verify(metrics, times(totalRequestsSent - endOfBlockRequest))
                .recordRequestSent(PublishStreamRequest.RequestOneOfType.BLOCK_ITEMS);
        verify(metrics, times(totalRequestsSent - endOfBlockRequest)).recordBlockItemsSent(anyInt());
        verify(metrics, times(totalRequestsSent)).recordRequestLatency(anyLong());
        verify(connectionManager).recordBlockProofSent(eq(connection.configuration()), eq(10L), any(Instant.class));
        verify(bufferService, atLeastOnce()).getBlockState(10);
        verify(bufferService, atLeastOnce()).getBlockState(11);
        verify(bufferService, atLeastOnce()).getEarliestAvailableBlockNumber();
        verify(metrics, atLeastOnce()).recordStreamingBlockNumber(anyLong());
        verify(metrics, atLeastOnce()).recordRequestBlockItemCount(anyInt());
        verify(metrics, atLeastOnce()).recordRequestBytes(anyLong());
        verify(metrics, atLeastOnce()).recordLatestBlockEndOfBlockSent(anyLong());
        verify(metrics, atLeastOnce()).recordHeaderSentToBlockEndSentLatency(anyLong());
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(requestPipeline);
    }

    /**
     * IMPROVED VERSION: Same test as above but uses Awaitility instead of Thread.sleep().
     *
     * <p>The original test uses 5 Thread.sleep() calls totaling ~1900ms:
     * <ul>
     *   <li>Thread.sleep(100) - wait for worker to detect state change</li>
     *   <li>Thread.sleep(400) - wait for first item request</li>
     *   <li>Thread.sleep(400) - wait for batched items request</li>
     *   <li>Thread.sleep(500) - wait for large items request</li>
     *   <li>Thread.sleep(500) - wait for block proof request</li>
     * </ul>
     *
     * <p>With Awaitility, the test completes as soon as each condition is met,
     * typically saving 500-1000ms per test run.
     */
    @Test
    @DisplayName("IMPROVED: Worker loop processes items - using Awaitility instead of Thread.sleep()")
    void testConnectionWorker_sendRequests_withAwaitility() throws Exception {
        openConnectionAndResetMocks();
        final AtomicReference<Thread> workerThreadRef = workerThreadRef();
        workerThreadRef.set(null); // clear the fake worker thread
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        streamingBlockNumber.set(10);

        final BlockState block = new BlockState(10);

        doReturn(block).when(bufferService).getBlockState(10);

        final ArgumentCaptor<PublishStreamRequest> requestCaptor = ArgumentCaptor.forClass(PublishStreamRequest.class);

        connection.updateConnectionState(ConnectionState.ACTIVE);

        // IMPROVEMENT: Instead of Thread.sleep(100), poll until worker starts
        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> workerThreadRef.get() != null);

        // add the header to the block
        final BlockItem item1 = newBlockHeaderItem();
        block.addItem(item1);

        // IMPROVEMENT: Instead of Thread.sleep(400), poll until request is sent
        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> verify(requestPipeline, atLeastOnce()).onNext(any()));

        verify(requestPipeline, atLeastOnce()).onNext(requestCaptor.capture());
        final List<PublishStreamRequest> requests1 = requestCaptor.getAllValues();
        reset(requestPipeline);

        assertThat(requests1).hasSize(1);
        assertRequestContainsItems(requests1.getFirst(), item1);

        // add multiple small items to the block
        final BlockItem item2 = newBlockTxItem(15);
        final BlockItem item3 = newBlockTxItem(20);
        final BlockItem item4 = newBlockTxItem(50);
        block.addItem(item2);
        block.addItem(item3);
        block.addItem(item4);

        // IMPROVEMENT: Instead of Thread.sleep(400), poll until batch is sent
        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> verify(requestPipeline, atLeastOnce()).onNext(any()));

        verify(requestPipeline, atLeastOnce()).onNext(requestCaptor.capture());
        final List<PublishStreamRequest> requests2 = requestCaptor.getAllValues();
        reset(requestPipeline);
        requests2.removeAll(requests1);
        assertRequestContainsItems(requests2, item2, item3, item4);

        // add a large item and a smaller item
        final BlockItem item5 = newBlockTxItem(2_097_000);
        final BlockItem item6 = newBlockTxItem(1_000_250);
        block.addItem(item5);
        block.addItem(item6);

        // IMPROVEMENT: Instead of Thread.sleep(500), poll until both requests sent
        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> verify(requestPipeline, times(2)).onNext(any()));

        verify(requestPipeline, times(2)).onNext(requestCaptor.capture());
        final List<PublishStreamRequest> requests3 = requestCaptor.getAllValues();
        reset(requestPipeline);
        requests3.removeAll(requests1);
        requests3.removeAll(requests2);
        // there should be two requests since the items together exceed the max per request
        assertThat(requests3).hasSize(2);
        assertRequestContainsItems(requests3, item5, item6);

        // now add some more items and the block proof, then close the block
        final BlockItem item7 = newBlockTxItem(100);
        final BlockItem item8 = newBlockTxItem(250);
        final BlockItem item9 = newPreProofBlockStateChangesItem();
        final BlockItem item10 = newBlockProofItem(10, 1_420_910);
        block.addItem(item7);
        block.addItem(item8);
        block.addItem(item9);
        block.addItem(item10);
        block.closeBlock();

        // IMPROVEMENT: Instead of Thread.sleep(500), poll until block number increments
        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> streamingBlockNumber.get() == 11);

        verify(requestPipeline, atLeastOnce()).onNext(requestCaptor.capture());
        final List<PublishStreamRequest> requests4 = requestCaptor.getAllValues();
        final int totalRequestsSent = requests4.size();
        final int endOfBlockRequest = 1;

        reset(requestPipeline);
        requests4.removeAll(requests1);
        requests4.removeAll(requests2);
        requests4.removeAll(requests3);
        assertRequestContainsItems(requests4, item7, item8, item9, item10);
        assertThat(requests4.getLast()).isEqualTo(createRequest(10));

        assertThat(streamingBlockNumber).hasValue(11);

        // Stop the worker thread before verifying no more interactions to avoid race conditions
        connection.updateConnectionState(ConnectionState.CLOSING);
        final Thread workerThread = workerThreadRef.get();
        if (workerThread != null) {
            assertThat(workerThread.join(Duration.ofSeconds(2))).isTrue();
        }

        verify(metrics, times(endOfBlockRequest)).recordRequestSent(PublishStreamRequest.RequestOneOfType.END_OF_BLOCK);
        verify(metrics, times(totalRequestsSent - endOfBlockRequest))
                .recordRequestSent(PublishStreamRequest.RequestOneOfType.BLOCK_ITEMS);
        verify(metrics, times(totalRequestsSent - endOfBlockRequest)).recordBlockItemsSent(anyInt());
        verify(metrics, times(totalRequestsSent)).recordRequestLatency(anyLong());
        verify(connectionManager).recordBlockProofSent(eq(connection.configuration()), eq(10L), any(Instant.class));
        verify(bufferService, atLeastOnce()).getBlockState(10);
        verify(bufferService, atLeastOnce()).getBlockState(11);
        verify(bufferService, atLeastOnce()).getEarliestAvailableBlockNumber();
        verify(metrics, atLeastOnce()).recordStreamingBlockNumber(anyLong());
        verify(metrics, atLeastOnce()).recordRequestBlockItemCount(anyInt());
        verify(metrics, atLeastOnce()).recordRequestBytes(anyLong());
        verify(metrics, atLeastOnce()).recordLatestBlockEndOfBlockSent(anyLong());
        verify(metrics, atLeastOnce()).recordHeaderSentToBlockEndSentLatency(anyLong());
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(requestPipeline);
    }

    // Utilities

    private void openConnectionAndResetMocks() {
        connection.initialize();
        // reset the mocks interactions to remove tracked interactions as a result of starting the connection
        reset(connectionManager, requestPipeline, bufferService, metrics);
    }

    private void assertRequestContainsItems(final PublishStreamRequest request, final BlockItem... expectedItems) {
        assertRequestContainsItems(List.of(request), expectedItems);
    }

    private void assertRequestContainsItems(
            final List<PublishStreamRequest> requests, final BlockItem... expectedItems) {
        final List<BlockItem> actualItems = new ArrayList<>();
        for (final PublishStreamRequest request : requests) {
            final BlockItemSet bis = request.blockItems();
            if (bis != null) {
                actualItems.addAll(bis.blockItems());
            }
        }

        assertThat(actualItems).hasSize(expectedItems.length);

        for (int i = 0; i < actualItems.size(); ++i) {
            final BlockItem actualItem = actualItems.get(i);
            assertThat(actualItem)
                    .withFailMessage("Block item at index " + i + " different. Expected: " + expectedItems[i]
                            + " but found " + actualItem)
                    .isSameAs(expectedItems[i]);
        }
    }

    private AtomicLong streamingBlockNumber() {
        return (AtomicLong) streamingBlockNumberHandle.get(connection);
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<Thread> workerThreadRef() {
        return (AtomicReference<Thread>) workerThreadRefHandle.get(connection);
    }
}
