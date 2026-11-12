// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.internal.AcknowledgeOnlyBytes;
import com.hedera.hapi.block.internal.EndStreamBytes;
import com.hedera.hapi.block.internal.PublishStreamRequestBytes;
import com.hedera.hapi.block.internal.PublishStreamRequestBytes.RequestOneOfType;
import com.hedera.node.app.blocks.impl.streaming.BlockNodePublisherConnection.PublisherRequest;
import com.hedera.node.app.blocks.impl.streaming.BlockNodePublisherConnection.SendRequestResult;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.types.BlockStreamGrpcCompressionType;
import com.hedera.pbj.runtime.grpc.GrpcCall;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.hiero.block.api.PublishStreamRequest.EndStream;
import org.hiero.block.api.PublishStreamResponse;
import org.hiero.block.api.PublishStreamResponse.BehindPublisher;
import org.hiero.block.api.PublishStreamResponse.EndOfStream;
import org.hiero.block.api.PublishStreamResponse.EndOfStream.Code;
import org.hiero.block.api.PublishStreamResponse.ResendBlock;
import org.hiero.block.api.PublishStreamResponse.ResponseOneOfType;
import org.hiero.block.api.PublishStreamResponse.SkipBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class BlockNodeObservingConnectionTest extends BlockNodeCommunicationTestBase {

    private static final Thread FAKE_WORKER_THREAD = new Thread(() -> {}, "fake-worker");

    private static final VarHandle connectionStateHandle;
    private static final VarHandle workerThreadRefHandle;

    private static final MethodHandle sendRequestHandle;

    static {
        try {
            final Lookup lookup = MethodHandles.lookup();
            final Class<?> publisherCls = BlockNodePublisherConnection.class;
            final Class<?> observingCls = BlockNodeObservingConnection.class;

            connectionStateHandle = MethodHandles.privateLookupIn(AbstractBlockNodeConnection.class, lookup)
                    .findVarHandle(AbstractBlockNodeConnection.class, "stateRef", AtomicReference.class);
            workerThreadRefHandle = MethodHandles.privateLookupIn(observingCls, lookup)
                    .findVarHandle(observingCls, "workerThreadRef", AtomicReference.class);

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
    private BlockNodeObservingConnection connection;

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

        connection = new BlockNodeObservingConnection(
                configProvider,
                blockNode,
                connectionManager,
                bufferService,
                metrics,
                blockingIoExecutor,
                clientFactory,
                0L);

        // To avoid potential non-deterministic effects due to the worker thread, assign a fake worker thread to the
        // connection that does nothing.
        final AtomicReference<Thread> workerThreadRef = workerThreadRef();
        workerThreadRef.set(FAKE_WORKER_THREAD);

        lenient().doReturn(requestCall).when(publishBytesClient).publishBlockStream(connection);
    }

    @AfterEach
    void afterEach() throws Exception {
        if (realExecSvc != null) {
            realExecSvc.shutdownNow();
        }

        connection.updateConnectionState(ConnectionState.CLOSED);
    }

    @Test
    void testOnNext_nullResponse() {
        assertThatThrownBy(() -> connection.onNext(null)).isInstanceOf(NullPointerException.class);

        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(requestCall);
    }

    @Test
    void testOnNext_connectionClosed() {
        connectionState().set(ConnectionState.CLOSED);

        connection.onNext(createBlockAckResponse(10L));

        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(requestCall);
    }

    @Test
    void testOnNext_handleAcknowledgement() {
        activateConnection();

        connection.onNext(createBlockAckResponse(10L));

        verify(metrics).recordResponseReceived(ResponseOneOfType.ACKNOWLEDGEMENT);
        verify(bufferService).acknowledgeBlock(10L, blockNode.configuration().streamingEndpoint());
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(requestCall);
    }

    @ParameterizedTest
    @MethodSource("testOnNext_handleEndStreamArgs")
    void testOnNext_handleEndStream(final EndOfStream.Code eosCode, final CloseReason expectedCloseReason) {
        final long blockNumber = 10L;

        activateConnection();

        final PublishStreamResponse response = createEndOfStreamResponse(eosCode, blockNumber);
        connection.onNext(response);

        assertThat(connectionState()).hasValue(ConnectionState.CLOSED);

        verify(metrics).recordResponseEndOfStreamReceived(eosCode);
        verify(metrics).recordLatestBlockEndOfStream(blockNumber);
        verify(metrics).recordConnectionClosed(expectedCloseReason);
        verify(bufferService)
                .acknowledgeBlock(blockNumber, connection.configuration().streamingEndpoint());
        verify(requestCall).completeRequests();
        verify(blockingIoExecutor, times(2)).submit(any(Runnable.class));
        verify(connectionManager).notifyConnectionClosed(connection);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(requestCall);
    }

    private static Stream<Arguments> testOnNext_handleEndStreamArgs() {
        final List<Arguments> args = new ArrayList<>();

        for (final EndOfStream.Code eosCode : EndOfStream.Code.values()) {
            final CloseReason expectedCloseReason = switch (eosCode) {
                case Code.TIMEOUT, Code.DUPLICATE_BLOCK, Code.BAD_BLOCK_PROOF, Code.INVALID_REQUEST ->
                    CloseReason.TRANSIENT_END_STREAM_RECEIVED;
                default -> CloseReason.END_STREAM_RECEIVED;
            };

            args.add(Arguments.of(eosCode, expectedCloseReason));
        }

        return args.stream();
    }

    @ParameterizedTest
    @MethodSource("testOnNext_unacceptedResponsesArgs")
    void testOnNext_unacceptedResponses(final PublishStreamResponse response) {
        activateConnection();

        connection.onNext(response);

        verify(metrics).recordIgnoredResponseWhileObserving();
        verifyNoMoreInteractions(metrics);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(requestCall);
    }

    private static Stream<Arguments> testOnNext_unacceptedResponsesArgs() {
        final List<Arguments> args = new ArrayList<>();

        for (final PublishStreamResponse.ResponseOneOfType responseType :
                PublishStreamResponse.ResponseOneOfType.values()) {
            final PublishStreamResponse resp = switch (responseType) {
                case ACKNOWLEDGEMENT, END_STREAM -> null; // these are accepted responses
                case SKIP_BLOCK ->
                    PublishStreamResponse.newBuilder()
                            .skipBlock(SkipBlock.newBuilder().blockNumber(10))
                            .build();
                case RESEND_BLOCK ->
                    PublishStreamResponse.newBuilder()
                            .resendBlock(ResendBlock.newBuilder().blockNumber(10))
                            .build();
                case UNSET -> PublishStreamResponse.newBuilder().build();
                case NODE_BEHIND_PUBLISHER ->
                    PublishStreamResponse.newBuilder()
                            .nodeBehindPublisher(BehindPublisher.newBuilder().blockNumber(10))
                            .build();
            };

            if (resp != null) {
                args.add(Arguments.of(resp));
            }
        }

        return args.stream();
    }

    @Test
    void testStartWorkerThread_alreadyStarted() {
        workerThreadRef().set(FAKE_WORKER_THREAD);

        connection.startWorkerThread();

        assertThat(workerThreadRef()).hasValue(FAKE_WORKER_THREAD);
    }

    @Test
    void testStartWorkerThread() {
        workerThreadRef().set(null);

        connection.startWorkerThread();

        assertThat(workerThreadRef()).doesNotHaveNullValue();
    }

    @Test
    void testConnectionWorkerLoopTask_terminalStateOnStart() throws Exception {
        connectionState().set(ConnectionState.CLOSED);

        final Object worker = createWorker();

        invokeRun(worker);

        // nothing is called since the worker exits before it attempts to do any work
        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(requestCall);
    }

    @Test
    void testConnectionWorkerLoopTask_terminalStateAfterDoWork() throws Exception {
        activateConnection();

        final Object worker = createWorker();

        // we want to switch the connection to a terminal state after the first iteration
        // we can do this by latching onto the block buffer calls to switch the connection state
        doAnswer(_ -> {
                    connectionState().set(ConnectionState.CLOSING);
                    return 10L;
                })
                .when(bufferService)
                .getLastBlockNumberProduced();
        when(bufferService.getHighestAckedBlockNumber()).thenReturn(10L);

        invokeRun(worker);

        // since the latest block produced matches the highest ACKed block, there should only be buffer interactions
        // because we escape early
        verify(bufferService).getLastBlockNumberProduced();
        verify(bufferService).getHighestAckedBlockNumber();
        verifyNoInteractions(metrics);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(requestCall);
    }

    @Test
    void testConnectionWorkerLoopTask_doWork_noBlocksProduced() throws Exception {
        final Object worker = createWorker();

        when(bufferService.getLastBlockNumberProduced()).thenReturn(-1L);
        when(bufferService.getHighestAckedBlockNumber()).thenReturn(-1L);

        invokeDoWork(worker);

        // since no blocks have been produced, there should only be buffer interactions
        verify(bufferService).getLastBlockNumberProduced();
        verify(bufferService).getHighestAckedBlockNumber();
        verifyNoInteractions(metrics);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(requestCall);
    }

    @Test
    void testConnectionWorkerLoopTask_doWork_noNewBlocksProduced() throws Exception {
        final Object worker = createWorker();

        when(bufferService.getLastBlockNumberProduced()).thenReturn(10L);
        when(bufferService.getHighestAckedBlockNumber()).thenReturn(10L);

        invokeDoWork(worker);

        // since no blocks have been produced, there should only be buffer interactions
        verify(bufferService).getLastBlockNumberProduced();
        verify(bufferService).getHighestAckedBlockNumber();
        verifyNoInteractions(metrics);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(requestCall);
    }

    @Test
    void testConnectionWorkerLoopTask_doWork_sendExceptionalFailure() throws Exception {
        activateConnection();

        final Object worker = createWorker();
        final AtomicReference<PublishStreamRequestBytes> eosRequestRef = new AtomicReference<>();

        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(0L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(11L);
        when(bufferService.getHighestAckedBlockNumber()).thenReturn(10L);
        doAnswer(inv -> {
                    final PublishStreamRequestBytes req = inv.getArgument(0);

                    if (req.hasAcknowledgeOnly()) {
                        // only fail the AcknowledgeOnly request
                        throw new RuntimeException("boom!");
                    } else if (req.hasEndStream()) {
                        eosRequestRef.set(req);
                    }

                    return null;
                })
                .when(requestCall)
                .sendRequest(any(PublishStreamRequestBytes.class), anyBoolean());

        invokeDoWork(worker);

        assertThat(eosRequestRef).doesNotHaveNullValue();
        final PublishStreamRequestBytes eosRequest = eosRequestRef.get();
        assertThat(eosRequest.hasEndStream()).isTrue();
        final EndStreamBytes endStream = eosRequest.endStream();
        assertThat(endStream.earliestBlockNumber()).isEqualTo(0L);
        assertThat(endStream.latestBlockNumber()).isEqualTo(10L);
        assertThat(endStream.endCode()).isEqualTo(EndStream.Code.ERROR);

        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getLastBlockNumberProduced();
        verify(bufferService, times(2)).getHighestAckedBlockNumber();
        verify(metrics).recordRequestSendFailure();
        verify(metrics).recordRequestEndStreamSent(EndStream.Code.ERROR);
        verify(metrics).recordConnectionClosed(CloseReason.CONNECTION_ERROR);
        verify(metrics).recordRequestLatency(anyLong());
        verify(requestCall, times(2)).sendRequest(any(PublishStreamRequestBytes.class), anyBoolean());
        verify(publishBytesClient).close();
        verify(blockingIoExecutor).shutdown();
        verify(connectionManager).notifyConnectionClosed(connection);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(requestCall);
    }

    @Test
    void testConnectionWorkerLoopTask_doWork_sendFailedNonExceptionally() throws Exception {
        // don't activate the connection so when we invoke doWork, we will try to send a request and it will fail
        // because the connection isn't active
        final Object worker = createWorker();

        when(bufferService.getLastBlockNumberProduced()).thenReturn(11L);
        when(bufferService.getHighestAckedBlockNumber()).thenReturn(10L);

        invokeDoWork(worker);

        verify(bufferService).getLastBlockNumberProduced();
        verify(bufferService).getHighestAckedBlockNumber();
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(metrics);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(requestCall);
    }

    @Test
    void testConnectionWorkerLoopTask_doWork() throws Exception {
        activateConnection();

        final Object worker = createWorker();
        final AtomicReference<PublishStreamRequestBytes> ackOnlyRequest = new AtomicReference<>();

        when(bufferService.getLastBlockNumberProduced()).thenReturn(11L);
        when(bufferService.getHighestAckedBlockNumber()).thenReturn(10L);
        doAnswer(inv -> {
                    final PublishStreamRequestBytes req = inv.getArgument(0);
                    if (req.hasAcknowledgeOnly()) {
                        ackOnlyRequest.set(req);
                    }

                    return null;
                })
                .when(requestCall)
                .sendRequest(any(PublishStreamRequestBytes.class), anyBoolean());

        invokeDoWork(worker);

        assertThat(ackOnlyRequest).doesNotHaveNullValue();
        final PublishStreamRequestBytes request = ackOnlyRequest.get();
        assertThat(request.hasAcknowledgeOnly()).isTrue();
        final AcknowledgeOnlyBytes ackOnly = request.acknowledgeOnly();
        assertThat(ackOnly.blockNumber()).isEqualTo(11L);

        verify(bufferService).getLastBlockNumberProduced();
        verify(bufferService).getHighestAckedBlockNumber();
        verify(metrics).recordRequestLatency(anyLong());
        verify(metrics).recordRequestSent(RequestOneOfType.ACKNOWLEDGE_ONLY);
        verify(requestCall).sendRequest(any(PublishStreamRequestBytes.class), anyBoolean());
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(requestCall);
    }

    @Test
    void testConnectionWorkerLoopTask_multipleRequests() throws Exception {
        activateConnection();

        final Object worker = createWorker();
        final AtomicInteger numAckOnlyRequests = new AtomicInteger();
        final AtomicLong lastBlockProduced = new AtomicLong(11L);
        final AtomicLong highestAckedBlock = new AtomicLong(10L);

        when(bufferService.getLastBlockNumberProduced()).thenReturn(11L);
        when(bufferService.getHighestAckedBlockNumber()).thenReturn(10L);
        doAnswer(inv -> {
                    final PublishStreamRequestBytes req = inv.getArgument(0);
                    if (req.hasAcknowledgeOnly()) {
                        // record the request being sent
                        numAckOnlyRequests.incrementAndGet();
                        // increment the buffer state so the next time we are ready to send again, we have a new block
                        // available
                        lastBlockProduced.incrementAndGet();
                        highestAckedBlock.incrementAndGet();
                    }

                    return null;
                })
                .when(requestCall)
                .sendRequest(any(PublishStreamRequestBytes.class), anyBoolean());

        /*
        We only permit sending a new acknowledgement request at an interval that matches the block period - which is
        configured as 2 seconds. Therefore, if we start a loop that constantly invokes doWork for a little over 2
        seconds, we should get at least 2 requests sent, with no more than 3 are expected.
         */

        final long startMillis = System.currentTimeMillis();

        for (long elapsedMillis = 0; elapsedMillis <= 3_000; ) {
            invokeDoWork(worker);

            Thread.sleep(100); // sleep a little so we aren't burning CPU cycles fanatically

            elapsedMillis = System.currentTimeMillis() - startMillis;
        }

        assertThat(numAckOnlyRequests).hasValueBetween(2, 3);

        verify(bufferService, times(numAckOnlyRequests.get())).getLastBlockNumberProduced();
        verify(bufferService, times(numAckOnlyRequests.get())).getHighestAckedBlockNumber();
        verify(metrics, times(numAckOnlyRequests.get())).recordRequestLatency(anyLong());
        verify(metrics, times(numAckOnlyRequests.get())).recordRequestSent(RequestOneOfType.ACKNOWLEDGE_ONLY);
        verify(requestCall, times(numAckOnlyRequests.get()))
                .sendRequest(any(PublishStreamRequestBytes.class), anyBoolean());
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(requestCall);
    }

    // ==== Utilities

    private Object createWorker() throws Exception {
        Class<?> workerClass = null;
        for (final Class<?> innerClass : BlockNodeObservingConnection.class.getDeclaredClasses()) {
            if (innerClass.getSimpleName().equals("ConnectionWorkerLoopTask")) {
                workerClass = innerClass;
                break;
            }
        }
        if (workerClass == null) {
            throw new IllegalStateException("ConnectionWorkerLoopTask inner class not found");
        }

        final Constructor<?> constructor = workerClass.getDeclaredConstructor(BlockNodeObservingConnection.class);
        constructor.setAccessible(true);
        return constructor.newInstance(connection);
    }

    private void invokeDoWork(final Object worker) throws Exception {
        final Method doWorkMethod = worker.getClass().getDeclaredMethod("doWork");
        doWorkMethod.setAccessible(true);
        doWorkMethod.invoke(worker);
    }

    private void invokeRun(final Object worker) throws Exception {
        final Method runMethod = worker.getClass().getDeclaredMethod("run");
        runMethod.setAccessible(true);
        runMethod.invoke(worker);
    }

    private void activateConnection() {
        connection.initialize();
        connection.updateConnectionState(ConnectionState.ACTIVE);
        resetMocks();
    }

    private void resetMocks() {
        reset(connectionManager, requestCall, bufferService, metrics);
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<ConnectionState> connectionState() {
        return (AtomicReference<ConnectionState>) connectionStateHandle.get(connection);
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<Thread> workerThreadRef() {
        return (AtomicReference<Thread>) workerThreadRefHandle.get(connection);
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
}
