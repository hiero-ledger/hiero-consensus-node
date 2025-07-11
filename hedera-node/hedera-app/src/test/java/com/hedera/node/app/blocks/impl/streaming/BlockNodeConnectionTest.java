// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnection.ConnectionState;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.internal.network.BlockNodeConfig;
import io.grpc.ManagedChannel;
import io.grpc.stub.ClientCallStreamObserver;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.time.Instant;
import java.util.Queue;
import org.hiero.block.api.protoc.BlockStreamPublishServiceGrpc;
import org.hiero.block.api.protoc.PublishStreamRequest;
import org.hiero.block.api.protoc.PublishStreamResponse;
import org.hiero.block.api.protoc.PublishStreamResponse.EndOfStream;
import org.hiero.block.api.protoc.PublishStreamResponse.EndOfStream.Code;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockNodeConnectionTest extends BlockNodeCommunicationTestBase {

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
    private ManagedChannel managedChannel;
    private BlockStreamMetrics metrics;
    private final String grpcEndpoint = "foo";

    @BeforeEach
    void beforeEach() {
        final ConfigProvider configProvider = createConfigProvider();
        final BlockNodeConfig nodeConfig = new BlockNodeConfig("localhost", 8080, 1);
        connectionManager = mock(BlockNodeConnectionManager.class);
        stateManager = mock(BlockBufferService.class);
        managedChannel = mock(ManagedChannel.class);
        metrics = mock(BlockStreamMetrics.class);

        connection = new BlockNodeConnection(
                configProvider, nodeConfig, connectionManager, stateManager, managedChannel, metrics, grpcEndpoint);
    }

    @Test
    void testCreateRequestObserver() {
        try (MockedStatic<BlockStreamPublishServiceGrpc> mockedStatic =
                Mockito.mockStatic(BlockStreamPublishServiceGrpc.class)) {
            // Mock the static method
            BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub mockStub =
                    mock(BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub.class);
            mockedStatic
                    .when(() -> BlockStreamPublishServiceGrpc.newStub(managedChannel))
                    .thenReturn(mockStub);

            // Mock the behavior of the stub's publishBlockStream method
            ClientCallStreamObserver<PublishStreamRequest> mockObserver = mock(ClientCallStreamObserver.class);
            when(mockStub.publishBlockStream(any())).thenReturn(mockObserver);

            assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.UNINITIALIZED);
            connection.createRequestObserver();

            assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.PENDING);
            verify(mockStub).publishBlockStream(any());
        }
    }

    @Test
    void testCreateRequestObserver_alreadyExists() {
        try (MockedStatic<BlockStreamPublishServiceGrpc> mockedStatic =
                Mockito.mockStatic(BlockStreamPublishServiceGrpc.class)) {
            // Mock the static method
            BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub mockStub =
                    mock(BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub.class);
            mockedStatic
                    .when(() -> BlockStreamPublishServiceGrpc.newStub(managedChannel))
                    .thenReturn(mockStub);

            // Mock the behavior of the stub's publishBlockStream method
            ClientCallStreamObserver<org.hiero.block.api.protoc.PublishStreamRequest> mockObserver =
                    mock(ClientCallStreamObserver.class);
            when(mockStub.publishBlockStream(any())).thenReturn(mockObserver);
            connection.createRequestObserver();
            connection.createRequestObserver();

            verify(mockStub).publishBlockStream(any()); // should only be called once
            verifyNoMoreInteractions(mockStub);
        }
    }

    @Test
    void testUpdatingConnectionState() {
        final ConnectionState preUpdateState = connection.getConnectionState();
        // this should be uninitialized because we haven't called connect yet
        assertThat(preUpdateState).isEqualTo(ConnectionState.UNINITIALIZED);
        connection.updateConnectionState(ConnectionState.ACTIVE);

        final ConnectionState postUpdateState = connection.getConnectionState();
        assertThat(postUpdateState).isEqualTo(ConnectionState.ACTIVE);
    }

    @Test
    void testHandleStreamError() {
        try (MockedStatic<BlockStreamPublishServiceGrpc> mockedStatic =
                Mockito.mockStatic(BlockStreamPublishServiceGrpc.class)) {
            // Mock the static method
            BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub mockStub =
                    mock(BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub.class);
            mockedStatic
                    .when(() -> BlockStreamPublishServiceGrpc.newStub(managedChannel))
                    .thenReturn(mockStub);

            // Mock the behavior of the stub's publishBlockStream method
            ClientCallStreamObserver<PublishStreamRequest> mockObserver = mock(ClientCallStreamObserver.class);
            when(mockStub.publishBlockStream(any())).thenReturn(mockObserver);
            openConnectionAndResetMocks();
            connection.updateConnectionState(ConnectionState.ACTIVE);
            // do a quick sanity check on the state
            final ConnectionState preState = connection.getConnectionState();
            assertThat(preState).isEqualTo(ConnectionState.ACTIVE);

            connection.handleStreamFailure();

            final ConnectionState postState = connection.getConnectionState();
            assertThat(postState).isEqualTo(ConnectionState.UNINITIALIZED);

            verify(mockObserver).onCompleted();
            verify(connectionManager).rescheduleAndSelectNewNode(connection, Duration.ofSeconds(30));
            verify(connectionManager).jumpToBlock(-1L);
            verifyNoMoreInteractions(mockObserver);
            verifyNoMoreInteractions(connectionManager);
        }
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
    void testOnNext_endOfStream_exceededMaxPermitted() {
        try (MockedStatic<BlockStreamPublishServiceGrpc> mockedStatic =
                Mockito.mockStatic(BlockStreamPublishServiceGrpc.class)) {
            // Mock the static method
            BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub mockStub =
                    mock(BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub.class);
            mockedStatic
                    .when(() -> BlockStreamPublishServiceGrpc.newStub(managedChannel))
                    .thenReturn(mockStub);

            // Mock the behavior of the stub's publishBlockStream method
            ClientCallStreamObserver<PublishStreamRequest> mockObserver = mock(ClientCallStreamObserver.class);
            when(mockStub.publishBlockStream(any())).thenReturn(mockObserver);
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

            verify(metrics)
                    .incrementEndOfStreamCount(org.hiero.block.api.PublishStreamResponse.EndOfStream.Code.BEHIND);
            verify(mockObserver).onCompleted();
            verify(connectionManager).jumpToBlock(-1L);
            verify(connectionManager).rescheduleAndSelectNewNode(eq(connection), any(Duration.class));
            verifyNoMoreInteractions(metrics);
            verifyNoMoreInteractions(mockObserver);
            verifyNoMoreInteractions(connectionManager);
            verifyNoInteractions(stateManager);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = EndOfStream.Code.class,
            names = {"INTERNAL_ERROR", "PERSISTENCE_FAILED"})
    void testOnNext_endOfStream_blockNodeInternalError(final EndOfStream.Code responseCode) {
        try (MockedStatic<BlockStreamPublishServiceGrpc> mockedStatic =
                Mockito.mockStatic(BlockStreamPublishServiceGrpc.class)) {
            // Mock the static method
            BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub mockStub =
                    mock(BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub.class);
            mockedStatic
                    .when(() -> BlockStreamPublishServiceGrpc.newStub(managedChannel))
                    .thenReturn(mockStub);

            // Mock the behavior of the stub's publishBlockStream method
            ClientCallStreamObserver<PublishStreamRequest> mockObserver = mock(ClientCallStreamObserver.class);
            when(mockStub.publishBlockStream(any())).thenReturn(mockObserver);
            openConnectionAndResetMocks();
            final PublishStreamResponse response = createEndOfStreamResponse(responseCode, 10L);

            connection.onNext(response);

            verify(metrics)
                    .incrementEndOfStreamCount(
                            org.hiero.block.api.PublishStreamResponse.EndOfStream.Code.fromProtobufOrdinal(
                                    responseCode.ordinal()));
            verify(mockObserver).onCompleted();
            verify(connectionManager).jumpToBlock(-1L);
            verify(connectionManager).rescheduleAndSelectNewNode(connection, Duration.ofSeconds(30));
            verifyNoMoreInteractions(metrics);
            verifyNoMoreInteractions(mockObserver);
            verifyNoMoreInteractions(connectionManager);
            verifyNoInteractions(stateManager);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = EndOfStream.Code.class,
            names = {"TIMEOUT", "DUPLICATE_BLOCK", "BAD_BLOCK_PROOF"})
    void testOnNext_endOfStream_clientFailures(final EndOfStream.Code responseCode) {
        try (MockedStatic<BlockStreamPublishServiceGrpc> mockedStatic =
                Mockito.mockStatic(BlockStreamPublishServiceGrpc.class)) {
            // Mock the static method
            BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub mockStub =
                    mock(BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub.class);
            mockedStatic
                    .when(() -> BlockStreamPublishServiceGrpc.newStub(managedChannel))
                    .thenReturn(mockStub);

            // Mock the behavior of the stub's publishBlockStream method
            ClientCallStreamObserver<PublishStreamRequest> mockObserver = mock(ClientCallStreamObserver.class);
            when(mockStub.publishBlockStream(any())).thenReturn(mockObserver);
            openConnectionAndResetMocks();
            final PublishStreamResponse response = createEndOfStreamResponse(responseCode, 10L);

            connection.onNext(response);

            verify(metrics)
                    .incrementEndOfStreamCount(
                            org.hiero.block.api.PublishStreamResponse.EndOfStream.Code.fromProtobufOrdinal(
                                    responseCode.ordinal()));
            verify(mockObserver).onCompleted();
            verify(connectionManager).jumpToBlock(-1L);
            verify(connectionManager).scheduleConnectionAttempt(connection, Duration.ofSeconds(1), 11L);
            verifyNoMoreInteractions(metrics);
            verifyNoMoreInteractions(mockObserver);
            verifyNoMoreInteractions(connectionManager);
            verifyNoInteractions(stateManager);
        }
    }

    @Test
    void testOnNext_endOfStream_blockNodeGracefulShutdown() {
        try (MockedStatic<BlockStreamPublishServiceGrpc> mockedStatic =
                Mockito.mockStatic(BlockStreamPublishServiceGrpc.class)) {
            // Mock the static method
            BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub mockStub =
                    mock(BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub.class);
            mockedStatic
                    .when(() -> BlockStreamPublishServiceGrpc.newStub(managedChannel))
                    .thenReturn(mockStub);

            // Mock the behavior of the stub's publishBlockStream method
            ClientCallStreamObserver<PublishStreamRequest> mockObserver = mock(ClientCallStreamObserver.class);
            when(mockStub.publishBlockStream(any())).thenReturn(mockObserver);
            openConnectionAndResetMocks();
            // STREAM_ITEMS_SUCCESS is sent when the block node is gracefully shutting down
            final PublishStreamResponse response = createEndOfStreamResponse(Code.SUCCESS, 10L);

            connection.onNext(response);

            verify(metrics)
                    .incrementEndOfStreamCount(org.hiero.block.api.PublishStreamResponse.EndOfStream.Code.SUCCESS);
            verify(mockObserver).onCompleted();
            verify(connectionManager).jumpToBlock(-1L);
            verify(connectionManager).rescheduleAndSelectNewNode(connection, Duration.ofSeconds(30));
            verifyNoMoreInteractions(metrics);
            verifyNoMoreInteractions(mockObserver);
            verifyNoMoreInteractions(connectionManager);
            verifyNoInteractions(stateManager);
        }
    }

    @Test
    void testOnNext_endOfStream_blockNodeBehind_blockExists() {
        try (MockedStatic<BlockStreamPublishServiceGrpc> mockedStatic =
                Mockito.mockStatic(BlockStreamPublishServiceGrpc.class)) {
            // Mock the static method
            BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub mockStub =
                    mock(BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub.class);
            mockedStatic
                    .when(() -> BlockStreamPublishServiceGrpc.newStub(managedChannel))
                    .thenReturn(mockStub);

            // Mock the behavior of the stub's publishBlockStream method
            ClientCallStreamObserver<PublishStreamRequest> mockObserver = mock(ClientCallStreamObserver.class);
            when(mockStub.publishBlockStream(any())).thenReturn(mockObserver);
            openConnectionAndResetMocks();
            final PublishStreamResponse response = createEndOfStreamResponse(Code.BEHIND, 10L);
            when(stateManager.getBlockState(11L)).thenReturn(new BlockState(11L));

            connection.onNext(response);

            verify(metrics)
                    .incrementEndOfStreamCount(org.hiero.block.api.PublishStreamResponse.EndOfStream.Code.BEHIND);
            verify(mockObserver).onCompleted();
            verify(connectionManager).jumpToBlock(-1L);
            verify(connectionManager).scheduleConnectionAttempt(connection, Duration.ofSeconds(1), 11L);
            verify(stateManager).getBlockState(11L);
            verifyNoMoreInteractions(metrics);
            verifyNoMoreInteractions(mockObserver);
            verifyNoMoreInteractions(connectionManager);
            verifyNoMoreInteractions(stateManager);
        }
    }

    @Test
    void testOnNext_endOfStream_blockNodeBehind_blockDoesNotExist() {
        try (MockedStatic<BlockStreamPublishServiceGrpc> mockedStatic =
                Mockito.mockStatic(BlockStreamPublishServiceGrpc.class)) {
            // Mock the static method
            BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub mockStub =
                    mock(BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub.class);
            mockedStatic
                    .when(() -> BlockStreamPublishServiceGrpc.newStub(managedChannel))
                    .thenReturn(mockStub);

            // Mock the behavior of the stub's publishBlockStream method
            ClientCallStreamObserver<PublishStreamRequest> mockObserver = mock(ClientCallStreamObserver.class);
            when(mockStub.publishBlockStream(any())).thenReturn(mockObserver);
            when(mockObserver.isReady()).thenReturn(true);
            openConnectionAndResetMocks();
            final PublishStreamResponse response = createEndOfStreamResponse(Code.BEHIND, 10L);
            when(stateManager.getBlockState(11L)).thenReturn(null);

            connection.updateConnectionState(ConnectionState.ACTIVE);
            connection.onNext(response);

            verify(metrics)
                    .incrementEndOfStreamCount(org.hiero.block.api.PublishStreamResponse.EndOfStream.Code.BEHIND);
            verify(stateManager, times(1)).getEarliestAvailableBlockNumber();
            verify(stateManager, times(1)).getHighestAckedBlockNumber();
            verify(connectionManager).rescheduleAndSelectNewNode(connection, Duration.ofSeconds(30));
            verify(stateManager).getBlockState(11L);
            verify(mockObserver)
                    .onNext(PublishStreamRequest.newBuilder()
                            .setEndStream(PublishStreamRequest.EndStream.newBuilder()
                                    .setEndCode(PublishStreamRequest.EndStream.Code.TOO_FAR_BEHIND)
                                    .build())
                            .build());
            verify(mockObserver).onCompleted();
            verify(connectionManager).jumpToBlock(-1L);
            verifyNoMoreInteractions(metrics);
            verifyNoMoreInteractions(mockObserver);
            verifyNoMoreInteractions(connectionManager);
            verifyNoMoreInteractions(stateManager);
        }
    }

    @Test
    void testOnNext_endOfStream_itemsUnknown() {
        try (MockedStatic<BlockStreamPublishServiceGrpc> mockedStatic =
                Mockito.mockStatic(BlockStreamPublishServiceGrpc.class)) {
            // Mock the static method
            BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub mockStub =
                    mock(BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub.class);
            mockedStatic
                    .when(() -> BlockStreamPublishServiceGrpc.newStub(managedChannel))
                    .thenReturn(mockStub);

            // Mock the behavior of the stub's publishBlockStream method
            ClientCallStreamObserver<PublishStreamRequest> mockObserver = mock(ClientCallStreamObserver.class);
            when(mockStub.publishBlockStream(any())).thenReturn(mockObserver);
            openConnectionAndResetMocks();
            final PublishStreamResponse response = createEndOfStreamResponse(Code.UNKNOWN, 10L);

            connection.onNext(response);

            verify(metrics)
                    .incrementEndOfStreamCount(org.hiero.block.api.PublishStreamResponse.EndOfStream.Code.UNKNOWN);
            verify(mockObserver).onCompleted();
            verify(connectionManager).jumpToBlock(-1L);
            verify(connectionManager).rescheduleAndSelectNewNode(connection, Duration.ofSeconds(30));
            verifyNoMoreInteractions(metrics);
            verifyNoMoreInteractions(mockObserver);
            verifyNoMoreInteractions(connectionManager);
            verifyNoInteractions(stateManager);
        }
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
        verifyNoMoreInteractions(connectionManager);
        verifyNoMoreInteractions(stateManager);
    }

    @Test
    void testOnNext_resendBlock_blockDoesNotExist() {
        try (MockedStatic<BlockStreamPublishServiceGrpc> mockedStatic =
                Mockito.mockStatic(BlockStreamPublishServiceGrpc.class)) {
            // Mock the static method
            BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub mockStub =
                    mock(BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub.class);
            mockedStatic
                    .when(() -> BlockStreamPublishServiceGrpc.newStub(managedChannel))
                    .thenReturn(mockStub);

            // Mock the behavior of the stub's publishBlockStream method
            ClientCallStreamObserver<PublishStreamRequest> mockObserver = mock(ClientCallStreamObserver.class);
            when(mockStub.publishBlockStream(any())).thenReturn(mockObserver);
            openConnectionAndResetMocks();

            final PublishStreamResponse response = createResendBlock(10L);
            when(stateManager.getBlockState(10L)).thenReturn(null);

            connection.onNext(response);

            verify(metrics).incrementResendBlockCount();
            verify(mockObserver).onCompleted();
            verify(connectionManager).jumpToBlock(-1L);
            verify(connectionManager).rescheduleAndSelectNewNode(connection, Duration.ofSeconds(30));
            verify(stateManager).getBlockState(10L);
            verifyNoMoreInteractions(metrics);
            verifyNoMoreInteractions(mockObserver);
            verifyNoMoreInteractions(connectionManager);
            verifyNoMoreInteractions(stateManager);
        }
    }

    @Test
    void testOnNext_unknown() {
        final PublishStreamResponse response =
                PublishStreamResponse.newBuilder().build();

        connection.onNext(response);

        verify(metrics).incrementUnknownResponseCount();

        verifyNoMoreInteractions(metrics);
        verifyNoInteractions(connectionManager);
        verifyNoInteractions(stateManager);
    }

    @Test
    void testSendRequest() throws InvalidProtocolBufferException {
        try (MockedStatic<BlockStreamPublishServiceGrpc> mockedStatic =
                Mockito.mockStatic(BlockStreamPublishServiceGrpc.class)) {
            // Mock the static method
            BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub mockStub =
                    mock(BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub.class);
            mockedStatic
                    .when(() -> BlockStreamPublishServiceGrpc.newStub(managedChannel))
                    .thenReturn(mockStub);

            // Mock the behavior of the stub's publishBlockStream method
            ClientCallStreamObserver<PublishStreamRequest> mockObserver = mock(ClientCallStreamObserver.class);
            when(mockStub.publishBlockStream(any())).thenReturn(mockObserver);
            when(mockObserver.isReady()).thenReturn(true);
            openConnectionAndResetMocks();

            final BlockHeader blockHeader = BlockHeader.newBuilder().number(1L).build();
            final BlockItem item =
                    BlockItem.newBuilder().blockHeader(blockHeader).build();
            final org.hiero.block.api.PublishStreamRequest request = createRequest(item);

            connection.updateConnectionState(ConnectionState.ACTIVE);
            connection.sendRequest(request);

            verify(mockObserver)
                    .onNext(org.hiero.block.api.protoc.PublishStreamRequest.parseFrom(
                            org.hiero.block.api.PublishStreamRequest.PROTOBUF
                                    .toBytes(createRequest(item))
                                    .toByteArray()));
            verifyNoInteractions(metrics);
            verifyNoMoreInteractions(mockObserver);
            verifyNoMoreInteractions(connectionManager);
            verifyNoInteractions(stateManager);
        }
    }

    @Test
    void testSendRequest_notActive() throws InvalidProtocolBufferException {
        try (MockedStatic<BlockStreamPublishServiceGrpc> mockedStatic =
                Mockito.mockStatic(BlockStreamPublishServiceGrpc.class)) {
            // Mock the static method
            BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub mockStub =
                    mock(BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub.class);
            mockedStatic
                    .when(() -> BlockStreamPublishServiceGrpc.newStub(managedChannel))
                    .thenReturn(mockStub);

            // Mock the behavior of the stub's publishBlockStream method
            ClientCallStreamObserver<PublishStreamRequest> mockObserver = mock(ClientCallStreamObserver.class);
            when(mockStub.publishBlockStream(any())).thenReturn(mockObserver);
            final BlockHeader blockHeader = BlockHeader.newBuilder().number(1L).build();
            final BlockItem item =
                    BlockItem.newBuilder().blockHeader(blockHeader).build();
            final org.hiero.block.api.PublishStreamRequest request = createRequest(item);

            connection.createRequestObserver();
            connection.updateConnectionState(ConnectionState.PENDING);
            connection.sendRequest(request);

            verifyNoInteractions(metrics);
            verifyNoMoreInteractions(mockObserver);
            verifyNoMoreInteractions(connectionManager);
            verifyNoInteractions(stateManager);
        }
    }

    @Test
    void testSendRequest_observerNull() {
        final BlockHeader blockHeader = BlockHeader.newBuilder().number(1L).build();
        final BlockItem item = BlockItem.newBuilder().blockHeader(blockHeader).build();
        final org.hiero.block.api.PublishStreamRequest request = createRequest(item);

        // don't create the observer
        connection.updateConnectionState(ConnectionState.PENDING);
        connection.sendRequest(request);

        verifyNoInteractions(metrics);
        verifyNoMoreInteractions(connectionManager);
        verifyNoInteractions(stateManager);
    }

    @Test
    void testClose() {
        try (MockedStatic<BlockStreamPublishServiceGrpc> mockedStatic =
                Mockito.mockStatic(BlockStreamPublishServiceGrpc.class)) {
            // Mock the static method
            BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub mockStub =
                    mock(BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub.class);
            mockedStatic
                    .when(() -> BlockStreamPublishServiceGrpc.newStub(managedChannel))
                    .thenReturn(mockStub);

            // Mock the behavior of the stub's publishBlockStream method
            ClientCallStreamObserver<PublishStreamRequest> mockObserver = mock(ClientCallStreamObserver.class);
            when(mockStub.publishBlockStream(any())).thenReturn(mockObserver);
            openConnectionAndResetMocks();
            connection.updateConnectionState(ConnectionState.ACTIVE);

            connection.close();

            assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.UNINITIALIZED);

            verify(connectionManager).jumpToBlock(-1L);
            verify(mockObserver).onCompleted();
            verifyNoInteractions(metrics);
            verifyNoMoreInteractions(mockObserver);
            verifyNoMoreInteractions(connectionManager);
            verifyNoInteractions(stateManager);
        }
    }

    @Test
    void testClose_failure() {
        try (MockedStatic<BlockStreamPublishServiceGrpc> mockedStatic =
                Mockito.mockStatic(BlockStreamPublishServiceGrpc.class)) {
            // Mock the static method
            BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub mockStub =
                    mock(BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub.class);
            mockedStatic
                    .when(() -> BlockStreamPublishServiceGrpc.newStub(managedChannel))
                    .thenReturn(mockStub);

            // Mock the behavior of the stub's publishBlockStream method
            ClientCallStreamObserver<PublishStreamRequest> mockObserver = mock(ClientCallStreamObserver.class);
            when(mockStub.publishBlockStream(any())).thenReturn(mockObserver);
            openConnectionAndResetMocks();
            doThrow(new RuntimeException("oh no!")).when(mockObserver).onCompleted();
            connection.updateConnectionState(ConnectionState.ACTIVE);

            connection.close();

            assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.UNINITIALIZED);

            verify(connectionManager).jumpToBlock(-1L);
            verify(mockObserver).onCompleted();
            verifyNoInteractions(metrics);
            verifyNoMoreInteractions(mockObserver);
            verifyNoMoreInteractions(connectionManager);
            verifyNoInteractions(stateManager);
        }
    }

    @Test
    void testOnError() {
        try (MockedStatic<BlockStreamPublishServiceGrpc> mockedStatic =
                Mockito.mockStatic(BlockStreamPublishServiceGrpc.class)) {
            // Mock the static method
            BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub mockStub =
                    mock(BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub.class);
            mockedStatic
                    .when(() -> BlockStreamPublishServiceGrpc.newStub(managedChannel))
                    .thenReturn(mockStub);

            // Mock the behavior of the stub's publishBlockStream method
            ClientCallStreamObserver<PublishStreamRequest> mockObserver = mock(ClientCallStreamObserver.class);
            when(mockStub.publishBlockStream(any())).thenReturn(mockObserver);

            openConnectionAndResetMocks();

            connection.onError(new RuntimeException("oh bother"));

            assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.UNINITIALIZED);

            verify(metrics).incrementOnErrorCount();

            verify(connectionManager).jumpToBlock(-1L);
            verify(mockObserver).onCompleted();
            verify(connectionManager).rescheduleAndSelectNewNode(connection, Duration.ofSeconds(30));
            verifyNoMoreInteractions(metrics);
            verifyNoMoreInteractions(mockObserver);
            verifyNoMoreInteractions(connectionManager);
            verifyNoInteractions(stateManager);
        }
    }

    @Test
    void testOnCompleted_streamClosingInProgress() {
        try (MockedStatic<BlockStreamPublishServiceGrpc> mockedStatic =
                Mockito.mockStatic(BlockStreamPublishServiceGrpc.class)) {
            // Mock the static method
            BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub mockStub =
                    mock(BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub.class);
            mockedStatic
                    .when(() -> BlockStreamPublishServiceGrpc.newStub(managedChannel))
                    .thenReturn(mockStub);

            // Mock the behavior of the stub's publishBlockStream method
            ClientCallStreamObserver<PublishStreamRequest> mockObserver = mock(ClientCallStreamObserver.class);
            when(mockStub.publishBlockStream(any())).thenReturn(mockObserver);
            openConnectionAndResetMocks();
            connection.close(); // call this so we mark the connection as closing
            resetMocks();

            connection.onCompleted();

            verifyNoInteractions(metrics);
            verifyNoInteractions(connectionManager);
            verifyNoInteractions(stateManager);
        }
    }

    @Test
    void testOnCompleted_streamClosingNotInProgress() {
        try (MockedStatic<BlockStreamPublishServiceGrpc> mockedStatic =
                Mockito.mockStatic(BlockStreamPublishServiceGrpc.class)) {
            // Mock the static method
            BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub mockStub =
                    mock(BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub.class);
            mockedStatic
                    .when(() -> BlockStreamPublishServiceGrpc.newStub(managedChannel))
                    .thenReturn(mockStub);

            // Mock the behavior of the stub's publishBlockStream method
            ClientCallStreamObserver<PublishStreamRequest> mockObserver = mock(ClientCallStreamObserver.class);
            when(mockStub.publishBlockStream(any())).thenReturn(mockObserver);
            openConnectionAndResetMocks();
            // don't call close so we do not mark the connection as closing
            connection.onCompleted();

            verify(connectionManager).jumpToBlock(-1L);
            verify(mockObserver).onCompleted();
            verify(connectionManager).rescheduleAndSelectNewNode(connection, Duration.ofSeconds(30));
            verifyNoMoreInteractions(metrics);
            verifyNoMoreInteractions(mockObserver);
            verifyNoMoreInteractions(connectionManager);
            verifyNoInteractions(stateManager);
        }
    }

    // Utilities

    private void openConnectionAndResetMocks() {
        connection.createRequestObserver();
        // reset the mocks interactions to remove tracked interactions as a result of starting the connection
        resetMocks();
    }

    private void resetMocks() {
        reset(connectionManager, stateManager, metrics);
    }
}
