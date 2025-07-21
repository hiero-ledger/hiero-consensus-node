// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnection.ConnectionState;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.internal.network.BlockNodeConfig;
import com.hedera.pbj.runtime.OneOf;
import io.grpc.stub.StreamObserver;
import io.helidon.webclient.grpc.GrpcServiceClient;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.time.Instant;
import java.util.Queue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.hiero.block.api.PublishStreamRequest;
import org.hiero.block.api.PublishStreamResponse;
import org.hiero.block.api.PublishStreamResponse.EndOfStream;
import org.hiero.block.api.PublishStreamResponse.EndOfStream.Code;
import org.hiero.block.api.PublishStreamResponse.ResponseOneOfType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockNodeConnectionTest extends BlockNodeCommunicationTestBase {
    private static final long ONCE_PER_DAY_MILLIS = Duration.ofHours(24).toMillis();
    private static final VarHandle eosTimestampsHandle;

    static {
        try {
            final Lookup lookup = MethodHandles.lookup();
            eosTimestampsHandle = MethodHandles.privateLookupIn(BlockNodeConnection.class, lookup)
                    .findVarHandle(BlockNodeConnection.class, "endOfStreamTimestamps", Queue.class);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BlockNodeConnection connection;

    private BlockNodeConnectionManager connectionManager;
    private BlockBufferService stateManager;
    private GrpcServiceClient grpcServiceClient;
    private BlockStreamMetrics metrics;
    private final String grpcEndpoint = "foo";
    private StreamObserver<PublishStreamRequest> requestObserver;
    private ScheduledExecutorService executorService;

    @BeforeEach
    void beforeEach() {
        final ConfigProvider configProvider = createConfigProvider();
        final BlockNodeConfig nodeConfig = new BlockNodeConfig("localhost", 8080, 1);
        connectionManager = mock(BlockNodeConnectionManager.class);
        stateManager = mock(BlockBufferService.class);
        grpcServiceClient = mock(GrpcServiceClient.class);
        metrics = mock(BlockStreamMetrics.class);
        requestObserver = mock(StreamObserver.class);
        executorService = mock(ScheduledExecutorService.class);

        connection = new BlockNodeConnection(
                configProvider,
                nodeConfig,
                connectionManager,
                stateManager,
                grpcServiceClient,
                metrics,
                grpcEndpoint,
                executorService);

        lenient().doReturn(requestObserver).when(grpcServiceClient).bidi(grpcEndpoint, connection);
    }

    @Test
    void testCreateRequestObserver() {
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.UNINITIALIZED);
        connection.createRequestObserver();

        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.PENDING);
        verify(grpcServiceClient).bidi(grpcEndpoint, connection);
    }

    @Test
    void testCreateRequestObserver_alreadyExists() {
        connection.createRequestObserver();
        connection.createRequestObserver();

        verify(grpcServiceClient).bidi(grpcEndpoint, connection); // should only be called once
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
        assertThat(postState).isEqualTo(ConnectionState.UNINITIALIZED);

        verify(requestObserver).onCompleted();
        verify(connectionManager).rescheduleAndSelectNewNode(connection, Duration.ofSeconds(30));
        verify(connectionManager).jumpToBlock(-1L);
        verifyNoMoreInteractions(requestObserver);
        verifyNoMoreInteractions(connectionManager);
    }

    @Test
    void testOnNext_acknowledgement_notStreaming() {
        final PublishStreamResponse response = createBlockAckResponse(10L, false);
        when(connectionManager.currentStreamingBlockNumber())
                .thenReturn(-1L); // we aren't streaming anything to the block node
        when(stateManager.getLastBlockNumberProduced()).thenReturn(10L);

        connection.onNext(response);

        verify(connectionManager).currentStreamingBlockNumber();
        verify(stateManager).getLastBlockNumberProduced();
        verify(connectionManager).updateLastVerifiedBlock(connection.getNodeConfig(), 10L);
        verify(metrics).incrementAcknowledgedBlockCount();
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(stateManager);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testOnNext_acknowledgement_olderThanCurrentStreamingAndProducing() {
        final PublishStreamResponse response = createBlockAckResponse(8L, false);

        when(connectionManager.currentStreamingBlockNumber()).thenReturn(10L);
        when(stateManager.getLastBlockNumberProduced()).thenReturn(10L);

        connection.onNext(response);

        verify(connectionManager).currentStreamingBlockNumber();
        verify(stateManager).getLastBlockNumberProduced();

        verify(connectionManager).updateLastVerifiedBlock(connection.getNodeConfig(), 8L);
        verify(metrics).incrementAcknowledgedBlockCount();
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(stateManager);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testOnNext_acknowledgement_newerThanCurrentProducing() {
        // I don't think this scenario is possible... we should never stream a block that is newer than the block
        // currently being produced.
        final PublishStreamResponse response = createBlockAckResponse(11L, false);

        when(connectionManager.currentStreamingBlockNumber()).thenReturn(11L);
        when(stateManager.getLastBlockNumberProduced()).thenReturn(10L);

        connection.onNext(response);

        verify(connectionManager).currentStreamingBlockNumber();
        verify(stateManager).getLastBlockNumberProduced();
        verify(connectionManager).updateLastVerifiedBlock(connection.getNodeConfig(), 11L);
        verify(connectionManager).jumpToBlock(12L);
        verify(metrics).incrementAcknowledgedBlockCount();
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(stateManager);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testOnNext_acknowledgement_newerThanCurrentStreaming() {
        final PublishStreamResponse response = createBlockAckResponse(11L, false);

        when(connectionManager.currentStreamingBlockNumber()).thenReturn(10L);
        when(stateManager.getLastBlockNumberProduced()).thenReturn(12L);

        connection.onNext(response);

        verify(connectionManager).currentStreamingBlockNumber();
        verify(stateManager).getLastBlockNumberProduced();
        verify(connectionManager).updateLastVerifiedBlock(connection.getNodeConfig(), 11L);
        verify(connectionManager).jumpToBlock(12L);
        verify(metrics).incrementAcknowledgedBlockCount();
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(stateManager);
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
        verifyNoInteractions(stateManager);
    }

    @Test
    void testOnNext_endOfStream_exceededMaxPermitted() {
        openConnectionAndResetMocks();
        final PublishStreamResponse response = createEndOfStreamResponse(Code.BEHIND, 10L);

        // populate the end of stream timestamp queue with some data so the next call exceeds the max allowed
        // the queue assumes chronological ordering, so make sure the oldest are added first
        final Queue<Instant> eosTimestamps = (Queue<Instant>) eosTimestampsHandle.get(connection);
        final Instant now = Instant.now();
        eosTimestamps.add(now.minusSeconds(5));
        eosTimestamps.add(now.minusSeconds(4));
        eosTimestamps.add(now.minusSeconds(3));
        eosTimestamps.add(now.minusSeconds(2));
        eosTimestamps.add(now.minusSeconds(1));

        connection.onNext(response);

        assertThat(eosTimestamps).hasSize(6);

        verify(metrics).incrementEndOfStreamCount(Code.BEHIND);
        verify(requestObserver).onCompleted();
        verify(connectionManager).jumpToBlock(-1L);
        verify(connectionManager).rescheduleAndSelectNewNode(eq(connection), any(Duration.class));
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestObserver);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(stateManager);
    }

    @ParameterizedTest
    @EnumSource(
            value = EndOfStream.Code.class,
            names = {"INTERNAL_ERROR", "PERSISTENCE_FAILED"})
    void testOnNext_endOfStream_blockNodeInternalError(final EndOfStream.Code responseCode) {
        openConnectionAndResetMocks();
        final PublishStreamResponse response = createEndOfStreamResponse(responseCode, 10L);

        connection.onNext(response);

        verify(metrics).incrementEndOfStreamCount(responseCode);
        verify(requestObserver).onCompleted();
        verify(connectionManager).jumpToBlock(-1L);
        verify(connectionManager).rescheduleAndSelectNewNode(connection, Duration.ofSeconds(30));
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestObserver);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(stateManager);
    }

    @ParameterizedTest
    @EnumSource(
            value = EndOfStream.Code.class,
            names = {"TIMEOUT", "DUPLICATE_BLOCK", "BAD_BLOCK_PROOF"})
    void testOnNext_endOfStream_clientFailures(final EndOfStream.Code responseCode) {
        openConnectionAndResetMocks();
        final PublishStreamResponse response = createEndOfStreamResponse(responseCode, 10L);

        connection.onNext(response);

        verify(metrics).incrementEndOfStreamCount(responseCode);
        verify(requestObserver).onCompleted();
        verify(connectionManager).jumpToBlock(-1L);
        verify(connectionManager).scheduleConnectionAttempt(connection, Duration.ofSeconds(1), 11L);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestObserver);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(stateManager);
    }

    @Test
    void testOnNext_endOfStream_blockNodeGracefulShutdown() {
        openConnectionAndResetMocks();
        // STREAM_ITEMS_SUCCESS is sent when the block node is gracefully shutting down
        final PublishStreamResponse response = createEndOfStreamResponse(Code.SUCCESS, 10L);

        connection.onNext(response);

        verify(metrics).incrementEndOfStreamCount(Code.SUCCESS);
        verify(requestObserver).onCompleted();
        verify(connectionManager).jumpToBlock(-1L);
        verify(connectionManager).rescheduleAndSelectNewNode(connection, Duration.ofSeconds(30));
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestObserver);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(stateManager);
    }

    @Test
    void testOnNext_endOfStream_blockNodeBehind_blockExists() {
        openConnectionAndResetMocks();
        final PublishStreamResponse response = createEndOfStreamResponse(Code.BEHIND, 10L);
        when(stateManager.getBlockState(11L)).thenReturn(new BlockState(11L));

        connection.onNext(response);

        verify(metrics).incrementEndOfStreamCount(Code.BEHIND);
        verify(requestObserver).onCompleted();
        verify(connectionManager).jumpToBlock(-1L);
        verify(connectionManager).scheduleConnectionAttempt(connection, Duration.ofSeconds(1), 11L);
        verify(stateManager).getBlockState(11L);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestObserver);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(stateManager);
    }

    @Test
    void testOnNext_endOfStream_blockNodeBehind_blockDoesNotExist() {
        openConnectionAndResetMocks();
        final PublishStreamResponse response = createEndOfStreamResponse(Code.BEHIND, 10L);
        when(stateManager.getBlockState(11L)).thenReturn(null);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        connection.onNext(response);

        verify(metrics).incrementEndOfStreamCount(Code.BEHIND);
        verify(stateManager, times(1)).getEarliestAvailableBlockNumber();
        verify(stateManager, times(1)).getHighestAckedBlockNumber();
        verify(connectionManager).rescheduleAndSelectNewNode(connection, Duration.ofSeconds(30));
        verify(stateManager).getBlockState(11L);
        verify(requestObserver)
                .onNext(PublishStreamRequest.newBuilder()
                        .endStream(PublishStreamRequest.EndStream.newBuilder()
                                .endCode(PublishStreamRequest.EndStream.Code.TOO_FAR_BEHIND)
                                .build())
                        .build());
        verify(requestObserver).onCompleted();
        verify(connectionManager).jumpToBlock(-1L);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestObserver);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(stateManager);
    }

    @Test
    void testOnNext_endOfStream_itemsUnknown() {
        openConnectionAndResetMocks();
        final PublishStreamResponse response = createEndOfStreamResponse(Code.UNKNOWN, 10L);

        connection.onNext(response);

        verify(metrics).incrementEndOfStreamCount(Code.UNKNOWN);
        verify(requestObserver).onCompleted();
        verify(connectionManager).jumpToBlock(-1L);
        verify(connectionManager).rescheduleAndSelectNewNode(connection, Duration.ofSeconds(30));
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestObserver);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(stateManager);
    }

    @Test
    void testOnNext_skipBlock_sameAsStreaming() {
        final PublishStreamResponse response = createSkipBlock(25L);
        when(connectionManager.currentStreamingBlockNumber()).thenReturn(25L);

        connection.onNext(response);

        verify(metrics).incrementSkipBlockCount();
        verify(connectionManager).jumpToBlock(26L); // jump to the response block number + 1
        verify(connectionManager).currentStreamingBlockNumber();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestObserver);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(stateManager);
    }

    @Test
    void testOnNext_skipBlock_notSameAsStreaming() {
        final PublishStreamResponse response = createSkipBlock(25L);
        when(connectionManager.currentStreamingBlockNumber()).thenReturn(26L);

        connection.onNext(response);

        verify(metrics).incrementSkipBlockCount();
        verify(connectionManager).currentStreamingBlockNumber();
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestObserver);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(stateManager);
    }

    @Test
    void testOnNext_resendBlock_blockExists() {
        final PublishStreamResponse response = createResendBlock(10L);
        when(stateManager.getBlockState(10L)).thenReturn(new BlockState(10L));

        connection.onNext(response);

        verify(metrics).incrementResendBlockCount();
        verify(connectionManager).jumpToBlock(10L);
        verify(stateManager).getBlockState(10L);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestObserver);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(stateManager);
    }

    @Test
    void testOnNext_resendBlock_blockDoesNotExist() {
        openConnectionAndResetMocks();

        final PublishStreamResponse response = createResendBlock(10L);
        when(stateManager.getBlockState(10L)).thenReturn(null);

        connection.onNext(response);

        verify(metrics).incrementResendBlockCount();
        verify(requestObserver).onCompleted();
        verify(connectionManager).jumpToBlock(-1L);
        verify(connectionManager).rescheduleAndSelectNewNode(connection, Duration.ofSeconds(30));
        verify(stateManager).getBlockState(10L);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestObserver);
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(stateManager);
    }

    @Test
    void testOnNext_unknown() {
        final PublishStreamResponse response = new PublishStreamResponse(new OneOf<>(ResponseOneOfType.UNSET, null));

        connection.onNext(response);

        verify(metrics).incrementUnknownResponseCount();

        verifyNoMoreInteractions(metrics);
        verifyNoInteractions(requestObserver);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(stateManager);
    }

    @Test
    void testSendRequest() {
        openConnectionAndResetMocks();

        final BlockHeader blockHeader = BlockHeader.newBuilder().number(1L).build();
        final BlockItem item = BlockItem.newBuilder().blockHeader(blockHeader).build();
        final PublishStreamRequest request = createRequest(item);

        connection.updateConnectionState(ConnectionState.ACTIVE);
        connection.sendRequest(request);

        verify(requestObserver).onNext(request);
        verifyNoInteractions(metrics);
        verifyNoMoreInteractions(requestObserver);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(stateManager);
    }

    @Test
    void testSendRequest_notActive() {
        final BlockHeader blockHeader = BlockHeader.newBuilder().number(1L).build();
        final BlockItem item = BlockItem.newBuilder().blockHeader(blockHeader).build();
        final PublishStreamRequest request = createRequest(item);

        connection.createRequestObserver();
        connection.updateConnectionState(ConnectionState.PENDING);
        connection.sendRequest(request);

        verifyNoInteractions(metrics);
        verifyNoMoreInteractions(requestObserver);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(stateManager);
    }

    @Test
    void testSendRequest_observerNull() {
        final BlockHeader blockHeader = BlockHeader.newBuilder().number(1L).build();
        final BlockItem item = BlockItem.newBuilder().blockHeader(blockHeader).build();
        final PublishStreamRequest request = createRequest(item);

        // don't create the observer
        connection.updateConnectionState(ConnectionState.PENDING);
        connection.sendRequest(request);

        verifyNoInteractions(metrics);
        verifyNoMoreInteractions(requestObserver);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(stateManager);
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

        connection.close();

        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.UNINITIALIZED);

        verify(connectionManager).jumpToBlock(-1L);
        verify(requestObserver).onCompleted();
        verifyNoInteractions(metrics);
        verifyNoMoreInteractions(requestObserver);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(stateManager);
    }

    @Test
    void testClose_failure() {
        openConnectionAndResetMocks();
        doThrow(new RuntimeException("oh no!")).when(requestObserver).onCompleted();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        // Verify task was scheduled to periodically reset the stream
        verify(executorService)
                .scheduleAtFixedRate(
                        any(Runnable.class),
                        eq(ONCE_PER_DAY_MILLIS), // initial delay
                        eq(ONCE_PER_DAY_MILLIS), // period
                        eq(TimeUnit.MILLISECONDS));

        connection.close();

        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.UNINITIALIZED);

        verify(connectionManager).jumpToBlock(-1L);
        verify(requestObserver).onCompleted();
        verifyNoInteractions(metrics);
        verifyNoMoreInteractions(requestObserver);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(stateManager);
    }

    @Test
    void testOnError() {
        openConnectionAndResetMocks();

        connection.onError(new RuntimeException("oh bother"));

        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.UNINITIALIZED);

        verify(metrics).incrementOnErrorCount();

        verify(connectionManager).jumpToBlock(-1L);
        verify(requestObserver).onCompleted();
        verify(connectionManager).rescheduleAndSelectNewNode(connection, Duration.ofSeconds(30));
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestObserver);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(stateManager);
    }

    @Test
    void testOnCompleted_streamClosingInProgress() {
        openConnectionAndResetMocks();
        connection.close(); // call this so we mark the connection as closing
        resetMocks();

        connection.onCompleted();

        verifyNoInteractions(metrics);
        verifyNoInteractions(requestObserver);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(stateManager);
    }

    @Test
    void testOnCompleted_streamClosingNotInProgress() {
        openConnectionAndResetMocks();
        // don't call close so we do not mark the connection as closing
        connection.onCompleted();

        verify(connectionManager).jumpToBlock(-1L);
        verify(requestObserver).onCompleted();
        verify(connectionManager).rescheduleAndSelectNewNode(connection, Duration.ofSeconds(30));
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(requestObserver);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(stateManager);
    }

    // Utilities

    private void openConnectionAndResetMocks() {
        connection.createRequestObserver();
        // reset the mocks interactions to remove tracked interactions as a result of starting the connection
        resetMocks();
    }

    private void resetMocks() {
        reset(connectionManager, requestObserver, stateManager, metrics);
    }
}
