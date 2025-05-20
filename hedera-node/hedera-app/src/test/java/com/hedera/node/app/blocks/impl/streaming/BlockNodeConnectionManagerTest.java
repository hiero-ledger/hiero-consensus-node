// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.node.app.blocks.impl.streaming.BlockNodeConnection.ConnectionState.ACTIVE;
import static com.hedera.node.app.blocks.impl.streaming.BlockNodeConnection.ConnectionState.UNINITIALIZED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.PublishStreamResponse;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
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

@ExtendWith(MockitoExtension.class)
class BlockNodeConnectionManagerTest extends BlockNodeCommunicationTestBase {

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
        configProvider = createConfigProvider();
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
    void testShutdownBlockNodeConnectionManager() {
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        assertThat(blockStreamStateManager.getConnections().containsValue(subject))
                .isTrue();
        assertEquals(subject.getConnectionState(), ACTIVE);

        blockNodeConnectionManager.shutdown();

        assertEquals(subject.getConnectionState(), UNINITIALIZED);
    }

    @Test
    void testExceptionHandlingOnRetryTasks() throws InterruptedException {
        // Stub the method to throw an exception initially
        Mockito.doThrow(new RuntimeException("Simulated Connection Exception"))
                .when(mockGrpcServiceClient)
                .bidi(any(), (StreamObserver<Object>) any());

        // Trigger the connection logic
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        // Verify the connection state is UNINITIALIZED after the exception
        assertEquals(subject.getConnectionState(), UNINITIALIZED);

        // Allow time for retry logic
        Thread.sleep(3000);

        // Reset the mock and stub the method to return a valid response
        Mockito.reset(mockGrpcServiceClient);
        when(mockGrpcServiceClient.bidi(any(), (StreamObserver<Object>) any())).thenReturn(genericMockStreamObserver);

        // Allow time for retry logic to succeed
        Thread.sleep(3000);

        // Verify the connection state is ACTIVE after retry
        assertEquals(subject.getConnectionState(), ACTIVE);
    }

    @Test
    void testBlockStreamWorkerLoop() throws InterruptedException {
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        assertThat(blockStreamStateManager.getConnections().containsValue(subject))
                .isTrue();
        assertEquals(subject.getConnectionState(), ACTIVE);

        blockStreamStateManager.openBlock(0L);

        for (int i = 0; i < BATCH_SIZE; i++) {
            blockStreamStateManager.addItem(0L, BlockItem.newBuilder().build());
        }
        // Add a BlockItem in the next batch
        blockStreamStateManager.addItem(0L, BlockItem.newBuilder().build());

        // Trigger Streaming PreBlockProofItems
        blockStreamStateManager.streamPreBlockProofItems(0L);

        // Close Block
        blockStreamStateManager.closeBlock(0L);

        // Add BlockProof
        blockStreamStateManager.addItem(
                0L,
                BlockItem.newBuilder()
                        .blockProof(BlockProof.newBuilder().build())
                        .build());

        blockStreamStateManager.openBlock(1L);

        Thread.sleep(1000);

        // Block Stream Worker Thread should move to Block #1
        assertEquals(1L, blockNodeConnectionManager.getStreamingBlockNumber().get());
    }
}
