// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.node.app.blocks.impl.streaming.BlockNodeConnection.ConnectionState.ACTIVE;
import static com.hedera.node.app.blocks.impl.streaming.BlockNodeConnection.ConnectionState.UNINITIALIZED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.PublishStreamRequest;
import com.hedera.hapi.block.PublishStreamResponse;
import com.hedera.hapi.block.PublishStreamResponse.Acknowledgement;
import com.hedera.hapi.block.PublishStreamResponseCode;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.internal.network.BlockNodeConfig;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.lifecycle.info.NodeInfo;
import io.grpc.stub.StreamObserver;
import io.helidon.webclient.grpc.GrpcServiceClient;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link BlockNodeConnection}.
 */
@ExtendWith(MockitoExtension.class)
public class BlockNodeConnectionTest extends BlockNodeCommunicationTestBase {

    // Block Node Communication Components
    private BlockNodeConnection subject;
    private BlockNodeConnection subject2;
    private BlockNodeConnectionManager blockNodeConnectionManager;
    private BlockStreamMetrics blockStreamMetrics;
    private BlockStreamStateManager blockStreamStateManager;
    private ConfigProvider configProvider;
    private BlockNodeConfig nodeConfig;
    private BlockNodeConfig nodeConfig2;

    @Mock
    private Metrics mockMetrics;

    @Mock
    private NodeInfo mockNodeInfo;

    @Mock
    private GrpcServiceClient mockGrpcServiceClient;

    private StreamObserver<Object> genericMockStreamObserver;

    @Mock
    private StreamObserver<PublishStreamResponse> mockStreamObserver;

    @BeforeEach
    void setUp() {
        // Setup ConfigProvider
        configProvider = createConfigProvider(BATCH_SIZE);
        nodeConfig = new BlockNodeConfig("localhost", 8080, 1);
        nodeConfig2 = new BlockNodeConfig("localhost", 8080, 2);

        // Create a mock of StreamObserver<Object> and cast it to StreamObserver<PublishStreamResponse>
        genericMockStreamObserver = Mockito.mock(StreamObserver.class);
        when(mockGrpcServiceClient.bidi(any(), (StreamObserver<Object>) any())).thenReturn(genericMockStreamObserver);

        // Setup BlockStreamMetrics with mocks
        when(mockNodeInfo.nodeId()).thenReturn(0L);
        blockStreamMetrics = new BlockStreamMetrics(mockMetrics, mockNodeInfo);

        // Setup BlockStreamStateManager
        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);

        // Setup BlockNodeConnectionManager
        blockNodeConnectionManager = Mockito.spy(
                new BlockNodeConnectionManager(configProvider, blockStreamStateManager, blockStreamMetrics));

        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);

        // Set up BlockNodeConnection
        subject = new BlockNodeConnection(
                configProvider,
                nodeConfig,
                blockNodeConnectionManager,
                blockStreamStateManager,
                mockGrpcServiceClient,
                blockStreamMetrics);

        subject2 = new BlockNodeConnection(
                configProvider,
                nodeConfig2,
                blockNodeConnectionManager,
                blockStreamStateManager,
                mockGrpcServiceClient,
                blockStreamMetrics);

        doReturn(subject).when(blockNodeConnectionManager).createBlockNodeConnection(any(), any());
    }

    @Test
    void testUpdateConnectionState() {
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        // Given
        final var expectedState = UNINITIALIZED;

        // When
        subject.updateConnectionState(expectedState);

        // Then
        assertThat(subject.getConnectionState()).isEqualTo(expectedState);
    }

    @Test
    void testHandleAcknowledgementLastVerifiedBlockUpdated() {
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        assertThat(blockStreamStateManager.getConnections().containsValue(subject))
                .isTrue();
        assertEquals(subject.getConnectionState(), ACTIVE);

        // Open Block 0
        blockStreamStateManager.openBlock(0L);

        // Create Test BlockAcknowledgement
        PublishStreamResponse response = createBlockAckResponse(0L, false);

        subject.onNext(response);

        assertEquals(blockNodeConnectionManager.getLastVerifiedBlock(nodeConfig), 0L);

        // Create Test BlockAcknowledgement
        response = createBlockAckResponse(123L, false);

        subject.onNext(response);

        assertEquals(blockNodeConnectionManager.getLastVerifiedBlock(nodeConfig), 123L);
    }

    @Test
    void testHandleAcknowledgementCurrentBlockStreamingNegativeCase() {
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        assertThat(blockStreamStateManager.getConnections().containsValue(subject))
                .isTrue();
        assertEquals(subject.getConnectionState(), ACTIVE);

        // Create Test BlockAcknowledgement
        PublishStreamResponse response = createBlockAckResponse(0L, true);

        subject.onNext(response);

        assertEquals(0L, blockNodeConnectionManager.getLastVerifiedBlock(nodeConfig));

        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertEquals(0L, blockNodeConnectionManager.getStreamingBlockNumber().get());
    }

    @Test
    void testHandleAcknowledgementJumpWhenAckBetweenStreamingAndProducing() {
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        assertThat(blockStreamStateManager.getConnections().containsValue(subject))
                .isTrue();
        assertEquals(subject.getConnectionState(), ACTIVE);

        // Create Test BlockAcknowledgement
        PublishStreamResponse response = createBlockAckResponse(5L, true);

        // Open Block 0
        blockStreamStateManager.openBlock(0L);

        // Open Block 0
        blockStreamStateManager.openBlock(10L);

        subject.onNext(response);

        assertEquals(5L, blockNodeConnectionManager.getLastVerifiedBlock(nodeConfig));

        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertEquals(6L, blockNodeConnectionManager.getStreamingBlockNumber().get());
    }

    @Test
    void testHandleAcknowledgementUnknown() {
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        assertThat(blockStreamStateManager.getConnections().containsValue(subject))
                .isTrue();
        assertEquals(subject.getConnectionState(), ACTIVE);

        // Open Block 0
        blockStreamStateManager.openBlock(0L);

        Acknowledgement acknowledgement = Acknowledgement.newBuilder().build();

        PublishStreamResponse response = PublishStreamResponse.newBuilder()
                .acknowledgement(acknowledgement)
                .build();

        subject.onNext(response);

        assertEquals(-1L, blockNodeConnectionManager.getLastVerifiedBlock(nodeConfig));
    }

    @Test
    void testHandleEndOfStreamClosesStreamItemsInternalError() {
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        PublishStreamResponse response =
                createEndOfStreamResponse(PublishStreamResponseCode.STREAM_ITEMS_INTERNAL_ERROR, Long.MAX_VALUE);

        blockStreamStateManager.openBlock(0L);

        doReturn(subject2).when(blockNodeConnectionManager).createBlockNodeConnection(any(), any());

        subject.onNext(response);

        assertEquals(UNINITIALIZED, subject.getConnectionState());
    }

    @Test
    void testHandleEndOfStreamClosesStreamItemsPersistenceFailure() {
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        PublishStreamResponse response =
                createEndOfStreamResponse(PublishStreamResponseCode.STREAM_ITEMS_PERSISTENCE_FAILED, Long.MAX_VALUE);

        blockStreamStateManager.openBlock(0L);

        doReturn(subject2).when(blockNodeConnectionManager).createBlockNodeConnection(any(), any());

        subject.onNext(response);

        assertEquals(UNINITIALIZED, subject.getConnectionState());
    }

    @Test
    void testHandleEndOfStreamClosesStreamItemsTimeoutRestartsWithoutSwitching() {
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        PublishStreamResponse response =
                createEndOfStreamResponse(PublishStreamResponseCode.STREAM_ITEMS_TIMEOUT, Long.MAX_VALUE);

        blockStreamStateManager.openBlock(0L);

        subject.onNext(response);

        try {
            Thread.sleep(3000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertEquals(ACTIVE, subject.getConnectionState());
        assertEquals(UNINITIALIZED, subject2.getConnectionState());
    }

    @Test
    void testHandleEndOfStreamClosesStreamItemsOutOfOrderRestartsWithoutSwitching() {
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        PublishStreamResponse response =
                createEndOfStreamResponse(PublishStreamResponseCode.STREAM_ITEMS_OUT_OF_ORDER, Long.MAX_VALUE);

        blockStreamStateManager.openBlock(0L);

        subject.onNext(response);

        try {
            Thread.sleep(3000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertEquals(ACTIVE, subject.getConnectionState());
        assertEquals(UNINITIALIZED, subject2.getConnectionState());
    }

    @Test
    void testHandleEndOfStreamClosesStreamItemsBadStateProofRestartsWithoutSwitching() {
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        PublishStreamResponse response =
                createEndOfStreamResponse(PublishStreamResponseCode.STREAM_ITEMS_BAD_STATE_PROOF, Long.MAX_VALUE);

        blockStreamStateManager.openBlock(0L);

        subject.onNext(response);

        try {
            Thread.sleep(3000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertEquals(ACTIVE, subject.getConnectionState());
        assertEquals(UNINITIALIZED, subject2.getConnectionState());
    }

    @Test
    void testHandleEndOfStreamClosesStreamItemsSuccess() {
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        PublishStreamResponse response =
                createEndOfStreamResponse(PublishStreamResponseCode.STREAM_ITEMS_SUCCESS, Long.MAX_VALUE);

        blockStreamStateManager.openBlock(0L);

        doReturn(subject2).when(blockNodeConnectionManager).createBlockNodeConnection(any(), any());

        subject.onNext(response);

        try {
            Thread.sleep(3000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertEquals(UNINITIALIZED, subject.getConnectionState());
        assertEquals(ACTIVE, subject2.getConnectionState());
    }

    @Test
    void testHandleEndOfStreamClosesStreamItemsUnknown() {
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        PublishStreamResponse response =
                createEndOfStreamResponse(PublishStreamResponseCode.STREAM_ITEMS_UNKNOWN, Long.MAX_VALUE);

        blockStreamStateManager.openBlock(0L);

        doReturn(subject2).when(blockNodeConnectionManager).createBlockNodeConnection(any(), any());

        subject.onNext(response);

        try {
            Thread.sleep(3000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertEquals(UNINITIALIZED, subject.getConnectionState());
        assertEquals(ACTIVE, subject2.getConnectionState());
    }

    @Test
    void testHandleEndOfStreamClosesStreamItemsBehindMaxLong() {
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        PublishStreamResponse response =
                createEndOfStreamResponse(PublishStreamResponseCode.STREAM_ITEMS_BEHIND, Long.MAX_VALUE);

        blockStreamStateManager.openBlock(0L);
        blockStreamStateManager.openBlock(100L);

        blockNodeConnectionManager.getJumpTargetBlock().set(100L);

        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertEquals(100L, blockNodeConnectionManager.getStreamingBlockNumber().get());

        subject.onNext(response);

        try {
            Thread.sleep(3000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertEquals(0L, blockNodeConnectionManager.getStreamingBlockNumber().get());
    }

    @Test
    void testHandleEndOfStreamClosesStreamItemsBehind() {
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        PublishStreamResponse response = createEndOfStreamResponse(PublishStreamResponseCode.STREAM_ITEMS_BEHIND, 49L);

        blockStreamStateManager.openBlock(0L);
        blockStreamStateManager.openBlock(50L);
        blockStreamStateManager.openBlock(100L);

        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertEquals(0L, blockNodeConnectionManager.getStreamingBlockNumber().get());

        subject.onNext(response);

        try {
            Thread.sleep(3000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertEquals(50L, blockNodeConnectionManager.getStreamingBlockNumber().get());
    }

    @Test
    void testHandleEndOfStreamClosesStreamItemsBehindNoBlockState() {
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        PublishStreamResponse response = createEndOfStreamResponse(PublishStreamResponseCode.STREAM_ITEMS_BEHIND, 49L);

        blockStreamStateManager.openBlock(0L);
        blockStreamStateManager.openBlock(100L);

        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertEquals(0L, blockNodeConnectionManager.getStreamingBlockNumber().get());

        subject.onNext(response);

        assertEquals(UNINITIALIZED, subject.getConnectionState());
    }

    @Test
    void testHandleEndOfStreamMultipleWithinTimeframe() throws InterruptedException {
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        PublishStreamResponse response =
                createEndOfStreamResponse(PublishStreamResponseCode.STREAM_ITEMS_BEHIND, Long.MAX_VALUE);

        blockStreamStateManager.openBlock(0L);

        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertEquals(0L, blockNodeConnectionManager.getStreamingBlockNumber().get());

        doReturn(subject2).when(blockNodeConnectionManager).createBlockNodeConnection(any(), any());

        // 5 EndOfStream within 30 seconds.
        subject.onNext(response);
        assertEquals(UNINITIALIZED, subject.getConnectionState());
        Thread.sleep(2000L);
        assertEquals(ACTIVE, subject.getConnectionState());

        subject.onNext(response);
        assertEquals(UNINITIALIZED, subject.getConnectionState());
        Thread.sleep(2000L);
        assertEquals(ACTIVE, subject.getConnectionState());

        subject.onNext(response);
        assertEquals(UNINITIALIZED, subject.getConnectionState());
        Thread.sleep(2000L);
        assertEquals(ACTIVE, subject.getConnectionState());

        subject.onNext(response);
        assertEquals(UNINITIALIZED, subject.getConnectionState());
        Thread.sleep(2000L);
        assertEquals(ACTIVE, subject.getConnectionState());

        subject.onNext(response);
        assertEquals(UNINITIALIZED, subject.getConnectionState());
        Thread.sleep(2000L);
        assertEquals(ACTIVE, subject.getConnectionState());

        subject.onNext(response);
        assertEquals(UNINITIALIZED, subject.getConnectionState());
        Thread.sleep(7000L);
        assertEquals(UNINITIALIZED, subject.getConnectionState());
        assertEquals(ACTIVE, subject2.getConnectionState());
    }

    @Test
    void testHandleSkipBlock() {
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        PublishStreamResponse response = createSkipBlock(0L);

        blockStreamStateManager.openBlock(0L);
        blockStreamStateManager.openBlock(1L);

        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertEquals(0L, blockNodeConnectionManager.getStreamingBlockNumber().get());

        subject.onNext(response);

        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertEquals(1L, blockNodeConnectionManager.getStreamingBlockNumber().get());
    }

    @Test
    void testHandleSkipBlockIgnore() {
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        PublishStreamResponse response = createSkipBlock(5L);

        blockStreamStateManager.openBlock(0L);
        blockStreamStateManager.openBlock(1L);

        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertEquals(0L, blockNodeConnectionManager.getStreamingBlockNumber().get());

        subject.onNext(response);

        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertEquals(0L, blockNodeConnectionManager.getStreamingBlockNumber().get());
    }

    @Test
    void testHandleResendBlock() {
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        PublishStreamResponse response = createResendBlock(0L);

        blockStreamStateManager.openBlock(0L);
        blockStreamStateManager.openBlock(1L);

        blockNodeConnectionManager.getJumpTargetBlock().set(1L);

        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertEquals(1L, blockNodeConnectionManager.getStreamingBlockNumber().get());

        subject.onNext(response);

        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertEquals(0L, blockNodeConnectionManager.getStreamingBlockNumber().get());
    }

    @Test
    void testHandleResendBlockNoBlockState() {
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        PublishStreamResponse response = createResendBlock(5L);

        blockStreamStateManager.openBlock(0L);

        assertEquals(0L, blockNodeConnectionManager.getStreamingBlockNumber().get());

        doReturn(subject2).when(blockNodeConnectionManager).createBlockNodeConnection(any(), any());

        subject.onNext(response);

        try {
            Thread.sleep(2000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertEquals(UNINITIALIZED, subject.getConnectionState());
        assertEquals(ACTIVE, subject2.getConnectionState());
    }

    @Test
    void testOnError() {
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        assertEquals(ACTIVE, subject.getConnectionState());

        doReturn(subject2).when(blockNodeConnectionManager).createBlockNodeConnection(any(), any());

        // Simulate an error
        subject.onError(new RuntimeException("Test error"));

        try {
            Thread.sleep(2000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        // Verify that the connection state is set to UNINITIALIZED
        assertEquals(UNINITIALIZED, subject.getConnectionState());
        assertEquals(ACTIVE, subject2.getConnectionState());
    }

    @Test
    void testOnCompleted() {
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        assertEquals(ACTIVE, subject.getConnectionState());

        doReturn(subject2).when(blockNodeConnectionManager).createBlockNodeConnection(any(), any());

        // Simulate onCompleted
        subject.onCompleted();

        try {
            Thread.sleep(2000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        // Verify that the connection state is set to UNINITIALIZED
        assertEquals(UNINITIALIZED, subject.getConnectionState());
        assertEquals(ACTIVE, subject2.getConnectionState());
    }

    @Test
    void testSendRequest() {
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        assertEquals(ACTIVE, subject.getConnectionState());

        PublishStreamRequest expectedRequest = PublishStreamRequest.newBuilder().build();
        subject.sendRequest(expectedRequest);

        // Verify that the request was sent to the correct StreamObserver
        Mockito.verify(genericMockStreamObserver).onNext(expectedRequest);
    }

    @Test
    void testSendRequestNotActive() {
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        assertEquals(ACTIVE, subject.getConnectionState());

        PublishStreamRequest expectedRequest = PublishStreamRequest.newBuilder().build();

        subject.updateConnectionState(UNINITIALIZED);
        subject.sendRequest(expectedRequest);

        // Verify that the request was sent to the correct StreamObserver
        Mockito.verifyNoInteractions(genericMockStreamObserver);
    }
}
