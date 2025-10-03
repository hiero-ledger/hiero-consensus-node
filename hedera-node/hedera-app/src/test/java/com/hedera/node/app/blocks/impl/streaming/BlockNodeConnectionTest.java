// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchRuntimeException;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
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
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnection.ConnectionState;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.internal.network.BlockNodeConfig;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.grpc.Pipeline;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
class BlockNodeConnectionTest extends BlockNodeCommunicationTestBase {
    private static final long ONCE_PER_DAY_MILLIS = Duration.ofHours(24).toMillis();

    private static final Thread FAKE_WORKER_THREAD = new Thread(() -> {}, "fake-worker");

    private static final VarHandle connectionStateHandle;
    private static final VarHandle streamingBlockNumberHandle;
    private static final VarHandle workerThreadRefHandle;

    static {
        try {
            final Lookup lookup = MethodHandles.lookup();
            connectionStateHandle = MethodHandles.privateLookupIn(BlockNodeConnection.class, lookup)
                    .findVarHandle(BlockNodeConnection.class, "connectionState", AtomicReference.class);
            streamingBlockNumberHandle = MethodHandles.privateLookupIn(BlockNodeConnection.class, lookup)
                    .findVarHandle(BlockNodeConnection.class, "streamingBlockNumber", AtomicLong.class);
            workerThreadRefHandle = MethodHandles.privateLookupIn(BlockNodeConnection.class, lookup)
                    .findVarHandle(BlockNodeConnection.class, "workerThreadRef", AtomicReference.class);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BlockNodeConnection connection;

    private BlockNodeConnectionManager connectionManager;
    private BlockBufferService bufferService;
    private BlockStreamPublishServiceClient grpcServiceClient;
    private BlockStreamMetrics metrics;
    private Pipeline<? super PublishStreamRequest> requestPipeline;
    private ScheduledExecutorService executorService;

    @BeforeEach
    void beforeEach() {
        final ConfigProvider configProvider = createConfigProvider(createDefaultConfigProvider());
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(8080, 1);
        connectionManager = mock(BlockNodeConnectionManager.class);
        bufferService = mock(BlockBufferService.class);
        grpcServiceClient = mock(BlockStreamPublishServiceClient.class);
        metrics = mock(BlockStreamMetrics.class);
        requestPipeline = mock(Pipeline.class);
        executorService = mock(ScheduledExecutorService.class);

        connection = new BlockNodeConnection(
                configProvider,
                nodeConfig,
                connectionManager,
                bufferService,
                grpcServiceClient,
                metrics,
                executorService);

        // To avoid potential non-deterministic effects due to the worker thread, assign a fake worker thread to the
        // connection that does nothing.
        final AtomicReference<Thread> workerThreadRef = workerThreadRef();
        workerThreadRef.set(FAKE_WORKER_THREAD);

        resetMocks();

        lenient().doReturn(requestPipeline).when(grpcServiceClient).publishBlockStream(connection);
    }

    @AfterEach
    void afterEach() throws Exception {
        // set the connection to closed so the worker thread stops gracefully
        connection.updateConnectionState(ConnectionState.CLOSED);
        final AtomicReference<Thread> workerThreadRef = workerThreadRef();

        for (int i = 0; i < 5; ++i) {
            final Thread workerThread = workerThreadRef.get();
            if (workerThread == null || workerThread.equals(FAKE_WORKER_THREAD)) {
                break;
            }

            Thread.sleep(50);
        }

        final Thread workerThread = workerThreadRef.get();
        if (workerThread != null && !workerThread.equals(FAKE_WORKER_THREAD)) {
            fail("Connection worker thread did not get cleaned up");
        }
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
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(connectionManager);
    }

    @Test
    void testOnNext_acknowledgement_notStreaming() {
        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(-1); // pretend we are currently not streaming any blocks
        final PublishStreamResponse response = createBlockAckResponse(10L);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        connection.onNext(response);

        assertThat(streamingBlockNumber).hasValue(11); // moved to acked block + 1

        verify(bufferService).getLastBlockNumberProduced();
        verify(bufferService).setLatestAcknowledgedBlock(10);
        verify(metrics).recordResponseReceived(ResponseOneOfType.ACKNOWLEDGEMENT);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(connectionManager);
    }

    @Test
    void testOnNext_acknowledgement_olderThanCurrentStreamingAndProducing() {
        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(10); // pretend we are streaming block 10
        when(bufferService.getLastBlockNumberProduced()).thenReturn(10L);
        final PublishStreamResponse response = createBlockAckResponse(8L);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        connection.onNext(response);

        assertThat(streamingBlockNumber).hasValue(10L); // should not change

        verify(bufferService).getLastBlockNumberProduced();
        verify(bufferService).setLatestAcknowledgedBlock(8);
        verify(metrics).recordResponseReceived(ResponseOneOfType.ACKNOWLEDGEMENT);
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

        connection.updateConnectionState(ConnectionState.ACTIVE);
        connection.onNext(response);

        assertThat(streamingBlockNumber).hasValue(12); // should be 1 + acked block number

        verify(bufferService).getLastBlockNumberProduced();
        verify(bufferService).setLatestAcknowledgedBlock(11L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.ACKNOWLEDGEMENT);
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

        connection.updateConnectionState(ConnectionState.ACTIVE);
        connection.onNext(response);

        assertThat(streamingBlockNumber).hasValue(12); // should be 1 + acked block number

        verify(bufferService).getLastBlockNumberProduced();
        verify(bufferService).setLatestAcknowledgedBlock(11L);
        verify(metrics).recordResponseReceived(ResponseOneOfType.ACKNOWLEDGEMENT);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(bufferService);
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

        verify(metrics).recordResponseEndOfStreamReceived(responseCode);
        verify(metrics).recordConnectionClosed();
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

        verify(metrics).recordResponseEndOfStreamReceived(responseCode);
        verify(metrics).recordConnectionClosed();
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

        verify(metrics).recordResponseEndOfStreamReceived(Code.SUCCESS);
        verify(metrics).recordConnectionClosed();
        verify(requestPipeline).onComplete();
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

        verify(metrics).recordResponseEndOfStreamReceived(Code.BEHIND);
        verify(metrics).recordConnectionClosed();
        verify(requestPipeline).onComplete();
        verify(connectionManager).rescheduleConnection(connection, null, 11L, false);
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

        verify(metrics).recordResponseEndOfStreamReceived(Code.BEHIND);
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordRequestEndStreamSent(EndStream.Code.TOO_FAR_BEHIND);
        verify(bufferService, times(1)).getEarliestAvailableBlockNumber();
        verify(bufferService, times(1)).getHighestAckedBlockNumber();
        verify(bufferService).getBlockState(11L);
        verify(requestPipeline).onNext(createRequest(EndStream.Code.TOO_FAR_BEHIND));
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

        verify(metrics).recordResponseEndOfStreamReceived(Code.UNKNOWN);
        verify(metrics).recordConnectionClosed();
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

        verify(metrics).recordResponseReceived(ResponseOneOfType.SKIP_BLOCK);

        assertThat(streamingBlockNumber).hasValue(26);

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

        verify(metrics).recordResponseReceived(ResponseOneOfType.RESEND_BLOCK);
        verify(bufferService).getBlockState(10L);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testOnNext_resendBlock_blockDoesNotExist() {
        openConnectionAndResetMocks();

        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(11); // pretend we are currently streaming block 11
        final PublishStreamResponse response = createResendBlock(10L);
        when(bufferService.getBlockState(10L)).thenReturn(null);
        connection.updateConnectionState(ConnectionState.ACTIVE);

        connection.onNext(response);

        verify(metrics).recordResponseReceived(ResponseOneOfType.RESEND_BLOCK);
        verify(metrics).recordConnectionClosed();
        verify(requestPipeline).onComplete();
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
        assertThat(e).isInstanceOf(RuntimeException.class).hasMessage("kaboom!");

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
        verify(spiedConnection, atLeast(2)).getConnectionState();

        verifyNoInteractions(metrics);
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

        verify(requestPipeline).onComplete();
        verify(metrics).recordConnectionClosed();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
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
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(bufferService);
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

        // sleep for a little bit to give the worker a chance to detect the connection state change
        Thread.sleep(100);

        assertThat(workerThreadRef).hasNullValue();
        assertThat(workerThread.isAlive()).isFalse();
    }

    @Test
    void testConnectionWorker_switchBlock_initializeToHighestAckedBlock() throws Exception {
        openConnectionAndResetMocks();
        final AtomicReference<Thread> workerThreadRef = workerThreadRef();
        workerThreadRef.set(null); // clear out the fake worker thread so a real one can be initialized
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        doReturn(100L)
                .when(bufferService).getHighestAckedBlockNumber();
        doReturn(new BlockState(101))
                .when(bufferService).getBlockState(101);

        assertThat(streamingBlockNumber).hasValue(-1);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        Thread.sleep(50); // give some time for the worker loop to detect the changes

        assertThat(workerThreadRef).doesNotHaveNullValue();
        assertThat(streamingBlockNumber).hasValue(101);

        verify(bufferService).getHighestAckedBlockNumber();
        verify(bufferService).getBlockState(101);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(metrics);
        verifyNoInteractions(requestPipeline);
    }

    @Test
    void testConnectionWorker_switchBlock_initializeToEarliestBlock() throws Exception {
        openConnectionAndResetMocks();
        final AtomicReference<Thread> workerThreadRef = workerThreadRef();
        workerThreadRef.set(null); // clear out the fake worker thread so a real one can be initialized
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        doReturn(-1L)
                .when(bufferService).getHighestAckedBlockNumber();
        doReturn(12L)
                .when(bufferService).getEarliestAvailableBlockNumber();
        doReturn(new BlockState(12))
                .when(bufferService).getBlockState(12);

        assertThat(streamingBlockNumber).hasValue(-1);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        Thread.sleep(50); // give some time for the worker loop to detect the changes

        assertThat(workerThreadRef).doesNotHaveNullValue();
        assertThat(streamingBlockNumber).hasValue(12);

        verify(bufferService).getHighestAckedBlockNumber();
        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getBlockState(12);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(metrics);
        verifyNoInteractions(requestPipeline);
    }

    @Test
    void testConnectionWorker_switchBlock_noBlockAvailable() throws Exception {
        openConnectionAndResetMocks();
        final AtomicReference<Thread> workerThreadRef = workerThreadRef();
        workerThreadRef.set(null); // clear out the fake worker thread so a real one can be initialized
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        doReturn(-1L)
                .when(bufferService).getHighestAckedBlockNumber();
        doReturn(-1L)
                .when(bufferService).getEarliestAvailableBlockNumber();

        assertThat(streamingBlockNumber).hasValue(-1);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        Thread.sleep(50); // give some time for the worker loop to detect the changes

        assertThat(workerThreadRef).doesNotHaveNullValue();
        assertThat(streamingBlockNumber).hasValue(-1);

        verify(bufferService, atLeastOnce()).getHighestAckedBlockNumber();
        verify(bufferService, atLeastOnce()).getEarliestAvailableBlockNumber();
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(metrics);
        verifyNoInteractions(requestPipeline);
    }

    @Test
    void testConnectionWorker_sendRequests() throws Exception {
        openConnectionAndResetMocks();
        final AtomicReference<Thread> workerThreadRef = workerThreadRef();
        workerThreadRef.set(null); // clear the fake worker thread
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        streamingBlockNumber.set(10);

        final BlockState block = new BlockState(10);

        doReturn(block)
                .when(bufferService).getBlockState(10);

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
        final BlockItem item10 = newBlockProofItem(1_420_910);
        block.addItem(item7);
        block.addItem(item8);
        block.addItem(item9);
        block.addItem(item10);
        block.closeBlock();

        Thread.sleep(500);

        verify(requestPipeline, atLeastOnce()).onNext(requestCaptor.capture());
        final List<PublishStreamRequest> requests4 = requestCaptor.getAllValues();
        final int totalRequestsSent = requests4.size();
        reset(requestPipeline);
        requests4.removeAll(requests1);
        requests4.removeAll(requests2);
        requests4.removeAll(requests3);
        assertRequestContainsItems(requests4, item7, item8, item9, item10);

        assertThat(streamingBlockNumber).hasValue(11);

        verify(metrics, times(totalRequestsSent)).recordRequestSent(RequestOneOfType.BLOCK_ITEMS);
        verify(metrics, times(totalRequestsSent)).recordBlockItemsSent(anyInt());
        verify(bufferService, atLeastOnce()).getBlockState(10);
        verify(bufferService, atLeastOnce()).getBlockState(11);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(connectionManager);
        verifyNoMoreInteractions(requestPipeline);
    }

    @Test
    void testConnectionWorker_noItemsAvailable() throws Exception {
        openConnectionAndResetMocks();
        final AtomicReference<Thread> workerThreadRef = workerThreadRef();
        workerThreadRef.set(null); // clear the fake worker thread
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        streamingBlockNumber.set(10);

        doReturn(new BlockState(10))
                .when(bufferService).getBlockState(10);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        // sleep to let the worker detect the state change and start doing work
        Thread.sleep(150);

        assertThat(workerThreadRef).doesNotHaveNullValue();
        assertThat(streamingBlockNumber).hasValue(10);

        verify(bufferService, atLeastOnce()).getBlockState(10);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(metrics);
        verifyNoInteractions(requestPipeline);
    }

    @Test
    void testConnectionWorker_blockJump() throws Exception {
        openConnectionAndResetMocks();
        final AtomicReference<Thread> workerThreadRef = workerThreadRef();
        workerThreadRef.set(null); // clear the fake worker thread
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        streamingBlockNumber.set(10);

        final BlockState block10 = new BlockState(10);
        final BlockItem block10Header = newBlockHeaderItem(10);
        block10.addItem(block10Header);
        final BlockState block11 = new BlockState(11);
        final BlockItem block11Header = newBlockHeaderItem(11);
        block11.addItem(block11Header);
        doReturn(block10)
                .when(bufferService).getBlockState(10);
        doReturn(block11)
                .when(bufferService).getBlockState(11);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        // sleep to let the worker detect the state change and start doing work
        Thread.sleep(150);

        // create a skip response to force the connection to jump to block 11
        final PublishStreamResponse skipResponse = createSkipBlock(10L);
        connection.onNext(skipResponse);

        Thread.sleep(600); // give the worker thread some time to detect the change

        assertThat(streamingBlockNumber).hasValue(11);

        final ArgumentCaptor<PublishStreamRequest> requestCaptor = ArgumentCaptor.forClass(PublishStreamRequest.class);
        verify(requestPipeline, times(2)).onNext(requestCaptor.capture());

        assertThat(requestCaptor.getAllValues()).hasSize(2);
        assertRequestContainsItems(requestCaptor.getAllValues(), block10Header, block11Header);

        verify(metrics, times(2)).recordRequestSent(RequestOneOfType.BLOCK_ITEMS);
        verify(metrics, times(2)).recordBlockItemsSent(1);
        verify(metrics).recordResponseReceived(ResponseOneOfType.SKIP_BLOCK);
        verify(bufferService, atLeastOnce()).getBlockState(10);
        verify(bufferService, atLeastOnce()).getBlockState(11);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(connectionManager);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
    }

    @Test
    void testConnectionWorker_hugeItem() throws Exception {
        openConnectionAndResetMocks();
        final AtomicReference<Thread> workerThreadRef = workerThreadRef();
        workerThreadRef.set(null); // clear the fake worker thread
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        streamingBlockNumber.set(10);

        final BlockState block = new BlockState(10);
        final BlockItem blockHeader = newBlockHeaderItem(10);
        final BlockItem hugeItem = newBlockTxItem(3_000_000);
        block.addItem(blockHeader);
        block.addItem(hugeItem);
        doReturn(block)
                .when(bufferService).getBlockState(10);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        // sleep to let the worker detect the state change and start doing work
        Thread.sleep(150);

        final ArgumentCaptor<PublishStreamRequest> requestCaptor = ArgumentCaptor.forClass(PublishStreamRequest.class);
        verify(requestPipeline, times(2)).onNext(requestCaptor.capture());

        // there should be two requests: one for the block header and another for the EndStream
        // the huge item should NOT be sent
        assertThat(requestCaptor.getAllValues()).hasSize(2);
        final List<PublishStreamRequest> requests = requestCaptor.getAllValues();
        assertRequestContainsItems(requests.getFirst(), blockHeader);
        final PublishStreamRequest endStreamRequest = requests.get(1);
        assertThat(endStreamRequest.hasEndStream()).isTrue();
        final EndStream endStream = endStreamRequest.endStream();
        assertThat(endStream).isNotNull();
        assertThat(endStream.endCode()).isEqualTo(EndStream.Code.ERROR);

        verify(metrics).recordBlockItemsSent(1);
        verify(metrics).recordRequestSent(RequestOneOfType.BLOCK_ITEMS);
        verify(metrics).recordRequestEndStreamSent(EndStream.Code.ERROR);
        verify(metrics).recordConnectionClosed();
        verify(requestPipeline).onComplete();
        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getHighestAckedBlockNumber();
        verify(connectionManager).connectionResetsTheStream(connection);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(connectionManager);
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

    private void assertRequestContainsItems(final List<PublishStreamRequest> requests, final BlockItem... expectedItems) {
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
