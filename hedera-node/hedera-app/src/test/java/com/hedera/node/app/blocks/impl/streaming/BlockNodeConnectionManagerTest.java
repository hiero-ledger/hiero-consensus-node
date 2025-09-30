// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchException;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnection.ConnectionState;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager.BlockNodeConnectionTask;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager.RetryState;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.internal.network.BlockNodeConfig;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.Thread.State;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.apache.logging.log4j.LogManager;
import org.hiero.block.api.PublishStreamRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockNodeConnectionManagerTest extends BlockNodeCommunicationTestBase {

    private static final VarHandle isManagerActiveHandle;
    private static final VarHandle workerThreadRefHandle;
    private static final VarHandle connectionsHandle;
    private static final VarHandle availableNodesHandle;
    private static final VarHandle activeConnectionRefHandle;
    private static final VarHandle jumpTargetHandle;
    private static final VarHandle streamingBlockNumberHandle;
    private static final VarHandle lastVerifiedBlockPerConnectionHandle;
    private static final VarHandle connectivityTaskConnectionHandle;
    private static final VarHandle isStreamingEnabledHandle;
    private static final VarHandle nodeStatsHandle;
    private static final VarHandle retryStatesHandle;
    private static final MethodHandle jumpToBlockIfNeededHandle;
    private static final MethodHandle processStreamingToBlockNodeHandle;
    private static final MethodHandle blockStreamWorkerLoopHandle;
    private static final MethodHandle createNewGrpcClientHandle;

    static {
        try {
            final Lookup lookup = MethodHandles.lookup();
            isManagerActiveHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "isConnectionManagerActive", AtomicBoolean.class);
            workerThreadRefHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(
                            BlockNodeConnectionManager.class, "blockStreamWorkerThreadRef", AtomicReference.class);
            connectionsHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "connections", Map.class);
            availableNodesHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "availableBlockNodes", List.class);
            activeConnectionRefHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "activeConnectionRef", AtomicReference.class);
            jumpTargetHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "jumpTargetBlock", AtomicLong.class);
            streamingBlockNumberHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "streamingBlockNumber", AtomicLong.class);
            lastVerifiedBlockPerConnectionHandle = MethodHandles.privateLookupIn(
                            BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "lastVerifiedBlockPerConnection", Map.class);
            connectivityTaskConnectionHandle = MethodHandles.privateLookupIn(BlockNodeConnectionTask.class, lookup)
                    .findVarHandle(BlockNodeConnectionTask.class, "connection", BlockNodeConnection.class);
            isStreamingEnabledHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "isStreamingEnabled", AtomicBoolean.class);
            nodeStatsHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "nodeStats", Map.class);
            retryStatesHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "retryStates", Map.class);

            final Method jumpToBlockIfNeeded =
                    BlockNodeConnectionManager.class.getDeclaredMethod("jumpToBlockIfNeeded");
            jumpToBlockIfNeeded.setAccessible(true);
            jumpToBlockIfNeededHandle = lookup.unreflect(jumpToBlockIfNeeded);

            final Method processStreamingToBlockNode = BlockNodeConnectionManager.class.getDeclaredMethod(
                    "processStreamingToBlockNode", BlockNodeConnection.class);
            processStreamingToBlockNode.setAccessible(true);
            processStreamingToBlockNodeHandle = lookup.unreflect(processStreamingToBlockNode);

            final Method blockStreamWorkerLoop =
                    BlockNodeConnectionManager.class.getDeclaredMethod("blockStreamWorkerLoop");
            blockStreamWorkerLoop.setAccessible(true);
            blockStreamWorkerLoopHandle = lookup.unreflect(blockStreamWorkerLoop);

            final Method createNewGrpcClientMethod =
                    BlockNodeConnectionManager.class.getDeclaredMethod("createNewGrpcClient", BlockNodeConfig.class);
            createNewGrpcClientMethod.setAccessible(true);
            createNewGrpcClientHandle = lookup.unreflect(createNewGrpcClientMethod);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BlockNodeConnectionManager connectionManager;

    private BlockBufferService bufferService;
    private BlockStreamMetrics metrics;
    private ScheduledExecutorService executorService;

    @BeforeEach
    void beforeEach() {
        final ConfigProvider configProvider = createConfigProvider(createDefaultConfigProvider());

        bufferService = mock(BlockBufferService.class);
        metrics = mock(BlockStreamMetrics.class);
        executorService = mock(ScheduledExecutorService.class);

        connectionManager = new BlockNodeConnectionManager(configProvider, bufferService, metrics, executorService);

        resetMocks();
    }

    @AfterEach
    void afterEach() {
        System.out.println("-- Ensuring worker thread dead -->");
        isActiveFlag().set(false);
        // ensure worker thread is dead

        final Thread workerThread = workerThread().get();
        if (workerThread != null) {
            workerThread.interrupt();
            final long startMillis = System.currentTimeMillis();

            do {
                final long durationMs = System.currentTimeMillis() - startMillis;
                if (durationMs >= 3_000) {
                    fail("Worker thread did not terminate in allotted time");
                    break;
                }

                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            } while (State.TERMINATED != workerThread.getState());
        }
        System.out.println("<-- Ensuring worker thread dead --");
    }

    @Test
    void testRescheduleAndSelectNode() {
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(8080, 1);
        final Duration delay = Duration.ofSeconds(1);
        doReturn(nodeConfig).when(connection).getNodeConfig();

        connectionManager.rescheduleConnection(connection, delay, null, true);

        // Verify task created to reconnect to the failing connection after a delay
        verify(executorService)
                .schedule(any(BlockNodeConnectionTask.class), eq(delay.toMillis()), eq(TimeUnit.MILLISECONDS));
        // Verify task created to connect to a new node without delay
        verify(executorService).schedule(any(BlockNodeConnectionTask.class), eq(0L), eq(TimeUnit.MILLISECONDS));
        verifyNoMoreInteractions(connection);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
        verifyNoMoreInteractions(executorService);
    }

    @Test
    void rescheduleConnectionAndExponentialBackoff() {
        final Map<BlockNodeConfig, RetryState> retryStates = retryStates();
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(8080, 1);
        doReturn(nodeConfig).when(connection).getNodeConfig();

        connectionManager.rescheduleConnection(connection, Duration.ZERO, null, true);
        connectionManager.rescheduleConnection(connection, Duration.ofMillis(10L), null, true);
        connectionManager.rescheduleConnection(connection, Duration.ofMillis(20L), null, true);
        connectionManager.rescheduleConnection(connection, Duration.ofMillis(30L), null, true);

        assertThat(retryStates).hasSize(1);
        assertThat(retryStates.get(nodeConfig).getRetryAttempt()).isEqualTo(4);

        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
        verifyNoMoreInteractions(connection);
    }

    @Test
    void rescheduleConnectionAndExponentialBackoffResets() throws Throwable {
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(8080, 1);
        doReturn(nodeConfig).when(connection).getNodeConfig();

        final TestConfigBuilder configBuilder =
                createDefaultConfigProvider().withValue("blockNode.protocolExpBackoffTimeframeReset", "1s");
        final ConfigProvider configProvider = createConfigProvider(configBuilder);

        connectionManager = new BlockNodeConnectionManager(configProvider, bufferService, metrics, executorService);

        connectionManager.rescheduleConnection(connection, Duration.ZERO, null, true);
        Thread.sleep(1_000L); // sleep to ensure the backoff timeframe has passed
        connectionManager.rescheduleConnection(connection, Duration.ZERO, null, true);

        final Map<BlockNodeConfig, RetryState> retryStates = retryStates();
        assertThat(retryStates).hasSize(1);
        assertThat(retryStates.get(nodeConfig).getRetryAttempt()).isEqualTo(1);

        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
        verifyNoMoreInteractions(connection);
    }

    @Test
    void testScheduleConnectionAttempt() {
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(8080, 1);
        doReturn(nodeConfig).when(connection).getNodeConfig();

        connectionManager.scheduleConnectionAttempt(connection.getNodeConfig(), Duration.ofSeconds(2), 100L);

        verify(executorService).schedule(any(BlockNodeConnectionTask.class), eq(2_000L), eq(TimeUnit.MILLISECONDS));
        verifyNoMoreInteractions(connection);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
        verifyNoMoreInteractions(executorService);
    }

    @Test
    void testScheduleConnectionAttempt_negativeDelay() {
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(8080, 1);
        doReturn(nodeConfig).when(connection).getNodeConfig();

        connectionManager.scheduleConnectionAttempt(connection.getNodeConfig(), Duration.ofSeconds(-2), 100L);

        verify(executorService).schedule(any(BlockNodeConnectionTask.class), eq(0L), eq(TimeUnit.MILLISECONDS));
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
        verifyNoMoreInteractions(executorService);
        verifyNoMoreInteractions(connection);
    }

    @Test
    void testScheduleConnectionAttempt_failure() {
        final var logCaptor = new LogCaptor(LogManager.getLogger(BlockNodeConnectionManager.class));
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(8080, 1);
        doReturn(nodeConfig).when(connection).getNodeConfig();
        doThrow(new RuntimeException("what the..."))
                .when(executorService)
                .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));

        connectionManager.scheduleConnectionAttempt(connection.getNodeConfig(), Duration.ofSeconds(2), 100L);

        assertThat(logCaptor.errorLogs())
                .anyMatch(msg -> msg.contains("Failed to schedule connection task for block node"));

        verify(executorService).schedule(any(BlockNodeConnectionTask.class), eq(2_000L), eq(TimeUnit.MILLISECONDS));
        verify(metrics).recordConnectionClosed();

        verifyNoInteractions(bufferService);
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(executorService);
        verifyNoMoreInteractions(connection);
    }

    @Test
    void testShutdown() throws InterruptedException {
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        // add some fake connections
        final BlockNodeConfig node1Config = newBlockNodeConfig(8080, 1);
        final BlockNodeConnection node1Conn = mock(BlockNodeConnection.class);
        final BlockNodeConfig node2Config = newBlockNodeConfig(8081, 2);
        final BlockNodeConnection node2Conn = mock(BlockNodeConnection.class);
        final BlockNodeConfig node3Config = newBlockNodeConfig(8082, 3);
        final BlockNodeConnection node3Conn = mock(BlockNodeConnection.class);
        connections.put(node1Config, node1Conn);
        connections.put(node2Config, node2Conn);
        connections.put(node3Config, node3Conn);

        // introduce a failure on one of the connection closes to ensure the shutdown process does not fail prematurely
        doThrow(new RuntimeException("oops, I did it again")).when(node2Conn).close(true);

        final AtomicBoolean isActive = isActiveFlag();
        final Thread dummyWorkerThread = mock(Thread.class);
        final AtomicReference<Thread> workerThreadRef = workerThread();
        workerThreadRef.set(dummyWorkerThread);

        isActive.set(true);

        try {
            connectionManager.shutdown();
        } finally {
            // remove the mocked worker thread; failure to do so will cause a failure in afterEach attempting to clean
            // up the worker thread
            workerThreadRef.set(null);
        }

        final AtomicReference<BlockNodeConnection> activeConnRef = activeConnection();
        assertThat(activeConnRef).hasNullValue();

        assertThat(connections).isEmpty();
        assertThat(isActive).isFalse();

        final AtomicLong streamBlockNum = streamingBlockNumber();
        assertThat(streamBlockNum).hasValue(-1);

        final Map<BlockNodeConfig, BlockNodeStats> nodeStats = nodeStats();
        assertThat(nodeStats).isEmpty();

        // calling shutdown again should not fail
        connectionManager.shutdown();

        verify(node1Conn).close(true);
        verify(node2Conn).close(true);
        verify(node3Conn).close(true);
        verify(dummyWorkerThread).interrupt();
        verify(dummyWorkerThread).join();
        verify(bufferService, times(2)).shutdown();
        verifyNoMoreInteractions(node1Conn);
        verifyNoMoreInteractions(node2Conn);
        verifyNoMoreInteractions(node3Conn);
        verifyNoInteractions(executorService);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testShutdown_withWorkerThreadInterrupt() throws InterruptedException {
        final AtomicBoolean isActive = isActiveFlag();
        final Thread dummyWorkerThread = mock(Thread.class);

        isActive.set(true);

        doThrow(new InterruptedException("wakey wakey, eggs and bakey"))
                .when(dummyWorkerThread)
                .join();

        final AtomicReference<Thread> workerThreadRef = workerThread();
        workerThreadRef.set(dummyWorkerThread);

        try {
            connectionManager.shutdown();
        } finally {
            // remove the mocked worker thread; failure to do so will cause a failure in afterEach attempting to clean
            // up the worker thread
            workerThreadRef.set(null);
        }

        assertThat(isActive).isFalse();

        verify(dummyWorkerThread).interrupt();
        verify(dummyWorkerThread).join();
        verify(bufferService).shutdown();
        verifyNoInteractions(executorService);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testStartup_alreadyActive() {
        final AtomicBoolean isActive = isActiveFlag();
        isActive.set(true);

        connectionManager.start();

        verifyNoInteractions(executorService);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testStartup_noNodesAvailable() {
        final AtomicReference<Thread> workerThreadRef = workerThread();
        final AtomicBoolean isActive = isActiveFlag();
        isActive.set(false);

        final List<BlockNodeConfig> availableNodes = availableNodes();
        availableNodes.clear(); // remove all available nodes from config

        assertThat(workerThreadRef).hasNullValue();

        final Exception exception = catchException(() -> connectionManager.start());
        assertThat(exception)
                .isInstanceOf(NoBlockNodesAvailableException.class)
                .hasMessage("No block nodes were available to connect to");

        assertThat(workerThreadRef).doesNotHaveNullValue();
        assertThat(isActive).isFalse();

        verifyNoInteractions(executorService);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testStartup() {
        final AtomicBoolean isActive = isActiveFlag();
        final AtomicReference<Thread> workerThreadRef = workerThread();
        isActive.set(false);

        final List<BlockNodeConfig> availableNodes = availableNodes();
        availableNodes.clear();
        availableNodes.add(newBlockNodeConfig(8080, 1));
        availableNodes.add(newBlockNodeConfig(8081, 1));
        availableNodes.add(newBlockNodeConfig(8082, 2));
        availableNodes.add(newBlockNodeConfig(8083, 3));
        availableNodes.add(newBlockNodeConfig(8084, 3));

        assertThat(workerThreadRef).hasNullValue(); // sanity check

        connectionManager.start();

        assertThat(workerThreadRef).doesNotHaveNullValue(); // worker thread should be spawned

        final ArgumentCaptor<BlockNodeConnectionTask> taskCaptor =
                ArgumentCaptor.forClass(BlockNodeConnectionTask.class);

        verify(executorService).schedule(taskCaptor.capture(), eq(0L), eq(TimeUnit.MILLISECONDS));

        final BlockNodeConnectionTask task = taskCaptor.getValue();
        final BlockNodeConnection connection = connectionFromTask(task);
        final BlockNodeConfig nodeConfig = connection.getNodeConfig();

        // verify we are trying to connect to one of the priority 1 nodes
        assertThat(nodeConfig.priority()).isEqualTo(1);
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.UNINITIALIZED);

        verifyNoMoreInteractions(executorService);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_noneAvailable() {
        final List<BlockNodeConfig> availableNodes = availableNodes();
        availableNodes.clear();

        final boolean isScheduled = connectionManager.selectNewBlockNodeForStreaming(false);

        assertThat(isScheduled).isFalse();

        verifyNoInteractions(executorService);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_noneAvailableInGoodState() {
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        final List<BlockNodeConfig> availableNodes = availableNodes();
        availableNodes.clear();

        final BlockNodeConfig node1Config = newBlockNodeConfig(8080, 1);
        final BlockNodeConnection node1Conn = mock(BlockNodeConnection.class);
        final BlockNodeConfig node2Config = newBlockNodeConfig(8081, 2);
        final BlockNodeConnection node2Conn = mock(BlockNodeConnection.class);

        availableNodes.add(node1Config);
        availableNodes.add(node2Config);
        connections.put(node1Config, node1Conn);
        connections.put(node2Config, node2Conn);

        final boolean isScheduled = connectionManager.selectNewBlockNodeForStreaming(false);

        assertThat(isScheduled).isFalse();

        verifyNoInteractions(executorService);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_higherPriorityThanActive() {
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        final List<BlockNodeConfig> availableNodes = availableNodes();
        final AtomicReference<BlockNodeConnection> activeConnection = activeConnection();

        final BlockNodeConfig node1Config = newBlockNodeConfig(8081, 1);
        final BlockNodeConfig node2Config = newBlockNodeConfig(8082, 2);
        final BlockNodeConnection node2Conn = mock(BlockNodeConnection.class);
        final BlockNodeConfig node3Config = newBlockNodeConfig(8083, 3);

        connections.put(node2Config, node2Conn);
        availableNodes.add(node1Config);
        availableNodes.add(node2Config);
        availableNodes.add(node3Config);
        activeConnection.set(node2Conn);

        final boolean isScheduled = connectionManager.selectNewBlockNodeForStreaming(false);

        assertThat(isScheduled).isTrue();

        final ArgumentCaptor<BlockNodeConnectionTask> taskCaptor =
                ArgumentCaptor.forClass(BlockNodeConnectionTask.class);

        verify(executorService).schedule(taskCaptor.capture(), eq(0L), eq(TimeUnit.MILLISECONDS));

        final BlockNodeConnectionTask task = taskCaptor.getValue();
        final BlockNodeConnection connection = connectionFromTask(task);
        final BlockNodeConfig nodeConfig = connection.getNodeConfig();

        // verify we are trying to connect to one of the priority 1 nodes
        assertThat(nodeConfig.priority()).isEqualTo(1);
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.UNINITIALIZED);

        verifyNoMoreInteractions(executorService);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_lowerPriorityThanActive() {
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        final List<BlockNodeConfig> availableNodes = availableNodes();
        final AtomicReference<BlockNodeConnection> activeConnection = activeConnection();

        final BlockNodeConfig node1Config = newBlockNodeConfig(8080, 1);
        final BlockNodeConnection node1Conn = mock(BlockNodeConnection.class);
        final BlockNodeConfig node2Config = newBlockNodeConfig(8081, 2);
        final BlockNodeConnection node2Conn = mock(BlockNodeConnection.class);
        final BlockNodeConfig node3Config = newBlockNodeConfig(8082, 3);

        connections.put(node1Config, node1Conn);
        connections.put(node2Config, node2Conn);
        availableNodes.add(node1Config);
        availableNodes.add(node2Config);
        availableNodes.add(node3Config);
        activeConnection.set(node2Conn);

        final boolean isScheduled = connectionManager.selectNewBlockNodeForStreaming(false);

        assertThat(isScheduled).isTrue();

        final ArgumentCaptor<BlockNodeConnectionTask> taskCaptor =
                ArgumentCaptor.forClass(BlockNodeConnectionTask.class);

        verify(executorService).schedule(taskCaptor.capture(), eq(0L), eq(TimeUnit.MILLISECONDS));

        final BlockNodeConnectionTask task = taskCaptor.getValue();
        final BlockNodeConnection connection = connectionFromTask(task);
        final BlockNodeConfig nodeConfig = connection.getNodeConfig();

        // verify we are trying to connect to one of the priority 1 nodes
        assertThat(nodeConfig.priority()).isEqualTo(3);
        assertThat(nodeConfig.port()).isEqualTo(8082);
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.UNINITIALIZED);

        verifyNoMoreInteractions(executorService);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_samePriority() {
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        final List<BlockNodeConfig> availableNodes = availableNodes();
        final AtomicReference<BlockNodeConnection> activeConnection = activeConnection();

        final BlockNodeConfig node1Config = newBlockNodeConfig(8080, 1);
        final BlockNodeConnection node1Conn = mock(BlockNodeConnection.class);
        final BlockNodeConfig node2Config = newBlockNodeConfig(8081, 2);
        final BlockNodeConnection node2Conn = mock(BlockNodeConnection.class);
        final BlockNodeConfig node3Config = newBlockNodeConfig(8082, 2);
        final BlockNodeConfig node4Config = newBlockNodeConfig(8083, 3);

        connections.put(node1Config, node1Conn);
        connections.put(node2Config, node2Conn);
        availableNodes.add(node1Config);
        availableNodes.add(node2Config);
        availableNodes.add(node3Config);
        availableNodes.add(node4Config);
        activeConnection.set(node2Conn);

        final boolean isScheduled = connectionManager.selectNewBlockNodeForStreaming(false);

        assertThat(isScheduled).isTrue();

        final ArgumentCaptor<BlockNodeConnectionTask> taskCaptor =
                ArgumentCaptor.forClass(BlockNodeConnectionTask.class);

        verify(executorService).schedule(taskCaptor.capture(), eq(0L), eq(TimeUnit.MILLISECONDS));

        final BlockNodeConnectionTask task = taskCaptor.getValue();
        final BlockNodeConnection connection = connectionFromTask(task);
        final BlockNodeConfig nodeConfig = connection.getNodeConfig();

        // verify we are trying to connect to one of the priority 1 nodes
        assertThat(nodeConfig.priority()).isEqualTo(2);
        assertThat(nodeConfig.port()).isEqualTo(8082);
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.UNINITIALIZED);

        verifyNoMoreInteractions(executorService);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testOpenBlock_noActiveConnection() {
        final AtomicReference<BlockNodeConnection> activeConnection = activeConnection();
        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        final AtomicLong jumpTargetBlock = jumpTarget();

        activeConnection.set(null);
        streamingBlockNumber.set(-1);
        jumpTargetBlock.set(-1);

        connectionManager.openBlock(100L);

        assertThat(streamingBlockNumber).hasValue(-1L);
        assertThat(jumpTargetBlock).hasValue(-1L);

        verify(metrics).recordNoActiveConnection();
        verifyNoInteractions(executorService);
        verifyNoInteractions(bufferService);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testOpenBlock_alreadyStreaming() {
        final AtomicReference<BlockNodeConnection> activeConnection = activeConnection();
        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        final AtomicLong jumpTargetBlock = jumpTarget();
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);

        activeConnection.set(connection);
        streamingBlockNumber.set(99);
        jumpTargetBlock.set(-1);

        connectionManager.openBlock(100L);

        assertThat(streamingBlockNumber).hasValue(99L);
        assertThat(jumpTargetBlock).hasValue(-1L);

        verifyNoInteractions(executorService);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testOpenBlock() {
        final AtomicReference<BlockNodeConnection> activeConnection = activeConnection();
        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        final AtomicLong jumpTargetBlock = jumpTarget();
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);

        activeConnection.set(connection);
        streamingBlockNumber.set(-1L);
        jumpTargetBlock.set(-1);

        connectionManager.openBlock(100L);

        assertThat(streamingBlockNumber).hasValue(-1L);
        assertThat(jumpTargetBlock).hasValue(100L);

        verifyNoInteractions(executorService);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testUpdateLastVerifiedBlock_connDoesNotExist() {
        final Map<BlockNodeConfig, Long> lastVerifiedBlockPerConnection = lastVerifiedBlockPerConnection();
        lastVerifiedBlockPerConnection.clear(); // ensure nothing exists in the map
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(8080, 1);

        connectionManager.updateLastVerifiedBlock(nodeConfig, 100L);

        assertThat(lastVerifiedBlockPerConnection).containsEntry(nodeConfig, 100L);

        verify(bufferService).setLatestAcknowledgedBlock(100L);
        verifyNoInteractions(executorService);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testUpdateLastVerifiedBlock_connExists_verifiedNewer() {
        final Map<BlockNodeConfig, Long> lastVerifiedBlockPerConnection = lastVerifiedBlockPerConnection();
        lastVerifiedBlockPerConnection.clear();
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(8080, 1);
        lastVerifiedBlockPerConnection.put(nodeConfig, 99L);

        // signal a 'newer' block has been verified - newer meaning this block (100) is younger than the older block
        // (99)
        connectionManager.updateLastVerifiedBlock(nodeConfig, 100L);

        assertThat(lastVerifiedBlockPerConnection).containsEntry(nodeConfig, 100L);

        verify(bufferService).setLatestAcknowledgedBlock(100L);
        verifyNoInteractions(executorService);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testUpdateLastVerifiedBlock_connExists_verifiedOlder() {
        final Map<BlockNodeConfig, Long> lastVerifiedBlockPerConnection = lastVerifiedBlockPerConnection();
        lastVerifiedBlockPerConnection.clear();
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(8080, 1);
        lastVerifiedBlockPerConnection.put(nodeConfig, 100L);

        // signal an 'older' block has been verified - older meaning the block number being verified is less than the
        // one already verified
        connectionManager.updateLastVerifiedBlock(nodeConfig, 60L);

        assertThat(lastVerifiedBlockPerConnection).containsEntry(nodeConfig, 100L);

        verify(bufferService).setLatestAcknowledgedBlock(60L);
        verifyNoInteractions(executorService);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testJumpToBlock() {
        final AtomicLong jumpTarget = jumpTarget();
        jumpTarget.set(-1);

        connectionManager.jumpToBlock(16);

        assertThat(jumpTarget).hasValue(16L);

        verifyNoInteractions(executorService);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConnectionTask_managerNotActive() {
        final AtomicBoolean isManagerActive = isActiveFlag();
        isManagerActive.set(false);

        final BlockNodeConnection connection = mock(BlockNodeConnection.class);

        connectionManager.new BlockNodeConnectionTask(connection, Duration.ofSeconds(1), null, false).run();

        verifyNoInteractions(connection);
        verifyNoInteractions(executorService);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConnectionTask_higherPriorityConnectionExists_withoutForce() {
        isActiveFlag().set(true);
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        final BlockNodeConnection activeConnection = mock(BlockNodeConnection.class);
        final BlockNodeConfig activeConnectionConfig = newBlockNodeConfig(8080, 1);
        doReturn(activeConnectionConfig).when(activeConnection).getNodeConfig();
        activeConnectionRef.set(activeConnection);

        final BlockNodeConnection newConnection = mock(BlockNodeConnection.class);
        final BlockNodeConfig newConnectionConfig = newBlockNodeConfig(8081, 2);
        doReturn(newConnectionConfig).when(newConnection).getNodeConfig();

        connectionManager.new BlockNodeConnectionTask(newConnection, Duration.ofSeconds(1), null, false).run();

        assertThat(activeConnectionRef).hasValue(activeConnection);

        verify(activeConnection).getNodeConfig();
        verify(newConnection).getNodeConfig();
        verify(newConnection).close(true);

        verifyNoMoreInteractions(activeConnection);
        verifyNoMoreInteractions(newConnection);
        verifyNoInteractions(executorService);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConnectionTask_higherPriorityConnectionExists_withForce() {
        isActiveFlag().set(true);

        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        final BlockNodeConnection activeConnection = mock(BlockNodeConnection.class);
        final BlockNodeConfig activeConnectionConfig = newBlockNodeConfig(8080, 1);
        doReturn(activeConnectionConfig).when(activeConnection).getNodeConfig();
        activeConnectionRef.set(activeConnection);

        final BlockNodeConnection newConnection = mock(BlockNodeConnection.class);
        final BlockNodeConfig newConnectionConfig = newBlockNodeConfig(8081, 2);
        doReturn(newConnectionConfig).when(newConnection).getNodeConfig();

        connectionManager.new BlockNodeConnectionTask(newConnection, Duration.ofSeconds(1), null, true).run();

        assertThat(activeConnectionRef).hasValue(newConnection);

        verify(activeConnection).getNodeConfig();
        verify(activeConnection).close(true);
        verify(newConnection, times(2)).getNodeConfig();
        verify(newConnection).createRequestPipeline();
        verify(newConnection).updateConnectionState(ConnectionState.ACTIVE);
        verify(bufferService).getLastBlockNumberProduced();
        verify(metrics).recordActiveConnectionIp(anyLong());

        verifyNoMoreInteractions(activeConnection);
        verifyNoMoreInteractions(newConnection);
        verifyNoInteractions(executorService);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testConnectionTask_connectionUninitialized_withActiveLowerPriorityConnection() {
        // also put an active connection into the state, but let it have a lower priority so the new connection
        // takes its place as the active one
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        final BlockNodeConnection activeConnection = mock(BlockNodeConnection.class);
        final BlockNodeConfig activeConnectionConfig = newBlockNodeConfig(8080, 2);
        doReturn(activeConnectionConfig).when(activeConnection).getNodeConfig();
        activeConnectionRef.set(activeConnection);
        isActiveFlag().set(true);

        final BlockNodeConnection newConnection = mock(BlockNodeConnection.class);
        final BlockNodeConfig newConnectionConfig = newBlockNodeConfig(8081, 1);
        doReturn(newConnectionConfig).when(newConnection).getNodeConfig();

        connectionManager.new BlockNodeConnectionTask(newConnection, Duration.ofSeconds(1), 30L, false).run();

        final AtomicLong jumpTarget = jumpTarget();
        assertThat(jumpTarget).hasValue(30L);
        assertThat(activeConnectionRef).hasValue(newConnection);

        verify(activeConnection).getNodeConfig();
        verify(activeConnection).close(true);
        verify(newConnection, times(2)).getNodeConfig();
        verify(newConnection).createRequestPipeline();
        verify(newConnection).updateConnectionState(ConnectionState.ACTIVE);
        verify(metrics).recordActiveConnectionIp(anyLong());

        verifyNoMoreInteractions(activeConnection);
        verifyNoMoreInteractions(newConnection);
        verifyNoInteractions(executorService);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testConnectionTask_sameConnectionAsActive() {
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        final BlockNodeConnection activeConnection = mock(BlockNodeConnection.class);
        activeConnectionRef.set(activeConnection);

        connectionManager.new BlockNodeConnectionTask(activeConnection, Duration.ofSeconds(1), null, false).run();

        verifyNoInteractions(activeConnection);
        verifyNoInteractions(executorService);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConnectionTask_noActiveConnection() {
        isActiveFlag().set(true);
        final AtomicLong jumpTarget = jumpTarget();
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        activeConnectionRef.set(null);

        final BlockNodeConfig newConnectionConfig = newBlockNodeConfig(8081, 1);
        final BlockNodeConnection newConnection = mock(BlockNodeConnection.class);
        doReturn(10L).when(bufferService).getLastBlockNumberProduced();
        doReturn(newConnectionConfig).when(newConnection).getNodeConfig();

        connectionManager.new BlockNodeConnectionTask(newConnection, Duration.ofSeconds(1), null, false).run();

        assertThat(jumpTarget).hasValue(10L);
        assertThat(activeConnectionRef).hasValue(newConnection);

        verify(newConnection).createRequestPipeline();
        verify(newConnection).updateConnectionState(ConnectionState.ACTIVE);
        verify(bufferService).getLastBlockNumberProduced();
        verify(metrics).recordActiveConnectionIp(anyLong());

        verifyNoMoreInteractions(newConnection);
        verifyNoInteractions(executorService);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testConnectionTask_closeExistingActiveFailed() {
        isActiveFlag().set(true);
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        final BlockNodeConnection activeConnection = mock(BlockNodeConnection.class);
        final BlockNodeConfig activeConnectionConfig = newBlockNodeConfig(8080, 2);
        doReturn(activeConnectionConfig).when(activeConnection).getNodeConfig();
        doThrow(new RuntimeException("why does this always happen to me"))
                .when(activeConnection)
                .close(true);
        activeConnectionRef.set(activeConnection);

        final BlockNodeConnection newConnection = mock(BlockNodeConnection.class);
        final BlockNodeConfig newConnectionConfig = newBlockNodeConfig(8081, 1);
        doReturn(newConnectionConfig).when(newConnection).getNodeConfig();

        connectionManager.new BlockNodeConnectionTask(newConnection, Duration.ofSeconds(1), 30L, false).run();

        final AtomicLong jumpTarget = jumpTarget();
        assertThat(jumpTarget).hasValue(30L);
        assertThat(activeConnectionRef).hasValue(newConnection);

        verify(activeConnection).getNodeConfig();
        verify(activeConnection).close(true);
        verify(newConnection, times(2)).getNodeConfig();
        verify(newConnection).createRequestPipeline();
        verify(newConnection).updateConnectionState(ConnectionState.ACTIVE);
        verify(metrics).recordActiveConnectionIp(anyLong());

        verifyNoMoreInteractions(activeConnection);
        verifyNoMoreInteractions(newConnection);
        verifyNoInteractions(executorService);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testConnectionTask_reschedule_delayZero() {
        isActiveFlag().set(true);
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        activeConnectionRef.set(null);

        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        doThrow(new RuntimeException("are you seeing this?")).when(connection).createRequestPipeline();

        final BlockNodeConnectionTask task =
                connectionManager.new BlockNodeConnectionTask(connection, Duration.ZERO, 10L, false);

        task.run();

        verify(connection).createRequestPipeline();
        verify(executorService).schedule(eq(task), anyLong(), eq(TimeUnit.MILLISECONDS));
        verify(metrics).recordConnectionCreateFailure();

        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(executorService);
        verifyNoInteractions(bufferService);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testConnectionTask_reschedule_delayNonZero() {
        isActiveFlag().set(true);
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        activeConnectionRef.set(null);

        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        doThrow(new RuntimeException("are you seeing this?")).when(connection).createRequestPipeline();

        final BlockNodeConnectionTask task =
                connectionManager.new BlockNodeConnectionTask(connection, Duration.ofSeconds(10), 10L, false);

        task.run();

        verify(connection).createRequestPipeline();
        verify(executorService).schedule(eq(task), anyLong(), eq(TimeUnit.MILLISECONDS));
        verify(metrics).recordConnectionCreateFailure();
        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(executorService);
        verifyNoInteractions(bufferService);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testConnectionTask_reschedule_failure() {
        isActiveFlag().set(true);
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        activeConnectionRef.set(null);
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();

        final BlockNodeConfig nodeConfig = newBlockNodeConfig(8080, 1);
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        doReturn(nodeConfig).when(connection).getNodeConfig();
        doThrow(new RuntimeException("are you seeing this?")).when(connection).createRequestPipeline();
        doThrow(new RuntimeException("welp, this is my life now"))
                .when(executorService)
                .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));

        connections.clear();
        connections.put(nodeConfig, connection);

        final BlockNodeConnectionTask task =
                connectionManager.new BlockNodeConnectionTask(connection, Duration.ofSeconds(10), 10L, false);

        task.run();

        assertThat(connections).isEmpty(); // connection should be removed

        verify(connection).createRequestPipeline();
        verify(executorService).schedule(eq(task), anyLong(), eq(TimeUnit.MILLISECONDS));
        verify(connection).getNodeConfig();
        verify(connection).close(true);
        verify(metrics).recordConnectionCreateFailure();

        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(executorService);
        verifyNoMoreInteractions(metrics);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testJumpToBlockIfNeeded_notSet() {
        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(-1L);
        final AtomicLong jumpTarget = jumpTarget();
        jumpTarget.set(-1L);

        invoke_jumpToBlockIfNeeded();

        assertThat(streamingBlockNumber).hasValue(-1L); // unchanged

        verifyNoInteractions(executorService);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testJumpToBlockIfNeeded() {
        final AtomicLong streamingBlockNumber = streamingBlockNumber();
        streamingBlockNumber.set(-1L);
        final AtomicLong jumpTarget = jumpTarget();
        jumpTarget.set(10L);

        invoke_jumpToBlockIfNeeded();

        assertThat(streamingBlockNumber).hasValue(10L);

        verifyNoInteractions(executorService);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testProcessStreamingToBlockNode_missingBlock_latestBlockAfterCurrentStreaming() {
        final BlockNodeConfig node1Config = newBlockNodeConfig(8080, 1);
        final BlockNodeConfig node2Config = newBlockNodeConfig(8081, 2);
        final List<BlockNodeConfig> availableNodes = availableNodes();
        availableNodes.clear();
        availableNodes.add(node1Config);
        availableNodes.add(node2Config);
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        activeConnectionRef.set(connection);
        final AtomicLong currentStreamingBlock = streamingBlockNumber();
        currentStreamingBlock.set(10L);
        doReturn(ConnectionState.ACTIVE).when(connection).getConnectionState();
        doReturn(node1Config).when(connection).getNodeConfig();
        doReturn(null).when(bufferService).getBlockState(10L);
        doReturn(11L).when(bufferService).getLastBlockNumberProduced();

        final boolean shouldSleep = invoke_processStreamingToBlockNode(connection);

        assertThat(shouldSleep).isTrue();

        verify(bufferService).getBlockState(10L);
        verify(bufferService).getLastBlockNumberProduced();
        verify(connection).close(true);
        // one scheduled task to reconnect the existing connection later
        verify(executorService).schedule(any(BlockNodeConnectionTask.class), eq(30_000L), eq(TimeUnit.MILLISECONDS));
        // another task scheduled to connect to a new node immediately
        verify(executorService).schedule(any(BlockNodeConnectionTask.class), eq(0L), eq(TimeUnit.MILLISECONDS));

        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testProcessStreamingToBlockNode_missingBlock() {
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        activeConnectionRef.set(connection);
        final AtomicLong currentStreamingBlock = streamingBlockNumber();
        currentStreamingBlock.set(10L);
        doReturn(ConnectionState.ACTIVE).when(connection).getConnectionState();
        doReturn(null).when(bufferService).getBlockState(10L);
        doReturn(10L).when(bufferService).getLastBlockNumberProduced();

        final boolean shouldSleep = invoke_processStreamingToBlockNode(connection);

        assertThat(shouldSleep).isTrue();

        verify(bufferService).getBlockState(10L);
        verify(bufferService).getLastBlockNumberProduced();

        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testProcessStreamingToBlockNode_zeroRequests() {
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        activeConnectionRef.set(connection);
        final AtomicLong currentStreamingBlock = streamingBlockNumber();
        currentStreamingBlock.set(10L);
        final BlockState blockState = new BlockState(10L);
        doReturn(ConnectionState.ACTIVE).when(connection).getConnectionState();
        doReturn(blockState).when(bufferService).getBlockState(10L);
        doReturn(10L).when(bufferService).getLastBlockNumberProduced();

        final boolean shouldSleep = invoke_processStreamingToBlockNode(connection);

        assertThat(shouldSleep).isTrue();

        verify(bufferService).getBlockState(10L);
        verify(bufferService).getLastBlockNumberProduced();

        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testProcessStreamingToBlockNode_requestsReady() {
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        activeConnectionRef.set(connection);
        final AtomicLong currentStreamingBlock = streamingBlockNumber();
        currentStreamingBlock.set(10L);
        final BlockState blockState = mock(BlockState.class);
        final PublishStreamRequest req = createRequest(newBlockHeaderItem());
        doReturn(ConnectionState.ACTIVE).when(connection).getConnectionState();
        doReturn(req).when(blockState).getRequest(0);
        doReturn(1).when(blockState).numRequestsCreated();
        doReturn(blockState).when(bufferService).getBlockState(10L);
        doReturn(10L).when(bufferService).getLastBlockNumberProduced();

        final boolean shouldSleep = invoke_processStreamingToBlockNode(connection);
        assertThat(shouldSleep).isTrue(); // there is nothing in the queue left to process, so we should sleep

        verify(bufferService).getBlockState(10L);
        verify(bufferService).getLastBlockNumberProduced();
        verify(connection).sendRequest(req);

        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testProcessStreamingToBlockNode_blockEnd_moveToNextBlock() {
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        activeConnectionRef.set(connection);
        final AtomicLong currentStreamingBlock = streamingBlockNumber();
        currentStreamingBlock.set(10L);
        final BlockState blockState = mock(BlockState.class);
        final PublishStreamRequest req = createRequest(newBlockHeaderItem());
        doReturn(ConnectionState.ACTIVE).when(connection).getConnectionState();
        doReturn(req).when(blockState).getRequest(0);
        doReturn(1).when(blockState).numRequestsCreated();
        doReturn(true).when(blockState).isBlockProofSent();
        doReturn(blockState).when(bufferService).getBlockState(10L);
        doReturn(10L).when(bufferService).getLastBlockNumberProduced();

        final boolean shouldSleep = invoke_processStreamingToBlockNode(connection);
        assertThat(shouldSleep)
                .isFalse(); // since we are moving blocks, we should not sleep and instead immediately re-check
        assertThat(currentStreamingBlock).hasValue(11L); // this should get incremented as we move to next

        verify(bufferService).getBlockState(10L);
        verify(bufferService).getLastBlockNumberProduced();
        verify(connection).sendRequest(req);

        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testProcessStreamingToBlockNode_moreRequestsAvailable() {
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        activeConnectionRef.set(connection);
        final AtomicLong currentStreamingBlock = streamingBlockNumber();
        currentStreamingBlock.set(10L);
        final BlockState blockState = mock(BlockState.class);
        final PublishStreamRequest req = createRequest(newBlockHeaderItem());
        doReturn(ConnectionState.ACTIVE).when(connection).getConnectionState();
        doReturn(req).when(blockState).getRequest(0);
        doReturn(2).when(blockState).numRequestsCreated();
        doReturn(false).when(blockState).isBlockProofSent();
        doReturn(blockState).when(bufferService).getBlockState(10L);
        doReturn(10L).when(bufferService).getLastBlockNumberProduced();

        final boolean shouldSleep = invoke_processStreamingToBlockNode(connection);
        assertThat(shouldSleep).isFalse(); // there is nothing in the queue left to process, so we should sleep

        verify(bufferService).getBlockState(10L);
        verify(bufferService).getLastBlockNumberProduced();
        verify(connection).sendRequest(req);

        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testProcessStreamingToBlockNode_noConnection() {
        final boolean shouldSleep = invoke_processStreamingToBlockNode(null);
        assertThat(shouldSleep).isTrue();

        verifyNoInteractions(bufferService);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testProcessStreamingToBlockNode_connectionNotActive() {
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        doReturn(ConnectionState.CLOSING).when(connection).getConnectionState();

        final boolean shouldSleep = invoke_processStreamingToBlockNode(connection);
        assertThat(shouldSleep).isTrue();

        verify(connection).getConnectionState();

        verifyNoMoreInteractions(connection);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testBlockStreamWorkerLoop_managerNotActive() {
        final AtomicBoolean isActive = isActiveFlag();
        isActive.set(false);

        invoke_blockStreamWorkerLoop();

        verifyNoInteractions(bufferService);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testBlockStreamWorkerLoop() throws InterruptedException {
        isActiveFlag().set(true);
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        activeConnectionRef.set(connection);
        final AtomicLong currentStreamingBlock = streamingBlockNumber();
        currentStreamingBlock.set(10L);
        final BlockState blockState = mock(BlockState.class);
        final PublishStreamRequest req1 = createRequest(newBlockHeaderItem());
        final PublishStreamRequest req2 = createRequest(newBlockProofItem());
        doReturn(ConnectionState.ACTIVE).when(connection).getConnectionState();
        doReturn(req1).when(blockState).getRequest(0);
        doReturn(req2).when(blockState).getRequest(1);
        doReturn(2).when(blockState).numRequestsCreated();
        doReturn(true).when(blockState).isBlockProofSent();
        doReturn(blockState).when(bufferService).getBlockState(10L);
        doReturn(10L).when(bufferService).getLastBlockNumberProduced();

        final CountDownLatch doneLatch = new CountDownLatch(1);
        final AtomicReference<Throwable> errorRef = new AtomicReference<>();

        ForkJoinPool.commonPool().execute(() -> {
            try {
                invoke_blockStreamWorkerLoop();
            } catch (final Throwable e) {
                errorRef.set(e);
            } finally {
                doneLatch.countDown();
            }
        });

        final long startMs = System.currentTimeMillis();
        long elapsedMs = 0;
        while (currentStreamingBlock.get() != 11 && elapsedMs < 2_000) {
            // wait up to 2 seconds for the current streaming block to change
            elapsedMs = System.currentTimeMillis() - startMs;
        }

        isActiveFlag().set(false); // stop the loop

        assertThat(doneLatch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(errorRef).hasNullValue();
        assertThat(currentStreamingBlock).hasValue(11L);

        verify(bufferService, atLeast(2)).getBlockState(10L);
        verify(bufferService, atLeast(2)).getLastBlockNumberProduced();
        verify(connection).sendRequest(req1);
        verify(connection).sendRequest(req2);

        verifyNoMoreInteractions(connection);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testBlockStreamWorkerLoop_failure() throws InterruptedException {
        isActiveFlag().set(true);
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        activeConnectionRef.set(connection);
        final AtomicLong currentStreamingBlock = streamingBlockNumber();
        currentStreamingBlock.set(10L);
        final BlockState blockState = mock(BlockState.class);
        final PublishStreamRequest req1 = createRequest(newBlockHeaderItem());
        final PublishStreamRequest req2 = createRequest(newBlockProofItem());
        doReturn(ConnectionState.ACTIVE).when(connection).getConnectionState();
        doReturn(req1).when(blockState).getRequest(0);
        doReturn(req2).when(blockState).getRequest(1);
        doReturn(2).when(blockState).numRequestsCreated();
        doReturn(true).when(blockState).isBlockProofSent();
        doReturn(blockState).when(bufferService).getBlockState(10L);
        doReturn(10L).when(bufferService).getLastBlockNumberProduced();
        when(bufferService.getBlockState(10L))
                .thenThrow(new RuntimeException("foobar"))
                .thenReturn(blockState);

        final CountDownLatch doneLatch = new CountDownLatch(1);
        final AtomicReference<Throwable> errorRef = new AtomicReference<>();

        ForkJoinPool.commonPool().execute(() -> {
            try {
                invoke_blockStreamWorkerLoop();
            } catch (final Throwable e) {
                errorRef.set(e);
            } finally {
                doneLatch.countDown();
            }
        });

        final long startMs = System.currentTimeMillis();
        long elapsedMs = 0;
        while (currentStreamingBlock.get() != 11 && elapsedMs < 2_000) {
            // wait up to 2 seconds for the current streaming block to change
            elapsedMs = System.currentTimeMillis() - startMs;
        }

        isActiveFlag().set(false); // stop the loop

        assertThat(doneLatch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(errorRef).hasNullValue();
        assertThat(currentStreamingBlock).hasValue(11L);

        verify(bufferService, atLeast(2)).getBlockState(10L);
        verify(bufferService, atLeast(2)).getLastBlockNumberProduced();
        verify(connection).sendRequest(req1);
        verify(connection).sendRequest(req2);

        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testScheduleAndSelectNewNode_streamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        isStreamingEnabled.set(false);
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);

        connectionManager.rescheduleConnection(connection, Duration.ZERO, null, true);

        verifyNoInteractions(connection);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testScheduleConnectionAttempt_streamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        isStreamingEnabled.set(false);
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);

        connectionManager.scheduleConnectionAttempt(newBlockNodeConfig(8080, 1), Duration.ZERO, 10L);

        verifyNoInteractions(connection);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testShutdown_streamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        isStreamingEnabled.set(false);

        connectionManager.shutdown();

        verifyNoInteractions(bufferService);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testStartup_streamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        final AtomicBoolean isManagerActive = isActiveFlag();
        isStreamingEnabled.set(false);
        isManagerActive.set(false);

        assertThat(isManagerActive).isFalse();

        verifyNoInteractions(bufferService);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_streamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        isStreamingEnabled.set(false);

        connectionManager.selectNewBlockNodeForStreaming(false);

        verifyNoInteractions(bufferService);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testOpenBlock_streamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        isStreamingEnabled.set(false);

        connectionManager.openBlock(10L);

        verifyNoInteractions(bufferService);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testUpdateLastVerifiedBlock_streamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        isStreamingEnabled.set(false);

        connectionManager.updateLastVerifiedBlock(mock(BlockNodeConfig.class), 1L);

        verifyNoInteractions(bufferService);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testJumpToBlock_streamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        isStreamingEnabled.set(false);

        connectionManager.jumpToBlock(100L);

        assertThat(jumpTarget()).hasValue(-1L);

        verifyNoInteractions(bufferService);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConnectionTask_run_streamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        isStreamingEnabled.set(false);
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);

        connectionManager.new BlockNodeConnectionTask(connection, Duration.ZERO, 100L, false);

        verifyNoInteractions(connection);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConnectionTask_MetricsIpFailsInvalidAddress() {
        isActiveFlag().set(true);
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        final List<BlockNodeConfig> availableNodes = availableNodes();

        final BlockNodeConfig newConnectionConfig = new BlockNodeConfig("::1", 50211, 1);
        final BlockNodeConnection newConnection = mock(BlockNodeConnection.class);
        doReturn(newConnectionConfig).when(newConnection).getNodeConfig();

        connections.put(newConnectionConfig, newConnection);
        availableNodes.add(newConnectionConfig);

        connectionManager.new BlockNodeConnectionTask(newConnection, Duration.ZERO, null, false).run();

        verify(bufferService).getLastBlockNumberProduced();
        verify(metrics).recordActiveConnectionIp(-1L);

        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testConnectionTask_MetricsIpFailsInvalidHost() {
        isActiveFlag().set(true);
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        final List<BlockNodeConfig> availableNodes = availableNodes();

        final BlockNodeConfig newConnectionConfig = new BlockNodeConfig("invalid.hostname.for.test", 50211, 1);
        final BlockNodeConnection newConnection = mock(BlockNodeConnection.class);
        doReturn(newConnectionConfig).when(newConnection).getNodeConfig();

        connections.put(newConnectionConfig, newConnection);
        availableNodes.add(newConnectionConfig);

        connectionManager.new BlockNodeConnectionTask(newConnection, Duration.ZERO, null, false).run();

        verify(bufferService).getLastBlockNumberProduced();
        verify(metrics).recordActiveConnectionIp(-1L);

        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(metrics);
    }

    // Utilities

    private void invoke_createNewGrpcClient(final BlockNodeConfig config) {
        try {
            createNewGrpcClientHandle.invoke(connectionManager, config);
        } catch (final Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private void invoke_blockStreamWorkerLoop() {
        try {
            blockStreamWorkerLoopHandle.invoke(connectionManager);
        } catch (final Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private void invoke_jumpToBlockIfNeeded() {
        try {
            jumpToBlockIfNeededHandle.invoke(connectionManager);
        } catch (final Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private boolean invoke_processStreamingToBlockNode(final BlockNodeConnection connection) {
        try {
            return (Boolean) processStreamingToBlockNodeHandle.invoke(connectionManager, connection);
        } catch (final Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private BlockNodeConnection connectionFromTask(@NonNull final BlockNodeConnectionTask task) {
        requireNonNull(task);
        return (BlockNodeConnection) connectivityTaskConnectionHandle.get(task);
    }

    private AtomicBoolean isStreamingEnabled() {
        return (AtomicBoolean) isStreamingEnabledHandle.get(connectionManager);
    }

    @SuppressWarnings("unchecked")
    private Map<BlockNodeConfig, RetryState> retryStates() {
        return (Map<BlockNodeConfig, RetryState>) retryStatesHandle.get(connectionManager);
    }

    @SuppressWarnings("unchecked")
    private Map<BlockNodeConfig, Long> lastVerifiedBlockPerConnection() {
        return (Map<BlockNodeConfig, Long>) lastVerifiedBlockPerConnectionHandle.get(connectionManager);
    }

    private AtomicLong streamingBlockNumber() {
        return (AtomicLong) streamingBlockNumberHandle.get(connectionManager);
    }

    private AtomicLong jumpTarget() {
        return (AtomicLong) jumpTargetHandle.get(connectionManager);
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<BlockNodeConnection> activeConnection() {
        return (AtomicReference<BlockNodeConnection>) activeConnectionRefHandle.get(connectionManager);
    }

    @SuppressWarnings("unchecked")
    private List<BlockNodeConfig> availableNodes() {
        return (List<BlockNodeConfig>) availableNodesHandle.get(connectionManager);
    }

    @SuppressWarnings("unchecked")
    private Map<BlockNodeConfig, BlockNodeConnection> connections() {
        return (Map<BlockNodeConfig, BlockNodeConnection>) connectionsHandle.get(connectionManager);
    }

    private AtomicBoolean isActiveFlag() {
        return (AtomicBoolean) isManagerActiveHandle.get(connectionManager);
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<Thread> workerThread() {
        return (AtomicReference<Thread>) workerThreadRefHandle.get(connectionManager);
    }

    @SuppressWarnings("unchecked")
    private Map<BlockNodeConfig, BlockNodeStats> nodeStats() {
        return (Map<BlockNodeConfig, BlockNodeStats>) nodeStatsHandle.get(connectionManager);
    }

    private void resetMocks() {
        reset(bufferService, metrics, executorService);
    }
}
