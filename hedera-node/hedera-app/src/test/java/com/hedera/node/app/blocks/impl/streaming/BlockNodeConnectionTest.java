// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.hapi.block.PublishStreamResponseCode.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hedera.hapi.block.PublishStreamRequest;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.fixtures.util.LogCaptureExtension;
import com.hedera.node.app.spi.fixtures.util.LoggingSubject;
import com.hedera.node.app.spi.fixtures.util.LoggingTarget;
import com.hedera.node.internal.network.BlockNodeConfig;
import io.grpc.stub.StreamObserver;
import io.helidon.webclient.grpc.GrpcServiceClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith({MockitoExtension.class, LogCaptureExtension.class})
class BlockNodeConnectionTest {

    private static final long BLOCK_NUMBER = 10L;
    private static final long NEXT_BLOCK_NUMBER = 11L;
    private static final String HOST_ADDRESS = "127.0.0.1";
    private static final int PORT = 50211;
    private static final String CONNECTION_DESCRIPTOR = HOST_ADDRESS + ":" + PORT + " (priority 1)";
    private static final int RECONNECT_SECS = 1;
    private static final int MAX_END_OF_STREAM_RESTARTS_VALUE = 3;
    private static final int MAX_END_OF_STREAM_EXP_RETRIES_VALUE = 10;
    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(1);

    @Mock
    private BlockNodeConfig blockNodeConfig;

    @Mock
    private GrpcServiceClient grpcServiceClient;

    @Mock
    private BlockNodeConnectionManager blockNodeConnectionManager;

    @Mock
    private BlockStreamStateManager blockStreamStateManager;

    @Mock
    private BlockStreamMetrics blockStreamMetrics;

    @Mock
    private StreamObserver<PublishStreamRequest> requestObserver;

    @Mock
    private ScheduledExecutorService scheduler;

    @Captor
    private ArgumentCaptor<Runnable> runnableCaptor;

    @Captor
    private ArgumentCaptor<Long> delayCaptor;

    @Captor
    private ArgumentCaptor<TimeUnit> timeUnitCaptor;

    @Captor
    private ArgumentCaptor<PublishStreamRequest> requestCaptor;

    @LoggingTarget
    private LogCaptor logCaptor;

    @LoggingSubject
    private BlockNodeConnection connection;

    private ExecutorService workerExecutorService;

    //    @BeforeEach
    //    void setUp() {
    //        when(blockNodeConfig.address()).thenReturn(HOST_ADDRESS);
    //        when(blockNodeConfig.port()).thenReturn(PORT);
    //        when(blockNodeConfig.priority()).thenReturn(1);
    //
    //        connection = spy(new BlockNodeConnection(
    //                blockNodeConfig,
    //                blockNodeConnectionManager,
    //                blockStreamStateManager,
    //                grpcServiceClient,
    //                scheduler,
    //                blockStreamMetrics));
    //
    //        when(grpcServiceClient.bidi(any(), eq(connection))).thenReturn((StreamObserver) requestObserver);
    //
    //        workerExecutorService = Executors.newSingleThreadExecutor();
    //    }
    //
    //    @AfterEach
    //    void tearDown() throws InterruptedException {
    //        connection.close();
    //        workerExecutorService.shutdownNow();
    //        if (!workerExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
    //            System.err.println("Test worker executor service did not terminate.");
    //        }
    //    }
    //
    //    @Test
    //    @DisplayName("Constructor throws NullPointerException for null arguments")
    //    void constructorNullChecks() {
    //        assertThrows(
    //                NullPointerException.class,
    //                () -> new BlockNodeConnection(
    //                        null,
    //                        blockNodeConnectionManager,
    //                        blockStreamStateManager,
    //                        grpcServiceClient,
    //                        scheduler,
    //                        blockStreamMetrics),
    //                "nodeConfig must not be null");
    //        assertThrows(
    //                NullPointerException.class,
    //                () -> new BlockNodeConnection(
    //                        blockNodeConfig,
    //                        null,
    //                        blockStreamStateManager,
    //                        grpcServiceClient,
    //                        scheduler,
    //                        blockStreamMetrics),
    //                "blockNodeConnectionManager must not be null");
    //        assertThrows(
    //                NullPointerException.class,
    //                () -> new BlockNodeConnection(
    //                        blockNodeConfig,
    //                        blockNodeConnectionManager,
    //                        null,
    //                        grpcServiceClient,
    //                        scheduler,
    //                        blockStreamMetrics),
    //                "blockStreamStateManager must not be null");
    //        assertThrows(
    //                NullPointerException.class,
    //                () -> new BlockNodeConnection(
    //                        blockNodeConfig,
    //                        blockNodeConnectionManager,
    //                        blockStreamStateManager,
    //                        null,
    //                        scheduler,
    //                        blockStreamMetrics),
    //                "grpcServiceClient must not be null");
    //        assertThrows(
    //                NullPointerException.class,
    //                () -> new BlockNodeConnection(
    //                        blockNodeConfig,
    //                        blockNodeConnectionManager,
    //                        blockStreamStateManager,
    //                        grpcServiceClient,
    //                        null,
    //                        blockStreamMetrics),
    //                "scheduler must not be null");
    //        assertThrows(
    //                NullPointerException.class,
    //                () -> new BlockNodeConnection(
    //                        blockNodeConfig,
    //                        blockNodeConnectionManager,
    //                        blockStreamStateManager,
    //                        grpcServiceClient,
    //                        scheduler,
    //                        null),
    //                "blockStreamMetrics must not be null");
    //    }
    //
    //    @Test
    //    @DisplayName("Create request observer successfully")
    //    void createRequestObserverSuccess() {
    //        connection.createRequestObserver();
    //        verify(grpcServiceClient).bidi(blockNodeConnectionManager.getGrpcEndPoint(), connection);
    //        assertNotNull(TestUtils.getInternalState(connection, "requestObserver", StreamObserver.class));
    //    }
    //
    //    @Test
    //    @DisplayName("Create request observer handles gRPC client exception")
    //    void createRequestObserverException() {
    //        doThrow(new RuntimeException("gRPC error")).when(grpcServiceClient).bidi(any(), eq(connection));
    //        assertThrows(RuntimeException.class, () -> connection.createRequestObserver());
    //    }
    //
    //    @Test
    //    @DisplayName("Update and get connection state")
    //    void updateAndGetConnectionState() {
    //        assertEquals(BlockNodeConnection.ConnectionState.UNINITIALIZED, connection.getConnectionState());
    //        connection.updateConnectionState(BlockNodeConnection.ConnectionState.PENDING);
    //        assertEquals(BlockNodeConnection.ConnectionState.PENDING, connection.getConnectionState());
    //        connection.updateConnectionState(BlockNodeConnection.ConnectionState.ACTIVE);
    //        assertEquals(BlockNodeConnection.ConnectionState.ACTIVE, connection.getConnectionState());
    //        assertEquals(BlockNodeConnection.ConnectionState.ACTIVE, connection.getState());
    //    }
    //
    //    @Test
    //    @DisplayName("Start request worker when active")
    //    void startRequestWorkerWhenActive() throws InterruptedException {
    //        connection.updateConnectionState(BlockNodeConnection.ConnectionState.ACTIVE);
    //        connection.createRequestObserver();
    //        connection.startRequestWorker();
    //
    //        // Allow worker to start
    //        Thread.sleep(100);
    //
    //        Thread worker = TestUtils.getInternalState(connection, "requestWorker", Thread.class);
    //        assertNotNull(worker);
    //        assertTrue(worker.isAlive());
    //        assertThat(logCaptor.debugLogs())
    //                .anyMatch(log -> log.contains("Started request worker thread for block node " +
    // CONNECTION_DESCRIPTOR));
    //
    //        worker.interrupt();
    //        worker.join(1000);
    //        assertFalse(worker.isAlive());
    //    }
    //
    //    @Test
    //    @DisplayName("Start request worker stops existing worker")
    //    void startRequestWorkerStopsExisting() throws InterruptedException {
    //        connection.updateConnectionState(BlockNodeConnection.ConnectionState.ACTIVE);
    //        connection.createRequestObserver();
    //
    //        connection.startRequestWorker();
    //        Thread firstWorker = TestUtils.getInternalState(connection, "requestWorker", Thread.class);
    //        assertNotNull(firstWorker);
    //        assertTrue(firstWorker.isAlive());
    //
    //        Thread.sleep(50);
    //
    //        connection.startRequestWorker();
    //        Thread secondWorker = TestUtils.getInternalState(connection, "requestWorker", Thread.class);
    //        assertNotNull(secondWorker);
    //        assertTrue(secondWorker.isAlive());
    //        assertNotSame(firstWorker, secondWorker);
    //
    //        // Check that the first worker was interrupted and stopped
    //        firstWorker.join(1000);
    //        assertFalse(firstWorker.isAlive(), "First worker thread should have stopped");
    //
    //        secondWorker.interrupt();
    //        secondWorker.join(1000);
    //        assertFalse(secondWorker.isAlive());
    //    }
    //
    //    @Test
    //    @DisplayName("Start request worker does nothing when not active")
    //    void startRequestWorkerWhenNotActive() {
    //        connection.updateConnectionState(BlockNodeConnection.ConnectionState.PENDING);
    //        connection.createRequestObserver();
    //        connection.startRequestWorker();
    //        assertNull(TestUtils.getInternalState(connection, "requestWorker", Thread.class));
    //
    //        connection.updateConnectionState(BlockNodeConnection.ConnectionState.UNINITIALIZED);
    //        connection.startRequestWorker();
    //        assertNull(TestUtils.getInternalState(connection, "requestWorker", Thread.class));
    //    }
    //
    //    @Test
    //    @DisplayName("Worker does not send requests when currentBlock is -1")
    //    void workerWaitsForNewBlockInitial() {
    //        setupWorkerTest(); // State is ACTIVE, currentBlock is -1
    //        connection.startRequestWorker();
    //
    //        // Verify no requests sent after a short delay
    //        verify(requestObserver, after(100).never()).onNext(any());
    //    }
    //
    //    @Test
    //    @DisplayName("Worker does not send requests when block state is null and not behind")
    //    void workerWaitsForNewBlockStateNull() {
    //        setupWorkerTest();
    //        connection.setCurrentBlockNumber(BLOCK_NUMBER);
    //        when(blockStreamStateManager.getBlockState(BLOCK_NUMBER)).thenReturn(null);
    //        when(blockStreamStateManager.getBlockNumber()).thenReturn(BLOCK_NUMBER - 1);
    //
    //        connection.startRequestWorker();
    //
    //        // Verify no requests sent after a short delay
    //        verify(requestObserver, after(100).never()).onNext(any());
    //    }
    //
    //    @Test
    //    @DisplayName("Worker handles stream failure when block state is null and behind")
    //    void workerHandlesFailureWhenBehind() {
    //        setupWorkerTest();
    //        connection.setCurrentBlockNumber(BLOCK_NUMBER);
    //        when(blockStreamStateManager.getBlockState(BLOCK_NUMBER)).thenReturn(null);
    //        when(blockStreamStateManager.getBlockNumber()).thenReturn(BLOCK_NUMBER + 1);
    //
    //        connection.startRequestWorker();
    //
    //        // Verify failure outcome: state becomes UNINITIALIZED, manager notified
    //        verify(blockNodeConnectionManager, timeout(VERIFY_TIMEOUT.toMillis())).handleConnectionError(connection);
    //        assertEquals(BlockNodeConnection.ConnectionState.UNINITIALIZED, connection.getState());
    //        assertThat(logCaptor.debugLogs())
    //                .anyMatch(log -> log.contains(
    //                        "Block 10 state not found and lowest available block is 11, ending stream for node "
    //                                + CONNECTION_DESCRIPTOR));
    //    }
    //
    //    @Test
    //    @DisplayName("Worker does not send requests when block state has no requests")
    //    void workerWaitsForNewRequests() {
    //        setupWorkerTest();
    //        connection.setCurrentBlockNumber(BLOCK_NUMBER);
    //        BlockState blockState = new BlockState(BLOCK_NUMBER, Collections.emptyList());
    //        when(blockStreamStateManager.getBlockState(BLOCK_NUMBER)).thenReturn(blockState);
    //
    //        connection.startRequestWorker();
    //
    //        // Verify no requests sent after a short delay
    //        verify(requestObserver, after(100).never()).onNext(any());
    //    }
    //
    //    @Test
    //    @DisplayName("Worker does not send requests when more requests are needed")
    //    void workerWaitsForMoreRequests() {
    //        setupWorkerTest();
    //        connection.setCurrentBlockNumber(BLOCK_NUMBER);
    //        PublishStreamRequest request1 = createMockRequest(BLOCK_NUMBER, 0);
    //        BlockState blockState = new BlockState(BLOCK_NUMBER, List.of(createBlockItem(request1)));
    //        when(blockStreamStateManager.getBlockState(BLOCK_NUMBER)).thenReturn(blockState);
    //        TestUtils.setInternalState(
    //                connection, "currentRequestIndex", new AtomicInteger(1)); // Already processed request 0
    //
    //        connection.startRequestWorker();
    //
    //        // Verify no requests sent after a short delay
    //        verify(requestObserver, after(100).never()).onNext(any());
    //    }
    //
    //    @Test
    //    @DisplayName("Worker processes available requests")
    //    void workerProcessesRequests() {
    //        setupWorkerTest();
    //        connection.setCurrentBlockNumber(BLOCK_NUMBER);
    //        PublishStreamRequest request1 = createMockRequest(BLOCK_NUMBER, 0);
    //        PublishStreamRequest request2 = createMockRequest(BLOCK_NUMBER, 1);
    //        BlockState blockState =
    //                new BlockState(BLOCK_NUMBER, List.of(createBlockItem(request1), createBlockItem(request2)));
    //        when(blockStreamStateManager.getBlockState(BLOCK_NUMBER)).thenReturn(blockState);
    //
    //        connection.startRequestWorker();
    //
    //        // Verify requests are sent in order
    //        verify(requestObserver, timeout(VERIFY_TIMEOUT.toMillis())).onNext(request1);
    //        verify(requestObserver, timeout(VERIFY_TIMEOUT.toMillis())).onNext(request2);
    //        assertEquals(2, connection.getCurrentRequestIndex());
    //    }
    //
    //    @Test
    //    @DisplayName("Worker moves to next block when current block is processed")
    //    void workerMovesToNextBlock() {
    //        setupWorkerTest();
    //        connection.setCurrentBlockNumber(BLOCK_NUMBER);
    //        PublishStreamRequest request1 = createMockRequest(BLOCK_NUMBER, 0);
    //        BlockState blockState1 = new BlockState(BLOCK_NUMBER, List.of(createBlockItem(request1)));
    //        when(blockStreamStateManager.getBlockState(BLOCK_NUMBER)).thenReturn(blockState1);
    //
    //        // Simulate request 0 already sent and acknowledged (moves index to 1)
    //        TestUtils.setInternalState(connection, "currentRequestIndex", new AtomicInteger(1));
    //
    //        // Setup next block
    //        PublishStreamRequest requestNext = createMockRequest(NEXT_BLOCK_NUMBER, 0);
    //        BlockState blockStateNext = new BlockState(NEXT_BLOCK_NUMBER, List.of(createBlockItem(requestNext)));
    //        when(blockStreamStateManager.getBlockState(NEXT_BLOCK_NUMBER)).thenReturn(blockStateNext);
    //
    //        when(blockNodeConnectionManager.higherPriorityStarted(connection)).thenReturn(false);
    //
    //        // Notify that block 1 is complete and next block (11) is available
    //        // This simulates the state manager signaling completion implicitly by providing the next block
    //        // In the real flow, notifyNewBlockAvailable might be called.
    //        connection.notifyNewBlockAvailable();
    //        connection.startRequestWorker();
    //
    //        // Verify request from the next block is sent
    //        verify(requestObserver, timeout(VERIFY_TIMEOUT.toMillis())).onNext(requestNext);
    //        assertEquals(NEXT_BLOCK_NUMBER, connection.getCurrentBlockNumber());
    //        assertEquals(1, connection.getCurrentRequestIndex()); // Index reset for new block
    //    }
    //
    //    @Test
    //    @DisplayName("Worker stops when higher priority connection starts")
    //    void workerStopsForHigherPriority() throws InterruptedException {
    //        setupWorkerTest();
    //        connection.setCurrentBlockNumber(BLOCK_NUMBER);
    //        PublishStreamRequest request1 = createMockRequest(BLOCK_NUMBER, 0);
    //        BlockState blockState = new BlockState(BLOCK_NUMBER, List.of(createBlockItem(request1)));
    //        when(blockStreamStateManager.getBlockState(BLOCK_NUMBER)).thenReturn(blockState);
    //        TestUtils.setInternalState(connection, "currentRequestIndex", new AtomicInteger(1)); // Block 10 processed
    //
    //        // Setup next block state, but worker shouldn't reach it
    //        PublishStreamRequest requestNext = createMockRequest(NEXT_BLOCK_NUMBER, 0);
    //        BlockState blockStateNext = new BlockState(NEXT_BLOCK_NUMBER, List.of(createBlockItem(requestNext)));
    //        when(blockStreamStateManager.getBlockState(NEXT_BLOCK_NUMBER)).thenReturn(blockStateNext);
    //
    //        // Signal higher priority connection started
    //        when(blockNodeConnectionManager.higherPriorityStarted(connection)).thenReturn(true);
    //
    //        connection.startRequestWorker();
    //        Thread workerThread = TestUtils.getInternalState(connection, "requestWorker", Thread.class);
    //        assertNotNull(workerThread);
    //
    //        // Verify worker thread exits
    //        workerThread.join(VERIFY_TIMEOUT.toMillis());
    //        assertFalse(workerThread.isAlive(), "Worker thread should exit");
    //
    //        // Verify no requests from the next block were sent
    //        verify(requestObserver, never()).onNext(requestNext);
    //        assertThat(logCaptor.debugLogs()).anyMatch(log -> log.contains("Request worker thread exiting for node"));
    //    }
    //
    //    @Test
    //    @DisplayName("Worker handles jump signal")
    //    void workerHandlesJumpSignal() {
    //        setupWorkerTest();
    //        long jumpTarget = 20L;
    //
    //        // Setup state for the target block
    //        PublishStreamRequest targetRequest = createMockRequest(jumpTarget, 0);
    //        BlockState targetBlockState = new BlockState(jumpTarget, List.of(createBlockItem(targetRequest)));
    //        when(blockStreamStateManager.getBlockState(jumpTarget)).thenReturn(targetBlockState);
    //
    //        // Start worker, then trigger jump
    //        connection.startRequestWorker();
    //        connection.jumpToBlock(jumpTarget);
    //
    //        // Verify request from the target block is sent
    //        verify(requestObserver, timeout(VERIFY_TIMEOUT.toMillis())).onNext(targetRequest);
    //        assertEquals(jumpTarget, connection.getCurrentBlockNumber(), "Current block number should be updated");
    //        assertEquals(1, connection.getCurrentRequestIndex());
    //        assertThat(logCaptor.debugLogs())
    //                .anyMatch(log -> log.contains("Worker received jump signal to block " + jumpTarget));
    //    }
    //
    //    @Test
    //    @DisplayName("Worker handles InterruptedException")
    //    void workerHandlesInterrupt() throws InterruptedException {
    //        setupWorkerTest();
    //        connection.startRequestWorker();
    //        Thread workerThread = TestUtils.getInternalState(connection, "requestWorker", Thread.class);
    //        assertNotNull(workerThread);
    //
    //        // Interrupt the worker
    //        workerThread.interrupt();
    //
    //        // Verify worker thread exits and logs error
    //        workerThread.join(VERIFY_TIMEOUT.toMillis());
    //        assertFalse(workerThread.isAlive(), "Worker thread should have terminated");
    //        assertThat(logCaptor.errorLogs())
    //                .anyMatch(log -> log.contains("Request worker thread interrupted for node " +
    // CONNECTION_DESCRIPTOR));
    //        // State should likely become UNINITIALIZED upon interruption failure
    //        assertEquals(BlockNodeConnection.ConnectionState.UNINITIALIZED, connection.getState());
    //        verify(blockNodeConnectionManager, timeout(VERIFY_TIMEOUT.toMillis())).handleConnectionError(connection);
    //    }
    //
    //    @Test
    //    @DisplayName("Worker handles general Exception from state manager")
    //    void workerHandlesGeneralException() {
    //        setupWorkerTest();
    //        RuntimeException testException = new RuntimeException("Test worker error");
    //        when(blockStreamStateManager.getBlockState(anyLong())).thenThrow(testException);
    //        connection.setCurrentBlockNumber(BLOCK_NUMBER);
    //
    //        connection.startRequestWorker();
    //
    //        // Verify failure outcome
    //        verify(blockNodeConnectionManager, timeout(VERIFY_TIMEOUT.toMillis())).handleConnectionError(connection);
    //        assertEquals(BlockNodeConnection.ConnectionState.UNINITIALIZED, connection.getState());
    //        assertThat(logCaptor.errorLogs())
    //                .anyMatch(log -> log.contains("Error in request worker thread for node " +
    // CONNECTION_DESCRIPTOR));
    //    }
    //
    //    @Test
    //    @DisplayName("Send request successfully")
    //    void sendRequestSuccess() {
    //        connection.createRequestObserver();
    //        PublishStreamRequest request = createMockRequest(BLOCK_NUMBER, 0);
    //        connection.sendRequest(request);
    //        verify(requestObserver).onNext(request);
    //    }
    //
    //    @Test
    //    @DisplayName("Send request handles observer exception")
    //    void sendRequestHandlesException() {
    //        connection.createRequestObserver();
    //        PublishStreamRequest request = createMockRequest(BLOCK_NUMBER, 0);
    //        StatusRuntimeException grpcError = new StatusRuntimeException(Status.UNAVAILABLE);
    //        doThrow(grpcError).when(requestObserver).onNext(request);
    //
    //        connection.sendRequest(request);
    //
    //        // Verify outcome: logs error, state becomes UNINITIALIZED, manager notified
    //        verify(requestObserver).onNext(request);
    //        assertThat(logCaptor.errorLogs()).anyMatch(log -> log.contains("Error sending request for node"));
    //        assertEquals(BlockNodeConnection.ConnectionState.UNINITIALIZED, connection.getState());
    //        verify(blockNodeConnectionManager).handleConnectionError(connection);
    //    }
    //
    //    @Test
    //    @DisplayName("Send request handles null observer")
    //    void sendRequestHandlesNullObserver() {
    //        // Don't call createRequestObserver()
    //        PublishStreamRequest request = createMockRequest(BLOCK_NUMBER, 0);
    //
    //        connection.sendRequest(request);
    //
    //        // Verify outcome: logs error, state becomes UNINITIALIZED, manager notified
    //        verify(requestObserver, never()).onNext(any());
    //        assertThat(logCaptor.errorLogs()).anyMatch(log -> log.contains("Request observer is null for node"));
    //        assertEquals(BlockNodeConnection.ConnectionState.UNINITIALIZED, connection.getState());
    //        verify(blockNodeConnectionManager).handleConnectionError(connection);
    //    }
    //
    //    @Test
    //    @DisplayName("Close stops worker and completes observer")
    //    void closeStopsWorkerAndCompletesObserver() throws InterruptedException {
    //        setupWorkerTest();
    //        connection.startRequestWorker();
    //        Thread worker = TestUtils.getInternalState(connection, "requestWorker", Thread.class);
    //        assertNotNull(worker);
    //        assertTrue(worker.isAlive());
    //
    //        connection.close();
    //
    //        // Verify worker thread stops
    //        worker.join(VERIFY_TIMEOUT.toMillis());
    //        assertFalse(worker.isAlive(), "Worker thread should be stopped");
    //
    //        // Verify observer is completed and nulled out
    //        verify(requestObserver).onCompleted();
    //        assertNull(TestUtils.getInternalState(connection, "requestObserver", StreamObserver.class));
    //    }
    //
    //    @Test
    //    @DisplayName("Close handles null worker or observer gracefully")
    //    void closeHandlesNulls() {
    //        // Scenario 1: Observer exists, worker is null
    //        connection.createRequestObserver();
    //        assertNull(TestUtils.getInternalState(connection, "requestWorker", Thread.class));
    //        connection.close();
    //        verify(requestObserver).onCompleted(); // Observer should still be completed
    //        assertNull(TestUtils.getInternalState(connection, "requestObserver", StreamObserver.class));
    //
    //        // Reset mocks for Scenario 2
    //        clearInvocations(requestObserver, blockNodeConnectionManager, grpcServiceClient);
    //        connection = spy(new BlockNodeConnection(
    //                blockNodeConfig,
    //                blockNodeConnectionManager,
    //                blockStreamStateManager,
    //                grpcServiceClient,
    //                scheduler,
    //                blockStreamMetrics));
    //        when(grpcServiceClient.bidi(any(), eq(connection))).thenReturn((StreamObserver) requestObserver);
    //
    //        // Scenario 2: Observer and worker are null
    //        assertNull(TestUtils.getInternalState(connection, "requestObserver", StreamObserver.class));
    //        assertNull(TestUtils.getInternalState(connection, "requestWorker", Thread.class));
    //        connection.close(); // Should not throw NPE
    //        verify(requestObserver, never()).onCompleted();
    //    }
    //
    //    @Test
    //    @DisplayName("Close handles exception during onCompleted")
    //    void closeHandlesExceptionOnCompleted() {
    //        connection.createRequestObserver();
    //        StatusRuntimeException grpcError = new StatusRuntimeException(Status.INTERNAL);
    //        doThrow(grpcError).when(requestObserver).onCompleted();
    //
    //        connection.close();
    //
    //        // Verify onCompleted was still called, error logged, observer nulled
    //        verify(requestObserver).onCompleted();
    //        assertThat(logCaptor.warnLogs()).anyMatch(log -> log.contains("Error closing stream observer for node"));
    //        assertNull(TestUtils.getInternalState(connection, "requestObserver", StreamObserver.class));
    //    }
    //
    //    @Test
    //    @DisplayName("isActive returns true only for ACTIVE state")
    //    void isActiveStateCheck() {
    //        connection.updateConnectionState(BlockNodeConnection.ConnectionState.UNINITIALIZED);
    //        assertFalse(connection.isActive());
    //        connection.updateConnectionState(BlockNodeConnection.ConnectionState.PENDING);
    //        assertFalse(connection.isActive());
    //        connection.updateConnectionState(BlockNodeConnection.ConnectionState.ACTIVE);
    //        assertTrue(connection.isActive());
    //    }
    //
    //    @Test
    //    @DisplayName("Getters return correct values")
    //    void getters() {
    //        assertEquals(blockNodeConfig, connection.getNodeConfig());
    //        assertEquals(-1, connection.getCurrentBlockNumber());
    //        assertEquals(0, connection.getCurrentRequestIndex());
    //        assertEquals(BlockNodeConnection.ConnectionState.UNINITIALIZED, connection.getState());
    //    }
    //
    //    @Test
    //    @DisplayName("notifyNewRequestAvailable notifies waiter")
    //    void notifyNewRequestAvailable() throws InterruptedException {
    //        final CountDownLatch notifyLatch = new CountDownLatch(1);
    //        final Object monitor = TestUtils.getInternalState(connection, "newRequestAvailable", Object.class);
    //
    //        Thread waiter = new Thread(() -> {
    //            synchronized (monitor) {
    //                try {
    //                    monitor.wait(2000);
    //                    notifyLatch.countDown();
    //                } catch (InterruptedException e) {
    //                    Thread.currentThread().interrupt();
    //                }
    //            }
    //        });
    //
    //        waiter.start();
    //        Thread.sleep(50); // Ensure waiter is waiting
    //
    //        connection.notifyNewRequestAvailable();
    //
    //        assertTrue(notifyLatch.await(1, TimeUnit.SECONDS), "Waiter should have been notified");
    //        waiter.join();
    //    }
    //
    //    @Test
    //    @DisplayName("notifyNewBlockAvailable notifies waiter")
    //    void notifyNewBlockAvailable() throws InterruptedException {
    //        final CountDownLatch notifyLatch = new CountDownLatch(1);
    //        final Object monitor = TestUtils.getInternalState(connection, "newBlockAvailable", Object.class);
    //
    //        Thread waiter = new Thread(() -> {
    //            synchronized (monitor) {
    //                try {
    //                    monitor.wait(2000);
    //                    notifyLatch.countDown();
    //                } catch (InterruptedException e) {
    //                    Thread.currentThread().interrupt();
    //                }
    //            }
    //        });
    //
    //        waiter.start();
    //        Thread.sleep(50); // Ensure waiter is waiting
    //
    //        connection.notifyNewBlockAvailable();
    //
    //        assertTrue(notifyLatch.await(1, TimeUnit.SECONDS), "Waiter should have been notified");
    //        waiter.join();
    //    }
    //
    //    @Test
    //    @DisplayName("setCurrentBlockNumber updates block and resets index")
    //    void setCurrentBlockNumber() {
    //        TestUtils.setInternalState(connection, "currentRequestIndex", new AtomicInteger(5));
    //        connection.setCurrentBlockNumber(BLOCK_NUMBER);
    //
    //        assertEquals(BLOCK_NUMBER, connection.getCurrentBlockNumber());
    //        assertEquals(0, connection.getCurrentRequestIndex(), "Request index should be reset");
    //    }
    //
    //    @Test
    //    @DisplayName("restartStreamAtBlock schedules restart task")
    //    void restartStreamAtBlock() {
    //        long targetBlock = 15L;
    //        connection.restartStreamAtBlock(targetBlock);
    //
    //        verify(scheduler).schedule(runnableCaptor.capture(), delayCaptor.capture(), timeUnitCaptor.capture());
    //        assertEquals(RECONNECT_SECS, delayCaptor.getValue());
    //        assertEquals(TimeUnit.SECONDS, timeUnitCaptor.getValue());
    //
    //        // Simulate task execution
    //        runnableCaptor.getValue().run();
    //
    //        // Verify manager is called to handle restart
    //        verify(blockNodeConnectionManager).scheduleRestart(connection, targetBlock);
    //    }
    //
    //    @Test
    //    @DisplayName("jumpToBlock sets target and notifies worker")
    //    void jumpToBlock() throws InterruptedException {
    //        long targetBlock = 25L;
    //        final CountDownLatch notifyLatch = new CountDownLatch(1);
    //        final Object monitor = TestUtils.getInternalState(connection, "newBlockAvailable", Object.class);
    //
    //        // Start a thread to wait on the notification monitor
    //        Thread waiter = new Thread(() -> {
    //            synchronized (monitor) {
    //                try {
    //                    monitor.wait(2000);
    //                    notifyLatch.countDown();
    //                } catch (InterruptedException e) {
    //                    Thread.currentThread().interrupt();
    //                }
    //            }
    //        });
    //        waiter.start();
    //        Thread.sleep(50); // Ensure waiter is waiting
    //
    //        // Perform the jump
    //        connection.jumpToBlock(targetBlock);
    //
    //        // Verify target is set and monitor was notified
    //        assertEquals(
    //                targetBlock,
    //                TestUtils.getInternalState(connection, "jumpTargetBlock", AtomicLong.class)
    //                        .get());
    //        assertTrue(notifyLatch.await(1, TimeUnit.SECONDS), "Worker should have been notified");
    //        waiter.join();
    //    }
    //
    //    @Test
    //    @DisplayName("onNext handles Acknowledgement OK")
    //    void onNextAcknowledgementOk() {
    //        connection.setCurrentBlockNumber(BLOCK_NUMBER);
    //        TestUtils.setInternalState(connection, "currentRequestIndex", new AtomicInteger(0));
    //        PublishStreamResponse response = createAcknowledgementResponse(BLOCK_NUMBER, 0, OK);
    //
    //        connection.onNext(response);
    //
    //        assertEquals(BLOCK_NUMBER, connection.getCurrentBlockNumber());
    //        assertEquals(1, connection.getCurrentRequestIndex()); // Index should advance
    //        verify(blockStreamMetrics).incrementBlockAckReceivedCount();
    //        verify(connection, times(1)).notifyNewRequestAvailable(); // Should notify worker
    //    }
    //
    //    @Test
    //    @DisplayName("onNext handles Acknowledgement DUPLICATE_REQUEST")
    //    void onNextAcknowledgementDuplicate() {
    //        connection.setCurrentBlockNumber(BLOCK_NUMBER);
    //        TestUtils.setInternalState(connection, "currentRequestIndex", new AtomicInteger(1)); // Expecting index 1
    //        PublishStreamResponse response = createAcknowledgementResponse(BLOCK_NUMBER, 0, DUPLICATE_REQUEST);
    //
    //        connection.onNext(response);
    //
    //        assertEquals(BLOCK_NUMBER, connection.getCurrentBlockNumber());
    //        assertEquals(1, connection.getCurrentRequestIndex()); // Index should NOT advance
    //        verify(blockStreamMetrics, never()).incrementBlockAckReceivedCount();
    //        assertThat(logCaptor.warnLogs())
    //                .anyMatch(log -> log.contains("Received DUPLICATE_REQUEST for block 10, request 0"));
    //        verify(connection, never()).notifyNewRequestAvailable(); // Should not notify worker
    //    }
    //
    //    @Test
    //    @DisplayName("onNext handles Acknowledgement OUT_OF_ORDER")
    //    void onNextAcknowledgementOutOfOrder() {
    //        connection.setCurrentBlockNumber(BLOCK_NUMBER);
    //        TestUtils.setInternalState(connection, "currentRequestIndex", new AtomicInteger(0)); // Expecting index 0
    //        PublishStreamResponse response = createAcknowledgementResponse(BLOCK_NUMBER, 1, OUT_OF_ORDER);
    //
    //        connection.onNext(response);
    //
    //        // Verify failure outcome
    //        assertEquals(BlockNodeConnection.ConnectionState.UNINITIALIZED, connection.getState());
    //        verify(blockNodeConnectionManager).handleConnectionError(connection);
    //        assertEquals(0, connection.getCurrentRequestIndex()); // Index unchanged
    //        verify(blockStreamMetrics, never()).incrementBlockAckReceivedCount();
    //        assertThat(logCaptor.errorLogs())
    //                .anyMatch(log -> log.contains("Received OUT_OF_ORDER acknowledgement for block 10, request 1"));
    //    }
    //
    //    @Test
    //    @DisplayName("onNext handles Acknowledgement ERROR")
    //    void onNextAcknowledgementError() {
    //        connection.setCurrentBlockNumber(BLOCK_NUMBER);
    //        TestUtils.setInternalState(connection, "currentRequestIndex", new AtomicInteger(0)); // Expecting index 0
    //        PublishStreamResponse response = createAcknowledgementResponse(BLOCK_NUMBER, 0, ERROR);
    //
    //        connection.onNext(response);
    //
    //        // Verify failure outcome
    //        assertEquals(BlockNodeConnection.ConnectionState.UNINITIALIZED, connection.getState());
    //        verify(blockNodeConnectionManager).handleConnectionError(connection);
    //        assertEquals(0, connection.getCurrentRequestIndex()); // Index unchanged
    //        verify(blockStreamMetrics, never()).incrementBlockAckReceivedCount();
    //        assertThat(logCaptor.errorLogs())
    //                .anyMatch(log -> log.contains("Received ERROR acknowledgement for block 10, request 0"));
    //    }
    //
    //    @Test
    //    @DisplayName("onNext handles Acknowledgement Mismatched Block Number")
    //    void onNextAcknowledgementMismatchBlock() {
    //        connection.setCurrentBlockNumber(BLOCK_NUMBER); // Expecting block 10
    //        TestUtils.setInternalState(connection, "currentRequestIndex", new AtomicInteger(0));
    //        PublishStreamResponse response = createAcknowledgementResponse(BLOCK_NUMBER + 1, 0, OK);
    //
    //        connection.onNext(response);
    //
    //        // Verify failure outcome
    //        assertEquals(BlockNodeConnection.ConnectionState.UNINITIALIZED, connection.getState());
    //        verify(blockNodeConnectionManager).handleConnectionError(connection);
    //        assertEquals(0, connection.getCurrentRequestIndex()); // Index unchanged
    //        assertThat(logCaptor.errorLogs())
    //                .anyMatch(log -> log.contains("Received acknowledgement for unexpected block number 11"));
    //    }
    //
    //    @Test
    //    @DisplayName("onNext handles Acknowledgement Mismatched Request Index")
    //    void onNextAcknowledgementMismatchIndex() {
    //        connection.setCurrentBlockNumber(BLOCK_NUMBER);
    //        TestUtils.setInternalState(connection, "currentRequestIndex", new AtomicInteger(1)); // Expecting index 1
    //        PublishStreamResponse response = createAcknowledgementResponse(BLOCK_NUMBER, 0, OK);
    //
    //        connection.onNext(response);
    //
    //        // Verify failure outcome
    //        assertEquals(BlockNodeConnection.ConnectionState.UNINITIALIZED, connection.getState());
    //        verify(blockNodeConnectionManager).handleConnectionError(connection);
    //        assertEquals(1, connection.getCurrentRequestIndex()); // Index unchanged
    //        assertThat(logCaptor.errorLogs())
    //                .anyMatch(log -> log.contains("Received acknowledgement for unexpected request index 0"));
    //    }
    //
    //    @Test
    //    @DisplayName("onNext handles EndOfStream - Immediate Restart")
    //    void onNextEndOfStreamImmediateRestart() {
    //        PublishStreamResponse response = createEndOfStreamResponse(BLOCK_NUMBER);
    //        connection.setCurrentBlockNumber(BLOCK_NUMBER);
    //        TestUtils.setInternalState(connection, "endOfStreamImmediateRestarts", new AtomicInteger(0));
    //
    //        connection.onNext(response);
    //
    //        // Verify immediate restart scheduled
    //        verify(scheduler).schedule(runnableCaptor.capture(), eq(0L), eq(TimeUnit.MILLISECONDS));
    //        assertEquals(
    //                1,
    //                TestUtils.getInternalState(connection, "endOfStreamImmediateRestarts", AtomicInteger.class)
    //                        .get());
    //
    //        // Simulate task execution
    //        runnableCaptor.getValue().run();
    //        verify(blockNodeConnectionManager).scheduleRestart(connection, BLOCK_NUMBER + 1);
    //        verify(blockStreamMetrics).incrementEndOfStreamCount(END_OF_STREAM);
    //    }
    //
    //    @Test
    //    @DisplayName("onNext handles EndOfStream - Exponential Backoff")
    //    void onNextEndOfStreamExponentialBackoff() {
    //        PublishStreamResponse response = createEndOfStreamResponse(BLOCK_NUMBER);
    //        connection.setCurrentBlockNumber(BLOCK_NUMBER);
    //        // Set state to trigger exponential backoff
    //        TestUtils.setInternalState(
    //                connection, "endOfStreamImmediateRestarts", new AtomicInteger(MAX_END_OF_STREAM_RESTARTS_VALUE));
    //        TestUtils.setInternalState(connection, "endOfStreamExpBackoffs", new AtomicInteger(0));
    //
    //        // First backoff
    //        connection.onNext(response);
    //        verify(scheduler).schedule(runnableCaptor.capture(), delayCaptor.capture(), timeUnitCaptor.capture());
    //        assertEquals(
    //                1,
    //                TestUtils.getInternalState(connection, "endOfStreamExpBackoffs", AtomicInteger.class)
    //                        .get());
    //        assertEquals(1 * RECONNECT_SECS, delayCaptor.getValue());
    //        assertEquals(TimeUnit.SECONDS, timeUnitCaptor.getValue());
    //        runnableCaptor.getValue().run();
    //        verify(blockNodeConnectionManager).scheduleRestart(connection, BLOCK_NUMBER + 1);
    //        verify(blockStreamMetrics).incrementEndOfStreamCount(END_OF_STREAM);
    //
    //        // Second backoff
    //        connection.onNext(response);
    //        verify(scheduler, times(2)).schedule(runnableCaptor.capture(), delayCaptor.capture(),
    // timeUnitCaptor.capture());
    //        assertEquals(
    //                2,
    //                TestUtils.getInternalState(connection, "endOfStreamExpBackoffs", AtomicInteger.class)
    //                        .get());
    //        assertEquals(2 * RECONNECT_SECS, delayCaptor.getValue());
    //        assertEquals(TimeUnit.SECONDS, timeUnitCaptor.getValue());
    //        runnableCaptor.getValue().run();
    //        verify(blockNodeConnectionManager, times(2)).scheduleRestart(connection, BLOCK_NUMBER + 1);
    //        verify(blockStreamMetrics, times(2)).incrementEndOfStreamCount(END_OF_STREAM);
    //    }
    //
    //    @Test
    //    @DisplayName("onNext handles EndOfStream - Max Exponential Retries Exceeded")
    //    void onNextEndOfStreamMaxRetries() {
    //        PublishStreamResponse response = createEndOfStreamResponse(BLOCK_NUMBER);
    //        connection.setCurrentBlockNumber(BLOCK_NUMBER);
    //        // Set state to exceed max retries
    //        TestUtils.setInternalState(
    //                connection, "endOfStreamImmediateRestarts", new AtomicInteger(MAX_END_OF_STREAM_RESTARTS_VALUE));
    //        TestUtils.setInternalState(
    //                connection, "endOfStreamExpBackoffs", new AtomicInteger(MAX_END_OF_STREAM_EXP_RETRIES_VALUE));
    //
    //        connection.onNext(response);
    //
    //        // Verify failure outcome (no more scheduling)
    //        verify(scheduler, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    //        assertEquals(BlockNodeConnection.ConnectionState.UNINITIALIZED, connection.getState());
    //        verify(blockNodeConnectionManager).handleConnectionError(connection);
    //        assertThat(logCaptor.errorLogs()).anyMatch(log -> log.contains("Maximum EndOfStream retries exceeded"));
    //        verify(blockStreamMetrics).incrementEndOfStreamCount(END_OF_STREAM);
    //    }
    //
    //    @Test
    //    @DisplayName("onNext handles EndOfStream - Clears Counters on Success")
    //    void onNextClearsCountersOnSuccess() {
    //        // Set counters to non-zero values
    //        TestUtils.setInternalState(connection, "endOfStreamImmediateRestarts", new AtomicInteger(1));
    //        TestUtils.setInternalState(connection, "endOfStreamExpBackoffs", new AtomicInteger(2));
    //
    //        // Receive a successful acknowledgement
    //        PublishStreamResponse ackResponse = createAcknowledgementResponse(BLOCK_NUMBER, 0, OK);
    //        connection.setCurrentBlockNumber(BLOCK_NUMBER);
    //        TestUtils.setInternalState(connection, "currentRequestIndex", new AtomicInteger(0));
    //
    //        connection.onNext(ackResponse);
    //
    //        // Verify counters are reset
    //        assertEquals(
    //                0,
    //                TestUtils.getInternalState(connection, "endOfStreamImmediateRestarts", AtomicInteger.class)
    //                        .get());
    //        assertEquals(
    //                0,
    //                TestUtils.getInternalState(connection, "endOfStreamExpBackoffs", AtomicInteger.class)
    //                        .get());
    //        verify(blockStreamMetrics).incrementBlockAckReceivedCount(); // Verify success was processed
    //    }
    //
    //    @Test
    //    @DisplayName("onNext handles SkipBlock")
    //    void onNextSkipBlock() {
    //        long targetBlock = BLOCK_NUMBER + 5;
    //        PublishStreamResponse response = createSkipBlockResponse(targetBlock);
    //        connection.setCurrentBlockNumber(BLOCK_NUMBER);
    //
    //        connection.onNext(response);
    //
    //        // Verify jumpToBlock was called
    //        verify(connection).jumpToBlock(targetBlock);
    //        assertThat(logCaptor.infoLogs())
    //                .anyMatch(log -> log.contains("Received SkipBlock request to block " + targetBlock));
    //        verify(blockStreamMetrics).incrementSkipBlockCount();
    //    }
    //
    //    @Test
    //    @DisplayName("onNext handles ResendBlock")
    //    void onNextResendBlock() {
    //        long targetBlock = BLOCK_NUMBER - 2;
    //        PublishStreamResponse response = createResendBlockResponse(targetBlock);
    //        connection.setCurrentBlockNumber(BLOCK_NUMBER);
    //
    //        connection.onNext(response);
    //
    //        // Verify jumpToBlock was called
    //        verify(connection).jumpToBlock(targetBlock);
    //        assertThat(logCaptor.infoLogs())
    //                .anyMatch(log -> log.contains("Received ResendBlock request for block " + targetBlock));
    //        verify(blockStreamMetrics).incrementResendBlockCount();
    //    }
    //
    //    @Test
    //    @DisplayName("onNext handles Unknown Response Type")
    //    void onNextUnknownResponseType() {
    //        PublishStreamResponse response = PublishStreamResponse.newBuilder().build(); // Empty response
    //        connection.setCurrentBlockNumber(BLOCK_NUMBER);
    //
    //        connection.onNext(response);
    //
    //        // Verify failure outcome
    //        assertEquals(BlockNodeConnection.ConnectionState.UNINITIALIZED, connection.getState());
    //        verify(blockNodeConnectionManager).handleConnectionError(connection);
    //        assertThat(logCaptor.errorLogs()).anyMatch(log -> log.contains("Received unknown response type"));
    //    }
    //
    //    @Test
    //    @DisplayName("onError logs error and handles failure")
    //    void onErrorHandlesFailure() {
    //        Throwable error = new StatusRuntimeException(Status.UNAVAILABLE.withDescription("Network issue"));
    //
    //        connection.onError(error);
    //
    //        // Verify failure outcome
    //        assertEquals(BlockNodeConnection.ConnectionState.UNINITIALIZED, connection.getState());
    //        verify(blockNodeConnectionManager).handleConnectionError(connection);
    //        assertThat(logCaptor.errorLogs())
    //                .anyMatch(log -> log.contains("Error in block stream for node " + CONNECTION_DESCRIPTOR));
    //    }
    //
    //    @Test
    //    @DisplayName("onCompleted logs completion and handles failure")
    //    void onCompletedHandlesFailure() {
    //        connection.onCompleted();
    //
    //        // Verify failure outcome
    //        assertEquals(BlockNodeConnection.ConnectionState.UNINITIALIZED, connection.getState());
    //        verify(blockNodeConnectionManager).handleConnectionError(connection);
    //        assertThat(logCaptor.warnLogs())
    //                .anyMatch(log -> log.contains("Block stream completed unexpectedly for node " +
    // CONNECTION_DESCRIPTOR));
    //    }
    //
    //    private void setupWorkerTest() {
    //        connection.updateConnectionState(BlockNodeConnection.ConnectionState.ACTIVE);
    //        connection.createRequestObserver();
    //    }
    //
    //    private PublishStreamRequest createMockRequest(long blockNumber, int requestIndex) {
    //        return PublishStreamRequest.newBuilder()
    //                .setBlockItem(BlockItem.newBuilder()
    //                        .setBlockNumber(blockNumber)
    //                        .setRequestIndex(requestIndex)
    //                        .setConsensusTimestamp(Timestamp.newBuilder()
    //                                .setSeconds(1234567890L + blockNumber)
    //                                .setNanos(0))
    //                        .build())
    //                .build();
    //    }
    //
    //    private BlockItem createBlockItem(PublishStreamRequest request) {
    //        return request.getBlockItem(); // Assuming request contains BlockItem
    //    }
    //
    //    private PublishStreamResponse createAcknowledgementResponse(
    //            long blockNumber, int requestIndex, PublishStreamResponseCode code) {
    //        return PublishStreamResponse.newBuilder()
    //                .setAcknowledgement(PublishStreamResponse.Acknowledgement.newBuilder()
    //                        .setBlockAcknowledgement(BlockAcknowledgement.newBuilder()
    //                                .setBlockNumber(blockNumber)
    //                                .setRequestIndex(requestIndex)
    //                                .setResponseCode(code)
    //                                .build())
    //                        .build())
    //                .build();
    //    }
    //
    //    private PublishStreamResponse createEndOfStreamResponse(long lastAckedBlockNumber) {
    //        return PublishStreamResponse.newBuilder()
    //                .setEndOfStream(PublishStreamResponse.EndOfStream.newBuilder()
    //                        .setLastAcknowledgedBlockNumber(lastAckedBlockNumber)
    //                        .build())
    //                .build();
    //    }
    //
    //    private PublishStreamResponse createSkipBlockResponse(long targetBlockNumber) {
    //        return PublishStreamResponse.newBuilder()
    //                .setSkipBlock(PublishStreamResponse.SkipBlock.newBuilder()
    //                        .setTargetBlockNumber(targetBlockNumber)
    //                        .build())
    //                .build();
    //    }
    //
    //    private PublishStreamResponse createResendBlockResponse(long targetBlockNumber) {
    //        return PublishStreamResponse.newBuilder()
    //                .setResendBlock(PublishStreamResponse.ResendBlock.newBuilder()
    //                        .setTargetBlockNumber(targetBlockNumber)
    //                        .build())
    //                .build();
    //    }
    //
    //    static class TestUtils {
    //        @SuppressWarnings("unchecked")
    //        public static <T> T getInternalState(Object target, String fieldName, Class<T> type) {
    //            try {
    //                Field field = target.getClass().getDeclaredField(fieldName);
    //                field.setAccessible(true);
    //                return type.cast(field.get(target));
    //            } catch (NoSuchFieldException | IllegalAccessException e) {
    //                throw new RuntimeException("Failed to get internal state '" + fieldName + "'", e);
    //            }
    //        }
    //
    //        public static void setInternalState(Object target, String fieldName, Object value) {
    //            try {
    //                Field field = target.getClass().getDeclaredField(fieldName);
    //                field.setAccessible(true);
    //                field.set(target, value);
    //            } catch (NoSuchFieldException | IllegalAccessException e) {
    //                throw new RuntimeException("Failed to set internal state '" + fieldName + "'", e);
    //            }
    //        }
    //    }
}
