// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchRuntimeException;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.hedera.pbj.grpc.client.helidon.PbjGrpcClientConfig;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.grpc.Pipeline;
import com.hedera.pbj.runtime.grpc.ServiceInterface.RequestOptions;
import io.helidon.webclient.api.WebClient;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
class BlockNodeConnectionTest extends BlockNodeCommunicationTestBase {
    private static final long ONCE_PER_DAY_MILLIS = Duration.ofHours(24).toMillis();
    private static final VarHandle connectionStateHandle;
    private static final Thread FAKE_WORKER_THREAD = new Thread(() -> {}, "fake-worker");
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
    private BlockNodeConfig nodeConfig;

    private BlockNodeConnectionManager connectionManager;
    private BlockBufferService bufferService;
    private BlockStreamPublishServiceClient grpcServiceClient;
    private BlockStreamMetrics metrics;
    private Pipeline<? super PublishStreamRequest> requestPipeline;
    private ScheduledExecutorService executorService;
    private BlockNodeStats.HighLatencyResult latencyResult;

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

        final BlockNodeClientFactory clientFactory = mock(BlockNodeClientFactory.class);
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
                null,
                clientFactory);

        // To avoid potential non-deterministic effects due to the worker thread, assign a fake worker thread to the
        // connection that does nothing.
        final AtomicReference<Thread> workerThreadRef = workerThreadRef();
        workerThreadRef.set(FAKE_WORKER_THREAD);

        //        resetMocks();

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
        when(connectionManager.recordBlockAckAndCheckLatency(eq(connection.getNodeConfig()), eq(10L), any()))
                .thenReturn(latencyResult);
        when(latencyResult.shouldSwitch()).thenReturn(false);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        connection.onNext(response);

        assertThat(streamingBlockNumber).hasValue(11); // moved to acked block + 1

        verify(connectionManager)
                .recordBlockAckAndCheckLatency(eq(connection.getNodeConfig()), eq(10L), any(Instant.class));
        verify(bufferService).getLastBlockNumberProduced();
        verify(bufferService).setLatestAcknowledgedBlock(10);
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
        when(connectionManager.recordBlockAckAndCheckLatency(eq(connection.getNodeConfig()), eq(8L), any()))
                .thenReturn(latencyResult);
        when(latencyResult.shouldSwitch()).thenReturn(false);

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
        when(connectionManager.recordBlockAckAndCheckLatency(eq(connection.getNodeConfig()), eq(11L), any()))
                .thenReturn(latencyResult);
        when(latencyResult.shouldSwitch()).thenReturn(false);

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
        when(connectionManager.recordBlockAckAndCheckLatency(eq(connection.getNodeConfig()), eq(11L), any()))
                .thenReturn(latencyResult);
        when(latencyResult.shouldSwitch()).thenReturn(false);

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

    // Tests acknowledgement equal to current streaming/producing blocks (should not jump)
    @Test
    void testOnNext_acknowledgement_equalToCurrentStreamingAndProducing() {
        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(10); // pretend we are streaming block 10
        final PublishStreamResponse response = createBlockAckResponse(10L);

        when(bufferService.getLastBlockNumberProduced()).thenReturn(10L);
        when(connectionManager.recordBlockAckAndCheckLatency(eq(connection.getNodeConfig()), eq(10L), any()))
                .thenReturn(latencyResult);
        when(latencyResult.shouldSwitch()).thenReturn(false);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        connection.onNext(response);

        // Should not jump to block since acknowledgement is not newer
        assertThat(streamingBlockNumber).hasValue(10L);

        verify(bufferService).getLastBlockNumberProduced();
        verify(bufferService).setLatestAcknowledgedBlock(10L);
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
    void testOnNext_resendBlock_blockDoesNotExist() {
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

        // Mock Pipeline#onComplete to throw a RuntimeException to trigger the catch block
        doThrow(new RuntimeException("Simulated close error"))
                .when(requestPipeline)
                .onComplete();

        // This should not throw an exception - it should be caught and logged
        connection.close(true);

        // Verify the exception handling path was taken
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
        sleep(100);

        assertThat(workerThreadRef).hasNullValue();
        assertThat(workerThread.isAlive()).isFalse();
    }

    @Test
    void testConnectionWorker_switchBlock_initializeToHighestAckedBlock() throws Exception {
        openConnectionAndResetMocks();
        final AtomicReference<Thread> workerThreadRef = workerThreadRef();
        workerThreadRef.set(null); // clear out the fake worker thread so a real one can be initialized
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        doReturn(100L).when(bufferService).getHighestAckedBlockNumber();
        doReturn(new BlockState(101)).when(bufferService).getBlockState(101);

        assertThat(streamingBlockNumber).hasValue(-1);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        sleep(50); // give some time for the worker loop to detect the changes

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

        doReturn(-1L).when(bufferService).getHighestAckedBlockNumber();
        doReturn(12L).when(bufferService).getEarliestAvailableBlockNumber();
        doReturn(new BlockState(12)).when(bufferService).getBlockState(12);

        assertThat(streamingBlockNumber).hasValue(-1);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        sleep(50); // give some time for the worker loop to detect the changes

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

        doReturn(-1L).when(bufferService).getHighestAckedBlockNumber();
        doReturn(-1L).when(bufferService).getEarliestAvailableBlockNumber();

        assertThat(streamingBlockNumber).hasValue(-1);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        sleep(50); // give some time for the worker loop to detect the changes

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

        doReturn(block).when(bufferService).getBlockState(10);

        final ArgumentCaptor<PublishStreamRequest> requestCaptor = ArgumentCaptor.forClass(PublishStreamRequest.class);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        // sleep to let the worker detect the state change and start doing work
        sleep(100);

        // add the header to the block, then wait for the max request delay... a request with the header should be sent
        final BlockItem item1 = newBlockHeaderItem();
        block.addItem(item1);

        sleep(400);
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

        sleep(400);

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

        sleep(500);

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

        sleep(500);

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
        verify(metrics, times(totalRequestsSent)).recordRequestLatency(anyLong());
        verify(connectionManager).recordBlockProofSent(eq(connection.getNodeConfig()), eq(10L), any(Instant.class));
        verify(bufferService, atLeastOnce()).getBlockState(10);
        verify(bufferService, atLeastOnce()).getBlockState(11);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(requestPipeline);
    }

    @Test
    void testConnectionWorker_noItemsAvailable() throws Exception {
        openConnectionAndResetMocks();
        final AtomicReference<Thread> workerThreadRef = workerThreadRef();
        workerThreadRef.set(null); // clear the fake worker thread
        final AtomicLong streamingBlockNumber = streamingBlockNumber();

        streamingBlockNumber.set(10);

        doReturn(new BlockState(10)).when(bufferService).getBlockState(10);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        // sleep to let the worker detect the state change and start doing work
        sleep(150);

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
        doReturn(block10).when(bufferService).getBlockState(10);
        doReturn(block11).when(bufferService).getBlockState(11);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        // sleep to let the worker detect the state change and start doing work
        sleep(150);

        // create a skip response to force the connection to jump to block 11
        final PublishStreamResponse skipResponse = createSkipBlock(10L);
        connection.onNext(skipResponse);

        sleep(600); // give the worker thread some time to detect the change

        assertThat(streamingBlockNumber).hasValue(11);

        final ArgumentCaptor<PublishStreamRequest> requestCaptor = ArgumentCaptor.forClass(PublishStreamRequest.class);
        verify(requestPipeline, times(2)).onNext(requestCaptor.capture());

        assertThat(requestCaptor.getAllValues()).hasSize(2);
        assertRequestContainsItems(requestCaptor.getAllValues(), block10Header, block11Header);

        verify(metrics, times(2)).recordRequestSent(RequestOneOfType.BLOCK_ITEMS);
        verify(metrics, times(2)).recordBlockItemsSent(1);
        verify(metrics, times(2)).recordRequestLatency(anyLong());
        verify(metrics).recordLatestBlockSkipBlock(10);
        verify(metrics).recordResponseReceived(ResponseOneOfType.SKIP_BLOCK);
        verify(bufferService, atLeastOnce()).getBlockState(10);
        verify(bufferService, atLeastOnce()).getBlockState(11);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(connectionManager);
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
        doReturn(block).when(bufferService).getBlockState(10);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        // sleep to let the worker detect the state change and start doing work
        sleep(150);

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
        verify(metrics).recordActiveConnectionIp(-1L);
        verify(metrics, times(2)).recordRequestLatency(anyLong());
        verify(requestPipeline).onComplete();
        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getHighestAckedBlockNumber();
        verify(connectionManager).connectionResetsTheStream(connection);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestPipeline);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(connectionManager);
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

    private static void sleep(final long millis) throws InterruptedException {
        Thread.sleep(millis);
    }
}
