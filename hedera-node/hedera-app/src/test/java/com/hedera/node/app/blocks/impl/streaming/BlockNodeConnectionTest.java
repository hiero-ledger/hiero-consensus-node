// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchRuntimeException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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

import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnection.ConnectionState;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.internal.network.BlockNodeConfig;
import com.hedera.pbj.grpc.client.helidon.PbjGrpcClientConfig;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.grpc.Pipeline;
import com.hedera.pbj.runtime.grpc.ServiceInterface.RequestOptions;
import io.helidon.webclient.api.WebClient;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.block.api.BlockStreamPublishServiceInterface.BlockStreamPublishServiceClient;
import org.hiero.block.api.PublishStreamRequest;
import org.hiero.block.api.PublishStreamRequest.EndStream;
import org.hiero.block.api.PublishStreamRequest.RequestOneOfType;
import org.hiero.block.api.PublishStreamResponse;
import org.hiero.block.api.PublishStreamResponse.EndOfStream;
import org.hiero.block.api.PublishStreamResponse.EndOfStream.Code;
import org.hiero.block.api.PublishStreamResponse.ResponseOneOfType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockNodeConnectionTest extends BlockNodeCommunicationTestBase {
    private static final long ONCE_PER_DAY_MILLIS = Duration.ofHours(24).toMillis();
    private BlockNodeConnection connection;
    private BlockNodeConfig nodeConfig;

    private BlockNodeConnectionManager connectionManager;
    private BlockBufferService bufferService;
    private BlockStreamPublishServiceClient grpcServiceClient;
    private BlockStreamMetrics metrics;
    private Pipeline<? super PublishStreamRequest> requestPipeline;
    private ScheduledExecutorService executorService;
    private BlockNodeStats.HighLatencyResult latencyResult;
    private BlockNodeClientFactory clientFactory;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void beforeEach() {
        final ConfigProvider configProvider = createConfigProvider(createDefaultConfigProvider());
        nodeConfig = newBlockNodeConfig(8080, 1);
        connectionManager = mock(BlockNodeConnectionManager.class);
        bufferService = mock(BlockBufferService.class);
        grpcServiceClient = mock(BlockStreamPublishServiceClient.class);
        metrics = mock(BlockStreamMetrics.class);
        requestPipeline = mock(Pipeline.class);
        executorService = mock(ScheduledExecutorService.class);
        latencyResult = mock(BlockNodeStats.HighLatencyResult.class);

        clientFactory = mock(BlockNodeClientFactory.class);
        lenient()
                .doReturn(grpcServiceClient)
                .when(clientFactory)
                .createClient(any(WebClient.class), any(PbjGrpcClientConfig.class), any(RequestOptions.class));
        connection = new BlockNodeConnection(
                configProvider, nodeConfig, connectionManager, bufferService, metrics, executorService, clientFactory);

        lenient().doReturn(requestPipeline).when(grpcServiceClient).publishBlockStream(connection);
    }

    @Test
    void testCreateRequestPipeline() {
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.UNINITIALIZED);

        connection.createRequestPipeline();

        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.PENDING);
        verify(grpcServiceClient).publishBlockStream(connection);
    }

    @Test
    void testCreateRequestPipeline_alreadyExists() {
        connection.createRequestPipeline();
        connection.createRequestPipeline();

        verify(grpcServiceClient).publishBlockStream(connection); // should only be called once
        verifyNoMoreInteractions(grpcServiceClient);
    }

    /**
     * Tests TimeoutException handling during pipeline creation.
     * Uses mocks to simulate a timeout without actually waiting, making the test fast.
     */
    @Test
    void testCreateRequestPipeline_timeoutException() throws Exception {
        // Create a mock Future that will throw TimeoutException when get() is called
        @SuppressWarnings("unchecked")
        final Future<Object> mockFuture = mock(Future.class);
        when(mockFuture.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException("Simulated timeout"));

        // Mock the executor to return our mock future
        final ExecutorService mockPipelineExecutor = mock(ExecutorService.class);
        doReturn(mockFuture).when(mockPipelineExecutor).submit(any(Runnable.class));

        // Use reflection to replace the pipelineExecutor with the mock
        final Field executorField = BlockNodeConnection.class.getDeclaredField("pipelineExecutor");
        executorField.setAccessible(true);
        executorField.set(connection, mockPipelineExecutor);

        // Attempt to create pipeline - should timeout and throw
        final RuntimeException exception = catchRuntimeException(() -> connection.createRequestPipeline());

        assertThat(exception).isNotNull();
        assertThat(exception.getMessage()).contains("Pipeline creation timed out");
        assertThat(exception.getCause()).isInstanceOf(TimeoutException.class);

        // Verify timeout was detected and recorded
        verify(mockFuture).get(anyLong(), any(TimeUnit.class));
        verify(mockFuture).cancel(true);
        verify(metrics).recordPipelineOperationTimeout();

        // Connection should still be UNINITIALIZED since pipeline creation failed
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.UNINITIALIZED);
    }

    /**
     * Tests InterruptedException handling during pipeline creation.
     * Uses mocks to simulate an interruption without actually waiting, making the test fast.
     */
    @Test
    void testCreateRequestPipeline_interruptedException() throws Exception {
        // Create a mock Future that will throw InterruptedException when get() is called
        @SuppressWarnings("unchecked")
        final Future<Object> mockFuture = mock(Future.class);
        when(mockFuture.get(anyLong(), any(TimeUnit.class)))
                .thenThrow(new InterruptedException("Simulated interruption"));

        // Mock the executor to return our mock future
        final ExecutorService mockPipelineExecutor = mock(ExecutorService.class);
        doReturn(mockFuture).when(mockPipelineExecutor).submit(any(Runnable.class));

        // Use reflection to replace the pipelineExecutor with the mock
        final Field executorField = BlockNodeConnection.class.getDeclaredField("pipelineExecutor");
        executorField.setAccessible(true);
        executorField.set(connection, mockPipelineExecutor);

        // Attempt to create pipeline - should handle interruption and throw
        final RuntimeException exception = catchRuntimeException(() -> connection.createRequestPipeline());

        assertThat(exception).isNotNull();
        assertThat(exception.getMessage()).contains("Interrupted while creating pipeline");
        assertThat(exception.getCause()).isInstanceOf(InterruptedException.class);

        // Verify interruption was handled
        verify(mockFuture).get(anyLong(), any(TimeUnit.class));

        // Connection should still be UNINITIALIZED since pipeline creation failed
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.UNINITIALIZED);
    }

    /**
     * Tests ExecutionException handling during pipeline creation.
     * Uses mocks to simulate an execution error without actually waiting, making the test fast.
     */
    @Test
    void testCreateRequestPipeline_executionException() throws Exception {
        // Create a mock Future that will throw ExecutionException when get() is called
        @SuppressWarnings("unchecked")
        final Future<Object> mockFuture = mock(Future.class);
        when(mockFuture.get(anyLong(), any(TimeUnit.class)))
                .thenThrow(new java.util.concurrent.ExecutionException(
                        "Simulated execution error", new RuntimeException("Underlying cause")));

        // Mock the executor to return our mock future
        final ExecutorService mockPipelineExecutor = mock(ExecutorService.class);
        doReturn(mockFuture).when(mockPipelineExecutor).submit(any(Runnable.class));

        // Use reflection to replace the pipelineExecutor with the mock
        final Field executorField = BlockNodeConnection.class.getDeclaredField("pipelineExecutor");
        executorField.setAccessible(true);
        executorField.set(connection, mockPipelineExecutor);

        // Attempt to create pipeline - should handle execution exception and throw
        final RuntimeException exception = catchRuntimeException(() -> connection.createRequestPipeline());

        assertThat(exception).isNotNull();
        assertThat(exception.getMessage()).contains("Error creating pipeline");
        assertThat(exception.getCause()).isInstanceOf(RuntimeException.class);
        assertThat(exception.getCause().getMessage()).isEqualTo("Underlying cause");

        // Verify execution exception was handled
        verify(mockFuture).get(anyLong(), any(TimeUnit.class));

        // Connection should still be UNINITIALIZED since pipeline creation failed
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.UNINITIALIZED);
    }

    @Test
    void testUpdatingConnectionState() {
        final ConnectionState preUpdateState = connection.getConnectionState();
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

        final ConnectionState postUpdateState = connection.getConnectionState();
        assertThat(postUpdateState).isEqualTo(ConnectionState.ACTIVE);
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
        final ConnectionState preState = connection.getConnectionState();
        assertThat(preState).isEqualTo(ConnectionState.ACTIVE);

        connection.handleStreamFailure();

        final ConnectionState postState = connection.getConnectionState();
        assertThat(postState).isEqualTo(ConnectionState.CLOSED);

        verify(requestPipeline).onComplete();
        verify(connectionManager).rescheduleConnection(connection, Duration.ofSeconds(30), null, true);
        verify(connectionManager).jumpToBlock(-1L);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(connectionManager);
    }

    @Test
    void testOnNext_acknowledgement_notStreaming() {
        final PublishStreamResponse response = createBlockAckResponse(10L);
        when(connectionManager.currentStreamingBlockNumber())
                .thenReturn(-1L); // we aren't streaming anything to the block node
        when(connectionManager.recordBlockAckAndCheckLatency(eq(connection.getNodeConfig()), eq(10L), any()))
                .thenReturn(latencyResult);
        when(latencyResult.shouldSwitch()).thenReturn(false);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        connection.onNext(response);

        verify(connectionManager).currentStreamingBlockNumber();
        verify(bufferService).getLastBlockNumberProduced();
        verify(connectionManager).updateLastVerifiedBlock(connection.getNodeConfig(), 10L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.ACKNOWLEDGEMENT);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testOnNext_acknowledgement_olderThanCurrentStreamingAndProducing() {
        final PublishStreamResponse response = createBlockAckResponse(8L);

        when(connectionManager.currentStreamingBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(10L);
        when(connectionManager.recordBlockAckAndCheckLatency(eq(connection.getNodeConfig()), eq(8L), any()))
                .thenReturn(latencyResult);
        when(latencyResult.shouldSwitch()).thenReturn(false);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        connection.onNext(response);

        verify(connectionManager).currentStreamingBlockNumber();
        verify(bufferService).getLastBlockNumberProduced();

        verify(connectionManager).updateLastVerifiedBlock(connection.getNodeConfig(), 8L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.ACKNOWLEDGEMENT);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testOnNext_acknowledgement_newerThanCurrentProducing() {
        // I don't think this scenario is possible... we should never stream a block that is newer than the block
        // currently being produced.
        final PublishStreamResponse response = createBlockAckResponse(11L);

        when(connectionManager.currentStreamingBlockNumber()).thenReturn(11L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(10L);
        when(connectionManager.recordBlockAckAndCheckLatency(eq(connection.getNodeConfig()), eq(11L), any()))
                .thenReturn(latencyResult);
        when(latencyResult.shouldSwitch()).thenReturn(false);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        connection.onNext(response);

        verify(connectionManager).currentStreamingBlockNumber();
        verify(bufferService).getLastBlockNumberProduced();
        verify(connectionManager).updateLastVerifiedBlock(connection.getNodeConfig(), 11L);
        verify(connectionManager).jumpToBlock(12L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.ACKNOWLEDGEMENT);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testOnNext_acknowledgement_newerThanCurrentStreaming() {
        final PublishStreamResponse response = createBlockAckResponse(11L);

        when(connectionManager.currentStreamingBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(12L);
        when(connectionManager.recordBlockAckAndCheckLatency(eq(connection.getNodeConfig()), eq(11L), any()))
                .thenReturn(latencyResult);
        when(latencyResult.shouldSwitch()).thenReturn(false);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        connection.onNext(response);

        verify(connectionManager).currentStreamingBlockNumber();
        verify(bufferService).getLastBlockNumberProduced();
        verify(connectionManager).updateLastVerifiedBlock(connection.getNodeConfig(), 11L);
        verify(connectionManager).jumpToBlock(12L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.ACKNOWLEDGEMENT);
        verifyNoMoreInteractions(metrics);
    }

    // Tests acknowledgement equal to current streaming/producing blocks (should not jump)
    @Test
    void testOnNext_acknowledgement_equalToCurrentStreamingAndProducing() {
        final PublishStreamResponse response = createBlockAckResponse(10L);

        when(connectionManager.currentStreamingBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(10L);
        when(connectionManager.recordBlockAckAndCheckLatency(eq(connection.getNodeConfig()), eq(10L), any()))
                .thenReturn(latencyResult);
        when(latencyResult.shouldSwitch()).thenReturn(false);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        connection.onNext(response);

        verify(connectionManager).currentStreamingBlockNumber();
        verify(bufferService).getLastBlockNumberProduced();
        verify(connectionManager).updateLastVerifiedBlock(connection.getNodeConfig(), 10L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.ACKNOWLEDGEMENT);
        // Should not jump to block since acknowledgement is not newer
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(metrics);
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
        verify(connectionManager).jumpToBlock(-1L);
        verify(connectionManager).rescheduleConnection(connection, Duration.ofSeconds(30), null, true);
        verify(connectionManager).updateLastVerifiedBlock(connection.getNodeConfig(), 10L);
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
        verify(connectionManager).currentStreamingBlockNumber();
        verify(connectionManager).updateLastVerifiedBlock(connection.getNodeConfig(), 10L);
        verify(requestPipeline).onComplete();
        verify(connectionManager).jumpToBlock(-1L);
        verify(connectionManager).rescheduleConnection(connection, null, 11L, false);
        verify(connectionManager).updateLastVerifiedBlock(connection.getNodeConfig(), 10L);
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
        verify(connectionManager).jumpToBlock(-1L);
        verify(connectionManager).updateLastVerifiedBlock(connection.getNodeConfig(), 10L);
        verify(connectionManager).rescheduleConnection(connection, Duration.ofSeconds(30), null, true);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
    }

    @Test
    void testOnNext_endOfStream_blockNodeBehind_blockExists() {
        openConnectionAndResetMocks();
        final PublishStreamResponse response = createEndOfStreamResponse(Code.BEHIND, 10L);
        when(bufferService.getBlockState(11L)).thenReturn(new BlockState(11L));
        connection.updateConnectionState(ConnectionState.ACTIVE);

        connection.onNext(response);

        verify(metrics).recordLatestBlockEndOfStream(10L);
        verify(metrics).recordResponseEndOfStreamReceived(Code.BEHIND);
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verify(requestPipeline).onComplete();
        verify(connectionManager).currentStreamingBlockNumber();
        verify(connectionManager).updateLastVerifiedBlock(connection.getNodeConfig(), 10L);
        verify(connectionManager).jumpToBlock(-1L);
        verify(connectionManager).rescheduleConnection(connection, null, 11L, false);
        verify(connectionManager).updateLastVerifiedBlock(connection.getNodeConfig(), 10L);
        verify(bufferService).getBlockState(11L);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
    }

    @Test
    void testOnNext_endOfStream_blockNodeBehind_blockDoesNotExist() {
        openConnectionAndResetMocks();
        final PublishStreamResponse response = createEndOfStreamResponse(Code.BEHIND, 10L);
        when(bufferService.getBlockState(11L)).thenReturn(null);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        connection.onNext(response);

        verify(metrics).recordLatestBlockEndOfStream(10L);
        verify(metrics).recordResponseEndOfStreamReceived(Code.BEHIND);
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verify(metrics).recordRequestEndStreamSent(EndStream.Code.TOO_FAR_BEHIND);
        verify(metrics).recordRequestLatency(anyLong());
        verify(bufferService, times(1)).getEarliestAvailableBlockNumber();
        verify(bufferService, times(1)).getHighestAckedBlockNumber();
        verify(bufferService).getBlockState(11L);
        verify(requestPipeline).onNext(createRequest(EndStream.Code.TOO_FAR_BEHIND));
        verify(requestPipeline).onComplete();
        verify(connectionManager).rescheduleConnection(connection, Duration.ofSeconds(30), null, true);
        verify(connectionManager).jumpToBlock(-1L);
        verify(connectionManager).updateLastVerifiedBlock(connection.getNodeConfig(), 10L);
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
        verify(connectionManager).jumpToBlock(-1L);
        verify(connectionManager).rescheduleConnection(connection, Duration.ofSeconds(30), null, true);
        verify(connectionManager).updateLastVerifiedBlock(connection.getNodeConfig(), 10L);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
    }

    @Test
    void testOnNext_skipBlock_sameAsStreaming() {
        final PublishStreamResponse response = createSkipBlock(25L);
        when(connectionManager.currentStreamingBlockNumber()).thenReturn(25L);
        connection.updateConnectionState(ConnectionState.ACTIVE);
        connection.onNext(response);

        verify(metrics).recordLatestBlockSkipBlock(25L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.SKIP_BLOCK);
        verify(connectionManager).jumpToBlock(26L); // jump to the response block number + 1
        verify(connectionManager).currentStreamingBlockNumber();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testOnNext_skipBlock_notSameAsStreaming() {
        final PublishStreamResponse response = createSkipBlock(25L);
        when(connectionManager.currentStreamingBlockNumber()).thenReturn(26L);
        connection.updateConnectionState(ConnectionState.ACTIVE);

        connection.onNext(response);

        verify(metrics).recordLatestBlockSkipBlock(25L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.SKIP_BLOCK);
        verify(connectionManager).currentStreamingBlockNumber();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testOnNext_resendBlock_blockExists() {
        final PublishStreamResponse response = createResendBlock(10L);
        when(bufferService.getBlockState(10L)).thenReturn(new BlockState(10L));

        connection.onNext(response);

        verify(metrics).recordLatestBlockResendBlock(10L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.RESEND_BLOCK);
        verify(connectionManager).jumpToBlock(10L);
        verify(bufferService).getBlockState(10L);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testOnNext_resendBlock_blockDoesNotExist() {
        openConnectionAndResetMocks();

        final PublishStreamResponse response = createResendBlock(10L);
        when(bufferService.getBlockState(10L)).thenReturn(null);
        connection.updateConnectionState(ConnectionState.ACTIVE);

        connection.onNext(response);

        verify(metrics).recordLatestBlockResendBlock(10L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.RESEND_BLOCK);
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verify(requestPipeline).onComplete();
        verify(connectionManager).jumpToBlock(-1L);
        verify(connectionManager).rescheduleConnection(connection, Duration.ofSeconds(30), null, true);
        verify(bufferService).getBlockState(10L);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(connectionManager);
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
        connection.sendRequest(request);

        verify(requestPipeline).onNext(request);
        verify(metrics).recordRequestSent(RequestOneOfType.BLOCK_ITEMS);
        verify(metrics).recordBlockItemsSent(1);
        verify(metrics).recordRequestLatency(anyLong());
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testSendRequest_notActive() {
        final PublishStreamRequest request = createRequest(newBlockHeaderItem());

        connection.createRequestPipeline();
        connection.updateConnectionState(ConnectionState.PENDING);
        connection.sendRequest(request);

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
        connection.updateConnectionState(ConnectionState.PENDING);
        connection.sendRequest(request);

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

        final RuntimeException e = catchRuntimeException(() -> connection.sendRequest(request));
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

        final BlockNodeConnection spiedConnection = spy(connection);
        doReturn(ConnectionState.ACTIVE, ConnectionState.CLOSING)
                .when(spiedConnection)
                .getConnectionState();
        final PublishStreamRequest request = createRequest(newBlockHeaderItem());

        spiedConnection.sendRequest(request);

        verify(requestPipeline).onNext(any());
        verify(spiedConnection, times(2)).getConnectionState();

        verifyNoInteractions(metrics);
    }

    // Tests sendRequest when ACTIVE but requestPipeline is null (should do nothing)
    @Test
    void testSendRequest_activeButPipelineNull() {
        final PublishStreamRequest request = createRequest(newBlockHeaderItem());

        // Set to ACTIVE state but don't create the pipeline
        connection.updateConnectionState(ConnectionState.ACTIVE);
        // requestPipeline remains null since we didn't call createRequestPipeline()

        connection.sendRequest(request);

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

        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CLOSED);

        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verify(connectionManager).jumpToBlock(-1L);
        verify(requestPipeline).onComplete();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(connectionManager);
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

        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CLOSED);

        verify(connectionManager).jumpToBlock(-1L);
        verify(requestPipeline).onComplete();
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    // Tests close operation without calling onComplete on pipeline
    @Test
    void testClose_withoutOnComplete() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        connection.close(false);

        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CLOSED);

        verify(connectionManager).jumpToBlock(-1L);
        // Should not call onComplete when callOnComplete is false
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(requestPipeline);
    }

    // Tests close operation when connection is not in ACTIVE state
    @Test
    void testClose_notActiveState() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.PENDING);

        connection.close(true);

        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CLOSED);

        verify(connectionManager).jumpToBlock(-1L);
        // Should call onComplete when callOnComplete=true and state transitions to CLOSING
        verify(requestPipeline).onComplete();
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoInteractions(bufferService);
    }

    // Tests exception handling during close operation (should catch and log RuntimeException)
    @Test
    void testClose_exceptionDuringClose() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Mock jumpToBlock to throw a RuntimeException to trigger the catch block
        doThrow(new RuntimeException("Simulated close error"))
                .when(connectionManager)
                .jumpToBlock(-1L);

        // This should not throw an exception - it should be caught and logged
        connection.close(true);

        // Verify the exception handling path was taken
        verify(connectionManager).jumpToBlock(-1L);
        verify(requestPipeline).onComplete(); // closePipeline should still be called before the exception

        // Connection state should still be CLOSED even after the exception
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CLOSED);
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
        verify(connectionManager).jumpToBlock(-1L); // Should still continue after pipeline exception

        // Connection state should still be CLOSED even after the pipeline exception
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CLOSED);
    }

    // Tests close operation when requestPipeline is null (should skip pipeline closure)
    @Test
    void testClose_pipelineNull() {
        // Don't call openConnectionAndResetMocks() to avoid creating a pipeline
        connection.updateConnectionState(ConnectionState.ACTIVE);
        // requestPipeline remains null since we didn't call createRequestPipeline()

        connection.close(true);

        // Should complete successfully without interacting with pipeline
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CLOSED);
        verify(connectionManager).jumpToBlock(-1L);

        // Should not interact with pipeline since it's null
        verifyNoInteractions(requestPipeline);
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(connectionManager);
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
    void testOnError_activeConnection() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        connection.onError(new RuntimeException("oh bother"));

        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CLOSED);

        verify(metrics).recordConnectionOnError();
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verify(connectionManager).jumpToBlock(-1L);
        verify(requestPipeline).onComplete();
        verify(connectionManager).rescheduleConnection(connection, Duration.ofSeconds(30), null, true);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(bufferService);
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
        final var field = BlockNodeConnection.class.getDeclaredField("streamShutdownInProgress");
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

        verify(connectionManager).jumpToBlock(-1L);
        verify(requestPipeline).onComplete();
        verify(connectionManager).rescheduleConnection(connection, Duration.ofSeconds(30), null, true);
        verify(metrics).recordConnectionOnComplete();
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(bufferService);
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
        verify(connectionManager).updateLastVerifiedBlock(nodeConfig, 10L);
        verify(connectionManager).rescheduleConnection(connection, Duration.ofMinutes(5), null, true);
        verify(connectionManager).jumpToBlock(-1L);
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
        verify(connectionManager).updateLastVerifiedBlock(connection.getNodeConfig(), Long.MAX_VALUE);
        verify(requestPipeline).onComplete();
        verify(connectionManager).jumpToBlock(-1L);
        verify(connectionManager).rescheduleConnection(connection, null, 0L, false);

        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);

        // Verify connection is closed after handling EndOfStream
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CLOSED);
    }

    // Tests EndOfStream BEHIND code with Long.MAX_VALUE edge case (should restart at block 0)
    @Test
    void testOnNext_endOfStream_blockNodeBehind_maxValueBlockNumber() {
        openConnectionAndResetMocks();
        final PublishStreamResponse response = createEndOfStreamResponse(Code.BEHIND, Long.MAX_VALUE);
        when(bufferService.getBlockState(0L)).thenReturn(new BlockState(0L));
        connection.updateConnectionState(ConnectionState.ACTIVE);

        connection.onNext(response);

        verify(metrics).recordLatestBlockEndOfStream(Long.MAX_VALUE);
        verify(metrics).recordResponseEndOfStreamReceived(Code.BEHIND);
        verify(requestPipeline).onComplete();
        verify(connectionManager).updateLastVerifiedBlock(connection.getNodeConfig(), Long.MAX_VALUE);
        verify(connectionManager).jumpToBlock(-1L);
        verify(connectionManager)
                .rescheduleConnection(connection, null, 0L, false); // Should restart at 0 for MAX_VALUE
        verify(bufferService).getBlockState(0L);
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
    }

    // Tests stream failure handling without calling onComplete on the pipeline
    @Test
    void testHandleStreamFailureWithoutOnComplete() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        connection.handleStreamFailureWithoutOnComplete();

        final ConnectionState postState = connection.getConnectionState();
        assertThat(postState).isEqualTo(ConnectionState.CLOSED);

        // Should not call onComplete on the pipeline
        verify(connectionManager).rescheduleConnection(connection, Duration.ofSeconds(30), null, true);
        verify(connectionManager).jumpToBlock(-1L);
        verifyNoInteractions(requestPipeline);
        verifyNoMoreInteractions(connectionManager);
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

    // Tests error handling in PENDING state (should not call onComplete on pipeline)
    @Test
    void testOnError_connectionPending() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.PENDING);

        connection.onError(new RuntimeException("test error"));

        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CLOSED);

        verify(metrics).recordConnectionOnError();
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verify(connectionManager).jumpToBlock(-1L);
        verify(connectionManager).rescheduleConnection(connection, Duration.ofSeconds(30), null, true);
        // Should call onComplete when callOnComplete=true (from handleStreamFailure)
        verify(requestPipeline).onComplete();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(requestPipeline);
    }

    // Tests error handling in UNINITIALIZED state (should do nothing)
    @Test
    void testOnError_connectionUninitialized() {
        // Connection starts in UNINITIALIZED state by default
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.UNINITIALIZED);

        connection.onError(new RuntimeException("test error"));

        // Should transition to CLOSED state after handling the error
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CLOSED);

        verify(metrics).recordConnectionOnError();
        verify(connectionManager).jumpToBlock(-1L);
        verify(connectionManager).rescheduleConnection(connection, Duration.ofSeconds(30), null, true);
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);
        verifyNoMoreInteractions(metrics);
        verifyNoInteractions(requestPipeline);
        verifyNoMoreInteractions(connectionManager);
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
        verify(connectionManager).jumpToBlock(-1L);

        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CLOSED);
    }

    // Tests client-side end stream handling (should have no side effects)
    @Test
    void testClientEndStreamReceived() {
        // This method calls the superclass implementation - test that it doesn't throw exceptions
        // and doesn't change connection state or interact with dependencies
        final ConnectionState initialState = connection.getConnectionState();

        connection.clientEndStreamReceived();

        // Verify state unchanged and no side effects
        assertThat(connection.getConnectionState()).isEqualTo(initialState);
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
    void testUpdateConnectionState_fromActiveToOther() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Reset mocks to focus on the state change
        reset(executorService);

        // Change from ACTIVE to PENDING should cancel stream reset
        connection.updateConnectionState(ConnectionState.PENDING);

        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.PENDING);
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

        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.ACTIVE);
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

        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.ACTIVE);
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
        connection.updateConnectionState(ConnectionState.PENDING);

        // Verify the task was cancelled
        verify(mockTask).cancel(false);

        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.PENDING);
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
        verify(connectionManager).connectionResetsTheStream(connection);
        verify(requestPipeline).onNext(any(PublishStreamRequest.class));
        verify(requestPipeline).onComplete();
        verify(connectionManager).jumpToBlock(-1L);

        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CLOSED);
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

        // Change state to PENDING before executing reset
        connection.updateConnectionState(ConnectionState.PENDING);
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
        connection.sendRequest(request);

        // Verify the request was sent successfully
        verify(requestPipeline).onNext(request);
        verify(metrics).recordRequestSent(RequestOneOfType.BLOCK_ITEMS);
        verify(metrics).recordBlockItemsSent(1);
        verify(metrics).recordRequestLatency(anyLong());

        // Verify no timeout was recorded
        verify(metrics, times(0)).recordPipelineOperationTimeout();

        // Connection should still be ACTIVE
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.ACTIVE);
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
        connection.sendRequest(request);

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
        verify(connectionManager).jumpToBlock(-1L);
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);

        // Connection should be CLOSED
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CLOSED);
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
        verify(connectionManager).jumpToBlock(-1L);
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);

        // Connection should be CLOSED
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CLOSED);
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
        final RuntimeException exception = catchRuntimeException(() -> connection.sendRequest(request));

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
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CLOSED);

        // Give the executor a moment to shutdown
        Thread.sleep(100);

        // Try to send a request after close - should be ignored since connection is CLOSED
        final PublishStreamRequest request = createRequest(newBlockHeaderItem());
        connection.sendRequest(request);

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

        // Mock the executor to return our mock future
        final ExecutorService mockPipelineExecutor = mock(ExecutorService.class);
        doReturn(mockFuture).when(mockPipelineExecutor).submit(any(Runnable.class));

        // Use reflection to replace the pipelineExecutor with the mock
        final Field executorField = BlockNodeConnection.class.getDeclaredField("pipelineExecutor");
        executorField.setAccessible(true);
        executorField.set(connection, mockPipelineExecutor);

        final PublishStreamRequest request = createRequest(newBlockHeaderItem());

        // Send request - should trigger timeout handling immediately
        connection.sendRequest(request);

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
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CLOSED);
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

        // Mock the executor to return our mock future
        final ExecutorService mockPipelineExecutor = mock(ExecutorService.class);
        doReturn(mockFuture).when(mockPipelineExecutor).submit(any(Runnable.class));

        // Use reflection to replace the pipelineExecutor with our mock
        final Field executorField = BlockNodeConnection.class.getDeclaredField("pipelineExecutor");
        executorField.setAccessible(true);
        executorField.set(connection, mockPipelineExecutor);

        // Close connection - should trigger timeout during onComplete
        connection.close(true);

        // Verify timeout was detected during onComplete
        verify(mockFuture).get(anyLong(), any(TimeUnit.class));
        verify(mockFuture).cancel(true);
        verify(metrics).recordPipelineOperationTimeout();
        verify(metrics).recordConnectionClosed();
        verify(connectionManager).jumpToBlock(-1L);

        // Connection should still be CLOSED despite timeout
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CLOSED);
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

        // Mock the executor to return our mock future
        final ExecutorService mockPipelineExecutor = mock(ExecutorService.class);
        doReturn(mockFuture).when(mockPipelineExecutor).submit(any(Runnable.class));

        // Use reflection to replace the pipelineExecutor with our mock
        final Field executorField = BlockNodeConnection.class.getDeclaredField("pipelineExecutor");
        executorField.setAccessible(true);
        executorField.set(connection, mockPipelineExecutor);

        // Close connection - should handle interruption during onComplete
        connection.close(true);

        // Verify interruption was handled gracefully
        verify(mockFuture).get(anyLong(), any(TimeUnit.class));
        verify(metrics).recordConnectionClosed();
        verify(connectionManager).jumpToBlock(-1L);

        // Connection should still be CLOSED despite interruption
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CLOSED);

        // Verify thread interrupt status was restored
        // (Note: Can't directly test this in unit test, but the code does Thread.currentThread().interrupt())
    }

    /**
     * Tests InterruptedException handling during pipelineExecutor.awaitTermination() in close().
     * This covers the exception handling when shutting down the executor is interrupted.
     */
    @Test
    void testClose_executorShutdownInterruptedException() throws Exception {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Mock the executor to throw InterruptedException during awaitTermination
        final ExecutorService mockPipelineExecutor = mock(ExecutorService.class);
        when(mockPipelineExecutor.awaitTermination(anyLong(), any(TimeUnit.class)))
                .thenThrow(new InterruptedException("Simulated shutdown interruption"));

        // Use reflection to replace the pipelineExecutor with our mock
        final Field executorField = BlockNodeConnection.class.getDeclaredField("pipelineExecutor");
        executorField.setAccessible(true);
        executorField.set(connection, mockPipelineExecutor);

        // Close connection - should handle interruption during executor shutdown
        connection.close(true);

        // Verify executor shutdown was attempted
        verify(mockPipelineExecutor).shutdown();
        verify(mockPipelineExecutor).awaitTermination(5, TimeUnit.SECONDS);

        // Verify shutdownNow was called after interruption
        verify(mockPipelineExecutor).shutdownNow();

        verify(metrics).recordConnectionClosed();
        verify(connectionManager).jumpToBlock(-1L);

        // Connection should still be CLOSED despite interruption
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CLOSED);
    }

    /**
     * Tests InterruptedException handling during pipeline operation.
     */
    @Test
    void testSendRequest_interruptedException() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Create a latch to coordinate the test
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<RuntimeException> exceptionRef = new AtomicReference<>();

        // Make the pipeline block until interrupted
        doAnswer(invocation -> {
                    try {
                        latch.await();
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
                connection.sendRequest(request);
            } catch (RuntimeException e) {
                exceptionRef.set(e);
            }
        });

        // Give the thread time to start and block
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Interrupt the thread
        testThread.interrupt();

        // Wait for thread to complete
        try {
            testThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Verify exception was thrown
        assertThat(exceptionRef.get()).isNotNull();
        assertThat(exceptionRef.get().getMessage()).contains("Interrupted while waiting for pipeline.onNext()");
        assertThat(exceptionRef.get().getCause()).isInstanceOf(InterruptedException.class);
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

        // Should throw RuntimeException wrapping ExecutionException
        final RuntimeException exception = catchRuntimeException(() -> connection.sendRequest(request));

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
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CLOSED);

        // Reset mocks to verify second close behavior
        reset(requestPipeline, metrics, connectionManager);

        // Close again - should be idempotent (no-op since already closed)
        connection.close(true);

        // Verify no additional operations were performed
        verifyNoInteractions(requestPipeline);
        verifyNoInteractions(metrics);
        verifyNoInteractions(connectionManager);

        // Connection should still be CLOSED
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CLOSED);
    }

    // Utilities

    private void openConnectionAndResetMocks() {
        connection.createRequestPipeline();
        // reset the mocks interactions to remove tracked interactions as a result of starting the connection
        resetMocks();
    }

    private void resetMocks() {
        reset(connectionManager, requestPipeline, bufferService, metrics);
    }
}
