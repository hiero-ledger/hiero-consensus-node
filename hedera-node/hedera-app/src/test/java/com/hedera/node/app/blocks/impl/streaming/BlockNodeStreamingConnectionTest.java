// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchRuntimeException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeStreamingConnection.BlockItemsStreamRequest;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeStreamingConnection.StreamRequest;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.grpc.GrpcException;
import com.hedera.pbj.runtime.grpc.GrpcStatus;
import com.hedera.pbj.runtime.grpc.Pipeline;
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
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.block.api.BlockItemSet;
import org.hiero.block.api.BlockStreamPublishServiceInterface.BlockStreamPublishServiceClient;
import org.hiero.block.api.PublishStreamRequest;
import org.hiero.block.api.PublishStreamRequest.EndStream;
import org.hiero.block.api.PublishStreamRequest.RequestOneOfType;
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
    private static final long ONCE_PER_DAY_MILLIS = Duration.ofHours(24).toMillis();
    private static final VarHandle connectionStateHandle;
    private static final Thread FAKE_WORKER_THREAD = new Thread(() -> {}, "fake-worker");
    private static final VarHandle streamingBlockNumberHandle;
    private static final VarHandle workerThreadRefHandle;
    private static final MethodHandle sendRequestHandle;

    static {
        try {
            final Lookup lookup = MethodHandles.lookup();
            connectionStateHandle = MethodHandles.privateLookupIn(AbstractBlockNodeConnection.class, lookup)
                    .findVarHandle(AbstractBlockNodeConnection.class, "stateRef", AtomicReference.class);
            streamingBlockNumberHandle = MethodHandles.privateLookupIn(BlockNodeStreamingConnection.class, lookup)
                    .findVarHandle(BlockNodeStreamingConnection.class, "streamingBlockNumber", AtomicLong.class);
            workerThreadRefHandle = MethodHandles.privateLookupIn(BlockNodeStreamingConnection.class, lookup)
                    .findVarHandle(BlockNodeStreamingConnection.class, "workerThreadRef", AtomicReference.class);

            final Method sendRequest =
                    BlockNodeStreamingConnection.class.getDeclaredMethod("sendRequest", StreamRequest.class);
            sendRequest.setAccessible(true);
            sendRequestHandle = lookup.unreflect(sendRequest);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BlockNodeStreamingConnection connection;
    private ConfigProvider configProvider;
    private BlockNodeConfiguration nodeConfig;
    private BlockNodeConnectionManager connectionManager;
    private BlockBufferService bufferService;
    private BlockStreamPublishServiceClient grpcServiceClient;
    private BlockStreamMetrics metrics;
    private Pipeline<? super PublishStreamRequest> requestPipeline;
    private ScheduledExecutorService executorService;
    private ExecutorService pipelineExecutor;
    private BlockNodeStats.HighLatencyResult latencyResult;
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
        latencyResult = mock(BlockNodeStats.HighLatencyResult.class);

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
                .createStreamingClient(any(BlockNodeConfiguration.class), any(Duration.class));
        connection = new BlockNodeStreamingConnection(
                configProvider,
                nodeConfig,
                connectionManager,
                bufferService,
                metrics,
                executorService,
                pipelineExecutor,
                null,
                clientFactory);

        // To avoid potential non-deterministic effects due to the worker thread, assign a fake worker thread to the
        // connection that does nothing.
        final AtomicReference<Thread> workerThreadRef = workerThreadRef();
        workerThreadRef.set(FAKE_WORKER_THREAD);

        lenient().doReturn(requestPipeline).when(grpcServiceClient).publishBlockStream(connection);
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
    void testInitialize() {
        assertThat(connection.currentState()).isEqualTo(ConnectionState.UNINITIALIZED);

        connection.initialize();

        assertThat(connection.currentState()).isEqualTo(ConnectionState.READY);
        verify(grpcServiceClient).publishBlockStream(connection);
        verify(clientFactory).createStreamingClient(any(BlockNodeConfiguration.class), any(Duration.class));
    }

    @Test
    void testInitialize_alreadyExists() {
        connection.initialize();
        connection.initialize();

        verify(grpcServiceClient).publishBlockStream(connection); // should only be called once
        verifyNoMoreInteractions(grpcServiceClient);
    }

    @Test
    void testConstructorWithInitialBlock() {
        // Create connection with initial block number
        connection = new BlockNodeStreamingConnection(
                configProvider,
                nodeConfig,
                connectionManager,
                bufferService,
                metrics,
                executorService,
                pipelineExecutor,
                100L,
                clientFactory);

        // Verify the streamingBlockNumber was set
        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        assertThat(streamingBlockNumber).hasValue(100L);
    }

    /**
     * Tests TimeoutException handling during pipeline creation.
     * Uses mocks to simulate a timeout without actually waiting, making the test fast.
     */
    @Test
    void testInitialize_timeoutException() throws Exception {
        // Create a mock Future that will throw TimeoutException when get() is called
        @SuppressWarnings("unchecked")
        final Future<Object> mockFuture = mock(Future.class);
        when(mockFuture.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException("Simulated timeout"));

        // Set up the pipelineExecutor to return mock future
        doReturn(mockFuture).when(pipelineExecutor).submit(any(Runnable.class));

        // Attempt to create pipeline - should timeout and throw
        final RuntimeException exception = catchRuntimeException(() -> connection.initialize());

        assertThat(exception).isNotNull();
        assertThat(exception.getMessage()).contains("Pipeline creation timed out");
        assertThat(exception.getCause()).isInstanceOf(TimeoutException.class);

        // Verify timeout was detected and recorded
        verify(mockFuture).get(anyLong(), any(TimeUnit.class));
        verify(mockFuture).cancel(true);
        verify(metrics).recordPipelineOperationTimeout();

        // Connection should still be UNINITIALIZED since pipeline creation failed
        assertThat(connection.currentState()).isEqualTo(ConnectionState.UNINITIALIZED);
    }

    /**
     * Tests InterruptedException handling during pipeline creation.
     * Uses mocks to simulate an interruption without actually waiting, making the test fast.
     */
    @Test
    void testInitialize_interruptedException() throws Exception {
        // Create a mock Future that will throw InterruptedException when get() is called
        @SuppressWarnings("unchecked")
        final Future<Object> mockFuture = mock(Future.class);
        when(mockFuture.get(anyLong(), any(TimeUnit.class)))
                .thenThrow(new InterruptedException("Simulated interruption"));

        // Set up the pipelineExecutor to return mock future
        doReturn(mockFuture).when(pipelineExecutor).submit(any(Runnable.class));

        // Attempt to create pipeline - should handle interruption and throw
        final RuntimeException exception = catchRuntimeException(() -> connection.initialize());

        assertThat(exception).isNotNull();
        assertThat(exception.getMessage()).contains("Interrupted while creating pipeline");
        assertThat(exception.getCause()).isInstanceOf(InterruptedException.class);

        // Verify interruption was handled
        verify(mockFuture).get(anyLong(), any(TimeUnit.class));

        // Connection should still be UNINITIALIZED since pipeline creation failed
        assertThat(connection.currentState()).isEqualTo(ConnectionState.UNINITIALIZED);
    }

    /**
     * Tests ExecutionException handling during pipeline creation.
     * Uses mocks to simulate an execution error without actually waiting, making the test fast.
     */
    @Test
    void testInitialize_executionException() throws Exception {
        // Create a mock Future that will throw ExecutionException when get() is called
        @SuppressWarnings("unchecked")
        final Future<Object> mockFuture = mock(Future.class);
        when(mockFuture.get(anyLong(), any(TimeUnit.class)))
                .thenThrow(new java.util.concurrent.ExecutionException(
                        "Simulated execution error", new RuntimeException("Underlying cause")));

        // Set up the pipelineExecutor to return mock future
        doReturn(mockFuture).when(pipelineExecutor).submit(any(Runnable.class));

        // Attempt to create pipeline - should handle execution exception and throw
        final RuntimeException exception = catchRuntimeException(() -> connection.initialize());

        assertThat(exception).isNotNull();
        assertThat(exception.getMessage()).contains("Error creating pipeline");
        assertThat(exception.getCause()).isInstanceOf(RuntimeException.class);
        assertThat(exception.getCause().getMessage()).isEqualTo("Underlying cause");

        // Verify execution exception was handled
        verify(mockFuture).get(anyLong(), any(TimeUnit.class));

        // Connection should still be UNINITIALIZED since pipeline creation failed
        assertThat(connection.currentState()).isEqualTo(ConnectionState.UNINITIALIZED);
    }

    @Test
    void testUpdatingConnectionState() {
        final ConnectionState preUpdateState = connection.currentState();
        // this should be uninitialized because we haven't called connect yet
        assertThat(preUpdateState).isEqualTo(ConnectionState.UNINITIALIZED);
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Verify task was scheduled to periodically reset the stream
        verify(executorService)
                .scheduleAtFixedRate(
                        any(Runnable.class),
                        eq(ONCE_PER_DAY_MILLIS), // initial delay
                        eq(ONCE_PER_DAY_MILLIS), // period
                        eq(TimeUnit.MILLISECONDS));

        final ConnectionState postUpdateState = connection.currentState();
        assertThat(postUpdateState).isEqualTo(ConnectionState.ACTIVE);
    }

    @Test
    void testUpdatingConnectionState_downgrade() {
        final ConnectionState preUpdateState = connection.currentState();
        // this should be uninitialized because we haven't called connect yet
        assertThat(preUpdateState).isEqualTo(ConnectionState.UNINITIALIZED);
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // the connection is ACTIVE so try to "downgrade" the state back to READY... should fail
        assertThatThrownBy(() -> connection.updateConnectionState(ConnectionState.READY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Attempted to downgrade state from ACTIVE to READY");

        assertThat(connection.currentState()).isEqualTo(ConnectionState.ACTIVE);
    }

    @Test
    void testHandleStreamError() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Verify task was scheduled to periodically reset the stream
        verify(executorService)
                .scheduleAtFixedRate(
                        any(Runnable.class),
                        eq(ONCE_PER_DAY_MILLIS), // initial delay
                        eq(ONCE_PER_DAY_MILLIS), // period
                        eq(TimeUnit.MILLISECONDS));

        // do a quick sanity check on the state
        final ConnectionState preState = connection.currentState();
        assertThat(preState).isEqualTo(ConnectionState.ACTIVE);

        connection.handleStreamFailure();

        final ConnectionState postState = connection.currentState();
        assertThat(postState).isEqualTo(ConnectionState.CLOSED);

        verify(requestPipeline).onComplete();
        verify(connectionManager).rescheduleConnection(connection, Duration.ofSeconds(30), null, true);
        verifyNoMoreInteractions(requestPipeline);
    }

    @Test
    void testOnNext_acknowledgement_notStreaming() {
        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(-1); // pretend we are currently not streaming any blocks
        final PublishStreamResponse response = createBlockAckResponse(10L);
        when(connectionManager.recordBlockAckAndCheckLatency(eq(connection.configuration()), eq(10L), any()))
                .thenReturn(latencyResult);
        when(latencyResult.shouldSwitch()).thenReturn(false);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        connection.onNext(response);

        assertThat(streamingBlockNumber).hasValue(11); // moved to acked block + 1

        verify(connectionManager)
                .recordBlockAckAndCheckLatency(eq(connection.configuration()), eq(10L), any(Instant.class));
        verify(bufferService, atLeastOnce()).getLastBlockNumberProduced();
        verify(bufferService).setLatestAcknowledgedBlock(10);
        verify(bufferService).getHighestAckedBlockNumber();
        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(metrics).recordResponseReceived(ResponseOneOfType.ACKNOWLEDGEMENT);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(connectionManager);
    }

    @Test
    void testOnNext_acknowledgement_olderThanCurrentStreamingAndProducing() {
        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(10); // pretend we are streaming block 10
        final PublishStreamResponse response = createBlockAckResponse(8L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(10L);
        when(connectionManager.recordBlockAckAndCheckLatency(eq(connection.configuration()), eq(8L), any()))
                .thenReturn(latencyResult);
        when(latencyResult.shouldSwitch()).thenReturn(false);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        connection.onNext(response);

        assertThat(streamingBlockNumber).hasValue(10L); // should not change

        verify(bufferService, atLeastOnce()).getLastBlockNumberProduced();
        verify(bufferService).setLatestAcknowledgedBlock(8);
        verify(bufferService).getHighestAckedBlockNumber();
        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(metrics).recordResponseReceived(ResponseOneOfType.ACKNOWLEDGEMENT);
        verify(bufferService, times(8)).getBlockState(anyLong());
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testOnNext_acknowledgement_newerThanCurrentProducing() {
        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(10); // pretend we are streaming block 10
        final PublishStreamResponse response = createBlockAckResponse(11L);

        when(bufferService.getLastBlockNumberProduced()).thenReturn(10L);
        when(connectionManager.recordBlockAckAndCheckLatency(eq(connection.configuration()), eq(11L), any()))
                .thenReturn(latencyResult);
        when(latencyResult.shouldSwitch()).thenReturn(false);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        connection.onNext(response);

        assertThat(streamingBlockNumber).hasValue(12); // should be 1 + acked block number

        verify(bufferService, atLeastOnce()).getLastBlockNumberProduced();
        verify(bufferService).setLatestAcknowledgedBlock(11L);
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
        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(8); // pretend we are streaming block 8
        final PublishStreamResponse response = createBlockAckResponse(11L);

        when(bufferService.getLastBlockNumberProduced()).thenReturn(12L);
        when(connectionManager.recordBlockAckAndCheckLatency(eq(connection.configuration()), eq(11L), any()))
                .thenReturn(latencyResult);
        when(latencyResult.shouldSwitch()).thenReturn(false);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        connection.onNext(response);

        assertThat(streamingBlockNumber).hasValue(12); // should be 1 + acked block number

        verify(bufferService, atLeastOnce()).getLastBlockNumberProduced();
        verify(bufferService).setLatestAcknowledgedBlock(11L);
        verify(bufferService).getHighestAckedBlockNumber();
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
        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(10); // pretend we are streaming block 10
        final PublishStreamResponse response = createBlockAckResponse(10L);

        when(bufferService.getLastBlockNumberProduced()).thenReturn(10L);
        when(connectionManager.recordBlockAckAndCheckLatency(eq(connection.configuration()), eq(10L), any()))
                .thenReturn(latencyResult);
        when(latencyResult.shouldSwitch()).thenReturn(false);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        connection.onNext(response);

        // Should not jump to block since acknowledgement is not newer
        assertThat(streamingBlockNumber).hasValue(10L);

        verify(bufferService, atLeastOnce()).getLastBlockNumberProduced();
        verify(bufferService).setLatestAcknowledgedBlock(10L);
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
        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(10);
        final PublishStreamResponse response = createBlockAckResponse(10L);

        when(bufferService.getLastBlockNumberProduced()).thenReturn(10L);
        when(connectionManager.recordBlockAckAndCheckLatency(eq(connection.configuration()), eq(10L), any()))
                .thenReturn(latencyResult);
        when(latencyResult.shouldSwitch()).thenReturn(true);
        when(latencyResult.consecutiveHighLatencyEvents()).thenReturn(5);
        when(connectionManager.isOnlyOneBlockNodeConfigured()).thenReturn(false);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        connection.onNext(response);

        // Should not jump to block since acknowledgement is not newer
        assertThat(streamingBlockNumber).hasValue(10L);

        verify(bufferService, atLeastOnce()).getLastBlockNumberProduced();
        verify(bufferService).setLatestAcknowledgedBlock(10L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.ACKNOWLEDGEMENT);
        verify(connectionManager).isOnlyOneBlockNodeConfigured();
        verify(connectionManager).rescheduleConnection(eq(connection), any(Duration.class), eq(null), eq(true));
    }

    @Test
    void testScheduleStreamResetTask() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Verify task was scheduled to periodically reset the stream
        verify(executorService)
                .scheduleAtFixedRate(
                        any(Runnable.class),
                        eq(ONCE_PER_DAY_MILLIS), // initial delay
                        eq(ONCE_PER_DAY_MILLIS), // period
                        eq(TimeUnit.MILLISECONDS));

        verifyNoMoreInteractions(executorService);
        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
    }

    @ParameterizedTest
    @EnumSource(
            value = EndOfStream.Code.class,
            names = {"ERROR", "PERSISTENCE_FAILED"})
    void testOnNext_endOfStream_blockNodeInternalError(final EndOfStream.Code responseCode) {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        final PublishStreamResponse response = createEndOfStreamResponse(responseCode, 10L);
        connection.onNext(response);

        verify(metrics).recordLatestBlockEndOfStream(10L);
        verify(metrics).recordResponseEndOfStreamReceived(responseCode);
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verify(requestPipeline).onComplete();
        verify(connectionManager).rescheduleConnection(connection, Duration.ofSeconds(30), null, true);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
    }

    @ParameterizedTest
    @EnumSource(
            value = EndOfStream.Code.class,
            names = {"TIMEOUT", "DUPLICATE_BLOCK", "BAD_BLOCK_PROOF", "INVALID_REQUEST"})
    void testOnNext_endOfStream_clientFailures(final EndOfStream.Code responseCode) {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        final PublishStreamResponse response = createEndOfStreamResponse(responseCode, 10L);
        connection.onNext(response);

        verify(metrics).recordLatestBlockEndOfStream(10L);
        verify(metrics).recordResponseEndOfStreamReceived(responseCode);
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verify(requestPipeline).onComplete();
        verify(connectionManager).rescheduleConnection(connection, null, 11L, false);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
    }

    @Test
    void testOnNext_endOfStream_blockNodeGracefulShutdown() {
        openConnectionAndResetMocks();
        // STREAM_ITEMS_SUCCESS is sent when the block node is gracefully shutting down
        final PublishStreamResponse response = createEndOfStreamResponse(Code.SUCCESS, 10L);
        connection.updateConnectionState(ConnectionState.ACTIVE);
        connection.onNext(response);

        verify(metrics).recordLatestBlockEndOfStream(10L);
        verify(metrics).recordResponseEndOfStreamReceived(Code.SUCCESS);
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verify(requestPipeline).onComplete();
        verify(connectionManager).rescheduleConnection(connection, Duration.ofSeconds(30), null, true);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
    }

    @Test
    void testOnNext_blockNodeBehind_blockExists() {
        openConnectionAndResetMocks();
        final PublishStreamResponse response = createBlockNodeBehindResponse(10L);
        when(bufferService.getBlockState(11L)).thenReturn(new BlockState(11L));
        connection.updateConnectionState(ConnectionState.ACTIVE);

        connection.onNext(response);

        verify(metrics).recordLatestBlockBehindPublisher(10L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.NODE_BEHIND_PUBLISHER);
        verify(bufferService).getBlockState(11L);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
    }

    @Test
    void testOnNext_blockNodeBehind_blockDoesNotExist_TooFarBehind() {
        openConnectionAndResetMocks();
        final PublishStreamResponse response = createBlockNodeBehindResponse(10L);
        when(bufferService.getBlockState(11L)).thenReturn(null);
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(12L);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        connection.onNext(response);

        verify(metrics).recordLatestBlockBehindPublisher(10L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.NODE_BEHIND_PUBLISHER);
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verify(metrics).recordRequestEndStreamSent(EndStream.Code.TOO_FAR_BEHIND);
        verify(metrics).recordRequestLatency(anyLong());
        verify(bufferService, atLeastOnce()).getEarliestAvailableBlockNumber();
        verify(bufferService, atLeastOnce()).getHighestAckedBlockNumber();
        verify(bufferService).getBlockState(11L);
        verify(requestPipeline)
                .onNext(PublishStreamRequest.newBuilder()
                        .endStream(EndStream.newBuilder()
                                .endCode(EndStream.Code.TOO_FAR_BEHIND)
                                .earliestBlockNumber(12L)
                                .build())
                        .build());
        verify(requestPipeline).onComplete();
        verify(connectionManager).rescheduleConnection(connection, Duration.ofSeconds(30), null, true);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
    }

    @Test
    void testOnNext_endOfStream_blockNodeBehind_blockDoesNotExist_Error() {
        openConnectionAndResetMocks();
        final PublishStreamResponse response = createBlockNodeBehindResponse(10L);
        when(bufferService.getHighestAckedBlockNumber()).thenReturn(10L);
        when(bufferService.getBlockState(11L)).thenReturn(null);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        connection.onNext(response);

        verify(metrics).recordLatestBlockBehindPublisher(10L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.NODE_BEHIND_PUBLISHER);
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verify(metrics).recordRequestEndStreamSent(EndStream.Code.ERROR);
        verify(metrics).recordRequestLatency(anyLong());
        verify(bufferService, atLeastOnce()).getEarliestAvailableBlockNumber();
        verify(bufferService, atLeastOnce()).getHighestAckedBlockNumber();
        verify(bufferService).getBlockState(11L);
        verify(requestPipeline)
                .onNext(PublishStreamRequest.newBuilder()
                        .endStream(EndStream.newBuilder()
                                .endCode(EndStream.Code.ERROR)
                                .latestBlockNumber(10L)
                                .build())
                        .build());
        verify(requestPipeline).onComplete();
        verify(connectionManager).rescheduleConnection(connection, Duration.ofSeconds(30), null, true);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
    }

    @Test
    void testOnNext_endOfStream_itemsUnknown() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        final PublishStreamResponse response = createEndOfStreamResponse(Code.UNKNOWN, 10L);
        connection.onNext(response);

        verify(metrics).recordLatestBlockEndOfStream(10L);
        verify(metrics).recordResponseEndOfStreamReceived(Code.UNKNOWN);
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verify(requestPipeline).onComplete();
        verify(connectionManager).rescheduleConnection(connection, Duration.ofSeconds(30), null, true);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
    }

    @Test
    void testOnNext_skipBlock_sameAsStreaming() {
        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(25); // pretend we are currently streaming block 25
        final PublishStreamResponse response = createSkipBlock(25L);
        connection.updateConnectionState(ConnectionState.ACTIVE);
        connection.onNext(response);

        assertThat(streamingBlockNumber).hasValue(26);

        verify(metrics).recordLatestBlockSkipBlock(25L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.SKIP_BLOCK);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testOnNext_skipBlockOlderBlock() {
        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(27); // pretend we are currently streaming block 27
        final PublishStreamResponse response = createSkipBlock(25L);
        connection.updateConnectionState(ConnectionState.ACTIVE);

        connection.onNext(response);

        assertThat(streamingBlockNumber).hasValue(27);

        verify(metrics).recordLatestBlockSkipBlock(25L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.SKIP_BLOCK);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testOnNext_resendBlock_blockExists() {
        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(11); // pretend we are currently streaming block 11
        final PublishStreamResponse response = createResendBlock(10L);
        when(bufferService.getBlockState(10L)).thenReturn(new BlockState(10L));

        connection.onNext(response);

        assertThat(streamingBlockNumber).hasValue(10);

        verify(metrics).recordLatestBlockResendBlock(10L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.RESEND_BLOCK);
        verify(bufferService).getBlockState(10L);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testOnNext_resendBlock_blockDoesNotExist_TooFarBehind() {
        openConnectionAndResetMocks();

        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(11); // pretend we are currently streaming block 11
        final PublishStreamResponse response = createResendBlock(10L);
        when(bufferService.getBlockState(10L)).thenReturn(null);
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(11L);
        connection.updateConnectionState(ConnectionState.ACTIVE);

        connection.onNext(response);

        verify(metrics).recordLatestBlockResendBlock(10L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.RESEND_BLOCK);
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verify(metrics).recordRequestLatency(anyLong());
        verify(metrics).recordRequestEndStreamSent(EndStream.Code.TOO_FAR_BEHIND);
        verify(requestPipeline).onNext(createRequest(EndStream.Code.TOO_FAR_BEHIND, 11L));
        verify(requestPipeline).onComplete();
        verify(connectionManager).rescheduleConnection(connection, Duration.ofSeconds(30), null, true);
        verify(connectionManager).notifyConnectionClosed(connection);
        verify(bufferService).getBlockState(10L);
        verify(bufferService, times(2)).getEarliestAvailableBlockNumber();
        verify(bufferService).getHighestAckedBlockNumber();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testOnNext_resendBlock_blockDoesNotExist_Error() {
        openConnectionAndResetMocks();

        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(11); // pretend we are currently streaming block 11
        final PublishStreamResponse response = createResendBlock(10L);
        when(bufferService.getBlockState(10L)).thenReturn(null);
        connection.updateConnectionState(ConnectionState.ACTIVE);

        connection.onNext(response);

        verify(metrics).recordLatestBlockResendBlock(10L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.RESEND_BLOCK);
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verify(metrics).recordRequestLatency(anyLong());
        verify(metrics).recordRequestEndStreamSent(EndStream.Code.ERROR);
        verify(requestPipeline).onNext(createRequest(EndStream.Code.ERROR));
        verify(requestPipeline).onComplete();
        verify(connectionManager).rescheduleConnection(connection, Duration.ofSeconds(30), null, true);
        verify(bufferService).getBlockState(10L);
        verify(bufferService, times(2)).getEarliestAvailableBlockNumber();
        verify(bufferService).getLastBlockNumberProduced();
        verify(bufferService).getHighestAckedBlockNumber();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testOnNext_unknown() {
        final PublishStreamResponse response = new PublishStreamResponse(new OneOf<>(ResponseOneOfType.UNSET, null));
        connection.updateConnectionState(ConnectionState.ACTIVE);
        connection.onNext(response);

        verify(metrics).recordUnknownResponseReceived();

        verifyNoMoreInteractions(metrics);
        verifyNoInteractions(requestPipeline);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testSendRequest() {
        openConnectionAndResetMocks();
        final PublishStreamRequest request = createRequest(newBlockHeaderItem());

        connection.updateConnectionState(ConnectionState.ACTIVE);
        sendRequest(new BlockItemsStreamRequest(request, 1L, 1, 1, false, false));

        verify(requestPipeline).onNext(request);
        verify(metrics).recordRequestSent(RequestOneOfType.BLOCK_ITEMS);
        verify(metrics).recordBlockItemsSent(1);
        verify(metrics).recordRequestLatency(anyLong());
        verify(metrics).recordRequestBlockItemCount(1);
        verify(metrics).recordRequestBytes(anyLong());
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testSendRequest_notActive() {
        final PublishStreamRequest request = createRequest(newBlockHeaderItem());

        connection.initialize();
        connection.updateConnectionState(ConnectionState.READY);
        sendRequest(new BlockItemsStreamRequest(request, 1L, 1, 1, false, false));

        verify(metrics).recordConnectionOpened();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testSendRequest_observerNull() {
        final PublishStreamRequest request = createRequest(newBlockHeaderItem());

        // don't create the observer
        connection.updateConnectionState(ConnectionState.READY);
        sendRequest(new BlockItemsStreamRequest(request, 1L, 1, 1, false, false));

        verifyNoInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testSendRequest_errorWhileActive() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);
        doThrow(new RuntimeException("kaboom!")).when(requestPipeline).onNext(any());
        final PublishStreamRequest request = createRequest(newBlockHeaderItem());

        final RuntimeException e =
                catchRuntimeException(() -> sendRequest(new BlockItemsStreamRequest(request, 1L, 1, 1, false, false)));
        assertThat(e).isInstanceOf(RuntimeException.class);
        // Exception gets wrapped when executed in virtual thread executor
        assertThat(e.getMessage()).contains("Error executing pipeline.onNext()");
        assertThat(e.getCause()).isInstanceOf(RuntimeException.class);
        assertThat(e.getCause().getMessage()).isEqualTo("kaboom!");

        verify(metrics).recordRequestSendFailure();
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testSendRequest_errorWhileNotActive() {
        openConnectionAndResetMocks();
        doThrow(new RuntimeException("kaboom!")).when(requestPipeline).onNext(any());

        final BlockNodeStreamingConnection spiedConnection = spy(connection);
        doReturn(ConnectionState.ACTIVE, ConnectionState.CLOSING)
                .when(spiedConnection)
                .currentState();
        final PublishStreamRequest request = createRequest(newBlockHeaderItem());

        sendRequest(spiedConnection, new BlockItemsStreamRequest(request, 1L, 1, 1, false, false));

        verify(requestPipeline).onNext(any());
        verify(spiedConnection, atLeast(2)).currentState();

        verifyNoInteractions(metrics);
    }

    // Tests sendRequest when ACTIVE but requestPipeline is null (should do nothing)
    @Test
    void testSendRequest_activeButPipelineNull() {
        final PublishStreamRequest request = createRequest(newBlockHeaderItem());

        // Set to ACTIVE state but don't create the pipeline
        connection.updateConnectionState(ConnectionState.ACTIVE);
        // requestPipeline remains null since we didn't call initialize()

        sendRequest(new BlockItemsStreamRequest(request, 1L, 1, 1, false, false));

        // Should not interact with anything since pipeline is null
        verifyNoInteractions(metrics);
        verifyNoInteractions(requestPipeline);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testClose() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Verify task was scheduled to periodically reset the stream
        verify(executorService)
                .scheduleAtFixedRate(
                        any(Runnable.class),
                        eq(ONCE_PER_DAY_MILLIS), // initial delay
                        eq(ONCE_PER_DAY_MILLIS), // period
                        eq(TimeUnit.MILLISECONDS));

        connection.close(true);

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);

        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verify(requestPipeline).onComplete();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testClose_failure() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Verify task was scheduled to periodically reset the stream
        verify(executorService)
                .scheduleAtFixedRate(
                        any(Runnable.class),
                        eq(ONCE_PER_DAY_MILLIS), // initial delay
                        eq(ONCE_PER_DAY_MILLIS), // period
                        eq(TimeUnit.MILLISECONDS));

        connection.close(true);

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);

        verify(requestPipeline).onComplete();
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoInteractions(bufferService);
    }

    // Tests close operation without calling onComplete on pipeline
    @Test
    void testClose_withoutOnComplete() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        connection.close(false);

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);

        // Should not call onComplete when callOnComplete is false
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verifyNoMoreInteractions(metrics);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(requestPipeline);
    }

    // Tests close operation when connection is not in ACTIVE state
    @Test
    void testClose_notActiveState() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.READY);

        connection.close(true);

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);

        // Should call onComplete when callOnComplete=true and state transitions to CLOSING
        verify(requestPipeline).onComplete();
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoInteractions(bufferService);
    }

    // Tests exception handling during close operation (should catch and log RuntimeException)
    @Test
    void testClose_exceptionDuringClose() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Mock Pipeline#onComplete to throw a RuntimeException to trigger the catch block
        doThrow(new RuntimeException("Simulated close error"))
                .when(requestPipeline)
                .onComplete();

        // This should not throw an exception - it should be caught and logged
        connection.close(true);

        // Verify the exception handling path was taken
        verify(requestPipeline).onComplete(); // closePipeline should still be called before the exception

        // Connection state should still be CLOSED even after the exception
        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
    }

    // Tests exception handling during pipeline completion (should catch and log Exception)
    @Test
    void testClose_exceptionDuringPipelineCompletion() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Mock requestPipeline.onComplete() to throw an Exception to trigger the catch block in closePipeline
        doThrow(new RuntimeException("Simulated pipeline completion error"))
                .when(requestPipeline)
                .onComplete();

        // This should not throw an exception - it should be caught and logged
        connection.close(true);

        // Verify the exception handling path was taken
        verify(requestPipeline).onComplete(); // Should be called and throw exception

        // Connection state should still be CLOSED even after the pipeline exception
        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
    }

    // Tests close operation when requestPipeline is null (should skip pipeline closure)
    @Test
    void testClose_pipelineNull() {
        // Don't call openConnectionAndResetMocks() to avoid creating a pipeline
        connection.updateConnectionState(ConnectionState.ACTIVE);
        // requestPipeline remains null since we didn't call initialize()

        connection.close(true);

        // Should complete successfully without interacting with pipeline
        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);

        // Should not interact with pipeline since it's null
        verifyNoInteractions(requestPipeline);
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verifyNoMoreInteractions(metrics);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testClose_alreadyClosed() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.CLOSED);

        connection.close(true);

        verifyNoInteractions(connectionManager);
        verifyNoInteractions(requestPipeline);
        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testClose_alreadyClosing() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.CLOSING);

        connection.close(true);

        verifyNoInteractions(connectionManager);
        verifyNoInteractions(requestPipeline);
        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testClose_stateChangedDuringClose() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Create a spy to intercept the close method and change state during execution
        final BlockNodeStreamingConnection spyConnection = spy(connection);
        final AtomicBoolean stateChanged = new AtomicBoolean(false);

        // Override status to trigger state change on first call
        doAnswer(invocation -> {
                    final ConnectionState result = (ConnectionState) invocation.callRealMethod();
                    if (!stateChanged.get()) {
                        stateChanged.set(true);
                        // Change the actual internal state to cause fail
                        final AtomicReference<ConnectionState> state = connectionState();
                        state.set(ConnectionState.READY);
                    }
                    return result;
                })
                .when(spyConnection)
                .currentState();

        // Now call close - it will get ACTIVE from state,
        // but then the state will be READY when it tries to CAS
        spyConnection.close(true);

        // The close should have aborted due to state mismatch
        // State should still be READY (not changed to CLOSING or CLOSED)
        assertThat(connection.currentState()).isEqualTo(ConnectionState.READY);

        // No interactions should have occurred since close aborted early
        verifyNoInteractions(requestPipeline);
    }

    @Test
    void testOnError_activeConnection() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        connection.onError(new RuntimeException("oh bother"));

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);

        verify(metrics).recordConnectionOnError();
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verify(requestPipeline).onComplete();
        verify(connectionManager).rescheduleConnection(connection, Duration.ofSeconds(30), null, true);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testOnError_grpcException() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Create a real GrpcException
        final GrpcException grpcException =
                new GrpcException(GrpcStatus.UNAVAILABLE, new RuntimeException("Service unavailable"));

        connection.onError(grpcException);

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);

        verify(metrics).recordConnectionOnError();
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verify(requestPipeline).onComplete();
        verify(connectionManager).rescheduleConnection(connection, Duration.ofSeconds(30), null, true);
    }

    @Test
    void testOnError_terminalConnection() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.CLOSING);

        connection.onError(new RuntimeException("oh bother"));

        verifyNoInteractions(metrics);
        verifyNoInteractions(requestPipeline);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testOnCompleted_streamClosingInProgress() {
        openConnectionAndResetMocks();
        connection.close(true); // call this so we mark the connection as closing
        resetMocks();

        connection.onComplete();

        verify(metrics).recordConnectionOnComplete();
        verifyNoMoreInteractions(metrics);
        verifyNoInteractions(requestPipeline);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    // Tests onComplete when streamShutdownInProgress is true but connection not closed
    @Test
    void testOnCompleted_streamShutdownInProgressButNotClosed() throws Exception {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Use reflection to set streamShutdownInProgress to true without closing the connection
        // This simulates the race condition where shutdown begins but onComplete arrives first
        final var field = BlockNodeStreamingConnection.class.getDeclaredField("streamShutdownInProgress");
        field.setAccessible(true);
        final AtomicBoolean streamShutdownInProgress = (AtomicBoolean) field.get(connection);
        streamShutdownInProgress.set(true);

        connection.onComplete();

        // Should log that stream close was in progress and not call handleStreamFailure
        // The flag should be reset to false by getAndSet(false)
        assertThat(streamShutdownInProgress.get()).isFalse();

        verify(metrics).recordConnectionOnComplete();

        verifyNoMoreInteractions(metrics);
        // Should not interact with dependencies since shutdown was expected
        verifyNoInteractions(requestPipeline);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testOnCompleted_streamClosingNotInProgress() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // don't call close so we do not mark the connection as closing
        connection.onComplete();

        verify(requestPipeline).onComplete();
        verify(connectionManager).rescheduleConnection(connection, Duration.ofSeconds(30), null, true);
        verify(metrics).recordConnectionOnComplete();
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testConnectionWorker_switchBlock_initialValue() throws Exception {
        openConnectionAndResetMocks();
        connection.initialize();
        connection.updateConnectionState(ConnectionState.ACTIVE);
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        doReturn(101L).when(bufferService).getLastBlockNumberProduced();
        doReturn(new BlockState(101)).when(bufferService).getBlockState(101);

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
        verifyNoInteractions(requestPipeline);
    }

    @Test
    void testConnectionWorker_switchBlock_noBlockAvailable() throws Exception {
        openConnectionAndResetMocks();
        connection.initialize();
        connection.updateConnectionState(ConnectionState.ACTIVE);
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        doReturn(-1L).when(bufferService).getLastBlockNumberProduced();

        assertThat(streamingBlockNumber).hasValue(-1);

        final Object worker = createWorker();
        invokeDoWork(worker);

        assertThat(streamingBlockNumber).hasValue(-1);

        verify(bufferService).getLastBlockNumberProduced();
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(metrics);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(requestPipeline);
    }

    @Test
    void testConnectionWorker_noItemsAvailable() throws Exception {
        openConnectionAndResetMocks();
        connection.initialize();
        connection.updateConnectionState(ConnectionState.ACTIVE);
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        streamingBlockNumber.set(10);
        doReturn(new BlockState(10)).when(bufferService).getBlockState(10);

        // Call doWork directly - with no items in the block, nothing should be sent
        final Object worker = createWorker();
        invokeDoWork(worker);

        assertThat(streamingBlockNumber).hasValue(10);

        verify(bufferService).getBlockState(10);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(metrics);
        verifyNoInteractions(requestPipeline);
    }

    @Test
    void testConnectionWorker_blockNodeTooFarBehind() throws Exception {
        openConnectionAndResetMocks();
        connection.initialize();
        connection.updateConnectionState(ConnectionState.ACTIVE);
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        streamingBlockNumber.set(10);
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(15L);
        when(bufferService.getBlockState(anyLong())).thenReturn(null);
        when(bufferService.getHighestAckedBlockNumber()).thenReturn(-1L);

        // block 10 is too far behind, should send TOO_FAR_BEHIND
        final Object worker = createWorker();
        invokeDoWork(worker);

        assertThat(streamingBlockNumber).hasValue(10);

        verify(metrics).recordRequestLatency(anyLong());
        verify(metrics).recordRequestEndStreamSent(EndStream.Code.TOO_FAR_BEHIND);
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verify(bufferService, times(2)).getEarliestAvailableBlockNumber();
        verify(bufferService).getHighestAckedBlockNumber();
        verify(connectionManager).notifyConnectionClosed(connection);
        verify(connectionManager).rescheduleConnection(connection, Duration.ofSeconds(30), null, true);

        final ArgumentCaptor<PublishStreamRequest> requestCaptor = ArgumentCaptor.forClass(PublishStreamRequest.class);
        verify(requestPipeline).onNext(requestCaptor.capture());
        verify(requestPipeline).onComplete();

        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(requestPipeline);
    }

    @Test
    void testConnectionWorker_blockJump() throws Exception {
        openConnectionAndResetMocks();
        connection.initialize();
        connection.updateConnectionState(ConnectionState.ACTIVE);
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        streamingBlockNumber.set(10);

        final BlockState block10 = new BlockState(10);
        final BlockItem block10Header = newBlockHeaderItem(10);
        block10.addItem(block10Header);

        final BlockState block11 = new BlockState(11);
        final BlockItem block11Header = newBlockHeaderItem(11);
        block11.addItem(block11Header);

        doReturn(block10).when(bufferService).getBlockState(10);
        doReturn(block11).when(bufferService).getBlockState(11);
        lenient().doReturn(null).when(bufferService).getBlockState(12L); // May check for block 12 after advancing

        final ArgumentCaptor<PublishStreamRequest> requestCaptor = ArgumentCaptor.forClass(PublishStreamRequest.class);
        final Object worker = createWorker();

        // Call doWork - accumulates block 10 header (doesn't send yet)
        invokeDoWork(worker);

        // Verify nothing sent yet
        verify(requestPipeline, times(0)).onNext(any());

        // Now simulate a skip block response - forces jump to block 11
        final PublishStreamResponse skipResponse = createSkipBlock(10L);
        connection.onNext(skipResponse);

        // Call doWork again - should now process block 11 header
        block11.closeBlock(); // Close block 11 to force sending
        invokeDoWork(worker);

        // After sending block 11 completely, it will advance to block 12
        assertThat(streamingBlockNumber).hasValue(12);

        verify(requestPipeline, atLeastOnce()).onNext(requestCaptor.capture());
        List<PublishStreamRequest> allRequests = requestCaptor.getAllValues();
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
        verifyNoMoreInteractions(requestPipeline);
    }

    @Test
    void testConnectionWorker_hugeItems() throws Exception {
        openConnectionAndResetMocks();
        connection.initialize();
        connection.updateConnectionState(ConnectionState.ACTIVE);
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        streamingBlockNumber.set(10);

        final BlockNodeConfiguration config = connection.configuration();
        // sanity check to make sure the sizes we are about to use are within the scope of the soft and hard limits
        assertThat(config.messageSizeSoftLimitBytes()).isEqualTo(2_097_152L); // soft limit = 2 MB
        assertThat(config.messageSizeHardLimitBytes()).isEqualTo(6_292_480L); // hard limit = 6 MB + 1 KB

        final BlockState block = new BlockState(10);
        final BlockItem item1 = newBlockHeaderItem(10);
        final BlockItem item2 = newBlockTxItem(5_000);
        final BlockItem item3 = newBlockTxItem(5_000);
        final BlockItem item4 = newBlockTxItem(3_001_500);
        final BlockItem item5 = newBlockTxItem(255);
        final BlockItem item6 = newBlockTxItem(1_950_000);
        final BlockItem item7 = newBlockTxItem(1_750_000);
        final BlockItem item8 = newBlockTxItem(25);
        final BlockItem item9 = newBlockTxItem(9_002_875);

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

        final ArgumentCaptor<PublishStreamRequest> requestCaptor = ArgumentCaptor.forClass(PublishStreamRequest.class);
        final Object worker = createWorker();

        invokeDoWork(worker);
        invokeDoWork(worker);
        invokeDoWork(worker);
        invokeDoWork(worker);
        invokeDoWork(worker);
        invokeDoWork(worker);

        verify(requestPipeline, times(5)).onNext(requestCaptor.capture());

        /*
        There should be 5 requests:
        Request 1: item 1, 2, and 3
        Request 2: item 4
        Request 3: item 5 and 6
        Request 4: item 7 and 8
        Request 5: EndStream.Error because item 8 was too big
         */

        assertThat(requestCaptor.getAllValues()).hasSize(5);
        final List<PublishStreamRequest> requests = requestCaptor.getAllValues();

        final PublishStreamRequest req1 = requests.get(0);
        assertThat(req1.blockItemsOrElse(BlockItemSet.DEFAULT).blockItems())
                .hasSize(3)
                .containsExactly(item1, item2, item3);
        final PublishStreamRequest req2 = requests.get(1);
        assertThat(req2.blockItemsOrElse(BlockItemSet.DEFAULT).blockItems())
                .hasSize(1)
                .containsExactly(item4);
        final PublishStreamRequest req3 = requests.get(2);
        assertThat(req3.blockItemsOrElse(BlockItemSet.DEFAULT).blockItems())
                .hasSize(2)
                .containsExactly(item5, item6);
        final PublishStreamRequest req4 = requests.get(3);
        assertThat(req4.blockItemsOrElse(BlockItemSet.DEFAULT).blockItems())
                .hasSize(2)
                .containsExactly(item7, item8);
        final PublishStreamRequest req5 = requests.get(4);
        assertThat(req5.hasEndStream()).isTrue();
        final EndStream endStream = req5.endStream();
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

        verify(metrics, times(6)).recordRequestExceedsHardLimit();
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verify(metrics, times(5)).recordRequestLatency(anyLong());
        verify(requestPipeline).onComplete();
        verify(bufferService, times(6)).getEarliestAvailableBlockNumber();
        verify(bufferService, times(6)).getHighestAckedBlockNumber();
        verify(connectionManager).notifyConnectionClosed(connection);
        verify(metrics, atLeastOnce()).recordStreamingBlockNumber(anyLong());
        verify(metrics, atLeastOnce()).recordRequestBlockItemCount(anyInt());
        verify(metrics, atLeastOnce()).recordRequestBytes(anyLong());

        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
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
        verifyNoInteractions(requestPipeline);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    // Tests EndOfStream rate limiting - connection closes when limit exceeded
    @Test
    void testOnNext_endOfStream_rateLimitExceeded() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);
        final PublishStreamResponse response = createEndOfStreamResponse(Code.ERROR, 10L);

        when(connectionManager.recordEndOfStreamAndCheckLimit(eq(nodeConfig), any()))
                .thenReturn(true);
        when(connectionManager.getEndOfStreamScheduleDelay()).thenReturn(Duration.ofMinutes(5));

        connection.onNext(response);

        verify(metrics).recordLatestBlockEndOfStream(10L);
        verify(metrics).recordResponseEndOfStreamReceived(Code.ERROR);
        verify(metrics).recordEndOfStreamLimitExceeded();
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verify(connectionManager).recordEndOfStreamAndCheckLimit(eq(nodeConfig), any());
        verify(connectionManager).rescheduleConnection(connection, Duration.ofMinutes(5), null, true);
        verify(requestPipeline).onComplete();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
    }

    // Tests EndOfStream client failure codes with Long.MAX_VALUE edge case (should restart at block 0)
    @ParameterizedTest
    @EnumSource(
            value = EndOfStream.Code.class,
            names = {"TIMEOUT", "DUPLICATE_BLOCK", "BAD_BLOCK_PROOF", "INVALID_REQUEST"})
    void testOnNext_endOfStream_clientFailures_maxValueBlockNumber(final EndOfStream.Code responseCode) {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        final PublishStreamResponse response = createEndOfStreamResponse(responseCode, Long.MAX_VALUE);
        connection.onNext(response);

        verify(metrics).recordLatestBlockEndOfStream(Long.MAX_VALUE);
        verify(metrics).recordResponseEndOfStreamReceived(responseCode);
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verify(requestPipeline).onComplete();
        verify(connectionManager).rescheduleConnection(connection, null, 0L, false);

        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);

        // Verify connection is closed after handling EndOfStream
        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
    }

    // Tests BehindPublisher code with Long.MAX_VALUE edge case (should restart at block 0)
    @Test
    void testOnNext_blockNodeBehind_maxValueBlockNumber() {
        openConnectionAndResetMocks();
        final PublishStreamResponse response = createBlockNodeBehindResponse(Long.MAX_VALUE);
        when(bufferService.getBlockState(0L)).thenReturn(new BlockState(0L));
        connection.updateConnectionState(ConnectionState.ACTIVE);

        connection.onNext(response);

        verify(metrics).recordLatestBlockBehindPublisher(Long.MAX_VALUE);
        verify(metrics).recordResponseReceived(ResponseOneOfType.NODE_BEHIND_PUBLISHER);
        verify(bufferService).getBlockState(0L);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(bufferService);
    }

    // Tests stream failure handling without calling onComplete on the pipeline
    @Test
    void testHandleStreamFailureWithoutOnComplete() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        connection.handleStreamFailureWithoutOnComplete();

        final ConnectionState postState = connection.currentState();
        assertThat(postState).isEqualTo(ConnectionState.CLOSED);

        // Should not call onComplete on the pipeline
        verify(connectionManager).rescheduleConnection(connection, Duration.ofSeconds(30), null, true);
        verifyNoInteractions(requestPipeline);
    }

    // Tests that error handling is skipped when connection is already closed
    @Test
    void testOnError_connectionClosed() {
        connection.updateConnectionState(ConnectionState.CLOSED);

        connection.onError(new RuntimeException("test error"));

        // Should not handle error when connection is already closed (terminal state)
        verifyNoInteractions(metrics);
        verifyNoInteractions(requestPipeline);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    // Tests error handling in READY state (should not call onComplete on pipeline)
    @Test
    void testOnError_connectionReady() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.READY);

        connection.onError(new RuntimeException("test error"));

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);

        verify(metrics).recordConnectionOnError();
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verify(connectionManager).rescheduleConnection(connection, Duration.ofSeconds(30), null, true);
        // Should call onComplete when callOnComplete=true (from handleStreamFailure)
        verify(requestPipeline).onComplete();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
    }

    // Tests error handling in UNINITIALIZED state (should do nothing)
    @Test
    void testOnError_connectionUninitialized() {
        // Connection starts in UNINITIALIZED state by default
        assertThat(connection.currentState()).isEqualTo(ConnectionState.UNINITIALIZED);

        connection.onError(new RuntimeException("test error"));

        // Should transition to CLOSED state after handling the error
        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);

        verify(metrics).recordConnectionOnError();
        verify(connectionManager).rescheduleConnection(connection, Duration.ofSeconds(30), null, true);
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verifyNoMoreInteractions(metrics);
        verifyNoInteractions(requestPipeline);
        verifyNoInteractions(bufferService);
    }

    // Tests explicit stream termination with proper EndStream request parameters
    @Test
    void testEndTheStreamWith() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(5L);
        when(bufferService.getHighestAckedBlockNumber()).thenReturn(15L);

        connection.endTheStreamWith(PublishStreamRequest.EndStream.Code.RESET);

        // Verify the EndStream request was sent with correct parameters
        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getHighestAckedBlockNumber();
        verify(requestPipeline).onNext(any(PublishStreamRequest.class));
        verify(requestPipeline).onComplete();

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
    }

    // Tests client-side end stream handling (should have no side effects)
    @Test
    void testClientEndStreamReceived() {
        // This method calls the superclass implementation - test that it doesn't throw exceptions
        // and doesn't change connection state or interact with dependencies
        final ConnectionState initialState = connection.currentState();

        connection.clientEndStreamReceived();

        // Verify state unchanged and no side effects
        assertThat(connection.currentState()).isEqualTo(initialState);
        verifyNoInteractions(metrics);
        verifyNoInteractions(requestPipeline);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    // Tests Flow.Subscriber contract implementation (should request Long.MAX_VALUE)
    @Test
    void testOnSubscribe() {
        final Flow.Subscription subscription = mock(Flow.Subscription.class);

        connection.onSubscribe(subscription);

        verify(subscription).request(Long.MAX_VALUE);
        verifyNoInteractions(metrics);
        verifyNoInteractions(requestPipeline);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    // Tests connection state transition from ACTIVE to other states (should cancel reset task)
    @Test
    void testUpdateStatus_fromActiveToOther() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Reset mocks to focus on the state change
        reset(executorService);

        // Change from ACTIVE to CLOSING should cancel stream reset
        connection.updateConnectionState(ConnectionState.CLOSING);

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSING);
        verifyNoInteractions(executorService); // No new scheduling should happen
    }

    // Tests cancellation of existing stream reset task when rescheduling (task not done)
    @Test
    void testScheduleStreamReset_cancelExistingTask() {
        openConnectionAndResetMocks();

        // Create a mock ScheduledFuture that is not done to simulate existing task
        final ScheduledFuture<?> mockTask = mock(ScheduledFuture.class);
        when(mockTask.isDone()).thenReturn(false);

        // Configure executor to return our mock task
        doReturn(mockTask)
                .when(executorService)
                .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));

        // First activation - creates initial task
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Verify first task was scheduled
        verify(executorService)
                .scheduleAtFixedRate(
                        any(Runnable.class),
                        eq(ONCE_PER_DAY_MILLIS),
                        eq(ONCE_PER_DAY_MILLIS),
                        eq(TimeUnit.MILLISECONDS));

        // Reset executor mock but keep the task behavior
        reset(executorService);
        doReturn(mockTask)
                .when(executorService)
                .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));

        // Activate again - this should cancel the existing task and create a new one
        // This covers the lines: if (streamResetTask != null && !streamResetTask.isDone()) {
        // streamResetTask.cancel(false); }
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Verify the existing task was cancelled
        verify(mockTask).cancel(false);

        // Verify a new task was scheduled
        verify(executorService)
                .scheduleAtFixedRate(
                        any(Runnable.class),
                        eq(ONCE_PER_DAY_MILLIS),
                        eq(ONCE_PER_DAY_MILLIS),
                        eq(TimeUnit.MILLISECONDS));

        assertThat(connection.currentState()).isEqualTo(ConnectionState.ACTIVE);
    }

    // Tests rescheduling when existing stream reset task is already done (should not cancel)
    @Test
    void testScheduleStreamReset_existingTaskAlreadyDone() {
        openConnectionAndResetMocks();

        // Create a mock ScheduledFuture that IS done to simulate completed task
        final ScheduledFuture<?> mockTask = mock(ScheduledFuture.class);
        when(mockTask.isDone()).thenReturn(true); // Task is already done

        // Configure executor to return our mock task
        doReturn(mockTask)
                .when(executorService)
                .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));

        // First activation - creates initial task
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Verify first task was scheduled
        verify(executorService)
                .scheduleAtFixedRate(
                        any(Runnable.class),
                        eq(ONCE_PER_DAY_MILLIS),
                        eq(ONCE_PER_DAY_MILLIS),
                        eq(TimeUnit.MILLISECONDS));

        // Reset executor mock but keep the task behavior
        reset(executorService);
        doReturn(mockTask)
                .when(executorService)
                .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));

        // Activate again - this should NOT cancel the existing task since it's already done
        // This covers: if (streamResetTask != null && !streamResetTask.isDone()) - the null check passes but isDone()
        // is true
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Verify the existing task was NOT cancelled since it's already done
        verify(mockTask, times(0)).cancel(false); // Should not be called

        // Verify a new task was still scheduled
        verify(executorService)
                .scheduleAtFixedRate(
                        any(Runnable.class),
                        eq(ONCE_PER_DAY_MILLIS),
                        eq(ONCE_PER_DAY_MILLIS),
                        eq(TimeUnit.MILLISECONDS));

        assertThat(connection.currentState()).isEqualTo(ConnectionState.ACTIVE);
    }

    // Tests cancellation of stream reset task when transitioning away from ACTIVE state
    @Test
    void testCancelStreamReset_existingTask() {
        openConnectionAndResetMocks();

        // Create a mock ScheduledFuture that exists
        final ScheduledFuture<?> mockTask = mock(ScheduledFuture.class);

        // Configure executor to return our mock task
        doReturn(mockTask)
                .when(executorService)
                .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));

        // First, activate the connection to create a stream reset task
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Verify task was scheduled
        verify(executorService)
                .scheduleAtFixedRate(
                        any(Runnable.class),
                        eq(ONCE_PER_DAY_MILLIS),
                        eq(ONCE_PER_DAY_MILLIS),
                        eq(TimeUnit.MILLISECONDS));

        // Now change to a non-ACTIVE state to trigger cancelStreamReset()
        // This should cover: if (streamResetTask != null) { streamResetTask.cancel(false); ... }
        connection.updateConnectionState(ConnectionState.CLOSING);

        // Verify the task was cancelled
        verify(mockTask).cancel(false);

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSING);
    }

    // Tests execution of periodic stream reset task (should reset stream and close connection)
    @Test
    void testPeriodicStreamReset() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Capture the scheduled runnable
        final ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(executorService)
                .scheduleAtFixedRate(
                        runnableCaptor.capture(),
                        eq(ONCE_PER_DAY_MILLIS),
                        eq(ONCE_PER_DAY_MILLIS),
                        eq(TimeUnit.MILLISECONDS));

        reset(connectionManager, bufferService);

        // Execute the periodic reset
        final Runnable periodicReset = runnableCaptor.getValue();
        periodicReset.run();

        // Verify reset behavior
        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getHighestAckedBlockNumber();
        verify(connectionManager).selectNewBlockNodeForStreaming(false);
        verify(requestPipeline).onNext(any(PublishStreamRequest.class));
        verify(requestPipeline).onComplete();

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
    }

    // Tests that periodic reset task does nothing when connection is not ACTIVE
    @Test
    void testPeriodicStreamReset_connectionNotActive() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Capture the scheduled runnable
        final ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(executorService)
                .scheduleAtFixedRate(
                        runnableCaptor.capture(),
                        eq(ONCE_PER_DAY_MILLIS),
                        eq(ONCE_PER_DAY_MILLIS),
                        eq(TimeUnit.MILLISECONDS));

        // Change state to CLOSING before executing reset
        connection.updateConnectionState(ConnectionState.CLOSING);
        reset(connectionManager, bufferService, requestPipeline);

        // Execute the periodic reset
        final Runnable periodicReset = runnableCaptor.getValue();
        periodicReset.run();

        // Should not perform reset when connection is not active
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(requestPipeline);
    }

    // Pipeline operation timeout tests

    /**
     * Tests onNext() normal (non-timeout) path.
     */
    @Test
    void testSendRequest_onNextCompletesSuccessfully() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        final PublishStreamRequest request = createRequest(newBlockHeaderItem());
        sendRequest(new BlockItemsStreamRequest(request, 1L, 1, 1, false, false));

        // Verify the request was sent successfully
        verify(requestPipeline).onNext(request);
        verify(metrics).recordRequestSent(RequestOneOfType.BLOCK_ITEMS);
        verify(metrics).recordBlockItemsSent(1);
        verify(metrics).recordRequestLatency(anyLong());

        // Verify no timeout was recorded
        verify(metrics, times(0)).recordPipelineOperationTimeout();

        // Connection should still be ACTIVE
        assertThat(connection.currentState()).isEqualTo(ConnectionState.ACTIVE);
    }

    /**
     * Tests that sendRequest does not execute if connection is no longer ACTIVE.
     */
    @Test
    void testSendRequest_connectionNotActive() {
        openConnectionAndResetMocks();
        // Start in CLOSING state
        connection.updateConnectionState(ConnectionState.CLOSING);

        final PublishStreamRequest request = createRequest(newBlockHeaderItem());

        // Since connection is not ACTIVE, sendRequest should not do anything
        sendRequest(new BlockItemsStreamRequest(request, 1L, 1, 1, false, false));

        // Verify no interactions since connection is not ACTIVE
        verifyNoInteractions(requestPipeline);
        verifyNoInteractions(metrics);
        verifyNoInteractions(connectionManager);
    }

    /**
     * Tests that close operation completes successfully.
     */
    @Test
    void testClose_completesSuccessfully() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Close connection normally
        connection.close(true);

        // Verify close completed successfully
        verify(requestPipeline).onComplete();
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);

        // Connection should be CLOSED
        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
    }

    /**
     * Tests that closing without calling onComplete does not call onComplete on pipeline.
     * This covers the case where callOnComplete=false.
     */
    @Test
    void testClose_withoutOnCompleteDoesNotCallOnComplete() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Close without calling onComplete
        connection.close(false);

        // Verify onComplete was not called on pipeline
        verifyNoInteractions(requestPipeline);
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);

        // Connection should be CLOSED
        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
    }

    /**
     * Tests that error during pipeline operation is handled properly.
     * This tests the exception handling in sendRequest when pipeline.onNext throws.
     */
    @Test
    void testSendRequest_pipelineThrowsException() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Mock requestPipeline.onNext() to throw an exception
        doThrow(new RuntimeException("Pipeline error")).when(requestPipeline).onNext(any());

        final PublishStreamRequest request = createRequest(newBlockHeaderItem());

        // Should throw RuntimeException wrapped by the executor
        final RuntimeException exception =
                catchRuntimeException(() -> sendRequest(new BlockItemsStreamRequest(request, 1L, 1, 1, false, false)));

        assertThat(exception).isNotNull();
        // Exception gets wrapped when executed in virtual thread executor
        assertThat(exception.getMessage()).contains("Error executing pipeline.onNext()");
        assertThat(exception.getCause()).isInstanceOf(RuntimeException.class);
        assertThat(exception.getCause().getMessage()).isEqualTo("Pipeline error");

        // Verify error was recorded
        verify(requestPipeline).onNext(request);
        verify(metrics).recordRequestSendFailure();
    }

    /**
     * Tests that the pipelineExecutor is properly shut down when the connection closes.
     * This ensures no resource leaks and that the executor won't accept new tasks after close.
     */
    @Test
    void testClose_pipelineExecutorShutdown() throws InterruptedException {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Close the connection
        connection.close(true);

        // Verify connection is closed
        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);

        // Try to send a request after close - should be ignored since connection is CLOSED
        final PublishStreamRequest request = createRequest(newBlockHeaderItem());
        sendRequest(new BlockItemsStreamRequest(request, 1L, 1, 1, false, false));

        // Verify that the pipeline was NOT called (executor should be shut down and connection is CLOSED)
        verify(requestPipeline, times(1)).onComplete(); // Only from the close() call
        verify(requestPipeline, times(0)).onNext(any()); // sendRequest should not execute

        // Verify no additional interactions beyond the close operation
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verifyNoMoreInteractions(requestPipeline);
    }

    /**
     * Tests TimeoutException handling when pipeline.onNext() times out.
     * Uses mocks to simulate a timeout without actually waiting, making the test fast.
     */
    @Test
    void testSendRequest_timeoutException() throws Exception {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Create a mock Future that will throw TimeoutException when get() is called
        @SuppressWarnings("unchecked")
        final Future<Object> mockFuture = mock(Future.class);
        when(mockFuture.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException("Simulated timeout"));

        // Set up the pipelineExecutor to return mock future
        doReturn(mockFuture).when(pipelineExecutor).submit(any(Runnable.class));

        final PublishStreamRequest request = createRequest(newBlockHeaderItem());

        // Send request - should trigger timeout handling immediately
        sendRequest(new BlockItemsStreamRequest(request, 1L, 1, 1, false, false));

        // Verify timeout was detected and handled
        // Note: future.get() is called twice - once for sendRequest (times out)
        // and once for closePipeline/onComplete (also times out during cleanup)
        verify(mockFuture, times(2)).get(anyLong(), any(TimeUnit.class));
        verify(mockFuture, times(2)).cancel(true); // Future should be cancelled both times

        // Timeout metric is recorded twice - once for sendRequest, once for onComplete during close
        verify(metrics, times(2)).recordPipelineOperationTimeout();
        verify(metrics).recordConnectionClosed();
        verify(connectionManager).rescheduleConnection(eq(connection), eq(Duration.ofSeconds(30)), eq(null), eq(true));

        // Connection should be CLOSED after timeout
        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
    }

    /**
     * Tests TimeoutException handling when pipeline.onComplete() times out during close.
     * Uses mocks to simulate a timeout without actually waiting, making the test fast.
     */
    @Test
    void testClose_onCompleteTimeoutException() throws Exception {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Create a mock Future that will throw TimeoutException when get() is called
        @SuppressWarnings("unchecked")
        final Future<Object> mockFuture = mock(Future.class);
        when(mockFuture.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException("Simulated timeout"));

        // Set up the pipelineExecutor to return mock future
        doReturn(mockFuture).when(pipelineExecutor).submit(any(Runnable.class));

        // Close connection - should trigger timeout during onComplete
        connection.close(true);

        // Verify timeout was detected during onComplete
        verify(mockFuture).get(anyLong(), any(TimeUnit.class));
        verify(mockFuture).cancel(true);
        verify(metrics).recordPipelineOperationTimeout();
        verify(metrics).recordConnectionClosed();

        // Connection should still be CLOSED despite timeout
        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
    }

    /**
     * Tests InterruptedException handling during pipelineExecutor.awaitTermination() in close().
     * This covers the exception handling when shutting down the executor is interrupted.
     */
    @Test
    void testClose_executorShutdownInterruptedException() throws Exception {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Set up the pipelineExecutor to throw InterruptedException during awaitTermination
        when(pipelineExecutor.awaitTermination(anyLong(), any(TimeUnit.class)))
                .thenThrow(new InterruptedException("Simulated shutdown interruption"));

        // Close connection - should handle interruption during executor shutdown
        connection.close(true);

        // Verify executor shutdown was attempted
        verify(pipelineExecutor).shutdown();
        verify(pipelineExecutor).awaitTermination(5, TimeUnit.SECONDS);

        // Verify shutdownNow was called after interruption
        verify(pipelineExecutor).shutdownNow();

        verify(metrics).recordConnectionClosed();

        // Connection should still be CLOSED despite interruption
        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
    }

    /**
     * Tests ExecutionException handling when pipeline.onNext() throws an exception.
     * This is already covered by testSendRequest_pipelineThrowsException but included
     * here for completeness of exception handling coverage.
     */
    @Test
    void testSendRequest_executionException() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Mock requestPipeline.onNext() to throw an exception
        doThrow(new RuntimeException("Execution failed")).when(requestPipeline).onNext(any());

        final PublishStreamRequest request = createRequest(newBlockHeaderItem());
        final BlockItemsStreamRequest bisReq = new BlockItemsStreamRequest(request, 10L, 1, 1, false, false);

        // Should throw RuntimeException wrapping ExecutionException
        final RuntimeException exception = catchRuntimeException(() -> sendRequest(bisReq));

        assertThat(exception).isNotNull();
        assertThat(exception.getMessage()).contains("Error executing pipeline.onNext()");
        assertThat(exception.getCause()).isInstanceOf(RuntimeException.class);
        assertThat(exception.getCause().getMessage()).isEqualTo("Execution failed");

        verify(metrics).recordRequestSendFailure();
    }

    /**
     * Tests that closing the connection multiple times doesn't cause issues with executor shutdown.
     * The executor should only be shut down once, and subsequent closes should be idempotent.
     */
    @Test
    void testClose_multipleCloseCallsHandleExecutorShutdownGracefully() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Close the connection first time
        connection.close(true);
        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);

        // Reset mocks to verify second close behavior
        reset(requestPipeline, metrics, connectionManager);

        // Close again - should be idempotent (no-op since already closed)
        connection.close(true);

        // Verify no additional operations were performed
        verifyNoInteractions(requestPipeline);
        verifyNoInteractions(metrics);
        verifyNoInteractions(connectionManager);

        // Connection should still be CLOSED
        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
    }

    // Utilities

    private void openConnectionAndResetMocks() {
        connection.initialize();
        // reset the mocks interactions to remove tracked interactions as a result of starting the connection
        resetMocks();
    }

    private void resetMocks() {
        reset(connectionManager, requestPipeline, bufferService, metrics);
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<ConnectionState> connectionState() {
        return (AtomicReference<ConnectionState>) connectionStateHandle.get(connection);
    }

    private AtomicLong streamingBlockNumber() {
        return (AtomicLong) streamingBlockNumberHandle.get(connection);
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<Thread> workerThreadRef() {
        return (AtomicReference<Thread>) workerThreadRefHandle.get(connection);
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

    /**
     * Helper method to create a ConnectionWorkerLoopTask instance using reflection.
     * The worker task is an inner class of BlockNodeConnection.
     *
     * @return a new ConnectionWorkerLoopTask instance
     * @throws Exception if reflection fails
     */
    private Object createWorker() throws Exception {
        Class<?> workerClass = null;
        for (Class<?> innerClass : BlockNodeStreamingConnection.class.getDeclaredClasses()) {
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

    private void sendRequest(final StreamRequest request) {
        sendRequest(connection, request);
    }

    private void sendRequest(final BlockNodeStreamingConnection connection, final StreamRequest request) {
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
}
