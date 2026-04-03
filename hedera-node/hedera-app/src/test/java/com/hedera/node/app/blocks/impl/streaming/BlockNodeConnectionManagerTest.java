// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager.GroupSelectionOutcome;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager.NodeCandidate;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager.RetrieveBlockNodeStatusTask;
import com.hedera.node.app.blocks.impl.streaming.ConnectionId.ConnectionType;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeEndpoint;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.BlockStreamConfig;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Future.State;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockNodeConnectionManagerTest extends BlockNodeCommunicationTestBase {

    private static final VarHandle isConnectionManagerActiveHandle;
    private static final VarHandle activeConnectionRefHandle;
    private static final VarHandle blockNodesHandle;
    private static final VarHandle globalActiveStreamingConnectionCountHandle;
    private static final VarHandle activeConfigRefHandle;
    private static final VarHandle globalCoolDownTimestampRefHandle;
    private static final VarHandle bufferStatusRefHandle;
    private static final VarHandle connectionMonitorThreadRefHandle;

    private static final MethodHandle isActiveConnectionAutoResetHandle;
    private static final MethodHandle isActiveConnectionStalledHandle;
    private static final MethodHandle isHigherPriorityNodeAvailableHandle;
    private static final MethodHandle isBufferUnhealthyHandle;
    private static final MethodHandle isConfigUpdatedHandle;
    private static final MethodHandle isMissingActiveConnectionHandle;
    private static final MethodHandle findAvailableNodeHandle;
    private static final MethodHandle getNextPriorityBlockNodeHandle;
    private static final MethodHandle selectNewBlockNodeHandle;
    private static final MethodHandle pruneNodesHandle;
    private static final MethodHandle updateConnectionIfNeededHandle;

    static {
        try {
            final Class<BlockNodeConnectionManager> cls = BlockNodeConnectionManager.class;
            final Lookup lookup = MethodHandles.privateLookupIn(cls, MethodHandles.lookup());
            isConnectionManagerActiveHandle =
                    lookup.findVarHandle(cls, "isConnectionManagerActive", AtomicBoolean.class);
            activeConnectionRefHandle = lookup.findVarHandle(cls, "activeConnectionRef", AtomicReference.class);
            blockNodesHandle = lookup.findVarHandle(cls, "nodes", ConcurrentMap.class);
            globalActiveStreamingConnectionCountHandle =
                    lookup.findVarHandle(cls, "globalActiveStreamingConnectionCount", AtomicInteger.class);
            activeConfigRefHandle = lookup.findVarHandle(cls, "activeConfigRef", AtomicReference.class);
            globalCoolDownTimestampRefHandle =
                    lookup.findVarHandle(cls, "globalCoolDownTimestampRef", AtomicReference.class);
            bufferStatusRefHandle = lookup.findVarHandle(cls, "bufferStatusRef", AtomicReference.class);
            connectionMonitorThreadRefHandle =
                    lookup.findVarHandle(cls, "connectionMonitorThreadRef", AtomicReference.class);

            final Method isActiveConnectionAutoReset = cls.getDeclaredMethod(
                    "isActiveConnectionAutoReset", Instant.class, BlockNodeStreamingConnection.class);
            isActiveConnectionAutoReset.setAccessible(true);
            isActiveConnectionAutoResetHandle = lookup.unreflect(isActiveConnectionAutoReset);

            final Method isActiveConnectionStalled = cls.getDeclaredMethod(
                    "isActiveConnectionStalled", Instant.class, BlockNodeStreamingConnection.class);
            isActiveConnectionStalled.setAccessible(true);
            isActiveConnectionStalledHandle = lookup.unreflect(isActiveConnectionStalled);

            final Method isHigherPriorityNodeAvailable =
                    cls.getDeclaredMethod("isHigherPriorityNodeAvailable", BlockNodeStreamingConnection.class);
            isHigherPriorityNodeAvailable.setAccessible(true);
            isHigherPriorityNodeAvailableHandle = lookup.unreflect(isHigherPriorityNodeAvailable);

            final Method isBufferUnhealthy = cls.getDeclaredMethod("isBufferUnhealthy");
            isBufferUnhealthy.setAccessible(true);
            isBufferUnhealthyHandle = lookup.unreflect(isBufferUnhealthy);

            final Method isConfigUpdated = cls.getDeclaredMethod("isConfigUpdated");
            isConfigUpdated.setAccessible(true);
            isConfigUpdatedHandle = lookup.unreflect(isConfigUpdated);

            final Method isMissingActiveConnection =
                    cls.getDeclaredMethod("isMissingActiveConnection", BlockNodeStreamingConnection.class);
            isMissingActiveConnection.setAccessible(true);
            isMissingActiveConnectionHandle = lookup.unreflect(isMissingActiveConnection);

            final Method findAvailableNode = cls.getDeclaredMethod("findAvailableNode", List.class);
            findAvailableNode.setAccessible(true);
            findAvailableNodeHandle = lookup.unreflect(findAvailableNode);

            final Method getNextPriorityBlockNode = cls.getDeclaredMethod("getNextPriorityBlockNode", List.class);
            getNextPriorityBlockNode.setAccessible(true);
            getNextPriorityBlockNodeHandle = lookup.unreflect(getNextPriorityBlockNode);

            final Method selectNewBlockNode =
                    cls.getDeclaredMethod("selectNewBlockNode", boolean.class, CloseReason.class);
            selectNewBlockNode.setAccessible(true);
            selectNewBlockNodeHandle = lookup.unreflect(selectNewBlockNode);

            final Method pruneNodes = cls.getDeclaredMethod("pruneNodes");
            pruneNodes.setAccessible(true);
            pruneNodesHandle = lookup.unreflect(pruneNodes);

            final Method updateConnectionIfNeeded = cls.getDeclaredMethod("updateConnectionIfNeeded");
            updateConnectionIfNeeded.setAccessible(true);
            updateConnectionIfNeededHandle = lookup.unreflect(updateConnectionIfNeeded);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BlockNodeConnectionManager connectionManager;

    private BlockBufferService bufferService;
    private BlockStreamMetrics metrics;
    private ExecutorService blockingIoExecutor;
    private Supplier<ExecutorService> blockingIoExecutorSupplier;
    private BlockNodeConfigService blockNodeConfigService;
    private ConfigProvider configProvider;

    @TempDir
    Path tempDir;

    @BeforeEach
    void beforeEach() {
        // Use a non-existent directory to prevent loading any existing block-nodes.json during tests
        configProvider = createConfigProvider(createDefaultConfigProvider()
                .withValue(
                        "blockNode.blockNodeConnectionFileDir",
                        tempDir.toAbsolutePath().toString()));

        bufferService = mock(BlockBufferService.class);
        metrics = mock(BlockStreamMetrics.class);
        blockingIoExecutor = mock(ExecutorService.class);
        blockNodeConfigService = mock(BlockNodeConfigService.class);
        blockingIoExecutorSupplier = () -> blockingIoExecutor;
        connectionManager = new BlockNodeConnectionManager(
                configProvider, bufferService, metrics, blockingIoExecutorSupplier, blockNodeConfigService);

        // Clear any nodes that might have been loaded
        blockNodes().clear();

        // Ensure manager is not active
        final AtomicBoolean isActive = isConnectionManagerActive();
        isActive.set(false);

        resetMocks();
    }

    @Test
    void testIsActiveConnectionAutoReset_nullConnection() throws Throwable {
        final boolean isAutoReset = invoke_isActiveConnectionAutoReset(Instant.now(), null);
        assertThat(isAutoReset).isFalse();
    }

    @Test
    void testIsActiveConnectionAutoReset_false() throws Throwable {
        final Instant now = Instant.now();
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        // the auto reset timestamp is set to be in the future, thus the connection isn't ready to be reset
        when(activeConnection.autoResetTimestamp()).thenReturn(now.plusSeconds(30));

        final boolean isAutoReset = invoke_isActiveConnectionAutoReset(now, activeConnection);

        assertThat(isAutoReset).isFalse();

        verify(activeConnection).autoResetTimestamp();
        verifyNoMoreInteractions(activeConnection);
    }

    @Test
    void testIsActiveConnectionAutoReset_true() throws Throwable {
        final Instant now = Instant.now();
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        // the auto reset timestamp is set to be in the future, thus the connection isn't ready to be reset
        when(activeConnection.autoResetTimestamp()).thenReturn(now.minusSeconds(30));

        final boolean isAutoReset = invoke_isActiveConnectionAutoReset(now, activeConnection);

        assertThat(isAutoReset).isTrue();

        verify(activeConnection).autoResetTimestamp();
        verify(activeConnection).closeAtBlockBoundary(CloseReason.PERIODIC_RESET);
        verifyNoMoreInteractions(activeConnection);
    }

    @Test
    void testIsActiveConnectionStalled_nullConnection() throws Throwable {
        final boolean isStalled = invoke_isActiveConnectionStalled(Instant.now(), null);
        assertThat(isStalled).isFalse();
    }

    @Test
    void testIsActiveConnectionStalled_false() throws Throwable {
        final Instant now = Instant.now();
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        // stalled connections are based on the heartbeat timestamp, so if we set the last heartbeat to near now
        // the connection will not be marked as stalled
        when(activeConnection.heartbeatTimestamp()).thenReturn(now.toEpochMilli());

        final boolean isStalled = invoke_isActiveConnectionStalled(now, activeConnection);

        assertThat(isStalled).isFalse();

        verify(activeConnection).heartbeatTimestamp();
        verifyNoMoreInteractions(activeConnection);
    }

    @Test
    void testIsActiveConnectionStalled_true() throws Throwable {
        final Instant now = Instant.now();
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        // stalled connections are based on the heartbeat timestamp, so set the last heartbeat to far in the past
        // to trigger a stall detection
        when(activeConnection.heartbeatTimestamp())
                .thenReturn(now.minusSeconds(1).toEpochMilli());

        final boolean isStalled = invoke_isActiveConnectionStalled(now, activeConnection);

        assertThat(isStalled).isTrue();

        verify(activeConnection).heartbeatTimestamp();
        verify(activeConnection).close(CloseReason.CONNECTION_STALLED, true);
        verifyNoMoreInteractions(activeConnection);
    }

    @Test
    void testIsHigherPriorityNodeAvailable_nullConnection() throws Throwable {
        final boolean isHigherAvailable = invoke_isHigherPriorityNodeAvailable(null);
        assertThat(isHigherAvailable).isFalse();
    }

    @Test
    void testIsHigherPriorityNodeAvailable_false() throws Throwable {
        final BlockNodeConfiguration activeConfig = newBlockNodeConfig("localhost", 9999, 1);
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        when(activeConnection.configuration()).thenReturn(activeConfig);
        when(activeConnection.connectionId()).thenReturn(new ConnectionId(ConnectionType.BLOCK_STREAMING, 1));
        when(activeConnection.createTimestamp()).thenReturn(Instant.now());
        when(activeConnection.activeTimestamp()).thenReturn(Instant.now());

        blockNodes().clear();
        final BlockNode priority2Node = new BlockNode(
                configProvider, newBlockNodeConfig("localhost", 1234, 2), new AtomicInteger(), new BlockNodeStats());
        final BlockNode activeNode =
                new BlockNode(configProvider, activeConfig, new AtomicInteger(), new BlockNodeStats());
        activeNode.onActive(activeConnection);
        blockNodes().put(new BlockNodeEndpoint("localhost", 1234), priority2Node);
        blockNodes().put(activeConfig.streamingEndpoint(), activeNode);

        final boolean isHigherAvailable = invoke_isHigherPriorityNodeAvailable(activeConnection);

        assertThat(isHigherAvailable).isFalse();

        verify(activeConnection, atLeastOnce()).configuration();
        verify(activeConnection, times(2)).connectionId();
        verify(activeConnection).createTimestamp();
        verify(activeConnection).activeTimestamp();
        verifyNoMoreInteractions(activeConnection);
    }

    @Test
    void testIsHigherPriorityNodeAvailable_true() throws Throwable {
        final BlockNodeConfiguration activeConfig = newBlockNodeConfig("localhost", 9999, 2);
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        when(activeConnection.configuration()).thenReturn(activeConfig);
        when(activeConnection.connectionId()).thenReturn(new ConnectionId(ConnectionType.BLOCK_STREAMING, 1));
        when(activeConnection.createTimestamp()).thenReturn(Instant.now());
        when(activeConnection.activeTimestamp()).thenReturn(Instant.now());

        blockNodes().clear();
        final BlockNode priority1Node = new BlockNode(
                configProvider, newBlockNodeConfig("localhost", 1234, 1), new AtomicInteger(), new BlockNodeStats());
        final BlockNode activeNode =
                new BlockNode(configProvider, activeConfig, new AtomicInteger(), new BlockNodeStats());
        activeNode.onActive(activeConnection);
        blockNodes().put(priority1Node.configuration().streamingEndpoint(), priority1Node);
        blockNodes().put(activeConfig.streamingEndpoint(), activeNode);

        final boolean isHigherAvailable = invoke_isHigherPriorityNodeAvailable(activeConnection);

        assertThat(isHigherAvailable).isTrue();

        verify(activeConnection, atLeastOnce()).configuration();
        verify(activeConnection, times(2)).connectionId();
        verify(activeConnection).createTimestamp();
        verify(activeConnection).activeTimestamp();
        verifyNoMoreInteractions(activeConnection);
    }

    @Test
    void testIsBufferUnhealthy_latestStatusNull() throws Throwable {
        when(bufferService.latestBufferStatus()).thenReturn(null);

        final boolean isBufferUnhealthy = invoke_isBufferUnhealthy();

        assertThat(isBufferUnhealthy).isFalse();

        verify(bufferService).latestBufferStatus();
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testIsBufferUnhealthy_sameStatus() throws Throwable {
        final BlockBufferStatus status = new BlockBufferStatus(Instant.now(), 5.00D, false);
        when(bufferService.latestBufferStatus()).thenReturn(status);
        bufferStatusRef().set(status);

        final boolean isUnhealthy = invoke_isBufferUnhealthy();

        assertThat(isUnhealthy).isFalse();

        verify(bufferService).latestBufferStatus();
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testIsBufferUnhealthy_false() throws Throwable {
        final Instant now = Instant.now();
        final BlockBufferStatus latestStatus = new BlockBufferStatus(now, 5.00D, false);
        when(bufferService.latestBufferStatus()).thenReturn(latestStatus);
        bufferStatusRef().set(new BlockBufferStatus(now.minusSeconds(1), 0.0D, false));

        final boolean isUnhealthy = invoke_isBufferUnhealthy();

        assertThat(isUnhealthy).isFalse();
        assertThat(bufferStatusRef()).hasValue(latestStatus);

        verify(bufferService).latestBufferStatus();
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testIsBufferUnhealthy_true() throws Throwable {
        final Instant now = Instant.now();
        final BlockBufferStatus latestStatus = new BlockBufferStatus(now, 80.00D, true);
        when(bufferService.latestBufferStatus()).thenReturn(latestStatus);
        bufferStatusRef().set(new BlockBufferStatus(now.minusSeconds(1), 0.0D, false));

        final boolean isUnhealthy = invoke_isBufferUnhealthy();

        assertThat(isUnhealthy).isTrue();
        assertThat(bufferStatusRef()).hasValue(latestStatus);

        verify(bufferService).latestBufferStatus();
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testIsConfigUpdated_configRemoved() throws Throwable {
        final VersionedBlockNodeConfigurationSet activeConfig =
                new VersionedBlockNodeConfigurationSet(1, List.of(newBlockNodeConfig("localhost", 1234, 1)));
        activeConfigRef().set(activeConfig);
        when(blockNodeConfigService.latestConfiguration()).thenReturn(null);
        final BlockNode existingNode = mock(BlockNode.class);
        blockNodes().put(new BlockNodeEndpoint("localhost", 1234), existingNode);

        final boolean isUpdated = invoke_isConfigUpdated();

        assertThat(isUpdated).isTrue();
        assertThat(activeConfigRef()).hasNullValue();

        verify(blockNodeConfigService).latestConfiguration();
        verify(existingNode).onTerminate(CloseReason.CONFIG_UPDATE);
        verifyNoMoreInteractions(blockNodeConfigService);
        verifyNoMoreInteractions(existingNode);
    }

    @Test
    void testIsConfigUpdated_configUpdated() throws Throwable {
        final BlockNodeConfiguration node1OldConfig = newBlockNodeConfig("localhost", 1234, 1);
        final BlockNodeConfiguration node2OldConfig = newBlockNodeConfig("localhost", 2345, 2);
        final BlockNodeConfiguration node1NewConfig = newBlockNodeConfig("localhost", 1234, 2);
        final BlockNodeConfiguration node3NewConfig = newBlockNodeConfig("localhost", 7890, 1);
        final VersionedBlockNodeConfigurationSet activeConfig =
                new VersionedBlockNodeConfigurationSet(1, List.of(node1OldConfig, node2OldConfig));
        // The new config updates localhost:1234 to priority 2, removes localhost:2345, and adds localhost:7890
        final VersionedBlockNodeConfigurationSet newConfig =
                new VersionedBlockNodeConfigurationSet(2, List.of(node1NewConfig, node3NewConfig));
        activeConfigRef().set(activeConfig);
        when(blockNodeConfigService.latestConfiguration()).thenReturn(newConfig);
        final BlockNode node1 = mock(BlockNode.class);
        final BlockNode node2 = mock(BlockNode.class);
        when(node1.configuration()).thenReturn(node1OldConfig);
        when(node2.configuration()).thenReturn(node2OldConfig);
        blockNodes().put(node1OldConfig.streamingEndpoint(), node1);
        blockNodes().put(node2OldConfig.streamingEndpoint(), node2);

        final boolean isUpdated = invoke_isConfigUpdated();

        final ConcurrentMap<BlockNodeEndpoint, BlockNode> nodes = blockNodes();
        assertThat(nodes)
                .hasSize(3)
                .containsOnlyKeys(
                        node1NewConfig.streamingEndpoint(),
                        node2OldConfig.streamingEndpoint(),
                        node3NewConfig.streamingEndpoint());

        assertThat(isUpdated).isTrue();
        assertThat(activeConfigRef()).hasValue(newConfig);

        verify(node1).onConfigUpdate(node1NewConfig);
        verify(node2).onTerminate(CloseReason.CONFIG_UPDATE);
        verify(blockNodeConfigService).latestConfiguration();
        verifyNoMoreInteractions(node1);
        verifyNoMoreInteractions(node2);
        verifyNoMoreInteractions(blockNodeConfigService);
    }

    @Test
    void testIsConfigUpdated_configUpdatedWithNoChanges() throws Throwable {
        final BlockNodeConfiguration config = newBlockNodeConfig("localhost", 1234, 1);
        final VersionedBlockNodeConfigurationSet existingConfig =
                new VersionedBlockNodeConfigurationSet(1, List.of(config));
        final VersionedBlockNodeConfigurationSet newConfig = new VersionedBlockNodeConfigurationSet(2, List.of(config));
        activeConfigRef().set(existingConfig);
        when(blockNodeConfigService.latestConfiguration()).thenReturn(newConfig);
        blockNodes()
                .put(
                        config.streamingEndpoint(),
                        new BlockNode(configProvider, config, new AtomicInteger(), new BlockNodeStats()));

        final boolean isUpdated = invoke_isConfigUpdated();

        assertThat(isUpdated).isFalse();

        assertThat(activeConfigRef()).hasValue(existingConfig);
        assertThat(blockNodes()).hasSize(1).containsKey(config.streamingEndpoint());
    }

    @Test
    void testIsConfigUpdated_noConfigs() throws Throwable {
        activeConfigRef().set(null);
        when(blockNodeConfigService.latestConfiguration()).thenReturn(null);

        final boolean isUpdated = invoke_isConfigUpdated();

        assertThat(isUpdated).isFalse();
    }

    @Test
    void testIsMissingActiveConnection_false() throws Throwable {
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        final boolean isMissing = invoke_isMissingActiveConnection(activeConnection);
        assertThat(isMissing).isFalse();
    }

    @Test
    void testIsMissingActiveConnection_true() throws Throwable {
        final boolean isMissing = invoke_isMissingActiveConnection(null);
        assertThat(isMissing).isTrue();
    }

    @Test
    void testNotifyConnectionActive_untrackedNode() {
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        when(connection.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        blockNodes().clear();

        connectionManager.notifyConnectionActive(connection);

        verify(connection).configuration();
        verify(connection).close(CloseReason.INTERNAL_ERROR, true);
        verifyNoMoreInteractions(connection);
    }

    @Test
    void testNotifyConnectionActive() {
        final BlockNode blockNode = mock(BlockNode.class);
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration config = newBlockNodeConfig("localhost", 1234, 1);
        when(connection.configuration()).thenReturn(config);
        blockNodes().put(config.streamingEndpoint(), blockNode);

        connectionManager.notifyConnectionActive(connection);

        verify(connection).configuration();
        verify(blockNode).onActive(connection);
        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(blockNode);
    }

    @Test
    void testNotifyConnectionClosed_untrackedNode() {
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        when(connection.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        blockNodes().clear();

        connectionManager.notifyConnectionClosed(connection);

        verify(connection).configuration();
        verifyNoMoreInteractions(connection);
    }

    @Test
    void testNotifyConnectionClosed_asNotActiveConnection() {
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        activeConnectionRef().set(activeConnection);
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        when(connection.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        blockNodes().clear();

        connectionManager.notifyConnectionClosed(connection);

        assertThat(activeConnectionRef()).hasValue(activeConnection);

        verify(connection).configuration();
        verifyNoMoreInteractions(connection);
    }

    @Test
    void testNotifyConnectionClosed_asActiveConnection() {
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration config = newBlockNodeConfig("localhost", 1234, 1);
        final BlockNode blockNode = mock(BlockNode.class);
        when(activeConnection.configuration()).thenReturn(config);
        activeConnectionRef().set(activeConnection);
        blockNodes().put(config.streamingEndpoint(), blockNode);

        connectionManager.notifyConnectionClosed(activeConnection);

        assertThat(activeConnectionRef()).hasNullValue();

        verify(activeConnection).configuration();
        verify(blockNode).onClose(activeConnection);
        verifyNoMoreInteractions(activeConnection);
        verifyNoMoreInteractions(blockNode);
    }

    @Test
    void testFindAvailableNode_nullNodes() {
        assertThatThrownBy(() -> invoke_findAvailableNode(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("nodes must not be null");
    }

    @Test
    void testFindAvailableNode_emptyNodes() throws Throwable {
        final GroupSelectionOutcome outcome = invoke_findAvailableNode(List.of());
        assertThat(outcome).isNull();
    }

    @Test
    void testFindAvailableNode_fetchStatusInterrupted() throws Throwable {
        // throw an InterruptedException when the tasks are submitted
        doThrow(new InterruptedException())
                .when(blockingIoExecutor)
                .invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));
        final AtomicReference<GroupSelectionOutcome> outcomeRef = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final BlockNode blockNode = mock(BlockNode.class);
        when(blockNode.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        final List<BlockNode> nodes = List.of(blockNode);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean isInterrupted = new AtomicBoolean();

        Thread.ofVirtual().start(() -> {
            try {
                outcomeRef.set(invoke_findAvailableNode(nodes));
            } catch (final Throwable t) {
                error.set(t);
            } finally {
                isInterrupted.set(Thread.currentThread().isInterrupted());
                latch.countDown();
            }
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        final Throwable t = error.get();
        if (t != null) {
            throw t; // no exception should be thrown
        }

        assertThat(outcomeRef).hasNullValue();
        assertThat(isInterrupted).isTrue();

        verify(blockingIoExecutor).invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));
        verify(blockNode).configuration();
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(blockNode);
    }

    @Test
    void testFindAvailableNode_fetchStatusException() throws Throwable {
        // throw a RuntimeException when the tasks are submitted
        doThrow(new RuntimeException())
                .when(blockingIoExecutor)
                .invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));
        final AtomicReference<GroupSelectionOutcome> outcomeRef = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final BlockNode blockNode = mock(BlockNode.class);
        when(blockNode.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        final List<BlockNode> nodes = List.of(blockNode);
        final CountDownLatch latch = new CountDownLatch(1);

        Thread.ofVirtual().start(() -> {
            try {
                outcomeRef.set(invoke_findAvailableNode(nodes));
            } catch (final Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        final Throwable t = error.get();
        if (t != null) {
            throw t; // no exception should be thrown
        }

        assertThat(outcomeRef).hasNullValue();

        verify(blockingIoExecutor).invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));
        verify(blockNode).configuration();
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(blockNode);
    }

    @Test
    void testFindAvailableNode_mismatchedNumberOfTasks() throws Throwable {
        // one task is submitted, but we should return zero futures to enter the mismatch path
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of());
        final AtomicReference<GroupSelectionOutcome> outcomeRef = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final BlockNode blockNode = mock(BlockNode.class);
        when(blockNode.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        final List<BlockNode> nodes = List.of(blockNode);
        final CountDownLatch latch = new CountDownLatch(1);

        Thread.ofVirtual().start(() -> {
            try {
                outcomeRef.set(invoke_findAvailableNode(nodes));
            } catch (final Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        final Throwable t = error.get();
        if (t != null) {
            throw t; // no exception should be thrown
        }

        assertThat(outcomeRef).hasNullValue();

        verify(blockingIoExecutor).invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));
        verify(blockNode).configuration();
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(blockNode);
    }

    @Test
    void testFindAvailableNode_noSuccessfulStatusChecks() throws Throwable {
        final BlockNode node1 = mock(BlockNode.class);
        when(node1.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        final BlockNode node2 = mock(BlockNode.class);
        when(node2.configuration()).thenReturn(newBlockNodeConfig("localhost", 2345, 1));
        final BlockNode node3 = mock(BlockNode.class);
        when(node3.configuration()).thenReturn(newBlockNodeConfig("localhost", 3456, 1));
        final BlockNode node4 = mock(BlockNode.class);
        when(node4.configuration()).thenReturn(newBlockNodeConfig("localhost", 4567, 1));
        // Node 1 future will be SUCCESS, but missing a value
        final Future<Object> node1Future = mock(Future.class);
        when(node1Future.state()).thenReturn(State.SUCCESS);
        when(node1Future.resultNow()).thenReturn(null);
        // Node 2 future will be FAILED
        final Future<Object> node2Future = mock(Future.class);
        when(node2Future.state()).thenReturn(State.FAILED);
        when(node2Future.exceptionNow()).thenReturn(new RuntimeException("Node2 is bad"));
        // Node 3 future will be CANCELLED
        final Future<Object> node3Future = mock(Future.class);
        when(node3Future.state()).thenReturn(State.CANCELLED);
        // Node 4 future will be RUNNING
        final Future<Object> node4Future = mock(Future.class);
        when(node4Future.state()).thenReturn(State.RUNNING);
        // the order of the nodes and futures should match
        final List<BlockNode> nodes = List.of(node1, node2, node3, node4);
        final List<Future<Object>> futures = List.of(node1Future, node2Future, node3Future, node4Future);
        when(blockingIoExecutor.invokeAll(anyList(), anyLong(), any(TimeUnit.class)))
                .thenReturn(futures);

        final GroupSelectionOutcome outcome = invoke_findAvailableNode(nodes);

        assertThat(outcome).isNull();

        verify(blockingIoExecutor).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verify(node1Future).state();
        verify(node1Future).resultNow();
        verify(node2Future).state();
        verify(node2Future).exceptionNow();
        verify(node3Future).state();
        verify(node3Future).cancel(true);
        verify(node4Future).state();
        verify(node4Future).cancel(true);
        verify(node1, times(2)).configuration();
        verify(node2, times(2)).configuration();
        verify(node3, times(2)).configuration();
        verify(node4, times(2)).configuration();
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(node1Future, node2Future, node3Future, node4Future);
        verifyNoMoreInteractions(node1, node2, node3, node4);
    }

    @Test
    void testFindAvailableNode_wantedOutOfRangeBlock() throws Throwable {
        final BlockNode node = mock(BlockNode.class);
        when(node.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(20L);
        final Future<Object> nodeFuture = mock(Future.class);
        when(nodeFuture.state()).thenReturn(State.SUCCESS);
        // return a status that indicates the block node wants block 6
        when(nodeFuture.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 5));
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(nodeFuture));

        final GroupSelectionOutcome outcome = invoke_findAvailableNode(List.of(node));

        assertThat(outcome).isNull();

        verify(blockingIoExecutor).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verify(nodeFuture).state();
        verify(nodeFuture).resultNow();
        verify(node, times(2)).configuration();
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(nodeFuture);
        verifyNoMoreInteractions(node);
    }

    @Test
    void testFindAvailableNode_noBlocksBuffered() throws Throwable {
        final BlockNode node = mock(BlockNode.class);
        when(node.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        // set the block buffer to indicate there are no blocks created
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(-1L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(-1L);
        final Future<Object> nodeFuture = mock(Future.class);
        when(nodeFuture.state()).thenReturn(State.SUCCESS);
        when(nodeFuture.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 5));
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(nodeFuture));

        final GroupSelectionOutcome outcome = invoke_findAvailableNode(List.of(node));

        assertThat(outcome).isNotNull();
        assertThat(outcome.inRangeCandidates()).hasSize(1).contains(new NodeCandidate(node, 6));
        assertThat(outcome.lowestAheadCandidates()).isEmpty();
        assertThat(outcome.lowestAheadWantedBlock()).isEqualTo(Long.MAX_VALUE);

        verify(blockingIoExecutor).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verify(nodeFuture).state();
        verify(nodeFuture).resultNow();
        verify(node, times(2)).configuration();
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(nodeFuture);
        verifyNoMoreInteractions(node);
    }

    @Test
    void testFindAvailableNode_inRangeCandidates() throws Throwable {
        final BlockNode node1 = mock(BlockNode.class);
        when(node1.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        final BlockNode node2 = mock(BlockNode.class);
        when(node2.configuration()).thenReturn(newBlockNodeConfig("localhost", 2345, 1));
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(20L);
        final Future<Object> node1Future = mock(Future.class);
        when(node1Future.state()).thenReturn(State.SUCCESS);
        when(node1Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 12));
        final Future<Object> node2Future = mock(Future.class);
        when(node2Future.state()).thenReturn(State.SUCCESS);
        when(node2Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 14));
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(node1Future, node2Future));

        final GroupSelectionOutcome outcome = invoke_findAvailableNode(List.of(node1, node2));

        assertThat(outcome).isNotNull();
        assertThat(outcome.inRangeCandidates())
                .hasSize(2)
                .contains(new NodeCandidate(node1, 13), new NodeCandidate(node2, 15));
        assertThat(outcome.lowestAheadCandidates()).isEmpty();
        assertThat(outcome.lowestAheadWantedBlock()).isEqualTo(Long.MAX_VALUE);

        verify(blockingIoExecutor).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verify(node1Future).state();
        verify(node1Future).resultNow();
        verify(node2Future).state();
        verify(node2Future).resultNow();
        verify(node1, times(2)).configuration();
        verify(node2, times(2)).configuration();
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(node1Future, node2Future);
        verifyNoMoreInteractions(node1, node2);
    }

    @Test
    void testFindAvailableNode_aheadCandidates() throws Throwable {
        final BlockNode node1 = mock(BlockNode.class);
        when(node1.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        final BlockNode node2 = mock(BlockNode.class);
        when(node2.configuration()).thenReturn(newBlockNodeConfig("localhost", 2345, 1));
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(20L);
        final Future<Object> node1Future = mock(Future.class);
        when(node1Future.state()).thenReturn(State.SUCCESS);
        when(node1Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 21));
        final Future<Object> node2Future = mock(Future.class);
        when(node2Future.state()).thenReturn(State.SUCCESS);
        when(node2Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 23));
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(node1Future, node2Future));

        final GroupSelectionOutcome outcome = invoke_findAvailableNode(List.of(node1, node2));

        assertThat(outcome).isNotNull();
        assertThat(outcome.inRangeCandidates()).isEmpty();
        assertThat(outcome.lowestAheadCandidates()).hasSize(1).contains(new NodeCandidate(node1, 22));
        assertThat(outcome.lowestAheadWantedBlock()).isEqualTo(22);

        verify(blockingIoExecutor).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verify(node1Future).state();
        verify(node1Future).resultNow();
        verify(node2Future).state();
        verify(node2Future).resultNow();
        verify(node1, times(2)).configuration();
        verify(node2, times(2)).configuration();
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(node1Future, node2Future);
        verifyNoMoreInteractions(node1, node2);
    }

    @Test
    void testRetrieveBlockNodeStatusTask() {
        final BlockNodeServiceConnection svcConnection = mock(BlockNodeServiceConnection.class);
        final BlockNodeStatus status = new BlockNodeStatus(true, 10, 25);
        when(svcConnection.getBlockNodeStatus()).thenReturn(status);

        final RetrieveBlockNodeStatusTask task = new RetrieveBlockNodeStatusTask(svcConnection);
        final BlockNodeStatus actualStatus = task.call();

        assertThat(actualStatus).isNotNull().isEqualTo(status);

        verify(svcConnection).initialize();
        verify(svcConnection).getBlockNodeStatus();
        verify(svcConnection).close();
        verifyNoMoreInteractions(svcConnection);
    }

    @Test
    void testGetNextPriorityBlockNode_skipP1GroupBecauseNoCandidates() throws Throwable {
        // Node 1 will have a priority of 1, but it will be unreachable
        final BlockNode node1 = mock(BlockNode.class);
        when(node1.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        final Future<Object> node1Future = mock(Future.class);
        when(node1Future.state()).thenReturn(State.RUNNING); // this will mark the node as unreachable (timeout)
        // Node 2 will have a priority of 2, and it will be reachable
        final BlockNode node2 = mock(BlockNode.class);
        when(node2.configuration()).thenReturn(newBlockNodeConfig("localhost", 2345, 2));
        final Future<Object> node2Future = mock(Future.class);
        when(node2Future.state()).thenReturn(State.SUCCESS);
        when(node2Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 15));
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(25L);
        // the blocking I/O executor will be called twice, once for Node 1 (priority 1 group) and once for Node 2
        // (priority 2 group)
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(node1Future), List.of(node2Future));

        final BlockNode selectedNode = invoke_getNextPriorityBlockNode(List.of(node1, node2));

        assertThat(selectedNode).isNotNull().isEqualTo(node2);

        verify(blockingIoExecutor, times(2)).invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));
        verify(node1, times(3)).configuration();
        verify(node2, times(3)).configuration();
        verify(node1Future).state();
        verify(node1Future).cancel(true);
        verify(node2Future).state();
        verify(node2Future).resultNow();
        verify(bufferService, times(2)).getEarliestAvailableBlockNumber();
        verify(bufferService, times(2)).getLastBlockNumberProduced();
        verifyNoMoreInteractions(node1, node2);
        verifyNoMoreInteractions(node1Future, node2Future);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(blockingIoExecutor);
    }

    @Test
    void testGetNextPriorityBlockNode_p1GroupInRange() throws Throwable {
        final BlockNode node1 = mock(BlockNode.class);
        when(node1.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        final BlockNode node2 = mock(BlockNode.class);
        when(node2.configuration()).thenReturn(newBlockNodeConfig("localhost", 2345, 1));
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(20L);
        final Future<Object> node1Future = mock(Future.class);
        when(node1Future.state()).thenReturn(State.SUCCESS);
        when(node1Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 12));
        final Future<Object> node2Future = mock(Future.class);
        when(node2Future.state()).thenReturn(State.SUCCESS);
        when(node2Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 14));
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(node1Future, node2Future));

        final BlockNode selectedNode = invoke_getNextPriorityBlockNode(List.of(node1, node2));

        assertThat(selectedNode).isNotNull().isIn(node1, node2);

        verify(blockingIoExecutor).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verify(node1Future).state();
        verify(node1Future).resultNow();
        verify(node2Future).state();
        verify(node2Future).resultNow();
        verify(node1, times(3)).configuration();
        verify(node2, times(3)).configuration();
        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getLastBlockNumberProduced();
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(node1Future, node2Future);
        verifyNoMoreInteractions(node1, node2);
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testGetNextPriorityBlockNode_p1GroupAhead() throws Throwable {
        final BlockNode node1 = mock(BlockNode.class);
        when(node1.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        final BlockNode node2 = mock(BlockNode.class);
        when(node2.configuration()).thenReturn(newBlockNodeConfig("localhost", 2345, 1));
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(20L);
        final Future<Object> node1Future = mock(Future.class);
        when(node1Future.state()).thenReturn(State.SUCCESS);
        when(node1Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 22));
        final Future<Object> node2Future = mock(Future.class);
        when(node2Future.state()).thenReturn(State.SUCCESS);
        when(node2Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 20));
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(node1Future, node2Future));

        final BlockNode selectedNode = invoke_getNextPriorityBlockNode(List.of(node1, node2));

        assertThat(selectedNode).isNotNull().isEqualTo(node2); // node 2 has the lowest ahead block, so it is selected

        verify(blockingIoExecutor).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verify(node1Future).state();
        verify(node1Future).resultNow();
        verify(node2Future).state();
        verify(node2Future).resultNow();
        verify(node1, times(3)).configuration();
        verify(node2, times(3)).configuration();
        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getLastBlockNumberProduced();
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(node1Future, node2Future);
        verifyNoMoreInteractions(node1, node2);
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testGetNextPriorityBlockNode_p2GroupLowerAhead() throws Throwable {
        final BlockNode node1 = mock(BlockNode.class);
        when(node1.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        // Node 2 is in priority group 2, and it has a lower ahead block
        final BlockNode node2 = mock(BlockNode.class);
        when(node2.configuration()).thenReturn(newBlockNodeConfig("localhost", 2345, 2));
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(20L);
        final Future<Object> node1Future = mock(Future.class);
        when(node1Future.state()).thenReturn(State.SUCCESS);
        when(node1Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 22));
        final Future<Object> node2Future = mock(Future.class);
        when(node2Future.state()).thenReturn(State.SUCCESS);
        when(node2Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 20));
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(node1Future), List.of(node2Future));

        final BlockNode selectedNode = invoke_getNextPriorityBlockNode(List.of(node1, node2));

        assertThat(selectedNode).isNotNull().isEqualTo(node2); // node 2 has the lowest ahead block, so it is selected

        verify(blockingIoExecutor, times(2)).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verify(node1Future).state();
        verify(node1Future).resultNow();
        verify(node2Future).state();
        verify(node2Future).resultNow();
        verify(node1, times(3)).configuration();
        verify(node2, times(3)).configuration();
        verify(bufferService, times(2)).getEarliestAvailableBlockNumber();
        verify(bufferService, times(2)).getLastBlockNumberProduced();
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(node1Future, node2Future);
        verifyNoMoreInteractions(node1, node2);
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testPruneNodes() throws Throwable {
        final BlockNode node1 = mock(BlockNode.class);
        when(node1.isRemovable()).thenReturn(true);
        final BlockNode node2 = mock(BlockNode.class);
        when(node2.isRemovable()).thenReturn(false);
        final BlockNode node3 = mock(BlockNode.class);
        when(node3.isRemovable()).thenReturn(false);

        blockNodes().put(new BlockNodeEndpoint("localhost", 1234), node1);
        blockNodes().put(new BlockNodeEndpoint("localhost", 2345), node2);
        blockNodes().put(new BlockNodeEndpoint("localhost", 3456), node3);

        invoke_pruneNodes();

        assertThat(blockNodes())
                .hasSize(2)
                .containsOnlyKeys(new BlockNodeEndpoint("localhost", 2345), new BlockNodeEndpoint("localhost", 3456));

        verify(node1).isRemovable();
        verify(node2).isRemovable();
        verify(node3).isRemovable();
        verifyNoMoreInteractions(node1, node2, node3);
    }

    @Test
    void testSelectNewBlockNode_globalCoolDownActiveWithoutForce() throws Throwable {
        // set the global cooldown in the future
        globalCoolDownTimestampRef().set(Instant.now().plusSeconds(10));
        activeConnectionRef().set(null);

        invoke_selectNewBlockNode(false, CloseReason.BUFFER_SATURATION);

        assertThat(activeConnectionRef()).hasNullValue();
    }

    @Test
    void testSelectNewBlockNode_globalCoolDownActiveWithForce() throws Throwable {
        // set the global cool down to be in the future
        final Instant existingGlobalCoolDown = Instant.now().plusSeconds(10);
        globalCoolDownTimestampRef().set(existingGlobalCoolDown);
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(20L);
        final BlockNode node1 = mock(BlockNode.class);
        when(node1.isStreamingCandidate()).thenReturn(true);
        when(node1.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        final Future<Object> node1Future = mock(Future.class);
        when(node1Future.state()).thenReturn(State.SUCCESS);
        when(node1Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 12));
        final BlockNode node2 = mock(BlockNode.class);
        when(node2.isStreamingCandidate()).thenReturn(false); // not a candidate
        lenient().when(node2.configuration()).thenReturn(newBlockNodeConfig("localhost", 2345, 2));
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(node1Future));

        blockNodes().put(new BlockNodeEndpoint("localhost", 1234), node1);
        blockNodes().put(new BlockNodeEndpoint("localhost", 2345), node2);

        try (final MockedConstruction<BlockNodeStreamingConnection> mockConnection =
                mockConstruction(BlockNodeStreamingConnection.class)) {
            invoke_selectNewBlockNode(true, CloseReason.BUFFER_SATURATION);

            // one connection should have been created
            assertThat(mockConnection.constructed()).hasSize(1);
        }

        assertThat(activeConnectionRef()).doesNotHaveNullValue();
        final Instant newGlobalCoolDownTimestamp = globalCoolDownTimestampRef().get();
        assertThat(newGlobalCoolDownTimestamp).isNotNull().isAfter(existingGlobalCoolDown);

        verify(blockingIoExecutor).invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));
        verify(node1, atLeastOnce()).isStreamingCandidate();
        verify(node1, atLeast(3)).configuration();
        verify(node1Future).state();
        verify(node1Future).resultNow();
        verify(node2, atLeastOnce()).isStreamingCandidate();
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(node1Future);
    }

    @Test
    void testSelectNewBlockNode_noCandidates() throws Throwable {
        // set the cooldown in the past
        final Instant existingGlobalCoolDown = Instant.now().minusSeconds(10);
        globalCoolDownTimestampRef().set(existingGlobalCoolDown);
        final BlockNode node1 = mock(BlockNode.class);
        when(node1.isStreamingCandidate()).thenReturn(false);
        final BlockNode node2 = mock(BlockNode.class);
        when(node2.isStreamingCandidate()).thenReturn(false); // not a candidate
        lenient().when(node2.configuration()).thenReturn(newBlockNodeConfig("localhost", 2345, 2));

        blockNodes().put(new BlockNodeEndpoint("localhost", 1234), node1);
        blockNodes().put(new BlockNodeEndpoint("localhost", 2345), node2);

        try (final MockedConstruction<BlockNodeStreamingConnection> mockConnection =
                mockConstruction(BlockNodeStreamingConnection.class)) {
            invoke_selectNewBlockNode(true, CloseReason.BUFFER_SATURATION);

            // no connection should have been created
            assertThat(mockConnection.constructed()).isEmpty();
        }

        assertThat(activeConnectionRef()).hasNullValue();

        // global cool down should not be updated since there is no new connection
        final Instant newGlobalCoolDownTimestamp = globalCoolDownTimestampRef().get();
        assertThat(newGlobalCoolDownTimestamp).isNotNull().isEqualTo(existingGlobalCoolDown);

        verify(node1, atLeastOnce()).isStreamingCandidate();
        verify(node2, atLeastOnce()).isStreamingCandidate();
    }

    @Test
    void testSelectNewBlockNode_foundCandidateWithActiveConnection() throws Throwable {
        // set the global cool down to be in the past
        final Instant existingGlobalCoolDown = Instant.now().minusSeconds(10);
        globalCoolDownTimestampRef().set(existingGlobalCoolDown);
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(20L);
        final BlockNode node1 = mock(BlockNode.class);
        when(node1.isStreamingCandidate()).thenReturn(true);
        when(node1.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        final Future<Object> node1Future = mock(Future.class);
        when(node1Future.state()).thenReturn(State.SUCCESS);
        when(node1Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 12));
        final BlockNode node2 = mock(BlockNode.class);
        final BlockNodeConfiguration node2Config = newBlockNodeConfig("localhost", 2345, 2);
        when(node2.isStreamingCandidate()).thenReturn(false); // not a candidate
        lenient().when(node2.configuration()).thenReturn(node2Config);
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(node1Future));
        final BlockNodeStreamingConnection node2Conn = mock(BlockNodeStreamingConnection.class);
        lenient().when(node2Conn.configuration()).thenReturn(node2Config);

        blockNodes().put(new BlockNodeEndpoint("localhost", 1234), node1);
        blockNodes().put(new BlockNodeEndpoint("localhost", 2345), node2);
        activeConnectionRef().set(node2Conn); // set node 2 as the current active connection

        final BlockNodeStreamingConnection newActiveConnection;
        try (final MockedConstruction<BlockNodeStreamingConnection> mockConnection =
                mockConstruction(BlockNodeStreamingConnection.class)) {
            invoke_selectNewBlockNode(true, CloseReason.BUFFER_SATURATION);

            // one connection should have been created
            final List<BlockNodeStreamingConnection> createdConnections = mockConnection.constructed();
            assertThat(createdConnections).hasSize(1);

            newActiveConnection = createdConnections.getFirst();
        }

        assertThat(activeConnectionRef()).doesNotHaveNullValue().hasValue(newActiveConnection);

        // since a new connection has been created, the global cool down should be updated
        final Instant newGlobalCoolDownTimestamp = globalCoolDownTimestampRef().get();
        assertThat(newGlobalCoolDownTimestamp).isNotNull().isAfter(existingGlobalCoolDown);

        verify(newActiveConnection).initialize();
        verify(newActiveConnection).updateConnectionState(ConnectionState.ACTIVE);
        verify(node2Conn).closeAtBlockBoundary(CloseReason.BUFFER_SATURATION);

        verify(blockingIoExecutor).invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));
        verify(node1, atLeastOnce()).isStreamingCandidate();
        verify(node1, atLeast(3)).configuration();
        verify(node1Future).state();
        verify(node1Future).resultNow();
        verify(node2, atLeastOnce()).isStreamingCandidate();
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(node1Future);
    }

    @Test
    void testUpdateConnectionIfNeeded_noActiveConnectionOnly() throws Throwable {
        // set no active connection
        activeConnectionRef().set(null);
        // apply latest configuration
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig("localhost", 1234, 1);
        final VersionedBlockNodeConfigurationSet config =
                new VersionedBlockNodeConfigurationSet(1, List.of(nodeConfig));
        activeConfigRef().set(config);
        when(blockNodeConfigService.latestConfiguration()).thenReturn(config);
        // indicate the buffer is healthy
        when(bufferService.latestBufferStatus()).thenReturn(new BlockBufferStatus(Instant.now(), 0.0D, false));
        // higher priority connection, stalled connection, and auto reset checks will be false since no active
        // connection exists

        // the following setup is to allow for the following node to be selected as the new active connection
        final BlockNode node = mock(BlockNode.class);
        when(node.configuration()).thenReturn(nodeConfig);
        when(node.isStreamingCandidate()).thenReturn(true);
        final Future<Object> nodeFuture = mock(Future.class);
        when(nodeFuture.state()).thenReturn(State.SUCCESS);
        when(nodeFuture.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 12));
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(nodeFuture));
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(20L);
        blockNodes().put(nodeConfig.streamingEndpoint(), node);

        final BlockNodeStreamingConnection newActiveConnection;
        try (final MockedConstruction<BlockNodeStreamingConnection> mockConnection =
                mockConstruction(BlockNodeStreamingConnection.class)) {
            invoke_updateConnectionIfNeeded();

            // one connection should have been created
            final List<BlockNodeStreamingConnection> createdConnections = mockConnection.constructed();
            assertThat(createdConnections).hasSize(1);

            newActiveConnection = createdConnections.getFirst();
        }

        assertThat(activeConnectionRef()).doesNotHaveNullValue().hasValue(newActiveConnection);
        verify(newActiveConnection).initialize();
        verify(newActiveConnection).updateConnectionState(ConnectionState.ACTIVE);
    }

    @Test
    void testUpdateConnectionIfNeeded_updatedConfigOnly() throws Throwable {
        // set active connection that is healthy
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig("localhost", 1234, 1);
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        when(activeConnection.configuration()).thenReturn(nodeConfig);
        when(activeConnection.heartbeatTimestamp())
                .thenReturn(Instant.now().plusSeconds(2).toEpochMilli());
        when(activeConnection.autoResetTimestamp()).thenReturn(Instant.now().plusSeconds(90));
        final BlockNode node = mock(BlockNode.class);
        when(node.configuration()).thenReturn(nodeConfig);
        when(node.isStreamingCandidate()).thenReturn(false);
        activeConnectionRef().set(activeConnection);
        // include in a lower priority node for fun
        final BlockNodeConfiguration lowerPriorityConfig = newBlockNodeConfig("localhost", 2345, 2);
        final BlockNode lowerPriorityNode = mock(BlockNode.class);
        when(lowerPriorityNode.configuration()).thenReturn(lowerPriorityConfig);
        when(lowerPriorityNode.isStreamingCandidate()).thenReturn(false);
        // set the current configuration
        final VersionedBlockNodeConfigurationSet currentConfigSet =
                new VersionedBlockNodeConfigurationSet(1, List.of(nodeConfig, lowerPriorityConfig));
        activeConfigRef().set(currentConfigSet);
        // mark buffer healthy
        when(bufferService.latestBufferStatus()).thenReturn(new BlockBufferStatus(Instant.now(), 0.0D, false));
        // create new config that removes everything
        when(blockNodeConfigService.latestConfiguration())
                .thenReturn(new VersionedBlockNodeConfigurationSet(2, List.of()));

        blockNodes().put(nodeConfig.streamingEndpoint(), node);
        blockNodes().put(lowerPriorityConfig.streamingEndpoint(), lowerPriorityNode);

        invoke_updateConnectionIfNeeded();

        verify(node).onTerminate(CloseReason.CONFIG_UPDATE);
        verify(lowerPriorityNode).onTerminate(CloseReason.CONFIG_UPDATE);
        // in the real world, when BlockNode#onTerminate(CloseReason) is called, it will close the connection
        // associated with the node, but since this is using mocks, those side effects aren't verifiable here
    }

    @Test
    void testUpdateConnectionIfNeeded_bufferUnhealthyOnly() throws Throwable {
        // set active connection that is healthy
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig("localhost", 1234, 1);
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        when(activeConnection.configuration()).thenReturn(nodeConfig);
        when(activeConnection.heartbeatTimestamp())
                .thenReturn(Instant.now().plusSeconds(2).toEpochMilli());
        when(activeConnection.autoResetTimestamp()).thenReturn(Instant.now().plusSeconds(90));
        final BlockNode node = mock(BlockNode.class);
        when(node.isStreamingCandidate()).thenReturn(false);
        activeConnectionRef().set(activeConnection);
        // set the latest config as the current config
        final VersionedBlockNodeConfigurationSet configSet =
                new VersionedBlockNodeConfigurationSet(1, List.of(nodeConfig));
        activeConfigRef().set(configSet);
        when(blockNodeConfigService.latestConfiguration()).thenReturn(configSet);
        // mark the buffer as unhealthy
        when(bufferService.latestBufferStatus()).thenReturn(new BlockBufferStatus(Instant.now(), 80.0D, true));

        blockNodes().put(nodeConfig.streamingEndpoint(), node);

        invoke_updateConnectionIfNeeded();

        // because there is only one block node, even though we are experiencing an unhealthy buffer we will not close
        // the active connection - having some sort of connection is better than nothing in this scenario
        verify(activeConnection, never()).closeAtBlockBoundary(any(CloseReason.class));
    }

    @Test
    void testUpdateConnectionIfNeeded_higherPriorityFoundOnly() throws Throwable {
        // set lower priority active connection
        final BlockNodeConfiguration lowerPriorityConfig = newBlockNodeConfig("localhost", 2345, 2);
        final BlockNodeStreamingConnection lowerPriorityConnection = mock(BlockNodeStreamingConnection.class);
        when(lowerPriorityConnection.configuration()).thenReturn(lowerPriorityConfig);
        // set the heartbeat to be now to pass the stall check
        when(lowerPriorityConnection.heartbeatTimestamp())
                .thenReturn(Instant.now().plusSeconds(2).toEpochMilli());
        // set the auto reset to be in the future to pass the auto reset check
        when(lowerPriorityConnection.autoResetTimestamp())
                .thenReturn(Instant.now().plusSeconds(90));
        final BlockNode lowerPriorityNode = mock(BlockNode.class);
        when(lowerPriorityNode.configuration()).thenReturn(lowerPriorityConfig);
        when(lowerPriorityNode.isStreamingCandidate()).thenReturn(false);
        activeConnectionRef().set(lowerPriorityConnection);
        // apply latest configuration
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig("localhost", 1234, 1);
        final VersionedBlockNodeConfigurationSet config =
                new VersionedBlockNodeConfigurationSet(1, List.of(nodeConfig));
        activeConfigRef().set(config);
        when(blockNodeConfigService.latestConfiguration()).thenReturn(config);
        // indicate the buffer is healthy
        when(bufferService.latestBufferStatus()).thenReturn(new BlockBufferStatus(Instant.now(), 0.0D, false));

        // the following setup is to allow for the following node to be selected as the new active connection
        final BlockNode node = mock(BlockNode.class);
        when(node.configuration()).thenReturn(nodeConfig);
        when(node.isStreamingCandidate()).thenReturn(true);
        final Future<Object> nodeFuture = mock(Future.class);
        when(nodeFuture.state()).thenReturn(State.SUCCESS);
        when(nodeFuture.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 12));
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(nodeFuture));
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(20L);

        blockNodes().put(nodeConfig.streamingEndpoint(), node);
        blockNodes().put(lowerPriorityNode.configuration().streamingEndpoint(), lowerPriorityNode);

        final BlockNodeStreamingConnection newActiveConnection;
        try (final MockedConstruction<BlockNodeStreamingConnection> mockConnection =
                mockConstruction(BlockNodeStreamingConnection.class)) {
            invoke_updateConnectionIfNeeded();

            // one connection should have been created
            final List<BlockNodeStreamingConnection> createdConnections = mockConnection.constructed();
            assertThat(createdConnections).hasSize(1);

            newActiveConnection = createdConnections.getFirst();
        }

        assertThat(activeConnectionRef()).doesNotHaveNullValue().hasValue(newActiveConnection);
        verify(newActiveConnection).initialize();
        verify(newActiveConnection).updateConnectionState(ConnectionState.ACTIVE);
        verify(lowerPriorityConnection).closeAtBlockBoundary(CloseReason.HIGHER_PRIORITY_FOUND);
    }

    @Test
    void testUpdateConnectionIfNeeded_activeConnectionStalledOnly() throws Throwable {
        // set active connection that is stalled
        final BlockNodeConfiguration existingActiveNodeConfig = newBlockNodeConfig("localhost", 1234, 1);
        final BlockNodeStreamingConnection existingActiveConnection = mock(BlockNodeStreamingConnection.class);
        when(existingActiveConnection.configuration()).thenReturn(existingActiveNodeConfig);
        // set the last heartbeat to be in the distant past to trigger the stall check
        when(existingActiveConnection.heartbeatTimestamp())
                .thenReturn(Instant.now().minusSeconds(2).toEpochMilli());
        when(existingActiveConnection.autoResetTimestamp())
                .thenReturn(Instant.now().plusSeconds(90));
        final BlockNode existingActiveNode = mock(BlockNode.class);
        when(existingActiveNode.isStreamingCandidate()).thenReturn(false);
        activeConnectionRef().set(existingActiveConnection);
        // mark the buffer as healthy
        when(bufferService.latestBufferStatus()).thenReturn(new BlockBufferStatus(Instant.now(), 0.0D, false));
        // add another block node to switch to
        final BlockNodeConfiguration otherNodeConfig = newBlockNodeConfig("localhost", 2345, 1);
        final BlockNode otherNode = mock(BlockNode.class);
        when(otherNode.configuration()).thenReturn(otherNodeConfig);
        when(otherNode.isStreamingCandidate()).thenReturn(true);
        final Future<Object> otherNodeFuture = mock(Future.class);
        when(otherNodeFuture.state()).thenReturn(State.SUCCESS);
        when(otherNodeFuture.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 12));
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(otherNodeFuture));
        // set the latest config as the current config
        final VersionedBlockNodeConfigurationSet configSet =
                new VersionedBlockNodeConfigurationSet(1, List.of(existingActiveNodeConfig, otherNodeConfig));
        activeConfigRef().set(configSet);
        when(blockNodeConfigService.latestConfiguration()).thenReturn(configSet);
        // other setup
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(20L);

        blockNodes().put(existingActiveNodeConfig.streamingEndpoint(), existingActiveNode);
        blockNodes().put(otherNodeConfig.streamingEndpoint(), otherNode);

        final BlockNodeStreamingConnection newActiveConnection;
        try (final MockedConstruction<BlockNodeStreamingConnection> mockConnection =
                mockConstruction(BlockNodeStreamingConnection.class)) {
            invoke_updateConnectionIfNeeded();

            // one connection should have been created
            final List<BlockNodeStreamingConnection> createdConnections = mockConnection.constructed();
            assertThat(createdConnections).hasSize(1);

            newActiveConnection = createdConnections.getFirst();
        }

        assertThat(activeConnectionRef()).doesNotHaveNullValue().hasValue(newActiveConnection);
        verify(newActiveConnection).initialize();
        verify(newActiveConnection).updateConnectionState(ConnectionState.ACTIVE);
        verify(existingActiveConnection).closeAtBlockBoundary(CloseReason.CONNECTION_STALLED);
    }

    @Test
    void testUpdateConnectionIfNeeded_activeConnectionAutoResetOnly() throws Throwable {
        // set active connection that is ready for auto reset
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig("localhost", 1234, 1);
        final BlockNodeStreamingConnection existingActiveConnection = mock(BlockNodeStreamingConnection.class);
        when(existingActiveConnection.configuration()).thenReturn(nodeConfig);
        when(existingActiveConnection.heartbeatTimestamp())
                .thenReturn(Instant.now().plusSeconds(1).toEpochMilli());
        // set the auto reset timestamp to be in the past so it trigger the auto reset check
        when(existingActiveConnection.autoResetTimestamp())
                .thenReturn(Instant.now().minusSeconds(90));
        final BlockNode node = mock(BlockNode.class);
        when(node.configuration()).thenReturn(nodeConfig);
        when(node.isStreamingCandidate()).thenReturn(true); // an auto reset does not make a node non-streamable
        activeConnectionRef().set(existingActiveConnection);
        // mark the buffer as healthy
        when(bufferService.latestBufferStatus()).thenReturn(new BlockBufferStatus(Instant.now(), 0.0D, false));
        // we will attempt to re-connect to the same node, so make sure the setup accounts for this
        final Future<Object> nodeFuture = mock(Future.class);
        when(nodeFuture.state()).thenReturn(State.SUCCESS);
        when(nodeFuture.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 12));
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(nodeFuture));
        // set the latest config as the current config
        final VersionedBlockNodeConfigurationSet configSet =
                new VersionedBlockNodeConfigurationSet(1, List.of(nodeConfig));
        activeConfigRef().set(configSet);
        when(blockNodeConfigService.latestConfiguration()).thenReturn(configSet);
        // other setup
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(20L);

        blockNodes().put(nodeConfig.streamingEndpoint(), node);

        final BlockNodeStreamingConnection newActiveConnection;
        try (final MockedConstruction<BlockNodeStreamingConnection> mockConnection =
                mockConstruction(BlockNodeStreamingConnection.class)) {
            invoke_updateConnectionIfNeeded();

            // one connection should have been created
            final List<BlockNodeStreamingConnection> createdConnections = mockConnection.constructed();
            assertThat(createdConnections).hasSize(1);

            newActiveConnection = createdConnections.getFirst();
        }

        assertThat(activeConnectionRef()).doesNotHaveNullValue().hasValue(newActiveConnection);
        verify(newActiveConnection).initialize();
        verify(newActiveConnection).updateConnectionState(ConnectionState.ACTIVE);
        verify(existingActiveConnection, times(2)).closeAtBlockBoundary(CloseReason.PERIODIC_RESET);
    }

    @Test
    void testUpdateConnectionIfNeeded_noUpdateNeeded() throws Throwable {
        // set active connection that is healthy
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig("localhost", 1234, 1);
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        when(activeConnection.configuration()).thenReturn(nodeConfig);
        when(activeConnection.heartbeatTimestamp())
                .thenReturn(Instant.now().plusSeconds(2).toEpochMilli());
        when(activeConnection.autoResetTimestamp()).thenReturn(Instant.now().plusSeconds(90));
        final BlockNode node = mock(BlockNode.class);
        activeConnectionRef().set(activeConnection);
        // set the latest config as the current config
        final VersionedBlockNodeConfigurationSet configSet =
                new VersionedBlockNodeConfigurationSet(1, List.of(nodeConfig));
        activeConfigRef().set(configSet);
        when(blockNodeConfigService.latestConfiguration()).thenReturn(configSet);
        // mark the buffer as unhealthy
        when(bufferService.latestBufferStatus()).thenReturn(new BlockBufferStatus(Instant.now(), 0.0D, false));

        blockNodes().put(nodeConfig.streamingEndpoint(), node);

        invoke_updateConnectionIfNeeded();

        // the active connection should not be changed
        assertThat(activeConnectionRef()).doesNotHaveNullValue().hasValue(activeConnection);
    }

    @Test
    void testStart_streamingNotEnabled() {
        final ConfigProvider localConfigProvider = mock(ConfigProvider.class);
        final VersionedConfiguration versionedConfiguration = mock(VersionedConfiguration.class);
        final BlockStreamConfig bsConfig = mock(BlockStreamConfig.class);
        when(versionedConfiguration.getConfigData(BlockStreamConfig.class)).thenReturn(bsConfig);
        when(localConfigProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(bsConfig.streamToBlockNodes()).thenReturn(false);

        connectionManager = new BlockNodeConnectionManager(
                localConfigProvider, bufferService, metrics, blockingIoExecutorSupplier, blockNodeConfigService);
        connectionManager.start();

        assertThat(isConnectionManagerActive()).isFalse();
        assertThat(connectionMonitorThreadRef()).hasNullValue();

        verifyNoInteractions(bufferService);
        verifyNoInteractions(blockNodeConfigService);
    }

    @Test
    void testStart_alreadyStarted() {
        isConnectionManagerActive().set(true);

        connectionManager.start();

        verifyNoInteractions(bufferService);
        verifyNoInteractions(blockNodeConfigService);
    }

    @Test
    void testStart_monitorThreadAlreadyExists() {
        final Thread monitorThread = mock(Thread.class);
        connectionMonitorThreadRef().set(monitorThread);

        connectionManager.start();

        assertThat(isConnectionManagerActive()).isTrue();
        assertThat(connectionMonitorThreadRef()).doesNotHaveNullValue().hasValue(monitorThread);

        verify(bufferService).start();
        verify(blockNodeConfigService).start();
    }

    @Test
    void testStart() {
        connectionManager.start();

        assertThat(isConnectionManagerActive()).isTrue();
        assertThat(connectionMonitorThreadRef()).doesNotHaveNullValue();

        verify(bufferService).start();
        verify(blockNodeConfigService).start();
    }

    @Test
    void testShutdown_alreadyShutdown() {
        isConnectionManagerActive().set(false);

        connectionManager.shutdown();

        verify(bufferService, never()).shutdown();
        verify(blockNodeConfigService, never()).shutdown();
    }

    @Test
    void testShutdown() {
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        activeConnectionRef().set(connection);
        isConnectionManagerActive().set(true);
        final Thread monitorThread = mock(Thread.class);
        connectionMonitorThreadRef().set(monitorThread);
        final BlockNode node = mock(BlockNode.class);
        blockNodes().put(new BlockNodeEndpoint("localhost", 1234), node);

        connectionManager.shutdown();

        assertThat(activeConnectionRef()).hasNullValue();
        assertThat(isConnectionManagerActive()).isFalse();
        assertThat(connectionMonitorThreadRef()).hasNullValue();

        verify(node).onTerminate(CloseReason.SHUTDOWN);
        verify(bufferService).shutdown();
        verify(blockNodeConfigService).shutdown();
    }

    // Utilities

    void invoke_updateConnectionIfNeeded() throws Throwable {
        updateConnectionIfNeededHandle.invoke(connectionManager);
    }

    void invoke_selectNewBlockNode(final boolean force, final CloseReason closeReason) throws Throwable {
        selectNewBlockNodeHandle.invoke(connectionManager, force, closeReason);
    }

    void invoke_pruneNodes() throws Throwable {
        pruneNodesHandle.invoke(connectionManager);
    }

    BlockNode invoke_getNextPriorityBlockNode(final List<BlockNode> availableBlockNodes) throws Throwable {
        return (BlockNode) getNextPriorityBlockNodeHandle.invoke(connectionManager, availableBlockNodes);
    }

    GroupSelectionOutcome invoke_findAvailableNode(final List<BlockNode> nodes) throws Throwable {
        return (GroupSelectionOutcome) findAvailableNodeHandle.invoke(connectionManager, nodes);
    }

    boolean invoke_isMissingActiveConnection(final BlockNodeStreamingConnection activeConnection) throws Throwable {
        return (boolean) isMissingActiveConnectionHandle.invoke(connectionManager, activeConnection);
    }

    boolean invoke_isConfigUpdated() throws Throwable {
        return (boolean) isConfigUpdatedHandle.invoke(connectionManager);
    }

    boolean invoke_isBufferUnhealthy() throws Throwable {
        return (boolean) isBufferUnhealthyHandle.invoke(connectionManager);
    }

    boolean invoke_isHigherPriorityNodeAvailable(final BlockNodeStreamingConnection activeConnection) throws Throwable {
        return (boolean) isHigherPriorityNodeAvailableHandle.invoke(connectionManager, activeConnection);
    }

    boolean invoke_isActiveConnectionStalled(final Instant now, final BlockNodeStreamingConnection activeConnection)
            throws Throwable {
        return (boolean) isActiveConnectionStalledHandle.invoke(connectionManager, now, activeConnection);
    }

    boolean invoke_isActiveConnectionAutoReset(final Instant now, final BlockNodeStreamingConnection activeConnection)
            throws Throwable {
        return (boolean) isActiveConnectionAutoResetHandle.invoke(connectionManager, now, activeConnection);
    }

    @SuppressWarnings("unchecked")
    AtomicReference<Thread> connectionMonitorThreadRef() {
        return (AtomicReference<Thread>) connectionMonitorThreadRefHandle.get(connectionManager);
    }

    @SuppressWarnings("unchecked")
    AtomicReference<VersionedBlockNodeConfigurationSet> activeConfigRef() {
        return (AtomicReference<VersionedBlockNodeConfigurationSet>) activeConfigRefHandle.get(connectionManager);
    }

    @SuppressWarnings("unchecked")
    AtomicReference<Instant> globalCoolDownTimestampRef() {
        return (AtomicReference<Instant>) globalCoolDownTimestampRefHandle.get(connectionManager);
    }

    @SuppressWarnings("unchecked")
    AtomicReference<BlockBufferStatus> bufferStatusRef() {
        return (AtomicReference<BlockBufferStatus>) bufferStatusRefHandle.get(connectionManager);
    }

    @SuppressWarnings("unchecked")
    AtomicReference<BlockNodeStreamingConnection> activeConnectionRef() {
        return (AtomicReference<BlockNodeStreamingConnection>) activeConnectionRefHandle.get(connectionManager);
    }

    @SuppressWarnings("unchecked")
    ConcurrentMap<BlockNodeEndpoint, BlockNode> blockNodes() {
        return (ConcurrentMap<BlockNodeEndpoint, BlockNode>) blockNodesHandle.get(connectionManager);
    }

    AtomicInteger globalActiveStreamingConnectionCount() {
        return (AtomicInteger) globalActiveStreamingConnectionCountHandle.get(connectionManager);
    }

    AtomicBoolean isConnectionManagerActive() {
        return (AtomicBoolean) isConnectionManagerActiveHandle.get(connectionManager);
    }

    void resetMocks() {
        reset(bufferService, metrics, blockNodeConfigService);
    }
}
