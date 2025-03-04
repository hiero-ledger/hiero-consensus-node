// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.hedera.hapi.block.protoc.PublishStreamRequest;
import com.hedera.hapi.block.protoc.PublishStreamResponse;
import com.hedera.hapi.block.protoc.PublishStreamResponseCode;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.fixtures.util.LogCaptureExtension;
import com.hedera.node.app.spi.fixtures.util.LoggingSubject;
import com.hedera.node.app.spi.fixtures.util.LoggingTarget;
import com.hedera.node.internal.network.BlockNodeConfig;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.helidon.webclient.grpc.GrpcServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
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
    GrpcServiceClient grpcServiceClient;

    @Mock
    BlockNodeConnectionManager blockNodeConnectionManager;

    @Mock
    private StreamObserver<PublishStreamRequest> requestObserver;

    @BeforeEach
    public void setUp() {
        blockNodeConnection = new BlockNodeConnection(nodeConfig, grpcServiceClient, blockNodeConnectionManager);
    }

    @Test
    void testNewBlockNodeConnection() {
        assertEquals(nodeConfig, blockNodeConnection.getNodeConfig());
        assertFalse(blockNodeConnection.isActive());
        assertThat(logCaptor.infoLogs()).contains("BlockNodeConnection INITIALIZED");
    }

    @Test
    void testEstablishStream() {
        given(grpcServiceClient.bidi(any(), any(StreamObserver.class))).willReturn(requestObserver);

        blockNodeConnection.establishStream();
        assertTrue(blockNodeConnection.isActive());
    }

    @Test
    void testSendRequest_ActiveConnection() {
        given(grpcServiceClient.bidi(any(), any(StreamObserver.class))).willReturn(requestObserver);
        blockNodeConnection.establishStream();
        assertTrue(blockNodeConnection.isActive());

        blockNodeConnection.sendRequest(PublishStreamRequest.getDefaultInstance());

        verify(requestObserver, times(1)).onNext(any());
    }

    @Test
    void testSendRequest_NotActiveConnection() {
        assertFalse(blockNodeConnection.isActive());

        blockNodeConnection.sendRequest(PublishStreamRequest.getDefaultInstance());

        verifyNoInteractions(requestObserver);
    }

    @Test
    void testClose_ActiveConnection() {
        given(grpcServiceClient.bidi(any(), any(StreamObserver.class))).willReturn(requestObserver);
        blockNodeConnection.establishStream();
        assertTrue(blockNodeConnection.isActive());

        blockNodeConnection.close();

        verify(requestObserver, times(1)).onCompleted();
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
        when(grpcServiceClient.bidi(any(), any(StreamObserver.class))).thenReturn(requestObserver);
        blockNodeConnection.establishStream();

        final var captor = ArgumentCaptor.forClass(StreamObserver.class);
        verify(grpcServiceClient).bidi(any(), captor.capture());
        final var capturedObserver = captor.getValue();
        assertNotNull(capturedObserver);

        final var response = PublishStreamResponse.newBuilder()
                .setAcknowledgement(PublishStreamResponse.Acknowledgement.newBuilder()
                        .setBlockAck(PublishStreamResponse.BlockAcknowledgement.newBuilder()
                                .setBlockNumber(1234)
                                .build()))
                .build();
        capturedObserver.onNext(response);

        assertThat(logCaptor.infoLogs()).contains("Block acknowledgment received for a full block: block_number: 1234");
    }

    @Test
    void testGrpcClientStreamObserver_OnNext_ItemAckResponse() {
        when(grpcServiceClient.bidi(any(), any(StreamObserver.class))).thenReturn(requestObserver);
        blockNodeConnection.establishStream();

        final var captor = ArgumentCaptor.forClass(StreamObserver.class);
        verify(grpcServiceClient).bidi(any(), captor.capture());
        final var capturedObserver = captor.getValue();
        assertNotNull(capturedObserver);

        final var response = PublishStreamResponse.newBuilder()
                .setAcknowledgement(PublishStreamResponse.Acknowledgement.newBuilder()
                        .setItemAck(PublishStreamResponse.ItemAcknowledgement.newBuilder()
                                .setItemsHash(ByteString.fromHex("1234"))
                                .build()))
                .build();
        capturedObserver.onNext(response);

        assertThat(logCaptor.infoLogs())
                .contains("Item acknowledgement received for a batch of block items: items_hash: \"\\0224\"");
    }

    @Test
    void testGrpcClientStreamObserver_OnNext_StatusResponseTimeout() {
        when(grpcServiceClient.bidi(any(), any(StreamObserver.class))).thenReturn(requestObserver);
        blockNodeConnection.establishStream();

        final var captor = ArgumentCaptor.forClass(StreamObserver.class);
        verify(grpcServiceClient).bidi(any(), captor.capture());
        final var capturedObserver = captor.getValue();
        assertNotNull(capturedObserver);

        final var response = PublishStreamResponse.newBuilder()
                .setStatus(PublishStreamResponse.EndOfStream.newBuilder()
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
        when(nodeConfig.address()).thenReturn("localhost");
        when(nodeConfig.port()).thenReturn(12345);
        when(grpcServiceClient.bidi(any(), any(StreamObserver.class))).thenReturn(requestObserver);
        blockNodeConnection.establishStream();

        final var captor = ArgumentCaptor.forClass(StreamObserver.class);
        verify(grpcServiceClient).bidi(any(), captor.capture());
        final var capturedObserver = captor.getValue();
        assertNotNull(capturedObserver);

        capturedObserver.onCompleted();

        assertThat(logCaptor.infoLogs()).contains("Stream completed for block node localhost:12345");
        assertFalse(blockNodeConnection.isActive());
        verify(blockNodeConnectionManager, times(1)).handleConnectionError(nodeConfig);
    }

    @Test
    void testGrpcClientStreamObserver_OnError_StatusAborted() {
        when(nodeConfig.address()).thenReturn("localhost");
        when(nodeConfig.port()).thenReturn(12345);
        when(grpcServiceClient.bidi(any(), any(StreamObserver.class))).thenReturn(requestObserver);

        blockNodeConnection.establishStream();

        final var captor = ArgumentCaptor.forClass(StreamObserver.class);
        verify(grpcServiceClient).bidi(any(), captor.capture());

        final var capturedObserver = captor.getValue();
        assertNotNull(capturedObserver);

        capturedObserver.onError(new StatusRuntimeException(Status.ABORTED));

        assertThat(logCaptor.errorLogs())
                .matches(
                        logs -> logs.getFirst()
                                .startsWith(
                                        "Error in block node stream localhost:12345: Status{code=ABORTED, description=null, cause=null} io.grpc.StatusRuntimeException: ABORTED"));
        assertFalse(blockNodeConnection.isActive());
        verify(blockNodeConnectionManager, times(1)).handleConnectionError(nodeConfig);
        verify(blockNodeConnectionManager, times(1)).scheduleReconnect(blockNodeConnection);
    }
}
