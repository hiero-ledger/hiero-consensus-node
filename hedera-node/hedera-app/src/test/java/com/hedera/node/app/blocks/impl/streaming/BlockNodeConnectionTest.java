// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.hapi.block.PublishStreamResponseCode.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hedera.hapi.block.BlockItemSet;
import com.hedera.hapi.block.PublishStreamRequest;
import com.hedera.hapi.block.PublishStreamResponse;
import com.hedera.hapi.block.PublishStreamResponseCode;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.CreateAccountOutput;
import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.fixtures.util.LogCaptureExtension;
import com.hedera.node.app.spi.fixtures.util.LoggingSubject;
import com.hedera.node.app.spi.fixtures.util.LoggingTarget;
import com.hedera.node.internal.network.BlockNodeConfig;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.helidon.webclient.grpc.GrpcServiceClient;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
    private static final String CONNECTION_DESCRIPTOR = HOST_ADDRESS + ":" + PORT;
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

    @BeforeEach
    void setUp() {
        when(blockNodeConfig.address()).thenReturn(HOST_ADDRESS);
        when(blockNodeConfig.port()).thenReturn(PORT);
        lenient().when(blockNodeConfig.priority()).thenReturn(1);

        connection = spy(new BlockNodeConnection(
                blockNodeConfig,
                blockNodeConnectionManager,
                blockStreamStateManager,
                grpcServiceClient,
                scheduler,
                blockStreamMetrics));

        lenient().when(grpcServiceClient.bidi(any(), eq(connection))).thenReturn((StreamObserver) requestObserver);

        workerExecutorService = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        connection.close();
        workerExecutorService.shutdownNow();
        if (!workerExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
            System.err.println("Test worker executor service did not terminate.");
        }
    }

    @Test
    @DisplayName("Constructor throws NullPointerException for null arguments")
    void constructorNullChecks() {
        assertThrows(
                NullPointerException.class,
                () -> new BlockNodeConnection(
                        null,
                        blockNodeConnectionManager,
                        blockStreamStateManager,
                        grpcServiceClient,
                        scheduler,
                        blockStreamMetrics),
                "nodeConfig must not be null");
        assertThrows(
                NullPointerException.class,
                () -> new BlockNodeConnection(
                        blockNodeConfig,
                        null,
                        blockStreamStateManager,
                        grpcServiceClient,
                        scheduler,
                        blockStreamMetrics),
                "blockNodeConnectionManager must not be null");
        assertThrows(
                NullPointerException.class,
                () -> new BlockNodeConnection(
                        blockNodeConfig,
                        blockNodeConnectionManager,
                        null,
                        grpcServiceClient,
                        scheduler,
                        blockStreamMetrics),
                "blockStreamStateManager must not be null");
        assertThrows(
                NullPointerException.class,
                () -> new BlockNodeConnection(
                        blockNodeConfig,
                        blockNodeConnectionManager,
                        blockStreamStateManager,
                        null,
                        scheduler,
                        blockStreamMetrics),
                "grpcServiceClient must not be null");
        assertThrows(
                NullPointerException.class,
                () -> new BlockNodeConnection(
                        blockNodeConfig,
                        blockNodeConnectionManager,
                        blockStreamStateManager,
                        grpcServiceClient,
                        null,
                        blockStreamMetrics),
                "scheduler must not be null");
        assertThrows(
                NullPointerException.class,
                () -> new BlockNodeConnection(
                        blockNodeConfig,
                        blockNodeConnectionManager,
                        blockStreamStateManager,
                        grpcServiceClient,
                        scheduler,
                        null),
                "blockStreamMetrics must not be null");
    }

    @Test
    @DisplayName("Create request observer successfully")
    void createRequestObserverSuccess() {
        connection.createRequestObserver();
        verify(grpcServiceClient).bidi(blockNodeConnectionManager.getGrpcEndPoint(), connection);
        assertNotNull(TestUtils.getInternalState(connection, "requestObserver", StreamObserver.class));
    }

    @Test
    @DisplayName("Create request observer handles gRPC client exception")
    void createRequestObserverException() {
        doThrow(new RuntimeException("gRPC error")).when(grpcServiceClient).bidi(any(), eq(connection));
        assertThrows(RuntimeException.class, () -> connection.createRequestObserver());
    }

    @Test
    @DisplayName("Update and get connection state")
    void updateAndGetConnectionState() {
        assertEquals(BlockNodeConnection.ConnectionState.UNINITIALIZED, connection.getState());
        connection.updateConnectionState(BlockNodeConnection.ConnectionState.PENDING);
        assertEquals(BlockNodeConnection.ConnectionState.PENDING, connection.getState());
        connection.updateConnectionState(BlockNodeConnection.ConnectionState.ACTIVE);
        assertEquals(BlockNodeConnection.ConnectionState.ACTIVE, connection.getState());
    }

    @Test
    @DisplayName("Start request worker when active")
    void startRequestWorkerWhenActive() throws InterruptedException {
        connection.updateConnectionState(BlockNodeConnection.ConnectionState.ACTIVE);
        connection.createRequestObserver();
        connection.startRequestWorker();

        // Allow worker to start
        Thread.sleep(100);

        Thread worker = TestUtils.getInternalState(connection, "requestWorker", Thread.class);
        assertNotNull(worker);
        assertTrue(worker.isAlive());
        assertThat(logCaptor.debugLogs())
                .anyMatch(log -> log.contains("Started request worker thread for block node " + CONNECTION_DESCRIPTOR));

        worker.interrupt();
        worker.join(1000);
        assertFalse(worker.isAlive());
    }

    @Test
    @DisplayName("Start request worker stops existing worker")
    void startRequestWorkerStopsExisting() throws InterruptedException {
        connection.updateConnectionState(BlockNodeConnection.ConnectionState.ACTIVE);
        connection.createRequestObserver();

        connection.startRequestWorker();
        Thread firstWorker = TestUtils.getInternalState(connection, "requestWorker", Thread.class);
        assertNotNull(firstWorker);
        assertTrue(firstWorker.isAlive());

        Thread.sleep(50);

        connection.startRequestWorker();
        Thread secondWorker = TestUtils.getInternalState(connection, "requestWorker", Thread.class);
        assertNotNull(secondWorker);
        assertTrue(secondWorker.isAlive());
        assertNotSame(firstWorker, secondWorker);

        // Check that the first worker was interrupted and stopped
        firstWorker.join(1000);
        assertFalse(firstWorker.isAlive(), "First worker thread should have stopped");

        secondWorker.interrupt();
        secondWorker.join(1000);
        assertFalse(secondWorker.isAlive());
    }

    @Test
    @DisplayName("Start request worker does nothing when connection is not active")
    void startRequestWorkerWhenConnectionNotActive() {
        connection.updateConnectionState(BlockNodeConnection.ConnectionState.PENDING);
        connection.createRequestObserver();
        connection.startRequestWorker();
        assertNull(TestUtils.getInternalState(connection, "requestWorker", Thread.class));

        connection.updateConnectionState(BlockNodeConnection.ConnectionState.UNINITIALIZED);
        connection.startRequestWorker();
        assertNull(TestUtils.getInternalState(connection, "requestWorker", Thread.class));
    }

    @Test
    @DisplayName("Worker does not send requests when currentBlock is -1")
    void workerWaitsForNewBlockInitial() {
        setupWorkerTest(); // State is ACTIVE, currentBlock is -1
        connection.startRequestWorker();

        // Verify no requests sent after a short delay
        verify(requestObserver, after(100).never()).onNext(any());
    }

    @Test
    @DisplayName("Worker does not send requests when block state is null and not behind")
    void workerWaitsForNewBlockStateNull() {
        setupWorkerTest();
        connection.setCurrentBlockNumber(BLOCK_NUMBER);
        when(blockStreamStateManager.getBlockState(BLOCK_NUMBER)).thenReturn(null);
        when(blockStreamStateManager.getBlockNumber()).thenReturn(BLOCK_NUMBER - 1);

        connection.startRequestWorker();

        // Verify no requests sent after a short delay
        verify(requestObserver, after(100).never()).onNext(any());
    }

    @Test
    @DisplayName("Worker handles stream failure when block state is null and behind")
    void workerHandlesFailureWhenBehind() {
        setupWorkerTest();
        connection.setCurrentBlockNumber(BLOCK_NUMBER);
        when(blockStreamStateManager.getBlockState(BLOCK_NUMBER)).thenReturn(null);
        when(blockStreamStateManager.getBlockNumber()).thenReturn(BLOCK_NUMBER + 1);

        connection.startRequestWorker();

        // Verify failure outcome: state becomes UNINITIALIZED, manager notified
        verify(blockNodeConnectionManager, timeout(VERIFY_TIMEOUT.toMillis())).handleConnectionError(connection);
        assertEquals(BlockNodeConnection.ConnectionState.UNINITIALIZED, connection.getState());
        assertThat(logCaptor.debugLogs())
                .anyMatch(log -> log.contains(
                        "Block 10 state not found and lowest available block is 11, ending stream for node "
                                + CONNECTION_DESCRIPTOR));
    }

    @Test
    @DisplayName("Worker does not send requests when block state has no requests")
    void workerWaitsForNewRequests() {
        setupWorkerTest();
        connection.setCurrentBlockNumber(BLOCK_NUMBER);
        BlockState blockState = new BlockState(BLOCK_NUMBER, Collections.emptyList());
        when(blockStreamStateManager.getBlockState(BLOCK_NUMBER)).thenReturn(blockState);

        connection.startRequestWorker();

        // Verify no requests sent after a short delay
        verify(requestObserver, after(100).never()).onNext(any());
    }

    @Test
    @DisplayName("Worker does not send requests when more requests are needed")
    void workerWaitsForMoreRequests() {
        setupWorkerTest();
        connection.setCurrentBlockNumber(BLOCK_NUMBER);

        PublishStreamRequest request1 = createMockRequestWithOneBlockItem();

        BlockState blockState = buildBlockState(BLOCK_NUMBER, false, request1);
        when(blockStreamStateManager.getBlockState(BLOCK_NUMBER)).thenReturn(blockState);
        TestUtils.setInternalState(
                connection, "currentRequestIndex", new AtomicInteger(1)); // Already processed request 0

        connection.startRequestWorker();

        // Verify no requests sent after a short delay
        verify(requestObserver, after(100).never()).onNext(any());
    }

    @Test
    @DisplayName("Worker processes available requests")
    void workerProcessesRequests() {
        setupWorkerTest();
        connection.setCurrentBlockNumber(BLOCK_NUMBER);

        PublishStreamRequest request1 = createMockRequestWithOneBlockItem();
        PublishStreamRequest request2 = createMockRequestWithOneBlockItem();

        BlockState blockState = buildBlockState(BLOCK_NUMBER, false, request1, request2);
        when(blockStreamStateManager.getBlockState(BLOCK_NUMBER)).thenReturn(blockState);

        connection.startRequestWorker();

        // Verify requests are sent in order
        verify(requestObserver, timeout(VERIFY_TIMEOUT.toMillis())).onNext(request1);
        verify(requestObserver, timeout(VERIFY_TIMEOUT.toMillis())).onNext(request2);
        assertEquals(2, connection.getCurrentRequestIndex());
    }

    @Test
    @DisplayName("Worker moves to next block when current block is processed")
    void workerMovesToNextBlock() {
        setupWorkerTest();
        connection.setCurrentBlockNumber(BLOCK_NUMBER);

        PublishStreamRequest request1 = createMockRequestWithOneBlockItem();

        BlockState blockState1 = buildBlockState(BLOCK_NUMBER, true, request1);
        when(blockStreamStateManager.getBlockState(BLOCK_NUMBER)).thenReturn(blockState1);

        // Setup next block
        PublishStreamRequest requestNext = createMockRequestWithOneBlockItem();

        BlockState blockStateNext = buildBlockState(BLOCK_NUMBER, false, requestNext);
        when(blockStreamStateManager.getBlockState(NEXT_BLOCK_NUMBER)).thenReturn(blockStateNext);
        when(blockNodeConnectionManager.higherPriorityStarted(connection)).thenReturn(false);

        // Notify that block 1 is complete and next block (11) is available
        // This simulates the state manager signaling completion implicitly by providing the next block
        // In the real flow, notifyNewBlockAvailable might be called.
        connection.notifyNewBlockAvailable();
        connection.startRequestWorker();

        // Verify request from the next block is sent
        verify(requestObserver, timeout(VERIFY_TIMEOUT.toMillis())).onNext(requestNext);
        assertEquals(NEXT_BLOCK_NUMBER, connection.getCurrentBlockNumber());
        assertEquals(1, connection.getCurrentRequestIndex()); // Index reset for new block
    }

    @Test
    @DisplayName("Worker stops when higher priority connection starts")
    void workerStopsForHigherPriority() throws InterruptedException {
        setupWorkerTest();
        connection.setCurrentBlockNumber(BLOCK_NUMBER);

        // Process first block state
        PublishStreamRequest request1 = createMockRequestWithOneBlockItem();
        BlockState blockState = buildBlockState(BLOCK_NUMBER, true, request1);
        when(blockStreamStateManager.getBlockState(BLOCK_NUMBER)).thenReturn(blockState);

        // Setup next block state, but worker shouldn't reach it
        PublishStreamRequest requestNext = createMockRequestWithOneBlockItem();
        buildBlockState(NEXT_BLOCK_NUMBER, false, requestNext);

        // Signal higher priority connection started
        when(blockNodeConnectionManager.higherPriorityStarted(connection)).thenReturn(true);

        connection.startRequestWorker();
        Thread workerThread = TestUtils.getInternalState(connection, "requestWorker", Thread.class);
        assertNotNull(workerThread);

        // Verify worker thread exits
        workerThread.join(VERIFY_TIMEOUT.toMillis());
        assertFalse(workerThread.isAlive(), "Worker thread should exit");

        // Verify no requests from the next block were sent
        verify(requestObserver, never()).onNext(requestNext);
        assertThat(logCaptor.debugLogs()).anyMatch(log -> log.contains("Request worker thread exiting for node"));
    }

    @Test
    @DisplayName("Worker handles jump signal")
    void workerHandlesJumpSignal() {
        setupWorkerTest();
        long jumpTarget = 20L;

        // Setup state for the target block
        PublishStreamRequest targetRequest = createMockRequestWithOneBlockItem();
        BlockState targetBlockState = buildBlockState(jumpTarget, false, targetRequest);
        when(blockStreamStateManager.getBlockState(jumpTarget)).thenReturn(targetBlockState);

        // Trigger jump and start the worker
        connection.jumpToBlock(jumpTarget);
        connection.startRequestWorker();

        // Verify request from the target block is sent
        verify(requestObserver, timeout(VERIFY_TIMEOUT.toMillis())).onNext(targetRequest);
        assertEquals(jumpTarget, connection.getCurrentBlockNumber(), "Current block number should be updated");
        assertEquals(1, connection.getCurrentRequestIndex());
        assertThat(logCaptor.debugLogs())
                .anyMatch(log -> log.contains("Worker received jump signal to block " + jumpTarget));
    }

    @Test
    @DisplayName("Worker handles InterruptedException")
    void workerHandlesInterrupt() throws InterruptedException {
        setupWorkerTest();
        connection.startRequestWorker();
        Thread workerThread = TestUtils.getInternalState(connection, "requestWorker", Thread.class);
        assertNotNull(workerThread);

        // Interrupt the worker
        workerThread.interrupt();

        // Verify worker thread exits and logs error
        workerThread.join(VERIFY_TIMEOUT.toMillis());
        assertFalse(workerThread.isAlive(), "Worker thread should have terminated");
        assertThat(logCaptor.errorLogs())
                .anyMatch(log -> log.contains("Request worker thread interrupted for node " + CONNECTION_DESCRIPTOR));
    }

    @Test
    @DisplayName("Worker handles general Exception from state manager")
    void workerHandlesGeneralException() {
        setupWorkerTest();
        RuntimeException testException = new RuntimeException("Test worker error");
        when(blockStreamStateManager.getBlockState(anyLong())).thenThrow(testException);
        connection.setCurrentBlockNumber(BLOCK_NUMBER);

        connection.startRequestWorker();

        // Verify failure outcome
        verify(blockNodeConnectionManager, timeout(VERIFY_TIMEOUT.toMillis())).handleConnectionError(connection);
        assertEquals(BlockNodeConnection.ConnectionState.UNINITIALIZED, connection.getState());
        assertThat(logCaptor.errorLogs())
                .anyMatch(log -> log.contains("Error in request worker thread for node " + CONNECTION_DESCRIPTOR));
    }

    @Test
    @DisplayName("Send request successfully")
    void sendRequestSuccess() {
        connection.createRequestObserver();

        // Set connection state to ACTIVE
        connection.updateConnectionState(BlockNodeConnection.ConnectionState.ACTIVE);

        // Mock the request observer
        TestUtils.setInternalState(connection, "requestObserver", requestObserver);

        PublishStreamRequest request = createMockRequestWithOneBlockItem();
        connection.sendRequest(request);

        verify(requestObserver).onNext(request);
    }

    @Test
    @DisplayName("Send request handles observer exception")
    void sendRequestHandlesException() {
        connection.createRequestObserver();

        // Set connection state to ACTIVE
        connection.updateConnectionState(BlockNodeConnection.ConnectionState.ACTIVE);

        // Mock the request observer
        TestUtils.setInternalState(connection, "requestObserver", requestObserver);

        PublishStreamRequest request = createMockRequestWithOneBlockItem();
        StatusRuntimeException grpcError = new StatusRuntimeException(Status.UNAVAILABLE);
        doThrow(grpcError).when(requestObserver).onNext(request);

        StatusRuntimeException thrown = assertThrows(
                StatusRuntimeException.class,
                () -> connection.sendRequest(request),
                "Expected sendRequest to throw, but it didn't");

        // Verify UNAVAILABLE status
        assertEquals(Status.UNAVAILABLE.getCode(), thrown.getStatus().getCode());
    }

    @Test
    @DisplayName("Send request handles null observer")
    void sendRequestHandlesNullObserver() {
        // Don't call createRequestObserver()
        PublishStreamRequest request = createMockRequestWithOneBlockItem();

        connection.sendRequest(request);

        // Verify that the request was not sent
        verify(requestObserver, never()).onNext(any());
        assertEquals(BlockNodeConnection.ConnectionState.UNINITIALIZED, connection.getState());
    }

    @Test
    @DisplayName("Close stops worker and completes observer")
    void closeStopsWorkerAndCompletesObserver() throws InterruptedException {
        setupWorkerTest();
        connection.startRequestWorker();
        Thread worker = TestUtils.getInternalState(connection, "requestWorker", Thread.class);
        assertNotNull(worker);
        assertTrue(worker.isAlive());

        connection.close();

        // Verify worker thread stops
        worker.join(VERIFY_TIMEOUT.toMillis());
        assertFalse(worker.isAlive(), "Worker thread should be stopped");

        // Verify observer is completed and null after close
        verify(requestObserver).onCompleted();
        assertNull(TestUtils.getInternalState(connection, "requestObserver", StreamObserver.class));
    }

    @Test
    @DisplayName("Close handles null worker gracefully")
    void closeHandlesNullWorker() {
        // Observer exists, worker is null
        connection.createRequestObserver();
        assertNull(TestUtils.getInternalState(connection, "requestWorker", Thread.class));
        connection.close();

        // Verify observer is completed and null after close
        verify(requestObserver).onCompleted(); // Observer should still be completed
        assertNull(TestUtils.getInternalState(connection, "requestObserver", StreamObserver.class));
    }

    @Test
    @DisplayName("Close handles null worker and observer gracefully")
    void closeHandlesNullWorkerAndObserver() {
        // create new connection so that both observer and worker are null
        BlockNodeConnection connection = new BlockNodeConnection(
                blockNodeConfig,
                blockNodeConnectionManager,
                blockStreamStateManager,
                grpcServiceClient,
                scheduler,
                blockStreamMetrics);

        assertNull(TestUtils.getInternalState(connection, "requestObserver", StreamObserver.class));
        assertNull(TestUtils.getInternalState(connection, "requestWorker", Thread.class));

        connection.close(); // Should not throw NPE

        verify(requestObserver, never()).onCompleted();
    }

    @Test
    @DisplayName("Close handles exception during onCompleted")
    void closeHandlesExceptionOnCompleted() {
        connection.createRequestObserver();
        StatusRuntimeException grpcError = new StatusRuntimeException(Status.INTERNAL);
        doThrow(grpcError).when(requestObserver).onCompleted();

        connection.close();

        // Verify onCompleted was still called, error logged, observer is null
        verify(requestObserver).onCompleted();
        assertThat(logCaptor.warnLogs()).anyMatch(log -> log.contains("Error while completing request observer"));
        assertNull(TestUtils.getInternalState(connection, "requestObserver", StreamObserver.class));
    }

    @Test
    @DisplayName("isActive returns true only for ACTIVE state")
    void isActiveStateCheck() {
        connection.updateConnectionState(BlockNodeConnection.ConnectionState.UNINITIALIZED);
        assertFalse(connection.isActive());
        connection.updateConnectionState(BlockNodeConnection.ConnectionState.PENDING);
        assertFalse(connection.isActive());
        connection.updateConnectionState(BlockNodeConnection.ConnectionState.ACTIVE);
        assertTrue(connection.isActive());
    }

    @Test
    @DisplayName("Getters return correct values")
    void getters() {
        assertEquals(blockNodeConfig, connection.getNodeConfig());
        assertEquals(-1, connection.getCurrentBlockNumber());
        assertEquals(0, connection.getCurrentRequestIndex());
        assertEquals(BlockNodeConnection.ConnectionState.UNINITIALIZED, connection.getState());
    }

    @Test
    @DisplayName("notifyNewRequestAvailable notifies waiter")
    void notifyNewRequestAvailable() throws InterruptedException {
        final CountDownLatch notifyLatch = new CountDownLatch(1);
        final Object monitor = TestUtils.getInternalState(connection, "newRequestAvailable", Object.class);

        Thread waiter = new Thread(() -> {
            synchronized (monitor) {
                try {
                    monitor.wait(2000);
                    notifyLatch.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        waiter.start();
        Thread.sleep(50); // Ensure waiter is waiting

        connection.notifyNewRequestAvailable();

        assertTrue(notifyLatch.await(1, TimeUnit.SECONDS), "Waiter should have been notified");
        waiter.join();
    }

    @Test
    @DisplayName("notifyNewBlockAvailable notifies waiter")
    void notifyNewBlockAvailable() throws InterruptedException {
        final CountDownLatch notifyLatch = new CountDownLatch(1);
        final Object monitor = TestUtils.getInternalState(connection, "newBlockAvailable", Object.class);

        Thread waiter = new Thread(() -> {
            synchronized (monitor) {
                try {
                    monitor.wait(2000);
                    notifyLatch.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        waiter.start();
        Thread.sleep(50); // Ensure waiter is waiting

        connection.notifyNewBlockAvailable();

        assertTrue(notifyLatch.await(1, TimeUnit.SECONDS), "Waiter should have been notified");
        waiter.join();
    }

    @Test
    @DisplayName("setCurrentBlockNumber updates block and resets index")
    void setCurrentBlockNumber() {
        TestUtils.setInternalState(connection, "currentRequestIndex", new AtomicInteger(5));
        connection.setCurrentBlockNumber(BLOCK_NUMBER);

        assertEquals(BLOCK_NUMBER, connection.getCurrentBlockNumber());
        assertEquals(0, connection.getCurrentRequestIndex(), "Request index should be reset");
    }

    @Test
    @DisplayName("restartStreamAtBlock schedules restart task")
    void restartStreamAtBlock() {
        long targetBlock = 15L;
        assertNotEquals(targetBlock, connection.getCurrentBlockNumber());

        connection.restartStreamAtBlock(targetBlock);

        assertEquals(targetBlock, connection.getCurrentBlockNumber());

        verify(blockNodeConnectionManager).scheduleRetry(connection, BlockNodeConnectionManager.INITIAL_RETRY_DELAY);

        assertThat(logCaptor.debugLogs()).anyMatch(log -> log.contains("Restarting stream at block " + targetBlock));
        assertThat(logCaptor.debugLogs()).anyMatch(log -> log.contains("Stream restarted at block " + targetBlock));
    }

    @Test
    @DisplayName("jumpToBlock sets target and notifies worker")
    void jumpToBlock() throws InterruptedException {
        long targetBlock = 25L;
        final CountDownLatch notifyLatch = new CountDownLatch(1);
        final Object monitor = TestUtils.getInternalState(connection, "newBlockAvailable", Object.class);

        // Start a thread to wait on the notification monitor
        Thread waiter = new Thread(() -> {
            synchronized (monitor) {
                try {
                    monitor.wait(2000);
                    notifyLatch.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        waiter.start();
        Thread.sleep(50); // Ensure waiter is waiting

        // Perform the jump
        connection.jumpToBlock(targetBlock);

        // Verify target is set and monitor was notified
        assertEquals(
                targetBlock,
                TestUtils.getInternalState(connection, "jumpTargetBlock", AtomicLong.class)
                        .get());
        assertTrue(notifyLatch.await(1, TimeUnit.SECONDS), "Worker should have been notified");
        waiter.join();
    }

    @Test
    @DisplayName("Handles ACK when block doesn't exist and streaming is behind, but producing has caught up")
    void handlesAckWhenStreamingBehindButProducingCaughtUp() {
        connection.setCurrentBlockNumber(BLOCK_NUMBER - 1);
        TestUtils.setInternalState(connection, "currentRequestIndex", new AtomicInteger(0));
        PublishStreamResponse response = createAcknowledgementResponse(BLOCK_NUMBER, false);

        // Stub the metrics increment and the producing block number to match the streaming block number
        doNothing().when(blockStreamMetrics).incrementBlockAckReceivedCount();
        when(blockStreamStateManager.getBlockNumber()).thenReturn(BLOCK_NUMBER);

        connection.onNext(response);

        // Verify block state is removed
        assertNull(blockStreamStateManager.getBlockState(BLOCK_NUMBER));

        // Verify jumpToBlock is called
        verify(connection).jumpToBlock(BLOCK_NUMBER + 1);

        // Verify metrics
        verify(blockStreamMetrics).incrementBlockAckReceivedCount();

        // Verify block number is updated
        assertEquals(BLOCK_NUMBER - 1, connection.getCurrentBlockNumber());

        // Verify block acknowledgement logs
        assertThat(logCaptor.debugLogs())
                .anyMatch(log -> log.contains("Block " + BLOCK_NUMBER)
                        && log.contains("acknowledgement received from block node"));

        // Verify logs
        assertThat(logCaptor.debugLogs())
                .anyMatch(log -> log.contains("moving streaming ahead to Block " + (BLOCK_NUMBER + 1)));
    }

    @Test
    @DisplayName("Handles ACK when block doesn't exist and both streaming and producing are on the acknowledged block")
    void handlesAckWhenStreamingAndProducingOnAcknowledgedBlock() {
        connection.setCurrentBlockNumber(BLOCK_NUMBER);
        TestUtils.setInternalState(connection, "currentRequestIndex", new AtomicInteger(0));
        PublishStreamResponse response = createAcknowledgementResponse(BLOCK_NUMBER, false);

        // Stub the metrics increment and the producing block number to match the streaming block number
        doNothing().when(blockStreamMetrics).incrementBlockAckReceivedCount();
        when(blockStreamStateManager.getBlockNumber()).thenReturn(BLOCK_NUMBER);

        connection.onNext(response);

        // Verify jumpToBlock is called
        verify(connection).jumpToBlock(BLOCK_NUMBER + 1);

        // Verify metrics
        verify(blockStreamMetrics).incrementBlockAckReceivedCount();

        // Verify block number is updated
        assertEquals(BLOCK_NUMBER, connection.getCurrentBlockNumber());

        // Verify block acknowledgement logs
        assertThat(logCaptor.debugLogs())
                .anyMatch(log -> log.contains("Block " + BLOCK_NUMBER)
                        && log.contains("acknowledgement received from block node"));

        // Verify logs
        assertThat(logCaptor.debugLogs())
                .anyMatch(log -> log.contains("Currently streaming Block " + BLOCK_NUMBER + " to Block Node")
                        && log.contains("acknowledged Block " + BLOCK_NUMBER)
                        && log.contains("moving streaming ahead to Block " + (BLOCK_NUMBER + 1)));
    }

    @Test
    @DisplayName("Handles ACK when block doesn't exist and streaming is ahead of the acknowledged block")
    void handlesAckWhenStreamingAheadOfAcknowledgedBlock() {
        connection.setCurrentBlockNumber(BLOCK_NUMBER + 1);
        TestUtils.setInternalState(connection, "currentRequestIndex", new AtomicInteger(0));
        PublishStreamResponse response = createAcknowledgementResponse(BLOCK_NUMBER, false);

        // Stub the metrics increment and the producing block number to match the streaming block number
        doNothing().when(blockStreamMetrics).incrementBlockAckReceivedCount();
        when(blockStreamStateManager.getBlockNumber()).thenReturn(BLOCK_NUMBER);

        connection.onNext(response);

        // Verify block state is removed
        assertNull(blockStreamStateManager.getBlockState(BLOCK_NUMBER));

        // Verify metrics
        verify(blockStreamMetrics).incrementBlockAckReceivedCount();

        // Verify block acknowledgement logs
        assertThat(logCaptor.debugLogs())
                .anyMatch(log -> log.contains("Block " + BLOCK_NUMBER)
                        && log.contains("acknowledgement received from block node"));
    }

    @Test
    @DisplayName("Handles ACK when block doesn't exist and streaming is behind the acknowledged block")
    void handlesAckWhenStreamingBehindAcknowledgedBlock() {
        connection.setCurrentBlockNumber(BLOCK_NUMBER);
        TestUtils.setInternalState(connection, "currentRequestIndex", new AtomicInteger(0));
        PublishStreamResponse response = createAcknowledgementResponse(BLOCK_NUMBER + 1, false);

        // Stub the metrics increment and the producing block number to match the streaming block number
        doNothing().when(blockStreamMetrics).incrementBlockAckReceivedCount();
        when(blockStreamStateManager.getBlockNumber()).thenReturn(BLOCK_NUMBER);

        connection.onNext(response);

        // Verify jumpToBlock is called
        verify(connection).jumpToBlock(BLOCK_NUMBER + 2);

        // Verify metrics
        verify(blockStreamMetrics).incrementBlockAckReceivedCount();

        // Verify block number is updated
        assertEquals(BLOCK_NUMBER, connection.getCurrentBlockNumber());

        // Verify block acknowledgement logs
        assertThat(logCaptor.debugLogs())
                .anyMatch(log -> log.contains("Block " + (BLOCK_NUMBER + 1))
                        && log.contains("acknowledgement received from block node"));

        // Verify logs
        assertThat(logCaptor.debugLogs())
                .anyMatch(log -> log.contains("Currently producing Block " + BLOCK_NUMBER + " to Block Node")
                        && log.contains("acknowledged Block " + (BLOCK_NUMBER + 1))
                        && log.contains("moving streaming ahead to Block " + (BLOCK_NUMBER + 2)));
    }

    @Test
    @DisplayName("Handles ACK when block already exists and streaming is behind, but producing has caught up")
    void handlesAckWhenBlockAlreadyExistsWithStreamingBehindButProducingCaughtUp() {
        connection.setCurrentBlockNumber(BLOCK_NUMBER - 1);
        TestUtils.setInternalState(connection, "currentRequestIndex", new AtomicInteger(0));
        PublishStreamResponse response = createAcknowledgementResponse(BLOCK_NUMBER, true);

        // Stub the metrics increment and the producing block number to match the streaming block number
        doNothing().when(blockStreamMetrics).incrementBlockAckReceivedCount();
        when(blockStreamStateManager.getBlockNumber()).thenReturn(BLOCK_NUMBER);

        connection.onNext(response);

        // Verify block state is removed
        assertNull(blockStreamStateManager.getBlockState(BLOCK_NUMBER));

        // Verify jumpToBlock is called
        verify(connection).jumpToBlock(BLOCK_NUMBER + 1);

        // Verify metrics
        verify(blockStreamMetrics).incrementBlockAckReceivedCount();

        // Verify block number is updated
        assertEquals(BLOCK_NUMBER - 1, connection.getCurrentBlockNumber());

        // Verify block acknowledgement logs
        assertThat(logCaptor.debugLogs())
                .anyMatch(
                        log -> log.contains("Block " + BLOCK_NUMBER) && log.contains("already exists on block node "));

        // Verify logs
        assertThat(logCaptor.debugLogs())
                .anyMatch(log -> log.contains("moving streaming ahead to Block " + (BLOCK_NUMBER + 1)));
    }

    @Test
    @DisplayName("Handles ACK when block already exists and both streaming and producing are on the acknowledged block")
    void handlesAckWhenBlockAlreadyExistsWithStreamingAndProducingOnAcknowledgedBlock() {
        connection.setCurrentBlockNumber(BLOCK_NUMBER);
        TestUtils.setInternalState(connection, "currentRequestIndex", new AtomicInteger(0));
        PublishStreamResponse response = createAcknowledgementResponse(BLOCK_NUMBER, true);

        // Stub the metrics increment and the producing block number to match the streaming block number
        doNothing().when(blockStreamMetrics).incrementBlockAckReceivedCount();
        when(blockStreamStateManager.getBlockNumber()).thenReturn(BLOCK_NUMBER);

        connection.onNext(response);

        // Verify jumpToBlock is called
        verify(connection).jumpToBlock(BLOCK_NUMBER + 1);

        // Verify metrics
        verify(blockStreamMetrics).incrementBlockAckReceivedCount();

        // Verify block number is updated
        assertEquals(BLOCK_NUMBER, connection.getCurrentBlockNumber());

        // Verify block acknowledgement logs
        assertThat(logCaptor.debugLogs())
                .anyMatch(
                        log -> log.contains("Block " + BLOCK_NUMBER) && log.contains("already exists on block node "));

        // Verify logs
        assertThat(logCaptor.debugLogs())
                .anyMatch(log -> log.contains("Currently streaming Block " + BLOCK_NUMBER + " to Block Node")
                        && log.contains("acknowledged Block " + BLOCK_NUMBER)
                        && log.contains("moving streaming ahead to Block " + (BLOCK_NUMBER + 1)));
    }

    @Test
    @DisplayName("Handles ACK when block already exists and streaming is ahead of the acknowledged block")
    void handlesAckWhenBlockAlreadyExistsWithStreamingAheadOfAcknowledgedBlock() {
        connection.setCurrentBlockNumber(BLOCK_NUMBER + 1);
        TestUtils.setInternalState(connection, "currentRequestIndex", new AtomicInteger(0));
        PublishStreamResponse response = createAcknowledgementResponse(BLOCK_NUMBER, true);

        // Stub the metrics increment and the producing block number to match the streaming block number
        doNothing().when(blockStreamMetrics).incrementBlockAckReceivedCount();
        when(blockStreamStateManager.getBlockNumber()).thenReturn(BLOCK_NUMBER);

        connection.onNext(response);

        // Verify block state is removed
        assertNull(blockStreamStateManager.getBlockState(BLOCK_NUMBER));

        // Verify metrics
        verify(blockStreamMetrics).incrementBlockAckReceivedCount();

        // Verify block acknowledgement logs
        assertThat(logCaptor.debugLogs())
                .anyMatch(
                        log -> log.contains("Block " + BLOCK_NUMBER) && log.contains("already exists on block node "));
    }

    @Test
    @DisplayName("Handles ACK when block already exists and streaming is behind the acknowledged block")
    void handlesAckWhenBlockAlreadyExistsWithStreamingBehindTheAcknowledgedBlock() {
        connection.setCurrentBlockNumber(BLOCK_NUMBER);
        TestUtils.setInternalState(connection, "currentRequestIndex", new AtomicInteger(0));
        PublishStreamResponse response = createAcknowledgementResponse(BLOCK_NUMBER + 1, true);

        // Stub the metrics increment and the producing block number to match the streaming block number
        doNothing().when(blockStreamMetrics).incrementBlockAckReceivedCount();
        when(blockStreamStateManager.getBlockNumber()).thenReturn(BLOCK_NUMBER);

        connection.onNext(response);

        // Verify jumpToBlock is called
        verify(connection).jumpToBlock(BLOCK_NUMBER + 2);

        // Verify metrics
        verify(blockStreamMetrics).incrementBlockAckReceivedCount();

        // Verify block number is updated
        assertEquals(BLOCK_NUMBER, connection.getCurrentBlockNumber());

        // Verify block acknowledgement logs
        assertThat(logCaptor.debugLogs())
                .anyMatch(log ->
                        log.contains("Block " + (BLOCK_NUMBER + 1)) && log.contains("already exists on block node "));

        // Verify logs
        assertThat(logCaptor.debugLogs())
                .anyMatch(log -> log.contains("Currently producing Block " + BLOCK_NUMBER + " to Block Node")
                        && log.contains("acknowledged Block " + (BLOCK_NUMBER + 1))
                        && log.contains("moving streaming ahead to Block " + (BLOCK_NUMBER + 2)));
    }

    @Test
    @DisplayName("Ignores ACK when current block is not initialized (-1)")
    void ignoresAckWhenCurrentBlockIsUninitialized() {
        connection.setCurrentBlockNumber(-1);
        TestUtils.setInternalState(connection, "currentRequestIndex", new AtomicInteger(0));
        PublishStreamResponse response = createAcknowledgementResponse(BLOCK_NUMBER, false);

        // Stub the metrics increment and the producing block number to match the streaming block number
        doNothing().when(blockStreamMetrics).incrementBlockAckReceivedCount();
        doNothing().when(blockNodeConnectionManager).updateLastVerifiedBlock(any(), anyLong());
        when(blockStreamStateManager.getBlockNumber()).thenReturn(BLOCK_NUMBER);

        connection.onNext(response);

        // Verify metrics
        verify(blockStreamMetrics).incrementBlockAckReceivedCount();
        verify(blockNodeConnectionManager).updateLastVerifiedBlock(eq(blockNodeConfig), eq(BLOCK_NUMBER));

        // Verify there are no other calls
        verify(connection, never()).jumpToBlock(anyInt());

        // Verify no more interactions with the metrics
        verifyNoMoreInteractions(blockStreamMetrics);
    }

    @Test
    @DisplayName("Unknown acknowledgement response code")
    void unknownAckResponseCode() {
        connection.setCurrentBlockNumber(BLOCK_NUMBER);
        TestUtils.setInternalState(connection, "currentRequestIndex", new AtomicInteger(0));
        // Create publish stream response with unknown acknowledgement
        PublishStreamResponse response = createUnknownAcknowledgementResponse();

        connection.onNext(response);

        // Verify connection state
        assertEquals(BlockNodeConnection.ConnectionState.UNINITIALIZED, connection.getState());

        // Verify there are no other calls
        verify(connection, never()).jumpToBlock(anyInt());

        // Verify no interactions with the metrics
        verifyNoInteractions(blockStreamMetrics);

        // Verify logs
        assertThat(logCaptor.warnLogs()).anyMatch(log -> log.contains("Unknown acknowledgement received: "));
    }

    @ParameterizedTest(name = "{index}: code={0}, retryCount={1}, expectRetry={2}")
    @MethodSource("internalErrorRetryCodes")
    @DisplayName("onNext handles EndOfStream with internal error code respecting retry limits cases")
    void onNextEndOfStreamInternalErrorWithRetryLimits(
            PublishStreamResponseCode code, int retryCount, boolean expectedRetry) {
        connection.setCurrentBlockNumber(BLOCK_NUMBER);
        TestUtils.setInternalState(connection, "endOfStreamExpBackoffs", new AtomicInteger(retryCount));
        PublishStreamResponse response = createEndOfStreamResponse(code, BLOCK_NUMBER);

        connection.onNext(response);

        // Verify close is called
        verify(connection).close();

        // Verify common logs
        assertThat(logCaptor.debugLogs())
                .anyMatch(log -> log.contains("Received EndOfStream from block node " + CONNECTION_DESCRIPTOR)
                        && log.contains("at block " + BLOCK_NUMBER));

        // Verify internal error common logs
        assertThat(logCaptor.warnLogs())
                .anyMatch(log -> log.contains("Block node " + CONNECTION_DESCRIPTOR)
                        && log.contains("reported an error at block " + BLOCK_NUMBER)
                        && log.contains("Will attempt to reestablish the stream later"));

        if (expectedRetry) {
            // Verify scheduler is used to schedule the retry
            verify(scheduler).schedule(runnableCaptor.capture(), delayCaptor.capture(), timeUnitCaptor.capture());
            assertEquals(5L, delayCaptor.getValue());
            assertEquals(TimeUnit.SECONDS, timeUnitCaptor.getValue());

            // Run the scheduled task and verify the retry
            runnableCaptor.getValue().run();
            assertThat(logCaptor.debugLogs())
                    .anyMatch(log ->
                            log.contains("Attempting retry after internal error for node " + CONNECTION_DESCRIPTOR)
                                    && log.contains("at block -1"));

            // Verify connection error handling is triggered
            verify(blockNodeConnectionManager).handleConnectionError(connection);
        } else {
            // Verify handleEndOfStreamError is not triggered
            verify(scheduler, never()).schedule((Runnable) any(), anyLong(), any());

            // Verify no retry log is generated
            assertThat(logCaptor.debugLogs())
                    .noneMatch(log ->
                            log.contains("Attempting retry after internal error for node " + CONNECTION_DESCRIPTOR)
                                    && log.contains("at block -1"));

            // Verify connection error handling is NOT triggered
            verify(blockNodeConnectionManager, never()).handleConnectionError(connection);
        }
    }

    @ParameterizedTest(name = "{index}: code={0}, retryCount={1}, expectRestart={2}")
    @MethodSource("immediateRestartCodes")
    @DisplayName("onNext handles EndOfStream with immediate restart codes respecting retry limits cases")
    void onNextEndOfStreamImmediateRestartWithRetryLimits(
            PublishStreamResponseCode code, int retryCount, boolean expectedRestart) {
        connection.setCurrentBlockNumber(BLOCK_NUMBER);
        TestUtils.setInternalState(connection, "endOfStreamImmediateRestarts", new AtomicInteger(retryCount));
        PublishStreamResponse response = createEndOfStreamResponse(code, BLOCK_NUMBER);

        connection.onNext(response);

        // Verify connection is closed and restarted immediately
        verify(connection).close();

        if (expectedRestart) {
            // Verify restart logs
            assertThat(logCaptor.warnLogs())
                    .anyMatch(log -> log.contains("Will restart stream at block " + (BLOCK_NUMBER + 1)));

            assertThat(logCaptor.debugLogs())
                    .anyMatch(log -> log.contains(
                            "Restarting stream at block " + (BLOCK_NUMBER + 1) + " for node " + CONNECTION_DESCRIPTOR));

            // Verify connection error handling is triggered
            verify(blockNodeConnectionManager)
                    .scheduleRetry(connection, BlockNodeConnectionManager.INITIAL_RETRY_DELAY);
        } else {
            verify(connection, never()).restartStreamAtBlock((anyLong()));

            // Verify scheduler is used to schedule the retry
            verify(scheduler).schedule(runnableCaptor.capture(), delayCaptor.capture(), timeUnitCaptor.capture());
            assertEquals(5L, delayCaptor.getValue());
            assertEquals(TimeUnit.SECONDS, timeUnitCaptor.getValue());

            // Run the scheduled task and verify the retry
            runnableCaptor.getValue().run();
            assertThat(logCaptor.debugLogs())
                    .anyMatch(log ->
                            log.contains("Attempting retry after internal error for node " + CONNECTION_DESCRIPTOR)
                                    && log.contains("at block -1"));

            // Verify connection error handling is triggered
            verify(blockNodeConnectionManager).handleConnectionError(connection);
        }
    }

    @ParameterizedTest(
            name =
                    "{index}: blockStateAvailable={0}, retryCount={1}, expectRestart={2}, expectHandleEndOfStreamError={3}")
    @MethodSource("streamItemsBehindCases")
    @DisplayName("onNext handles EndOfStream with STREAM_ITEMS_BEHIND response respecting retry limits cases")
    void OnNextHandlesStreamItemsBehind(
            boolean blockStateAvailable, int retryCount, boolean expectRestart, boolean expectHandleEndOfStreamError) {
        connection.setCurrentBlockNumber(BLOCK_NUMBER);
        long restartBlock = BLOCK_NUMBER + 1;
        PublishStreamResponse response = createEndOfStreamResponse(STREAM_ITEMS_BEHIND, BLOCK_NUMBER);

        // Setup retry state
        if (blockStateAvailable) {
            TestUtils.setInternalState(connection, "endOfStreamImmediateRestarts", new AtomicInteger(retryCount));
            when(blockStreamStateManager.getBlockState(restartBlock)).thenReturn(mock(BlockState.class));
        } else {
            TestUtils.setInternalState(connection, "endOfStreamExpBackoffs", new AtomicInteger(retryCount));
            when(blockStreamStateManager.getBlockState(restartBlock)).thenReturn(null);
        }

        connection.onNext(response);
        // Verify connection is closed
        verify(connection).close();

        if (blockStateAvailable) {
            // Verify block state is available
            assertThat(logCaptor.warnLogs())
                    .anyMatch(log -> log.contains("Block node " + CONNECTION_DESCRIPTOR + " reported it is behind")
                            && log.contains("restart stream at block " + restartBlock));

            if (expectRestart) {
                verify(connection).restartStreamAtBlock(restartBlock);
                assertThat(logCaptor.warnLogs())
                        .anyMatch(log -> log.contains("Will restart stream at block " + restartBlock));
            } else {
                verify(connection, never()).restartStreamAtBlock(anyLong());
            }
        } else {
            // When there is no block state available
            assertThat(logCaptor.warnLogs())
                    .anyMatch(log -> log.contains("Block node " + CONNECTION_DESCRIPTOR
                            + " is behind and block state is not available." + " Closing connection and retrying"));

            if (expectHandleEndOfStreamError) {
                verify(blockNodeConnectionManager).handleConnectionError(connection);
            } else {
                verify(blockNodeConnectionManager, never()).handleConnectionError(connection);
            }
        }
    }

    @Test
    @DisplayName("onNext handles EndOfStream with STREAM_ITEMS_UNKNOWN response code")
    void onNextEndOfStreamStreamItemsUnknown() {
        connection.setCurrentBlockNumber(BLOCK_NUMBER);
        PublishStreamResponse response = createEndOfStreamResponse(STREAM_ITEMS_UNKNOWN, BLOCK_NUMBER);

        connection.onNext(response);

        // Verify connection is closed and restarted immediately
        verify(connection).close();

        // Verify restart logs
        assertThat(logCaptor.errorLogs())
                .anyMatch(log -> log.contains("Block node " + CONNECTION_DESCRIPTOR)
                        && log.contains(" reported an unknown error at block " + BLOCK_NUMBER)
                        && log.contains("Closing connection"));
    }

    @Test
    @DisplayName("onNext handles SkipBlock for current block number")
    void onNextSkipBlockCurrentBlockNumber() {
        // the next block
        long nextBlock = BLOCK_NUMBER + 1L;

        // skip block response is for the block we are currently processing
        PublishStreamResponse response = createSkipBlockResponse(BLOCK_NUMBER);
        connection.setCurrentBlockNumber(BLOCK_NUMBER);

        connection.onNext(response);

        // Verify metrics
        verify(blockStreamMetrics).incrementSkipBlockCount();

        // Verify jumpToBlock was called for the next block
        verify(connection).jumpToBlock(nextBlock);

        // Verify skipBlock logs
        assertThat(logCaptor.debugLogs())
                .anyMatch(log ->
                        log.contains("Received SkipBlock from block node") && log.contains(Long.toString(nextBlock)));

        // Verify logs
        assertThat(logCaptor.debugLogs())
                .anyMatch(log -> log.contains("Skipping ahead to Block ") && log.contains(Long.toString(nextBlock)));
    }

    @Test
    @DisplayName("onNext handles SkipBlock not for current block number")
    void onNextSkipBlock() {
        // target block
        long targetBlock = BLOCK_NUMBER + 5L;

        // skip block response is for the target block
        PublishStreamResponse response = createSkipBlockResponse(targetBlock);
        connection.setCurrentBlockNumber(BLOCK_NUMBER);

        connection.onNext(response); // Jump

        // Verify metrics
        verify(blockStreamMetrics).incrementSkipBlockCount();

        // Verify skipBlock logs
        assertThat(logCaptor.debugLogs())
                .anyMatch(log ->
                        log.contains("Received SkipBlock from block node") && log.contains(Long.toString(targetBlock)));

        // Verify logs
        assertThat(logCaptor.warnLogs())
                .anyMatch(log -> log.contains("Received SkipBlock for") && log.contains(Long.toString(targetBlock)));
    }

    @Test
    @DisplayName("onNext handles ResendBlock")
    void onNextResendBlock() {
        long targetBlock = BLOCK_NUMBER - 2;
        PublishStreamResponse response = createResendBlockResponse(targetBlock);
        connection.setCurrentBlockNumber(BLOCK_NUMBER);

        connection.onNext(response);

        // Verify metrics
        verify(blockStreamMetrics).incrementResendBlockCount();

        // Verify restartStreamAtBlock was called
        verify(connection).restartStreamAtBlock(targetBlock);

        // Verify logs
        assertThat(logCaptor.debugLogs())
                .anyMatch(log -> log.contains("Restarting stream at the next block")
                        && log.contains(Long.toString(targetBlock)));
    }

    @Test
    @DisplayName("onNext handles Unknown Response Type")
    void onNextUnknownResponseType() {
        PublishStreamResponse response = PublishStreamResponse.newBuilder().build(); // Empty response
        connection.setCurrentBlockNumber(BLOCK_NUMBER);

        connection.onNext(response);

        // Verify connection not initialized
        assertEquals(BlockNodeConnection.ConnectionState.UNINITIALIZED, connection.getState());
    }

    @Test
    @DisplayName("onError logs error and handles failure")
    void onErrorHandlesFailure() {
        Throwable error = new StatusRuntimeException(Status.UNAVAILABLE.withDescription("Network issue"));

        connection.onError(error);

        // Verify failure outcome
        assertEquals(BlockNodeConnection.ConnectionState.UNINITIALIZED, connection.getState());
        verify(blockNodeConnectionManager).handleConnectionError(connection);
        verify(blockStreamMetrics).incrementOnErrorCount();
        assertThat(logCaptor.errorLogs())
                .anyMatch(log -> log.contains("Error on stream from block node " + CONNECTION_DESCRIPTOR));
    }

    @Test
    @DisplayName("onCompleted logs completion and handles failure")
    void onCompletedHandlesFailure() {
        connection.onCompleted();

        // Verify failure outcome
        assertEquals(BlockNodeConnection.ConnectionState.UNINITIALIZED, connection.getState());
        verify(blockNodeConnectionManager).handleConnectionError(connection);
        assertThat(logCaptor.debugLogs())
                .anyMatch(log -> log.contains("Stream completed for block node " + CONNECTION_DESCRIPTOR));
    }

    static Stream<Arguments> immediateRestartCodes() {
        return Stream.of(
                Arguments.of(PublishStreamResponseCode.STREAM_ITEMS_SUCCESS, 0, true),
                Arguments.of(PublishStreamResponseCode.STREAM_ITEMS_SUCCESS, MAX_END_OF_STREAM_RESTARTS_VALUE, false),
                Arguments.of(PublishStreamResponseCode.STREAM_ITEMS_TIMEOUT, 0, true),
                Arguments.of(PublishStreamResponseCode.STREAM_ITEMS_TIMEOUT, MAX_END_OF_STREAM_RESTARTS_VALUE, false),
                Arguments.of(PublishStreamResponseCode.STREAM_ITEMS_OUT_OF_ORDER, 0, true),
                Arguments.of(
                        PublishStreamResponseCode.STREAM_ITEMS_OUT_OF_ORDER, MAX_END_OF_STREAM_RESTARTS_VALUE, false),
                Arguments.of(PublishStreamResponseCode.STREAM_ITEMS_BAD_STATE_PROOF, 0, true),
                Arguments.of(
                        PublishStreamResponseCode.STREAM_ITEMS_BAD_STATE_PROOF,
                        MAX_END_OF_STREAM_RESTARTS_VALUE,
                        false));
    }

    static Stream<Arguments> internalErrorRetryCodes() {
        return Stream.of(
                Arguments.of(PublishStreamResponseCode.STREAM_ITEMS_INTERNAL_ERROR, 0, true),
                Arguments.of(
                        PublishStreamResponseCode.STREAM_ITEMS_INTERNAL_ERROR,
                        MAX_END_OF_STREAM_EXP_RETRIES_VALUE,
                        false),
                Arguments.of(PublishStreamResponseCode.STREAM_ITEMS_PERSISTENCE_FAILED, 0, true),
                Arguments.of(
                        PublishStreamResponseCode.STREAM_ITEMS_PERSISTENCE_FAILED,
                        MAX_END_OF_STREAM_EXP_RETRIES_VALUE,
                        false));
    }

    static Stream<Arguments> streamItemsBehindCases() {
        return Stream.of(
                Arguments.of(true, 0, true, false), // Case with restart
                Arguments.of(
                        true,
                        MAX_END_OF_STREAM_RESTARTS_VALUE,
                        false,
                        false), // Case with restart limit hit results in error
                Arguments.of(false, 0, false, true), // Case with no block state - retry scheduled
                Arguments.of(
                        false,
                        MAX_END_OF_STREAM_EXP_RETRIES_VALUE,
                        false,
                        false)); // Case with no block state and retry limit hit results in no action
    }

    private void setupWorkerTest() {
        connection.updateConnectionState(BlockNodeConnection.ConnectionState.ACTIVE);
        connection.createRequestObserver();
    }

    private BlockState buildBlockState(
            final long blockNumber, final boolean isComplete, final PublishStreamRequest... requests) {

        // extract the block items from each request
        List<BlockItem> items =
                Arrays.stream(requests).map(this::getBlockItemFromRequest).toList();

        // build the block state with the extracted items
        BlockState blockState = new BlockState(blockNumber, items);
        for (var req : requests) {
            blockState.requests().add(req);
        }

        // mark the block state as completed when needed so that the worker moves to next block
        if (isComplete) {
            blockState.setComplete();
        }
        return blockState;
    }

    private AccountID generateRandomAccountId() {
        return AccountID.newBuilder()
                .shardNum(0)
                .realmNum(0)
                .accountNum(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
                .build();
    }

    private PublishStreamRequest createMockRequestWithOneBlockItem() {
        BlockItem blockItem = BlockItem.newBuilder()
                .transactionOutput(TransactionOutput.newBuilder()
                        .accountCreate(CreateAccountOutput.newBuilder().createdAccountId(generateRandomAccountId()))
                        .build())
                .build();

        BlockItemSet blockItemSet =
                BlockItemSet.newBuilder().blockItems(blockItem).build();

        return PublishStreamRequest.newBuilder().blockItems(blockItemSet).build();
    }

    private BlockItem getBlockItemFromRequest(PublishStreamRequest request) {
        BlockItemSet blockItemSet = request.blockItemsOrThrow(); // Assuming request contains BlockItems
        List<BlockItem> blockItems = blockItemSet.blockItems();
        if (blockItems.size() == 1) {
            return blockItems.get(0);
        } else {
            throw new IllegalArgumentException("Request does not contain exactly one BlockItem");
        }
    }

    private PublishStreamResponse createAcknowledgementResponse(long blockNumber, boolean blockAlreadyExists) {
        return PublishStreamResponse.newBuilder()
                .acknowledgement(PublishStreamResponse.Acknowledgement.newBuilder()
                        .blockAck(PublishStreamResponse.BlockAcknowledgement.newBuilder()
                                .blockNumber(blockNumber)
                                .blockAlreadyExists(blockAlreadyExists)
                                .build())
                        .build())
                .build();
    }

    private PublishStreamResponse createUnknownAcknowledgementResponse() {
        return PublishStreamResponse.newBuilder()
                .acknowledgement(
                        PublishStreamResponse.Acknowledgement.newBuilder().build())
                .build();
    }

    private PublishStreamResponse createEndOfStreamResponse(PublishStreamResponseCode status, long blockNumber) {
        return PublishStreamResponse.newBuilder()
                .endStream(PublishStreamResponse.EndOfStream.newBuilder()
                        .status(status)
                        .blockNumber(blockNumber)
                        .build())
                .build();
    }

    private PublishStreamResponse createSkipBlockResponse(long targetBlockNumber) {
        return PublishStreamResponse.newBuilder()
                .skipBlock(PublishStreamResponse.SkipBlock.newBuilder()
                        .blockNumber(targetBlockNumber)
                        .build())
                .build();
    }

    private PublishStreamResponse createResendBlockResponse(long targetBlockNumber) {
        return PublishStreamResponse.newBuilder()
                .resendBlock(PublishStreamResponse.ResendBlock.newBuilder()
                        .blockNumber(targetBlockNumber)
                        .build())
                .build();
    }

    static class TestUtils {
        @SuppressWarnings("unchecked")
        public static <T> T getInternalState(Object target, String fieldName, Class<T> type) {
            try {
                Field field = target.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                return type.cast(field.get(target));
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException("Failed to get internal state '" + fieldName + "'", e);
            }
        }

        public static void setInternalState(Object target, String fieldName, Object value) {
            try {
                Field field = target.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException("Failed to set internal state '" + fieldName + "'", e);
            }
        }
    }
}
