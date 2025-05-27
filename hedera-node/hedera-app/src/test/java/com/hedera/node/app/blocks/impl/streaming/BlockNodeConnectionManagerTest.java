// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockNodeConnectionManagerTest extends BlockNodeCommunicationTestBase {

//    // Block Node Communication Components
//    private BlockNodeConnection subject;
//    private BlockNodeConnectionManager blockNodeConnectionManager;
//    private BlockStreamMetrics blockStreamMetrics;
//    private BlockStreamStateManager blockStreamStateManager;
//    private ConfigProvider configProvider;
//    private BlockNodeConfig nodeConfig;
//
//    @Mock
//    private Metrics mockMetrics;
//
//    @Mock
//    private NodeInfo mockNodeInfo;
//
//    @Mock
//    private GrpcServiceClient mockGrpcServiceClient;
//
//    private StreamObserver<Object> genericMockStreamObserver;
//
//    @Mock
//    private StreamObserver<PublishStreamResponse> mockStreamObserver;
//
//    @BeforeEach
//    void setUp() {
//        // Setup ConfigProvider
//        configProvider = createConfigProvider();
//        nodeConfig = new BlockNodeConfig("localhost", 8080, 1);
//
//        // Create a mock of StreamObserver<Object> and cast it to StreamObserver<PublishStreamResponse>
//        genericMockStreamObserver = Mockito.mock(StreamObserver.class);
//        when(mockGrpcServiceClient.bidi(any(), (StreamObserver<Object>) any())).thenReturn(genericMockStreamObserver);
//
//        // Setup BlockStreamMetrics with mocks
//        when(mockNodeInfo.nodeId()).thenReturn(0L);
//        blockStreamMetrics = new BlockStreamMetrics(mockMetrics, mockNodeInfo);
//
//        // Setup BlockStreamStateManager
//        blockStreamStateManager = new BlockStreamStateManager(configProvider, blockStreamMetrics);
//
//        // Setup BlockNodeConnectionManager
//        blockNodeConnectionManager = Mockito.spy(
//                new BlockNodeConnectionManager(configProvider, blockStreamStateManager, blockStreamMetrics));
//
//        blockStreamStateManager.setBlockNodeConnectionManager(blockNodeConnectionManager);
//
//        // Set up BlockNodeConnection
//        subject = new BlockNodeConnection(
//                configProvider,
//                nodeConfig,
//                blockNodeConnectionManager,
//                blockStreamStateManager,
//                mockGrpcServiceClient,
//                blockStreamMetrics,
//                "foo");
//
//        doReturn(subject).when(blockNodeConnectionManager).createBlockNodeConnection(any(), any());
//    }
//
//    @Test
//    void testShutdownBlockNodeConnectionManager() {
//        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));
//
//        assertConnectionExists(blockNodeConnectionManager, subject);
//        assertEquals(ACTIVE, subject.getConnectionState());
//
//        blockNodeConnectionManager.shutdown();
//
//        assertEquals(UNINITIALIZED, subject.getConnectionState());
//    }
//
//    @Test
//    void testExceptionHandlingOnRetryTasks() throws InterruptedException {
//        // Stub the method to throw an exception initially
//        Mockito.doThrow(new RuntimeException("Simulated Connection Exception"))
//                .when(mockGrpcServiceClient)
//                .bidi(any(), (StreamObserver<Object>) any());
//
//        // Trigger the connection logic
//        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));
//
//        // Verify the connection state is UNINITIALIZED after the exception
//        assertEquals(UNINITIALIZED, subject.getConnectionState());
//
//        // Allow time for retry logic
//        Thread.sleep(3000);
//
//        // Reset the mock and stub the method to return a valid response
//        Mockito.reset(mockGrpcServiceClient);
//        when(mockGrpcServiceClient.bidi(any(), (StreamObserver<Object>) any())).thenReturn(genericMockStreamObserver);
//
//        // Allow time for retry logic to succeed
//        Thread.sleep(3000);
//
//        // Verify the connection state is ACTIVE after retry
//        assertEquals(ACTIVE, subject.getConnectionState());
//    }
//
//    @Test
//    void testBlockStreamWorkerLoop() throws InterruptedException {
//        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));
//
//        assertConnectionExists(blockNodeConnectionManager, subject);
//        assertEquals(ACTIVE, subject.getConnectionState());
//
//        blockStreamStateManager.openBlock(0L);
//
//        for (int i = 0; i < BATCH_SIZE; i++) {
//            blockStreamStateManager.addItem(0L, BlockItem.newBuilder().build());
//        }
//        // Add a BlockItem in the next batch
//        blockStreamStateManager.addItem(0L, BlockItem.newBuilder().build());
//
//        // Trigger Streaming PreBlockProofItems
//        blockStreamStateManager.streamPreBlockProofItems(0L);
//
//        // Close Block
//        blockStreamStateManager.closeBlock(0L);
//
//        // Add BlockProof
//        final BlockItem item = BlockItem.newBuilder()
//                .blockProof(BlockProof.newBuilder().build())
//                .build();
//        blockStreamStateManager.addItem(0L, item);
//
//        blockStreamStateManager.openBlock(1L);
//
//        Thread.sleep(1000);
//
//        // Block Stream Worker Thread should move to Block #1
//        assertEquals(1L, blockNodeConnectionManager.currentStreamingBlockNumber());
//    }
}
