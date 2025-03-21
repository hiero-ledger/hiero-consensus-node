// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.protoc.BlockStreamServiceGrpc;
import com.hedera.hapi.block.protoc.PublishStreamRequest;
import com.hedera.hapi.block.protoc.PublishStreamResponse;
import com.hedera.hapi.block.protoc.PublishStreamResponseCode;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.fixtures.util.LogCaptureExtension;
import com.hedera.node.app.spi.fixtures.util.LoggingSubject;
import com.hedera.node.app.spi.fixtures.util.LoggingTarget;
import com.hedera.node.internal.network.BlockNodeConfig;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith({MockitoExtension.class, LogCaptureExtension.class})
class BlockNodeConnectionTest {
    @LoggingSubject
    private BlockNodeConnection blockNodeConnection;

    @LoggingTarget
    private LogCaptor logCaptor;

    @Mock
    BlockNodeConfig nodeConfig;

    @Mock
    BlockNodeConnectionManager blockNodeConnectionManager;

    @Mock
    BlockStreamServiceGrpc.BlockStreamServiceStub grpcStub;

    @Mock
    private StreamObserver<PublishStreamRequest> requestObserver;

    @Mock
    NettyChannelBuilder nettyChannelBuilder;

    private ManagedChannel channel;
    private io.grpc.ClientCall clientCall;

    // Keep static mocks as class fields
    private MockedStatic<ManagedChannelBuilder> mockedChannel;
    private MockedStatic<BlockStreamServiceGrpc> mockedGrpc;

    @BeforeEach
    public void setUp() throws InterruptedException {
        // Basic setup that's needed for all tests
        lenient().when(nodeConfig.address()).thenReturn("localhost");
        lenient().when(nodeConfig.port()).thenReturn(12345);

        channel = mock(ManagedChannel.class);

        // Create static mocks that will persist throughout the test
        mockedChannel = mockStatic(ManagedChannelBuilder.class);
        mockedGrpc = mockStatic(BlockStreamServiceGrpc.class);

        // Setup channel builder chain since it's needed by constructor
        mockedChannel
                .when(() -> ManagedChannelBuilder.forAddress("localhost", 12345))
                .thenReturn(nettyChannelBuilder);
        lenient().when(nettyChannelBuilder.usePlaintext()).thenReturn(nettyChannelBuilder);
        lenient().when(nettyChannelBuilder.build()).thenReturn(channel);

        // Setup channel shutdown chain
        lenient().when(channel.shutdown()).thenReturn(channel);
        lenient()
                .when(channel.awaitTermination(any(Long.class), any(TimeUnit.class)))
                .thenReturn(true);

        blockNodeConnection = new BlockNodeConnection(nodeConfig, blockNodeConnectionManager);
    }

    private void setupGrpcMocks() {
        // Setup for tests that need gRPC functionality
        mockedGrpc.when(() -> BlockStreamServiceGrpc.newStub(channel)).thenReturn(grpcStub);

        when(grpcStub.publishBlockStream(any())).thenReturn(requestObserver);
    }

    @AfterEach
    void tearDown() {
        if (mockedChannel != null) {
            mockedChannel.close();
        }
        if (mockedGrpc != null) {
            mockedGrpc.close();
        }
    }

    @Test
    void testNewBlockNodeConnection() {
        assertEquals(nodeConfig, blockNodeConnection.getNodeConfig());
        assertFalse(blockNodeConnection.isActive());
    }

    @Test
    void testEstablishStream() {
        setupGrpcMocks();
        blockNodeConnection.establishStream();
        assertTrue(blockNodeConnection.isActive());
        verify(grpcStub).publishBlockStream(any());
    }

    @Test
    void testSendRequest_ActiveConnection() {
        setupGrpcMocks();
        blockNodeConnection.establishStream();
        assertTrue(blockNodeConnection.isActive());

        var request = PublishStreamRequest.getDefaultInstance();
        blockNodeConnection.sendRequest(request);

        verify(requestObserver).onNext(request);
    }

    @Test
    void testSendRequest_NotActiveConnection() {
        assertFalse(blockNodeConnection.isActive());
        blockNodeConnection.sendRequest(PublishStreamRequest.getDefaultInstance());
        verifyNoInteractions(requestObserver);
    }

    @Test
    void testClose_ActiveConnection() {
        setupGrpcMocks();
        blockNodeConnection.establishStream();
        assertTrue(blockNodeConnection.isActive());

        blockNodeConnection.close();

        verify(requestObserver).onCompleted();
        assertFalse(blockNodeConnection.isActive());
    }

    @Test
    void testClose_NotActiveConnection() {
        assertFalse(blockNodeConnection.isActive());
        blockNodeConnection.close();
        verifyNoInteractions(requestObserver);
    }

    @Test
    void testGrpcClientStreamObserver_OnNext_BlockAckResponse() {
        setupGrpcMocks();
        blockNodeConnection.establishStream();

        final var response = PublishStreamResponse.newBuilder()
                .setAcknowledgement(PublishStreamResponse.Acknowledgement.newBuilder()
                        .setBlockAck(PublishStreamResponse.BlockAcknowledgement.newBuilder()
                                .setBlockNumber(1234)
                                .build()))
                .build();

        final var captor = ArgumentCaptor.forClass(StreamObserver.class);
        verify(grpcStub).publishBlockStream(captor.capture());
        final var responseObserver = captor.getValue();
        assertNotNull(responseObserver);

        responseObserver.onNext(response);

        assertThat(logCaptor.infoLogs()).contains("Block acknowledgment received for a full block: 1234");
    }

    @Test
    void testGrpcClientStreamObserver_OnNext_StatusResponseTimeout() {
        setupGrpcMocks();
        blockNodeConnection.establishStream();

        final var captor = ArgumentCaptor.forClass(StreamObserver.class);
        verify(grpcStub).publishBlockStream(captor.capture());
        final var capturedObserver = captor.getValue();
        assertNotNull(capturedObserver);

        final var response = PublishStreamResponse.newBuilder()
                .setEndStream(PublishStreamResponse.EndOfStream.newBuilder()
                        .setStatus(PublishStreamResponseCode.STREAM_ITEMS_TIMEOUT)
                        .setBlockNumber(1234)
                        .build())
                .build();
        capturedObserver.onNext(response);

        assertThat(logCaptor.infoLogs())
                .contains(
                        "Error returned from block node at block number 1234: status: STREAM_ITEMS_TIMEOUT\nblock_number: 1234");
    }

    @Test
    void testGrpcClientStreamObserver_OnCompleted() {
        setupGrpcMocks();
        blockNodeConnection.establishStream();

        final var captor = ArgumentCaptor.forClass(StreamObserver.class);
        verify(grpcStub).publishBlockStream(captor.capture());
        final var capturedObserver = captor.getValue();
        assertNotNull(capturedObserver);

        capturedObserver.onCompleted();

        assertThat(logCaptor.infoLogs()).contains("Stream completed for block node localhost:12345");
        assertFalse(blockNodeConnection.isActive());
        verify(blockNodeConnectionManager).handleConnectionError(nodeConfig);
    }

    @Test
    void testGrpcClientStreamObserver_OnError_StatusAborted() {
        setupGrpcMocks();
        blockNodeConnection.establishStream();

        final var captor = ArgumentCaptor.forClass(StreamObserver.class);
        verify(grpcStub).publishBlockStream(captor.capture());
        final var capturedObserver = captor.getValue();
        assertNotNull(capturedObserver);

        capturedObserver.onError(new StatusRuntimeException(Status.ABORTED));

        assertThat(logCaptor.errorLogs())
                .matches(
                        logs -> logs.getFirst()
                                .startsWith(
                                        "Error in block node stream localhost:12345: Status{code=ABORTED, description=null, cause=null} ABORTED"));
        assertFalse(blockNodeConnection.isActive());
        verify(blockNodeConnectionManager).handleConnectionError(nodeConfig);
        verify(blockNodeConnectionManager).scheduleReconnect(blockNodeConnection);
    }
}
