// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchRuntimeException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
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

import com.hedera.hapi.block.internal.PublishStreamRequestBytes;
import com.hedera.hapi.block.internal.PublishStreamRequestBytes.RequestOneOfType;
import com.hedera.node.app.blocks.impl.streaming.BlockNodePublisherConnection.PublisherBlockItemsRequest;
import com.hedera.node.app.blocks.impl.streaming.BlockNodePublisherConnection.PublisherRequest;
import com.hedera.node.app.blocks.impl.streaming.BlockNodePublisherConnection.SendRequestResult;
import com.hedera.node.app.blocks.impl.streaming.BlockNodePublisherConnection.SendRequestStatus;
import com.hedera.node.app.blocks.impl.streaming.BlockNodePublisherConnectionTest.DummyPublisherConnection.PostSuccessfulRequestSendArgs;
import com.hedera.node.app.blocks.impl.streaming.ConnectionId.ConnectionType;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.types.BlockStreamGrpcCompressionType;
import com.hedera.pbj.runtime.grpc.GrpcCall;
import com.hedera.pbj.runtime.grpc.GrpcException;
import com.hedera.pbj.runtime.grpc.GrpcStatus;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.block.api.PublishStreamRequest.EndStream;
import org.hiero.block.api.PublishStreamResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class BlockNodePublisherConnectionTest extends BlockNodeCommunicationTestBase {

    private static final VarHandle connectionStateHandle;

    private static final MethodHandle sendRequestHandle;

    static {
        try {
            final Lookup lookup = MethodHandles.lookup();
            final Class<?> publisherCls = BlockNodePublisherConnection.class;

            connectionStateHandle = MethodHandles.privateLookupIn(AbstractBlockNodeConnection.class, lookup)
                    .findVarHandle(AbstractBlockNodeConnection.class, "stateRef", AtomicReference.class);

            final Method sendRequest = publisherCls.getDeclaredMethod("sendRequest", PublisherRequest.class);
            sendRequest.setAccessible(true);
            sendRequestHandle = lookup.unreflect(sendRequest);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ConfigProvider configProvider;
    private BlockNode blockNode;
    private BlockNodeStats blockNodeStats;
    private BlockNodeClientFactory clientFactory;
    private BlockStreamPublishBytesClient publishBytesClient;
    private GrpcCall<PublishStreamRequestBytes, PublishStreamResponse> requestCall;
    private BlockStreamMetrics metrics;
    private ExecutorService blockingIoExecutor;
    private BlockBufferService bufferService;
    private BlockNodeConnectionManager connectionManager;

    private ExecutorService realExecSvc;
    private DummyPublisherConnection connection;

    @BeforeEach
    void beforeEach() throws Exception {
        configProvider = createConfigProvider(createDefaultConfigProvider());
        final BlockNodeConfiguration config = newBlockNodeConfig(8080, 1);
        blockNodeStats = mock(BlockNodeStats.class);
        blockNode = new BlockNode(configProvider, config, new AtomicInteger(), new AtomicInteger(), blockNodeStats);
        connectionManager = mock(BlockNodeConnectionManager.class);
        clientFactory = mock(BlockNodeClientFactory.class);
        publishBytesClient = mock(BlockStreamPublishBytesClient.class);
        requestCall = mock(GrpcCall.class);
        metrics = mock(BlockStreamMetrics.class);
        blockingIoExecutor = mock(ExecutorService.class);
        bufferService = mock(BlockBufferService.class);
        connectionManager = mock(BlockNodeConnectionManager.class);

        realExecSvc = Executors.newCachedThreadPool();
        lenient()
                .doAnswer(inv -> {
                    final Runnable runnable = inv.getArgument(0);
                    return realExecSvc.submit(runnable);
                })
                .when(blockingIoExecutor)
                .submit(any(Runnable.class));

        lenient()
                .doAnswer(_ -> {
                    realExecSvc.shutdown();
                    return null;
                })
                .when(blockingIoExecutor)
                .shutdown();

        lenient()
                .doAnswer(inv -> {
                    final long timeout = inv.getArgument(0);
                    final TimeUnit unit = inv.getArgument(1);
                    return realExecSvc.awaitTermination(timeout, unit);
                })
                .when(blockingIoExecutor)
                .awaitTermination(anyLong(), any(TimeUnit.class));

        lenient()
                .doReturn(publishBytesClient)
                .when(clientFactory)
                .createStreamingClient(
                        any(BlockNodeConfiguration.class),
                        any(Duration.class),
                        anyString(),
                        any(BlockStreamGrpcCompressionType.class));

        connection = newConnection(ConnectionType.BLOCK_STREAMING);
    }

    @AfterEach
    void afterEach() throws Exception {
        if (realExecSvc != null) {
            realExecSvc.shutdownNow();
        }

        connection.updateConnectionState(ConnectionState.CLOSED);
    }

    @Test
    void testInitialize() {
        assertThat(connection.currentState()).isEqualTo(ConnectionState.UNINITIALIZED);
        assertThat(connection.isRequestClientActive()).isFalse();

        connection.initialize();

        assertThat(connection.isRequestClientActive()).isTrue();
        assertThat(connection.currentState()).isEqualTo(ConnectionState.READY);
        assertThat(connection.connectionId().toString()).matches("N\\d+-STR\\d+-JVM[0-9a-z]{8}");

        verify(publishBytesClient).publishBlockStream(connection);
        verify(clientFactory)
                .createStreamingClient(
                        any(BlockNodeConfiguration.class),
                        any(Duration.class),
                        anyString(),
                        any(BlockStreamGrpcCompressionType.class));
    }

    @Test
    void testInitialize_alreadyExists() {
        connection.initialize();
        connection.initialize();

        verify(publishBytesClient).publishBlockStream(connection); // should only be called once
        verifyNoMoreInteractions(publishBytesClient);
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

        // Set up the blockingIoExecutor to return mock future
        doReturn(mockFuture).when(blockingIoExecutor).submit(any(Runnable.class));

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

        // Set up the blockingIoExecutor to return mock future
        doReturn(mockFuture).when(blockingIoExecutor).submit(any(Runnable.class));

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

        // Set up the blockingIoExecutor to return mock future
        doReturn(mockFuture).when(blockingIoExecutor).submit(any(Runnable.class));

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
        connection.updateConnectionState(ConnectionState.READY);

        final ConnectionState postUpdateState = connection.currentState();
        assertThat(postUpdateState).isEqualTo(ConnectionState.READY);
    }

    @Test
    void testUpdatingConnectionState_downgrade() {
        final ConnectionState preUpdateState = connection.currentState();
        // this should be uninitialized because we haven't called connect yet
        assertThat(preUpdateState).isEqualTo(ConnectionState.UNINITIALIZED);
        connection.updateConnectionState(ConnectionState.READY);

        // the connection is ACTIVE so try to "downgrade" the state back to READY... should fail
        assertThatThrownBy(() -> connection.updateConnectionState(ConnectionState.UNINITIALIZED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Attempted to downgrade state from READY to UNINITIALIZED");

        assertThat(connection.currentState()).isEqualTo(ConnectionState.READY);
    }

    @Test
    void testClose() {
        activateConnection();

        assertThat(connection.isPostCloseCalled).isFalse();

        connection.close(CloseReason.CONFIG_UPDATE, true, EndStream.Code.RESET);

        assertThat(connection.closeReason()).isEqualTo(CloseReason.CONFIG_UPDATE);
        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
        assertThat(connection.isPostCloseCalled).isTrue();
        assertThat(connection.isOnTerminalStateTransitionCalled).isTrue();

        // verifications for sending EndStream.RESET
        verify(requestCall).sendRequest(any(), anyBoolean());
        verify(metrics).recordRequestLatency(anyLong());
        verify(metrics).recordRequestEndStreamSent(EndStream.Code.RESET);
        // remaining verifications
        verify(metrics).recordConnectionClosed(CloseReason.CONFIG_UPDATE);
        verify(requestCall).completeRequests();
        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getHighestAckedBlockNumber();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
        verifyNoMoreInteractions(bufferService);
    }

    // Tests close operation without calling onComplete on pipeline
    @Test
    void testClose_withoutOnComplete() {
        activateConnection();

        connection.close(CloseReason.UNKNOWN, false, EndStream.Code.RESET);

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
        assertThat(connection.closeReason()).isEqualTo(CloseReason.UNKNOWN);

        // verifications for sending EndStream.RESET
        verify(requestCall).sendRequest(any(), anyBoolean());
        verify(metrics).recordRequestLatency(anyLong());
        verify(metrics).recordRequestEndStreamSent(EndStream.Code.RESET);
        // remaining verifications
        // Should not call onComplete when callOnComplete is false
        verify(metrics).recordConnectionClosed(CloseReason.UNKNOWN);
        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getHighestAckedBlockNumber();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(requestCall);
    }

    // Tests close operation when connection is not in ACTIVE state
    @Test
    void testClose_notActiveState() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.READY);

        connection.close(CloseReason.UNKNOWN, true, EndStream.Code.RESET);

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
        assertThat(connection.closeReason()).isEqualTo(CloseReason.UNKNOWN);

        // Should call onComplete when callOnComplete=true and state transitions to CLOSING
        verify(requestCall).completeRequests();
        verify(metrics).recordConnectionClosed(CloseReason.UNKNOWN);
        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getHighestAckedBlockNumber();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
        verifyNoMoreInteractions(bufferService);
    }

    // Tests exception handling during close operation (should catch and log RuntimeException)
    @Test
    void testClose_exceptionDuringClose() {
        activateConnection();

        // Mock Pipeline#onComplete to throw a RuntimeException to trigger the catch block
        doThrow(new RuntimeException("Simulated close error")).when(requestCall).completeRequests();

        // This should not throw an exception - it should be caught and logged
        connection.close(CloseReason.CONNECTION_ERROR, true, EndStream.Code.RESET);

        // Verify the exception handling path was taken
        verify(requestCall).completeRequests(); // closePipeline should still be called before the exception

        // Connection state should still be CLOSED even after the exception
        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
        assertThat(connection.closeReason()).isEqualTo(CloseReason.CONNECTION_ERROR);
    }

    // Tests exception handling during pipeline completion (should catch and log Exception)
    @Test
    void testClose_exceptionDuringPipelineCompletion() {
        activateConnection();

        // Mock requestCall.completeRequests() to throw an Exception to trigger the catch block in closePipeline
        doThrow(new RuntimeException("Simulated pipeline completion error"))
                .when(requestCall)
                .completeRequests();

        // This should not throw an exception - it should be caught and logged
        connection.close(CloseReason.CONNECTION_ERROR, true, EndStream.Code.RESET);

        // Verify the exception handling path was taken
        verify(requestCall).completeRequests(); // Should be called and throw exception

        // Connection state should still be CLOSED even after the pipeline exception
        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
        assertThat(connection.closeReason()).isEqualTo(CloseReason.CONNECTION_ERROR);
    }

    // Tests close operation when requestCall is null (should skip pipeline closure)
    @Test
    void testClose_pipelineNull() {
        // Don't call openConnectionAndResetMocks() to avoid creating a pipeline
        connection.updateConnectionState(ConnectionState.READY);
        // requestCall remains null since we didn't call initialize()

        connection.close(CloseReason.UNKNOWN, true, EndStream.Code.RESET);

        // Should complete successfully without interacting with pipeline
        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
        assertThat(connection.closeReason()).isEqualTo(CloseReason.UNKNOWN);

        // Should not interact with pipeline since it's null
        verifyNoInteractions(requestCall);
        verify(metrics).recordConnectionClosed(CloseReason.UNKNOWN);
        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getHighestAckedBlockNumber();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testClose_alreadyClosed() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.CLOSED);

        connection.close(CloseReason.UNKNOWN, true, EndStream.Code.RESET);
        assertThat(connection.closeReason()).isNull(); // the UNKNOWN close reason should not be propagated

        verifyNoInteractions(connectionManager);
        verifyNoInteractions(requestCall);
        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testClose_alreadyClosing() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.CLOSING);

        connection.close(CloseReason.UNKNOWN, true, EndStream.Code.RESET);
        assertThat(connection.closeReason()).isNull(); // the UNKNOWN close reason should not be propagated

        verifyNoInteractions(connectionManager);
        verifyNoInteractions(requestCall);
        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testClose_stateChangedDuringClose() {
        activateConnection();

        // Create a spy to intercept the close method and change state during execution
        final DummyPublisherConnection spyConnection = spy(connection);
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
        spyConnection.close(CloseReason.UNKNOWN, true, EndStream.Code.RESET);

        // The close should have aborted due to state mismatch
        // State should still be READY (not changed to CLOSING or CLOSED)
        assertThat(connection.currentState()).isEqualTo(ConnectionState.READY);
        assertThat(connection.closeReason()).isNull();

        // No interactions should have occurred since close aborted early
        verifyNoInteractions(requestCall);
    }

    /**
     * Tests TimeoutException handling when pipeline.onComplete() times out during close.
     * Uses mocks to simulate a timeout without actually waiting, making the test fast.
     */
    @Test
    void testClose_onCompleteTimeoutException() throws Exception {
        activateConnection();

        // Create a mock Future that will throw TimeoutException when get() is called
        @SuppressWarnings("unchecked")
        final Future<Object> mockFuture = mock(Future.class);
        final AtomicInteger opCounter = new AtomicInteger(0);
        doAnswer(_ -> {
                    // when close is called, we will first attempt to send an EndStream.RESET message, that we want to
                    // succeed
                    // the second invocation will be the pipeline close that we want to fail with a timeout
                    final int opCount = opCounter.incrementAndGet();
                    if (opCount == 2) {
                        throw new TimeoutException("Simulated timeout");
                    } else {
                        return null;
                    }
                })
                .when(mockFuture)
                .get(anyLong(), any(TimeUnit.class));

        // Set up the blockingIoExecutor to return mock future
        doReturn(mockFuture).when(blockingIoExecutor).submit(any(Runnable.class));

        // Close connection - should trigger timeout during onComplete
        connection.close(CloseReason.INTERNAL_ERROR, true, EndStream.Code.RESET);

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
        assertThat(connection.closeReason()).isEqualTo(CloseReason.INTERNAL_ERROR);

        // Verify timeout was detected during onComplete
        verify(mockFuture, times(2)).get(anyLong(), any(TimeUnit.class));
        verify(mockFuture).cancel(true);
        verify(metrics).recordPipelineOperationTimeout();
        verify(metrics).recordConnectionClosed(CloseReason.INTERNAL_ERROR);
    }

    /**
     * Tests InterruptedException handling during blockingIoExecutor.awaitTermination() in close().
     * This covers the exception handling when shutting down the executor is interrupted.
     */
    @Test
    void testClose_executorShutdownInterruptedException() throws Exception {
        activateConnection();

        // Set up the blockingIoExecutor to throw InterruptedException during awaitTermination
        when(blockingIoExecutor.awaitTermination(anyLong(), any(TimeUnit.class)))
                .thenThrow(new InterruptedException("Simulated shutdown interruption"));

        // Close connection - should handle interruption during executor shutdown
        connection.close(CloseReason.INTERNAL_ERROR, true, EndStream.Code.ERROR);

        // Connection should still be CLOSED despite interruption
        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
        assertThat(connection.closeReason()).isEqualTo(CloseReason.INTERNAL_ERROR);

        // Verify executor shutdown was attempted
        verify(blockingIoExecutor).shutdown();
        verify(blockingIoExecutor).awaitTermination(5, TimeUnit.SECONDS);

        // Verify shutdownNow was called after interruption
        verify(blockingIoExecutor).shutdownNow();

        verify(metrics).recordConnectionClosed(CloseReason.INTERNAL_ERROR);
    }

    /**
     * Tests that closing the connection multiple times doesn't cause issues with executor shutdown.
     * The executor should only be shut down once, and subsequent closes should be idempotent.
     */
    @Test
    void testClose_multipleCloseCallsHandleExecutorShutdownGracefully() {
        activateConnection();

        // Close the connection first time
        connection.close(CloseReason.INTERNAL_ERROR, true, EndStream.Code.ERROR);
        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
        assertThat(connection.closeReason()).isEqualTo(CloseReason.INTERNAL_ERROR);

        // Reset mocks to verify second close behavior
        reset(requestCall, metrics, connectionManager);

        // Close again - should be idempotent (no-op since already closed)
        connection.close(CloseReason.UNKNOWN, true, EndStream.Code.ERROR);

        // close reason should not change since the connection is already closed when the second close was invoked
        assertThat(connection.closeReason()).isEqualTo(CloseReason.INTERNAL_ERROR);

        // Verify no additional operations were performed
        verifyNoInteractions(requestCall);
        verifyNoInteractions(metrics);
        verifyNoInteractions(connectionManager);

        // Connection should still be CLOSED
        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
    }

    /**
     * Tests that the pipelineExecutor is properly shut down when the connection closes.
     * This ensures no resource leaks and that the executor won't accept new tasks after close.
     */
    @Test
    void testClose_pipelineExecutorShutdown() {
        activateConnection();

        // Close the connection
        connection.close(CloseReason.PERIODIC_RESET, true, EndStream.Code.RESET);

        // Verify connection is closed
        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
        assertThat(connection.closeReason()).isEqualTo(CloseReason.PERIODIC_RESET);

        // Try to send a request after close - should be ignored since connection is CLOSED
        final PublishStreamRequestBytes request = createRequest(newBlockHeaderItem());
        sendRequest(new PublisherBlockItemsRequest(request, 1L, 1, 1, false, false, 3_000, 1));

        verify(requestCall).completeRequests(); // Only from the close() call
        verify(requestCall).sendRequest(any(), anyBoolean()); // sendRequest executed to send the RESET request

        // Verify no additional interactions beyond the close operation
        verify(metrics).recordConnectionClosed(CloseReason.PERIODIC_RESET);
        verifyNoMoreInteractions(requestCall);
    }

    @Test
    void testSendRequest() {
        activateConnection();
        final PublishStreamRequestBytes request = createRequest(newBlockHeaderItem());

        sendRequest(new PublisherBlockItemsRequest(request, 1L, 1, 1, false, false, 3_000, 1));

        final PostSuccessfulRequestSendArgs sendArgs = connection.postSuccessfulRequestSendArgs.get();
        assertThat(sendArgs).isNotNull();
        assertThat(sendArgs.request).isNotNull();
        assertThat(sendArgs.request.streamRequest()).isEqualTo(request);
        assertThat(sendArgs.result).isNotNull();
        assertThat(sendArgs.result.status()).isEqualTo(SendRequestStatus.SUCCESS);

        verify(requestCall).sendRequest(request, false);
        verify(metrics).recordRequestSent(RequestOneOfType.BLOCK_ITEMS);
        verify(metrics).recordBlockItemsSent(1);
        verify(metrics).recordRequestLatency(anyLong());
        verify(metrics).recordRequestBlockItemCount(1);
        verify(metrics).recordRequestBytes(anyLong());
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testSendRequest_notActive() {
        final PublishStreamRequestBytes request = createRequest(newBlockHeaderItem());

        connection.initialize();
        sendRequest(new PublisherBlockItemsRequest(request, 1L, 1, 1, false, false, 3_000, 1));

        assertThat(connection.postSuccessfulRequestSendArgs).hasNullValue();

        verify(metrics).recordConnectionOpened();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testSendRequest_requestPipelineNullAndNotActive() {
        final PublishStreamRequestBytes request = createRequest(newBlockHeaderItem());

        // don't create the request pipeline nor set connection active
        connection.updateConnectionState(ConnectionState.READY);
        sendRequest(new PublisherBlockItemsRequest(request, 1L, 1, 1, false, false, 3_000, 1));

        assertThat(connection.postSuccessfulRequestSendArgs).hasNullValue();

        verifyNoInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testSendRequest_errorWhileActive() {
        activateConnection();
        doThrow(new RuntimeException("kaboom!")).when(requestCall).sendRequest(any(), anyBoolean());
        final PublishStreamRequestBytes request = createRequest(newBlockHeaderItem());

        final RuntimeException e = catchRuntimeException(
                () -> sendRequest(new PublisherBlockItemsRequest(request, 1L, 1, 1, false, false, 3_000, 1)));
        assertThat(e).isInstanceOf(RuntimeException.class);
        // Exception gets wrapped when executed in virtual thread executor
        assertThat(e.getMessage()).contains("Error encountered while sending request to block node");
        assertThat(e.getCause()).isInstanceOf(RuntimeException.class);
        assertThat(e.getCause().getMessage()).isEqualTo("kaboom!");

        assertThat(connection.postSuccessfulRequestSendArgs).hasNullValue();

        verify(metrics).recordRequestSendFailure();
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testSendRequest_errorWhileNotActive() {
        openConnectionAndResetMocks();
        doThrow(new RuntimeException("kaboom!")).when(requestCall).sendRequest(any(), anyBoolean());

        final DummyPublisherConnection spiedConnection = spy(connection);
        doReturn(ConnectionState.ACTIVE, ConnectionState.CLOSING)
                .when(spiedConnection)
                .currentState();
        final PublishStreamRequestBytes request = createRequest(newBlockHeaderItem());

        sendRequest(spiedConnection, new PublisherBlockItemsRequest(request, 1L, 1, 1, false, false, 3_000, 1));

        assertThat(connection.postSuccessfulRequestSendArgs).hasNullValue();

        verify(requestCall).sendRequest(any(), anyBoolean());
        verify(spiedConnection, atLeast(2)).currentState();
        verify(metrics).recordRequestSendFailure();

        verifyNoMoreInteractions(metrics);
    }

    /**
     * Tests onNext() normal (non-timeout) path.
     */
    @Test
    void testSendRequest_onNextCompletesSuccessfully() {
        activateConnection();

        final PublishStreamRequestBytes request = createRequest(newBlockHeaderItem());
        sendRequest(new PublisherBlockItemsRequest(request, 1L, 1, 1, false, false, 3_000, 1));

        // Verify the request was sent successfully
        verify(requestCall).sendRequest(request, false);
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

        final PublishStreamRequestBytes request = createRequest(newBlockHeaderItem());

        // Since connection is not ACTIVE, sendRequest should not do anything
        sendRequest(new PublisherBlockItemsRequest(request, 1L, 1, 1, false, false, 3_000, 1));

        assertThat(connection.postSuccessfulRequestSendArgs).hasNullValue();

        // Verify no interactions since connection is not ACTIVE
        verifyNoInteractions(requestCall);
        verifyNoInteractions(metrics);
        verifyNoInteractions(connectionManager);
    }

    /**
     * Tests that error during pipeline operation is handled properly.
     * This tests the exception handling in sendRequest when pipeline.onNext throws.
     */
    @Test
    void testSendRequest_pipelineThrowsException() {
        activateConnection();

        // Mock requestCall.sendRequest() to throw an exception
        doThrow(new RuntimeException("Pipeline error")).when(requestCall).sendRequest(any(), anyBoolean());

        final PublishStreamRequestBytes request = createRequest(newBlockHeaderItem());

        // Should throw RuntimeException wrapped by the executor
        final RuntimeException exception = catchRuntimeException(
                () -> sendRequest(new PublisherBlockItemsRequest(request, 1L, 1, 1, false, false, 3_000, 1)));

        assertThat(connection.postSuccessfulRequestSendArgs).hasNullValue();
        assertThat(exception).isNotNull();
        // Exception gets wrapped when executed in virtual thread executor
        assertThat(exception.getMessage()).contains("Error encountered while sending request to block node");
        assertThat(exception.getCause()).isInstanceOf(RuntimeException.class);
        assertThat(exception.getCause().getMessage()).isEqualTo("Pipeline error");

        // Verify error was recorded
        verify(requestCall).sendRequest(request, false);
        verify(metrics).recordRequestSendFailure();
    }

    @Test
    void testSendRequest_timeoutException_firstFailure() throws Exception {
        activateConnection();

        // Create a mock Future that will throw TimeoutException when get() is called
        @SuppressWarnings("unchecked")
        final Future<Object> mockFuture = mock(Future.class);
        final AtomicBoolean isFirstCall = new AtomicBoolean(true);
        doAnswer(_ -> {
                    if (isFirstCall.compareAndSet(true, false)) {
                        throw new TimeoutException("Simulated timeout");
                    } else {
                        return null;
                    }
                })
                .when(mockFuture)
                .get(anyLong(), any(TimeUnit.class));

        // Set up the blockingIoExecutor to return mock future
        doReturn(mockFuture).when(blockingIoExecutor).submit(any(Runnable.class));

        final PublishStreamRequestBytes request = createRequest(newBlockHeaderItem());

        // Send request - should trigger timeout handling immediately
        final SendRequestResult result =
                sendRequest(new PublisherBlockItemsRequest(request, 1L, 1, 1, false, false, 3_000, 1));

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(SendRequestStatus.TIMEOUT);
        assertThat(connection.postSuccessfulRequestSendArgs).hasNullValue();

        // Connection should still be open
        assertThat(connection.currentState()).isEqualTo(ConnectionState.ACTIVE);
        assertThat(connection.closeReason()).isNull();

        // Verify timeout was detected and handled
        // Note: future.get() is called twice - once for sendRequest (times out)
        // and once for closePipeline/onComplete (also times out during cleanup)
        verify(mockFuture).get(anyLong(), any(TimeUnit.class));
        verify(mockFuture).cancel(true); // Future should be cancelled both times

        verify(metrics).recordRequestSendFailure();
        verify(metrics).recordPipelineOperationTimeout();

        verifyNoMoreInteractions(mockFuture);
        verifyNoMoreInteractions(metrics);
    }

    /**
     * Tests ExecutionException handling when pipeline.onNext() throws an exception.
     * This is already covered by testSendRequest_pipelineThrowsException but included
     * here for completeness of exception handling coverage.
     */
    @Test
    void testSendRequest_executionException() {
        activateConnection();

        // Mock requestCall.sendRequest() to throw an exception
        doThrow(new RuntimeException("Execution failed")).when(requestCall).sendRequest(any(), anyBoolean());

        final PublishStreamRequestBytes request = createRequest(newBlockHeaderItem());
        final PublisherBlockItemsRequest bisReq =
                new PublisherBlockItemsRequest(request, 10L, 1, 1, false, false, 3_000, 1);

        // Should throw RuntimeException wrapping ExecutionException
        final RuntimeException exception = catchRuntimeException(() -> sendRequest(bisReq));

        assertThat(connection.currentState()).isEqualTo(ConnectionState.ACTIVE);
        assertThat(connection.closeReason()).isNull();
        assertThat(connection.postSuccessfulRequestSendArgs).hasNullValue();

        assertThat(exception).isNotNull();
        assertThat(exception.getMessage()).contains("Error encountered while sending request to block node");
        assertThat(exception.getCause()).isInstanceOf(RuntimeException.class);
        assertThat(exception.getCause().getMessage()).isEqualTo("Execution failed");

        verify(metrics).recordRequestSendFailure();
    }

    @Test
    void testOnActiveStateTransition_notInitialized() {
        // don't call initialize
        connection.updateConnectionState(ConnectionState.ACTIVE);

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
        assertThat(connection.closeReason()).isEqualTo(CloseReason.INTERNAL_ERROR);
        assertThat(connection.isStartWorkerThreadCalled).isFalse();

        verify(metrics).recordConnectionClosed(CloseReason.INTERNAL_ERROR);
        verify(connectionManager).notifyConnectionClosed(connection);
        verifyNoInteractions(requestCall);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(connectionManager);
    }

    @Test
    void testOnActiveStateTransition() {
        assertThat(connection.currentState()).isEqualTo(ConnectionState.UNINITIALIZED);
        assertThat(connection.isStartWorkerThreadCalled).isFalse();
        assertThat(connection.isRequestClientActive()).isFalse();

        connection.initialize();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        assertThat(connection.currentState()).isEqualTo(ConnectionState.ACTIVE);
        assertThat(connection.isStartWorkerThreadCalled).isTrue();
        assertThat(connection.isRequestClientActive()).isTrue();

        verify(blockingIoExecutor).submit(any(Runnable.class));
        verify(metrics).recordConnectionOpened();
        verify(connectionManager).notifyConnectionActive(connection);
        verifyNoInteractions(requestCall);
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(connectionManager);
    }

    @Test
    void testOnError_activeConnection() {
        activateConnection();

        connection.onError(new RuntimeException("oh bother"));

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
        assertThat(connection.closeReason()).isEqualTo(CloseReason.CONNECTION_ERROR);

        verify(metrics).recordConnectionOnError();
        verify(metrics).recordConnectionClosed(CloseReason.CONNECTION_ERROR);
        verify(requestCall).completeRequests();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testOnError_grpcException() {
        activateConnection();

        // Create a real GrpcException
        final GrpcException grpcException =
                new GrpcException(GrpcStatus.UNAVAILABLE, new RuntimeException("Service unavailable"));

        connection.onError(grpcException);

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
        assertThat(connection.closeReason()).isEqualTo(CloseReason.CONNECTION_ERROR);

        verify(metrics).recordConnectionOnError();
        verify(metrics).recordConnectionClosed(CloseReason.CONNECTION_ERROR);
        verify(requestCall).completeRequests();
    }

    @Test
    void testOnError_terminalConnection() {
        openConnectionAndResetMocks();
        connection.updateConnectionState(ConnectionState.CLOSING);

        connection.onError(new RuntimeException("oh bother"));

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSING);
        assertThat(connection.closeReason()).isNull();

        verifyNoInteractions(metrics);
        verifyNoInteractions(requestCall);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testOnCompleted_streamClosingInProgress() {
        openConnectionAndResetMocks();
        // call close to mark the connection as closing
        connection.close(CloseReason.UNKNOWN, true, EndStream.Code.RESET);
        resetMocks();

        connection.onComplete();

        verify(metrics).recordConnectionOnComplete();
        verifyNoMoreInteractions(metrics);
        verifyNoInteractions(requestCall);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    // Tests onComplete when streamShutdownInProgress is true but connection not closed
    @Test
    void testOnCompleted_streamShutdownInProgressButNotClosed() throws Exception {
        activateConnection();

        // Use reflection to set streamShutdownInProgress to true without closing the connection
        // This simulates the race condition where shutdown begins but onComplete arrives first
        final var field = BlockNodePublisherConnection.class.getDeclaredField("streamShutdownInProgress");
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
        verifyNoInteractions(requestCall);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testOnCompleted_streamClosingNotInProgress() {
        activateConnection();

        // don't call close so we do not mark the connection as closing
        connection.onComplete();

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
        assertThat(connection.closeReason()).isEqualTo(CloseReason.CONNECTION_ERROR);

        verify(requestCall).completeRequests();
        verify(metrics).recordConnectionOnComplete();
        verify(metrics).recordConnectionClosed(CloseReason.CONNECTION_ERROR);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
        verifyNoInteractions(bufferService);
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
        verifyNoInteractions(requestCall);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    // Tests that error handling is skipped when connection is already closed
    @Test
    void testOnError_connectionClosed() {
        connection.updateConnectionState(ConnectionState.CLOSED);

        connection.onError(new RuntimeException("test error"));

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
        assertThat(connection.closeReason()).isNull();

        // Should not handle error when connection is already closed (terminal state)
        verifyNoInteractions(metrics);
        verifyNoInteractions(requestCall);
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
        assertThat(connection.closeReason()).isEqualTo(CloseReason.CONNECTION_ERROR);

        verify(metrics).recordConnectionOnError();
        verify(metrics).recordConnectionClosed(CloseReason.CONNECTION_ERROR);
        // Should call onComplete when callOnComplete=true (from handleStreamFailure)
        verify(requestCall).completeRequests();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestCall);
    }

    // Tests error handling in UNINITIALIZED state (should do nothing)
    @Test
    void testOnError_connectionUninitialized() {
        // Connection starts in UNINITIALIZED state by default
        assertThat(connection.currentState()).isEqualTo(ConnectionState.UNINITIALIZED);

        connection.onError(new RuntimeException("test error"));

        // Should transition to CLOSED state after handling the error
        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
        assertThat(connection.closeReason()).isEqualTo(CloseReason.CONNECTION_ERROR);

        verify(metrics).recordConnectionOnError();
        verify(metrics).recordConnectionClosed(CloseReason.CONNECTION_ERROR);
        verifyNoMoreInteractions(metrics);
        verifyNoInteractions(requestCall);
        verifyNoInteractions(bufferService);
    }

    // Tests Flow.Subscriber contract implementation (should request Long.MAX_VALUE)
    @Test
    void testOnSubscribe() {
        final Flow.Subscription subscription = mock(Flow.Subscription.class);

        connection.onSubscribe(subscription);

        verify(subscription).request(Long.MAX_VALUE);
        verifyNoInteractions(metrics);
        verifyNoInteractions(requestCall);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(bufferService);
    }

    // Tests connection state transition from ACTIVE to other states (should cancel reset task)
    @Test
    void testUpdateStatus_fromActiveToOther() {
        activateConnection();

        // Change from ACTIVE to CLOSING should cancel stream reset
        connection.updateConnectionState(ConnectionState.CLOSING);

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSING);
    }

    // ==== Utilities

    private void activateConnection() {
        connection.initialize();
        connection.updateConnectionState(ConnectionState.ACTIVE);
        resetMocks();
    }

    private void openConnectionAndResetMocks() {
        connection.initialize();
        // reset the mocks interactions to remove tracked interactions as a result of starting the connection
        resetMocks();
    }

    private void resetMocks() {
        reset(connectionManager, requestCall, bufferService, metrics);
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<ConnectionState> connectionState() {
        return (AtomicReference<ConnectionState>) connectionStateHandle.get(connection);
    }

    private SendRequestResult sendRequest(final PublisherRequest request) {
        return sendRequest(connection, request);
    }

    private SendRequestResult sendRequest(
            final BlockNodePublisherConnection connection, final PublisherRequest request) {
        try {
            return (SendRequestResult) sendRequestHandle.invoke(connection, request);
        } catch (final Throwable e) {
            if (e instanceof final RuntimeException re) {
                throw re;
            } else {
                throw new RuntimeException(e);
            }
        }
    }

    private DummyPublisherConnection newConnection(final ConnectionType type) {
        final DummyPublisherConnection connection = new DummyPublisherConnection(
                configProvider,
                blockNode,
                type,
                clientFactory,
                0L,
                metrics,
                blockingIoExecutor,
                bufferService,
                connectionManager);
        lenient().doReturn(requestCall).when(publishBytesClient).publishBlockStream(connection);

        return connection;
    }

    static class DummyPublisherConnection extends BlockNodePublisherConnection {

        private final AtomicBoolean isPostCloseCalled = new AtomicBoolean(false);
        private final AtomicReference<PostSuccessfulRequestSendArgs> postSuccessfulRequestSendArgs =
                new AtomicReference<>();
        private final AtomicBoolean isStartWorkerThreadCalled = new AtomicBoolean(false);
        private final AtomicBoolean isOnTerminalStateTransitionCalled = new AtomicBoolean(false);

        record PostSuccessfulRequestSendArgs(PublisherRequest request, SendRequestResult result) {}

        DummyPublisherConnection(
                final ConfigProvider configProvider,
                final BlockNode blockNode,
                final ConnectionType connectionType,
                final BlockNodeClientFactory clientFactory,
                final long nodeId,
                final BlockStreamMetrics metrics,
                final ExecutorService blockingIoExecutor,
                final BlockBufferService bufferService,
                final BlockNodeConnectionManager connectionManager) {
            super(
                    configProvider,
                    blockNode,
                    connectionType,
                    clientFactory,
                    nodeId,
                    metrics,
                    blockingIoExecutor,
                    bufferService,
                    connectionManager);
        }

        @Override
        void postClose() {
            isPostCloseCalled.set(true);
        }

        @Override
        void postSuccessfulRequestSend(
                @NonNull final PublisherRequest request, @NonNull final SendRequestResult result) {
            postSuccessfulRequestSendArgs.set(new PostSuccessfulRequestSendArgs(request, result));
        }

        @Override
        void startWorkerThread() {
            isStartWorkerThreadCalled.set(true);
        }

        @Override
        void onTerminalStateTransition() {
            isOnTerminalStateTransitionCalled.set(true);
        }
    }
}
