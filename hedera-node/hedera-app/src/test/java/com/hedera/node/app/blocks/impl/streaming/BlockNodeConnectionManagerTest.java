// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockNodeConnectionManagerTest extends BlockNodeCommunicationTestBase {

    private static final VarHandle isManagerActiveHandle;
    private static final VarHandle connectionsHandle;
    private static final VarHandle availableNodesHandle;
    private static final VarHandle activeConnectionRefHandle;
    private static final VarHandle connectivityTaskConnectionHandle;
    private static final VarHandle isStreamingEnabledHandle;
    private static final VarHandle nodeStatsHandle;
    private static final VarHandle retryStatesHandle;

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
            isStreamingEnabledHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "isStreamingEnabled", AtomicBoolean.class);
            nodeStatsHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "nodeStats", Map.class);
            retryStatesHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "retryStates", Map.class);
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

//    @Test
//    void testScheduleConnectionAttempt() {
//        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
//        final BlockNodeConfig nodeConfig = newBlockNodeConfig(8080, 1);
//        doReturn(nodeConfig).when(connection).getNodeConfig();
//
//        connectionManager.scheduleConnectionAttempt(connection.getNodeConfig(), Duration.ofSeconds(2), 100L);
//
//        verify(executorService).schedule(any(BlockNodeConnectionTask.class), eq(2_000L), eq(TimeUnit.MILLISECONDS));
//        verifyNoMoreInteractions(connection);
//        verifyNoInteractions(bufferService);
//        verifyNoInteractions(metrics);
//        verifyNoMoreInteractions(executorService);
//    }
//
//    @Test
//    void testScheduleConnectionAttempt_negativeDelay() {
//        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
//        final BlockNodeConfig nodeConfig = newBlockNodeConfig(8080, 1);
//        doReturn(nodeConfig).when(connection).getNodeConfig();
//
//        connectionManager.scheduleConnectionAttempt(connection.getNodeConfig(), Duration.ofSeconds(-2), 100L);
//
//        verify(executorService).schedule(any(BlockNodeConnectionTask.class), eq(0L), eq(TimeUnit.MILLISECONDS));
//        verifyNoInteractions(bufferService);
//        verifyNoInteractions(metrics);
//        verifyNoMoreInteractions(executorService);
//        verifyNoMoreInteractions(connection);
//    }
//
//    @Test
//    void testScheduleConnectionAttempt_failure() {
//        final var logCaptor = new LogCaptor(LogManager.getLogger(BlockNodeConnectionManager.class));
//        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
//        final BlockNodeConfig nodeConfig = newBlockNodeConfig(8080, 1);
//        doReturn(nodeConfig).when(connection).getNodeConfig();
//        doThrow(new RuntimeException("what the..."))
//                .when(executorService)
//                .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
//
//        connectionManager.scheduleConnectionAttempt(connection.getNodeConfig(), Duration.ofSeconds(2), 100L);
//
//        assertThat(logCaptor.warnLogs())
//                .anyMatch(msg -> msg.contains("Failed to schedule connection task for block node."));
//
//        verify(executorService).schedule(any(BlockNodeConnectionTask.class), eq(2_000L), eq(TimeUnit.MILLISECONDS));
//        verify(metrics).recordConnectionClosed();
//
//        verifyNoInteractions(bufferService);
//        verifyNoMoreInteractions(metrics);
//        verifyNoMoreInteractions(executorService);
//        verifyNoMoreInteractions(connection);
//    }

    @Test
    void testShutdown() {
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

        isActive.set(true);

        connectionManager.shutdown();

        final AtomicReference<BlockNodeConnection> activeConnRef = activeConnection();
        assertThat(activeConnRef).hasNullValue();

        assertThat(connections).isEmpty();
        assertThat(isActive).isFalse();

        final Map<BlockNodeConfig, BlockNodeStats> nodeStats = nodeStats();
        assertThat(nodeStats).isEmpty();

        // calling shutdown again should not fail
        connectionManager.shutdown();

        verify(node1Conn).close(true);
        verify(node2Conn).close(true);
        verify(node3Conn).close(true);
        verify(bufferService, times(2)).shutdown();
        verifyNoMoreInteractions(node1Conn);
        verifyNoMoreInteractions(node2Conn);
        verifyNoMoreInteractions(node3Conn);
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
        final AtomicBoolean isActive = isActiveFlag();
        isActive.set(false);

        final List<BlockNodeConfig> availableNodes = availableNodes();
        availableNodes.clear(); // remove all available nodes from config


        final Exception exception = catchException(() -> connectionManager.start());
        assertThat(exception)
                .isInstanceOf(NoBlockNodesAvailableException.class)
                .hasMessage("No block nodes were available to connect to");

        assertThat(isActive).isFalse();

        verifyNoInteractions(executorService);
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testStartup() {
        final AtomicBoolean isActive = isActiveFlag();
        isActive.set(false);

        final List<BlockNodeConfig> availableNodes = availableNodes();
        availableNodes.clear();
        availableNodes.add(newBlockNodeConfig(8080, 1));
        availableNodes.add(newBlockNodeConfig(8081, 1));
        availableNodes.add(newBlockNodeConfig(8082, 2));
        availableNodes.add(newBlockNodeConfig(8083, 3));
        availableNodes.add(newBlockNodeConfig(8084, 3));

        connectionManager.start();

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
        final BlockNodeConfig activeConnectionConfig = newBlockNodeConfig(8080, 1);
        doReturn(activeConnectionConfig).when(activeConnection).getNodeConfig();
        activeConnectionRef.set(activeConnection);

        final BlockNodeConnection newConnection = mock(BlockNodeConnection.class);
        final BlockNodeConfig newConnectionConfig = newBlockNodeConfig(8081, 2);
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
        final BlockNodeConfig activeConnectionConfig = newBlockNodeConfig(8080, 1);
        doReturn(activeConnectionConfig).when(activeConnection).getNodeConfig();
        activeConnectionRef.set(activeConnection);

        final BlockNodeConnection newConnection = mock(BlockNodeConnection.class);
        final BlockNodeConfig newConnectionConfig = newBlockNodeConfig(8081, 2);
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
        final BlockNodeConfig activeConnectionConfig = newBlockNodeConfig(8080, 2);
        doReturn(activeConnectionConfig).when(activeConnection).getNodeConfig();
        activeConnectionRef.set(activeConnection);
        isActiveFlag().set(true);

        final BlockNodeConnection newConnection = mock(BlockNodeConnection.class);
        final BlockNodeConfig newConnectionConfig = newBlockNodeConfig(8081, 1);
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

        final BlockNodeConfig newConnectionConfig = newBlockNodeConfig(8081, 1);
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
        final BlockNodeConfig activeConnectionConfig = newBlockNodeConfig(8080, 2);
        doReturn(activeConnectionConfig).when(activeConnection).getNodeConfig();
        doThrow(new RuntimeException("why does this always happen to me"))
                .when(activeConnection)
                .close(true);
        activeConnectionRef.set(activeConnection);

        final BlockNodeConnection newConnection = mock(BlockNodeConnection.class);
        final BlockNodeConfig newConnectionConfig = newBlockNodeConfig(8081, 1);
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
        doThrow(new RuntimeException("are you seeing this?")).when(connection).createRequestPipeline();

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
        doThrow(new RuntimeException("are you seeing this?")).when(connection).createRequestPipeline();

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
                connectionManager.new BlockNodeConnectionTask(connection, Duration.ofSeconds(10), false);

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

        connectionManager = new BlockNodeConnectionManager(configProvider, bufferService, metrics, executorService);

        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        assertThat(isStreamingEnabled).isFalse();

        final List<BlockNodeConfig> availableNodes = availableNodes();
        assertThat(availableNodes).isEmpty();
    }

    @Test
    void testConstructor_configFileNotFound() {
        // Create a config provider with a non-existent directory to trigger IOException
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.writerMode", "FILE_AND_GRPC")
                .withValue("blockNode.blockNodeConnectionFileDir", "/non/existent/path")
                .getOrCreateConfig();
        final ConfigProvider configProvider = () -> new VersionedConfigImpl(config, 1L);

        // This should throw a RuntimeException due to the missing config file
        final Exception exception = catchException(
                () -> new BlockNodeConnectionManager(configProvider, bufferService, metrics, executorService));

        assertThat(exception)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to read block node configuration from");
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
        // Create a configuration with only one block node
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.writerMode", "FILE_AND_GRPC")
                .withValue(
                        "blockNode.blockNodeConnectionFileDir",
                        Objects.requireNonNull(BlockNodeCommunicationTestBase.class
                                        .getClassLoader()
                                        .getResource("bootstrap/"))
                                .getPath())
                .getOrCreateConfig();
        final ConfigProvider configProvider = () -> new VersionedConfigImpl(config, 1L);

        // Create connection manager with single block node configuration
        connectionManager = new BlockNodeConnectionManager(configProvider, bufferService, metrics, executorService);

        // Get the available nodes and ensure there's only one
        final List<BlockNodeConfig> availableNodes = availableNodes();
        // Modify the list to have only one node to trigger the condition
        availableNodes.clear();
        availableNodes.add(newBlockNodeConfig(8080, 1));

        final BlockNodeConnection connection = mock(BlockNodeConnection.class);
        final BlockNodeConfig nodeConfig = newBlockNodeConfig(8080, 1);
        doReturn(nodeConfig).when(connection).getNodeConfig();

        // Call rescheduleConnection which internally calls handleConnectionCleanupAndReschedule
        connectionManager.rescheduleConnection(connection, Duration.ofSeconds(5), null, true);

        // Verify that scheduleConnectionAttempt was called once with the 5-second delay
        // selectNewBlockNodeForStreaming is NOT called since there's only one node
        verify(executorService).schedule(any(BlockNodeConnectionTask.class), eq(5000L), eq(TimeUnit.MILLISECONDS));
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
    }

    @Test
    void testConnectionTask_runStreamingDisabled() {
        final AtomicBoolean isStreamingEnabled = isStreamingEnabled();
        isStreamingEnabled.set(false);
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
            createConnectionManager(blockNodes);

            // Perform selection - should only select from priority 0 nodes
            connectionManager.selectNewBlockNodeForStreaming(true);

            // Capture the scheduled task and verify it's connecting to a priority 0 node
            final ArgumentCaptor<BlockNodeConnectionTask> taskCaptor =
                    ArgumentCaptor.forClass(BlockNodeConnectionTask.class);
            verify(executorService).schedule(taskCaptor.capture(), anyLong(), any(TimeUnit.class));

            final BlockNodeConnectionTask task = taskCaptor.getValue();
            final BlockNodeConnection connection = connectionFromTask(task);
            final BlockNodeConfig selectedConfig = connection.getNodeConfig();

            // Verify only priority 0 nodes are selected
            assertThat(selectedConfig.priority()).isZero();
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
        assertThat(selectedNodes).hasSizeGreaterThan(1);
    }

    @Test
    void testPriorityBasedSelection_onlyLowerPriorityNodesAvailable() {
        // Setup: All priority 0 nodes are unavailable, only lower priority nodes available
        final List<BlockNodeConfig> blockNodes = List.of(
                new BlockNodeConfig("node1.example.com", 8080, 1), // Priority 1
                new BlockNodeConfig("node2.example.com", 8080, 2), // Priority 2
                new BlockNodeConfig("node3.example.com", 8080, 3) // Priority 3
                );

        createConnectionManager(blockNodes);

        // Perform selection
        connectionManager.selectNewBlockNodeForStreaming(true);

        // Verify it selects the highest priority available
        final ArgumentCaptor<BlockNodeConnectionTask> taskCaptor =
                ArgumentCaptor.forClass(BlockNodeConnectionTask.class);
        verify(executorService).schedule(taskCaptor.capture(), anyLong(), any(TimeUnit.class));

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
        verify(executorService).schedule(taskCaptor.capture(), anyLong(), any(TimeUnit.class));

        final BlockNodeConnectionTask task = taskCaptor.getValue();
        final BlockNodeConnection connection = connectionFromTask(task);
        final BlockNodeConfig selectedConfig = connection.getNodeConfig();

        assertThat(selectedConfig.priority()).isZero();
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
        verify(executorService).schedule(taskCaptor.capture(), anyLong(), any(TimeUnit.class));

        final BlockNodeConnectionTask task = taskCaptor.getValue();
        final BlockNodeConnection connection = connectionFromTask(task);
        final BlockNodeConfig selectedConfig = connection.getNodeConfig();

        assertThat(selectedConfig.priority()).isEqualTo(1); // Should fall back to priority 1
        assertThat(selectedConfig.address()).isIn("node3.example.com", "node4.example.com");
    }

    // Utilities

    private void createConnectionManager(final List<BlockNodeConfig> blockNodes) {
        // Create a custom config provider with the specified block nodes
        final ConfigProvider configProvider = createConfigProvider(createDefaultConfigProvider());

        // Create the manager
        connectionManager = new BlockNodeConnectionManager(configProvider, bufferService, metrics, executorService);

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

    private AtomicBoolean isStreamingEnabled() {
        return (AtomicBoolean) isStreamingEnabledHandle.get(connectionManager);
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

    private void resetMocks() {
        reset(bufferService, metrics, executorService);
    }
}
