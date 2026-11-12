// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.internal.BlockItemSetBytes;
import com.hedera.hapi.block.internal.EndStreamBytes;
import com.hedera.hapi.block.internal.PublishStreamRequestBytes;
import com.hedera.hapi.block.internal.PublishStreamRequestBytes.RequestOneOfType;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.blocks.impl.streaming.BlockNodePublisherConnection.PublisherRequest;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeEndpoint;
import com.hedera.node.app.blocks.impl.streaming.obs.BlockStreamingObs;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.types.BlockStreamGrpcCompressionType;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.grpc.GrpcCall;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.block.api.BlockEnd;
import org.hiero.block.api.PublishStreamRequest.EndStream;
import org.hiero.block.api.PublishStreamResponse;
import org.hiero.block.api.PublishStreamResponse.EndOfStream;
import org.hiero.block.api.PublishStreamResponse.EndOfStream.Code;
import org.hiero.block.api.PublishStreamResponse.ResponseOneOfType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockNodeStreamingConnectionTest extends BlockNodeCommunicationTestBase {

    private static final long NODE_ID = 0L;
    private static final Thread FAKE_WORKER_THREAD = new Thread(() -> {}, "fake-worker");
    private static final VarHandle streamingBlockNumberHandle;
    private static final VarHandle workerThreadRefHandle;
    private static final MethodHandle sendRequestHandle;
    private static final MethodHandle updateAcknowledgementMetricsHandle;

    static {
        try {
            final Lookup lookup = MethodHandles.lookup();
            final Class<?> streamingCls = BlockNodeStreamingConnection.class;
            final Class<?> publisherCls = BlockNodePublisherConnection.class;

            streamingBlockNumberHandle = MethodHandles.privateLookupIn(streamingCls, lookup)
                    .findVarHandle(streamingCls, "streamingBlockNumber", AtomicLong.class);
            workerThreadRefHandle = MethodHandles.privateLookupIn(streamingCls, lookup)
                    .findVarHandle(streamingCls, "workerThreadRef", AtomicReference.class);

            final Method sendRequest = publisherCls.getDeclaredMethod("sendRequest", PublisherRequest.class);
            sendRequest.setAccessible(true);
            sendRequestHandle = lookup.unreflect(sendRequest);

            final Method updateAcknowledgementMetrics =
                    streamingCls.getDeclaredMethod("updateAcknowledgementMetrics", long.class, long.class);
            updateAcknowledgementMetrics.setAccessible(true);
            updateAcknowledgementMetricsHandle = lookup.unreflect(updateAcknowledgementMetrics);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BlockNodeStreamingConnection connection;
    private ConfigProvider configProvider;
    private BlockNodeStats stats;
    private BlockNode blockNode;
    private BlockNodeEndpoint blockNodeStreamingEndpoint;
    private BlockNodeConnectionManager connectionManager;
    private BlockBufferService bufferService;
    private BlockStreamMetrics metrics;
    private GrpcCall<PublishStreamRequestBytes, PublishStreamResponse> requestCall;
    private ExecutorService pipelineExecutor;
    private BlockNodeStats.HighLatencyResult latencyResult;
    private BlockNodeClientFactory clientFactory;
    private BlockStreamingObs streamingObs;

    private ExecutorService realExecutor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void beforeEach() throws Exception {
        configProvider = createConfigProvider(createDefaultConfigProvider());
        stats = mock(BlockNodeStats.class);
        final BlockNodeConfiguration config = newBlockNodeConfig(8080, 1);
        blockNode = new BlockNode(configProvider, config, new AtomicInteger(), new AtomicInteger(), stats);
        blockNodeStreamingEndpoint = config.streamingEndpoint();
        connectionManager = mock(BlockNodeConnectionManager.class);
        bufferService = mock(BlockBufferService.class);
        final BlockStreamPublishBytesClient grpcServiceClient = mock(BlockStreamPublishBytesClient.class);
        metrics = mock(BlockStreamMetrics.class);
        requestCall = mock(GrpcCall.class);
        pipelineExecutor = mock(ExecutorService.class);
        latencyResult = mock(BlockNodeStats.HighLatencyResult.class);
        streamingObs = mock(BlockStreamingObs.class);

        // Set up default behavior for pipelineExecutor using a real executor
        // This ensures proper Future semantics while still being fast for tests
        // Individual tests can override this with their own specific mocks for timeout scenarios
        realExecutor = Executors.newCachedThreadPool();
        lenient()
                .doAnswer(invocation -> {
                    final Runnable runnable = invocation.getArgument(0);
                    return realExecutor.submit(runnable);
                })
                .when(pipelineExecutor)
                .submit(any(Runnable.class));

        // Also handle shutdown for cleanup
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
                .createStreamingClient(
                        any(BlockNodeConfiguration.class),
                        any(Duration.class),
                        anyString(),
                        any(BlockStreamGrpcCompressionType.class));
        connection = new BlockNodeStreamingConnection(
                configProvider,
                blockNode,
                connectionManager,
                bufferService,
                metrics,
                pipelineExecutor,
                null,
                clientFactory,
                NODE_ID,
                streamingObs);

        // To avoid potential non-deterministic effects due to the worker thread, assign a fake worker thread to the
        // connection that does nothing.
        final AtomicReference<Thread> workerThreadRef = workerThreadRef();
        workerThreadRef.set(FAKE_WORKER_THREAD);

        lenient().doReturn(requestCall).when(grpcServiceClient).publishBlockStream(connection);
    }

    @AfterEach
    void afterEach() throws Exception {
        if (realExecutor != null) {
            realExecutor.shutdownNow();
        }

        // set the connection to closed so the worker thread stops gracefully
        connection.updateConnectionState(ConnectionState.CLOSED);
        final AtomicReference<Thread> workerThreadRef = workerThreadRef();

        // Wait for worker thread to terminate
        final Thread workerThread = workerThreadRef.get();
        if (workerThread != null && !workerThread.equals(FAKE_WORKER_THREAD)) {
            assertThat(workerThread.join(Duration.ofSeconds(2))).isTrue();
        }
    }

    @Test
    void testOnActiveStateTransition_workerThreadNameIncludesJvmId() {
        // remove the fake worker thread so a real one is created when the connection becomes active
        workerThreadRef().set(null);
        activateConnection();

        final Thread workerThread = workerThreadRef().get();
        assertThat(workerThread).isNotNull();
        assertThat(workerThread.getName())
                .isEqualTo("bn-conn-worker-" + connection.connectionId())
                .matches("bn-conn-worker-N\\d+-STR\\d+-JVM[0-9a-z]{8}");
    }

    @Test
    void testConstructorWithInitialBlock() {
        // Create connection with initial block number
        connection = new BlockNodeStreamingConnection(
                configProvider,
                blockNode,
                connectionManager,
                bufferService,
                metrics,
                pipelineExecutor,
                100L,
                clientFactory,
                NODE_ID,
                streamingObs);

        // Verify the streamingBlockNumber was set
        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        assertThat(streamingBlockNumber).hasValue(100L);
    }

    @Test
    void testOnNext_acknowledgement_notStreaming() {
        activateConnection();

        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(-1); // pretend we are currently not streaming any blocks
        final PublishStreamResponse response = createBlockAckResponse(10L);
        final AtomicLong latestAckedBlock = new AtomicLong(0);
        doAnswer(inv -> {
                    final long block = inv.getArgument(0);
                    latestAckedBlock.set(block);

                    return block;
                })
                .when(bufferService)
                .acknowledgeBlock(anyLong(), any(BlockNodeEndpoint.class));
        doAnswer(_ -> latestAckedBlock.get()).when(bufferService).getHighestAckedBlockNumber();
        when(stats.recordAcknowledgementAndEvaluate(eq(10L), any(Instant.class), any(Duration.class), anyInt()))
                .thenReturn(latencyResult);
        when(latencyResult.shouldSwitch()).thenReturn(false);

        connection.onNext(response);

        assertThat(streamingBlockNumber).hasValue(11); // moved to acked block + 1

        verify(stats).recordAcknowledgementAndEvaluate(eq(10L), any(Instant.class), any(Duration.class), anyInt());
        verify(bufferService, atLeastOnce()).getLastBlockNumberProduced();
        verify(bufferService).acknowledgeBlock(10L, blockNodeStreamingEndpoint);
        verify(bufferService, times(2)).getHighestAckedBlockNumber();
        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(metrics).recordResponseReceived(ResponseOneOfType.ACKNOWLEDGEMENT);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(connectionManager);
    }

    @Test
    void testOnNext_acknowledgement_olderThanCurrentStreamingAndProducing() {
        activateConnection();

        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(10); // pretend we are streaming block 10
        final AtomicLong latestAckedBlock = new AtomicLong(0);
        doAnswer(inv -> {
                    final long block = inv.getArgument(0);
                    latestAckedBlock.set(block);

                    return block;
                })
                .when(bufferService)
                .acknowledgeBlock(anyLong(), any(BlockNodeEndpoint.class));
        doAnswer(_ -> latestAckedBlock.get()).when(bufferService).getHighestAckedBlockNumber();
        final PublishStreamResponse response = createBlockAckResponse(8L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(10L);
        when(stats.recordAcknowledgementAndEvaluate(eq(8L), any(Instant.class), any(Duration.class), anyInt()))
                .thenReturn(latencyResult);
        when(latencyResult.shouldSwitch()).thenReturn(false);

        connection.onNext(response);

        assertThat(streamingBlockNumber).hasValue(10L); // should not change

        verify(bufferService, atLeastOnce()).getLastBlockNumberProduced();
        verify(bufferService).acknowledgeBlock(8L, blockNodeStreamingEndpoint);
        verify(bufferService, times(2)).getHighestAckedBlockNumber();
        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(metrics).recordResponseReceived(ResponseOneOfType.ACKNOWLEDGEMENT);
        verify(bufferService, times(8)).getBlockState(anyLong());
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testOnNext_acknowledgement_newerThanCurrentProducing() {
        activateConnection();

        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(10); // pretend we are streaming block 10
        final PublishStreamResponse response = createBlockAckResponse(11L);

        final AtomicLong latestAckedBlock = new AtomicLong(0);
        doAnswer(inv -> {
                    final long block = inv.getArgument(0);
                    latestAckedBlock.set(block);

                    return block;
                })
                .when(bufferService)
                .acknowledgeBlock(anyLong(), any(BlockNodeEndpoint.class));
        doAnswer(_ -> latestAckedBlock.get()).when(bufferService).getHighestAckedBlockNumber();
        when(bufferService.getLastBlockNumberProduced()).thenReturn(10L);
        when(stats.recordAcknowledgementAndEvaluate(eq(11L), any(Instant.class), any(Duration.class), anyInt()))
                .thenReturn(latencyResult);
        when(latencyResult.shouldSwitch()).thenReturn(false);

        connection.onNext(response);

        assertThat(streamingBlockNumber).hasValue(12); // should be 1 + acked block number

        verify(bufferService, atLeastOnce()).getLastBlockNumberProduced();
        verify(bufferService).acknowledgeBlock(11L, blockNodeStreamingEndpoint);
        verify(bufferService, atLeastOnce()).getHighestAckedBlockNumber();
        verify(bufferService, atLeastOnce()).getEarliestAvailableBlockNumber();
        verify(metrics).recordResponseReceived(ResponseOneOfType.ACKNOWLEDGEMENT);
        verify(bufferService, times(10)).getBlockState(anyLong());
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testOnNext_acknowledgement_newerThanCurrentStreaming() {
        activateConnection();

        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(8); // pretend we are streaming block 8
        final PublishStreamResponse response = createBlockAckResponse(11L);

        final AtomicLong latestAckedBlock = new AtomicLong(0);
        doAnswer(inv -> {
                    final long block = inv.getArgument(0);
                    latestAckedBlock.set(block);

                    return block;
                })
                .when(bufferService)
                .acknowledgeBlock(anyLong(), any(BlockNodeEndpoint.class));
        doAnswer(_ -> latestAckedBlock.get()).when(bufferService).getHighestAckedBlockNumber();
        when(bufferService.getLastBlockNumberProduced()).thenReturn(12L);
        when(stats.recordAcknowledgementAndEvaluate(eq(11L), any(Instant.class), any(Duration.class), anyInt()))
                .thenReturn(latencyResult);
        when(latencyResult.shouldSwitch()).thenReturn(false);

        connection.onNext(response);

        assertThat(streamingBlockNumber).hasValue(12); // should be 1 + acked block number

        verify(bufferService, atLeastOnce()).getLastBlockNumberProduced();
        verify(bufferService).acknowledgeBlock(11L, blockNodeStreamingEndpoint);
        verify(bufferService, times(2)).getHighestAckedBlockNumber();
        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(metrics).recordResponseReceived(ResponseOneOfType.ACKNOWLEDGEMENT);
        verify(bufferService, times(11)).getBlockState(anyLong());
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(bufferService);
    }

    // Tests acknowledgement equal to current streaming/producing blocks (should not jump)
    @Test
    void testOnNext_acknowledgement_equalToCurrentStreamingAndProducing() {
        activateConnection();

        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(10); // pretend we are streaming block 10
        final PublishStreamResponse response = createBlockAckResponse(10L);

        final AtomicLong latestAckedBlock = new AtomicLong(0);
        doAnswer(inv -> {
                    final long block = inv.getArgument(0);
                    latestAckedBlock.set(block);

                    return block;
                })
                .when(bufferService)
                .acknowledgeBlock(anyLong(), any(BlockNodeEndpoint.class));
        doAnswer(_ -> latestAckedBlock.get()).when(bufferService).getHighestAckedBlockNumber();

        when(bufferService.getLastBlockNumberProduced()).thenReturn(10L);
        when(stats.recordAcknowledgementAndEvaluate(eq(10L), any(Instant.class), any(Duration.class), anyInt()))
                .thenReturn(latencyResult);
        when(latencyResult.shouldSwitch()).thenReturn(false);

        connection.onNext(response);

        // Should not jump to block since acknowledgement is not newer
        assertThat(streamingBlockNumber).hasValue(10L);

        verify(bufferService, atLeastOnce()).getLastBlockNumberProduced();
        verify(bufferService).acknowledgeBlock(10L, blockNodeStreamingEndpoint);
        verify(bufferService, atLeastOnce()).getHighestAckedBlockNumber();
        verify(bufferService, atLeastOnce()).getEarliestAvailableBlockNumber();
        verify(metrics).recordResponseReceived(ResponseOneOfType.ACKNOWLEDGEMENT);
        verify(bufferService, times(10)).getBlockState(anyLong());
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testOnNext_acknowledgement_highLatencyShouldSwitch() {
        activateConnection();

        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(10);
        final PublishStreamResponse response = createBlockAckResponse(10L);

        final AtomicLong latestAckedBlock = new AtomicLong(0);
        doAnswer(inv -> {
                    final long block = inv.getArgument(0);
                    latestAckedBlock.set(block);

                    return block;
                })
                .when(bufferService)
                .acknowledgeBlock(anyLong(), any(BlockNodeEndpoint.class));
        doAnswer(_ -> latestAckedBlock.get()).when(bufferService).getHighestAckedBlockNumber();
        when(bufferService.getLastBlockNumberProduced()).thenReturn(10L);
        when(stats.recordAcknowledgementAndEvaluate(eq(10L), any(Instant.class), any(Duration.class), anyInt()))
                .thenReturn(latencyResult);
        when(latencyResult.isHighLatency()).thenReturn(true);
        when(latencyResult.shouldSwitch()).thenReturn(true);
        when(latencyResult.consecutiveHighLatencyEvents()).thenReturn(5);

        connection.onNext(response);

        // Should not jump to block since acknowledgement is not newer
        assertThat(streamingBlockNumber).hasValue(10L);
        assertThat(connection.closeReason()).isEqualTo(CloseReason.BLOCK_NODE_HIGH_LATENCY);
        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);

        verify(bufferService, atLeastOnce()).getLastBlockNumberProduced();
        verify(bufferService, times(3)).getHighestAckedBlockNumber();
        verify(bufferService, times(2)).getEarliestAvailableBlockNumber();
        verify(bufferService).acknowledgeBlock(10L, blockNodeStreamingEndpoint);
        verify(bufferService, atLeastOnce()).getBlockState(anyLong());
        verify(metrics).recordResponseReceived(ResponseOneOfType.ACKNOWLEDGEMENT);
        verify(metrics).recordHighLatencyEvent();
        verify(metrics).recordConnectionClosed(CloseReason.BLOCK_NODE_HIGH_LATENCY);
        verify(metrics).recordRequestLatency(anyLong());
        verify(metrics).recordRequestEndStreamSent(EndStream.Code.TIMEOUT);
        verify(connectionManager).notifyConnectionClosed(connection);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(bufferService);
    }

    @ParameterizedTest
    @EnumSource(
            value = EndOfStream.Code.class,
            names = {"ERROR", "PERSISTENCE_FAILED"})
    void testOnNext_endOfStream_blockNodeInternalError(final EndOfStream.Code responseCode) {
        activateConnection();

        final PublishStreamResponse response = createEndOfStreamResponse(responseCode, 10L);
        connection.onNext(response);

        assertThat(connection.closeReason()).isEqualTo(CloseReason.END_STREAM_RECEIVED);

        verify(metrics).recordLatestBlockEndOfStream(10L);
        verify(metrics).recordResponseEndOfStreamReceived(responseCode);
        verify(metrics).recordConnectionClosed(CloseReason.END_STREAM_RECEIVED);
        verify(requestCall).completeRequests();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
    }

    @ParameterizedTest
    @EnumSource(
            value = EndOfStream.Code.class,
            names = {"TIMEOUT", "DUPLICATE_BLOCK", "BAD_BLOCK_PROOF", "INVALID_REQUEST"})
    void testOnNext_endOfStream_clientFailures(final EndOfStream.Code responseCode) {
        activateConnection();

        final PublishStreamResponse response = createEndOfStreamResponse(responseCode, 10L);
        connection.onNext(response);

        assertThat(connection.closeReason()).isEqualTo(CloseReason.TRANSIENT_END_STREAM_RECEIVED);

        verify(metrics).recordLatestBlockEndOfStream(10L);
        verify(metrics).recordResponseEndOfStreamReceived(responseCode);
        verify(metrics).recordConnectionClosed(CloseReason.TRANSIENT_END_STREAM_RECEIVED);
        verify(requestCall).completeRequests();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
    }

    @Test
    void testOnNext_endOfStream_blockNodeGracefulShutdown() {
        activateConnection();
        // STREAM_ITEMS_SUCCESS is sent when the block node is gracefully shutting down
        final PublishStreamResponse response = createEndOfStreamResponse(Code.SUCCESS, 10L);

        connection.onNext(response);

        assertThat(connection.closeReason()).isEqualTo(CloseReason.END_STREAM_RECEIVED);

        verify(metrics).recordLatestBlockEndOfStream(10L);
        verify(metrics).recordResponseEndOfStreamReceived(Code.SUCCESS);
        verify(metrics).recordConnectionClosed(CloseReason.END_STREAM_RECEIVED);
        verify(requestCall).completeRequests();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
    }

    @Test
    void testOnNext_blockNodeBehind_blockExists() {
        activateConnection();
        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        // We are ahead of the block node, streaming a block beyond the one it last verified (10).
        streamingBlockNumber.set(15L);
        final PublishStreamResponse response = createBlockNodeBehindResponse(10L);
        when(bufferService.getBlockState(11L)).thenReturn(new BlockState(11L, 2_000L));
        when(stats.shouldIgnoreBehindPublisher(any(Instant.class), any(Duration.class), any(Duration.class)))
                .thenReturn(false);
        when(stats.addBehindPublisherAndCheckLimit(any(Instant.class), anyInt(), any(Duration.class)))
                .thenReturn(false);

        connection.onNext(response);

        // Recovery path: the block node is behind but block N+1 is buffered, so the CN repositions
        // its stream to N+1 (10 + 1) and stays ACTIVE.
        assertThat(streamingBlockNumber).hasValue(11L);
        assertThat(connection.currentState()).isEqualTo(ConnectionState.ACTIVE);

        verify(metrics).recordLatestBlockBehindPublisher(10L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.NODE_BEHIND_PUBLISHER);
        verify(stats).shouldIgnoreBehindPublisher(any(Instant.class), any(Duration.class), any(Duration.class));
        verify(bufferService).getBlockState(11L);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
    }

    @Test
    void testOnNext_blockNodeBehind_blockDoesNotExist_tooFarBehind() {
        activateConnection();
        final PublishStreamResponse response = createBlockNodeBehindResponse(10L);
        when(bufferService.getBlockState(11L)).thenReturn(null);
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(12L);
        when(stats.shouldIgnoreBehindPublisher(any(Instant.class), any(Duration.class), any(Duration.class)))
                .thenReturn(false);

        connection.onNext(response);

        assertThat(connection.closeReason()).isEqualTo(CloseReason.BLOCK_NODE_BEHIND);

        verify(metrics).recordLatestBlockBehindPublisher(10L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.NODE_BEHIND_PUBLISHER);
        verify(metrics).recordConnectionClosed(CloseReason.BLOCK_NODE_BEHIND);
        verify(metrics).recordRequestEndStreamSent(EndStream.Code.TOO_FAR_BEHIND);
        verify(metrics).recordRequestLatency(anyLong());
        verify(bufferService, atLeastOnce()).getEarliestAvailableBlockNumber();
        verify(bufferService, atLeastOnce()).getHighestAckedBlockNumber();
        verify(bufferService).getBlockState(11L);
        verify(requestCall)
                .sendRequest(
                        PublishStreamRequestBytes.newBuilder()
                                .endStream(EndStreamBytes.newBuilder()
                                        .endCode(EndStream.Code.TOO_FAR_BEHIND)
                                        .earliestBlockNumber(12L)
                                        .build())
                                .build(),
                        false);
        verify(requestCall).completeRequests();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
    }

    @Test
    void testOnNext_endOfStream_blockNodeBehind_blockDoesNotExist_error() {
        activateConnection();
        final PublishStreamResponse response = createBlockNodeBehindResponse(10L);
        when(bufferService.getHighestAckedBlockNumber()).thenReturn(10L);
        when(bufferService.getBlockState(11L)).thenReturn(null);
        when(stats.shouldIgnoreBehindPublisher(any(Instant.class), any(Duration.class), any(Duration.class)))
                .thenReturn(false);

        connection.onNext(response);

        assertThat(connection.closeReason()).isEqualTo(CloseReason.INTERNAL_ERROR);

        verify(metrics).recordLatestBlockBehindPublisher(10L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.NODE_BEHIND_PUBLISHER);
        verify(metrics).recordConnectionClosed(CloseReason.INTERNAL_ERROR);
        verify(metrics).recordRequestEndStreamSent(EndStream.Code.ERROR);
        verify(metrics).recordRequestLatency(anyLong());
        verify(stats).shouldIgnoreBehindPublisher(any(Instant.class), any(Duration.class), any(Duration.class));
        verify(bufferService, atLeastOnce()).getEarliestAvailableBlockNumber();
        verify(bufferService, atLeastOnce()).getHighestAckedBlockNumber();
        verify(bufferService).getBlockState(11L);
        verify(requestCall)
                .sendRequest(
                        PublishStreamRequestBytes.newBuilder()
                                .endStream(EndStreamBytes.newBuilder()
                                        .endCode(EndStream.Code.ERROR)
                                        .latestBlockNumber(10L)
                                        .build())
                                .build(),
                        false);
        verify(requestCall).completeRequests();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
    }

    @Test
    void testOnNext_endOfStream_itemsUnknown() {
        activateConnection();

        final PublishStreamResponse response = createEndOfStreamResponse(Code.UNKNOWN, 10L);
        connection.onNext(response);

        assertThat(connection.closeReason()).isEqualTo(CloseReason.END_STREAM_RECEIVED);

        verify(metrics).recordLatestBlockEndOfStream(10L);
        verify(metrics).recordResponseEndOfStreamReceived(Code.UNKNOWN);
        verify(metrics).recordConnectionClosed(CloseReason.END_STREAM_RECEIVED);
        verify(requestCall).completeRequests();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
    }

    @Test
    void testOnNext_skipBlock_sameAsStreaming() {
        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(25); // pretend we are currently streaming block 25
        final PublishStreamResponse response = createSkipBlock(25L);
        connection.onNext(response);

        assertThat(streamingBlockNumber).hasValue(26);

        verify(metrics).recordLatestBlockSkipBlock(25L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.SKIP_BLOCK);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testOnNext_skipBlockOlderBlock() {
        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(27); // pretend we are currently streaming block 27
        final PublishStreamResponse response = createSkipBlock(25L);

        connection.onNext(response);

        assertThat(streamingBlockNumber).hasValue(27);

        verify(metrics).recordLatestBlockSkipBlock(25L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.SKIP_BLOCK);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testOnNext_resendBlock_blockExists() {
        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(11); // pretend we are currently streaming block 11
        final PublishStreamResponse response = createResendBlock(10L);
        when(bufferService.getBlockState(10L)).thenReturn(new BlockState(10L, 2_000L));

        connection.onNext(response);

        assertThat(streamingBlockNumber).hasValue(10);

        verify(metrics).recordLatestBlockResendBlock(10L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.RESEND_BLOCK);
        verify(bufferService).getBlockState(10L);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testOnNext_resendBlock_blockDoesNotExist_tooFarBehind() {
        activateConnection();

        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(11); // pretend we are currently streaming block 11
        final PublishStreamResponse response = createResendBlock(10L);
        when(bufferService.getBlockState(10L)).thenReturn(null);
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(11L);

        connection.onNext(response);

        assertThat(connection.closeReason()).isEqualTo(CloseReason.BLOCK_NODE_BEHIND);

        verify(metrics).recordLatestBlockResendBlock(10L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.RESEND_BLOCK);
        verify(metrics).recordConnectionClosed(CloseReason.BLOCK_NODE_BEHIND);
        verify(metrics).recordRequestLatency(anyLong());
        verify(metrics).recordRequestEndStreamSent(EndStream.Code.TOO_FAR_BEHIND);
        verify(requestCall).sendRequest(createRequest(EndStream.Code.TOO_FAR_BEHIND, 11L), false);
        verify(requestCall).completeRequests();
        verify(connectionManager).notifyConnectionClosed(connection);
        verify(bufferService).getBlockState(10L);
        verify(bufferService, times(2)).getEarliestAvailableBlockNumber();
        verify(bufferService).getHighestAckedBlockNumber();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testOnNext_resendBlock_blockDoesNotExist_error() {
        activateConnection();

        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(11); // pretend we are currently streaming block 11
        final PublishStreamResponse response = createResendBlock(10L);
        when(bufferService.getBlockState(10L)).thenReturn(null);

        connection.onNext(response);

        assertThat(connection.closeReason()).isEqualTo(CloseReason.INTERNAL_ERROR);

        verify(metrics).recordLatestBlockResendBlock(10L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.RESEND_BLOCK);
        verify(metrics).recordConnectionClosed(CloseReason.INTERNAL_ERROR);
        verify(metrics).recordRequestLatency(anyLong());
        verify(metrics).recordRequestEndStreamSent(EndStream.Code.ERROR);
        verify(requestCall).sendRequest(createRequest(EndStream.Code.ERROR), false);
        verify(requestCall).completeRequests();
        verify(bufferService).getBlockState(10L);
        verify(bufferService, times(2)).getEarliestAvailableBlockNumber();
        verify(bufferService).getHighestAckedBlockNumber();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testOnNext_unknown() {
        activateConnection();

        final PublishStreamResponse response = new PublishStreamResponse(new OneOf<>(ResponseOneOfType.UNSET, null));
        connection.onNext(response);

        verify(metrics).recordUnknownResponseReceived();

        verifyNoMoreInteractions(metrics);
        verifyNoInteractions(requestCall);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testConnectionWorker_switchBlock_initialValue() throws Exception {
        activateConnection();
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        doReturn(101L).when(bufferService).getLastBlockNumberProduced();
        doReturn(new BlockState(101, 2_000L)).when(bufferService).getBlockState(101);

        assertThat(streamingBlockNumber).hasValue(-1);

        // Call doWork directly instead of starting the worker thread
        final Object worker = createWorker();
        invokeDoWork(worker);

        assertThat(streamingBlockNumber).hasValue(101);

        verify(bufferService).getLastBlockNumberProduced();
        verify(bufferService).getBlockState(101);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(connectionManager);
        verifyNoMoreInteractions(metrics);
        verifyNoInteractions(requestCall);
    }

    @Test
    void testConnectionWorker_switchBlock_noBlockAvailable() throws Exception {
        activateConnection();
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        doReturn(-1L).when(bufferService).getLastBlockNumberProduced();

        assertThat(streamingBlockNumber).hasValue(-1);

        final Object worker = createWorker();
        invokeDoWork(worker);

        assertThat(streamingBlockNumber).hasValue(-1);

        verify(bufferService).getLastBlockNumberProduced();
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(connectionManager);
        verifyNoMoreInteractions(metrics);
        verifyNoInteractions(requestCall);
    }

    @Test
    void testConnectionWorker_noItemsAvailable() throws Exception {
        activateConnection();
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        streamingBlockNumber.set(10);
        doReturn(new BlockState(10, 2_000L)).when(bufferService).getBlockState(10);

        // Call doWork directly - with no items in the block, nothing should be sent
        final Object worker = createWorker();
        invokeDoWork(worker);

        assertThat(streamingBlockNumber).hasValue(10);

        verify(bufferService).getBlockState(10);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(connectionManager);
        verifyNoMoreInteractions(metrics);
        verifyNoInteractions(requestCall);
    }

    @Test
    void testConnectionWorker_blockNodeTooFarBehind() throws Exception {
        activateConnection();
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        streamingBlockNumber.set(10);
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(15L);
        when(bufferService.getBlockState(anyLong())).thenReturn(null);
        when(bufferService.getHighestAckedBlockNumber()).thenReturn(-1L);

        // block 10 is too far behind, should send TOO_FAR_BEHIND
        final Object worker = createWorker();
        invokeDoWork(worker);

        assertThat(streamingBlockNumber).hasValue(10);
        assertThat(connection.closeReason()).isEqualTo(CloseReason.BLOCK_NODE_BEHIND);

        verify(metrics).recordRequestLatency(anyLong());
        verify(metrics).recordRequestEndStreamSent(EndStream.Code.TOO_FAR_BEHIND);
        verify(metrics).recordConnectionClosed(CloseReason.BLOCK_NODE_BEHIND);
        verify(bufferService, times(2)).getEarliestAvailableBlockNumber();
        verify(bufferService).getHighestAckedBlockNumber();
        verify(connectionManager).notifyConnectionClosed(connection);

        final ArgumentCaptor<PublishStreamRequestBytes> requestCaptor =
                ArgumentCaptor.forClass(PublishStreamRequestBytes.class);
        verify(requestCall).sendRequest(requestCaptor.capture(), anyBoolean());
        verify(requestCall).completeRequests();

        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(requestCall);
    }

    @Test
    void testConnectionWorker_blockJump() throws Exception {
        activateConnection();
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        streamingBlockNumber.set(10);

        final BlockState block10 = new BlockState(10, 2_000L);
        final BlockItem block10Header = newBlockHeaderItem(10);
        block10.addItem(block10Header);

        final BlockState block11 = new BlockState(11, 2_000L);
        final BlockItem block11Header = newBlockHeaderItem(11);
        block11.addItem(block11Header);

        doReturn(block10).when(bufferService).getBlockState(10);
        doReturn(block11).when(bufferService).getBlockState(11);
        lenient().doReturn(null).when(bufferService).getBlockState(12L); // May check for block 12 after advancing

        final ArgumentCaptor<PublishStreamRequestBytes> requestCaptor =
                ArgumentCaptor.forClass(PublishStreamRequestBytes.class);
        final Object worker = createWorker();

        // Call doWork - accumulates block 10 header (doesn't send yet)
        invokeDoWork(worker);

        // Verify nothing sent yet
        verify(requestCall, times(0)).sendRequest(any(), anyBoolean());

        // Now simulate a skip block response - forces jump to block 11
        final PublishStreamResponse skipResponse = createSkipBlock(10L);
        connection.onNext(skipResponse);

        // Call doWork again - should now process block 11 header
        block11.closeBlock(); // Close block 11 to force sending
        invokeDoWork(worker);

        // After sending block 11 completely, it will advance to block 12
        assertThat(streamingBlockNumber).hasValue(12);

        verify(requestCall, atLeastOnce()).sendRequest(requestCaptor.capture(), anyBoolean());
        final List<PublishStreamRequestBytes> allRequests = requestCaptor.getAllValues();
        // Should have sent block 11 header and EndOfBlock
        assertRequestContainsItems(allRequests.getFirst(), block11Header);

        verify(metrics, atLeast(1)).recordRequestSent(RequestOneOfType.BLOCK_ITEMS);
        verify(metrics, times(1)).recordRequestSent(RequestOneOfType.END_OF_BLOCK);
        verify(metrics, atLeast(1)).recordBlockItemsSent(1);
        verify(metrics, atLeast(2)).recordRequestLatency(anyLong());
        verify(metrics).recordLatestBlockSkipBlock(10);
        verify(metrics).recordResponseReceived(ResponseOneOfType.SKIP_BLOCK);
        verify(bufferService, atLeastOnce()).getBlockState(10);
        verify(bufferService, atLeastOnce()).getBlockState(11);
        verify(bufferService, atLeastOnce()).getBlockState(12);
        verify(bufferService, atLeastOnce()).getEarliestAvailableBlockNumber();
        verify(metrics, times(1)).recordRequestBlockItemCount(1);
        verify(metrics, times(1)).recordRequestBytes(anyLong());
        verify(metrics, atLeastOnce()).recordStreamingBlockNumber(anyLong());
        verify(metrics, atLeastOnce()).recordLatestBlockEndOfBlockSent(anyLong());
        verify(metrics, atLeastOnce()).recordHeaderSentToBlockEndSentLatency(anyLong());

        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
    }

    @Test
    void testConnectionWorker_nearSoftLimitSize() throws Exception {
        activateConnection();
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        streamingBlockNumber.set(10);

        final BlockNodeConfiguration config = connection.configuration();
        // sanity check to make sure the sizes we are about to use are within the scope of the soft and hard limits
        assertThat(config.messageSizeSoftLimitBytes()).isEqualTo(2_097_152L); // soft limit = 2 MB
        assertThat(config.messageSizeHardLimitBytes()).isEqualTo(37_748_736L); // hard limit = 36 MB

        final BlockState block = new BlockState(10, 2_000L);
        final BlockItem header = newBlockHeaderItem(10);
        final BlockItem item = newBlockTxItem(2097120);
        final BlockItem proof = newBlockProofItem(10, 2_000);

        doReturn(block).when(bufferService).getBlockState(10);
        lenient().when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        lenient().when(bufferService.getHighestAckedBlockNumber()).thenReturn(-1L);

        final ArgumentCaptor<PublishStreamRequestBytes> requestCaptor =
                ArgumentCaptor.forClass(PublishStreamRequestBytes.class);
        final Object worker = createWorker();

        block.addItem(header);
        invokeDoWork(worker); // header should get sent

        Thread.sleep(200);
        block.addItem(item);
        invokeDoWork(worker); // the block item should get sent

        Thread.sleep(200);
        block.addItem(proof);
        block.closeBlock();
        invokeDoWork(worker); // the proof should be sent

        verify(requestCall, times(4)).sendRequest(requestCaptor.capture(), anyBoolean());

        final List<PublishStreamRequestBytes> requests = requestCaptor.getAllValues();
        assertThat(requests).hasSize(4);

        final PublishStreamRequestBytes req1 = requests.get(0);
        assertThat(req1.blockItemsOrElse(BlockItemSetBytes.DEFAULT).blockItems())
                .hasSize(1)
                .containsExactly(toBytes(header));
        final PublishStreamRequestBytes req2 = requests.get(1);
        assertThat(req2.blockItemsOrElse(BlockItemSetBytes.DEFAULT).blockItems())
                .hasSize(1)
                .containsExactly(toBytes(item));
        final PublishStreamRequestBytes req3 = requests.get(2);
        assertThat(req3.blockItemsOrElse(BlockItemSetBytes.DEFAULT).blockItems())
                .hasSize(1)
                .containsExactly(toBytes(proof));
        final PublishStreamRequestBytes req4 = requests.get(3);
        assertThat(req4.endOfBlockOrElse(BlockEnd.DEFAULT).blockNumber()).isEqualTo(10);
    }

    @Test
    void testConnectionWorker_twoConsecutiveItemsBetweenSoftAndHardLimit_mustNotFatallyClose() throws Exception {
        activateConnection();
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        streamingBlockNumber.set(10);

        final BlockNodeConfiguration config = connection.configuration();
        // sanity check to make sure the sizes we are about to use are within the scope of the soft and hard limits
        assertThat(config.messageSizeSoftLimitBytes()).isEqualTo(2_097_152L); // soft limit = 2 MB
        assertThat(config.messageSizeHardLimitBytes()).isEqualTo(37_748_736L); // hard limit = 36 MB

        final BlockState block = new BlockState(10, 2_000L);
        // Each item is 20 MB: above the 2 MB soft limit but well below the 36 MB hard limit.
        // Individually valid, but 20 MB + 20 MB = 40 MB exceeds the hard limit.
        final BlockItem item1 = newBlockTxItem(20_000_000);
        final BlockItem item2 = newBlockTxItem(20_000_000);

        block.addItem(item1);
        block.addItem(item2);
        block.closeBlock();

        doReturn(block).when(bufferService).getBlockState(10);
        lenient().when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        lenient().when(bufferService.getHighestAckedBlockNumber()).thenReturn(-1L);

        final ArgumentCaptor<PublishStreamRequestBytes> requestCaptor =
                ArgumentCaptor.forClass(PublishStreamRequestBytes.class);
        final Object worker = createWorker();

        invokeDoWork(worker);

        // The connection must NOT have been fatally closed: both items are valid and can each be sent in their own
        // request. This is the primary signal of the bug.
        verify(metrics, never()).recordRequestExceedsHardLimit();
        verify(metrics, never()).recordRequestEndStreamSent(EndStream.Code.ERROR);
        assertThat(connection.currentState()).isNotEqualTo(ConnectionState.CLOSED);
        assertThat(connection.closeReason()).isNotEqualTo(CloseReason.INTERNAL_ERROR);

        // Both items must actually have been sent, each in its own request.
        verify(requestCall, atLeast(2)).sendRequest(requestCaptor.capture(), anyBoolean());
        final List<Bytes> allSentItems = requestCaptor.getAllValues().stream()
                .flatMap(req -> req.blockItemsOrElse(BlockItemSetBytes.DEFAULT).blockItems().stream())
                .toList();
        assertThat(allSentItems).contains(toBytes(item1), toBytes(item2));
    }

    @Test
    void testConnectionWorker_hugeItems() throws Exception {
        activateConnection();
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        streamingBlockNumber.set(10);

        final BlockNodeConfiguration config = connection.configuration();
        // sanity check to make sure the sizes we are about to use are within the scope of the soft and hard limits
        assertThat(config.messageSizeSoftLimitBytes()).isEqualTo(2_097_152L); // soft limit = 2 MB
        assertThat(config.messageSizeHardLimitBytes()).isEqualTo(37_748_736L); // hard limit = 36 MB

        final BlockState block = new BlockState(10, 2_000L);
        final BlockItem item1 = newBlockHeaderItem(10);
        final BlockItem item2 = newBlockTxItem(5_000);
        final BlockItem item3 = newBlockTxItem(5_000);
        final BlockItem item4 = newBlockTxItem(3_001_500);
        final BlockItem item5 = newBlockTxItem(255);
        final BlockItem item6 = newBlockTxItem(1_950_000);
        final BlockItem item7 = newBlockTxItem(1_750_000);
        final BlockItem item8 = newBlockTxItem(25);
        final BlockItem item9 = newBlockTxItem(37_748_731);

        block.addItem(item1);
        block.addItem(item2);
        block.addItem(item3);
        block.addItem(item4);
        block.addItem(item5);
        block.addItem(item6);
        block.addItem(item7);
        block.addItem(item8);
        block.addItem(item9);
        doReturn(block).when(bufferService).getBlockState(10);
        lenient().when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        lenient().when(bufferService.getHighestAckedBlockNumber()).thenReturn(-1L);

        final ArgumentCaptor<PublishStreamRequestBytes> requestCaptor =
                ArgumentCaptor.forClass(PublishStreamRequestBytes.class);
        final Object worker = createWorker();

        invokeDoWork(worker);

        verify(requestCall, times(5)).sendRequest(requestCaptor.capture(), anyBoolean());

        /*
        There should be 5 requests:
        Request 1: item 1, 2, and 3
        Request 2: item 4
        Request 3: item 5 and 6
        Request 4: item 7 and 8
        Request 5: EndStream.Error because item 9 was too big
         */

        assertThat(requestCaptor.getAllValues()).hasSize(5);
        final List<PublishStreamRequestBytes> requests = requestCaptor.getAllValues();

        final PublishStreamRequestBytes req1 = requests.get(0);
        assertThat(req1.blockItemsOrElse(BlockItemSetBytes.DEFAULT).blockItems())
                .hasSize(3)
                .containsExactly(toBytes(item1), toBytes(item2), toBytes(item3));
        final PublishStreamRequestBytes req2 = requests.get(1);
        assertThat(req2.blockItemsOrElse(BlockItemSetBytes.DEFAULT).blockItems())
                .hasSize(1)
                .containsExactly(toBytes(item4));
        final PublishStreamRequestBytes req3 = requests.get(2);
        assertThat(req3.blockItemsOrElse(BlockItemSetBytes.DEFAULT).blockItems())
                .hasSize(2)
                .containsExactly(toBytes(item5), toBytes(item6));
        final PublishStreamRequestBytes req4 = requests.get(3);
        assertThat(req4.blockItemsOrElse(BlockItemSetBytes.DEFAULT).blockItems())
                .hasSize(2)
                .containsExactly(toBytes(item7), toBytes(item8));
        final PublishStreamRequestBytes req5 = requests.get(4);
        assertThat(req5.hasEndStream()).isTrue();
        final EndStreamBytes endStream = req5.endStream();
        assertThat(endStream).isNotNull();
        assertThat(endStream.endCode()).isEqualTo(EndStream.Code.ERROR);

        verify(metrics, times(4)).recordRequestSent(RequestOneOfType.BLOCK_ITEMS);
        verify(metrics).recordRequestEndStreamSent(EndStream.Code.ERROR);

        final ArgumentCaptor<Integer> metricItemsSentCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(metrics, times(4)).recordBlockItemsSent(metricItemsSentCaptor.capture());
        int totalItems = 0;
        for (final int count : metricItemsSentCaptor.getAllValues()) {
            totalItems += count;
        }
        assertThat(totalItems).isEqualTo(8);

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
        assertThat(connection.closeReason()).isEqualTo(CloseReason.INTERNAL_ERROR);

        verify(metrics).recordRequestExceedsHardLimit();
        verify(metrics).recordConnectionClosed(CloseReason.INTERNAL_ERROR);
        verify(metrics, times(5)).recordRequestLatency(anyLong());
        verify(requestCall).completeRequests();
        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getHighestAckedBlockNumber();
        verify(connectionManager).notifyConnectionClosed(connection);
        verify(metrics, atLeastOnce()).recordStreamingBlockNumber(anyLong());
        verify(metrics, atLeastOnce()).recordRequestBlockItemCount(anyInt());
        verify(metrics, atLeastOnce()).recordRequestBytes(anyLong());

        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(bufferService);
    }

    // Tests that no response processing occurs when connection is already closed
    @Test
    void testOnNext_connectionClosed() {
        final PublishStreamResponse response = createBlockAckResponse(10L);
        connection.updateConnectionState(ConnectionState.CLOSED);

        connection.onNext(response);

        // Should not process any response when connection is closed
        verifyNoInteractions(metrics);
        verifyNoInteractions(requestCall);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    // Tests EndOfStream rate limiting
    @Test
    void testOnNext_endOfStream_rateLimitExceeded() {
        activateConnection();
        final PublishStreamResponse response = createEndOfStreamResponse(Code.ERROR, 10L);
        when(stats.addEndOfStreamAndCheckLimit(any(Instant.class), anyInt(), any(Duration.class)))
                .thenReturn(true);

        connection.onNext(response);

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
        assertThat(connection.closeReason()).isEqualTo(CloseReason.TOO_MANY_END_STREAM_RESPONSES);

        verify(metrics).recordLatestBlockEndOfStream(10L);
        verify(metrics).recordResponseEndOfStreamReceived(Code.ERROR);
        verify(metrics).recordEndOfStreamLimitExceeded();
        verify(metrics).recordConnectionClosed(CloseReason.TOO_MANY_END_STREAM_RESPONSES);
        verify(stats).addEndOfStreamAndCheckLimit(any(Instant.class), anyInt(), any(Duration.class));
        verify(requestCall).completeRequests();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
    }

    // Tests EndOfStream client failure codes with Long.MAX_VALUE edge case (should restart at block 0)
    @ParameterizedTest
    @EnumSource(
            value = EndOfStream.Code.class,
            names = {"TIMEOUT", "DUPLICATE_BLOCK", "BAD_BLOCK_PROOF", "INVALID_REQUEST"})
    void testOnNext_endOfStream_clientFailures_maxValueBlockNumber(final EndOfStream.Code responseCode) {
        activateConnection();

        final PublishStreamResponse response = createEndOfStreamResponse(responseCode, Long.MAX_VALUE);
        connection.onNext(response);

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
        assertThat(connection.closeReason()).isEqualTo(CloseReason.TRANSIENT_END_STREAM_RECEIVED);

        verify(metrics).recordLatestBlockEndOfStream(Long.MAX_VALUE);
        verify(metrics).recordResponseEndOfStreamReceived(responseCode);
        verify(metrics).recordConnectionClosed(CloseReason.TRANSIENT_END_STREAM_RECEIVED);
        verify(requestCall).completeRequests();

        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
    }

    @Test
    void testOnNext_blockNodeBehind_inRangeMissingBlock() {
        activateConnection();
        final PublishStreamResponse response = createBlockNodeBehindResponse(10);
        when(bufferService.getBlockState(11)).thenReturn(null);
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(stats.shouldIgnoreBehindPublisher(any(Instant.class), any(Duration.class), any(Duration.class)))
                .thenReturn(false);
        when(stats.addBehindPublisherAndCheckLimit(any(Instant.class), anyInt(), any(Duration.class)))
                .thenReturn(false);

        connection.onNext(response);

        // Block N+1 (11) is missing but not below the earliest available block (10), so this is an
        // internal inconsistency: the CN sends EndStream.ERROR and closes with INTERNAL_ERROR.
        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
        assertThat(connection.closeReason()).isEqualTo(CloseReason.INTERNAL_ERROR);
        verify(metrics).recordRequestEndStreamSent(EndStream.Code.ERROR);
        verify(requestCall).completeRequests();
    }

    // Tests BehindPublisher code with Long.MAX_VALUE edge case (should restart at block 0)
    @Test
    void testOnNext_blockNodeBehind_maxValueBlockNumber() {
        activateConnection();
        final PublishStreamResponse response = createBlockNodeBehindResponse(Long.MAX_VALUE);
        when(bufferService.getBlockState(0L)).thenReturn(new BlockState(0L, 2_000L));
        when(stats.shouldIgnoreBehindPublisher(any(Instant.class), any(Duration.class), any(Duration.class)))
                .thenReturn(false);
        when(stats.addBehindPublisherAndCheckLimit(any(Instant.class), anyInt(), any(Duration.class)))
                .thenReturn(false);

        connection.onNext(response);

        verify(metrics).recordLatestBlockBehindPublisher(Long.MAX_VALUE);
        verify(metrics).recordResponseReceived(ResponseOneOfType.NODE_BEHIND_PUBLISHER);
        verify(stats).shouldIgnoreBehindPublisher(any(Instant.class), any(Duration.class), any(Duration.class));
        verify(stats).addBehindPublisherAndCheckLimit(any(Instant.class), anyInt(), any(Duration.class));
        verify(bufferService).getBlockState(0L);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(bufferService);
    }

    // Tests BehindPublisher rate limiting - sends EndStream with RESET code and reschedules when limit exceeded
    @Test
    void testOnNext_blockNodeBehind_rateLimitExceeded() {
        activateConnection();
        final PublishStreamResponse response = createBlockNodeBehindResponse(10L);

        when(stats.shouldIgnoreBehindPublisher(any(Instant.class), any(Duration.class), any(Duration.class)))
                .thenReturn(false);
        when(stats.addBehindPublisherAndCheckLimit(any(Instant.class), anyInt(), any(Duration.class)))
                .thenReturn(true);
        // Mock bufferService for EndStream request
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(5L);
        when(bufferService.getHighestAckedBlockNumber()).thenReturn(15L);

        connection.onNext(response);

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
        assertThat(connection.closeReason()).isEqualTo(CloseReason.BLOCK_NODE_BEHIND);

        verify(metrics).recordLatestBlockBehindPublisher(10L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.NODE_BEHIND_PUBLISHER);
        verify(metrics).recordConnectionClosed(CloseReason.BLOCK_NODE_BEHIND);
        // Verify EndStream request metrics
        verify(metrics).recordRequestEndStreamSent(EndStream.Code.RESET);
        verify(metrics).recordRequestLatency(anyLong());
        verify(stats).shouldIgnoreBehindPublisher(any(Instant.class), any(Duration.class), any(Duration.class));
        verify(stats).addBehindPublisherAndCheckLimit(any(Instant.class), anyInt(), any(Duration.class));
        // Verify EndStream request was sent with RESET code
        verify(bufferService, atLeastOnce()).getEarliestAvailableBlockNumber();
        verify(bufferService, atLeastOnce()).getHighestAckedBlockNumber();
        verify(requestCall).sendRequest(any(PublishStreamRequestBytes.class), anyBoolean());
        verify(requestCall).completeRequests();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
    }

    // Tests BehindPublisher ignore period - first message in new window should be processed
    @Test
    void testOnNext_blockNodeBehind_ignorePeriod_firstMessageInWindow() {
        activateConnection();
        final PublishStreamResponse response = createBlockNodeBehindResponse(10L);
        when(bufferService.getBlockState(11L)).thenReturn(new BlockState(11L, 2_000L));

        // First message should NOT be ignored (new window, queue is empty)
        when(stats.shouldIgnoreBehindPublisher(any(Instant.class), any(Duration.class), any(Duration.class)))
                .thenReturn(false);
        when(stats.addBehindPublisherAndCheckLimit(any(Instant.class), anyInt(), any(Duration.class)))
                .thenReturn(false);

        connection.onNext(response);

        assertThat(connection.currentState()).isEqualTo(ConnectionState.ACTIVE);

        verify(metrics).recordLatestBlockBehindPublisher(10L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.NODE_BEHIND_PUBLISHER);
        verify(bufferService).getBlockState(11L);
        verify(stats).shouldIgnoreBehindPublisher(any(Instant.class), any(Duration.class), any(Duration.class));
        verify(stats).addBehindPublisherAndCheckLimit(any(Instant.class), anyInt(), any(Duration.class));
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
    }

    // Tests BehindPublisher ignore period - second message within ignore period should be ignored
    @Test
    void testOnNext_blockNodeBehind_ignorePeriod_withinIgnorePeriod() {
        activateConnection();
        final PublishStreamResponse response = createBlockNodeBehindResponse(10L);

        // Second message within ignore period should be ignored
        when(stats.shouldIgnoreBehindPublisher(any(Instant.class), any(Duration.class), any(Duration.class)))
                .thenReturn(true);

        connection.onNext(response);

        assertThat(connection.currentState()).isEqualTo(ConnectionState.ACTIVE);

        // Metrics are recorded before the ignore check in onNext, but handleBlockNodeBehind returns early
        verify(metrics).recordResponseReceived(ResponseOneOfType.NODE_BEHIND_PUBLISHER);
        verify(metrics).recordLatestBlockBehindPublisher(10L);
        verify(stats).shouldIgnoreBehindPublisher(any(Instant.class), any(Duration.class), any(Duration.class));
        // Should NOT record or process the message in handleBlockNodeBehind
        verify(stats, never()).addBehindPublisherAndCheckLimit(any(Instant.class), anyInt(), any(Duration.class));
        verify(bufferService, never()).getBlockState(anyLong());
        verifyNoMoreInteractions(requestCall);
    }

    @Test
    void testUpdateAcknowledgementMetrics_ackWatermarkCeiling() throws Throwable {
        invokeUpdateAcknowledgementMetrics(10, Long.MAX_VALUE);

        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testUpdateAcknowledgementMetrics_noChange() throws Throwable {
        invokeUpdateAcknowledgementMetrics(10, 10);

        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testUpdateAcknowledgementMetrics() throws Throwable {
        final long nowNanos = System.nanoTime();
        // don't create block 1
        final BlockState block2 = mock(BlockState.class);
        when(block2.openedNanos()).thenReturn(nowNanos - 8100);
        when(block2.headerSentNanos()).thenReturn(nowNanos - 8000);
        when(block2.blockEndSentNanos()).thenReturn(nowNanos - 7000);
        when(block2.closedNanos()).thenReturn(nowNanos);
        final BlockState block3 = mock(BlockState.class);
        when(block3.openedNanos()).thenReturn(nowNanos - 5100);
        when(block3.headerSentNanos()).thenReturn(nowNanos - 5000);
        when(block3.blockEndSentNanos()).thenReturn(nowNanos - 4000);
        when(block3.closedNanos()).thenReturn(nowNanos);
        final BlockState block4 = mock(BlockState.class);
        when(block4.openedNanos()).thenReturn(nowNanos - 2100);
        when(block4.headerSentNanos()).thenReturn(nowNanos - 2000);
        when(block4.blockEndSentNanos()).thenReturn(nowNanos - 1000);
        when(block4.closedNanos()).thenReturn(nowNanos);

        when(bufferService.getLastBlockNumberProduced()).thenReturn(5L);
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(0L);
        doAnswer(inv -> {
                    final long blockNumber = inv.getArgument(0);
                    if (blockNumber == 2) {
                        return block2;
                    } else if (blockNumber == 3) {
                        return block3;
                    } else if (blockNumber == 4) {
                        return block4;
                    }
                    return null;
                })
                .when(bufferService)
                .getBlockState(anyLong());

        invokeUpdateAcknowledgementMetrics(0, 4);

        verify(bufferService).getLastBlockNumberProduced();
        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getBlockState(1L);
        verify(bufferService).getBlockState(2L);
        verify(bufferService).getBlockState(3L);
        verify(bufferService).getBlockState(4L);

        final ArgumentCaptor<Long> headerCreatedToAckLatencyVals = ArgumentCaptor.forClass(long.class);
        verify(metrics, times(3)).recordHeaderProducedToAckLatency(headerCreatedToAckLatencyVals.capture());
        assertThat(headerCreatedToAckLatencyVals.getAllValues()).hasSize(3);
        for (final long headerCreatedToAckLatency : headerCreatedToAckLatencyVals.getAllValues()) {
            assertThat(headerCreatedToAckLatency).isGreaterThan(0L);
        }

        final ArgumentCaptor<Long> closeToAckLatencyVals = ArgumentCaptor.forClass(long.class);
        verify(metrics, times(3)).recordBlockClosedToAckLatency(closeToAckLatencyVals.capture());
        assertThat(closeToAckLatencyVals.getAllValues()).hasSize(3);
        for (final long closeToAckLatency : closeToAckLatencyVals.getAllValues()) {
            assertThat(closeToAckLatency).isGreaterThan(0L);
        }

        final ArgumentCaptor<Long> headerSentToAckLatencyVals = ArgumentCaptor.forClass(long.class);
        verify(metrics, times(3)).recordHeaderSentAckLatency(headerSentToAckLatencyVals.capture());
        assertThat(headerSentToAckLatencyVals.getAllValues()).hasSize(3);
        for (final long headerSentToAckLatency : headerSentToAckLatencyVals.getAllValues()) {
            assertThat(headerSentToAckLatency).isGreaterThan(0L);
        }

        final ArgumentCaptor<Long> blockEndSentToAckLatencyVals = ArgumentCaptor.forClass(long.class);
        verify(metrics, times(3)).recordBlockEndSentToAckLatency(blockEndSentToAckLatencyVals.capture());
        assertThat(blockEndSentToAckLatencyVals.getAllValues()).hasSize(3);
        for (final long blockEndSentToAckLatency : blockEndSentToAckLatencyVals.getAllValues()) {
            assertThat(blockEndSentToAckLatency).isGreaterThan(0L);
        }

        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(bufferService);
    }

    // Utilities

    private void activateConnection() {
        connection.initialize();
        connection.updateConnectionState(ConnectionState.ACTIVE);
        resetMocks();
    }

    private void resetMocks() {
        reset(connectionManager, requestCall, bufferService, metrics);
    }

    private AtomicLong streamingBlockNumber() {
        return (AtomicLong) streamingBlockNumberHandle.get(connection);
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<Thread> workerThreadRef() {
        return (AtomicReference<Thread>) workerThreadRefHandle.get(connection);
    }

    private void assertRequestContainsItems(final PublishStreamRequestBytes request, final BlockItem... expectedItems) {
        assertRequestContainsItems(List.of(request), expectedItems);
    }

    private void assertRequestContainsItems(
            final List<PublishStreamRequestBytes> requests, final BlockItem... expectedItems) {
        final List<Bytes> actualItems = new ArrayList<>();
        for (final PublishStreamRequestBytes request : requests) {
            final BlockItemSetBytes bis = request.blockItems();
            if (bis != null) {
                actualItems.addAll(bis.blockItems());
            }
        }

        assertThat(actualItems).hasSize(expectedItems.length);

        for (int i = 0; i < actualItems.size(); ++i) {
            final Bytes actualItem = actualItems.get(i);
            final Bytes expectedItem = BlockItem.PROTOBUF.toBytes(expectedItems[i]);
            assertThat(actualItem)
                    .withFailMessage("Block item at index " + i + " different. Expected: " + expectedItem
                            + " but found " + actualItem)
                    .isEqualTo(expectedItem);
        }
    }

    private Bytes toBytes(final BlockItem item) {
        return BlockItem.PROTOBUF.toBytes(item);
    }

    /**
     * Helper method to create a ConnectionWorkerLoopTask instance using reflection.
     * The worker task is an inner class of BlockNodeConnection.
     *
     * @return a new ConnectionWorkerLoopTask instance
     * @throws Exception if reflection fails
     */
    private Object createWorker() throws Exception {
        Class<?> workerClass = null;
        for (final Class<?> innerClass : BlockNodeStreamingConnection.class.getDeclaredClasses()) {
            if (innerClass.getSimpleName().equals("ConnectionWorkerLoopTask")) {
                workerClass = innerClass;
                break;
            }
        }
        if (workerClass == null) {
            throw new IllegalStateException("ConnectionWorkerLoopTask inner class not found");
        }

        final Constructor<?> constructor = workerClass.getDeclaredConstructor(BlockNodeStreamingConnection.class);
        constructor.setAccessible(true);
        return constructor.newInstance(connection);
    }

    /**
     * Helper method to invoke the doWork() method on a ConnectionWorkerLoopTask instance
     * using reflection.
     *
     * @param worker the worker instance (from createWorker())
     * @throws Exception if reflection or doWork() execution fails
     */
    private void invokeDoWork(final Object worker) throws Exception {
        final Method doWorkMethod = worker.getClass().getDeclaredMethod("doWork");
        doWorkMethod.setAccessible(true);
        doWorkMethod.invoke(worker);
    }

    private void invokeUpdateAcknowledgementMetrics(
            final long previousAcknowledgedBlockNumber, final long latestAcknowledgedBlockNumber) throws Throwable {
        updateAcknowledgementMetricsHandle.invoke(
                connection, previousAcknowledgedBlockNumber, latestAcknowledgedBlockNumber);
    }
}
