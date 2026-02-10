// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.node.app.blocks.impl.streaming.BlockNodeStatus.notReachable;
import static com.hedera.node.app.blocks.impl.streaming.BlockNodeStatus.reachable;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager.BlockNodeConnectionTask;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager.RetrieveBlockNodeStatusTask;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager.RetryState;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.internal.network.BlockNodeConfig;
import com.hedera.node.internal.network.BlockNodeConnectionInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockNodeConnectionManagerTest extends BlockNodeCommunicationTestBase {
    private static final VarHandle isManagerActiveHandle;
    private static final VarHandle connectionsHandle;
    private static final VarHandle availableNodesHandle;
    private static final VarHandle activeConnectionRefHandle;
    private static final VarHandle connectivityTaskConnectionHandle;
    private static final VarHandle nodeStatsHandle;
    private static final VarHandle retryStatesHandle;
    private static final VarHandle sharedExecutorServiceHandle;
    private static final VarHandle blockNodeConfigDirectoryHandle;
    private static final VarHandle configWatcherThreadRef;
    private static final VarHandle nodeStatusTaskConnectionHandle;
    private static final MethodHandle closeAllConnectionsHandle;
    private static final MethodHandle refreshAvailableBlockNodesHandle;
    private static final MethodHandle extractBlockNodesConfigurationsHandle;
    private static final MethodHandle scheduleConnectionAttemptHandle;

    public static final String PBJ_UNIT_TEST_HOST = "pbj-unit-test-host";

    static {
        try {
            final Lookup lookup = MethodHandles.lookup();
            isManagerActiveHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "isConnectionManagerActive", AtomicBoolean.class);
            connectionsHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "connections", Map.class);
            availableNodesHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "availableBlockNodes", List.class);
            activeConnectionRefHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "activeConnectionRef", AtomicReference.class);
            connectivityTaskConnectionHandle = MethodHandles.privateLookupIn(BlockNodeConnectionTask.class, lookup)
                    .findVarHandle(BlockNodeConnectionTask.class, "connection", BlockNodeStreamingConnection.class);
            nodeStatsHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "nodeStats", Map.class);
            retryStatesHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "retryStates", Map.class);
            sharedExecutorServiceHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(
                            BlockNodeConnectionManager.class, "sharedExecutorService", ScheduledExecutorService.class);
            blockNodeConfigDirectoryHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "blockNodeConfigDirectory", Path.class);
            configWatcherThreadRef = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "configWatcherThreadRef", AtomicReference.class);
            nodeStatusTaskConnectionHandle = MethodHandles.privateLookupIn(RetrieveBlockNodeStatusTask.class, lookup)
                    .findVarHandle(
                            RetrieveBlockNodeStatusTask.class, "svcConnection", BlockNodeServiceConnection.class);

            final Method closeAllConnections =
                    BlockNodeConnectionManager.class.getDeclaredMethod("closeAllConnections");
            closeAllConnections.setAccessible(true);
            closeAllConnectionsHandle = lookup.unreflect(closeAllConnections);

            final Method refreshAvailableBlockNodes =
                    BlockNodeConnectionManager.class.getDeclaredMethod("refreshAvailableBlockNodes");
            refreshAvailableBlockNodes.setAccessible(true);
            refreshAvailableBlockNodesHandle = lookup.unreflect(refreshAvailableBlockNodes);

            final Method extractBlockNodesConfigurations =
                    BlockNodeConnectionManager.class.getDeclaredMethod("extractBlockNodesConfigurations", String.class);
            extractBlockNodesConfigurations.setAccessible(true);
            extractBlockNodesConfigurationsHandle = lookup.unreflect(extractBlockNodesConfigurations);

            final Method scheduleConnectionAttempt = BlockNodeConnectionManager.class.getDeclaredMethod(
                    "scheduleConnectionAttempt",
                    BlockNodeConfiguration.class,
                    Duration.class,
                    Long.class,
                    boolean.class);
            scheduleConnectionAttempt.setAccessible(true);
            scheduleConnectionAttemptHandle = lookup.unreflect(scheduleConnectionAttempt);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BlockNodeConnectionManager connectionManager;

    private BlockBufferService bufferService;
    private BlockStreamMetrics metrics;
    private ScheduledExecutorService scheduledExecutor;
    private ExecutorService blockingIoExecutor;
    private Supplier<ExecutorService> blockingIoExecutorSupplier;

    @TempDir
    Path tempDir;

    private void replaceLocalhostWithPbjUnitTestHost() {
        // Tests here don't want to establish real network connections, and so they use the special
        // PBJ_UNIT_TEST_HOST hostname instead of "localhost". The latter comes from the bootstrap configuration
        // (from the block-nodes.json file.) So we replace the bootstrap endpoints here:
        availableNodes().clear();
        availableNodes().add(newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1));
        availableNodes().add(newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 2));
    }

    @BeforeEach
    void beforeEach() {
        // Use a non-existent directory to prevent loading any existing block-nodes.json during tests
        final ConfigProvider configProvider = createConfigProvider(createDefaultConfigProvider()
                .withValue(
                        "blockNode.blockNodeConnectionFileDir",
                        tempDir.toAbsolutePath().toString()));

        bufferService = mock(BlockBufferService.class);
        metrics = mock(BlockStreamMetrics.class);
        scheduledExecutor = mock(ScheduledExecutorService.class);
        blockingIoExecutor = mock(ExecutorService.class);
        blockingIoExecutorSupplier = () -> blockingIoExecutor;
        connectionManager =
                new BlockNodeConnectionManager(configProvider, bufferService, metrics, blockingIoExecutorSupplier);
        replaceLocalhostWithPbjUnitTestHost();

        // Inject mock executor to control scheduling behavior in tests.
        // Tests that call start() will have this overwritten by a real executor.
        sharedExecutorServiceHandle.set(connectionManager, scheduledExecutor);

        // Clear any nodes that might have been loaded
        final List<BlockNodeConfiguration> availableNodes = availableNodes();
        availableNodes.clear();

        // Clear any connections that might have been created
        final Map<BlockNodeConfiguration, BlockNodeStreamingConnection> connections = connections();
        connections.clear();

        // Clear active connection
        final AtomicReference<BlockNodeStreamingConnection> activeConnection = activeConnection();
        activeConnection.set(null);

        // Ensure manager is not active
        final AtomicBoolean isActive = isActiveFlag();
        isActive.set(false);

        resetMocks();
    }

    @Test
    void testRescheduleAndSelectNode() throws Exception {
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        final Duration delay = Duration.ofSeconds(1);
        doReturn(nodeConfig).when(connection).configuration();

        // Add both nodes to available nodes so selectNewBlockNodeForStreaming can find a different one
        final List<BlockNodeConfiguration> availableNodes = availableNodes();
        availableNodes.clear();
        availableNodes.add(nodeConfig);
        availableNodes.add(newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 1));

        // Add the connection to the map so it can be removed during reschedule
        final Map<BlockNodeConfiguration, BlockNodeStreamingConnection> connections = connections();
        connections.put(nodeConfig, connection);

        doReturn(100L).when(bufferService).getEarliestAvailableBlockNumber();
        doReturn(200L).when(bufferService).getLastBlockNumberProduced();
        doAnswer(invocation -> {
                    final List<RetrieveBlockNodeStatusTask> tasks = invocation.getArgument(0);
                    final List<CompletableFuture<BlockNodeStatus>> futures = new ArrayList<>();
                    for (int i = 0; i < tasks.size(); ++i) {
                        futures.add(completedFuture(reachable(10, 99)));
                    }

                    return futures;
                })
                .when(blockingIoExecutor)
                .invokeAll(anyList(), anyLong(), any(TimeUnit.class));

        connectionManager.rescheduleConnection(connection, delay, null, true);

        // Verify at least 2 schedule calls were made (one for retry, one for new node selection)
        verify(scheduledExecutor, atLeast(2))
                .schedule(any(BlockNodeConnectionTask.class), anyLong(), eq(TimeUnit.MILLISECONDS));

        // Verify new connections were created (map should have 2 entries - retry + new node)
        assertThat(connections).hasSize(2);
        assertThat(connections).containsKeys(nodeConfig, newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 1));
    }

    @Test
    void rescheduleConnectionAndExponentialBackoff() {
        final Map<BlockNodeConfiguration, RetryState> retryStates = retryStates();
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        doReturn(nodeConfig).when(connection).configuration();

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
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        doReturn(nodeConfig).when(connection).configuration();

        final TestConfigBuilder configBuilder = createDefaultConfigProvider()
                .withValue("blockNode.blockNodeConnectionFileDir", "/tmp/non-existent-test-dir-" + System.nanoTime())
                .withValue("blockNode.protocolExpBackoffTimeframeReset", "1s");
        final ConfigProvider configProvider = createConfigProvider(configBuilder);

        connectionManager =
                new BlockNodeConnectionManager(configProvider, bufferService, metrics, blockingIoExecutorSupplier);
        replaceLocalhostWithPbjUnitTestHost();
        // Inject the mock executor service to control scheduling in tests
        sharedExecutorServiceHandle.set(connectionManager, scheduledExecutor);

        doReturn(100L).when(bufferService).getEarliestAvailableBlockNumber();
        doReturn(200L).when(bufferService).getLastBlockNumberProduced();
        doReturn(List.of(completedFuture(reachable(10, 99))))
                .when(blockingIoExecutor)
                .invokeAll(anyList(), anyLong(), any(TimeUnit.class));

        connectionManager.rescheduleConnection(connection, Duration.ZERO, null, true);
        Thread.sleep(1_000L); // sleep to ensure the backoff timeframe has passed
        connectionManager.rescheduleConnection(connection, Duration.ZERO, null, true);

        final Map<BlockNodeConfiguration, RetryState> retryStates = retryStates();
        assertThat(retryStates).hasSize(1);
        assertThat(retryStates.get(nodeConfig).getRetryAttempt()).isEqualTo(1);

        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getLastBlockNumberProduced();
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(metrics);
        verifyNoMoreInteractions(connection);
    }

    @Test
    void testScheduleConnectionAttempt_streamingDisabledReturnsEarly() {
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        useStreamingDisabledManager();
        try {
            scheduleConnectionAttemptHandle.invoke(connectionManager, nodeConfig, Duration.ZERO, null, false);
        } catch (final Throwable ignored) {
            // Intentionally ignored — safe to continue
        }

        // Ensure nothing was scheduled or stored due to early return
        verifyNoInteractions(scheduledExecutor);
        assertThat(connections()).doesNotContainKey(nodeConfig);

        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testShutdown() {
        final Map<BlockNodeConfiguration, BlockNodeStreamingConnection> connections = connections();
        // add some fake connections
        final BlockNodeConfiguration node1Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        final BlockNodeStreamingConnection node1Conn = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration node2Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 2);
        final BlockNodeStreamingConnection node2Conn = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration node3Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8082, 3);
        final BlockNodeStreamingConnection node3Conn = mock(BlockNodeStreamingConnection.class);
        connections.put(node1Config, node1Conn);
        connections.put(node2Config, node2Conn);
        connections.put(node3Config, node3Conn);

        // introduce a failure on one of the connection closes to ensure the shutdown process does not fail prematurely
        doThrow(new RuntimeException("oops, I did it again")).when(node2Conn).close(true);

        final AtomicBoolean isActive = isActiveFlag();
        isActive.set(true);

        connectionManager.shutdown();

        final AtomicReference<BlockNodeStreamingConnection> activeConnRef = activeConnection();
        assertThat(activeConnRef).hasNullValue();

        assertThat(connections).isEmpty();
        assertThat(isActive).isFalse();

        final Map<BlockNodeConfig, BlockNodeStats> nodeStats = nodeStats();
        assertThat(nodeStats).isEmpty();

        // calling shutdown again would only potentially shutdown the config watcher
        // and not shutdown the buffer service again
        connectionManager.shutdown();

        verify(node1Conn).close();
        verify(node2Conn).close();
        verify(node3Conn).close();
        verify(bufferService).shutdown();
        verify(blockingIoExecutor).shutdownNow();
        verifyNoMoreInteractions(node1Conn);
        verifyNoMoreInteractions(node2Conn);
        verifyNoMoreInteractions(node3Conn);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testStartup_alreadyActive() {
        final AtomicBoolean isActive = isActiveFlag();
        isActive.set(true);

        connectionManager.start();

        verifyNoInteractions(scheduledExecutor);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testStartup_noNodesAvailable() {
        final AtomicBoolean isActive = isActiveFlag();
        isActive.set(false);

        final List<BlockNodeConfiguration> availableNodes = availableNodes();
        availableNodes.clear(); // remove all available nodes from config

        assertThat(isActive).isFalse();

        verifyNoInteractions(scheduledExecutor);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testStartup() throws Exception {
        final AtomicBoolean isActive = isActiveFlag();
        isActive.set(false);

        final Path file = tempDir.resolve("block-nodes.json");
        final List<BlockNodeConfig> availableNodes = new ArrayList<>();
        availableNodes.add(BlockNodeConfig.newBuilder()
                .address(PBJ_UNIT_TEST_HOST)
                .streamingPort(8080)
                .servicePort(8081)
                .priority(1)
                .build());
        availableNodes.add(BlockNodeConfig.newBuilder()
                .address(PBJ_UNIT_TEST_HOST)
                .streamingPort(8180)
                .servicePort(8181)
                .priority(1)
                .build());
        availableNodes.add(BlockNodeConfig.newBuilder()
                .address(PBJ_UNIT_TEST_HOST)
                .streamingPort(8280)
                .servicePort(8281)
                .priority(2)
                .build());
        availableNodes.add(BlockNodeConfig.newBuilder()
                .address(PBJ_UNIT_TEST_HOST)
                .streamingPort(8380)
                .servicePort(8381)
                .priority(3)
                .build());
        availableNodes.add(BlockNodeConfig.newBuilder()
                .address(PBJ_UNIT_TEST_HOST)
                .streamingPort(8480)
                .servicePort(8481)
                .priority(3)
                .build());
        final BlockNodeConnectionInfo connectionInfo = new BlockNodeConnectionInfo(availableNodes);
        final String valid = BlockNodeConnectionInfo.JSON.toJSON(connectionInfo);
        Files.writeString(
                file, valid, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        doReturn(100L).when(bufferService).getEarliestAvailableBlockNumber();
        doReturn(200L).when(bufferService).getLastBlockNumberProduced();
        doAnswer(invocation -> {
                    final List<RetrieveBlockNodeStatusTask> tasks = invocation.getArgument(0);
                    final List<CompletableFuture<BlockNodeStatus>> futures = new ArrayList<>();
                    for (int i = 0; i < tasks.size(); ++i) {
                        // mark all nodes as reachable but with -1 as the latest block
                        futures.add(completedFuture(reachable(10, 99)));
                    }

                    return futures;
                })
                .when(blockingIoExecutor)
                .invokeAll(anyList(), anyLong(), any(TimeUnit.class));

        // start() creates a real executor, replacing the mock.
        connectionManager.start();

        // Immediately stop the config watcher to prevent it from detecting the file write
        // and triggering additional refreshAvailableBlockNodes() calls that would race with
        // our assertions below.
        stopConfigWatcher();

        // Immediately shutdown the real executor to prevent background tasks from running
        // and potentially adding more connections to the map.
        shutdownSharedExecutor();

        // start() creates a real executor that schedules a connection task with 0 delay.
        // Due to the race between the scheduled task and our shutdown, the connections map
        // may contain 1 or more connections. The key invariant is that at least one
        // connection was created and it should be for a priority 1 node.
        final Map<BlockNodeConfiguration, BlockNodeStreamingConnection> connections = connections();
        assertThat(connections).isNotEmpty();

        // Verify that at least one connection is for a priority 1 node and is still UNINITIALIZED
        final BlockNodeStreamingConnection priority1Connection = connections.values().stream()
                .filter(conn -> conn.configuration().priority() == 1)
                .findFirst()
                .orElse(null);
        assertThat(priority1Connection)
                .as("Expected at least one connection to a priority 1 node")
                .isNotNull();
        assertThat(priority1Connection.currentState()).isEqualTo(ConnectionState.UNINITIALIZED);

        // We don't verify metrics here because the real ScheduledExecutorService
        // may run the BlockNodeConnectionTask in the background, which can interact with metrics.
    }

    @Test
    void testSelectNewBlockNodeForStreaming_noneAvailable() {
        final List<BlockNodeConfiguration> availableNodes = availableNodes();
        availableNodes.clear();

        final boolean isScheduled = connectionManager.selectNewBlockNodeForStreaming(false);

        assertThat(isScheduled).isFalse();

        verifyNoInteractions(scheduledExecutor);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_noneAvailableInGoodState() {
        final Map<BlockNodeConfiguration, BlockNodeStreamingConnection> connections = connections();
        final List<BlockNodeConfiguration> availableNodes = availableNodes();
        availableNodes.clear();

        final BlockNodeConfiguration node1Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        final BlockNodeStreamingConnection node1Conn = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration node2Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 2);
        final BlockNodeStreamingConnection node2Conn = mock(BlockNodeStreamingConnection.class);

        availableNodes.add(node1Config);
        availableNodes.add(node2Config);
        connections.put(node1Config, node1Conn);
        connections.put(node2Config, node2Conn);

        final boolean isScheduled = connectionManager.selectNewBlockNodeForStreaming(false);

        assertThat(isScheduled).isFalse();

        verifyNoInteractions(scheduledExecutor);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_higherPriorityThanActive() throws Exception {
        final Map<BlockNodeConfiguration, BlockNodeStreamingConnection> connections = connections();
        final List<BlockNodeConfiguration> availableNodes = availableNodes();
        final AtomicReference<BlockNodeStreamingConnection> activeConnection = activeConnection();

        final BlockNodeConfiguration node1Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 1);
        final BlockNodeConfiguration node2Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8082, 2);
        final BlockNodeStreamingConnection node2Conn = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration node3Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8083, 3);

        connections.put(node2Config, node2Conn);
        availableNodes.add(node1Config);
        availableNodes.add(node2Config);
        availableNodes.add(node3Config);
        activeConnection.set(node2Conn);

        doReturn(100L).when(bufferService).getEarliestAvailableBlockNumber();
        doReturn(200L).when(bufferService).getLastBlockNumberProduced();
        doAnswer(invocation -> {
                    final List<RetrieveBlockNodeStatusTask> tasks = invocation.getArgument(0);
                    final List<CompletableFuture<BlockNodeStatus>> futures = new ArrayList<>();
                    for (int i = 0; i < tasks.size(); ++i) {
                        futures.add(completedFuture(reachable(10, 99)));
                    }

                    return futures;
                })
                .when(blockingIoExecutor)
                .invokeAll(anyList(), anyLong(), any(TimeUnit.class));

        final boolean isScheduled = connectionManager.selectNewBlockNodeForStreaming(false);

        assertThat(isScheduled).isTrue();

        final ArgumentCaptor<BlockNodeConnectionTask> taskCaptor =
                ArgumentCaptor.forClass(BlockNodeConnectionTask.class);

        verify(scheduledExecutor).schedule(taskCaptor.capture(), eq(0L), eq(TimeUnit.MILLISECONDS));

        final BlockNodeConnectionTask task = taskCaptor.getValue();
        final BlockNodeStreamingConnection connection = connectionFromTask(task);
        final BlockNodeConfiguration nodeConfig = connection.configuration();

        // verify we are trying to connect to one of the priority 1 nodes
        assertThat(nodeConfig.priority()).isEqualTo(1);
        assertThat(connection.currentState()).isEqualTo(ConnectionState.UNINITIALIZED);

        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getLastBlockNumberProduced();
        verifyNoMoreInteractions(scheduledExecutor);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_lowerPriorityThanActive() throws Exception {
        final Map<BlockNodeConfiguration, BlockNodeStreamingConnection> connections = connections();
        final List<BlockNodeConfiguration> availableNodes = availableNodes();
        final AtomicReference<BlockNodeStreamingConnection> activeConnection = activeConnection();

        final BlockNodeConfiguration node1Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        final BlockNodeStreamingConnection node1Conn = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration node2Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 2);
        final BlockNodeStreamingConnection node2Conn = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration node3Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8082, 3);

        connections.put(node1Config, node1Conn);
        connections.put(node2Config, node2Conn);
        availableNodes.add(node1Config);
        availableNodes.add(node2Config);
        availableNodes.add(node3Config);
        activeConnection.set(node2Conn);

        doReturn(100L).when(bufferService).getEarliestAvailableBlockNumber();
        doReturn(200L).when(bufferService).getLastBlockNumberProduced();
        doAnswer(invocation -> {
                    final List<RetrieveBlockNodeStatusTask> tasks = invocation.getArgument(0);
                    final List<CompletableFuture<BlockNodeStatus>> futures = new ArrayList<>();
                    for (int i = 0; i < tasks.size(); ++i) {
                        futures.add(completedFuture(reachable(10, 99)));
                    }

                    return futures;
                })
                .when(blockingIoExecutor)
                .invokeAll(anyList(), anyLong(), any(TimeUnit.class));

        final boolean isScheduled = connectionManager.selectNewBlockNodeForStreaming(false);

        assertThat(isScheduled).isTrue();

        final ArgumentCaptor<BlockNodeConnectionTask> taskCaptor =
                ArgumentCaptor.forClass(BlockNodeConnectionTask.class);

        verify(scheduledExecutor, atLeast(1)).schedule(taskCaptor.capture(), eq(0L), eq(TimeUnit.MILLISECONDS));

        final BlockNodeConnectionTask task = taskCaptor.getValue();
        final BlockNodeStreamingConnection connection = connectionFromTask(task);
        final BlockNodeConfiguration nodeConfig = connection.configuration();

        // verify we are trying to connect to one of the priority 1 nodes
        assertThat(nodeConfig.priority()).isEqualTo(3);
        assertThat(nodeConfig.streamingPort()).isEqualTo(8082);
        assertThat(connection.currentState()).isEqualTo(ConnectionState.UNINITIALIZED);

        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getLastBlockNumberProduced();
        verifyNoMoreInteractions(scheduledExecutor);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_samePriority() throws Exception {
        final Map<BlockNodeConfiguration, BlockNodeStreamingConnection> connections = connections();
        final List<BlockNodeConfiguration> availableNodes = availableNodes();
        final AtomicReference<BlockNodeStreamingConnection> activeConnection = activeConnection();

        final BlockNodeConfiguration node1Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        final BlockNodeStreamingConnection node1Conn = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration node2Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 2);
        final BlockNodeStreamingConnection node2Conn = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration node3Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8082, 2);
        final BlockNodeConfiguration node4Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8083, 3);

        connections.put(node1Config, node1Conn);
        connections.put(node2Config, node2Conn);
        availableNodes.add(node1Config);
        availableNodes.add(node2Config);
        availableNodes.add(node3Config);
        availableNodes.add(node4Config);
        activeConnection.set(node2Conn);

        doReturn(100L).when(bufferService).getEarliestAvailableBlockNumber();
        doReturn(200L).when(bufferService).getLastBlockNumberProduced();
        doAnswer(invocation -> {
                    final List<RetrieveBlockNodeStatusTask> tasks = invocation.getArgument(0);
                    final List<CompletableFuture<BlockNodeStatus>> futures = new ArrayList<>();
                    for (int i = 0; i < tasks.size(); ++i) {
                        futures.add(completedFuture(reachable(10, 99)));
                    }

                    return futures;
                })
                .when(blockingIoExecutor)
                .invokeAll(anyList(), anyLong(), any(TimeUnit.class));

        final boolean isScheduled = connectionManager.selectNewBlockNodeForStreaming(false);

        assertThat(isScheduled).isTrue();

        final ArgumentCaptor<BlockNodeConnectionTask> taskCaptor =
                ArgumentCaptor.forClass(BlockNodeConnectionTask.class);

        verify(scheduledExecutor, atLeast(1)).schedule(taskCaptor.capture(), eq(0L), eq(TimeUnit.MILLISECONDS));

        final BlockNodeConnectionTask task = taskCaptor.getValue();
        final BlockNodeStreamingConnection connection = connectionFromTask(task);
        final BlockNodeConfiguration nodeConfig = connection.configuration();

        // verify we are trying to connect to one of the priority 1 nodes
        assertThat(nodeConfig.priority()).isEqualTo(2);
        assertThat(nodeConfig.streamingPort()).isEqualTo(8082);
        assertThat(connection.currentState()).isEqualTo(ConnectionState.UNINITIALIZED);

        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getLastBlockNumberProduced();
        verifyNoMoreInteractions(scheduledExecutor);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConnectionTask_managerNotActive() {
        final AtomicBoolean isManagerActive = isActiveFlag();
        isManagerActive.set(false);

        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        connectionManager.new BlockNodeConnectionTask(connection, Duration.ofSeconds(1), false).run();

        verifyNoInteractions(connection);
        verifyNoInteractions(scheduledExecutor);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConnectionTask_higherPriorityConnectionExists_withoutForce() {
        isActiveFlag().set(true);
        final AtomicReference<BlockNodeStreamingConnection> activeConnectionRef = activeConnection();
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration activeConnectionConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        doReturn(activeConnectionConfig).when(activeConnection).configuration();
        activeConnectionRef.set(activeConnection);

        final BlockNodeStreamingConnection newConnection = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration newConnectionConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 2);
        doReturn(newConnectionConfig).when(newConnection).configuration();

        connectionManager.new BlockNodeConnectionTask(newConnection, Duration.ofSeconds(1), false).run();

        assertThat(activeConnectionRef).hasValue(activeConnection);

        verify(activeConnection).configuration();
        verify(newConnection).configuration();
        verify(newConnection).close(false);

        verifyNoMoreInteractions(activeConnection);
        verifyNoMoreInteractions(newConnection);
        verifyNoInteractions(scheduledExecutor);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConnectionTask_higherPriorityConnectionExists_withForce() {
        isActiveFlag().set(true);

        final AtomicReference<BlockNodeStreamingConnection> activeConnectionRef = activeConnection();
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration activeConnectionConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        doReturn(activeConnectionConfig).when(activeConnection).configuration();
        activeConnectionRef.set(activeConnection);

        final BlockNodeStreamingConnection newConnection = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration newConnectionConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 2);
        doReturn(newConnectionConfig).when(newConnection).configuration();

        connectionManager.new BlockNodeConnectionTask(newConnection, Duration.ofSeconds(1), true).run();

        assertThat(activeConnectionRef).hasValue(newConnection);

        verify(activeConnection, times(2)).configuration();
        verify(activeConnection).closeAtBlockBoundary();
        verify(newConnection, times(2)).configuration();
        verify(newConnection).initialize();
        verify(newConnection).updateConnectionState(ConnectionState.ACTIVE);
        verify(metrics).recordActiveConnectionIp(anyLong());

        verifyNoMoreInteractions(newConnection);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testConnectionTask_connectionUninitialized_withActiveLowerPriorityConnection() {
        // also put an active connection into the state, but let it have a lower priority so the new connection
        // takes its place as the active one
        final AtomicReference<BlockNodeStreamingConnection> activeConnectionRef = activeConnection();
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration activeConnectionConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 2);
        doReturn(activeConnectionConfig).when(activeConnection).configuration();
        activeConnectionRef.set(activeConnection);
        isActiveFlag().set(true);

        final BlockNodeStreamingConnection newConnection = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration newConnectionConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 1);
        doReturn(newConnectionConfig).when(newConnection).configuration();

        connectionManager.new BlockNodeConnectionTask(newConnection, Duration.ofSeconds(1), false).run();

        assertThat(activeConnectionRef).hasValue(newConnection);

        verify(activeConnection).configuration();
        verify(activeConnection).closeAtBlockBoundary();
        verify(newConnection, times(2)).configuration();
        verify(newConnection).initialize();
        verify(newConnection).updateConnectionState(ConnectionState.ACTIVE);
        verify(metrics).recordActiveConnectionIp(anyLong());

        verifyNoMoreInteractions(activeConnection);
        verifyNoMoreInteractions(newConnection);
        verifyNoInteractions(scheduledExecutor);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testConnectionTask_sameConnectionAsActive() {
        final AtomicReference<BlockNodeStreamingConnection> activeConnectionRef = activeConnection();
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        activeConnectionRef.set(activeConnection);

        connectionManager.new BlockNodeConnectionTask(activeConnection, Duration.ofSeconds(1), false).run();

        verifyNoInteractions(activeConnection);
        verifyNoInteractions(scheduledExecutor);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConnectionTask_noActiveConnection() {
        isActiveFlag().set(true);
        final AtomicReference<BlockNodeStreamingConnection> activeConnectionRef = activeConnection();
        activeConnectionRef.set(null);

        final BlockNodeConfiguration newConnectionConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 1);
        final BlockNodeStreamingConnection newConnection = mock(BlockNodeStreamingConnection.class);
        doReturn(newConnectionConfig).when(newConnection).configuration();

        connectionManager.new BlockNodeConnectionTask(newConnection, Duration.ofSeconds(1), false).run();

        assertThat(activeConnectionRef).hasValue(newConnection);

        verify(newConnection).initialize();
        verify(newConnection).updateConnectionState(ConnectionState.ACTIVE);
        verify(metrics).recordActiveConnectionIp(anyLong());

        verifyNoMoreInteractions(newConnection);
        verifyNoInteractions(scheduledExecutor);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testConnectionTask_closeExistingActiveFailed() {
        isActiveFlag().set(true);
        final AtomicReference<BlockNodeStreamingConnection> activeConnectionRef = activeConnection();
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration activeConnectionConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 2);
        doReturn(activeConnectionConfig).when(activeConnection).configuration();
        doThrow(new RuntimeException("why does this always happen to me"))
                .when(activeConnection)
                .closeAtBlockBoundary();
        activeConnectionRef.set(activeConnection);

        final BlockNodeStreamingConnection newConnection = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration newConnectionConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 1);
        doReturn(newConnectionConfig).when(newConnection).configuration();

        connectionManager.new BlockNodeConnectionTask(newConnection, Duration.ofSeconds(1), false).run();

        assertThat(activeConnectionRef).hasValue(newConnection);

        verify(activeConnection).configuration();
        verify(activeConnection).closeAtBlockBoundary();
        verify(newConnection, times(2)).configuration();
        verify(newConnection).initialize();
        verify(newConnection).updateConnectionState(ConnectionState.ACTIVE);
        verify(metrics).recordActiveConnectionIp(anyLong());

        verifyNoMoreInteractions(activeConnection);
        verifyNoMoreInteractions(newConnection);
        verifyNoInteractions(scheduledExecutor);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testConnectionTask_reschedule_delayZero() {
        isActiveFlag().set(true);
        final AtomicReference<BlockNodeStreamingConnection> activeConnectionRef = activeConnection();
        activeConnectionRef.set(null);

        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        doReturn(nodeConfig).when(connection).configuration();
        doThrow(new RuntimeException("are you seeing this?")).when(connection).initialize();

        // Add the connection to the connections map so it can be rescheduled
        final Map<BlockNodeConfiguration, BlockNodeStreamingConnection> connections = connections();
        connections.put(nodeConfig, connection);

        // Ensure the node config is available for rescheduling
        final List<BlockNodeConfiguration> availableNodes = availableNodes();
        availableNodes.clear();
        availableNodes.add(nodeConfig);

        final BlockNodeConnectionTask task =
                connectionManager.new BlockNodeConnectionTask(connection, Duration.ZERO, false);

        task.run();

        verify(connection).initialize();
        verify(scheduledExecutor).schedule(eq(task), anyLong(), eq(TimeUnit.MILLISECONDS));
        verify(metrics).recordConnectionCreateFailure();

        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(scheduledExecutor);
        verifyNoInteractions(bufferService);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testConnectionTask_reschedule_delayNonZero() {
        isActiveFlag().set(true);
        final AtomicReference<BlockNodeStreamingConnection> activeConnectionRef = activeConnection();
        activeConnectionRef.set(null);

        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        doReturn(nodeConfig).when(connection).configuration();

        doThrow(new RuntimeException("are you seeing this?")).when(connection).initialize();

        // Add the connection to the connections map so it can be rescheduled
        final Map<BlockNodeConfiguration, BlockNodeStreamingConnection> connections = connections();
        connections.put(nodeConfig, connection);

        // Ensure the node config is available for rescheduling
        final List<BlockNodeConfiguration> availableNodes = availableNodes();
        availableNodes.clear();
        availableNodes.add(nodeConfig);

        final BlockNodeConnectionTask task =
                connectionManager.new BlockNodeConnectionTask(connection, Duration.ofSeconds(10), false);

        task.run();

        verify(connection).initialize();
        verify(scheduledExecutor).schedule(eq(task), anyLong(), eq(TimeUnit.MILLISECONDS));
        verify(metrics).recordConnectionCreateFailure();
        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(scheduledExecutor);
        verifyNoInteractions(bufferService);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testConnectionTask_reschedule_failure() {
        isActiveFlag().set(true);
        final AtomicReference<BlockNodeStreamingConnection> activeConnectionRef = activeConnection();
        activeConnectionRef.set(null);
        final Map<BlockNodeConfiguration, BlockNodeStreamingConnection> connections = connections();

        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        doReturn(nodeConfig).when(connection).configuration();
        doThrow(new RuntimeException("are you seeing this?")).when(connection).initialize();
        doThrow(new RuntimeException("welp, this is my life now"))
                .when(scheduledExecutor)
                .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));

        connections.clear();
        connections.put(nodeConfig, connection);

        // Ensure the node config is available for rescheduling
        final List<BlockNodeConfiguration> availableNodes = availableNodes();
        availableNodes.clear();
        availableNodes.add(nodeConfig);

        final BlockNodeConnectionTask task =
                connectionManager.new BlockNodeConnectionTask(connection, Duration.ofSeconds(10), false);

        task.run();

        verify(connection).initialize();
        verify(scheduledExecutor).schedule(eq(task), anyLong(), eq(TimeUnit.MILLISECONDS));
        verify(connection).closeAtBlockBoundary();
        verify(metrics).recordConnectionCreateFailure();

        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(scheduledExecutor);
        verifyNoMoreInteractions(metrics);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testScheduleAndSelectNewNode_streamingDisabled() {
        useStreamingDisabledManager();
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);

        connectionManager.rescheduleConnection(connection, Duration.ZERO, null, true);

        verifyNoInteractions(connection);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(scheduledExecutor);
        verifyNoInteractions(metrics);
    }

    @Test
    void testShutdown_streamingDisabled() {
        useStreamingDisabledManager();

        connectionManager.shutdown();

        verifyNoInteractions(bufferService);
        verifyNoInteractions(scheduledExecutor);
        verifyNoInteractions(metrics);
    }

    @Test
    void testStartup_streamingDisabled() {
        useStreamingDisabledManager();

        connectionManager.start();

        final AtomicBoolean isManagerActive = isActiveFlag();
        assertThat(isManagerActive).isFalse();

        verifyNoInteractions(bufferService);
        verifyNoInteractions(scheduledExecutor);
        verifyNoInteractions(metrics);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_streamingDisabled() {
        useStreamingDisabledManager();

        connectionManager.selectNewBlockNodeForStreaming(false);

        verifyNoInteractions(bufferService);
        verifyNoInteractions(scheduledExecutor);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConstructor_streamingDisabled() {
        useStreamingDisabledManager();

        final List<BlockNodeConfiguration> availableNodes = availableNodes();
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

        connectionManager =
                new BlockNodeConnectionManager(configProvider, bufferService, metrics, blockingIoExecutorSupplier);

        // Verify that the manager was created but has no available nodes
        final List<BlockNodeConfiguration> availableNodes = availableNodes();
        assertThat(availableNodes).isEmpty();
    }

    @Test
    void testRescheduleConnection_singleBlockNode() {
        // selectNewBlockNodeForStreaming should NOT be called
        final Configuration config = HederaTestConfigBuilder.create()
                .withValue("blockStream.writerMode", "FILE_AND_GRPC")
                .withValue("blockNode.blockNodeConnectionFileDir", "/tmp/non-existent-test-dir-" + System.nanoTime())
                .getOrCreateConfig();
        final ConfigProvider configProvider = () -> new VersionedConfigImpl(config, 1L);

        connectionManager =
                new BlockNodeConnectionManager(configProvider, bufferService, metrics, blockingIoExecutorSupplier);

        sharedExecutorServiceHandle.set(connectionManager, scheduledExecutor);

        final List<BlockNodeConfiguration> availableNodes = availableNodes();
        availableNodes.clear();
        availableNodes.add(newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1));

        reset(scheduledExecutor);

        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        doReturn(nodeConfig).when(connection).configuration();

        final Map<BlockNodeConfiguration, BlockNodeStreamingConnection> connections = connections();
        connections.put(nodeConfig, connection);

        connectionManager.rescheduleConnection(connection, Duration.ofSeconds(5), null, true);

        // Verify exactly 1 schedule call was made (only the retry, no new node selection since there's only one node)
        verify(scheduledExecutor, times(1))
                .schedule(any(BlockNodeConnectionTask.class), eq(5000L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void testStart_streamingDisabled() {
        useStreamingDisabledManager();

        connectionManager.start();

        // Verify early return - no interactions with any services
        verifyNoInteractions(bufferService);
        verifyNoInteractions(scheduledExecutor);
        verifyNoInteractions(metrics);

        // Verify manager remains inactive
        final AtomicBoolean isActive = isActiveFlag();
        assertThat(isActive).isFalse();
    }

    @Test
    void testConnectionTask_runStreamingDisabled() {
        // Streaming disabled via config in constructor setup
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);

        final BlockNodeConnectionTask task =
                connectionManager.new BlockNodeConnectionTask(connection, Duration.ZERO, false);
        task.run();

        verifyNoInteractions(connection);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(scheduledExecutor);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConnectionTask_metricsIpFailsInvalidAddress() {
        isActiveFlag().set(true);
        final Map<BlockNodeConfiguration, BlockNodeStreamingConnection> connections = connections();
        final List<BlockNodeConfiguration> availableNodes = availableNodes();

        final BlockNodeConfiguration newConnectionConfig = newBlockNodeConfig("::1", 50211, 1);
        final BlockNodeStreamingConnection newConnection = mock(BlockNodeStreamingConnection.class);
        doReturn(newConnectionConfig).when(newConnection).configuration();

        connections.put(newConnectionConfig, newConnection);
        availableNodes.add(newConnectionConfig);

        connectionManager.new BlockNodeConnectionTask(newConnection, Duration.ZERO, false).run();

        verify(metrics).recordActiveConnectionIp(-1L);

        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testConnectionTask_metricsIpFailsInvalidHost() {
        isActiveFlag().set(true);
        final Map<BlockNodeConfiguration, BlockNodeStreamingConnection> connections = connections();
        final List<BlockNodeConfiguration> availableNodes = availableNodes();

        final BlockNodeConfiguration newConnectionConfig = newBlockNodeConfig("invalid.hostname.for.test", 50211, 1);
        final BlockNodeStreamingConnection newConnection = mock(BlockNodeStreamingConnection.class);
        doReturn(newConnectionConfig).when(newConnection).configuration();

        connections.put(newConnectionConfig, newConnection);
        availableNodes.add(newConnectionConfig);

        connectionManager.new BlockNodeConnectionTask(newConnection, Duration.ZERO, false).run();

        verify(metrics).recordActiveConnectionIp(-1L);

        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testHighLatencyTracking() {
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        final Instant ackedTime = Instant.now();

        connectionManager.recordBlockProofSent(nodeConfig, 1L, ackedTime);
        connectionManager.recordBlockAckAndCheckLatency(nodeConfig, 1L, ackedTime.plusMillis(30001));

        verify(metrics).recordHighLatencyEvent();
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testRecordEndOfStreamAndCheckLimit_streamingDisabled() {
        useStreamingDisabledManager();
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);

        final boolean limitExceeded = connectionManager.recordEndOfStreamAndCheckLimit(nodeConfig, Instant.now());

        assertThat(limitExceeded).isFalse();
    }

    @Test
    void testRecordEndOfStreamAndCheckLimit_withinLimit() {
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);

        final boolean limitExceeded = connectionManager.recordEndOfStreamAndCheckLimit(nodeConfig, Instant.now());

        assertThat(limitExceeded).isFalse();
    }

    @Test
    void testRecordEndOfStreamAndCheckLimit_exceedsLimit() {
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);

        // Record multiple EndOfStream events to exceed the limit
        // The default maxEndOfStreamsAllowed is 5
        for (int i = 0; i < 5; i++) {
            connectionManager.recordEndOfStreamAndCheckLimit(nodeConfig, Instant.now());
        }
        final boolean limitExceeded = connectionManager.recordEndOfStreamAndCheckLimit(nodeConfig, Instant.now());

        assertThat(limitExceeded).isTrue();
    }

    @Test
    void testRecordBehindPublisherAndCheckLimit_streamingDisabled() {
        useStreamingDisabledManager();
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);

        final boolean limitExceeded = connectionManager.recordBehindPublisherAndCheckLimit(nodeConfig, Instant.now());
        assertThat(limitExceeded).isFalse();

        final int count = connectionManager.getBehindPublisherCount(nodeConfig);
        assertThat(count).isZero();
    }

    @Test
    void testRecordBehindPublisherAndCheckLimit_withinLimit() {
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);

        final boolean limitExceeded = connectionManager.recordBehindPublisherAndCheckLimit(nodeConfig, Instant.now());
        assertThat(limitExceeded).isFalse();

        final int count = connectionManager.getBehindPublisherCount(nodeConfig);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void testRecordBehindPublisherAndCheckLimit_exceedsLimit() {
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);

        // Record multiple BehindPublisher events to exceed the limit
        // The default maxBehindPublishersAllowed is 1
        connectionManager.recordBehindPublisherAndCheckLimit(nodeConfig, Instant.now());

        final boolean limitExceeded = connectionManager.recordBehindPublisherAndCheckLimit(nodeConfig, Instant.now());
        assertThat(limitExceeded).isTrue();

        final int count = connectionManager.getBehindPublisherCount(nodeConfig);
        assertThat(count).isEqualTo(2);
    }

    // Priority based BN selection
    @Test
    void testPriorityBasedSelection_multiplePriority0Nodes_randomSelection() throws Exception {
        // Setup: Create multiple nodes with priority 0 and some with lower priorities
        final List<BlockNodeConfiguration> blockNodes = List.of(
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 0), // Priority 0
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 0), // Priority 0
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8082, 0), // Priority 0
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8083, 0), // Priority 0
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8084, 0), // Priority 0
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8085, 0), // Priority 0
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8086, 0), // Priority 0
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8087, 0), // Priority 0
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8088, 0), // Priority 0
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8089, 0), // Priority 0
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8090, 1), // Priority 1
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8091, 2), // Priority 2
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8092, 2), // Priority 2
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8093, 3), // Priority 3
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8094, 3) // Priority 3
                );

        // Track which priority 0 nodes get selected over multiple runs
        final Set<Integer> selectedNodes = new HashSet<>();

        // Run multiple selections to test randomization
        for (int i = 0; i < 50; i++) {
            // Reset mocks for each iteration
            resetMocks();

            doReturn(100L).when(bufferService).getEarliestAvailableBlockNumber();
            doReturn(200L).when(bufferService).getLastBlockNumberProduced();
            doAnswer(invocation -> {
                        final List<RetrieveBlockNodeStatusTask> tasks = invocation.getArgument(0);
                        final List<CompletableFuture<BlockNodeStatus>> futures = new ArrayList<>();
                        for (int k = 0; k < tasks.size(); ++k) {
                            futures.add(completedFuture(reachable(10, 99)));
                        }

                        return futures;
                    })
                    .when(blockingIoExecutor)
                    .invokeAll(anyList(), anyLong(), any(TimeUnit.class));

            // Configure the manager with these nodes
            createConnectionManager(blockNodes);

            // Perform selection - should only select from priority 0 nodes
            connectionManager.selectNewBlockNodeForStreaming(true);

            // Capture the scheduled task and verify it's connecting to a priority 0 node
            final ArgumentCaptor<BlockNodeConnectionTask> taskCaptor =
                    ArgumentCaptor.forClass(BlockNodeConnectionTask.class);
            verify(scheduledExecutor, atLeast(1)).schedule(taskCaptor.capture(), anyLong(), any(TimeUnit.class));

            final BlockNodeConnectionTask task = taskCaptor.getValue();
            final BlockNodeStreamingConnection connection = connectionFromTask(task);
            final BlockNodeConfiguration selectedConfig = connection.configuration();

            // Verify only priority 0 nodes are selected
            assertThat(selectedConfig.priority()).isZero();
            assertThat(selectedConfig.streamingPort()).isBetween(8080, 8089);

            // Track which node was selected
            selectedNodes.add(selectedConfig.streamingPort());
        }

        // Over 50 runs, we should see at least 2 different priority 0 nodes being selected.
        // This verifies the randomization is working (very unlikely to get same node 50 times).
        // The probability of flakiness is effectively zero - around 10^(-47).
        // Failure of this test means the random selection is not working.
        assertThat(selectedNodes).hasSizeGreaterThan(1);
    }

    @Test
    void testPriorityBasedSelection_onlyLowerPriorityNodesAvailable() throws Exception {
        // Setup: All priority 0 nodes are unavailable, only lower priority nodes available
        final List<BlockNodeConfiguration> blockNodes = List.of(
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1), // Priority 1
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 2), // Priority 2
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8082, 3) // Priority 3
                );

        createConnectionManager(blockNodes);

        doReturn(100L).when(bufferService).getEarliestAvailableBlockNumber();
        doReturn(200L).when(bufferService).getLastBlockNumberProduced();
        doAnswer(invocation -> {
                    final List<RetrieveBlockNodeStatusTask> tasks = invocation.getArgument(0);
                    final List<CompletableFuture<BlockNodeStatus>> futures = new ArrayList<>();
                    for (int i = 0; i < tasks.size(); ++i) {
                        futures.add(completedFuture(reachable(10, 99)));
                    }

                    return futures;
                })
                .when(blockingIoExecutor)
                .invokeAll(anyList(), anyLong(), any(TimeUnit.class));

        // Perform selection
        connectionManager.selectNewBlockNodeForStreaming(true);

        // Verify it selects the highest priority available
        final ArgumentCaptor<BlockNodeConnectionTask> taskCaptor =
                ArgumentCaptor.forClass(BlockNodeConnectionTask.class);
        verify(scheduledExecutor, atLeast(1)).schedule(taskCaptor.capture(), anyLong(), any(TimeUnit.class));

        final BlockNodeConnectionTask task = taskCaptor.getValue();
        final BlockNodeStreamingConnection connection = connectionFromTask(task);
        final BlockNodeConfiguration selectedConfig = connection.configuration();

        assertThat(selectedConfig.priority()).isEqualTo(1); // Should select priority 1 (highest available)

        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getLastBlockNumberProduced();
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testPriorityBasedSelection_mixedPrioritiesWithSomeUnavailable() throws Exception {
        // Setup: Mix of priorities where some priority 0 nodes are already connected
        final List<BlockNodeConfiguration> allBlockNodes = List.of(
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 0), // Priority 0 - will be unavailable
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 0), // Priority 0 - available
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8082, 0), // Priority 0 - available
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8083, 1), // Priority 1
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8084, 2) // Priority 2
                );

        createConnectionManager(allBlockNodes);

        doReturn(100L).when(bufferService).getEarliestAvailableBlockNumber();
        doReturn(200L).when(bufferService).getLastBlockNumberProduced();
        doAnswer(invocation -> {
                    final List<RetrieveBlockNodeStatusTask> tasks = invocation.getArgument(0);
                    final List<CompletableFuture<BlockNodeStatus>> futures = new ArrayList<>();
                    for (int i = 0; i < tasks.size(); ++i) {
                        futures.add(completedFuture(reachable(10, 99)));
                    }

                    return futures;
                })
                .when(blockingIoExecutor)
                .invokeAll(anyList(), anyLong(), any(TimeUnit.class));

        // Simulate that node1 is already connected (unavailable)
        final BlockNodeConfiguration unavailableNode = allBlockNodes.getFirst();
        final BlockNodeStreamingConnection existingConnection = mock(BlockNodeStreamingConnection.class);

        // Add the existing connection to make node1 unavailable
        final Map<BlockNodeConfiguration, BlockNodeStreamingConnection> connections = connections();
        connections.put(unavailableNode, existingConnection);

        // Perform selection
        connectionManager.selectNewBlockNodeForStreaming(true);

        // Verify it still selects from remaining priority 0 nodes
        final ArgumentCaptor<BlockNodeConnectionTask> taskCaptor =
                ArgumentCaptor.forClass(BlockNodeConnectionTask.class);
        verify(scheduledExecutor, atLeast(1)).schedule(taskCaptor.capture(), anyLong(), any(TimeUnit.class));

        final BlockNodeConnectionTask task = taskCaptor.getValue();
        final BlockNodeStreamingConnection connection = connectionFromTask(task);
        final BlockNodeConfiguration selectedConfig = connection.configuration();

        assertThat(selectedConfig.priority()).isZero();
        assertThat(selectedConfig.streamingPort()).isIn(8081, 8082);
        assertThat(selectedConfig.streamingPort()).isNotEqualTo(8080); // Should not select unavailable node

        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getLastBlockNumberProduced();
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testPriorityBasedSelection_allPriority0NodesUnavailable() throws Exception {
        // Setup: All priority 0 nodes are connected, lower priority nodes available
        final List<BlockNodeConfiguration> allBlockNodes = List.of(
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 0), // Priority 0 - unavailable
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 0), // Priority 0 - unavailable
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8082, 1), // Priority 1 - available
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8083, 1), // Priority 1 - available
                newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8084, 2) // Priority 2 - available
                );

        createConnectionManager(allBlockNodes);

        doReturn(100L).when(bufferService).getEarliestAvailableBlockNumber();
        doReturn(200L).when(bufferService).getLastBlockNumberProduced();
        doAnswer(invocation -> {
                    final List<RetrieveBlockNodeStatusTask> tasks = invocation.getArgument(0);
                    final List<CompletableFuture<BlockNodeStatus>> futures = new ArrayList<>();
                    for (int i = 0; i < tasks.size(); ++i) {
                        futures.add(completedFuture(reachable(10, 99)));
                    }

                    return futures;
                })
                .when(blockingIoExecutor)
                .invokeAll(anyList(), anyLong(), any(TimeUnit.class));

        // Make all priority 0 nodes unavailable by adding them to connections
        final Map<BlockNodeConfiguration, BlockNodeStreamingConnection> connections = connections();
        for (int i = 0; i < 2; i++) { // First 2 nodes are priority 0
            final BlockNodeConfiguration unavailableNode = allBlockNodes.get(i);
            final BlockNodeStreamingConnection existingConnection = mock(BlockNodeStreamingConnection.class);
            connections.put(unavailableNode, existingConnection);
        }

        // Perform selection
        connectionManager.selectNewBlockNodeForStreaming(true);

        // Verify it selects from next highest priority group (priority 1)
        final ArgumentCaptor<BlockNodeConnectionTask> taskCaptor =
                ArgumentCaptor.forClass(BlockNodeConnectionTask.class);
        verify(scheduledExecutor, atLeast(1)).schedule(taskCaptor.capture(), anyLong(), any(TimeUnit.class));

        final BlockNodeConnectionTask task = taskCaptor.getValue();
        final BlockNodeStreamingConnection connection = connectionFromTask(task);
        final BlockNodeConfiguration selectedConfig = connection.configuration();

        assertThat(selectedConfig.priority()).isEqualTo(1); // Should fall back to priority 1
        assertThat(selectedConfig.streamingPort()).isIn(8082, 8083);

        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getLastBlockNumberProduced();
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testCloseAllConnections() {
        final BlockNodeStreamingConnection conn = mock(BlockNodeStreamingConnection.class);
        connections().put(newBlockNodeConfig(8080, 1), conn);

        invoke_closeAllConnections();

        verify(conn).close();
        assertThat(connections()).isEmpty();
    }

    @Test
    void testCloseAllConnections_whenStreamingDisabled() {
        useStreamingDisabledManager();
        // Streaming disabled via config in constructor setup
        final BlockNodeStreamingConnection conn = mock(BlockNodeStreamingConnection.class);
        connections().put(newBlockNodeConfig(8080, 1), conn);

        invoke_closeAllConnections();

        verify(conn).close();
    }

    @Test
    void testRefreshAvailableBlockNodes() {
        final BlockNodeStreamingConnection conn = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration oldNode = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 9999, 1);
        connections().put(oldNode, conn);
        availableNodes().add(oldNode);

        invoke_refreshAvailableBlockNodes();

        // Verify old connection was closed
        verify(conn).close();
    }

    @Test
    void testRefreshAvailableBlockNodes_shutsDownExecutorAndReloads_whenValid() throws Exception {
        // Point manager at real bootstrap config directory so reload finds valid JSON
        final var configPath = Objects.requireNonNull(
                        BlockNodeCommunicationTestBase.class.getClassLoader().getResource("bootstrap/"))
                .getPath();
        // Replace the directory used by the manager
        blockNodeConfigDirectoryHandle.set(connectionManager, Path.of(configPath));

        // Populate with a dummy existing connection and a mock executor to be shut down
        final BlockNodeStreamingConnection existing = mock(BlockNodeStreamingConnection.class);
        connections().put(newBlockNodeConfig(4242, 0), existing);
        final ScheduledExecutorService oldExecutor = mock(ScheduledExecutorService.class);
        sharedExecutorServiceHandle.set(connectionManager, oldExecutor);

        // Ensure manager is initially inactive
        isActiveFlag().set(false);

        doReturn(100L).when(bufferService).getEarliestAvailableBlockNumber();
        doReturn(200L).when(bufferService).getLastBlockNumberProduced();
        doAnswer(invocation -> {
                    final List<RetrieveBlockNodeStatusTask> tasks = invocation.getArgument(0);
                    final List<CompletableFuture<BlockNodeStatus>> futures = new ArrayList<>();
                    for (int i = 0; i < tasks.size(); ++i) {
                        futures.add(completedFuture(reachable(10, 99)));
                    }

                    return futures;
                })
                .when(blockingIoExecutor)
                .invokeAll(anyList(), anyLong(), any(TimeUnit.class));

        invoke_refreshAvailableBlockNodes();

        // Old connection closed and executor shut down
        verify(existing).close();

        // Available nodes should be reloaded from bootstrap JSON (non-empty)
        assertThat(availableNodes()).isNotEmpty();
    }

    @Test
    void testStartConfigWatcher_alreadyRunning() throws Exception {
        connectionManager.start(); // This starts the config watcher

        // Call startConfigWatcher again via reflection to trigger the "already running" check
        final var method = BlockNodeConnectionManager.class.getDeclaredMethod("startConfigWatcher");
        method.setAccessible(true);
        method.invoke(connectionManager);

        // Verify the manager is still active
        assertThat(isActiveFlag().get()).isTrue();
    }

    @Test
    void testRescheduleConnection_withNullDelay() {
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        doReturn(nodeConfig).when(connection).configuration();

        final List<BlockNodeConfiguration> availableNodes = availableNodes();
        availableNodes.clear();
        availableNodes.add(nodeConfig);

        // Call rescheduleConnection with null delay to trigger the calculateJitteredDelayMs path
        connectionManager.rescheduleConnection(connection, null, null, true);

        // Verify the retry state was created and connection was scheduled
        final Map<BlockNodeConfiguration, RetryState> retryStates = retryStates();
        assertThat(retryStates).containsKey(nodeConfig);
    }

    @Test
    void testRecordActiveConnectionIp() throws Exception {
        final var method = BlockNodeConnectionManager.class.getDeclaredMethod(
                "recordActiveConnectionIp", BlockNodeConfiguration.class);
        method.setAccessible(true);

        final BlockNodeConfiguration config = newBlockNodeConfig("localhost", 8080, 1);

        method.invoke(connectionManager, config);

        verify(metrics).recordActiveConnectionIp(anyLong());
    }

    @Test
    void testStartConfigWatcher_reactsToCreateModifyDelete() throws Exception {
        // Ensure the watcher monitors the temp directory used by this test
        blockNodeConfigDirectoryHandle.set(connectionManager, tempDir);

        doReturn(100L).when(bufferService).getEarliestAvailableBlockNumber();
        doReturn(200L).when(bufferService).getLastBlockNumberProduced();
        doAnswer(invocation -> {
                    final List<RetrieveBlockNodeStatusTask> tasks = invocation.getArgument(0);
                    final List<CompletableFuture<BlockNodeStatus>> futures = new ArrayList<>();
                    for (int i = 0; i < tasks.size(); ++i) {
                        futures.add(completedFuture(reachable(10, 99)));
                    }

                    return futures;
                })
                .when(blockingIoExecutor)
                .invokeAll(anyList(), anyLong(), any(TimeUnit.class));

        connectionManager.start();
        final Path file = tempDir.resolve("block-nodes.json");
        final List<BlockNodeConfig> configs = new ArrayList<>();
        final BlockNodeConfig config = BlockNodeConfig.newBuilder()
                .address("localhost")
                .streamingPort(8080)
                .servicePort(8081)
                .priority(0)
                .build();
        configs.add(config);
        final BlockNodeConnectionInfo connectionInfo = new BlockNodeConnectionInfo(configs);
        final String valid = BlockNodeConnectionInfo.JSON.toJSON(connectionInfo);
        Files.writeString(
                file, valid, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        awaitCondition(() -> !availableNodes().isEmpty(), 5_000);
        Files.writeString(file, "not json", StandardOpenOption.TRUNCATE_EXISTING);
        awaitCondition(() -> availableNodes().isEmpty(), 5_000);
        Files.writeString(file, valid, StandardOpenOption.TRUNCATE_EXISTING);
        awaitCondition(() -> !availableNodes().isEmpty(), 5_000);
        Files.deleteIfExists(file);
        awaitCondition(() -> availableNodes().isEmpty(), 3_000);

        // Exercise unchanged path: write back the same content and ensure no restart occurs
        Files.writeString(
                file, valid, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        awaitCondition(() -> !availableNodes().isEmpty(), 5_000);
        final List<BlockNodeConfiguration> before = availableNodes();
        invoke_refreshAvailableBlockNodes();
        final List<BlockNodeConfiguration> after = availableNodes();
        assertThat(after).isEqualTo(before);
    }

    @Test
    void testCloseAllConnections_withException() {
        final BlockNodeStreamingConnection conn = mock(BlockNodeStreamingConnection.class);
        doThrow(new RuntimeException("Close failed")).when(conn).close(true);
        connections().put(newBlockNodeConfig(8080, 1), conn);

        // Should not throw - exceptions are caught and logged
        invoke_closeAllConnections();

        verify(conn).close();
        assertThat(connections()).isEmpty();
    }

    @Test
    void testExtractBlockNodesConfigurations_fileNotExists() {
        final List<BlockNodeConfiguration> configs = invoke_extractBlockNodesConfigurations("/non/existent/path");

        assertThat(configs).isEmpty();
    }

    @Test
    void testExtractBlockNodesConfigurations_invalidJson() {
        // Use a path that exists but doesn't contain valid JSON
        final List<BlockNodeConfiguration> configs = invoke_extractBlockNodesConfigurations("/tmp");

        // Should return empty list when parse fails
        assertThat(configs).isEmpty();
    }

    @Test
    void testExtractBlockNodesConfigurations_validJson_populatesProtocolConfigs() throws Exception {
        final Path dir = tempDir;
        final Path file = dir.resolve("block-nodes.json");

        final String json = """
                {
                  "nodes": [
                    {
                      "address": "localhost",
                      "streamingPort": 50051,
                      "servicePort": 50052,
                      "priority": 1,
                      "messageSizeSoftLimitBytes": 1500000,
                      "messageSizeHardLimitBytes": 8000000
                    }
                  ]
                }
                """;

        Files.writeString(file, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        final List<BlockNodeConfiguration> configs = invoke_extractBlockNodesConfigurations(dir.toString());

        assertThat(configs).hasSize(1);

        final BlockNodeConfiguration protocol = configs.getFirst();
        assertThat(protocol.messageSizeSoftLimitBytes()).isEqualTo(1_500_000L);
        assertThat(protocol.messageSizeHardLimitBytes()).isEqualTo(8_000_000L);
    }

    @Test
    void testConnectionTask_activeConnectionIsSameConnection() {
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);

        activeConnection().set(connection);

        final BlockNodeConnectionTask task =
                connectionManager.new BlockNodeConnectionTask(connection, Duration.ZERO, false);

        task.run();

        // Should return early without creating pipeline
        verify(connection, never()).initialize();
    }

    @Test
    void testConnectionTask_preempted_reschedules() {
        // compareAndSet fails due to preemption, then reschedules
        isActiveFlag().set(true);
        final AtomicReference<BlockNodeStreamingConnection> activeRef = activeConnection();

        // Start with an active connection of lower priority than the candidate
        final BlockNodeStreamingConnection initialActive = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration initialActiveCfg = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8092, 2);
        doReturn(initialActiveCfg).when(initialActive).configuration();
        activeRef.set(initialActive);

        // Candidate has higher priority.
        final BlockNodeConfiguration candidateCfg = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8093, 1);
        final BlockNodeStreamingConnection candidate = mock(BlockNodeStreamingConnection.class);
        // Ensure priority comparison path is exercised and pipeline is created
        doReturn(candidateCfg).when(candidate).configuration();

        // Ensure candidate's node remains available for reschedule path
        final List<BlockNodeConfiguration> avail = availableNodes();
        avail.add(candidateCfg);

        // Simulate preemption: during pipeline creation, another connection becomes active
        final BlockNodeStreamingConnection preemptor = mock(BlockNodeStreamingConnection.class);

        doAnswer(invocation -> {
                    activeRef.set(preemptor);
                    return null;
                })
                .when(candidate)
                .initialize();

        final BlockNodeConnectionTask task =
                connectionManager.new BlockNodeConnectionTask(candidate, Duration.ZERO, false);
        task.run();

        // the task should have been rescheduled
        verify(scheduledExecutor).schedule(eq(task), anyLong(), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void testConnectionTask_runStreamingDisabledEarlyReturn() {
        useStreamingDisabledManager();

        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);

        connectionManager.new BlockNodeConnectionTask(connection, Duration.ZERO, false).run();

        verifyNoInteractions(connection);
        verifyNoInteractions(scheduledExecutor);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConfigWatcher_handlesExceptionInRefresh() throws Exception {
        final Path file = tempDir.resolve("block-nodes.json");

        // Start with valid config
        final List<BlockNodeConfig> configs = new ArrayList<>();
        configs.add(BlockNodeConfig.newBuilder()
                .address(PBJ_UNIT_TEST_HOST)
                .streamingPort(8080)
                .servicePort(8081)
                .priority(1)
                .messageSizeSoftLimitBytes(1_000_000L)
                .messageSizeHardLimitBytes(2_000_000L)
                .build());
        final BlockNodeConnectionInfo connectionInfo = new BlockNodeConnectionInfo(configs);
        final String valid = BlockNodeConnectionInfo.JSON.toJSON(connectionInfo);
        Files.writeString(
                file, valid, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        doReturn(100L).when(bufferService).getEarliestAvailableBlockNumber();
        doReturn(200L).when(bufferService).getLastBlockNumberProduced();
        doAnswer(invocation -> {
                    final List<RetrieveBlockNodeStatusTask> tasks = invocation.getArgument(0);
                    final List<CompletableFuture<BlockNodeStatus>> futures = new ArrayList<>();
                    for (int i = 0; i < tasks.size(); ++i) {
                        futures.add(completedFuture(reachable(10, 99)));
                    }

                    return futures;
                })
                .when(blockingIoExecutor)
                .invokeAll(anyList(), anyLong(), any(TimeUnit.class));

        connectionManager.start();

        // Wait for initial load
        awaitCondition(() -> !availableNodes().isEmpty(), 2_000);

        // Replace blockNodeConfigDirectory with an invalid path that will cause issues
        // This will trigger an exception when refreshAvailableBlockNodes() is called
        final Path invalidPath = Path.of("/invalid/nonexistent/path/that/will/cause/issues");
        blockNodeConfigDirectoryHandle.set(connectionManager, invalidPath);

        // Modify the file to trigger a watch event
        Files.writeString(file, valid + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        // Give the watcher time to detect the change and handle the exception
        Thread.sleep(500);

        connectionManager.shutdown();
    }

    @Test
    void testStartConfigWatcher_handlesIOException() throws Exception {
        // Create a file instead of directory to trigger IOException when trying to watch it
        final Path fileNotDir = tempDir.resolve("not-a-directory.txt");
        Files.writeString(fileNotDir, "test", StandardOpenOption.CREATE);

        final var configProvider = createConfigProvider(createDefaultConfigProvider()
                .withValue(
                        "blockNode.blockNodeConnectionFileDir",
                        fileNotDir.toAbsolutePath().toString()));

        // This should trigger IOException when trying to create WatchService on a file
        final var manager =
                new BlockNodeConnectionManager(configProvider, bufferService, metrics, blockingIoExecutorSupplier);
        manager.start();

        // Manager should start successfully even though config watcher failed
        Thread.sleep(500);

        manager.shutdown();
    }

    @Test
    void testConfigWatcher_handlesInterruptedException() throws Exception {
        final Path file = tempDir.resolve("block-nodes.json");
        final List<BlockNodeConfig> configs = new ArrayList<>();
        configs.add(BlockNodeConfig.newBuilder()
                .address(PBJ_UNIT_TEST_HOST)
                .streamingPort(8080)
                .servicePort(8081)
                .priority(1)
                .build());
        final BlockNodeConnectionInfo connectionInfo = new BlockNodeConnectionInfo(configs);
        final String valid = BlockNodeConnectionInfo.JSON.toJSON(connectionInfo);
        Files.writeString(
                file, valid, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        doReturn(100L).when(bufferService).getEarliestAvailableBlockNumber();
        doReturn(200L).when(bufferService).getLastBlockNumberProduced();
        doAnswer(invocation -> {
                    final List<RetrieveBlockNodeStatusTask> tasks = invocation.getArgument(0);
                    final List<CompletableFuture<BlockNodeStatus>> futures = new ArrayList<>();
                    for (int i = 0; i < tasks.size(); ++i) {
                        futures.add(completedFuture(reachable(10, 99)));
                    }

                    return futures;
                })
                .when(blockingIoExecutor)
                .invokeAll(anyList(), anyLong(), any(TimeUnit.class));

        connectionManager.start();
        awaitCondition(() -> !availableNodes().isEmpty(), 2_000);

        // Shutdown will interrupt the watcher thread
        connectionManager.shutdown();

        // Verify the watcher thread is no longer running
        @SuppressWarnings("unchecked")
        final AtomicReference<Thread> threadRef =
                (AtomicReference<Thread>) configWatcherThreadRef.get(connectionManager);
        final Thread watcherThread = threadRef.get();
        if (watcherThread != null) {
            watcherThread.join(1000);
            assertThat(watcherThread.isAlive()).isFalse();
        }
    }

    @Test
    void testRescheduleConnection_multipleNodesButSelectNewFalse() {
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration nodeConfig1 = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        final BlockNodeConfiguration nodeConfig2 = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 1);
        doReturn(nodeConfig1).when(connection).configuration();

        availableNodes().add(nodeConfig1);
        availableNodes().add(nodeConfig2);
        connections().put(nodeConfig1, connection);

        connectionManager.rescheduleConnection(connection, Duration.ofSeconds(1), null, false);

        // Verify only one schedule call (for the reschedule, not for new node selection)
        verify(scheduledExecutor, times(1))
                .schedule(any(BlockNodeConnectionTask.class), anyLong(), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void testRescheduleConnection_negativeDelayClampedToZero() {
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        doReturn(nodeConfig).when(connection).configuration();

        availableNodes().add(nodeConfig);
        connections().put(nodeConfig, connection);

        connectionManager.rescheduleConnection(connection, Duration.ofMillis(-5), null, false);

        final ArgumentCaptor<Long> delayCaptor = ArgumentCaptor.forClass(Long.class);
        verify(scheduledExecutor)
                .schedule(any(BlockNodeConnectionTask.class), delayCaptor.capture(), eq(TimeUnit.MILLISECONDS));
        assertThat(delayCaptor.getValue()).isZero();
    }

    @Test
    void testConnectionTask_reschedule_exceedsMaxBackoff() {
        isActiveFlag().set(true);
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        doReturn(nodeConfig).when(connection).configuration();

        doThrow(new RuntimeException("Connection failed")).when(connection).initialize();

        connections().put(nodeConfig, connection);
        availableNodes().add(nodeConfig);

        // Create task with a very large initial delay to exceed max backoff
        final BlockNodeConnectionTask task =
                connectionManager.new BlockNodeConnectionTask(connection, Duration.ofHours(10), false);

        task.run();

        // Verify it was rescheduled
        verify(scheduledExecutor).schedule(eq(task), anyLong(), eq(TimeUnit.MILLISECONDS));
        verify(metrics).recordConnectionCreateFailure();
    }

    @Test
    void testIsOnlyOneBlockNodeConfigured_true() {
        availableNodes().clear();
        availableNodes().add(newBlockNodeConfig(8080, 1));

        assertThat(connectionManager.isOnlyOneBlockNodeConfigured()).isTrue();
    }

    @Test
    void testIsOnlyOneBlockNodeConfigured_false() {
        availableNodes().clear();
        availableNodes().add(newBlockNodeConfig(8080, 1));
        availableNodes().add(newBlockNodeConfig(8081, 1));

        assertThat(connectionManager.isOnlyOneBlockNodeConfigured()).isFalse();
    }

    @Test
    void testIsOnlyOneBlockNodeConfigured_empty() {
        availableNodes().clear();

        assertThat(connectionManager.isOnlyOneBlockNodeConfigured()).isFalse();
    }

    @Test
    void testRecordBlockAckAndCheckLatency_normalLatency() {
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        final Instant sentTime = Instant.now();
        final Instant ackedTime = sentTime.plusMillis(100); // Normal latency

        connectionManager.recordBlockProofSent(nodeConfig, 1L, sentTime);
        final BlockNodeStats.HighLatencyResult result =
                connectionManager.recordBlockAckAndCheckLatency(nodeConfig, 1L, ackedTime);

        assertThat(result.isHighLatency()).isFalse();
        assertThat(result.shouldSwitch()).isFalse();
    }

    @Test
    void testGetEndOfStreamCount() {
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);

        connectionManager.recordEndOfStreamAndCheckLimit(nodeConfig, Instant.now());
        connectionManager.recordEndOfStreamAndCheckLimit(nodeConfig, Instant.now());

        final int count = connectionManager.getEndOfStreamCount(nodeConfig);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void testGetEndOfStreamCount_unknownNode() {
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);

        final int count = connectionManager.getEndOfStreamCount(nodeConfig);
        assertThat(count).isZero();
    }

    @Test
    void testGetEndOfStreamScheduleDelay() {
        final Duration delay = connectionManager.getEndOfStreamScheduleDelay();
        assertThat(delay).isNotNull();
    }

    @Test
    void testGetEndOfStreamTimeframe() {
        final Duration timeframe = connectionManager.getEndOfStreamTimeframe();
        assertThat(timeframe).isNotNull();
    }

    @Test
    void testGetMaxEndOfStreamsAllowed() {
        final int max = connectionManager.getMaxEndOfStreamsAllowed();
        assertThat(max).isGreaterThan(0);
    }

    @Test
    void testShutdownScheduledExecutorService_nullExecutor() {
        sharedExecutorServiceHandle.set(connectionManager, null);

        // Should handle null executor gracefully
        connectionManager.shutdown();

        verifyNoInteractions(scheduledExecutor);
    }

    @Test
    void testConnectionTask_closeOldActiveConnectionThrowsException() {
        isActiveFlag().set(true);
        final AtomicReference<BlockNodeStreamingConnection> activeConnectionRef = activeConnection();

        final BlockNodeStreamingConnection oldActive = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration oldConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 2);
        doReturn(oldConfig).when(oldActive).configuration();
        doThrow(new RuntimeException("Close failed")).when(oldActive).closeAtBlockBoundary();
        activeConnectionRef.set(oldActive);

        final BlockNodeStreamingConnection newConnection = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration newConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 1);
        doReturn(newConfig).when(newConnection).configuration();

        // Should handle exception gracefully
        connectionManager.new BlockNodeConnectionTask(newConnection, Duration.ZERO, false).run();

        verify(oldActive).closeAtBlockBoundary();
        verify(newConnection).initialize();
        verify(newConnection).updateConnectionState(ConnectionState.ACTIVE);
        assertThat(activeConnectionRef).hasValue(newConnection);
    }

    @Test
    void parsesBootstrapBlockNodesJsonWithBlockNodeConfigCodec() throws Exception {
        final URL url = BlockNodeCommunicationTestBase.class.getClassLoader().getResource("bootstrap/block-nodes.json");
        assertThat(url).isNotNull();
        final Path dirPath = Path.of(url.getPath());
        final byte[] jsonConfig = Files.readAllBytes(dirPath);
        final BlockNodeConnectionInfo protoConfig = BlockNodeConnectionInfo.JSON.parse(Bytes.wrap(jsonConfig));
        assertThat(protoConfig).isNotNull();
        assertThat(protoConfig.nodes().getFirst().messageSizeSoftLimitBytes()).isNotNull();
    }

    @Test
    void testNotifyConnectionClosed_removesNonActiveConnection() {
        final BlockNodeStreamingConnection conn = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration cfg = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 4242, 1);
        when(conn.configuration()).thenReturn(cfg);

        // Put the connection into the connections map
        connections().put(cfg, conn);

        // Ensure it's not the active connection
        activeConnection().set(null);

        // Call notifyConnectionClosed
        connectionManager.notifyConnectionClosed(conn);

        // The connection should be removed from the map
        assertThat(connections()).doesNotContainKey(cfg);

        // No scheduling or other side effects expected
        verifyNoInteractions(scheduledExecutor);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_notEnabled() {
        useStreamingDisabledManager();

        connectionManager.selectNewBlockNodeForStreaming(false);

        verifyNoInteractions(bufferService);
        verifyNoInteractions(scheduledExecutor);
        verifyNoInteractions(metrics);
        verifyNoInteractions(blockingIoExecutor);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_noConfiguredNodes() {
        availableNodes().clear();

        connectionManager.selectNewBlockNodeForStreaming(false);

        verifyNoInteractions(bufferService);
        verifyNoInteractions(scheduledExecutor);
        verifyNoInteractions(metrics);
        verifyNoInteractions(blockingIoExecutor);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_allNodesUnreachableOrOutOfBounds() throws Exception {
        final BlockNodeConfiguration node1Config = newBlockNodeConfig(8080, 1);
        final BlockNodeConfiguration node2Config = newBlockNodeConfig(8081, 2);
        final BlockNodeConfiguration node3Config = newBlockNodeConfig(8082, 2);
        final BlockNodeConfiguration node4Config = newBlockNodeConfig(8083, 3);
        availableNodes().clear();
        availableNodes().addAll(List.of(node1Config, node2Config, node3Config, node4Config));

        final long earliestBlock = 100;
        final long latestBlock = 250;
        doReturn(earliestBlock).when(bufferService).getEarliestAvailableBlockNumber();
        doReturn(latestBlock).when(bufferService).getLastBlockNumberProduced();
        doAnswer(invocation -> {
                    final List<RetrieveBlockNodeStatusTask> tasks = invocation.getArgument(0);
                    final List<CompletableFuture<BlockNodeStatus>> futures = new ArrayList<>();
                    for (int i = 0; i < tasks.size(); ++i) {
                        final BlockNodeServiceConnection connection =
                                (BlockNodeServiceConnection) nodeStatusTaskConnectionHandle.get(tasks.get(i));
                        final BlockNodeConfiguration taskNodeConfig = connection.configuration();
                        if (taskNodeConfig.streamingPort() == node1Config.streamingPort()) {
                            // set one node as unreachable
                            futures.add(completedFuture(notReachable()));
                        } else if (taskNodeConfig.streamingPort() == node2Config.streamingPort()) {
                            // set one node to have a latest block greater than what is available
                            futures.add(completedFuture(reachable(2, latestBlock + 10)));
                        } else {
                            // set the rest of the nodes to be behind the CN
                            futures.add(completedFuture(reachable(4, earliestBlock - 10)));
                        }
                    }

                    return futures;
                })
                .when(blockingIoExecutor)
                .invokeAll(anyList(), anyLong(), any(TimeUnit.class));

        assertThat(connectionManager.selectNewBlockNodeForStreaming(false)).isFalse();

        verify(bufferService, times(3)).getLastBlockNumberProduced();
        verify(bufferService, times(3)).getEarliestAvailableBlockNumber();
        verify(blockingIoExecutor, times(3)).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoInteractions(metrics);
        verifyNoInteractions(scheduledExecutor);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_noHighPriorityNodesAvailable() throws Exception {
        final BlockNodeConfiguration node1Config = newBlockNodeConfig(8080, 1);
        final BlockNodeConfiguration node2Config = newBlockNodeConfig(8081, 1);
        final BlockNodeConfiguration node3Config = newBlockNodeConfig(8082, 1);
        final BlockNodeConfiguration node4Config = newBlockNodeConfig(8083, 2);
        availableNodes().clear();
        availableNodes().addAll(List.of(node1Config, node2Config, node3Config, node4Config));

        final long earliestBlock = 100;
        final long latestBlock = 250;
        doReturn(earliestBlock).when(bufferService).getEarliestAvailableBlockNumber();
        doReturn(latestBlock).when(bufferService).getLastBlockNumberProduced();

        doAnswer(invocation -> {
                    final List<RetrieveBlockNodeStatusTask> tasks = invocation.getArgument(0);
                    final List<CompletableFuture<BlockNodeStatus>> futures = new ArrayList<>();
                    for (int i = 0; i < tasks.size(); ++i) {
                        final BlockNodeServiceConnection connection =
                                (BlockNodeServiceConnection) nodeStatusTaskConnectionHandle.get(tasks.get(i));
                        final BlockNodeConfiguration taskNodeConfig = connection.configuration();
                        if (taskNodeConfig.streamingPort() == node4Config.streamingPort()) {
                            // set node 4 (priority 2) as the only reachable node
                            futures.add(completedFuture(reachable(3, latestBlock - 10)));
                        } else {
                            // set all other nodes (priority 1) to be behind the CN
                            futures.add(completedFuture(reachable(4, earliestBlock - 10)));
                        }
                    }

                    return futures;
                })
                .when(blockingIoExecutor)
                .invokeAll(anyList(), anyLong(), any(TimeUnit.class));

        assertThat(connectionManager.selectNewBlockNodeForStreaming(false)).isTrue();

        final ArgumentCaptor<Runnable> scheduledExecCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduledExecutor).schedule(scheduledExecCaptor.capture(), anyLong(), any(TimeUnit.class));

        assertThat(scheduledExecCaptor.getAllValues()).hasSize(1);
        final Runnable task = scheduledExecCaptor.getValue();
        assertThat(task).isNotNull().isInstanceOf(BlockNodeConnectionTask.class);
        final BlockNodeStreamingConnection connection = connectionFromTask((BlockNodeConnectionTask) task);
        assertThat(connection.configuration()).isEqualTo(node4Config);

        verify(bufferService, times(2)).getLastBlockNumberProduced();
        verify(bufferService, times(2)).getEarliestAvailableBlockNumber();
        verify(blockingIoExecutor, times(2)).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoInteractions(metrics);
        verifyNoMoreInteractions(scheduledExecutor);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_multipleGoodNodes() throws Exception {
        final BlockNodeConfiguration node1Config = newBlockNodeConfig(8080, 1);
        final BlockNodeConfiguration node2Config = newBlockNodeConfig(8081, 1);
        final BlockNodeConfiguration node3Config = newBlockNodeConfig(8082, 2);
        final BlockNodeConfiguration node4Config = newBlockNodeConfig(8083, 3);
        availableNodes().clear();
        availableNodes().addAll(List.of(node1Config, node2Config, node3Config, node4Config));

        final long earliestBlock = 100;
        final long latestBlock = 250;
        doReturn(earliestBlock).when(bufferService).getEarliestAvailableBlockNumber();
        doReturn(latestBlock).when(bufferService).getLastBlockNumberProduced();

        doAnswer(invocation -> {
                    final List<RetrieveBlockNodeStatusTask> tasks = invocation.getArgument(0);
                    final List<CompletableFuture<BlockNodeStatus>> futures = new ArrayList<>();
                    for (int i = 0; i < tasks.size(); ++i) {
                        futures.add(completedFuture(reachable(5, latestBlock - 5)));
                    }

                    return futures;
                })
                .when(blockingIoExecutor)
                .invokeAll(anyList(), anyLong(), any(TimeUnit.class));

        assertThat(connectionManager.selectNewBlockNodeForStreaming(false)).isTrue();

        final ArgumentCaptor<Runnable> scheduledExecCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduledExecutor).schedule(scheduledExecCaptor.capture(), anyLong(), any(TimeUnit.class));

        assertThat(scheduledExecCaptor.getAllValues()).hasSize(1);
        final Runnable task = scheduledExecCaptor.getValue();
        assertThat(task).isNotNull().isInstanceOf(BlockNodeConnectionTask.class);
        final BlockNodeStreamingConnection connection = connectionFromTask((BlockNodeConnectionTask) task);
        // the node we've scheduled to connect to should be a node from priority group 1 (node 1 or 2)
        assertThat(connection.configuration()).isIn(node1Config, node2Config);

        // since one of the nodes in priority group 1 was chosen, we should only interact with the buffer service once
        verify(bufferService, times(1)).getLastBlockNumberProduced();
        verify(bufferService, times(1)).getEarliestAvailableBlockNumber();
        verify(blockingIoExecutor).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoInteractions(metrics);
        verifyNoMoreInteractions(scheduledExecutor);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_noneAvailable_timeout() throws Exception {
        final BlockNodeConfiguration node1Config = newBlockNodeConfig(8080, 1);
        final BlockNodeConfiguration node2Config = newBlockNodeConfig(8081, 1);
        final BlockNodeConfiguration node3Config = newBlockNodeConfig(8082, 1);
        final BlockNodeConfiguration node4Config = newBlockNodeConfig(8083, 1);
        availableNodes().clear();
        availableNodes().addAll(List.of(node1Config, node2Config, node3Config, node4Config));

        final long earliestBlock = 100;
        final long latestBlock = 250;
        doReturn(earliestBlock).when(bufferService).getEarliestAvailableBlockNumber();
        doReturn(latestBlock).when(bufferService).getLastBlockNumberProduced();

        // when submitting the tasks to retrieve the status, block for longer than the timeout (default: 250ms)
        final CompletableFuture<BlockNodeStatus> node1CfSpy = spy(createSleepingFuture());
        final CompletableFuture<BlockNodeStatus> node2CfSpy = spy(createSleepingFuture());
        final CompletableFuture<BlockNodeStatus> node3CfSpy = spy(createSleepingFuture());
        final CompletableFuture<BlockNodeStatus> node4CfSpy = spy(createSleepingFuture());

        doAnswer(invocation -> {
                    final List<RetrieveBlockNodeStatusTask> tasks = invocation.getArgument(0);
                    final List<CompletableFuture<BlockNodeStatus>> futures = new ArrayList<>();
                    for (int i = 0; i < tasks.size(); ++i) {
                        final BlockNodeServiceConnection connection =
                                (BlockNodeServiceConnection) nodeStatusTaskConnectionHandle.get(tasks.get(i));
                        final BlockNodeConfiguration taskNodeConfig = connection.configuration();
                        if (node1Config.streamingPort() == taskNodeConfig.streamingPort()) {
                            futures.add(node1CfSpy);
                        } else if (node2Config.streamingPort() == taskNodeConfig.streamingPort()) {
                            futures.add(node2CfSpy);
                        } else if (node3Config.streamingPort() == taskNodeConfig.streamingPort()) {
                            futures.add(node3CfSpy);
                        } else if (node4Config.streamingPort() == taskNodeConfig.streamingPort()) {
                            futures.add(node4CfSpy);
                        } else {
                            throw new IllegalStateException("Unexpected config: " + taskNodeConfig);
                        }
                    }

                    return futures;
                })
                .when(blockingIoExecutor)
                .invokeAll(anyList(), anyLong(), any(TimeUnit.class));

        assertThat(connectionManager.selectNewBlockNodeForStreaming(false)).isFalse();

        // since the tasks exceed the timeout, they should all be canceled
        verify(node1CfSpy).cancel(true);
        verify(node2CfSpy).cancel(true);
        verify(node3CfSpy).cancel(true);
        verify(node4CfSpy).cancel(true);
        verify(bufferService, times(1)).getLastBlockNumberProduced();
        verify(bufferService, times(1)).getEarliestAvailableBlockNumber();
        verify(blockingIoExecutor).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verifyNoInteractions(scheduledExecutor);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoInteractions(metrics);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_interrupted() throws Exception {
        final BlockNodeConfiguration node1Config = newBlockNodeConfig(8080, 1);
        final BlockNodeConfiguration node2Config = newBlockNodeConfig(8081, 1);
        final BlockNodeConfiguration node3Config = newBlockNodeConfig(8082, 2);
        final BlockNodeConfiguration node4Config = newBlockNodeConfig(8083, 2);
        availableNodes().clear();
        availableNodes().addAll(List.of(node1Config, node2Config, node3Config, node4Config));

        final AtomicBoolean isFirstInvocation = new AtomicBoolean(true);
        final long earliestBlock = 100;
        final long latestBlock = 250;
        doReturn(earliestBlock).when(bufferService).getEarliestAvailableBlockNumber();
        doReturn(latestBlock).when(bufferService).getLastBlockNumberProduced();
        doAnswer(invocation -> {
                    if (isFirstInvocation.compareAndSet(true, false)) {
                        throw new InterruptedException();
                    } else {
                        final List<RetrieveBlockNodeStatusTask> tasks = invocation.getArgument(0);
                        final List<CompletableFuture<BlockNodeStatus>> futures = new ArrayList<>();
                        for (int i = 0; i < tasks.size(); ++i) {
                            futures.add(completedFuture(reachable(5, latestBlock - 5)));
                        }

                        return futures;
                    }
                })
                .when(blockingIoExecutor)
                .invokeAll(anyList(), anyLong(), any(TimeUnit.class));

        assertThat(connectionManager.selectNewBlockNodeForStreaming(false)).isTrue();

        final ArgumentCaptor<Runnable> scheduledExecCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduledExecutor).schedule(scheduledExecCaptor.capture(), anyLong(), any(TimeUnit.class));

        assertThat(scheduledExecCaptor.getAllValues()).hasSize(1);
        final Runnable task = scheduledExecCaptor.getValue();
        assertThat(task).isNotNull().isInstanceOf(BlockNodeConnectionTask.class);
        final BlockNodeStreamingConnection connection = connectionFromTask((BlockNodeConnectionTask) task);
        // the node we've scheduled to connect to should be a node from priority group 2 (node 3 or 4)
        assertThat(connection.configuration()).isIn(node3Config, node4Config);

        verify(bufferService).getLastBlockNumberProduced();
        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(blockingIoExecutor, times(2)).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verifyNoMoreInteractions(scheduledExecutor);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoInteractions(metrics);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_executorError() throws Exception {
        final BlockNodeConfiguration node1Config = newBlockNodeConfig(8080, 1);
        final BlockNodeConfiguration node2Config = newBlockNodeConfig(8081, 1);
        final BlockNodeConfiguration node3Config = newBlockNodeConfig(8082, 2);
        final BlockNodeConfiguration node4Config = newBlockNodeConfig(8083, 2);
        availableNodes().clear();
        availableNodes().addAll(List.of(node1Config, node2Config, node3Config, node4Config));

        final AtomicBoolean isFirstInvocation = new AtomicBoolean(true);
        final long earliestBlock = 100;
        final long latestBlock = 250;
        doReturn(earliestBlock).when(bufferService).getEarliestAvailableBlockNumber();
        doReturn(latestBlock).when(bufferService).getLastBlockNumberProduced();
        doAnswer(invocation -> {
                    if (isFirstInvocation.compareAndSet(true, false)) {
                        throw new RuntimeException("watch out!");
                    } else {
                        final List<RetrieveBlockNodeStatusTask> tasks = invocation.getArgument(0);
                        final List<CompletableFuture<BlockNodeStatus>> futures = new ArrayList<>();
                        for (int i = 0; i < tasks.size(); ++i) {
                            futures.add(completedFuture(reachable(5, latestBlock - 5)));
                        }

                        return futures;
                    }
                })
                .when(blockingIoExecutor)
                .invokeAll(anyList(), anyLong(), any(TimeUnit.class));

        assertThat(connectionManager.selectNewBlockNodeForStreaming(false)).isTrue();

        final ArgumentCaptor<Runnable> scheduledExecCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduledExecutor).schedule(scheduledExecCaptor.capture(), anyLong(), any(TimeUnit.class));

        assertThat(scheduledExecCaptor.getAllValues()).hasSize(1);
        final Runnable task = scheduledExecCaptor.getValue();
        assertThat(task).isNotNull().isInstanceOf(BlockNodeConnectionTask.class);
        final BlockNodeStreamingConnection connection = connectionFromTask((BlockNodeConnectionTask) task);
        // the node we've scheduled to connect to should be a node from priority group 2 (node 3 or 4)
        assertThat(connection.configuration()).isIn(node3Config, node4Config);

        verify(bufferService).getLastBlockNumberProduced();
        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(blockingIoExecutor, times(2)).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verifyNoMoreInteractions(scheduledExecutor);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoInteractions(metrics);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_candidateAndTaskMismatch() throws Exception {
        final BlockNodeConfiguration node1Config = newBlockNodeConfig(8080, 1);
        final BlockNodeConfiguration node2Config = newBlockNodeConfig(8081, 1);
        final BlockNodeConfiguration node3Config = newBlockNodeConfig(8082, 2);
        final BlockNodeConfiguration node4Config = newBlockNodeConfig(8083, 2);
        availableNodes().clear();
        availableNodes().addAll(List.of(node1Config, node2Config, node3Config, node4Config));

        final AtomicBoolean isFirstInvocation = new AtomicBoolean(true);
        final long earliestBlock = 100;
        final long latestBlock = 250;
        doReturn(earliestBlock).when(bufferService).getEarliestAvailableBlockNumber();
        doReturn(latestBlock).when(bufferService).getLastBlockNumberProduced();
        doAnswer(invocation -> {
                    if (isFirstInvocation.compareAndSet(true, false)) {
                        // return an empty list to trigger a mismatch since the number of candidates (2)
                        // will be different than the number of tasks (0)
                        return List.of();
                    } else {
                        final List<RetrieveBlockNodeStatusTask> tasks = invocation.getArgument(0);
                        final List<CompletableFuture<BlockNodeStatus>> futures = new ArrayList<>();
                        for (int i = 0; i < tasks.size(); ++i) {
                            futures.add(completedFuture(reachable(5, latestBlock - 5)));
                        }

                        return futures;
                    }
                })
                .when(blockingIoExecutor)
                .invokeAll(anyList(), anyLong(), any(TimeUnit.class));

        assertThat(connectionManager.selectNewBlockNodeForStreaming(false)).isTrue();

        final ArgumentCaptor<Runnable> scheduledExecCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduledExecutor).schedule(scheduledExecCaptor.capture(), anyLong(), any(TimeUnit.class));

        assertThat(scheduledExecCaptor.getAllValues()).hasSize(1);
        final Runnable task = scheduledExecCaptor.getValue();
        assertThat(task).isNotNull().isInstanceOf(BlockNodeConnectionTask.class);
        final BlockNodeStreamingConnection connection = connectionFromTask((BlockNodeConnectionTask) task);
        // the node we've scheduled to connect to should be a node from priority group 2 (node 3 or 4)
        assertThat(connection.configuration()).isIn(node3Config, node4Config);

        verify(bufferService).getLastBlockNumberProduced();
        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(blockingIoExecutor, times(2)).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verifyNoMoreInteractions(scheduledExecutor);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoInteractions(metrics);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_error() throws Exception {
        final BlockNodeConfiguration node1Config = newBlockNodeConfig(8080, 1);
        final BlockNodeConfiguration node2Config = newBlockNodeConfig(8081, 1);
        final BlockNodeConfiguration node3Config = newBlockNodeConfig(8082, 2);
        final BlockNodeConfiguration node4Config = newBlockNodeConfig(8083, 2);
        availableNodes().clear();
        availableNodes().addAll(List.of(node1Config, node2Config, node3Config, node4Config));

        final long earliestBlock = 100;
        final long latestBlock = 250;
        doReturn(earliestBlock).when(bufferService).getEarliestAvailableBlockNumber();
        doReturn(latestBlock).when(bufferService).getLastBlockNumberProduced();

        doAnswer(invocation -> {
                    final List<RetrieveBlockNodeStatusTask> tasks = invocation.getArgument(0);
                    final List<CompletableFuture<BlockNodeStatus>> futures = new ArrayList<>();
                    for (int i = 0; i < tasks.size(); ++i) {
                        final BlockNodeServiceConnection connection =
                                (BlockNodeServiceConnection) nodeStatusTaskConnectionHandle.get(tasks.get(i));
                        final BlockNodeConfiguration taskNodeConfig = connection.configuration();
                        // fail all priority 1 nodes and one of the priority 2 nodes
                        if (node4Config.streamingPort() == taskNodeConfig.streamingPort()) {
                            futures.add(completedFuture(reachable(10, earliestBlock + 25)));
                        } else {
                            futures.add(failedFuture(new RuntimeException("kaboom!")));
                        }
                    }

                    return futures;
                })
                .when(blockingIoExecutor)
                .invokeAll(anyList(), anyLong(), any(TimeUnit.class));

        assertThat(connectionManager.selectNewBlockNodeForStreaming(false)).isTrue();

        final ArgumentCaptor<Runnable> scheduledExecCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduledExecutor).schedule(scheduledExecCaptor.capture(), anyLong(), any(TimeUnit.class));

        assertThat(scheduledExecCaptor.getAllValues()).hasSize(1);
        final Runnable task = scheduledExecCaptor.getValue();
        assertThat(task).isNotNull().isInstanceOf(BlockNodeConnectionTask.class);
        final BlockNodeStreamingConnection connection = connectionFromTask((BlockNodeConnectionTask) task);
        // the node we've scheduled to connect to should be the non-failing task for node 4
        assertThat(connection.configuration()).isEqualTo(node4Config);

        verify(bufferService, times(2)).getLastBlockNumberProduced();
        verify(bufferService, times(2)).getEarliestAvailableBlockNumber();
        verify(blockingIoExecutor, times(2)).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verifyNoMoreInteractions(scheduledExecutor);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoInteractions(metrics);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_allBlockNodesHaveNoBlocks() throws Exception {
        /*
        This test validates that when all the block nodes respond with -1 as the latest block, we treat it as a wildcard
        meaning the block node will accept whatever we send it - unless later the block node tells us something
        different via a response message like SkipBlock or BehindPublisher. Thus, block nodes with no known latest block
        are treated as viable candidates to connect to.
         */
        final BlockNodeConfiguration node1Config = newBlockNodeConfig(8080, 1);
        final BlockNodeConfiguration node2Config = newBlockNodeConfig(8081, 1);
        final BlockNodeConfiguration node3Config = newBlockNodeConfig(8082, 1);
        final BlockNodeConfiguration node4Config = newBlockNodeConfig(8083, 2);
        availableNodes().clear();
        availableNodes().addAll(List.of(node1Config, node2Config, node3Config, node4Config));

        final long earliestBlock = 100;
        final long latestBlock = 250;
        doReturn(earliestBlock).when(bufferService).getEarliestAvailableBlockNumber();
        doReturn(latestBlock).when(bufferService).getLastBlockNumberProduced();
        doAnswer(invocation -> {
                    final List<RetrieveBlockNodeStatusTask> tasks = invocation.getArgument(0);
                    final List<CompletableFuture<BlockNodeStatus>> futures = new ArrayList<>();
                    for (int i = 0; i < tasks.size(); ++i) {
                        // mark all nodes as reachable but with -1 as the latest block
                        futures.add(completedFuture(reachable(5, -1)));
                    }

                    return futures;
                })
                .when(blockingIoExecutor)
                .invokeAll(anyList(), anyLong(), any(TimeUnit.class));

        assertThat(connectionManager.selectNewBlockNodeForStreaming(false)).isTrue();

        final ArgumentCaptor<Runnable> scheduledExecCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduledExecutor).schedule(scheduledExecCaptor.capture(), anyLong(), any(TimeUnit.class));

        assertThat(scheduledExecCaptor.getAllValues()).hasSize(1);
        final Runnable task = scheduledExecCaptor.getValue();
        assertThat(task).isNotNull().isInstanceOf(BlockNodeConnectionTask.class);
        final BlockNodeStreamingConnection connection = connectionFromTask((BlockNodeConnectionTask) task);
        // the node we've scheduled to connect to should be a node from priority group 1 (node 1, 2, or 3)
        assertThat(connection.configuration()).isIn(node1Config, node2Config, node3Config);

        // since one of the nodes in priority group 1 was chosen, we should only interact with the buffer service once
        verify(bufferService, times(1)).getLastBlockNumberProduced();
        verify(bufferService, times(1)).getEarliestAvailableBlockNumber();
        verify(blockingIoExecutor).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoInteractions(metrics);
        verifyNoMoreInteractions(scheduledExecutor);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_noBufferedBlocks() throws Exception {
        /*
        This test validates a scenario in which the consensus node has no blocks in the buffer. This may be due to a
        restart in which no blocks were previously persisted (e.g. they were all acked) or it may due to the node being
        initialized for the first time. In such a scenario, any reachable block node will be considered a candidate to
        connect to.
         */
        final BlockNodeConfiguration node1Config = newBlockNodeConfig(8080, 1);
        final BlockNodeConfiguration node2Config = newBlockNodeConfig(8081, 1);
        final BlockNodeConfiguration node3Config = newBlockNodeConfig(8082, 1);
        final BlockNodeConfiguration node4Config = newBlockNodeConfig(8083, 2);
        availableNodes().clear();
        availableNodes().addAll(List.of(node1Config, node2Config, node3Config, node4Config));

        doReturn(-1L).when(bufferService).getEarliestAvailableBlockNumber();
        doReturn(-1L).when(bufferService).getLastBlockNumberProduced();
        doAnswer(invocation -> {
                    // mark all nodes as reachable
                    final List<RetrieveBlockNodeStatusTask> tasks = invocation.getArgument(0);
                    final List<CompletableFuture<BlockNodeStatus>> futures = new ArrayList<>();

                    for (int i = 0; i < tasks.size(); ++i) {
                        final BlockNodeServiceConnection connection =
                                (BlockNodeServiceConnection) nodeStatusTaskConnectionHandle.get(tasks.get(i));
                        final BlockNodeConfiguration taskNodeConfig = connection.configuration();
                        if (node1Config.streamingPort() == taskNodeConfig.streamingPort()) {
                            futures.add(completedFuture(reachable(10, 10)));
                        } else if (node2Config.streamingPort() == taskNodeConfig.streamingPort()) {
                            futures.add(completedFuture(reachable(10, -1)));
                        } else if (node3Config.streamingPort() == taskNodeConfig.streamingPort()) {
                            futures.add(completedFuture(reachable(10, 25)));
                        } else if (node4Config.streamingPort() == taskNodeConfig.streamingPort()) {
                            futures.add(completedFuture(reachable(10, 11)));
                        } else {
                            throw new IllegalStateException("Unexpected config: " + taskNodeConfig);
                        }
                    }

                    return futures;
                })
                .when(blockingIoExecutor)
                .invokeAll(anyList(), anyLong(), any(TimeUnit.class));

        assertThat(connectionManager.selectNewBlockNodeForStreaming(false)).isTrue();

        final ArgumentCaptor<Runnable> scheduledExecCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduledExecutor).schedule(scheduledExecCaptor.capture(), anyLong(), any(TimeUnit.class));

        assertThat(scheduledExecCaptor.getAllValues()).hasSize(1);
        final Runnable task = scheduledExecCaptor.getValue();
        assertThat(task).isNotNull().isInstanceOf(BlockNodeConnectionTask.class);
        final BlockNodeStreamingConnection connection = connectionFromTask((BlockNodeConnectionTask) task);
        // the node we've scheduled to connect to should be a node from priority group 1 (node 1, 2, or 3)
        assertThat(connection.configuration()).isIn(node1Config, node2Config, node3Config);

        // since one of the nodes in priority group 1 was chosen, we should only interact with the buffer service once
        verify(bufferService, times(1)).getLastBlockNumberProduced();
        verify(bufferService, times(1)).getEarliestAvailableBlockNumber();
        verify(blockingIoExecutor).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoInteractions(metrics);
        verifyNoMoreInteractions(scheduledExecutor);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_nullResponse() throws Exception {
        final BlockNodeConfiguration node1Config = newBlockNodeConfig(8080, 1);
        final BlockNodeConfiguration node2Config = newBlockNodeConfig(8081, 2);
        final BlockNodeConfiguration node3Config = newBlockNodeConfig(8082, 2);
        final BlockNodeConfiguration node4Config = newBlockNodeConfig(8083, 2);
        availableNodes().clear();
        availableNodes().addAll(List.of(node1Config, node2Config, node3Config, node4Config));

        doReturn(10L).when(bufferService).getEarliestAvailableBlockNumber();
        doReturn(25L).when(bufferService).getLastBlockNumberProduced();
        doAnswer(invocation -> {
                    // return a successful null for node 1 and then successful, non-null response for the rest of the
                    // nodes
                    final List<RetrieveBlockNodeStatusTask> tasks = invocation.getArgument(0);
                    final List<CompletableFuture<BlockNodeStatus>> futures = new ArrayList<>();
                    for (int i = 0; i < tasks.size(); ++i) {
                        final BlockNodeServiceConnection connection =
                                (BlockNodeServiceConnection) nodeStatusTaskConnectionHandle.get(tasks.get(i));
                        final BlockNodeConfiguration taskNodeConfig = connection.configuration();
                        if (node1Config.streamingPort() == taskNodeConfig.streamingPort()) {
                            futures.add(completedFuture(null));
                        } else if (node2Config.streamingPort() == taskNodeConfig.streamingPort()
                                || node3Config.streamingPort() == taskNodeConfig.streamingPort()
                                || node4Config.streamingPort() == taskNodeConfig.streamingPort()) {
                            futures.add(completedFuture(reachable(10, 15)));
                        } else {
                            throw new IllegalStateException("Unexpected config: " + taskNodeConfig);
                        }
                    }

                    return futures;
                })
                .when(blockingIoExecutor)
                .invokeAll(anyList(), anyLong(), any(TimeUnit.class));

        assertThat(connectionManager.selectNewBlockNodeForStreaming(false)).isTrue();

        final ArgumentCaptor<Runnable> scheduledExecCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduledExecutor).schedule(scheduledExecCaptor.capture(), anyLong(), any(TimeUnit.class));

        assertThat(scheduledExecCaptor.getAllValues()).hasSize(1);
        final Runnable task = scheduledExecCaptor.getValue();
        assertThat(task).isNotNull().isInstanceOf(BlockNodeConnectionTask.class);
        final BlockNodeStreamingConnection connection = connectionFromTask((BlockNodeConnectionTask) task);
        // the node we've scheduled to connect to should be a node from priority group 2 (node 2, 3, or 3)
        assertThat(connection.configuration()).isIn(node2Config, node3Config, node4Config);

        // since both priority groups will be processed, we will interact with the buffer twice and submit 4 tasks
        verify(bufferService, times(2)).getLastBlockNumberProduced();
        verify(bufferService, times(2)).getEarliestAvailableBlockNumber();
        verify(blockingIoExecutor, times(2)).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoInteractions(metrics);
        verifyNoMoreInteractions(scheduledExecutor);
    }

    @Test
    void testRetrieveBlockNodeStatusTask_nullConfig() {
        assertThatThrownBy(() -> connectionManager.new RetrieveBlockNodeStatusTask(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Node configuration is required");
    }

    @Test
    void testRetrieveBlockNodeStatusTask() {
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig(8080, 1);
        final BlockNodeStatus expectedStatus = reachable(10, 100);

        try (final MockedConstruction<BlockNodeServiceConnection> mockedSvcConn =
                mockConstruction(BlockNodeServiceConnection.class)) {
            final RetrieveBlockNodeStatusTask task = connectionManager.new RetrieveBlockNodeStatusTask(nodeConfig);
            final BlockNodeServiceConnection connection =
                    mockedSvcConn.constructed().getFirst();

            doReturn(expectedStatus).when(connection).getBlockNodeStatus();

            final BlockNodeStatus status = task.call();

            assertThat(status).isEqualTo(expectedStatus);
            assertThat(mockedSvcConn.constructed()).hasSize(1);

            verify(connection).initialize();
            verify(connection).getBlockNodeStatus();
            verify(connection).close();
            verifyNoMoreInteractions(connection);
        }
    }

    // Utilities

    private void createConnectionManager(final List<BlockNodeConfiguration> blockNodes) {
        // Create a custom config provider with the specified block nodes
        final ConfigProvider configProvider = createConfigProvider(createDefaultConfigProvider()
                .withValue("blockNode.blockNodeConnectionFileDir", "/tmp/non-existent-test-dir-" + System.nanoTime()));

        // Create the manager
        connectionManager =
                new BlockNodeConnectionManager(configProvider, bufferService, metrics, blockingIoExecutorSupplier);

        // Inject the mock executor service to control scheduling in tests
        sharedExecutorServiceHandle.set(connectionManager, scheduledExecutor);

        // Set the available nodes using reflection
        try {
            final List<BlockNodeConfiguration> availableNodes = availableNodes();
            availableNodes.clear();
            availableNodes.addAll(blockNodes);
        } catch (final Throwable t) {
            throw new RuntimeException("Failed to set available nodes", t);
        }
    }

    private BlockNodeStreamingConnection connectionFromTask(@NonNull final BlockNodeConnectionTask task) {
        requireNonNull(task);
        return (BlockNodeStreamingConnection) connectivityTaskConnectionHandle.get(task);
    }

    @SuppressWarnings("unchecked")
    private Map<BlockNodeConfiguration, RetryState> retryStates() {
        return (Map<BlockNodeConfiguration, RetryState>) retryStatesHandle.get(connectionManager);
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<BlockNodeStreamingConnection> activeConnection() {
        return (AtomicReference<BlockNodeStreamingConnection>) activeConnectionRefHandle.get(connectionManager);
    }

    @SuppressWarnings("unchecked")
    private List<BlockNodeConfiguration> availableNodes() {
        return (List<BlockNodeConfiguration>) availableNodesHandle.get(connectionManager);
    }

    @SuppressWarnings("unchecked")
    private Map<BlockNodeConfiguration, BlockNodeStreamingConnection> connections() {
        return (Map<BlockNodeConfiguration, BlockNodeStreamingConnection>) connectionsHandle.get(connectionManager);
    }

    private AtomicBoolean isActiveFlag() {
        return (AtomicBoolean) isManagerActiveHandle.get(connectionManager);
    }

    @SuppressWarnings("unchecked")
    private Map<BlockNodeConfig, BlockNodeStats> nodeStats() {
        return (Map<BlockNodeConfig, BlockNodeStats>) nodeStatsHandle.get(connectionManager);
    }

    private void invoke_closeAllConnections() {
        try {
            closeAllConnectionsHandle.invoke(connectionManager);
        } catch (final Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private void invoke_refreshAvailableBlockNodes() {
        try {
            refreshAvailableBlockNodesHandle.invoke(connectionManager);
        } catch (final Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<BlockNodeConfiguration> invoke_extractBlockNodesConfigurations(final String path) {
        try {
            return (List<BlockNodeConfiguration>) extractBlockNodesConfigurationsHandle.invoke(connectionManager, path);
        } catch (final Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private void awaitCondition(final BooleanSupplier condition, final long timeoutMs) {
        final long start = System.currentTimeMillis();
        while (!condition.getAsBoolean() && (System.currentTimeMillis() - start) < timeoutMs) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private void resetMocks() {
        reset(bufferService, metrics, scheduledExecutor);
    }

    /**
     * Stops the config watcher thread to prevent it from detecting file changes and triggering
     * additional refreshAvailableBlockNodes() calls that could race with test assertions.
     */
    @SuppressWarnings("unchecked")
    private void stopConfigWatcher() {
        final AtomicReference<Thread> watcherThreadRef =
                (AtomicReference<Thread>) configWatcherThreadRef.get(connectionManager);
        final Thread watcherThread = watcherThreadRef.getAndSet(null);
        if (watcherThread != null) {
            watcherThread.interrupt();
            try {
                watcherThread.join(1000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Shuts down the shared executor service to prevent background tasks from running
     * and potentially modifying state that tests are asserting on.
     */
    private void shutdownSharedExecutor() {
        final ScheduledExecutorService executor =
                (ScheduledExecutorService) sharedExecutorServiceHandle.get(connectionManager);
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void useStreamingDisabledManager() {
        // Recreate connectionManager with streaming disabled (writerMode=FILE)
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.writerMode", "FILE")
                .withValue(
                        "blockNode.blockNodeConnectionFileDir",
                        Objects.requireNonNull(BlockNodeCommunicationTestBase.class
                                        .getClassLoader()
                                        .getResource("bootstrap/"))
                                .getPath())
                .getOrCreateConfig();
        final ConfigProvider disabledProvider = () -> new VersionedConfigImpl(config, 1L);
        connectionManager =
                new BlockNodeConnectionManager(disabledProvider, bufferService, metrics, blockingIoExecutorSupplier);
        sharedExecutorServiceHandle.set(connectionManager, scheduledExecutor);
    }

    private static <T> CompletableFuture<T> createSleepingFuture() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(30_000);
            } catch (final InterruptedException e) {
                throw new RuntimeException(e);
            }

            return null;
        });
    }
}
