// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnection.ConnectionState;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeHelidonGrpcConfiguration;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeHelidonHttpConfiguration;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.pbj.grpc.client.helidon.PbjGrpcClientConfig;
import com.hedera.pbj.runtime.grpc.Pipeline;
import com.hedera.pbj.runtime.grpc.ServiceInterface.RequestOptions;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import io.helidon.webclient.api.WebClient;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.block.api.BlockEnd;
import org.hiero.block.api.BlockItemSet;
import org.hiero.block.api.BlockStreamPublishServiceInterface.BlockStreamPublishServiceClient;
import org.hiero.block.api.PublishStreamRequest;
import org.hiero.block.api.PublishStreamRequest.EndStream;
import org.hiero.block.api.PublishStreamRequest.RequestOneOfType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Component tests for BlockNodeConnection that use real worker threads.
 * These tests spawn actual worker threads and test end-to-end behavior with timing dependencies.
 */
@ExtendWith(MockitoExtension.class)
class BlockNodeConnectionComponentTest extends BlockNodeCommunicationTestBase {
    private static final VarHandle streamingBlockNumberHandle;
    private static final VarHandle workerThreadRefHandle;
    private static final MethodHandle sendRequestHandle;

    static {
        try {
            final Lookup lookup = MethodHandles.lookup();
            streamingBlockNumberHandle = MethodHandles.privateLookupIn(BlockNodeConnection.class, lookup)
                    .findVarHandle(BlockNodeConnection.class, "streamingBlockNumber", AtomicLong.class);
            workerThreadRefHandle = MethodHandles.privateLookupIn(BlockNodeConnection.class, lookup)
                    .findVarHandle(BlockNodeConnection.class, "workerThreadRef", AtomicReference.class);

            final Method sendRequest =
                    BlockNodeConnection.class.getDeclaredMethod("sendRequest", BlockNodeConnection.StreamRequest.class);
            sendRequest.setAccessible(true);
            sendRequestHandle = lookup.unreflect(sendRequest);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BlockNodeConnection connection;
    private ConfigProvider configProvider;
    private BlockNodeConfiguration nodeConfig;
    private BlockNodeConnectionManager connectionManager;
    private BlockBufferService bufferService;
    private BlockStreamPublishServiceClient grpcServiceClient;
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
        grpcServiceClient = mock(BlockStreamPublishServiceClient.class);
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
                .createClient(any(WebClient.class), any(PbjGrpcClientConfig.class), any(RequestOptions.class));
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
    void testConnectionWorkerLifecycle() throws Exception {
        final AtomicReference<Thread> workerThreadRef = workerThreadRef();
        workerThreadRef.set(null); // clear out the fake worker thread so a real one can be initialized

        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // the act of having the connection go active will start the worker thread
        final Thread workerThread = workerThreadRef.get();
        assertThat(workerThread).isNotNull();
        assertThat(workerThread.isAlive()).isTrue();

        // set the connection state to closing. this will terminate the worker thread
        connection.updateConnectionState(ConnectionState.CLOSING);

        // Wait for worker thread to actually terminate
        assertThat(workerThread.join(Duration.ofSeconds(2))).isTrue();

        assertThat(workerThreadRef).hasNullValue();
        assertThat(workerThread.isAlive()).isFalse();
    }

    @Test
    void testWorkerConstructor_respectsMaxMessageSizeFromProtocolConfig() throws Exception {
        // Provide a protocol config with a smaller max message size than the hard cap
        final int softLimitBytes = 1_000_000;
        final int hardLimitBytes = 2_000_000;

        // Recreate connection with a protocol config that sets a smaller max message size
        final BlockNodeClientFactory localFactory = mock(BlockNodeClientFactory.class);
        lenient()
                .doReturn(grpcServiceClient)
                .when(localFactory)
                .createClient(any(WebClient.class), any(PbjGrpcClientConfig.class), any(RequestOptions.class));

        final BlockNodeConfiguration cfgWithMax = BlockNodeConfiguration.newBuilder()
                .address(nodeConfig.address())
                .port(nodeConfig.port())
                .priority(nodeConfig.priority())
                .messageSizeSoftLimitBytes(softLimitBytes)
                .messageSizeHardLimitBytes(hardLimitBytes)
                .clientGrpcConfig(BlockNodeHelidonGrpcConfiguration.DEFAULT)
                .clientHttpConfig(BlockNodeHelidonHttpConfiguration.DEFAULT)
                .build();

        connection = new BlockNodeConnection(
                configProvider,
                cfgWithMax,
                connectionManager,
                bufferService,
                metrics,
                executorService,
                pipelineExecutor,
                null,
                localFactory);

        // Ensure publish stream returns pipeline
        lenient().doReturn(requestPipeline).when(grpcServiceClient).publishBlockStream(connection);

        // These methods may be called during error handling (timing-dependent race condition)
        lenient().doReturn(5L).when(bufferService).getEarliestAvailableBlockNumber();
        lenient().doReturn(4L).when(bufferService).getHighestAckedBlockNumber();

        // Start the connection to trigger worker construction
        final AtomicReference<Thread> workerThreadRef = workerThreadRef();
        workerThreadRef.set(null);

        // Feed one item just over configuredMax to force fatal branch if limit not respected
        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(5);
        final BlockState block = new BlockState(5);
        final BlockItem header = newBlockHeaderItem(5);
        block.addItem(header);
        // Slightly over configuredMax to ensure split/end if not honored
        final BlockItem tooLarge = newBlockTxItem(hardLimitBytes + 10);
        block.addItem(tooLarge);
        doReturn(block).when(bufferService).getBlockState(5);

        // Set up latch to wait for connection to close (error handling)
        final CountDownLatch connectionClosedLatch = new CountDownLatch(1);
        doAnswer(invocation -> {
                    connectionClosedLatch.countDown();
                    return null;
                })
                .when(connectionManager)
                .notifyConnectionClosed(connection);

        connection.createRequestPipeline();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Wait for connection to close due to oversized item
        assertThat(connectionClosedLatch.await(2, TimeUnit.SECONDS))
                .as("Connection should close due to oversized item")
                .isTrue();

        // Should have sent header, then ended stream due to size violation under configured limit
        verify(requestPipeline, atLeastOnce()).onNext(any(PublishStreamRequest.class));
        verify(connectionManager).notifyConnectionClosed(connection);
    }

    @Test
    void testConnectionWorker_sendPendingRequest_multiItemRequestExceedsSoftLimit() throws Exception {
        final TestConfigBuilder cfgBuilder = createDefaultConfigProvider()
                .withValue("blockNode.streamingRequestPaddingBytes", "0")
                .withValue("blockNode.streamingRequestItemPaddingBytes", "0");
        configProvider = createConfigProvider(cfgBuilder);
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

        lenient().doReturn(requestPipeline).when(grpcServiceClient).publishBlockStream(connection);
        openConnectionAndResetMocks();
        final AtomicReference<Thread> workerThreadRef = workerThreadRef();
        workerThreadRef.set(null); // clear the fake worker thread
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        streamingBlockNumber.set(10);

        final BlockNodeConfiguration config = connection.getNodeConfig();
        // sanity check to make sure the sizes we are about to use are within the scope of the soft and hard limits
        assertThat(config.messageSizeSoftLimitBytes()).isEqualTo(2_097_152L); // soft limit = 2 MB
        assertThat(config.messageSizeHardLimitBytes()).isEqualTo(6_292_480L); // hard limit = 6 MB + 1 KB

        final BlockState block = new BlockState(10);
        doReturn(block).when(bufferService).getBlockState(10);
        /*
        Items 1, 2, and 3 are sized such that, given a request padding of 0 and an item padding of 0, during the pending
        request building phase where the size is estimated, the total estimated size will be exactly the soft limit size
        of 2_097_152. When we try to send the request, we will build the real PublishStreamRequest and validate the
        actual size. During this phase, the size will exceed the soft limit size (approximately 2_097_167). This will
        trigger a rebuilding of the pending request where the last item is removed to ensure the request adheres to the
        soft limit. The last item (item 3) will get sent in a subsequent request along with item 4.
         */
        final BlockItem item1 = newBlockTxItem(2_095_148);
        final BlockItem item2 = newBlockTxItem(997);
        final BlockItem item3 = newBlockTxItem(997);
        final BlockItem item4 = newBlockTxItem(1_500);

        block.addItem(item1);
        block.addItem(item2);
        block.addItem(item3);
        block.addItem(item4);
        block.closeBlock();

        // Set up latch to wait for END_OF_BLOCK to be recorded
        final CountDownLatch endOfBlockLatch = new CountDownLatch(1);
        doAnswer(invocation -> {
                    RequestOneOfType type = invocation.getArgument(0);
                    if (type == RequestOneOfType.END_OF_BLOCK) {
                        endOfBlockLatch.countDown();
                    }
                    return null;
                })
                .when(metrics)
                .recordRequestSent(any(RequestOneOfType.class));

        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Wait for the worker thread to send END_OF_BLOCK
        assertThat(endOfBlockLatch.await(2, TimeUnit.SECONDS))
                .as("Worker thread should send END_OF_BLOCK")
                .isTrue();

        final ArgumentCaptor<PublishStreamRequest> requestCaptor = ArgumentCaptor.forClass(PublishStreamRequest.class);
        verify(requestPipeline, times(3)).onNext(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues()).hasSize(3);
        final List<PublishStreamRequest> requests = requestCaptor.getAllValues();

        final PublishStreamRequest req1 = requests.get(0);
        assertThat(req1.blockItemsOrElse(BlockItemSet.DEFAULT).blockItems())
                .hasSize(2)
                .containsExactly(item1, item2);

        final PublishStreamRequest req2 = requests.get(1);
        assertThat(req2.blockItemsOrElse(BlockItemSet.DEFAULT).blockItems())
                .hasSize(2)
                .containsExactly(item3, item4);

        final PublishStreamRequest req3 = requests.get(2);
        assertThat(req3.endOfBlockOrElse(BlockEnd.DEFAULT).blockNumber()).isEqualTo(block.blockNumber());

        verify(metrics).recordMultiItemRequestExceedsSoftLimit();
        verify(metrics, times(3)).recordRequestLatency(anyLong());
        verify(metrics, times(2)).recordBlockItemsSent(2);
        verify(metrics, times(2)).recordRequestSent(RequestOneOfType.BLOCK_ITEMS);
        verify(metrics).recordRequestSent(RequestOneOfType.END_OF_BLOCK);
        verify(metrics, atLeastOnce()).recordRequestBlockItemCount(anyInt());
        verify(metrics, atLeastOnce()).recordRequestBytes(anyLong());
        verify(metrics, atLeastOnce()).recordStreamingBlockNumber(anyLong());
        verify(metrics, atLeastOnce()).recordLatestBlockEndOfBlockSent(anyLong());

        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoInteractions(connectionManager);
    }

    @Test
    void testConnectionWorker_sendPendingRequest_singleItemRequestExceedsHardLimit() throws Exception {
        final TestConfigBuilder cfgBuilder = createDefaultConfigProvider()
                .withValue("blockNode.streamingRequestPaddingBytes", "0")
                .withValue("blockNode.streamingRequestItemPaddingBytes", "0");
        configProvider = createConfigProvider(cfgBuilder);
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

        lenient().doReturn(requestPipeline).when(grpcServiceClient).publishBlockStream(connection);
        openConnectionAndResetMocks();
        final AtomicReference<Thread> workerThreadRef = workerThreadRef();
        workerThreadRef.set(null); // clear the fake worker thread
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        streamingBlockNumber.set(10);

        final BlockNodeConfiguration config = connection.getNodeConfig();
        // sanity check to make sure the sizes we are about to use are within the scope of the soft and hard limits
        assertThat(config.messageSizeSoftLimitBytes()).isEqualTo(2_097_152L); // soft limit = 2 MB
        assertThat(config.messageSizeHardLimitBytes()).isEqualTo(6_292_480L); // hard limit = 6 MB + 1 KB

        final BlockState block = new BlockState(10);
        doReturn(block).when(bufferService).getBlockState(10);
        /*
        The item is sized such that, given a request padding of 0 and an item padding of 0, during the pending request
        building phase where the size is estimated, the total estimated size will be exactly the hard limit size
        of 6_292_480. When we try to send the request, we will build the real PublishStreamRequest and validate the
        actual size. During this phase, the size will exceed the hard limit size (approximately 6_292_490). Since it has
        exceeded the hard limit, the item will not get sent and the connection will be closed.
         */
        final BlockItem item = newBlockTxItem(6_292_475);

        block.addItem(item);

        // Set up latch to wait for connection closure after error
        final CountDownLatch connectionClosedLatch = new CountDownLatch(1);
        doAnswer(invocation -> {
                    connectionClosedLatch.countDown();
                    return null;
                })
                .when(metrics)
                .recordConnectionClosed();

        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Wait for the worker thread to close the connection due to oversized item
        assertThat(connectionClosedLatch.await(2, TimeUnit.SECONDS))
                .as("Worker thread should close connection due to oversized item")
                .isTrue();

        final ArgumentCaptor<PublishStreamRequest> requestCaptor = ArgumentCaptor.forClass(PublishStreamRequest.class);
        verify(requestPipeline).onNext(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues()).hasSize(1);
        final List<PublishStreamRequest> requests = requestCaptor.getAllValues();
        final PublishStreamRequest req1 = requests.getFirst();
        final EndStream endStream = req1.endStream();
        assertThat(endStream).isNotNull();
        assertThat(endStream.endCode()).isEqualTo(EndStream.Code.ERROR);

        verify(metrics).recordRequestExceedsHardLimit();
        verify(metrics).recordRequestEndStreamSent(EndStream.Code.ERROR);
        verify(metrics).recordRequestLatency(anyLong());
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verify(requestPipeline).onComplete();
        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getHighestAckedBlockNumber();
        verify(connectionManager).notifyConnectionClosed(connection);
        verify(metrics, atLeastOnce()).recordStreamingBlockNumber(anyLong());

        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testConnectionWorker_sendMultipleBlocks() {
        openConnectionAndResetMocks();
        final AtomicReference<Thread> workerThreadRef = workerThreadRef();
        workerThreadRef.set(null); // clear the fake worker thread
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        final BlockState block1 = new BlockState(1);
        final BlockState block2 = new BlockState(2);
        final BlockState block3 = new BlockState(3);
        final BlockState block4 = new BlockState(4);
        final BlockState block5 = new BlockState(5);
        final BlockState block6 = new BlockState(6);

        streamingBlockNumber.set(block1.blockNumber());

        doReturn(block1).when(bufferService).getBlockState(block1.blockNumber());
        doReturn(block2).when(bufferService).getBlockState(block2.blockNumber());
        doReturn(block3).when(bufferService).getBlockState(block3.blockNumber());
        doReturn(block4).when(bufferService).getBlockState(block4.blockNumber());
        doReturn(block5).when(bufferService).getBlockState(block5.blockNumber());
        doReturn(block6).when(bufferService).getBlockState(block6.blockNumber());

        final BlockNodeConfiguration config = connection.getNodeConfig();
        // sanity check to make sure the sizes we are about to use are within the scope of the soft and hard limits
        assertThat(config.messageSizeSoftLimitBytes()).isEqualTo(2_097_152L); // soft limit = 2 MB
        assertThat(config.messageSizeHardLimitBytes()).isEqualTo(6_292_480L); // hard limit = 6 MB + 1 KB

        final List<BlockItem> block1Items = newRandomBlockItems(block1.blockNumber(), 2_500_000);
        block1Items.forEach(block1::addItem);
        block1.closeBlock();
        final List<BlockItem> block2Items = newRandomBlockItems(block2.blockNumber(), 2_500_000);
        block2Items.forEach(block2::addItem);
        block2.closeBlock();
        final List<BlockItem> block3Items = newRandomBlockItems(block3.blockNumber(), 2_500_000);
        block3Items.forEach(block3::addItem);
        block3.closeBlock();
        final List<BlockItem> block4Items = newRandomBlockItems(block4.blockNumber(), 2_500_000);
        block4Items.forEach(block4::addItem);
        block4.closeBlock();
        final List<BlockItem> block5Items = newRandomBlockItems(block5.blockNumber(), 2_500_000);
        block5Items.forEach(block5::addItem);
        block5.closeBlock();

        final List<BlockItem> allItems = new ArrayList<>();
        allItems.addAll(block1Items);
        allItems.addAll(block2Items);
        allItems.addAll(block3Items);
        allItems.addAll(block4Items);
        allItems.addAll(block5Items);

        final ArgumentCaptor<PublishStreamRequest> requestCaptor = ArgumentCaptor.forClass(PublishStreamRequest.class);

        connection.updateConnectionState(ConnectionState.ACTIVE);

        // wait up to 10 seconds for all block ends to be sent
        verify(metrics, timeout(10_000).times(5)).recordRequestSent(RequestOneOfType.END_OF_BLOCK);
        verify(requestPipeline, atLeast(10)).onNext(requestCaptor.capture());

        // there should be at least 6 requests (3 for items and 3 for block ends)
        final List<PublishStreamRequest> requests = requestCaptor.getAllValues();
        assertThat(requests).hasSizeGreaterThanOrEqualTo(6);

        final Set<Long> blockNumbers = new HashSet<>(Set.of(
                block1.blockNumber(),
                block2.blockNumber(),
                block3.blockNumber(),
                block4.blockNumber(),
                block5.blockNumber()));
        final List<BlockItem> blockItems = new ArrayList<>();

        for (final PublishStreamRequest request : requests) {
            final BlockItemSet bis = request.blockItems();
            if (bis != null) {
                blockItems.addAll(bis.blockItems());
            }
            final BlockEnd blockEnd = request.endOfBlock();
            if (blockEnd != null) {
                assertThat(blockNumbers.remove(blockEnd.blockNumber())).isTrue();
            }
        }

        assertThat(blockNumbers).as("BlockEnd should be found for each block").isEmpty();

        assertThat(blockItems).containsExactly(allItems.toArray(new BlockItem[0]));

        verify(metrics, times(requests.size())).recordRequestLatency(anyLong());
        verify(metrics, times(requests.size() - 5)).recordRequestSent(RequestOneOfType.BLOCK_ITEMS);

        final ArgumentCaptor<Integer> numItemsSentCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(metrics, atLeastOnce()).recordBlockItemsSent(numItemsSentCaptor.capture());
        final int itemsSentCount = numItemsSentCaptor.getAllValues().stream().reduce(0, Integer::sum);
        assertThat(itemsSentCount).isEqualTo(allItems.size());

        verify(bufferService, atLeast(6)).getBlockState(anyLong());
        verify(connectionManager, times(5))
                .recordBlockProofSent(any(BlockNodeConfiguration.class), anyLong(), any(Instant.class));
        verify(metrics, atLeastOnce()).recordStreamingBlockNumber(anyLong());
        verify(metrics, atLeastOnce()).recordRequestBlockItemCount(anyInt());
        verify(metrics, atLeastOnce()).recordRequestBytes(anyLong());
        verify(metrics, atLeastOnce()).recordLatestBlockEndOfBlockSent(anyLong());
        verify(metrics, atLeastOnce()).recordHeaderSentToBlockEndSentLatency(anyLong());
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testWorkerThread() throws Exception {
        openConnectionAndResetMocks();
        final AtomicReference<Thread> workerThreadRef = workerThreadRef();
        workerThreadRef.set(null); // clear the fake worker thread so a real one can be initialized

        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(10);

        final BlockState block = new BlockState(10);
        final BlockItem header = newBlockHeaderItem(10);
        block.addItem(header);
        block.closeBlock(); // Close the block to force sending
        doReturn(block).when(bufferService).getBlockState(10);
        lenient().doReturn(null).when(bufferService).getBlockState(11L); // Next block doesn't exist

        // Use a latch on END_OF_BLOCK metric recording to ensure it's fully processed
        final CountDownLatch endOfBlockLatch = new CountDownLatch(1);
        doAnswer(invocation -> {
                    RequestOneOfType type = invocation.getArgument(0);
                    if (type == RequestOneOfType.END_OF_BLOCK) {
                        endOfBlockLatch.countDown();
                    }
                    return null;
                })
                .when(metrics)
                .recordRequestSent(any(RequestOneOfType.class));

        // Create the request pipeline before starting the worker thread
        connection.createRequestPipeline();

        // Start the actual worker thread
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Wait for the worker thread to record END_OF_BLOCK metric
        assertThat(endOfBlockLatch.await(2, TimeUnit.SECONDS))
                .as("Worker thread should send the block header and EndOfBlock")
                .isTrue();

        assertThat(workerThreadRef).doesNotHaveNullValue();
        verify(requestPipeline, times(2)).onNext(any(PublishStreamRequest.class));
        verify(metrics).recordRequestSent(RequestOneOfType.BLOCK_ITEMS);
        verify(metrics).recordRequestSent(RequestOneOfType.END_OF_BLOCK);
        verify(metrics).recordBlockItemsSent(1);
        verify(metrics, times(2)).recordRequestLatency(anyLong());
    }

    @Test
    void testCloseAtBlockBoundary_noActiveBlock() throws Exception {
        // re-create the connection so we get the worker thread to run
        final long blockNumber = 10;
        // indicate we want to start with block 10, but don't add the block to the buffer

        connection = new BlockNodeConnection(
                configProvider,
                nodeConfig,
                connectionManager,
                bufferService,
                metrics,
                executorService,
                pipelineExecutor,
                blockNumber, // start streaming with block 10
                clientFactory);

        lenient().doReturn(requestPipeline).when(grpcServiceClient).publishBlockStream(connection);

        connection.createRequestPipeline();
        connection.updateConnectionState(ConnectionState.ACTIVE); // this will start the worker thread

        final Thread workerThread = workerThreadRef().get();
        assertThat(workerThread).isNotNull();

        // signal to close at the block boundary
        connection.closeAtBlockBoundary();

        // the worker should determine there is no block available to stream and with the flag enabled to close at the
        // nearest block boundary, the connection should be closed without sending any items

        // Wait for worker thread to complete (which means close is done)
        assertThat(workerThread.join(Duration.ofSeconds(2))).isTrue();

        // now the connection should be closed and all the items are sent
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CLOSED);

        final ArgumentCaptor<PublishStreamRequest> requestCaptor = ArgumentCaptor.forClass(PublishStreamRequest.class);

        // only one request should be sent and it should be the EndStream message
        verify(requestPipeline).onNext(requestCaptor.capture());

        assertThat(requestCaptor.getAllValues()).hasSize(1);
        final PublishStreamRequest req = requestCaptor.getAllValues().getFirst();
        final EndStream endStream = req.endStream();
        assertThat(endStream).isNotNull();
        assertThat(endStream.endCode()).isEqualTo(EndStream.Code.RESET);

        verify(requestPipeline).onComplete();
        verify(bufferService, atLeastOnce()).getBlockState(blockNumber);
        verify(bufferService, atLeastOnce()).getEarliestAvailableBlockNumber();
        verify(bufferService).getHighestAckedBlockNumber();
        verify(metrics).recordConnectionOpened();
        verify(metrics).recordRequestLatency(anyLong());
        verify(metrics).recordRequestEndStreamSent(EndStream.Code.RESET);
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);

        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testCloseAtBlockBoundary_activeBlock() throws Exception {
        // re-create the connection so we get the worker thread to run
        final long blockNumber = 10;
        final BlockState block = new BlockState(blockNumber);
        lenient().when(bufferService.getBlockState(blockNumber)).thenReturn(block);

        connection = new BlockNodeConnection(
                configProvider,
                nodeConfig,
                connectionManager,
                bufferService,
                metrics,
                executorService,
                pipelineExecutor,
                blockNumber, // start streaming with block 10
                clientFactory);

        lenient().doReturn(requestPipeline).when(grpcServiceClient).publishBlockStream(connection);

        connection.createRequestPipeline();
        connection.updateConnectionState(ConnectionState.ACTIVE); // this will start the worker thread

        final Thread workerThread = workerThreadRef().get();
        assertThat(workerThread).isNotNull();

        block.addItem(newBlockHeaderItem(blockNumber));
        block.addItem(newBlockTxItem(1_345));

        // now signal to close the connection at the block boundary
        connection.closeAtBlockBoundary();

        // add more items including the proof and ensure they are all sent
        block.addItem(newBlockTxItem(5_039));
        block.addItem(newBlockTxItem(590));
        block.addItem(newBlockProofItem(blockNumber, 3_501));
        block.closeBlock();

        // Wait for worker thread to complete (which means close is done)
        assertThat(workerThread.join(Duration.ofSeconds(2))).isTrue();

        // now the connection should be closed and all the items are sent
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CLOSED);

        final ArgumentCaptor<PublishStreamRequest> requestCaptor = ArgumentCaptor.forClass(PublishStreamRequest.class);

        /*
        There should be at least 3 requests.
        All items, in order are:
        1) Block header         <-+
        2) Signed transaction     |
        3) Signed transaction     +- 1 or more requests
        4) Signed transaction     |
        5) Block proof          <-+
        6) Block end            <--- single request
        7) EndStream with RESET <--- single request
         */

        verify(requestPipeline, atLeast(3)).onNext(requestCaptor.capture());
        final List<PublishStreamRequest> requests = requestCaptor.getAllValues();

        final PublishStreamRequest lastRequest = requests.getLast();
        final EndStream endStream = lastRequest.endStream();
        assertThat(endStream).isNotNull();
        assertThat(endStream.endCode()).isEqualTo(EndStream.Code.RESET);

        final PublishStreamRequest secondToLastRequest = requests.get(requests.size() - 2);
        final BlockEnd blockEnd = secondToLastRequest.endOfBlock();
        assertThat(blockEnd).isNotNull();
        assertThat(blockEnd.blockNumber()).isEqualTo(blockNumber);

        // collect the block items
        final List<BlockItem> items = new ArrayList<>();
        for (int i = 0; i < requests.size() - 2; ++i) {
            final PublishStreamRequest request = requests.get(i);
            final BlockItemSet bis = request.blockItems();
            if (bis != null) {
                items.addAll(bis.blockItems());
            }
        }

        // there should be 5 items
        assertThat(items).hasSize(5);
        for (int i = 0; i < 5; ++i) {
            final BlockItem blockItem = items.get(i);

            if (i == 0) {
                // the first item should be the block header
                final com.hedera.hapi.block.stream.output.BlockHeader header = blockItem.blockHeader();
                assertThat(header).isNotNull();
                assertThat(header.number()).isEqualTo(blockNumber);
            } else if (i == 4) {
                // the last item should be the block proof
                final com.hedera.hapi.block.stream.BlockProof proof = blockItem.blockProof();
                assertThat(proof).isNotNull();
                assertThat(proof.block()).isEqualTo(blockNumber);
            } else {
                // the other items should all be signed transactions
                assertThat(blockItem.signedTransaction()).isNotNull();
            }
        }

        verify(requestPipeline).onComplete();
        verify(bufferService, atLeastOnce()).getBlockState(blockNumber);
        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getHighestAckedBlockNumber();
        verify(metrics, atLeastOnce()).recordRequestBlockItemCount(anyInt());
        verify(metrics, atLeastOnce()).recordRequestBytes(anyLong());
        verify(metrics, atLeastOnce()).recordLatestBlockEndOfBlockSent(anyLong());
        verify(metrics, atLeastOnce()).recordHeaderSentToBlockEndSentLatency(anyLong());
        verify(metrics, atLeastOnce()).recordStreamingBlockNumber(anyLong());
        verify(metrics).recordConnectionOpened();
        verify(metrics, atLeastOnce()).recordRequestLatency(anyLong());
        verify(metrics, atLeastOnce()).recordRequestSent(RequestOneOfType.BLOCK_ITEMS);
        verify(metrics, atLeastOnce()).recordBlockItemsSent(anyInt());
        verify(metrics).recordRequestSent(RequestOneOfType.END_OF_BLOCK);
        verify(metrics).recordRequestEndStreamSent(EndStream.Code.RESET);
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);

        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(bufferService);
    }

    /**
     * Tests InterruptedException handling during pipeline operation.
     */
    @Test
    void testSendRequest_interruptedException() throws Exception {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        final CountDownLatch threadBlockingLatch = new CountDownLatch(1);
        final CountDownLatch waitLatch = new CountDownLatch(1);
        final AtomicReference<RuntimeException> exceptionRef = new AtomicReference<>();

        // Make the pipeline block until interrupted
        doAnswer(invocation -> {
                    threadBlockingLatch.countDown(); // Signal that we've started blocking
                    try {
                        waitLatch.await(); // Block until interrupted
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted", e);
                    }
                    return null;
                })
                .when(requestPipeline)
                .onNext(any());

        final PublishStreamRequest request = createRequest(newBlockHeaderItem());

        // Send request in a separate thread
        final Thread testThread = Thread.ofVirtual().start(() -> {
            try {
                sendRequest(new BlockNodeConnection.BlockItemsStreamRequest(request, 1L, 1, 1, false, false));
            } catch (RuntimeException e) {
                exceptionRef.set(e);
            }
        });

        assertThat(threadBlockingLatch.await(2, TimeUnit.SECONDS))
                .as("Thread should start blocking")
                .isTrue();

        // Interrupt the thread
        testThread.interrupt();

        // Wait for thread to complete
        assertThat(testThread.join(Duration.ofSeconds(2))).isTrue();

        // Verify exception was thrown
        assertThat(exceptionRef.get()).isNotNull();
        assertThat(exceptionRef.get().getMessage()).contains("Interrupted while waiting for pipeline.onNext()");
        assertThat(exceptionRef.get().getCause()).isInstanceOf(InterruptedException.class);
    }

    /**
     * Tests InterruptedException handling when pipeline.onComplete() is interrupted during close.
     * Uses mocks to simulate an interruption without actually waiting, making the test fast.
     */
    @Test
    void testClose_onCompleteInterruptedException() throws Exception {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Create a mock Future that will throw InterruptedException when get() is called
        @SuppressWarnings("unchecked")
        final Future<Object> mockFuture = mock(Future.class);
        when(mockFuture.get(anyLong(), any(TimeUnit.class)))
                .thenThrow(new InterruptedException("Simulated interruption"));

        // Set up the pipelineExecutor to return mock future
        doReturn(mockFuture).when(pipelineExecutor).submit(any(Runnable.class));

        // Close connection in a separate thread to verify interrupt status is restored
        final AtomicBoolean isInterrupted = new AtomicBoolean(false);
        final CountDownLatch latch = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            try {
                connection.close(true);
            } finally {
                isInterrupted.set(Thread.currentThread().isInterrupted());
                latch.countDown();
            }
        });

        // Wait for the close operation to complete
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // Verify interruption was handled gracefully
        verify(mockFuture).get(anyLong(), any(TimeUnit.class));
        verify(metrics).recordConnectionClosed();

        // Connection should still be CLOSED despite interruption
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CLOSED);

        assertThat(isInterrupted.get()).isTrue();
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

        verify(metrics, times(endOfBlockRequest)).recordRequestSent(RequestOneOfType.END_OF_BLOCK);
        verify(metrics, times(totalRequestsSent - endOfBlockRequest)).recordRequestSent(RequestOneOfType.BLOCK_ITEMS);
        verify(metrics, times(totalRequestsSent - endOfBlockRequest)).recordBlockItemsSent(anyInt());
        verify(metrics, times(totalRequestsSent)).recordRequestLatency(anyLong());
        verify(connectionManager).recordBlockProofSent(eq(connection.getNodeConfig()), eq(10L), any(Instant.class));
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
        connection.createRequestPipeline();
        // reset the mocks interactions to remove tracked interactions as a result of starting the connection
        reset(connectionManager, requestPipeline, bufferService, metrics);
    }

    private AtomicLong streamingBlockNumber() {
        return (AtomicLong) streamingBlockNumberHandle.get(connection);
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<Thread> workerThreadRef() {
        return (AtomicReference<Thread>) workerThreadRefHandle.get(connection);
    }

    private void sendRequest(final BlockNodeConnection.StreamRequest request) {
        sendRequest(connection, request);
    }

    private void sendRequest(final BlockNodeConnection connection, final BlockNodeConnection.StreamRequest request) {
        try {
            sendRequestHandle.invoke(connection, request);
        } catch (final Throwable e) {
            if (e instanceof final RuntimeException re) {
                throw re;
            } else {
                throw new RuntimeException(e);
            }
        }
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
}
