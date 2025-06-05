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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.blocks.impl.streaming.BlockBufferService.BlockStreamQueueItem;
import com.hedera.node.app.blocks.impl.streaming.BlockBufferService.BlockStreamQueueItemType;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnection.ConnectionState;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager.BlockNodeConnectionTask;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.internal.network.BlockNodeConfig;
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
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.block.api.PublishStreamRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockNodeConnectionManagerTest extends BlockNodeCommunicationTestBase {

    private static final Logger logger = LogManager.getLogger(BlockNodeConnectionManagerTest.class);

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
    private static final MethodHandle jumpToBlockIfNeededHandle;
    private static final MethodHandle processBlockStreamQueueHandle;
    private static final MethodHandle processStreamingToBlockNodeHandle;
    private static final MethodHandle blockStreamWorkerLoopHandle;

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

            final Method jumpToBlockIfNeeded =
                    BlockNodeConnectionManager.class.getDeclaredMethod("jumpToBlockIfNeeded");
            jumpToBlockIfNeeded.setAccessible(true);
            jumpToBlockIfNeededHandle = lookup.unreflect(jumpToBlockIfNeeded);

            final Method processBlockStreamQueue =
                    BlockNodeConnectionManager.class.getDeclaredMethod("processBlockStreamQueue");
            processBlockStreamQueue.setAccessible(true);
            processBlockStreamQueueHandle = lookup.unreflect(processBlockStreamQueue);

            final Method processStreamingToBlockNode =
                    BlockNodeConnectionManager.class.getDeclaredMethod("processStreamingToBlockNode");
            processStreamingToBlockNode.setAccessible(true);
            processStreamingToBlockNodeHandle = lookup.unreflect(processStreamingToBlockNode);

            final Method blockStreamWorkerLoop =
                    BlockNodeConnectionManager.class.getDeclaredMethod("blockStreamWorkerLoop");
            blockStreamWorkerLoop.setAccessible(true);
            blockStreamWorkerLoopHandle = lookup.unreflect(blockStreamWorkerLoop);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BlockNodeConnectionManager connectionManager;

    private BlockBufferService stateManager;
    private BlockStreamMetrics metrics;
    private ScheduledExecutorService executorService;
    private Queue<BlockStreamQueueItem> blockStreamItemQueue;

    @BeforeEach
    void beforeEach() {
        final ConfigProvider configProvider = createConfigProvider();
        stateManager = mock(BlockBufferService.class);
        metrics = mock(BlockStreamMetrics.class);
        executorService = mock(ScheduledExecutorService.class);
        blockStreamItemQueue = new ConcurrentLinkedQueue<>();

        connectionManager = new BlockNodeConnectionManager(configProvider, stateManager, metrics, executorService);
        connectionManager.start();

        // Disable the background worker thread to make testing in isolation better
        disableWorkerThread();

        lenient().when(stateManager.getBlockStreamItemQueue()).thenReturn(blockStreamItemQueue);
    }

    @Test
    void testRescheduleAndSelectNode() {
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final Duration delay = Duration.ofSeconds(1);

        connectionManager.rescheduleAndSelectNewNode(connection, delay);

        // Verify task created to reconnect to the failing connection after a delay
        verify(executorService)
                .schedule(any(BlockNodeConnectionTask.class), eq(delay.toMillis()), eq(TimeUnit.MILLISECONDS));
        // Verify task created to connect to a new node without delay
        verify(executorService).schedule(any(BlockNodeConnectionTask.class), eq(0L), eq(TimeUnit.MILLISECONDS));
        verify(connection).updateConnectionState(ConnectionState.CONNECTING);
        verifyNoMoreInteractions(connection);
        verifyNoInteractions(stateManager);
        verifyNoInteractions(metrics);
        verifyNoMoreInteractions(executorService);
    }

    @Test
    void testScheduleConnectionAttempt() {
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);

        connectionManager.scheduleConnectionAttempt(connection, Duration.ofSeconds(2), 100L);

        verify(executorService).schedule(any(BlockNodeConnectionTask.class), eq(2_000L), eq(TimeUnit.MILLISECONDS));
        verify(connection).updateConnectionState(ConnectionState.CONNECTING);
        verifyNoMoreInteractions(connection);
        verifyNoInteractions(stateManager);
        verifyNoInteractions(metrics);
        verifyNoMoreInteractions(executorService);
    }

    @Test
    void testScheduleConnectionAttempt_negativeDelay() {
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);

        connectionManager.scheduleConnectionAttempt(connection, Duration.ofSeconds(-2), 100L);

        verify(executorService).schedule(any(BlockNodeConnectionTask.class), eq(0L), eq(TimeUnit.MILLISECONDS));
        verify(connection).updateConnectionState(ConnectionState.CONNECTING);
        verifyNoInteractions(stateManager);
        verifyNoInteractions(metrics);
        verifyNoMoreInteractions(executorService);
        verifyNoMoreInteractions(connection);
    }

    @Test
    void testScheduleConnectionAttempt_failure() {
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        doThrow(new RuntimeException("what the..."))
                .when(executorService)
                .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));

        connectionManager.scheduleConnectionAttempt(connection, Duration.ofSeconds(2), 100L);

        verify(executorService).schedule(any(BlockNodeConnectionTask.class), eq(2_000L), eq(TimeUnit.MILLISECONDS));
        verify(connection).updateConnectionState(ConnectionState.CONNECTING);
        verify(connection).close();
        verifyNoInteractions(stateManager);
        verifyNoInteractions(metrics);
        verifyNoMoreInteractions(executorService);
        verifyNoMoreInteractions(connection);
    }

    @Test
    void testShutdown() throws InterruptedException {
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        // add some fake connections
        final BlockNodeConfig node1Config = new BlockNodeConfig("localhost", 8080, 1);
        final BlockNodeConnection node1Conn = mock(BlockNodeConnection.class);
        final BlockNodeConfig node2Config = new BlockNodeConfig("localhost", 8081, 2);
        final BlockNodeConnection node2Conn = mock(BlockNodeConnection.class);
        final BlockNodeConfig node3Config = new BlockNodeConfig("localhost", 8082, 3);
        final BlockNodeConnection node3Conn = mock(BlockNodeConnection.class);
        connections.put(node1Config, node1Conn);
        connections.put(node2Config, node2Conn);
        connections.put(node3Config, node3Conn);

        // introduce a failure on one of the connection closes to ensure the shutdown process does not fail prematurely
        doThrow(new RuntimeException("oops, I did it again")).when(node2Conn).close();

        final AtomicBoolean isActive = isActiveFlag();
        final Thread dummyWorkerThread = mock(Thread.class);
        final AtomicReference<Thread> workerThreadRef = workerThread();
        workerThreadRef.set(dummyWorkerThread);

        connectionManager.shutdown();

        assertThat(connections).isEmpty();
        assertThat(isActive).isFalse();

        verify(node1Conn).close();
        verify(node2Conn).close();
        verify(node3Conn).close();
        verify(dummyWorkerThread).interrupt();
        verify(dummyWorkerThread).join();
        verifyNoMoreInteractions(node1Conn);
        verifyNoMoreInteractions(node2Conn);
        verifyNoMoreInteractions(node3Conn);
        verifyNoInteractions(executorService);
        verifyNoInteractions(stateManager);
        verifyNoInteractions(metrics);
    }

    @Test
    void testShutdown_withWorkerThreadInterrupt() throws InterruptedException {
        final AtomicBoolean isActive = isActiveFlag();
        final Thread dummyWorkerThread = mock(Thread.class);

        doThrow(new InterruptedException("wakey wakey, eggs and bakey"))
                .when(dummyWorkerThread)
                .join();

        final AtomicReference<Thread> workerThreadRef = workerThread();
        workerThreadRef.set(dummyWorkerThread);

        connectionManager.shutdown();

        assertThat(isActive).isFalse();

        verify(dummyWorkerThread).interrupt();
        verify(dummyWorkerThread).join();
        verifyNoInteractions(executorService);
        verifyNoInteractions(stateManager);
        verifyNoInteractions(metrics);
    }

    @Test
    void testStartup_alreadyActive() {
        final AtomicBoolean isActive = isActiveFlag();
        isActive.set(true);

        final Exception exception = catchException(() -> connectionManager.start());
        assertThat(exception)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Connection manager already started");

        verifyNoInteractions(executorService);
        verifyNoInteractions(stateManager);
        verifyNoInteractions(metrics);
    }

    @Test
    void testStartup_noNodesAvailable() {
        final AtomicBoolean isActive = isActiveFlag();
        isActive.set(false);

        final List<BlockNodeConfig> availableNodes = availableNodes();
        availableNodes.clear(); // remove all available nodes from config

        final Exception exception = catchException(() -> connectionManager.start());
        assertThat(exception)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No block nodes available to connect to");

        verify(stateManager, times(2)).getBlockStreamItemQueue();
        verify(metrics, times(1)).registerMetrics();

        verifyNoInteractions(executorService);
        verifyNoMoreInteractions(stateManager);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testStartup() {
        final AtomicBoolean isActive = isActiveFlag();
        isActive.set(false);

        final List<BlockNodeConfig> availableNodes = availableNodes();
        availableNodes.clear();
        availableNodes.add(new BlockNodeConfig("localhost", 8080, 1));
        availableNodes.add(new BlockNodeConfig("localhost", 8081, 1));
        availableNodes.add(new BlockNodeConfig("localhost", 8082, 2));
        availableNodes.add(new BlockNodeConfig("localhost", 8083, 3));
        availableNodes.add(new BlockNodeConfig("localhost", 8084, 3));

        connectionManager.start();

        final ArgumentCaptor<BlockNodeConnectionTask> taskCaptor =
                ArgumentCaptor.forClass(BlockNodeConnectionTask.class);

        verify(executorService).schedule(taskCaptor.capture(), eq(0L), eq(TimeUnit.MILLISECONDS));

        final BlockNodeConnectionTask task = taskCaptor.getValue();
        final BlockNodeConnection connection = connectionFromTask(task);
        final BlockNodeConfig nodeConfig = connection.getNodeConfig();

        // verify we are trying to connect to one of the priority 1 nodes
        assertThat(nodeConfig.priority()).isEqualTo(1);
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CONNECTING);

        verify(stateManager, times(2)).getBlockStreamItemQueue();
        verify(metrics, times(1)).registerMetrics();

        verifyNoMoreInteractions(executorService);
        verifyNoMoreInteractions(stateManager);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_noneAvailable() {
        final List<BlockNodeConfig> availableNodes = availableNodes();
        availableNodes.clear();

        final boolean isScheduled = connectionManager.selectNewBlockNodeForStreaming();

        assertThat(isScheduled).isFalse();

        verifyNoInteractions(executorService);
        verifyNoInteractions(stateManager);
        verifyNoInteractions(metrics);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_noneAvailableInGoodState() {
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        final List<BlockNodeConfig> availableNodes = availableNodes();
        availableNodes.clear();

        final BlockNodeConfig node1Config = new BlockNodeConfig("localhost", 8080, 1);
        final BlockNodeConnection node1Conn = mock(BlockNodeConnection.class);
        doReturn(ConnectionState.CONNECTING).when(node1Conn).getConnectionState();
        final BlockNodeConfig node2Config = new BlockNodeConfig("localhost", 8081, 2);
        final BlockNodeConnection node2Conn = mock(BlockNodeConnection.class);
        doReturn(ConnectionState.ACTIVE).when(node2Conn).getConnectionState();

        availableNodes.add(node1Config);
        availableNodes.add(node2Config);
        connections.put(node1Config, node1Conn);
        connections.put(node2Config, node2Conn);

        final boolean isScheduled = connectionManager.selectNewBlockNodeForStreaming();

        assertThat(isScheduled).isFalse();

        verifyNoInteractions(executorService);
        verifyNoInteractions(stateManager);
        verifyNoInteractions(metrics);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_higherPriorityThanActive() {
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        final List<BlockNodeConfig> availableNodes = availableNodes();
        final AtomicReference<BlockNodeConnection> activeConnection = activeConnection();

        final BlockNodeConfig node1Config = new BlockNodeConfig("localhost", 8081, 1);
        final BlockNodeConfig node2Config = new BlockNodeConfig("localhost", 8082, 2);
        final BlockNodeConnection node2Conn = mock(BlockNodeConnection.class);
        final BlockNodeConfig node3Config = new BlockNodeConfig("localhost", 8083, 3);

        connections.put(node2Config, node2Conn);
        availableNodes.add(node1Config);
        availableNodes.add(node2Config);
        availableNodes.add(node3Config);
        activeConnection.set(node2Conn);

        final boolean isScheduled = connectionManager.selectNewBlockNodeForStreaming();

        assertThat(isScheduled).isTrue();

        final ArgumentCaptor<BlockNodeConnectionTask> taskCaptor =
                ArgumentCaptor.forClass(BlockNodeConnectionTask.class);

        verify(executorService).schedule(taskCaptor.capture(), eq(0L), eq(TimeUnit.MILLISECONDS));

        final BlockNodeConnectionTask task = taskCaptor.getValue();
        final BlockNodeConnection connection = connectionFromTask(task);
        final BlockNodeConfig nodeConfig = connection.getNodeConfig();

        // verify we are trying to connect to one of the priority 1 nodes
        assertThat(nodeConfig.priority()).isEqualTo(1);
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CONNECTING);

        verifyNoMoreInteractions(executorService);
        verifyNoInteractions(stateManager);
        verifyNoInteractions(metrics);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_lowerPriorityThanActive() {
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        final List<BlockNodeConfig> availableNodes = availableNodes();
        final AtomicReference<BlockNodeConnection> activeConnection = activeConnection();

        final BlockNodeConfig node1Config = new BlockNodeConfig("localhost", 8080, 1);
        final BlockNodeConnection node1Conn = mock(BlockNodeConnection.class);
        doReturn(ConnectionState.PENDING).when(node1Conn).getConnectionState();
        final BlockNodeConfig node2Config = new BlockNodeConfig("localhost", 8081, 2);
        final BlockNodeConnection node2Conn = mock(BlockNodeConnection.class);
        doReturn(ConnectionState.ACTIVE).when(node2Conn).getConnectionState();
        final BlockNodeConfig node3Config = new BlockNodeConfig("localhost", 8082, 3);

        connections.put(node1Config, node1Conn);
        connections.put(node2Config, node2Conn);
        availableNodes.add(node1Config);
        availableNodes.add(node2Config);
        availableNodes.add(node3Config);
        activeConnection.set(node2Conn);

        final boolean isScheduled = connectionManager.selectNewBlockNodeForStreaming();

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
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CONNECTING);

        verifyNoMoreInteractions(executorService);
        verifyNoInteractions(stateManager);
        verifyNoInteractions(metrics);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_samePriority() {
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        final List<BlockNodeConfig> availableNodes = availableNodes();
        final AtomicReference<BlockNodeConnection> activeConnection = activeConnection();

        final BlockNodeConfig node1Config = new BlockNodeConfig("localhost", 8080, 1);
        final BlockNodeConnection node1Conn = mock(BlockNodeConnection.class);
        doReturn(ConnectionState.PENDING).when(node1Conn).getConnectionState();
        final BlockNodeConfig node2Config = new BlockNodeConfig("localhost", 8081, 2);
        final BlockNodeConnection node2Conn = mock(BlockNodeConnection.class);
        doReturn(ConnectionState.ACTIVE).when(node2Conn).getConnectionState();
        final BlockNodeConfig node3Config = new BlockNodeConfig("localhost", 8082, 2);
        final BlockNodeConfig node4Config = new BlockNodeConfig("localhost", 8083, 3);

        connections.put(node1Config, node1Conn);
        connections.put(node2Config, node2Conn);
        availableNodes.add(node1Config);
        availableNodes.add(node2Config);
        availableNodes.add(node3Config);
        availableNodes.add(node4Config);
        activeConnection.set(node2Conn);

        final boolean isScheduled = connectionManager.selectNewBlockNodeForStreaming();

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
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.CONNECTING);

        verifyNoMoreInteractions(executorService);
        verifyNoInteractions(stateManager);
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

        verifyNoInteractions(executorService);
        verifyNoInteractions(stateManager);
        verifyNoInteractions(metrics);
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
        verifyNoInteractions(stateManager);
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
        verifyNoInteractions(stateManager);
        verifyNoInteractions(metrics);
    }

    @Test
    void testUpdateLastVerifiedBlock_connDoesNotExist() {
        final Map<BlockNodeConfig, Long> lastVerifiedBlockPerConnection = lastVerifiedBlockPerConnection();
        lastVerifiedBlockPerConnection.clear(); // ensure nothing exists in the map
        final BlockNodeConfig nodeConfig = new BlockNodeConfig("localhost", 8080, 1);

        connectionManager.updateLastVerifiedBlock(nodeConfig, 100L);

        assertThat(lastVerifiedBlockPerConnection).containsEntry(nodeConfig, 100L);

        verify(stateManager).setLatestAcknowledgedBlock(100L);
        verifyNoInteractions(executorService);
        verifyNoMoreInteractions(stateManager);
        verifyNoInteractions(metrics);
    }

    @Test
    void testUpdateLastVerifiedBlock_connExists_verifiedNewer() {
        final Map<BlockNodeConfig, Long> lastVerifiedBlockPerConnection = lastVerifiedBlockPerConnection();
        lastVerifiedBlockPerConnection.clear();
        final BlockNodeConfig nodeConfig = new BlockNodeConfig("localhost", 8080, 1);
        lastVerifiedBlockPerConnection.put(nodeConfig, 99L);

        // signal a 'newer' block has been verified - newer meaning this block (100) is younger than the older block
        // (99)
        connectionManager.updateLastVerifiedBlock(nodeConfig, 100L);

        assertThat(lastVerifiedBlockPerConnection).containsEntry(nodeConfig, 100L);

        verify(stateManager).setLatestAcknowledgedBlock(100L);
        verifyNoInteractions(executorService);
        verifyNoMoreInteractions(stateManager);
        verifyNoInteractions(metrics);
    }

    @Test
    void testUpdateLastVerifiedBlock_connExists_verifiedOlder() {
        final Map<BlockNodeConfig, Long> lastVerifiedBlockPerConnection = lastVerifiedBlockPerConnection();
        lastVerifiedBlockPerConnection.clear();
        final BlockNodeConfig nodeConfig = new BlockNodeConfig("localhost", 8080, 1);
        lastVerifiedBlockPerConnection.put(nodeConfig, 100L);

        // signal an 'older' block has been verified - older meaning the block number being verified is less than the
        // one already verified
        connectionManager.updateLastVerifiedBlock(nodeConfig, 60L);

        assertThat(lastVerifiedBlockPerConnection).containsEntry(nodeConfig, 100L);

        verify(stateManager).setLatestAcknowledgedBlock(60L);
        verifyNoInteractions(executorService);
        verifyNoMoreInteractions(stateManager);
        verifyNoInteractions(metrics);
    }

    @Test
    void testJumpToBlock() {
        final AtomicLong jumpTarget = jumpTarget();
        jumpTarget.set(-1);

        connectionManager.jumpToBlock(16);

        assertThat(jumpTarget).hasValue(16L);

        verifyNoInteractions(executorService);
        verifyNoInteractions(stateManager);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConnectionTask_managerNotActive() {
        final AtomicBoolean isManagerActive = isActiveFlag();
        isManagerActive.set(false);

        final BlockNodeConnection connection = mock(BlockNodeConnection.class);

        connectionManager.new BlockNodeConnectionTask(connection, Duration.ofSeconds(1), null).run();

        verifyNoInteractions(connection);
        verifyNoInteractions(executorService);
        verifyNoInteractions(stateManager);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConnectionTask_higherPriorityConnectionExists() {
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        final BlockNodeConnection activeConnection = mock(BlockNodeConnection.class);
        final BlockNodeConfig activeConnectionConfig = new BlockNodeConfig("localhost", 8080, 1);
        doReturn(activeConnectionConfig).when(activeConnection).getNodeConfig();
        activeConnectionRef.set(activeConnection);

        final BlockNodeConnection newConnection = mock(BlockNodeConnection.class);
        final BlockNodeConfig newConnectionConfig = new BlockNodeConfig("localhost", 8081, 2);
        doReturn(newConnectionConfig).when(newConnection).getNodeConfig();

        connectionManager.new BlockNodeConnectionTask(newConnection, Duration.ofSeconds(1), null).run();

        assertThat(activeConnectionRef).hasValue(activeConnection);

        verify(activeConnection).getNodeConfig();
        verify(newConnection).getNodeConfig();

        verifyNoMoreInteractions(activeConnection);
        verifyNoMoreInteractions(newConnection);
        verifyNoInteractions(executorService);
        verifyNoInteractions(stateManager);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConnectionTask_connectionUninitialized_withActiveLowerPriorityConnection() {
        // also put an active connection into the state, but let it have a lower priority so the new connection
        // takes its place as the active one
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        final BlockNodeConnection activeConnection = mock(BlockNodeConnection.class);
        final BlockNodeConfig activeConnectionConfig = new BlockNodeConfig("localhost", 8080, 2);
        doReturn(activeConnectionConfig).when(activeConnection).getNodeConfig();
        activeConnectionRef.set(activeConnection);

        final BlockNodeConnection newConnection = mock(BlockNodeConnection.class);
        final BlockNodeConfig newConnectionConfig = new BlockNodeConfig("localhost", 8081, 1);
        doReturn(newConnectionConfig).when(newConnection).getNodeConfig();

        connectionManager.new BlockNodeConnectionTask(newConnection, Duration.ofSeconds(1), 30L).run();

        final AtomicLong jumpTarget = jumpTarget();
        assertThat(jumpTarget).hasValue(30L);
        assertThat(activeConnectionRef).hasValue(newConnection);

        verify(activeConnection).getNodeConfig();
        verify(activeConnection).close();
        verify(newConnection).getNodeConfig();
        verify(newConnection).createRequestObserver();
        verify(newConnection).updateConnectionState(ConnectionState.ACTIVE);

        verifyNoMoreInteractions(activeConnection);
        verifyNoMoreInteractions(newConnection);
        verifyNoInteractions(executorService);
        verifyNoMoreInteractions(stateManager);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConnectionTask_sameConnectionAsActive() {
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        final BlockNodeConnection activeConnection = mock(BlockNodeConnection.class);
        activeConnectionRef.set(activeConnection);

        connectionManager.new BlockNodeConnectionTask(activeConnection, Duration.ofSeconds(1), null).run();

        verifyNoInteractions(activeConnection);
        verifyNoInteractions(executorService);
        verifyNoInteractions(stateManager);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConnectionTask_noActiveConnection() {
        final AtomicLong jumpTarget = jumpTarget();
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        activeConnectionRef.set(null);

        doReturn(10L).when(stateManager).getLowestUnackedBlockNumber();

        final BlockNodeConnection newConnection = mock(BlockNodeConnection.class);

        connectionManager.new BlockNodeConnectionTask(newConnection, Duration.ofSeconds(1), null).run();

        assertThat(jumpTarget).hasValue(10L);
        assertThat(activeConnectionRef).hasValue(newConnection);

        verify(newConnection).createRequestObserver();
        verify(newConnection).updateConnectionState(ConnectionState.ACTIVE);
        verify(stateManager).getLowestUnackedBlockNumber();

        verifyNoMoreInteractions(newConnection);
        verifyNoInteractions(executorService);
        verifyNoMoreInteractions(stateManager);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConnectionTask_closeExistingActiveFailed() {
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        final BlockNodeConnection activeConnection = mock(BlockNodeConnection.class);
        final BlockNodeConfig activeConnectionConfig = new BlockNodeConfig("localhost", 8080, 2);
        doReturn(activeConnectionConfig).when(activeConnection).getNodeConfig();
        doThrow(new RuntimeException("why does this always happen to me"))
                .when(activeConnection)
                .close();
        activeConnectionRef.set(activeConnection);

        final BlockNodeConnection newConnection = mock(BlockNodeConnection.class);
        final BlockNodeConfig newConnectionConfig = new BlockNodeConfig("localhost", 8081, 1);
        doReturn(newConnectionConfig).when(newConnection).getNodeConfig();

        connectionManager.new BlockNodeConnectionTask(newConnection, Duration.ofSeconds(1), 30L).run();

        final AtomicLong jumpTarget = jumpTarget();
        assertThat(jumpTarget).hasValue(30L);
        assertThat(activeConnectionRef).hasValue(newConnection);

        verify(activeConnection).getNodeConfig();
        verify(activeConnection).close();
        verify(newConnection).getNodeConfig();
        verify(newConnection).createRequestObserver();
        verify(newConnection).updateConnectionState(ConnectionState.ACTIVE);

        verifyNoMoreInteractions(activeConnection);
        verifyNoMoreInteractions(newConnection);
        verifyNoInteractions(executorService);
        verifyNoMoreInteractions(stateManager);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConnectionTask_reschedule_delayZero() {
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        activeConnectionRef.set(null);

        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        doThrow(new RuntimeException("are you seeing this?")).when(connection).createRequestObserver();

        final BlockNodeConnectionTask task =
                connectionManager.new BlockNodeConnectionTask(connection, Duration.ZERO, 10L);

        task.run();

        verify(connection).createRequestObserver();
        verify(executorService).schedule(eq(task), anyLong(), eq(TimeUnit.MILLISECONDS));

        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(executorService);
        verifyNoInteractions(stateManager);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConnectionTask_reschedule_delayNonZero() {
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        activeConnectionRef.set(null);

        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        doThrow(new RuntimeException("are you seeing this?")).when(connection).createRequestObserver();

        final BlockNodeConnectionTask task =
                connectionManager.new BlockNodeConnectionTask(connection, Duration.ofSeconds(10), 10L);

        task.run();

        verify(connection).createRequestObserver();
        verify(executorService).schedule(eq(task), anyLong(), eq(TimeUnit.MILLISECONDS));

        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(executorService);
        verifyNoInteractions(stateManager);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConnectionTask_reschedule_failure() {
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        activeConnectionRef.set(null);
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();

        final BlockNodeConfig nodeConfig = new BlockNodeConfig("localhost", 8080, 1);
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        doReturn(nodeConfig).when(connection).getNodeConfig();
        doThrow(new RuntimeException("are you seeing this?")).when(connection).createRequestObserver();
        doThrow(new RuntimeException("welp, this is my life now"))
                .when(executorService)
                .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));

        connections.clear();
        connections.put(nodeConfig, connection);

        final BlockNodeConnectionTask task =
                connectionManager.new BlockNodeConnectionTask(connection, Duration.ofSeconds(10), 10L);

        task.run();

        assertThat(connections).isEmpty(); // connection should be removed

        verify(connection).createRequestObserver();
        verify(executorService).schedule(eq(task), anyLong(), eq(TimeUnit.MILLISECONDS));

        verify(connection).getNodeConfig();
        verify(connection).close();

        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(executorService);
        verifyNoInteractions(stateManager);
        verifyNoInteractions(metrics);
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
        verifyNoInteractions(stateManager);
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
        verifyNoInteractions(stateManager);
        verifyNoInteractions(metrics);
    }

    @Test
    void testProcessBlockStreamQueue_emptyQueue() {
        invoke_processBlockStreamQueue();

        verify(stateManager).getBlockStreamItemQueue();

        verifyNoMoreInteractions(stateManager);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testProcessBlockStreamQueue_unknownBlock() {
        final BlockItem blockItem = newBlockHeaderItem();
        final BlockStreamQueueItem queueItem =
                new BlockStreamQueueItem(100L, BlockStreamQueueItemType.BLOCK_ITEM, blockItem);
        blockStreamItemQueue.add(queueItem);
        doReturn(null).when(stateManager).getBlockState(100L);

        invoke_processBlockStreamQueue();

        verify(stateManager).getBlockStreamItemQueue();
        verify(stateManager).getBlockState(100L);

        verifyNoMoreInteractions(stateManager);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testProcessBlockStreamQueue_preBlockProof() {
        final BlockItem blockItem = newBlockHeaderItem();
        final BlockStreamQueueItem queueItem =
                new BlockStreamQueueItem(100L, BlockStreamQueueItemType.PRE_BLOCK_PROOF_ACTION, blockItem);
        blockStreamItemQueue.add(queueItem);
        final BlockState blockState = new BlockState(100L);
        doReturn(blockState).when(stateManager).getBlockState(100L);

        invoke_processBlockStreamQueue();

        assertThat(blockState.requestsSize()).isZero();
        assertThat(blockState.getRequest(0)).isNull();
        assertThat(blockState.requestsCompleted()).isFalse();

        verify(stateManager).getBlockStreamItemQueue();
        verify(stateManager).getBlockState(100L);

        verifyNoMoreInteractions(stateManager);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testProcessBlockStreamQueue_withItems_notEnoughForBatch() {
        final int numItemsToCreate = BATCH_SIZE - 1;
        for (int i = 0; i < numItemsToCreate; ++i) {
            blockStreamItemQueue.add(
                    new BlockStreamQueueItem(100L, BlockStreamQueueItemType.BLOCK_ITEM, newBlockTxItem()));
        }

        final BlockState blockState = new BlockState(100L);
        doReturn(blockState).when(stateManager).getBlockState(100L);

        invoke_processBlockStreamQueue();

        assertThat(blockState.requestsSize()).isZero();
        assertThat(blockState.getRequest(0)).isNull();
        assertThat(blockState.requestsCompleted()).isFalse();

        verify(stateManager).getBlockStreamItemQueue();
        verify(stateManager, times(numItemsToCreate)).getBlockState(100L);

        verifyNoMoreInteractions(stateManager);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testProcessBlockStreamQueue_withItems_multipleBatches() {
        final int numItemsToCreate = BATCH_SIZE * 2;
        for (int i = 0; i < numItemsToCreate; ++i) {
            blockStreamItemQueue.add(
                    new BlockStreamQueueItem(100L, BlockStreamQueueItemType.BLOCK_ITEM, newBlockTxItem()));
        }

        final BlockState blockState = new BlockState(100L);
        doReturn(blockState).when(stateManager).getBlockState(100L);

        invoke_processBlockStreamQueue();
        // Each invocation will handle up to N items, where N is the batch size
        assertThat(blockState.requestsSize()).isEqualTo(1);
        // So we need to invoke twice to get through multiple batches
        invoke_processBlockStreamQueue();

        assertThat(blockState.requestsSize()).isEqualTo(2);
        final PublishStreamRequest request1 = blockState.getRequest(0);
        final PublishStreamRequest request2 = blockState.getRequest(1);

        assertThat(request1).isNotNull();
        assertThat(request2).isNotNull();
        assertThat(request1.hasBlockItems()).isTrue();
        assertThat(request2.hasBlockItems()).isTrue();
        assertThat(blockState.requestsCompleted()).isFalse();

        verify(stateManager, times(2)).getBlockStreamItemQueue();
        verify(stateManager, times(numItemsToCreate)).getBlockState(100L);

        verifyNoMoreInteractions(stateManager);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testProcessBlockStreamQueue_onlyBlockProof() {
        final BlockItem blockItem = newBlockProofItem();
        final BlockStreamQueueItem queueItem =
                new BlockStreamQueueItem(100L, BlockStreamQueueItemType.BLOCK_ITEM, blockItem);
        blockStreamItemQueue.add(queueItem);
        final BlockState blockState = new BlockState(100L);
        doReturn(blockState).when(stateManager).getBlockState(100L);

        invoke_processBlockStreamQueue();

        assertThat(blockState.requestsSize()).isEqualTo(1);
        assertThat(blockState.getRequest(0)).isNotNull();
        assertThat(blockState.requestsCompleted()).isTrue();

        verify(stateManager).getBlockStreamItemQueue();
        verify(stateManager).getBlockState(100L);

        verifyNoMoreInteractions(stateManager);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testProcessStreamingToBlockNode_noActiveConnection() {
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        activeConnectionRef.set(null);

        final boolean shouldSleep = invoke_processStreamingToBlockNode();

        assertThat(shouldSleep).isTrue();

        verifyNoInteractions(stateManager);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testProcessStreamingToBlockNode_missingBlock_latestBlockAfterCurrentStreaming() {
        final List<BlockNodeConfig> availableNodes = availableNodes();
        availableNodes.clear();
        availableNodes.add(new BlockNodeConfig("localhost", 8080, 1));
        availableNodes.add(new BlockNodeConfig("localhost", 8081, 2));
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        activeConnectionRef.set(connection);
        final AtomicLong currentStreamingBlock = streamingBlockNumber();
        currentStreamingBlock.set(10L);
        doReturn(null).when(stateManager).getBlockState(10L);
        doReturn(11L).when(stateManager).getBlockNumber();

        final boolean shouldSleep = invoke_processStreamingToBlockNode();

        assertThat(shouldSleep).isTrue();

        verify(stateManager).getBlockState(10L);
        verify(stateManager).getBlockNumber();
        // one scheduled task to reconnect the existing connection later
        verify(executorService).schedule(any(BlockNodeConnectionTask.class), eq(30_000L), eq(TimeUnit.MILLISECONDS));
        // another task scheduled to connect to a new node immediately
        verify(executorService).schedule(any(BlockNodeConnectionTask.class), eq(0L), eq(TimeUnit.MILLISECONDS));
        verify(connection).updateConnectionState(ConnectionState.CONNECTING);

        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(stateManager);
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
        doReturn(null).when(stateManager).getBlockState(10L);
        doReturn(10L).when(stateManager).getBlockNumber();

        final boolean shouldSleep = invoke_processStreamingToBlockNode();

        assertThat(shouldSleep).isTrue();

        verify(stateManager).getBlockState(10L);
        verify(stateManager).getBlockNumber();

        verifyNoInteractions(connection);
        verifyNoMoreInteractions(stateManager);
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
        doReturn(blockState).when(stateManager).getBlockState(10L);
        doReturn(10L).when(stateManager).getBlockNumber();

        final boolean shouldSleep = invoke_processStreamingToBlockNode();

        assertThat(shouldSleep).isTrue();

        verify(stateManager).getBlockState(10L);
        verify(stateManager).getBlockNumber();

        verifyNoInteractions(connection);
        verifyNoMoreInteractions(stateManager);
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
        doReturn(req).when(blockState).getRequest(0);
        doReturn(1).when(blockState).requestsSize();
        doReturn(blockState).when(stateManager).getBlockState(10L);
        doReturn(10L).when(stateManager).getBlockNumber();

        final boolean shouldSleep = invoke_processStreamingToBlockNode();
        assertThat(shouldSleep).isTrue(); // there is nothing in the queue left to process, so we should sleep

        verify(stateManager).getBlockState(10L);
        verify(stateManager).getBlockNumber();
        verify(stateManager).getBlockStreamItemQueue();
        verify(connection).sendRequest(req);

        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(stateManager);
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
        doReturn(req).when(blockState).getRequest(0);
        doReturn(1).when(blockState).requestsSize();
        doReturn(true).when(blockState).requestsCompleted();
        doReturn(blockState).when(stateManager).getBlockState(10L);
        doReturn(10L).when(stateManager).getBlockNumber();

        final boolean shouldSleep = invoke_processStreamingToBlockNode();
        assertThat(shouldSleep)
                .isFalse(); // since we are moving blocks, we should not sleep and instead immediately re-check
        assertThat(currentStreamingBlock).hasValue(11L); // this should get incremented as we move to next

        verify(stateManager).getBlockState(10L);
        verify(stateManager).getBlockNumber();
        verify(connection).sendRequest(req);

        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(stateManager);
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
        doReturn(req).when(blockState).getRequest(0);
        doReturn(2).when(blockState).requestsSize();
        doReturn(false).when(blockState).requestsCompleted();
        doReturn(blockState).when(stateManager).getBlockState(10L);
        doReturn(10L).when(stateManager).getBlockNumber();

        final boolean shouldSleep = invoke_processStreamingToBlockNode();
        assertThat(shouldSleep).isFalse(); // there is nothing in the queue left to process, so we should sleep

        verify(stateManager).getBlockState(10L);
        verify(stateManager).getBlockNumber();
        verify(connection).sendRequest(req);

        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(stateManager);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testBlockStreamWorkerLoop_managerNotActive() {
        final AtomicBoolean isActive = isActiveFlag();
        isActive.set(false);

        invoke_blockStreamWorkerLoop();

        verifyNoInteractions(stateManager);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testBlockStreamWorkerLoop() throws InterruptedException {
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        activeConnectionRef.set(connection);
        final AtomicLong currentStreamingBlock = streamingBlockNumber();
        currentStreamingBlock.set(10L);
        final BlockState blockState = mock(BlockState.class);
        final PublishStreamRequest req1 = createRequest(newBlockHeaderItem());
        final PublishStreamRequest req2 = createRequest(newBlockProofItem());
        doReturn(req1).when(blockState).getRequest(0);
        doReturn(req2).when(blockState).getRequest(1);
        doReturn(2).when(blockState).requestsSize();
        doReturn(true).when(blockState).requestsCompleted();
        doReturn(blockState).when(stateManager).getBlockState(10L);
        doReturn(10L).when(stateManager).getBlockNumber();

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

        verify(stateManager, atLeast(2)).getBlockState(10L);
        verify(stateManager, atLeast(2)).getBlockNumber();
        verify(stateManager, atLeast(2)).getBlockStreamItemQueue();
        verify(connection).sendRequest(req1);
        verify(connection).sendRequest(req2);

        verifyNoMoreInteractions(connection);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testBlockStreamWorkerLoop_failure() throws InterruptedException {
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        activeConnectionRef.set(connection);
        final AtomicLong currentStreamingBlock = streamingBlockNumber();
        currentStreamingBlock.set(10L);
        final BlockState blockState = mock(BlockState.class);
        final PublishStreamRequest req1 = createRequest(newBlockHeaderItem());
        final PublishStreamRequest req2 = createRequest(newBlockProofItem());
        doReturn(req1).when(blockState).getRequest(0);
        doReturn(req2).when(blockState).getRequest(1);
        doReturn(2).when(blockState).requestsSize();
        doReturn(true).when(blockState).requestsCompleted();
        doReturn(blockState).when(stateManager).getBlockState(10L);
        doReturn(10L).when(stateManager).getBlockNumber();
        when(stateManager.getBlockStreamItemQueue())
                .thenThrow(new RuntimeException("foobar"))
                .thenReturn(blockStreamItemQueue);

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

        verify(stateManager, atLeast(2)).getBlockState(10L);
        verify(stateManager, atLeast(2)).getBlockNumber();
        // the queue will be accessed 3 times, the first time failing and then two more "normal" times
        verify(stateManager, atLeast(3)).getBlockStreamItemQueue();
        verify(connection).sendRequest(req1);
        verify(connection).sendRequest(req2);

        verifyNoMoreInteractions(connection);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testScheduleAndSelectNewNode_streamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        isStreamingEnabled.set(false);
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);

        connectionManager.rescheduleAndSelectNewNode(connection, Duration.ZERO);

        verifyNoInteractions(connection);
        verifyNoInteractions(stateManager);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testScheduleConnectionAttempt_streamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        isStreamingEnabled.set(false);
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);

        connectionManager.scheduleConnectionAttempt(connection, Duration.ZERO, 10L);

        verifyNoInteractions(connection);
        verifyNoInteractions(stateManager);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testShutdown_streamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        isStreamingEnabled.set(false);

        connectionManager.shutdown();

        verifyNoInteractions(stateManager);
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

        verifyNoInteractions(stateManager);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_streamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        isStreamingEnabled.set(false);

        connectionManager.selectNewBlockNodeForStreaming();

        verifyNoInteractions(stateManager);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testOpenBlock_streamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        isStreamingEnabled.set(false);

        connectionManager.openBlock(10L);

        verifyNoInteractions(stateManager);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testUpdateLastVerifiedBlock_streamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        isStreamingEnabled.set(false);

        connectionManager.updateLastVerifiedBlock(mock(BlockNodeConfig.class), 1L);

        verifyNoInteractions(stateManager);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testJumpToBlock_streamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        isStreamingEnabled.set(false);

        connectionManager.jumpToBlock(100L);

        assertThat(jumpTarget()).hasValue(-1L);

        verifyNoInteractions(stateManager);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConnectionTask_run_streamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        isStreamingEnabled.set(false);
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);

        connectionManager.new BlockNodeConnectionTask(connection, Duration.ZERO, 100L);

        verifyNoInteractions(connection);
        verifyNoInteractions(stateManager);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    // Utilities

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

    private void invoke_processBlockStreamQueue() {
        try {
            processBlockStreamQueueHandle.invoke(connectionManager);
        } catch (final Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private boolean invoke_processStreamingToBlockNode() {
        try {
            return (Boolean) processStreamingToBlockNodeHandle.invoke(connectionManager);
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

    private Map<BlockNodeConfig, Long> lastVerifiedBlockPerConnection() {
        return (Map<BlockNodeConfig, Long>) lastVerifiedBlockPerConnectionHandle.get(connectionManager);
    }

    private AtomicLong streamingBlockNumber() {
        return (AtomicLong) streamingBlockNumberHandle.get(connectionManager);
    }

    private AtomicLong jumpTarget() {
        return (AtomicLong) jumpTargetHandle.get(connectionManager);
    }

    private AtomicReference<BlockNodeConnection> activeConnection() {
        return (AtomicReference<BlockNodeConnection>) activeConnectionRefHandle.get(connectionManager);
    }

    private List<BlockNodeConfig> availableNodes() {
        return (List<BlockNodeConfig>) availableNodesHandle.get(connectionManager);
    }

    private Map<BlockNodeConfig, BlockNodeConnection> connections() {
        return (Map<BlockNodeConfig, BlockNodeConnection>) connectionsHandle.get(connectionManager);
    }

    private AtomicBoolean isActiveFlag() {
        return (AtomicBoolean) isManagerActiveHandle.get(connectionManager);
    }

    private AtomicReference<Thread> workerThread() {
        return (AtomicReference<Thread>) workerThreadRefHandle.get(connectionManager);
    }

    private void disableWorkerThread() {
        logger.info("--- Disabling worker thread -->");
        final AtomicBoolean isActive = isActiveFlag();
        isActive.set(false); // set the flag to false so we can shutdown the worker thread

        // wait for the worker thread to die
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

        isActive.set(true); // set the flag back to true now that the worker thread is dead

        resetMocks();
        logger.info("<-- Worker thread disabled ---");
    }

    private void resetMocks() {
        reset(stateManager, metrics, executorService);
    }
}
