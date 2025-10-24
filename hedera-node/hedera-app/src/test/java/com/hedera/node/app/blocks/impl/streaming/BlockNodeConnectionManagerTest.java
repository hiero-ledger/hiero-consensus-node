// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnection.ConnectionState;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager.BlockNodeConnectionTask;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager.RetryState;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.internal.network.BlockNodeConfig;
import com.hedera.node.internal.network.BlockNodeConnectionInfo;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
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
    private static final MethodHandle closeAllConnectionsHandle;
    private static final MethodHandle refreshAvailableBlockNodesHandle;
    private static final MethodHandle extractBlockNodesConfigurationsHandle;

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
                    .findVarHandle(BlockNodeConnectionTask.class, "connection", BlockNodeConnection.class);
            nodeStatsHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "nodeStats", Map.class);
            retryStatesHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "retryStates", Map.class);
            sharedExecutorServiceHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(
                            BlockNodeConnectionManager.class, "sharedExecutorService", ScheduledExecutorService.class);
            blockNodeConfigDirectoryHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "blockNodeConfigDirectory", Path.class);

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
        executorService = mock(ScheduledExecutorService.class);

        connectionManager = new BlockNodeConnectionManager(configProvider, bufferService, metrics);
        replaceLocalhostWithPbjUnitTestHost();

        // Inject mock executor to control scheduling behavior in tests.
        // Tests that call start() will have this overwritten by a real executor.
        sharedExecutorServiceHandle.set(connectionManager, executorService);

        // Clear any nodes that might have been loaded
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

        resetMocks();
    }

    @Test
    void testRescheduleAndSelectNode() {
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
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
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
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
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        doReturn(nodeConfig).when(connection).getNodeConfig();

        final TestConfigBuilder configBuilder = createDefaultConfigProvider()
                .withValue("blockNode.blockNodeConnectionFileDir", "/tmp/non-existent-test-dir-" + System.nanoTime())
                .withValue("blockNode.protocolExpBackoffTimeframeReset", "1s");
        final ConfigProvider configProvider = createConfigProvider(configBuilder);

        connectionManager = new BlockNodeConnectionManager(configProvider, bufferService, metrics);
        replaceLocalhostWithPbjUnitTestHost();
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
    void testShutdown() {
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        // add some fake connections
        final BlockNodeConfig node1Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        final BlockNodeConnection node1Conn = mock(BlockNodeConnection.class);
        final BlockNodeConfig node2Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 2);
        final BlockNodeConnection node2Conn = mock(BlockNodeConnection.class);
        final BlockNodeConfig node3Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8082, 3);
        final BlockNodeConnection node3Conn = mock(BlockNodeConnection.class);
        connections.put(node1Config, node1Conn);
        connections.put(node2Config, node2Conn);
        connections.put(node3Config, node3Conn);

        // introduce a failure on one of the connection closes to ensure the shutdown process does not fail prematurely
        doThrow(new RuntimeException("oops, I did it again")).when(node2Conn).close(true);

        final AtomicBoolean isActive = isActiveFlag();
        isActive.set(true);

        connectionManager.shutdown();

        final AtomicReference<BlockNodeConnection> activeConnRef = activeConnection();
        assertThat(activeConnRef).hasNullValue();

        assertThat(connections).isEmpty();
        assertThat(isActive).isFalse();

        final Map<BlockNodeConfig, BlockNodeStats> nodeStats = nodeStats();
        assertThat(nodeStats).isEmpty();

        // calling shutdown again would only potentially shutdown the config watcher
        // and not shutdown the buffer service again
        connectionManager.shutdown();

        verify(node1Conn).close(true);
        verify(node2Conn).close(true);
        verify(node3Conn).close(true);
        verify(bufferService).shutdown();
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

        verifyNoInteractions(executorService);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testStartup_noNodesAvailable() {
        final AtomicBoolean isActive = isActiveFlag();
        isActive.set(false);

        final List<BlockNodeConfig> availableNodes = availableNodes();
        availableNodes.clear(); // remove all available nodes from config

        assertThat(isActive).isFalse();

        verifyNoInteractions(executorService);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testStartup() throws IOException {
        final AtomicBoolean isActive = isActiveFlag();
        isActive.set(false);

        final Path file = tempDir.resolve("block-nodes.json");
        final List<BlockNodeConfig> availableNodes = new ArrayList<>();
        availableNodes.add(newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1));
        availableNodes.add(newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 1));
        availableNodes.add(newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8082, 2));
        availableNodes.add(newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8083, 3));
        availableNodes.add(newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8084, 3));
        final BlockNodeConnectionInfo connectionInfo = new BlockNodeConnectionInfo(availableNodes);
        final String valid = BlockNodeConnectionInfo.JSON.toJSON(connectionInfo);
        Files.writeString(
                file, valid, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        connectionManager.start();

        // start() creates a real executor, replacing the mock.
        // Verify that a connection was created and scheduled.
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        assertThat(connections).hasSize(1);

        final BlockNodeConnection connection = connections.values().iterator().next();
        final BlockNodeConfig nodeConfig = connection.getNodeConfig();

        // verify we are trying to connect to one of the priority 1 nodes
        assertThat(nodeConfig.priority()).isEqualTo(1);
        assertThat(connection.getConnectionState()).isEqualTo(ConnectionState.UNINITIALIZED);

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

        final BlockNodeConfig node1Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        final BlockNodeConnection node1Conn = mock(BlockNodeConnection.class);
        final BlockNodeConfig node2Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 2);
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

        final BlockNodeConfig node1Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 1);
        final BlockNodeConfig node2Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8082, 2);
        final BlockNodeConnection node2Conn = mock(BlockNodeConnection.class);
        final BlockNodeConfig node3Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8083, 3);

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

        final BlockNodeConfig node1Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        final BlockNodeConnection node1Conn = mock(BlockNodeConnection.class);
        final BlockNodeConfig node2Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 2);
        final BlockNodeConnection node2Conn = mock(BlockNodeConnection.class);
        final BlockNodeConfig node3Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8082, 3);

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

        final BlockNodeConfig node1Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        final BlockNodeConnection node1Conn = mock(BlockNodeConnection.class);
        final BlockNodeConfig node2Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 2);
        final BlockNodeConnection node2Conn = mock(BlockNodeConnection.class);
        final BlockNodeConfig node3Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8082, 2);
        final BlockNodeConfig node4Config = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8083, 3);

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
    void testConnectionTask_managerNotActive() {
        final AtomicBoolean isManagerActive = isActiveFlag();
        isManagerActive.set(false);

        final BlockNodeConnection connection = mock(BlockNodeConnection.class);

        connectionManager.new BlockNodeConnectionTask(connection, Duration.ofSeconds(1), false).run();

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
        final BlockNodeConfig activeConnectionConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        doReturn(activeConnectionConfig).when(activeConnection).getNodeConfig();
        activeConnectionRef.set(activeConnection);

        final BlockNodeConnection newConnection = mock(BlockNodeConnection.class);
        final BlockNodeConfig newConnectionConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 2);
        doReturn(newConnectionConfig).when(newConnection).getNodeConfig();

        connectionManager.new BlockNodeConnectionTask(newConnection, Duration.ofSeconds(1), false).run();

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
        final BlockNodeConfig activeConnectionConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        doReturn(activeConnectionConfig).when(activeConnection).getNodeConfig();
        activeConnectionRef.set(activeConnection);

        final BlockNodeConnection newConnection = mock(BlockNodeConnection.class);
        final BlockNodeConfig newConnectionConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 2);
        doReturn(newConnectionConfig).when(newConnection).getNodeConfig();

        connectionManager.new BlockNodeConnectionTask(newConnection, Duration.ofSeconds(1), true).run();

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
    void testConnectionTask_connectionUninitialized_withActiveLowerPriorityConnection() {
        // also put an active connection into the state, but let it have a lower priority so the new connection
        // takes its place as the active one
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        final BlockNodeConnection activeConnection = mock(BlockNodeConnection.class);
        final BlockNodeConfig activeConnectionConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 2);
        doReturn(activeConnectionConfig).when(activeConnection).getNodeConfig();
        activeConnectionRef.set(activeConnection);
        isActiveFlag().set(true);

        final BlockNodeConnection newConnection = mock(BlockNodeConnection.class);
        final BlockNodeConfig newConnectionConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 1);
        doReturn(newConnectionConfig).when(newConnection).getNodeConfig();

        connectionManager.new BlockNodeConnectionTask(newConnection, Duration.ofSeconds(1), false).run();

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

        connectionManager.new BlockNodeConnectionTask(activeConnection, Duration.ofSeconds(1), false).run();

        verifyNoInteractions(activeConnection);
        verifyNoInteractions(executorService);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConnectionTask_noActiveConnection() {
        isActiveFlag().set(true);
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        activeConnectionRef.set(null);

        final BlockNodeConfig newConnectionConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 1);
        final BlockNodeConnection newConnection = mock(BlockNodeConnection.class);
        doReturn(newConnectionConfig).when(newConnection).getNodeConfig();

        connectionManager.new BlockNodeConnectionTask(newConnection, Duration.ofSeconds(1), false).run();

        assertThat(activeConnectionRef).hasValue(newConnection);

        verify(newConnection).createRequestPipeline();
        verify(newConnection).updateConnectionState(ConnectionState.ACTIVE);
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
        final BlockNodeConfig activeConnectionConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 2);
        doReturn(activeConnectionConfig).when(activeConnection).getNodeConfig();
        doThrow(new RuntimeException("why does this always happen to me"))
                .when(activeConnection)
                .close(true);
        activeConnectionRef.set(activeConnection);

        final BlockNodeConnection newConnection = mock(BlockNodeConnection.class);
        final BlockNodeConfig newConnectionConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 1);
        doReturn(newConnectionConfig).when(newConnection).getNodeConfig();

        connectionManager.new BlockNodeConnectionTask(newConnection, Duration.ofSeconds(1), false).run();

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
                connectionManager.new BlockNodeConnectionTask(connection, Duration.ZERO, false);

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
                connectionManager.new BlockNodeConnectionTask(connection, Duration.ofSeconds(10), false);

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

        final BlockNodeConfig nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
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
                connectionManager.new BlockNodeConnectionTask(connection, Duration.ofSeconds(10), false);

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
    void testScheduleAndSelectNewNode_streamingDisabled() {
        useStreamingDisabledManager();
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);

        connectionManager.rescheduleConnection(connection, Duration.ZERO, null, true);

        verifyNoInteractions(connection);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testShutdown_streamingDisabled() {
        useStreamingDisabledManager();

        connectionManager.shutdown();

        verifyNoInteractions(bufferService);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testStartup_streamingDisabled() {
        useStreamingDisabledManager();

        connectionManager.start();

        final AtomicBoolean isManagerActive = isActiveFlag();
        assertThat(isManagerActive).isFalse();

        verifyNoInteractions(bufferService);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testSelectNewBlockNodeForStreaming_streamingDisabled() {
        useStreamingDisabledManager();

        connectionManager.selectNewBlockNodeForStreaming(false);

        verifyNoInteractions(bufferService);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConstructor_streamingDisabled() {
        useStreamingDisabledManager();

        final List<BlockNodeConfig> availableNodes = availableNodes();
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

        connectionManager = new BlockNodeConnectionManager(configProvider, bufferService, metrics);

        // Verify that the manager was created but has no available nodes
        final List<BlockNodeConfig> availableNodes = availableNodes();
        assertThat(availableNodes).isEmpty();
    }

    @Test
    void testRestartConnection() {
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
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

        connectionManager = new BlockNodeConnectionManager(configProvider, bufferService, metrics);

        sharedExecutorServiceHandle.set(connectionManager, executorService);

        final List<BlockNodeConfig> availableNodes = availableNodes();
        availableNodes.clear();
        availableNodes.add(newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1));

        reset(executorService);

        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        doReturn(nodeConfig).when(connection).getNodeConfig();

        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        connections.put(nodeConfig, connection);

        connectionManager.rescheduleConnection(connection, Duration.ofSeconds(5), null, true);

        // Verify exactly 1 schedule call was made (only the retry, no new node selection since there's only one node)
        verify(executorService, times(1))
                .schedule(any(BlockNodeConnectionTask.class), eq(5000L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void testConnectionResetsTheStream_streamingDisabled() {
        useStreamingDisabledManager();
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);

        connectionManager.connectionResetsTheStream(connection);

        verifyNoInteractions(connection);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testStart_streamingDisabled() {
        useStreamingDisabledManager();

        connectionManager.start();

        // Verify early return - no interactions with any services
        verifyNoInteractions(bufferService);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);

        // Verify manager remains inactive
        final AtomicBoolean isActive = isActiveFlag();
        assertThat(isActive).isFalse();
    }

    @Test
    void testConnectionTask_runStreamingDisabled() {
        // Streaming disabled via config in constructor setup
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);

        final BlockNodeConnectionTask task =
                connectionManager.new BlockNodeConnectionTask(connection, Duration.ZERO, false);
        task.run();

        verifyNoInteractions(connection);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(executorService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testConnectionTask_metricsIpFailsInvalidAddress() {
        isActiveFlag().set(true);
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        final List<BlockNodeConfig> availableNodes = availableNodes();

        final BlockNodeConfig newConnectionConfig = new BlockNodeConfig("::1", 50211, 1);
        final BlockNodeConnection newConnection = mock(BlockNodeConnection.class);
        doReturn(newConnectionConfig).when(newConnection).getNodeConfig();

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
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        final List<BlockNodeConfig> availableNodes = availableNodes();

        final BlockNodeConfig newConnectionConfig = new BlockNodeConfig("invalid.hostname.for.test", 50211, 1);
        final BlockNodeConnection newConnection = mock(BlockNodeConnection.class);
        doReturn(newConnectionConfig).when(newConnection).getNodeConfig();

        connections.put(newConnectionConfig, newConnection);
        availableNodes.add(newConnectionConfig);

        connectionManager.new BlockNodeConnectionTask(newConnection, Duration.ZERO, false).run();

        verify(metrics).recordActiveConnectionIp(-1L);

        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testHighLatencyTracking() {
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);
        final Instant ackedTime = Instant.now();

        connectionManager.recordBlockProofSent(nodeConfig, 1L, ackedTime);
        connectionManager.recordBlockAckAndCheckLatency(nodeConfig, 1L, ackedTime.plusMillis(30001));

        verify(metrics).recordAcknowledgementLatency(30001);
        verify(metrics).recordHighLatencyEvent();
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void testRecordEndOfStreamAndCheckLimit_streamingDisabled() {
        useStreamingDisabledManager();
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);

        final boolean limitExceeded = connectionManager.recordEndOfStreamAndCheckLimit(nodeConfig, Instant.now());

        assertThat(limitExceeded).isFalse();
    }

    @Test
    void testConnectionResetsTheStream() {
        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(8080, 1);
        doReturn(nodeConfig).when(connection).getNodeConfig();
        availableNodes().add(nodeConfig);

        // Add the connection to the connections map and set it as active
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        final AtomicReference<BlockNodeConnection> activeConnectionRef = activeConnection();
        connections.put(nodeConfig, connection);
        activeConnectionRef.set(connection);

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
    void testRecordEndOfStreamAndCheckLimit_withinLimit() {
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);

        final boolean limitExceeded = connectionManager.recordEndOfStreamAndCheckLimit(nodeConfig, Instant.now());

        assertThat(limitExceeded).isFalse();
    }

    @Test
    void testRecordEndOfStreamAndCheckLimit_exceedsLimit() {
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1);

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
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 0), // Priority 0
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 0), // Priority 0
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8082, 0), // Priority 0
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8083, 0), // Priority 0
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8084, 0), // Priority 0
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8085, 0), // Priority 0
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8086, 0), // Priority 0
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8087, 0), // Priority 0
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8088, 0), // Priority 0
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8089, 0), // Priority 0
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8090, 1), // Priority 1
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8091, 2), // Priority 2
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8092, 2), // Priority 2
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8093, 3), // Priority 3
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8094, 3) // Priority 3
                );

        // Track which priority 0 nodes get selected over multiple runs
        final Set<Integer> selectedNodes = new HashSet<>();

        // Run multiple selections to test randomization
        for (int i = 0; i < 50; i++) {
            // Reset mocks for each iteration
            resetMocks();

            // Configure the manager with these nodes
            createConnectionManager(blockNodes);

            // Perform selection - should only select from priority 0 nodes
            connectionManager.selectNewBlockNodeForStreaming(true);

            // Capture the scheduled task and verify it's connecting to a priority 0 node
            final ArgumentCaptor<BlockNodeConnectionTask> taskCaptor =
                    ArgumentCaptor.forClass(BlockNodeConnectionTask.class);
            verify(executorService, atLeast(1)).schedule(taskCaptor.capture(), anyLong(), any(TimeUnit.class));

            final BlockNodeConnectionTask task = taskCaptor.getValue();
            final BlockNodeConnection connection = connectionFromTask(task);
            final BlockNodeConfig selectedConfig = connection.getNodeConfig();

            // Verify only priority 0 nodes are selected
            assertThat(selectedConfig.priority()).isZero();
            assertThat(selectedConfig.port()).isBetween(8080, 8089);

            // Track which node was selected
            selectedNodes.add(selectedConfig.port());
        }

        // Over 50 runs, we should see at least 2 different priority 0 nodes being selected.
        // This verifies the randomization is working (very unlikely to get same node 50 times).
        // The probability of flakiness is effectively zero - around 10^(-47).
        // Failure of this test means the random selection is not working.
        assertThat(selectedNodes).hasSizeGreaterThan(1);
    }

    @Test
    void testPriorityBasedSelection_onlyLowerPriorityNodesAvailable() {
        // Setup: All priority 0 nodes are unavailable, only lower priority nodes available
        final List<BlockNodeConfig> blockNodes = List.of(
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 1), // Priority 1
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 2), // Priority 2
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8082, 3) // Priority 3
                );

        createConnectionManager(blockNodes);

        // Perform selection
        connectionManager.selectNewBlockNodeForStreaming(true);

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
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 0), // Priority 0 - will be unavailable
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 0), // Priority 0 - available
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8082, 0), // Priority 0 - available
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8083, 1), // Priority 1
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8084, 2) // Priority 2
                );

        createConnectionManager(allBlockNodes);

        // Simulate that node1 is already connected (unavailable)
        final BlockNodeConfig unavailableNode = allBlockNodes.getFirst();
        final BlockNodeConnection existingConnection = mock(BlockNodeConnection.class);

        // Add the existing connection to make node1 unavailable
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        connections.put(unavailableNode, existingConnection);

        // Perform selection
        connectionManager.selectNewBlockNodeForStreaming(true);

        // Verify it still selects from remaining priority 0 nodes
        final ArgumentCaptor<BlockNodeConnectionTask> taskCaptor =
                ArgumentCaptor.forClass(BlockNodeConnectionTask.class);
        verify(executorService, atLeast(1)).schedule(taskCaptor.capture(), anyLong(), any(TimeUnit.class));

        final BlockNodeConnectionTask task = taskCaptor.getValue();
        final BlockNodeConnection connection = connectionFromTask(task);
        final BlockNodeConfig selectedConfig = connection.getNodeConfig();

        assertThat(selectedConfig.priority()).isZero();
        assertThat(selectedConfig.port()).isIn(8081, 8082);
        assertThat(selectedConfig.port()).isNotEqualTo(8080); // Should not select unavailable node
    }

    @Test
    void testPriorityBasedSelection_allPriority0NodesUnavailable() {
        // Setup: All priority 0 nodes are connected, lower priority nodes available
        final List<BlockNodeConfig> allBlockNodes = List.of(
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8080, 0), // Priority 0 - unavailable
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8081, 0), // Priority 0 - unavailable
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8082, 1), // Priority 1 - available
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8083, 1), // Priority 1 - available
                new BlockNodeConfig(PBJ_UNIT_TEST_HOST, 8084, 2) // Priority 2 - available
                );

        createConnectionManager(allBlockNodes);

        // Make all priority 0 nodes unavailable by adding them to connections
        final Map<BlockNodeConfig, BlockNodeConnection> connections = connections();
        for (int i = 0; i < 2; i++) { // First 2 nodes are priority 0
            final BlockNodeConfig unavailableNode = allBlockNodes.get(i);
            final BlockNodeConnection existingConnection = mock(BlockNodeConnection.class);
            connections.put(unavailableNode, existingConnection);
        }

        // Perform selection
        connectionManager.selectNewBlockNodeForStreaming(true);

        // Verify it selects from next highest priority group (priority 1)
        final ArgumentCaptor<BlockNodeConnectionTask> taskCaptor =
                ArgumentCaptor.forClass(BlockNodeConnectionTask.class);
        verify(executorService, atLeast(1)).schedule(taskCaptor.capture(), anyLong(), any(TimeUnit.class));

        final BlockNodeConnectionTask task = taskCaptor.getValue();
        final BlockNodeConnection connection = connectionFromTask(task);
        final BlockNodeConfig selectedConfig = connection.getNodeConfig();

        assertThat(selectedConfig.priority()).isEqualTo(1); // Should fall back to priority 1
        assertThat(selectedConfig.port()).isIn(8082, 8083);
    }

    @Test
    void testCloseAllConnections() {
        final BlockNodeConnection conn = mock(BlockNodeConnection.class);
        connections().put(newBlockNodeConfig(8080, 1), conn);

        invoke_closeAllConnections();

        verify(conn).close(true);
        assertThat(connections()).isEmpty();
    }

    @Test
    void testCloseAllConnections_whenStreamingDisabled() {
        useStreamingDisabledManager();
        // Streaming disabled via config in constructor setup
        final BlockNodeConnection conn = mock(BlockNodeConnection.class);
        connections().put(newBlockNodeConfig(8080, 1), conn);

        invoke_closeAllConnections();

        verify(conn).close(true);
    }

    @Test
    void testRefreshAvailableBlockNodes() {
        final BlockNodeConnection conn = mock(BlockNodeConnection.class);
        final BlockNodeConfig oldNode = newBlockNodeConfig(9999, 1);
        connections().put(oldNode, conn);
        availableNodes().add(oldNode);

        invoke_refreshAvailableBlockNodes();

        // Verify old connection was closed
        verify(conn).close(true);
    }

    @Test
    void testRefreshAvailableBlockNodes_shutsDownExecutorAndReloads_whenValid() {
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

        invoke_refreshAvailableBlockNodes();

        // Old connection closed and executor shut down
        verify(existing).close(true);

        // Available nodes should be reloaded from bootstrap JSON (non-empty)
        assertThat(availableNodes()).isNotEmpty();
    }

    @Test
    void testStartConfigWatcher_reactsToCreateModifyDelete() throws Exception {
        connectionManager.start();
        final Path file = tempDir.resolve("block-nodes.json");
        final List<BlockNodeConfig> configs = new ArrayList<>();
        final BlockNodeConfig config = BlockNodeConfig.newBuilder()
                .address("localhost")
                .port(8080)
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
        awaitCondition(() -> availableNodes().isEmpty(), 2_000);
    }

    @Test
    void testCloseAllConnections_withException() {
        final BlockNodeConnection conn = mock(BlockNodeConnection.class);
        doThrow(new RuntimeException("Close failed")).when(conn).close(true);
        connections().put(newBlockNodeConfig(8080, 1), conn);

        // Should not throw - exceptions are caught and logged
        invoke_closeAllConnections();

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
                connectionManager.new BlockNodeConnectionTask(connection, Duration.ZERO, false);

        task.run();

        // Should return early without creating pipeline
        verify(connection, never()).createRequestPipeline();
    }

    // Utilities

    private void createConnectionManager(final List<BlockNodeConfig> blockNodes) {
        // Create a custom config provider with the specified block nodes
        final ConfigProvider configProvider = createConfigProvider(createDefaultConfigProvider()
                .withValue("blockNode.blockNodeConnectionFileDir", "/tmp/non-existent-test-dir-" + System.nanoTime()));

        // Create the manager
        connectionManager = new BlockNodeConnectionManager(configProvider, bufferService, metrics);

        // Inject the mock executor service to control scheduling in tests
        sharedExecutorServiceHandle.set(connectionManager, executorService);

        // Set the available nodes using reflection
        try {
            final List<BlockNodeConfig> availableNodes = availableNodes();
            availableNodes.clear();
            availableNodes.addAll(blockNodes);
        } catch (final Throwable t) {
            throw new RuntimeException("Failed to set available nodes", t);
        }
    }

    private BlockNodeConnection connectionFromTask(@NonNull final BlockNodeConnectionTask task) {
        requireNonNull(task);
        return (BlockNodeConnection) connectivityTaskConnectionHandle.get(task);
    }

    @SuppressWarnings("unchecked")
    private Map<BlockNodeConfig, RetryState> retryStates() {
        return (Map<BlockNodeConfig, RetryState>) retryStatesHandle.get(connectionManager);
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
    private List<BlockNodeConfig> invoke_extractBlockNodesConfigurations(final String path) {
        try {
            return (List<BlockNodeConfig>) extractBlockNodesConfigurationsHandle.invoke(connectionManager, path);
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
        reset(bufferService, metrics, executorService);
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
        connectionManager = new BlockNodeConnectionManager(disabledProvider, bufferService, metrics);
        sharedExecutorServiceHandle.set(connectionManager, executorService);
    }
}
