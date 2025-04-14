// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.BlockItemSet;
import com.hedera.hapi.block.PublishStreamRequest;
import com.hedera.hapi.block.PublishStreamResponse;
import com.hedera.hapi.block.PublishStreamResponse.Acknowledgement;
import com.hedera.hapi.block.PublishStreamResponse.BlockAcknowledgement;
import com.hedera.hapi.block.PublishStreamResponseCode;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.fixtures.util.LogCaptureExtension;
import com.hedera.node.app.spi.fixtures.util.LoggingSubject;
import com.hedera.node.app.spi.fixtures.util.LoggingTarget;
import com.hedera.node.internal.network.BlockNodeConfig;
import io.grpc.stub.StreamObserver;
import io.helidon.webclient.grpc.GrpcServiceClient;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith({MockitoExtension.class, LogCaptureExtension.class})
class BlockNodeConnectionTest {

    private static final String TEST_ADDRESS = "localhost";
    private static final int TEST_PORT = 8080;
    private static final long TEST_BLOCK_NUMBER = 42L;

    @Mock
    private BlockNodeConfig nodeConfig;

    @Mock
    private BlockNodeConnectionManager connectionManager;

    @Mock
    private BlockStreamStateManager blockStreamStateManager;

    @Mock
    private StreamObserver<PublishStreamRequest> requestObserver;

    @Mock
    private GrpcServiceClient grpcServiceClient;

    @Mock
    private BlockState blockState;

    @LoggingTarget
    private LogCaptor logCaptor;

    @LoggingSubject
    private BlockNodeConnection connection;

    @BeforeEach
    void setUp() throws Exception {
        when(nodeConfig.address()).thenReturn(TEST_ADDRESS);
        when(nodeConfig.port()).thenReturn(TEST_PORT);

        connection = new BlockNodeConnection(nodeConfig, connectionManager, blockStreamStateManager, grpcServiceClient);

        // Set requestObserver via reflection to avoid establishing an actual gRPC connection
        Field requestObserverField = BlockNodeConnection.class.getDeclaredField("requestObserver");
        requestObserverField.setAccessible(true);
        requestObserverField.set(connection, requestObserver);

        // Set isActive to true via reflection
        Field isActiveField = BlockNodeConnection.class.getDeclaredField("isActive");
        isActiveField.setAccessible(true);
        isActiveField.set(connection, new AtomicBoolean(true));
    }

    @AfterEach
    void tearDown() {
        connection.close();
    }

    /**
     * Tests the flow where the current block number is -1, and we need to wait for a new block.
     */
    @Test
    void testRequestWorkerLoop_WaitsForNewBlock() throws Exception {
        // Arrange
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean workerStarted = new AtomicBoolean(false);
        AtomicBoolean workerStopped = new AtomicBoolean(false);

        // Act - Start the worker thread
        Thread workerThread = Thread.ofPlatform().name("TestWorker").start(() -> {
            workerStarted.set(true);

            // Call the method under test via reflection
            try {
                Method requestWorkerLoopMethod = BlockNodeConnection.class.getDeclaredMethod("requestWorkerLoop");
                requestWorkerLoopMethod.setAccessible(true);
                // Use a separate thread to execute the method so we can interrupt it later
                Thread methodExecutor = Thread.ofPlatform().start(() -> {
                    try {
                        requestWorkerLoopMethod.invoke(connection);
                    } catch (Exception e) {
                        // Ignore
                    }
                });
                methodExecutor.interrupt();
            } catch (Exception e) {
                // Ignore
            }

            workerStopped.set(true);
            latch.countDown();
        });

        // Wait for the worker to start
        while (!workerStarted.get()) {
            Thread.sleep(10);
        }

        // Simulate notifying a new block is available
        connection.notifyNewBlockAvailable();
        connection.setCurrentBlockNumber(TEST_BLOCK_NUMBER);

        // After the worker receives the notification, make sure it stops
        Thread.sleep(100);
        connection.close();

        // Wait for the worker to stop
        assertTrue(latch.await(1, TimeUnit.SECONDS), "Worker thread did not stop");
        assertTrue(workerStopped.get(), "Worker thread did not run to completion");

        // Verify log messages
        final String expectedWaitingLog =
                "[] Waiting for new block to be available for node " + TEST_ADDRESS + ":" + TEST_PORT;
        assertTrue(
                logCaptor.debugLogs().stream().anyMatch(log -> log.contains(expectedWaitingLog)),
                "Expected log message not found: " + expectedWaitingLog);
        final String expectedClosedLog = "Closed connection to block node " + TEST_ADDRESS + ":" + TEST_PORT;
        assertTrue(
                logCaptor.debugLogs().stream().anyMatch(log -> log.contains(expectedClosedLog)),
                "Expected log message not found: " + expectedClosedLog);
    }

    /**
     * Tests the flow where the block state is null for the current block number,
     * so the method waits for a new block to become available.
     */
    @Test
    void testRequestWorkerLoop_BlockStateIsNull() throws Exception {
        // Arrange
        connection.setCurrentBlockNumber(TEST_BLOCK_NUMBER);
        when(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER)).thenReturn(null);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean workerStarted = new AtomicBoolean(false);
        AtomicBoolean validBlockState = new AtomicBoolean(false);

        // Act - Start the worker thread
        Thread workerThread = Thread.ofPlatform().name("TestWorker").start(() -> {
            workerStarted.set(true);

            // Call the method under test via reflection
            try {
                Method requestWorkerLoopMethod = BlockNodeConnection.class.getDeclaredMethod("requestWorkerLoop");
                requestWorkerLoopMethod.setAccessible(true);

                // Use a separate thread to execute the method so we can interrupt it later
                Thread methodExecutor = Thread.ofPlatform().start(() -> {
                    try {
                        requestWorkerLoopMethod.invoke(connection);
                    } catch (Exception e) {
                        // Ignore
                    }
                });

                // Wait a bit for the method to reach the waiting state
                Thread.sleep(100);

                // Then provide a valid block state and notify
                when(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER)).thenReturn(blockState);
                when(blockState.isComplete()).thenReturn(true);
                when(blockState.requests()).thenReturn(List.of());
                validBlockState.set(true);

                connection.notifyNewBlockAvailable();

                // Wait a bit more for the method to process the valid block state
                Thread.sleep(100);

                // Then close the connection to stop the loop
                connection.close();
                methodExecutor.interrupt();

            } catch (Exception e) {
                // Ignore
            }

            latch.countDown();
        });

        // Wait for the worker to complete its test logic
        assertTrue(latch.await(3, TimeUnit.SECONDS), "Worker thread did not complete test logic");
        assertTrue(validBlockState.get(), "Block state was not set to valid");

        // Verify log messages indicate waiting for a new block
        final String expectedWaitingLog =
                "[] Waiting for new block to be available for node " + TEST_ADDRESS + ":" + TEST_PORT;
        assertTrue(
                logCaptor.debugLogs().stream().anyMatch(log -> log.contains(expectedWaitingLog)),
                "Expected log message not found: " + expectedWaitingLog);
        final String expectedClosedLog = "Closed connection to block node " + TEST_ADDRESS + ":" + TEST_PORT;
        assertTrue(
                logCaptor.debugLogs().stream().anyMatch(log -> log.contains(expectedClosedLog)),
                "Expected log message not found: " + expectedClosedLog);
    }

    /**
     * Tests the flow where there are no requests available for the current block state
     * and the block is not complete.
     */
    @Test
    void testRequestWorkerLoop_NoRequestsAvailable() throws Exception {
        // Arrange
        connection.setCurrentBlockNumber(TEST_BLOCK_NUMBER);
        when(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER)).thenReturn(blockState);
        when(blockState.requests()).thenReturn(List.of());
        when(blockState.isComplete()).thenReturn(false);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean workerStarted = new AtomicBoolean(false);
        AtomicBoolean notificationReceived = new AtomicBoolean(false);

        // Act - Start the worker thread
        Thread workerThread = Thread.ofPlatform().name("TestWorker").start(() -> {
            workerStarted.set(true);

            // Call the method under test via reflection
            try {
                Method requestWorkerLoopMethod = BlockNodeConnection.class.getDeclaredMethod("requestWorkerLoop");
                requestWorkerLoopMethod.setAccessible(true);
                // Use a separate thread to execute the method so we can interrupt it later
                Thread methodExecutor = Thread.ofPlatform().start(() -> {
                    try {
                        requestWorkerLoopMethod.invoke(connection);
                    } catch (Exception e) {
                        // Ignore
                    }
                });

                methodExecutor.interrupt();
            } catch (Exception e) {
                // Ignore
            }

            notificationReceived.set(true);
            latch.countDown();
        });

        // Wait for the worker to start
        while (!workerStarted.get()) {
            Thread.sleep(10);
        }

        // Simulate notifying a new request is available
        connection.notifyNewRequestAvailable();

        // After the worker receives the notification, make sure it stops
        Thread.sleep(100);
        connection.close();

        // Wait for the worker to stop
        assertTrue(latch.await(1, TimeUnit.SECONDS), "Worker thread did not stop");
        assertTrue(notificationReceived.get(), "Worker did not receive notification");

        // Verify log messages indicate waiting for new requests
        final String expectedProcessingLog =
                "[] Processing block " + TEST_BLOCK_NUMBER + " for node " + TEST_ADDRESS + ":" + TEST_PORT;
        assertTrue(
                logCaptor.debugLogs().stream().anyMatch(log -> log.contains(expectedProcessingLog)),
                "Expected log message not found: " + expectedProcessingLog);
        final String expectedWaitingLog = "[] Waiting for new requests to be available for block " + TEST_BLOCK_NUMBER
                + " on node " + TEST_ADDRESS + ":" + TEST_PORT;
        assertTrue(
                logCaptor.debugLogs().stream().anyMatch(log -> log.contains(expectedWaitingLog)),
                "Expected log message not found: " + expectedWaitingLog);
        final String expectedClosedLog = "Closed connection to block node " + TEST_ADDRESS + ":" + TEST_PORT;
        assertTrue(
                logCaptor.debugLogs().stream().anyMatch(log -> log.contains(expectedClosedLog)),
                "Expected log message not found: " + expectedClosedLog);
    }

    /**
     * Tests the flow where there are requests available for the current block state,
     * and the worker processes all of them.
     */
    @Test
    void testRequestWorkerLoop_ProcessAvailableRequests() throws Exception {
        // Arrange
        connection.setCurrentBlockNumber(TEST_BLOCK_NUMBER);

        // Create mock requests
        final PublishStreamRequest request1 = mock(PublishStreamRequest.class);
        final PublishStreamRequest request2 = mock(PublishStreamRequest.class);
        final List<PublishStreamRequest> requests = List.of(request1, request2);
        final BlockItemSet blockItems = mock(BlockItemSet.class);

        when(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER)).thenReturn(blockState);
        when(blockState.requests()).thenReturn(requests);
        when(blockState.isComplete()).thenReturn(true);
        when(request1.blockItems()).thenReturn(blockItems);
        when(request2.blockItems()).thenReturn(blockItems);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean processedRequests = new AtomicBoolean(false);
        AtomicReference<Long> finalBlockNumber = new AtomicReference<>(0L);

        // Act - Start the worker thread
        Thread workerThread = Thread.ofPlatform().name("TestWorker").start(() -> {
            // Call the method under test via reflection
            try {
                Method requestWorkerLoopMethod = BlockNodeConnection.class.getDeclaredMethod("requestWorkerLoop");
                requestWorkerLoopMethod.setAccessible(true);
                // Use a separate thread to execute the method so we can interrupt it later
                Thread methodExecutor = Thread.ofPlatform().start(() -> {
                    try {
                        requestWorkerLoopMethod.invoke(connection);
                    } catch (Exception e) {
                        // Ignore
                    }
                });
                methodExecutor.interrupt();
            } catch (Exception e) {
                // Ignore
            }
            processedRequests.set(true);
            latch.countDown();
        });

        // Wait a bit for the worker to process the requests
        Thread.sleep(100);
        finalBlockNumber.set(connection.getCurrentBlockNumber());
        connection.close();

        // Assert
        assertTrue(latch.await(1, TimeUnit.SECONDS), "Worker thread did not stop");
        assertEquals(TEST_BLOCK_NUMBER + 1, finalBlockNumber.get());
        verify(requestObserver, times(2)).onNext(any(PublishStreamRequest.class));

        // Verify log messages indicate processing of requests
        final String expectedProcessingLog =
                "[] Processing block " + TEST_BLOCK_NUMBER + " for node " + TEST_ADDRESS + ":" + TEST_PORT;
        assertTrue(
                logCaptor.debugLogs().stream().anyMatch(log -> log.contains(expectedProcessingLog)),
                "Expected log message not found: " + expectedProcessingLog);
        final String expectedSendingLog = "[] Sending request for block " + TEST_BLOCK_NUMBER;
        assertTrue(
                logCaptor.debugLogs().stream().anyMatch(log -> log.contains(expectedSendingLog)),
                "Expected log message not found: " + expectedSendingLog);
        final String expectedClosedLog = "Closed connection to block node " + TEST_ADDRESS + ":" + TEST_PORT;
        assertTrue(
                logCaptor.debugLogs().stream().anyMatch(log -> log.contains(expectedClosedLog)),
                "Expected log message not found: " + expectedClosedLog);
    }

    /**
     * Tests the flow where the block is complete and all requests have been processed,
     * so the worker should move to the next block.
     */
    @Test
    void testRequestWorkerLoop_MoveToNextBlock() throws Exception {
        // Arrange
        connection.setCurrentBlockNumber(TEST_BLOCK_NUMBER);

        // Create mock requests
        final PublishStreamRequest request1 = mock(PublishStreamRequest.class);
        final List<PublishStreamRequest> requests = List.of(request1);
        final BlockItemSet blockItems = mock(BlockItemSet.class);

        when(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER)).thenReturn(blockState);
        when(blockState.requests()).thenReturn(requests);
        when(blockState.isComplete()).thenReturn(true);
        when(request1.blockItems()).thenReturn(blockItems);

        // For the next block, return null to stop the loop
        when(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER + 1)).thenReturn(null);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Long> finalBlockNumber = new AtomicReference<>(0L);

        // Act - Start the worker thread
        Thread workerThread = Thread.ofPlatform().name("TestWorker").start(() -> {
            // Call the method under test via reflection
            try {
                Method requestWorkerLoopMethod = BlockNodeConnection.class.getDeclaredMethod("requestWorkerLoop");
                requestWorkerLoopMethod.setAccessible(true);
                // Use a separate thread to execute the method so we can interrupt it later
                Thread methodExecutor = Thread.ofPlatform().start(() -> {
                    try {
                        requestWorkerLoopMethod.invoke(connection);
                    } catch (Exception e) {
                        // Ignore
                    }
                });
                methodExecutor.interrupt();
            } catch (Exception e) {
                // Ignore
            }

            latch.countDown();
        });

        Thread.sleep(100);
        finalBlockNumber.set(connection.getCurrentBlockNumber());
        connection.close();

        // Wait a bit for the worker to process the requests and move to the next block
        assertTrue(latch.await(1, TimeUnit.SECONDS), "Worker thread did not stop");

        // Assert
        assertEquals(TEST_BLOCK_NUMBER + 1, finalBlockNumber.get());
        verify(requestObserver, times(1)).onNext(any(PublishStreamRequest.class));

        // Verify log messages indicate moving to the next block
        final String expectedSendingLog = "[] Sending request for block " + TEST_BLOCK_NUMBER;
        assertTrue(
                logCaptor.debugLogs().stream().anyMatch(log -> log.contains(expectedSendingLog)),
                "Expected log message not found: " + expectedSendingLog);
        final String expectedCompletedLog = "[] Completed sending all requests for block " + TEST_BLOCK_NUMBER;
        assertTrue(
                logCaptor.debugLogs().stream().anyMatch(log -> log.contains(expectedCompletedLog)),
                "Expected log message not found: " + expectedCompletedLog);
    }

    /**
     * Tests the flow where an InterruptedException is thrown during the worker loop.
     */
    @Test
    void testRequestWorkerLoop_InterruptedException() throws Exception {
        // Arrange
        connection.setCurrentBlockNumber(TEST_BLOCK_NUMBER);

        // Make waiting for new block throw an InterruptedException
        Object mockNewBlockAvailable = mock(Object.class);
        doThrow(new InterruptedException()).when(mockNewBlockAvailable).wait();

        Field newBlockAvailableField = BlockNodeConnection.class.getDeclaredField("newBlockAvailable");
        newBlockAvailableField.setAccessible(true);
        newBlockAvailableField.set(connection, mockNewBlockAvailable);

        // We expect the worker to exit after the InterruptedException
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean workerExited = new AtomicBoolean(false);

        // Act - Start the worker thread
        Thread workerThread = Thread.ofPlatform().name("TestWorker").start(() -> {
            // Call the method under test via reflection
            try {
                Method requestWorkerLoopMethod = BlockNodeConnection.class.getDeclaredMethod("requestWorkerLoop");
                requestWorkerLoopMethod.setAccessible(true);
                requestWorkerLoopMethod.invoke(connection);
            } catch (Exception e) {
                // Ignore
            }

            workerExited.set(true);
            latch.countDown();
        });

        // Wait for the worker to exit
        assertTrue(latch.await(1, TimeUnit.SECONDS), "Worker thread did not stop");
        assertTrue(workerExited.get(), "Worker did not exit after InterruptedException");

        // Verify log messages for handling the InterruptedException
        final String expectedErrorLog =
                "[] Request worker thread interrupted for node " + TEST_ADDRESS + ":" + TEST_PORT;
        assertTrue(
                logCaptor.errorLogs().stream().anyMatch(log -> log.contains(expectedErrorLog)),
                "Expected log message not found: " + expectedErrorLog);
    }

    /**
     * Tests the flow where a generic exception is thrown during the worker loop.
     */
    @Test
    void testRequestWorkerLoop_GenericException() throws Exception {
        // Arrange
        connection.setCurrentBlockNumber(TEST_BLOCK_NUMBER);

        // Make blockStreamStateManager.getBlockState throw a RuntimeException
        when(blockStreamStateManager.getBlockState(anyLong())).thenThrow(new RuntimeException("Test exception"));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean workerContinued = new AtomicBoolean(false);
        AtomicInteger iterationCount = new AtomicInteger(0);

        // Act - Start the worker thread
        Thread workerThread = Thread.ofPlatform().name("TestWorker").start(() -> {
            // Call the method under test via reflection
            try {
                // Make the isActive field accessible and override it to stop after a few iterations
                Field isActiveField = BlockNodeConnection.class.getDeclaredField("isActive");
                isActiveField.setAccessible(true);

                Method requestWorkerLoopMethod = BlockNodeConnection.class.getDeclaredMethod("requestWorkerLoop");
                requestWorkerLoopMethod.setAccessible(true);

                // Only allow a few iterations to avoid an infinite loop
                while (iterationCount.incrementAndGet() < 3) {
                    requestWorkerLoopMethod.invoke(connection);
                }

                // If we reached here, the worker continued despite the exception
                workerContinued.set(true);
                isActiveField.set(connection, new AtomicBoolean(false));

            } catch (Exception e) {
                // Ignore
            }

            latch.countDown();
        });

        // Wait for the worker to run a few iterations
        assertTrue(latch.await(1, TimeUnit.SECONDS), "Worker thread did not stop");
        assertTrue(workerContinued.get(), "Worker did not continue after generic exception");

        // Verify log messages for handling the generic exception
        final String expectedErrorLog = "[] Error in request worker thread for node " + TEST_ADDRESS + ":" + TEST_PORT;
        assertTrue(
                logCaptor.errorLogs().stream().anyMatch(log -> log.contains(expectedErrorLog)),
                "Expected log message not found: " + expectedErrorLog);
    }

    /**
     * Tests the flow where an IndexOutOfBoundsException is thrown during processing of requests.
     */
    @Test
    void testRequestWorkerLoop_IndexOutOfBoundsException() throws Exception {
        // Arrange
        connection.setCurrentBlockNumber(TEST_BLOCK_NUMBER);

        // Set up a situation where an IndexOutOfBoundsException might occur
        final List<PublishStreamRequest> requests = new ArrayList<>();
        requests.add(mock(PublishStreamRequest.class));

        when(blockStreamStateManager.getBlockState(TEST_BLOCK_NUMBER)).thenReturn(blockState);
        when(blockState.requests()).thenReturn(requests);
        when(blockState.isComplete()).thenReturn(false);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean requestIndexResetCalled = new AtomicBoolean(false);

        // Act - Start the worker thread
        Thread workerThread = Thread.ofPlatform().name("TestWorker").start(() -> {
            // Call the method under test via reflection
            try {
                Method requestWorkerLoopMethod = BlockNodeConnection.class.getDeclaredMethod("requestWorkerLoop");
                requestWorkerLoopMethod.setAccessible(true);

                // Call once, which should trigger the IndexOutOfBoundsException
                requestWorkerLoopMethod.invoke(connection);

                // Check if the currentRequestIndex was reset to a safe value
                Field currentRequestIndexField = BlockNodeConnection.class.getDeclaredField("currentRequestIndex");
                currentRequestIndexField.setAccessible(true);
                AtomicInteger resetIndex = (AtomicInteger) currentRequestIndexField.get(connection);

                if (resetIndex.get() == 0) {
                    requestIndexResetCalled.set(true);
                }
            } catch (Exception e) {
                // Ignore
            }

            latch.countDown();
        });

        // Wait for the worker to finish
        assertTrue(latch.await(1, TimeUnit.SECONDS), "Worker thread did not stop");
        assertTrue(requestIndexResetCalled.get(), "Request index was not reset after IndexOutOfBoundsException");

        // Verify log messages for handling the IndexOutOfBoundsException
        final String expectedErrorLog = "[] Error in request worker thread for node " + TEST_ADDRESS + ":" + TEST_PORT;
        assertTrue(
                logCaptor.errorLogs().stream().anyMatch(log -> log.contains(expectedErrorLog)),
                "Expected log message not found: " + expectedErrorLog);
    }

    /**
     * Tests that the connection can properly jump to a specific block.
     */
    @Test
    void testJumpToBlock() {
        // Arrange
        final long blockNumber = 100L;

        // Act
        connection.jumpToBlock(blockNumber);

        // Assert
        assertEquals(blockNumber, connection.getCurrentBlockNumber());
        assertEquals(0, connection.getCurrentRequestIndex());

        // Verify log messages for jumping to a block
        final String expectedLog = "Setting current block number to " + blockNumber + " for node " + TEST_ADDRESS + ":"
                + TEST_PORT + " without ending stream";
        assertTrue(
                logCaptor.infoLogs().stream().anyMatch(log -> log.contains(expectedLog))
                        || logCaptor.debugLogs().stream().anyMatch(log -> log.contains(expectedLog)),
                "Expected log message not found: " + expectedLog);
    }

    /**
     * Tests that the connection properly ends the stream and restarts at a specific block.
     */
    @Test
    void testEndStreamAndRestartAtBlock() {
        // Arrange
        final long blockNumber = 100L;

        // Act
        connection.endStreamAndRestartAtBlock(blockNumber);

        // Assert
        verify(requestObserver).onCompleted();
        assertEquals(blockNumber, connection.getCurrentBlockNumber());
        assertEquals(0, connection.getCurrentRequestIndex());

        // Verify log messages for ending stream and restarting
        final String expectedLog = "Ending stream and restarting at block " + blockNumber;
        assertTrue(
                logCaptor.infoLogs().stream().anyMatch(log -> log.contains(expectedLog))
                        || logCaptor.debugLogs().stream().anyMatch(log -> log.contains(expectedLog)),
                "Expected log message not found: " + expectedLog);
    }

    /**
     * Tests the onNext method handling a PublishStreamResponse with an Acknowledgement.
     */
    @Test
    void testOnNext_WithAcknowledgement() {
        // Arrange
        final Acknowledgement acknowledgement = Acknowledgement.newBuilder()
                .blockAck(BlockAcknowledgement.newBuilder()
                        .blockNumber(TEST_BLOCK_NUMBER)
                        .blockAlreadyExists(false)
                        .build())
                .build();
        final PublishStreamResponse response = PublishStreamResponse.newBuilder()
                .acknowledgement(acknowledgement)
                .build();

        // Act
        connection.onNext(response);

        // No specific verification needed - the method doesn't do much with the acknowledgement

        // Verify log messages for acknowledgement
        final String expectedLog = "Received acknowledgement";
        assertTrue(
                logCaptor.infoLogs().stream().anyMatch(log -> log.contains(expectedLog))
                        || logCaptor.debugLogs().stream().anyMatch(log -> log.contains(expectedLog)),
                "Expected acknowledgement handling logs not found");
    }

    /**
     * Tests the onNext method handling a PublishStreamResponse with an EndOfStream.
     */
    @Test
    void testOnNext_WithEndOfStream() {
        // Arrange
        final BlockNodeConnection connectionSpy = spy(connection);
        final PublishStreamResponse.EndOfStream endOfStream = PublishStreamResponse.EndOfStream.newBuilder()
                .blockNumber(TEST_BLOCK_NUMBER)
                .status(PublishStreamResponseCode.STREAM_ITEMS_INTERNAL_ERROR)
                .build();
        final PublishStreamResponse response =
                PublishStreamResponse.newBuilder().endStream(endOfStream).build();

        // Act
        connectionSpy.onNext(response);

        // Assert connection restarts after the last verified block number
        verify(connectionSpy).endStreamAndRestartAtBlock(endOfStream.blockNumber() + 1L);

        // Verify log messages for end of stream
        final String expectedLog = "Received EndOfStream from block node";
        assertTrue(
                logCaptor.debugLogs().stream().anyMatch(log -> log.contains(expectedLog))
                        || logCaptor.errorLogs().stream().anyMatch(log -> log.contains(expectedLog)),
                "Expected log message not found: " + expectedLog);
    }

    /**
     * Tests the onError method when an error is received from the stream.
     */
    @Test
    void testOnError() {
        // Arrange
        Throwable error = new RuntimeException("Stream error");

        // Act
        connection.onError(error);

        // Assert - connection manager is notified
        verify(connectionManager).disconnectFromNode(nodeConfig);

        // Verify log messages for onError
        final String expectedLog = "[] Error on stream from block node " + TEST_ADDRESS + ":" + TEST_PORT;
        assertTrue(
                logCaptor.errorLogs().stream().anyMatch(log -> log.contains(expectedLog)),
                "Expected log message not found: " + expectedLog);
    }

    /**
     * Tests the onCompleted method when the stream is completed.
     */
    @Test
    void testOnCompleted() {
        // Act
        connection.onCompleted();

        // Assert - connection manager is notified
        verify(connectionManager).disconnectFromNode(nodeConfig);

        // Verify log messages for onCompleted
        final String expectedLog = "[] Stream completed for block node " + TEST_ADDRESS + ":" + TEST_PORT;
        assertTrue(
                logCaptor.debugLogs().stream().anyMatch(log -> log.contains(expectedLog)),
                "Expected log message not found: " + expectedLog);
    }
}
