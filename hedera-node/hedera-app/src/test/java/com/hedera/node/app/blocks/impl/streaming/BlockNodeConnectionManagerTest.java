// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.internal.network.BlockNodeConfig;
import com.hedera.node.internal.network.BlockNodeConnectionInfo;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.lang.Thread.State;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
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
import org.junit.jupiter.api.io.TempDir;
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
    private static final VarHandle requestIndexHandle;
    private static final VarHandle sharedExecutorServiceHandle;
    private static final VarHandle configWatcherThreadRefHandle;
    private static final VarHandle configWatchServiceRefHandle;
    private static final VarHandle blockNodeConfigDirectoryHandle;
    private static final MethodHandle jumpToBlockIfNeededHandle;
    private static final MethodHandle processStreamingToBlockNodeHandle;
    private static final MethodHandle blockStreamWorkerLoopHandle;
    private static final MethodHandle stopConnectionsHandle;
    private static final MethodHandle handleConfigFileChangeHandle;
    private static final MethodHandle extractBlockNodesConfigurationsHandle;
    private static final MethodHandle performInitialConfigLoadHandle;
    private static final MethodHandle startConfigWatcherHandle;
    private static final MethodHandle stopConfigWatcherHandle;

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
            requestIndexHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "requestIndex", int.class);
            sharedExecutorServiceHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(
                            BlockNodeConnectionManager.class, "sharedExecutorService", ScheduledExecutorService.class);
            configWatcherThreadRefHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "configWatcherThreadRef", AtomicReference.class);
            configWatchServiceRefHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "configWatchServiceRef", AtomicReference.class);
            blockNodeConfigDirectoryHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "blockNodeConfigDirectory", Path.class);

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

            final Method stopConnections = BlockNodeConnectionManager.class.getDeclaredMethod("stopConnections");
            stopConnections.setAccessible(true);
            stopConnectionsHandle = lookup.unreflect(stopConnections);

            final Method handleConfigFileChange =
                    BlockNodeConnectionManager.class.getDeclaredMethod("handleConfigFileChange");
            handleConfigFileChange.setAccessible(true);
            handleConfigFileChangeHandle = lookup.unreflect(handleConfigFileChange);

            final Method extractBlockNodesConfigurations =
                    BlockNodeConnectionManager.class.getDeclaredMethod("extractBlockNodesConfigurations", String.class);
            extractBlockNodesConfigurations.setAccessible(true);
            extractBlockNodesConfigurationsHandle = lookup.unreflect(extractBlockNodesConfigurations);

            final Method performInitialConfigLoad =
                    BlockNodeConnectionManager.class.getDeclaredMethod("performInitialConfigLoad");
            performInitialConfigLoad.setAccessible(true);
            performInitialConfigLoadHandle = lookup.unreflect(performInitialConfigLoad);

            final Method startConfigWatcher = BlockNodeConnectionManager.class.getDeclaredMethod("startConfigWatcher");
            startConfigWatcher.setAccessible(true);
            startConfigWatcherHandle = lookup.unreflect(startConfigWatcher);

            final Method stopConfigWatcher = BlockNodeConnectionManager.class.getDeclaredMethod("stopConfigWatcher");
            stopConfigWatcher.setAccessible(true);
            stopConfigWatcherHandle = lookup.unreflect(stopConfigWatcher);

        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BlockNodeConnectionManager connectionManager;

    private BlockBufferService bufferService;
    private BlockStreamMetrics metrics;
    private ScheduledExecutorService executorService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void beforeEach() {
        // Use a non-existent directory to prevent loading any existing block-nodes.json during tests
        final ConfigProvider configProvider = createConfigProvider(createDefaultConfigProvider()
                .withValue(
                        "blockNode.blockNodeConnectionFileDir",
                        tempDir.toAbsolutePath().toString()));

        bufferService = mock(BlockBufferService.class);
        metrics = mock(BlockStreamMetrics.class);
        executorService = mock(ScheduledExecutorService.class);

        connectionManager = new BlockNodeConnectionManager(configProvider, bufferService, metrics);

        // Inject mock executor to control scheduling behavior in tests.
        // Tests that call start() will have this overwritten by a real executor.
        sharedExecutorServiceHandle.set(connectionManager, executorService);

        // Clear any nodes that might have been loaded by performInitialConfigLoad()
        final List<BlockNodeConfig> availableNodes = availableNodes();
        availableNodes.clear();

        // Clear any connections that might have been created
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        connections.clear();

        // Clear active connection
        final AtomicReference<BlockNodeConnection> activeConnection = activeConnection();
        activeConnection.set(null);

        // Ensure manager is not active
        final AtomicBoolean isActive = isActiveFlag();
        isActive.set(false);

        // Clear worker thread
        final AtomicReference<Thread> workerThread = workerThread();
        workerThread.set(null);

        // Reset request index
        setRequestIndex(0);

        // Clear jump target
        final AtomicLong jumpTarget = jumpTarget();
        jumpTarget.set(-1);

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

        // Add both nodes to available nodes so selectNewBlockNodeForStreaming can find a different one
        final List<BlockNodeConfig> availableNodes = availableNodes();
        availableNodes.clear();
        availableNodes.add(nodeConfig);
        availableNodes.add(newBlockNodeConfig(8081, 1));

        // Add the connection to the map so it can be removed during reschedule
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        connections.put(nodeConfig, connection);

        connectionManager.rescheduleConnection(connection, delay, null, true);

        // Verify at least 2 schedule calls were made (one for retry, one for new node selection)
        verify(executorService, atLeast(2))
                .schedule(any(BlockNodeConnectionTask.class), anyLong(), eq(TimeUnit.MILLISECONDS));

        // Verify new connections were created (map should have 2 entries - retry + new node)
        assertThat(connections).hasSize(2);
        assertThat(connections).containsKeys(nodeConfig, newBlockNodeConfig(8081, 1));
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

        final TestConfigBuilder configBuilder = createDefaultConfigProvider()
                .withValue("blockNode.blockNodeConnectionFileDir", "/tmp/non-existent-test-dir-" + System.nanoTime())
                .withValue("blockNode.protocolExpBackoffTimeframeReset", "1s");
        final ConfigProvider configProvider = createConfigProvider(configBuilder);

        connectionManager = new BlockNodeConnectionManager(configProvider, bufferService, metrics);
        // Inject the mock executor service to control scheduling in tests
        sharedExecutorServiceHandle.set(connectionManager, executorService);

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

        assertThat(logCaptor.warnLogs())
                .anyMatch(msg -> msg.contains("Failed to schedule connection task for block node."));

        verify(executorService).schedule(any(BlockNodeConnectionTask.class), eq(2_000L), eq(TimeUnit.MILLISECONDS));
        verify(metrics).recordConnectionClosed();
        verify(metrics).recordActiveConnectionIp(-1L);

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

        // calling shutdown again would only potentially shutdown the config watcher
        // and not shutdown the buffer service again
        connectionManager.shutdown();

        verify(node1Conn).close(true);
        verify(node2Conn).close(true);
        verify(node3Conn).close(true);
        verify(dummyWorkerThread).interrupt();
        verify(dummyWorkerThread).join();
        verify(bufferService).shutdown();
        verifyNoMoreInteractions(node1Conn);
        verifyNoMoreInteractions(node2Conn);
        verifyNoMoreInteractions(node3Conn);
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

        // start() creates a real executor, replacing the mock.
        // Verify that a connection was created and scheduled.
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        assertThat(connections).hasSize(1);

        final BlockNodeConnection connection = connections.values().iterator().next();
        final BlockNodeConfig nodeConfig = connection.getNodeConfig();

        // verify we are trying to connect to one of the priority 1 nodes
        assertThat(nodeConfig.priority()).isEqualTo(1);
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.UNINITIALIZED);

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

        verify(executorService, atLeast(1)).schedule(taskCaptor.capture(), eq(0L), eq(TimeUnit.MILLISECONDS));

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

        verify(executorService, atLeast(1)).schedule(taskCaptor.capture(), eq(0L), eq(TimeUnit.MILLISECONDS));

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
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(8080, 1);
        doReturn(nodeConfig).when(connection).getNodeConfig();
        doThrow(new RuntimeException("are you seeing this?")).when(connection).createRequestPipeline();

        // Add the connection to the connections map so it can be rescheduled
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        connections.put(nodeConfig, connection);

        // Ensure the node config is available for rescheduling
        final List<BlockNodeConfig> availableNodes = availableNodes();
        availableNodes.clear();
        availableNodes.add(nodeConfig);

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
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(8080, 1);
        doReturn(nodeConfig).when(connection).getNodeConfig();
        doThrow(new RuntimeException("are you seeing this?")).when(connection).createRequestPipeline();

        // Add the connection to the connections map so it can be rescheduled
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        connections.put(nodeConfig, connection);

        // Ensure the node config is available for rescheduling
        final List<BlockNodeConfig> availableNodes = availableNodes();
        availableNodes.clear();
        availableNodes.add(nodeConfig);

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

        // Ensure the node config is available for rescheduling
        final List<BlockNodeConfig> availableNodes = availableNodes();
        availableNodes.clear();
        availableNodes.add(nodeConfig);

        final BlockNodeConnectionTask task =
                connectionManager.new BlockNodeConnectionTask(connection, Duration.ofSeconds(10), 10L, false);

        task.run();

        assertThat(connections).isEmpty(); // connection should be removed

        verify(connection).createRequestPipeline();
        verify(executorService).schedule(eq(task), anyLong(), eq(TimeUnit.MILLISECONDS));
        verify(connection, atLeast(1)).getNodeConfig();
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
        // Setup: Create a connection with an active state and a block with one request
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final BlockState blockState = mock(BlockState.class);
        final PublishStreamRequest req1 = createRequest(newBlockHeaderItem());
        final CountDownLatch requestSentLatch = new CountDownLatch(1);

        when(connection.getConnectionState()).thenReturn(ConnectionState.ACTIVE);
        when(blockState.getRequest(0)).thenReturn(req1);
        when(blockState.numRequestsCreated()).thenReturn(1);
        when(bufferService.getBlockState(10L)).thenReturn(blockState);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(10L);

        // Signal when the request is sent
        doAnswer(invocation -> {
                    requestSentLatch.countDown();
                    return null;
                })
                .when(connection)
                .sendRequest(req1);

        // Set up the manager state
        activeConnection().set(connection);
        streamingBlockNumber().set(10L);
        setRequestIndex(0);
        isActiveFlag().set(true);

        // Run the worker loop in a separate thread
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

        // Wait for the request to be sent, then stop the loop
        assertThat(requestSentLatch.await(2, TimeUnit.SECONDS)).isTrue();
        isActiveFlag().set(false);

        // Verify the loop completed without errors
        assertThat(doneLatch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(errorRef).hasNullValue();

        // Verify the worker loop processed the block
        verify(bufferService, atLeast(1)).getBlockState(10L);
        verify(connection).sendRequest(req1);
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
    void testConstructor_streamingDisabled() {
        // Create a config provider that disables streaming (writerMode = FILE)
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.writerMode", "FILE")
                .withValue(
                        "blockNode.blockNodeConnectionFileDir",
                        Objects.requireNonNull(BlockNodeCommunicationTestBase.class
                                        .getClassLoader()
                                        .getResource("bootstrap/"))
                                .getPath())
                .getOrCreateConfig();
        final ConfigProvider configProvider = () -> new VersionedConfigImpl(config, 1L);

        final BlockNodeConnectionManager manager =
                new BlockNodeConnectionManager(configProvider, bufferService, metrics);

        final AtomicBoolean isStreamingEnabled = (AtomicBoolean) isStreamingEnabledHandle.get(manager);
        assertThat(isStreamingEnabled).isFalse();

        final List<BlockNodeConfig> availableNodes = (List<BlockNodeConfig>) availableNodesHandle.get(manager);
        assertThat(availableNodes).isEmpty();
    }

    @Test
    void testConstructor_configFileNotFound() {
        // Create a config provider with a non-existent directory to trigger IOException
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.writerMode", "FILE_AND_GRPC")
                .withValue("blockNode.blockNodeConnectionFileDir", "/non/existent/path")
                .withValue("blockNode.blockNodeConnectionFile", "block-nodes.json")
                .getOrCreateConfig();
        final ConfigProvider configProvider = () -> new VersionedConfigImpl(config, 1L);

        final BlockNodeConnectionManager manager =
                new BlockNodeConnectionManager(configProvider, bufferService, metrics);

        // Verify that the manager was created but has no available nodes
        assertThat(manager).isNotNull();
        final List<BlockNodeConfig> availableNodes = availableNodes(manager);
        assertThat(availableNodes).isEmpty();
    }

    @Test
    void testRestartConnection() {
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(8080, 1);
        doReturn(nodeConfig).when(connection).getNodeConfig();

        // Add the connection to the connections map and set it as active
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        connections.put(nodeConfig, connection);
        activeConnectionRef.set(connection);

        // Ensure the node config is available for selection
        final List<BlockNodeConfig> availableNodes = availableNodes();
        availableNodes.clear();
        availableNodes.add(nodeConfig);

        connectionManager.connectionResetsTheStream(connection);

        // Verify the active connection reference was cleared
        assertThat(activeConnectionRef).hasNullValue();
        // Verify a new connection was created and added to the connections map
        assertThat(connections).containsKey(nodeConfig);
        // Verify it's a different connection object (the old one was replaced)
        assertThat(connections.get(nodeConfig)).isNotSameAs(connection);

        // Verify that scheduleConnectionAttempt was called with Duration.ZERO and the block number
        verify(executorService).schedule(any(BlockNodeConnectionTask.class), eq(0L), eq(TimeUnit.MILLISECONDS));
        verifyNoMoreInteractions(connection);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
        verifyNoMoreInteractions(executorService);
    }

    @Test
    void testRescheduleConnection_singleBlockNode() {
        // selectNewBlockNodeForStreaming should NOT be called
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.writerMode", "FILE_AND_GRPC")
                .withValue("blockNode.blockNodeConnectionFileDir", "/tmp/non-existent-test-dir-" + System.nanoTime())
                .getOrCreateConfig();
        final ConfigProvider configProvider = () -> new VersionedConfigImpl(config, 1L);

        final BlockNodeConnectionManager manager =
                new BlockNodeConnectionManager(configProvider, bufferService, metrics);

        sharedExecutorServiceHandle.set(manager, executorService);

        final List<BlockNodeConfig> availableNodes = (List<BlockNodeConfig>) availableNodesHandle.get(manager);
        availableNodes.clear();
        availableNodes.add(newBlockNodeConfig(8080, 1));

        reset(executorService);

        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(8080, 1);
        doReturn(nodeConfig).when(connection).getNodeConfig();

        final Map<BlockNodeConfig, BlockNodeConnection> connections =
                (Map<BlockNodeConfig, BlockNodeConnection>) connectionsHandle.get(manager);
        connections.put(nodeConfig, connection);

        manager.rescheduleConnection(connection, Duration.ofSeconds(5), null, true);

        // Verify exactly 1 schedule call was made (only the retry, no new node selection since there's only one node)
        verify(executorService, times(1))
                .schedule(any(BlockNodeConnectionTask.class), eq(5000L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void testConnectionResetsTheStream() {
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(8080, 1);
        doReturn(nodeConfig).when(connection).getNodeConfig();

        // Add the connection to the connections map and set it as active
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        connections.put(nodeConfig, connection);
        activeConnectionRef.set(connection);

        // Ensure the node config is available for selection
        final List<BlockNodeConfig> availableNodes = availableNodes();
        availableNodes.clear();
        availableNodes.add(nodeConfig);

        connectionManager.connectionResetsTheStream(connection);

        // Verify the active connection reference was cleared
        assertThat(activeConnectionRef).hasNullValue();
        // Verify a new connection was created and added to the connections map
        assertThat(connections).containsKey(nodeConfig);
        // Verify it's a different connection object (the old one was replaced)
        assertThat(connections.get(nodeConfig)).isNotSameAs(connection);

        // Verify that selectNewBlockNodeForStreaming was called
        verify(executorService).schedule(any(BlockNodeConnectionTask.class), eq(0L), eq(TimeUnit.MILLISECONDS));
        verifyNoMoreInteractions(connection);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
        verifyNoMoreInteractions(executorService);
    }

    @Test
    void testConnectionResetsTheStream_streamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        isStreamingEnabled.set(false);
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);

        connectionManager.connectionResetsTheStream(connection);

        verifyNoInteractions(connection);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testShutdown_noWorkerThread() {
        final AtomicBoolean isActive = isActiveFlag();
        final AtomicReference<Thread> workerThreadRef = workerThread();

        BlockNodeConfig config = newBlockNodeConfig(8080, 1);
        availableNodes().add(config);

        connectionManager.start();

        // Ensure there's no worker thread
        workerThreadRef.set(null);

        connectionManager.shutdown();

        assertThat(isActive).isFalse();
        // Verify buffer service shutdown is called even with no worker thread
        verify(bufferService).shutdown();
    }

    @Test
    void testStart_streamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        isStreamingEnabled.set(false);

        connectionManager.start();

        // Verify early return - no interactions with any services
        verifyNoInteractions(bufferService);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);

        // Verify manager remains inactive
        final AtomicBoolean isActive = isActiveFlag();
        assertThat(isActive).isFalse();

        // Verify no worker thread was created
        final AtomicReference<Thread> workerThreadRef = workerThread();
        assertThat(workerThreadRef).hasNullValue();
    }

    @Test
    void testBlockStreamWorkerLoop_uncheckedIOException() throws InterruptedException {
        isActiveFlag().set(true);
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        activeConnectionRef.set(connection);
        final AtomicLong currentStreamingBlock = streamingBlockNumber();
        currentStreamingBlock.set(10L);

        // Mock connection state to be ACTIVE so processStreamingToBlockNode proceeds
        doReturn(ConnectionState.ACTIVE).when(connection).getConnectionState();

        // Make bufferService throw UncheckedIOException
        when(bufferService.getBlockState(10L))
                .thenThrow(new java.io.UncheckedIOException("IO Error", new java.io.IOException("Test IO error")))
                .thenReturn(null); // Return null on subsequent calls to allow loop to exit

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

        // Give the loop time to execute and handle the exception
        Thread.sleep(100);
        isActiveFlag().set(false); // stop the loop

        assertThat(doneLatch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(errorRef).hasNullValue();

        // Verify that handleStreamFailureWithoutOnComplete was called
        verify(connection).handleStreamFailureWithoutOnComplete();
        verify(bufferService, atLeast(1)).getBlockState(10L);

        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testBlockStreamWorkerLoop_uncheckedIOException_noActiveConnection() throws InterruptedException {
        isActiveFlag().set(true);
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        final AtomicLong currentStreamingBlock = streamingBlockNumber();
        currentStreamingBlock.set(10L);

        // Start with an active connection so getBlockState gets called
        activeConnectionRef.set(connection);

        // Mock connection state to be ACTIVE so processStreamingToBlockNode proceeds
        doReturn(ConnectionState.ACTIVE).when(connection).getConnectionState();

        // Make bufferService throw UncheckedIOException on first call
        when(bufferService.getBlockState(10L))
                .thenThrow(new java.io.UncheckedIOException("IO Error", new java.io.IOException("Test IO error")));

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

        // Give the loop time to execute and handle the exception, then clear active connection
        Thread.sleep(50);
        activeConnectionRef.set(null); // Clear active connection before exception handling
        Thread.sleep(50);
        isActiveFlag().set(false); // stop the loop

        assertThat(doneLatch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(errorRef).hasNullValue();

        // Verify that getBlockState was called (which threw the exception)
        verify(bufferService, atLeast(1)).getBlockState(10L);

        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testBlockStreamWorkerLoop_exception_noActiveConnection() throws InterruptedException {
        isActiveFlag().set(true);
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        final AtomicLong currentStreamingBlock = streamingBlockNumber();
        currentStreamingBlock.set(10L);

        // Start with an active connection so getBlockState gets called
        activeConnectionRef.set(connection);

        // Mock connection state to be ACTIVE so processStreamingToBlockNode proceeds
        doReturn(ConnectionState.ACTIVE).when(connection).getConnectionState();

        // Make bufferService throw RuntimeException on first call
        when(bufferService.getBlockState(10L)).thenThrow(new RuntimeException("General error"));

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

        // Give the loop time to execute and handle the exception, then clear active connection
        Thread.sleep(50);
        activeConnectionRef.set(null); // Clear active connection before exception handling
        Thread.sleep(50);
        isActiveFlag().set(false); // stop the loop

        assertThat(doneLatch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(errorRef).hasNullValue();

        // Verify that getBlockState was called (which threw the exception)
        verify(bufferService, atLeast(1)).getBlockState(10L);

        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConnectionTask_runStreamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        isStreamingEnabled.set(false);
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);

        final BlockNodeConnectionTask task =
                connectionManager.new BlockNodeConnectionTask(connection, Duration.ZERO, 100L, false);
        task.run();

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

    @Test
    void testHighLatencyTracking() {
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(8080, 1);
        final Instant ackedTime = Instant.now();

        connectionManager.recordBlockProofSent(nodeConfig, 1L, ackedTime);
        connectionManager.recordBlockAckAndCheckLatency(nodeConfig, 1L, ackedTime.plusMillis(30001));

        verify(metrics).recordAcknowledgementLatency(30001);
        verify(metrics).recordHighLatencyEvent();
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testRecordEndOfStreamAndCheckLimit_streamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        isStreamingEnabled.set(false);
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(8080, 1);

        final boolean limitExceeded = connectionManager.recordEndOfStreamAndCheckLimit(nodeConfig, Instant.now());

        assertThat(limitExceeded).isFalse();
    }

    @Test
    void testRecordEndOfStreamAndCheckLimit_withinLimit() {
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(8080, 1);

        final boolean limitExceeded = connectionManager.recordEndOfStreamAndCheckLimit(nodeConfig, Instant.now());

        assertThat(limitExceeded).isFalse();
    }

    @Test
    void testRecordEndOfStreamAndCheckLimit_exceedsLimit() {
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(8080, 1);

        // Record multiple EndOfStream events to exceed the limit
        // The default maxEndOfStreamsAllowed is 5
        for (int i = 0; i < 5; i++) {
            connectionManager.recordEndOfStreamAndCheckLimit(nodeConfig, Instant.now());
        }
        final boolean limitExceeded = connectionManager.recordEndOfStreamAndCheckLimit(nodeConfig, Instant.now());

        assertThat(limitExceeded).isTrue();
    }

    // Priority based BN selection
    @Test
    void testPriorityBasedSelection_multiplePriority0Nodes_randomSelection() {
        // Setup: Create multiple nodes with priority 0 and some with lower priorities
        final List<BlockNodeConfig> blockNodes = List.of(
                new BlockNodeConfig("node1.example.com", 8080, 0), // Priority 0
                new BlockNodeConfig("node2.example.com", 8080, 0), // Priority 0
                new BlockNodeConfig("node3.example.com", 8080, 0), // Priority 0
                new BlockNodeConfig("node4.example.com", 8080, 0), // Priority 0
                new BlockNodeConfig("node5.example.com", 8080, 0), // Priority 0
                new BlockNodeConfig("node6.example.com", 8080, 0), // Priority 0
                new BlockNodeConfig("node7.example.com", 8080, 0), // Priority 0
                new BlockNodeConfig("node8.example.com", 8080, 0), // Priority 0
                new BlockNodeConfig("node9.example.com", 8080, 0), // Priority 0
                new BlockNodeConfig("node10.example.com", 8080, 0), // Priority 0
                new BlockNodeConfig("node11.example.com", 8080, 1), // Priority 1
                new BlockNodeConfig("node12.example.com", 8080, 2), // Priority 2
                new BlockNodeConfig("node13.example.com", 8080, 2), // Priority 2
                new BlockNodeConfig("node14.example.com", 8080, 3), // Priority 3
                new BlockNodeConfig("node15.example.com", 8080, 3) // Priority 3
                );

        // Track which priority 0 nodes get selected over multiple runs
        final Set<String> selectedNodes = new HashSet<>();

        // Run multiple selections to test randomization
        for (int i = 0; i < 50; i++) {
            // Reset mocks for each iteration
            resetMocks();

            // Configure the manager with these nodes
            final BlockNodeConnectionManager manager = createConnectionManager(blockNodes);

            // Perform selection - should only select from priority 0 nodes
            manager.selectNewBlockNodeForStreaming(true);

            // Capture the scheduled task and verify it's connecting to a priority 0 node
            final ArgumentCaptor<BlockNodeConnectionTask> taskCaptor =
                    ArgumentCaptor.forClass(BlockNodeConnectionTask.class);
            verify(executorService, atLeast(1)).schedule(taskCaptor.capture(), anyLong(), any(TimeUnit.class));

            final BlockNodeConnectionTask task = taskCaptor.getValue();
            final BlockNodeConnection connection = connectionFromTask(task);
            final BlockNodeConfig selectedConfig = connection.getNodeConfig();

            // Verify only priority 0 nodes are selected
            assertThat(selectedConfig.priority()).isEqualTo(0);
            assertThat(selectedConfig.address())
                    .isIn(
                            "node1.example.com",
                            "node2.example.com",
                            "node3.example.com",
                            "node4.example.com",
                            "node5.example.com",
                            "node6.example.com",
                            "node7.example.com",
                            "node8.example.com",
                            "node9.example.com",
                            "node10.example.com");

            // Track which node was selected
            selectedNodes.add(selectedConfig.address());
        }

        // Over 50 runs, we should see at least 2 different priority 0 nodes being selected.
        // This verifies the randomization is working (very unlikely to get same node 50 times).
        // The probability of flakiness is effectively zero - around 10^(-47).
        // Failure of this test means the random selection is not working.
        assertThat(selectedNodes.size()).isGreaterThan(1);
    }

    @Test
    void testPriorityBasedSelection_onlyLowerPriorityNodesAvailable() {
        // Setup: All priority 0 nodes are unavailable, only lower priority nodes available
        final List<BlockNodeConfig> blockNodes = List.of(
                new BlockNodeConfig("node1.example.com", 8080, 1), // Priority 1
                new BlockNodeConfig("node2.example.com", 8080, 2), // Priority 2
                new BlockNodeConfig("node3.example.com", 8080, 3) // Priority 3
                );

        final BlockNodeConnectionManager manager = createConnectionManager(blockNodes);

        // Perform selection
        manager.selectNewBlockNodeForStreaming(true);

        // Verify it selects the highest priority available
        final ArgumentCaptor<BlockNodeConnectionTask> taskCaptor =
                ArgumentCaptor.forClass(BlockNodeConnectionTask.class);
        verify(executorService, atLeast(1)).schedule(taskCaptor.capture(), anyLong(), any(TimeUnit.class));

        final BlockNodeConnectionTask task = taskCaptor.getValue();
        final BlockNodeConnection connection = connectionFromTask(task);
        final BlockNodeConfig selectedConfig = connection.getNodeConfig();

        assertThat(selectedConfig.priority()).isEqualTo(1); // Should select priority 1 (highest available)
    }

    @Test
    void testPriorityBasedSelection_mixedPrioritiesWithSomeUnavailable() {
        // Setup: Mix of priorities where some priority 0 nodes are already connected
        final List<BlockNodeConfig> allBlockNodes = List.of(
                new BlockNodeConfig("node1.example.com", 8080, 0), // Priority 0 - will be unavailable
                new BlockNodeConfig("node2.example.com", 8080, 0), // Priority 0 - available
                new BlockNodeConfig("node3.example.com", 8080, 0), // Priority 0 - available
                new BlockNodeConfig("node4.example.com", 8080, 1), // Priority 1
                new BlockNodeConfig("node5.example.com", 8080, 2) // Priority 2
                );

        final BlockNodeConnectionManager manager = createConnectionManager(allBlockNodes);

        // Simulate that node1 is already connected (unavailable)
        final BlockNodeConfig unavailableNode = allBlockNodes.getFirst();
        final BlockNodeConnection existingConnection = mock(BlockNodeConnection.class);

        // Add the existing connection to make node1 unavailable
        final Map<BlockNodeConfig, BlockNodeConnection> connections = getConnections(manager);
        connections.put(unavailableNode, existingConnection);

        // Perform selection
        manager.selectNewBlockNodeForStreaming(true);

        // Verify it still selects from remaining priority 0 nodes
        final ArgumentCaptor<BlockNodeConnectionTask> taskCaptor =
                ArgumentCaptor.forClass(BlockNodeConnectionTask.class);
        verify(executorService, atLeast(1)).schedule(taskCaptor.capture(), anyLong(), any(TimeUnit.class));

        final BlockNodeConnectionTask task = taskCaptor.getValue();
        final BlockNodeConnection connection = connectionFromTask(task);
        final BlockNodeConfig selectedConfig = connection.getNodeConfig();

        assertThat(selectedConfig.priority()).isEqualTo(0);
        assertThat(selectedConfig.address()).isIn("node2.example.com", "node3.example.com");
        assertThat(selectedConfig.address()).isNotEqualTo("node1.example.com"); // Should not select unavailable node
    }

    @Test
    void testPriorityBasedSelection_allPriority0NodesUnavailable() {
        // Setup: All priority 0 nodes are connected, lower priority nodes available
        final List<BlockNodeConfig> allBlockNodes = List.of(
                new BlockNodeConfig("node1.example.com", 8080, 0), // Priority 0 - unavailable
                new BlockNodeConfig("node2.example.com", 8080, 0), // Priority 0 - unavailable
                new BlockNodeConfig("node3.example.com", 8080, 1), // Priority 1 - available
                new BlockNodeConfig("node4.example.com", 8080, 1), // Priority 1 - available
                new BlockNodeConfig("node5.example.com", 8080, 2) // Priority 2 - available
                );

        final BlockNodeConnectionManager manager = createConnectionManager(allBlockNodes);

        // Make all priority 0 nodes unavailable by adding them to connections
        final Map<BlockNodeConfig, BlockNodeConnection> connections = getConnections(manager);
        for (int i = 0; i < 2; i++) { // First 2 nodes are priority 0
            final BlockNodeConfig unavailableNode = allBlockNodes.get(i);
            final BlockNodeConnection existingConnection = mock(BlockNodeConnection.class);
            connections.put(unavailableNode, existingConnection);
        }

        // Perform selection
        manager.selectNewBlockNodeForStreaming(true);

        // Verify it selects from next highest priority group (priority 1)
        final ArgumentCaptor<BlockNodeConnectionTask> taskCaptor =
                ArgumentCaptor.forClass(BlockNodeConnectionTask.class);
        verify(executorService, atLeast(1)).schedule(taskCaptor.capture(), anyLong(), any(TimeUnit.class));

        final BlockNodeConnectionTask task = taskCaptor.getValue();
        final BlockNodeConnection connection = connectionFromTask(task);
        final BlockNodeConfig selectedConfig = connection.getNodeConfig();

        assertThat(selectedConfig.priority()).isEqualTo(1); // Should fall back to priority 1
        assertThat(selectedConfig.address()).isIn("node3.example.com", "node4.example.com");
    }

    @Test
    void testStopConnections() {
        final BlockNodeConnection conn = mock(BlockNodeConnection.class);
        connections().put(newBlockNodeConfig(8080, 1), conn);

        invoke_stopConnections();

        verify(conn).close(true);
        assertThat(connections()).isEmpty();
    }

    @Test
    void testStopConnections_whenStreamingDisabled() {
        isStreamingEnabled().set(false);
        final BlockNodeConnection conn = mock(BlockNodeConnection.class);
        connections().put(newBlockNodeConfig(8080, 1), conn);

        invoke_stopConnections();

        verifyNoInteractions(conn);
    }

    @Test
    void testHandleConfigFileChange() {
        final BlockNodeConnection conn = mock(BlockNodeConnection.class);
        final BlockNodeConfig oldNode = newBlockNodeConfig(9999, 1);
        connections().put(oldNode, conn);
        availableNodes().add(oldNode);

        invoke_handleConfigFileChange();

        // Verify old connection was closed
        verify(conn).close(true);
    }

    @Test
    void testHandleConfigFileChange_shutsDownExecutorAndReloads_whenValid() throws Exception {
        // Point manager at real bootstrap config directory so reload finds valid JSON
        final var configPath = Objects.requireNonNull(
                        BlockNodeCommunicationTestBase.class.getClassLoader().getResource("bootstrap/"))
                .getPath();
        // Replace the directory used by the manager
        blockNodeConfigDirectoryHandle.set(connectionManager, Path.of(configPath));

        // Populate with a dummy existing connection and a mock executor to be shut down
        final BlockNodeConnection existing = mock(BlockNodeConnection.class);
        connections().put(newBlockNodeConfig(4242, 0), existing);
        final ScheduledExecutorService oldExecutor = mock(ScheduledExecutorService.class);
        sharedExecutorServiceHandle.set(connectionManager, oldExecutor);

        // Ensure manager is initially inactive
        isActiveFlag().set(false);
        workerThread().set(null);

        invoke_handleConfigFileChange();

        // Old connection closed and executor shut down
        verify(existing).close(true);
        verify(oldExecutor).shutdownNow();

        // Available nodes should be reloaded from bootstrap JSON (non-empty)
        assertThat(availableNodes()).isNotEmpty();

        // Manager should have started a worker thread due to valid configs
        assertThat(workerThread().get()).isNotNull();
    }

    @Test
    void testPerformInitialConfigLoad_noFile() {
        final Path tmpDir = tempDir.resolve("perfinit-nofile");
        try {
            Files.createDirectories(tmpDir);
            blockNodeConfigDirectoryHandle.set(connectionManager, tmpDir);
            invoke_performInitialConfigLoad();
            assertThat(availableNodes()).isEmpty();
            assertThat(workerThread().get()).isNull();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            try {
                Files.deleteIfExists(tmpDir);
            } catch (final Exception ignore) {
            }
        }
    }

    @Test
    void testPerformInitialConfigLoad_withValidFile_startsAndLoads() throws Exception {
        List<BlockNodeConfig> configs = new ArrayList<>();
        BlockNodeConfig config = BlockNodeConfig.newBuilder()
                .address("localhost")
                .port(8080)
                .priority(0)
                .build();
        configs.add(config);
        BlockNodeConnectionInfo connectionInfo = new BlockNodeConnectionInfo(configs);
        final String json = BlockNodeConnectionInfo.JSON.toJSON(connectionInfo);
        Files.writeString(
                tempDir.resolve("block-nodes.json"),
                json,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        isActiveFlag().set(false);
        workerThread().set(null);
        invoke_performInitialConfigLoad();
        assertThat(availableNodes()).hasSize(1);
        assertThat(workerThread().get()).isNotNull();
    }

    @Test
    void testStartConfigWatcher_reactsToCreateModifyDelete() throws Exception {
        final Path file = tempDir.resolve("block-nodes.json");
        List<BlockNodeConfig> configs = new ArrayList<>();
        BlockNodeConfig config = BlockNodeConfig.newBuilder()
                .address("localhost")
                .port(8080)
                .priority(0)
                .build();
        configs.add(config);
        BlockNodeConnectionInfo connectionInfo = new BlockNodeConnectionInfo(configs);
        final String valid = BlockNodeConnectionInfo.JSON.toJSON(connectionInfo);
        Files.writeString(
                file, valid, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        awaitCondition(() -> !availableNodes().isEmpty(), 5_000);
        Files.writeString(file, "not json", StandardOpenOption.TRUNCATE_EXISTING);
        awaitCondition(() -> availableNodes().isEmpty(), 5_000);
        Files.writeString(file, valid, StandardOpenOption.TRUNCATE_EXISTING);
        awaitCondition(() -> !availableNodes().isEmpty(), 5_000);
        Files.deleteIfExists(file);
        awaitCondition(() -> availableNodes().isEmpty(), 2_000);
    }

    @Test
    void testStopConnections_withException() {
        final BlockNodeConnection conn = mock(BlockNodeConnection.class);
        doThrow(new RuntimeException("Close failed")).when(conn).close(true);
        connections().put(newBlockNodeConfig(8080, 1), conn);

        // Should not throw - exceptions are caught and logged
        invoke_stopConnections();

        verify(conn).close(true);
        assertThat(connections()).isEmpty();
    }

    @Test
    void testExtractBlockNodesConfigurations_fileNotExists() {
        final List<BlockNodeConfig> configs = invoke_extractBlockNodesConfigurations("/non/existent/path");

        assertThat(configs).isEmpty();
    }

    @Test
    void testExtractBlockNodesConfigurations_invalidJson() {
        // Use a path that exists but doesn't contain valid JSON
        final List<BlockNodeConfig> configs = invoke_extractBlockNodesConfigurations("/tmp");

        // Should return empty list when parse fails
        assertThat(configs).isEmpty();
    }

    @Test
    void testConnectionTask_activeConnectionIsSameConnection() {
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);

        activeConnection().set(connection);

        final BlockNodeConnectionTask task =
                connectionManager.new BlockNodeConnectionTask(connection, Duration.ZERO, null, false);

        task.run();

        // Should return early without creating pipeline
        verify(connection, never()).createRequestPipeline();
    }

    // Utilities

    private BlockNodeConnectionManager createConnectionManager(List<BlockNodeConfig> blockNodes) {
        // Create a custom config provider with the specified block nodes
        final ConfigProvider configProvider = createConfigProvider(createDefaultConfigProvider()
                .withValue("blockNode.blockNodeConnectionFileDir", "/tmp/non-existent-test-dir-" + System.nanoTime()));

        // Create the manager
        final BlockNodeConnectionManager manager =
                new BlockNodeConnectionManager(configProvider, bufferService, metrics);

        // Inject the mock executor service to control scheduling in tests
        sharedExecutorServiceHandle.set(manager, executorService);

        // Set the available nodes using reflection
        try {
            final List<BlockNodeConfig> availableNodes = (List<BlockNodeConfig>) availableNodesHandle.get(manager);
            availableNodes.clear();
            availableNodes.addAll(blockNodes);
        } catch (final Throwable t) {
            throw new RuntimeException("Failed to set available nodes", t);
        }

        return manager;
    }

    private Map<BlockNodeConfig, BlockNodeConnection> getConnections(BlockNodeConnectionManager manager) {
        try {
            return (Map<BlockNodeConfig, BlockNodeConnection>) connectionsHandle.get(manager);
        } catch (final Throwable t) {
            throw new RuntimeException("Failed to get connections", t);
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
    private List<BlockNodeConfig> availableNodes(BlockNodeConnectionManager manager) {
        return (List<BlockNodeConfig>) availableNodesHandle.get(manager);
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

    private void setRequestIndex(int value) {
        requestIndexHandle.set(connectionManager, value);
    }

    private void invoke_stopConnections() {
        try {
            stopConnectionsHandle.invoke(connectionManager);
        } catch (final Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private void invoke_handleConfigFileChange() {
        try {
            handleConfigFileChangeHandle.invoke(connectionManager);
        } catch (final Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<BlockNodeConfig> invoke_extractBlockNodesConfigurations(String path) {
        try {
            return (List<BlockNodeConfig>) extractBlockNodesConfigurationsHandle.invoke(connectionManager, path);
        } catch (final Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private void invoke_performInitialConfigLoad() {
        try {
            performInitialConfigLoadHandle.invoke(connectionManager);
        } catch (final Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private void invoke_startConfigWatcher() {
        try {
            startConfigWatcherHandle.invoke(connectionManager);
        } catch (final Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private void invoke_stopConfigWatcher() {
        try {
            stopConfigWatcherHandle.invoke(connectionManager);
        } catch (final Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private void awaitCondition(final java.util.function.BooleanSupplier condition, final long timeoutMs) {
        final long start = System.currentTimeMillis();
        while (!condition.getAsBoolean() && (System.currentTimeMillis() - start) < timeoutMs) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private void resetMocks() {
        reset(bufferService, metrics, executorService);
    }
}
